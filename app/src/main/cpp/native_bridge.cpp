#include <android/log.h>
#include <jni.h>
#include <string>
#include <thread>
#include <chrono>
#include <fstream>
#include <atomic>
#include <sys/uio.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <cstdlib>
#include <cstdio>
#include <cmath>
#include <vector>
#include <sstream>
#include <cstring>
#include <cerrno>
#include <algorithm>
#include <mutex>
#include <unordered_map>
#include <dirent.h>

#define LOG_TAG "NativeBridge"
#ifdef NDEBUG
#define LOGI(...) ((void)0)
#define LOGD(...) ((void)0)
#define LOGW(...) ((void)0)
#define LOGE(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#endif

extern void start_anti_frida();

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGI("JNI_OnLoad: JavaVM registrada");
    start_anti_frida();
    return JNI_VERSION_1_6;
}

// ==============================================================================================
// [VARIABLES GLOBALES]
// ==============================================================================================

static std::atomic<int> g_game_pid{-1};
static std::atomic<bool> g_aimbot_active{true};
static std::atomic<bool> g_aimbot_running{false};
static std::thread g_aimbot_thread;

// Sniper Scope (aim-assist) estado
static std::atomic<bool> g_sniper_active{false};
static std::atomic<int> g_sniper_mode{0};                // 0 = Cabeza, 1 = Cuerpo
static std::atomic<bool> g_sniper_ignore_knocked{true};
static std::atomic<bool> g_sniper_ignore_bots{false};
static std::atomic<int> g_screen_w{1080};
static std::atomic<int> g_screen_h{1920};
static const float SNIPER_FOV_PX = 200.0f;
static const float SNIPER_MAX_DIST = 800.0f;
static const float AIM_DEADZONE_PX = 6.0f;   // no re-apuntar si ya está dentro de este radio

// Caché de la base de libil2cpp.so por PID (evita su -c cat en cada iteración del loop)
static std::atomic<int> g_base_pid{-1};
static std::atomic<long> g_game_base{0};

// Free Fire se ejecuta como proceso de 32 bits (armeabi-v7a).
static std::atomic<int> g_ptr_width{4};
// ==============================================================================================
// [ROOT MEM IO - HELPER PERSISTENTE]
// ==============================================================================================
// Lanza `su -c ffmem <pid>` una sola vez y habla con él por pipes. Cada lectura/escritura
// es un pread/pwrite del helper (microsegundos) en lugar de popen("su -c dd") (decenas de ms).

static std::string g_helper_path;
static std::mutex g_io_mutex;
static FILE* g_io_in = nullptr;    // stdout del helper (lectura)
static FILE* g_io_out = nullptr;   // stdin del helper (escritura)
static int g_io_pid = -1;

bool ensureHelperSpawned(int pid);

bool rootMemIOSpawn(int pid) {
    std::lock_guard<std::mutex> lock(g_io_mutex);
    if (g_io_in && g_io_out && g_io_pid == pid) return true;

    if (g_io_in) { fclose(g_io_in); g_io_in = nullptr; }
    if (g_io_out) { fclose(g_io_out); g_io_out = nullptr; }
    g_io_pid = -1;

    if (g_helper_path.empty() || pid <= 0) return false;

    int in_pipe[2], out_pipe[2];
    if (pipe(in_pipe) != 0 || pipe(out_pipe) != 0) return false;

    pid_t child = fork();
    if (child < 0) {
        close(in_pipe[0]); close(in_pipe[1]);
        close(out_pipe[0]); close(out_pipe[1]);
        return false;
    }
    if (child == 0) {
        dup2(in_pipe[0], 0);
        dup2(out_pipe[1], 1);
        close(in_pipe[0]); close(in_pipe[1]);
        close(out_pipe[0]); close(out_pipe[1]);
        std::string cmd = "su -c '" + g_helper_path + " " + std::to_string(pid) + "'";
        execl("/system/bin/sh", "sh", "-c", cmd.c_str(), (char*)nullptr);
        _exit(127);
    }
    close(in_pipe[0]);
    close(out_pipe[1]);
    g_io_out = fdopen(in_pipe[1], "w");
    g_io_in = fdopen(out_pipe[0], "r");
    if (!g_io_in || !g_io_out) {
        if (g_io_in) { fclose(g_io_in); g_io_in = nullptr; }
        if (g_io_out) { fclose(g_io_out); g_io_out = nullptr; }
        return false;
    }
    g_io_pid = pid;
    LOGI("[FREEZY] Helper ffmem lanzado (PID %d)", pid);
    return true;
}

// Lee directamente del helper (rápido). Devuelve false si no hay helper activo.
bool rootMemIOReadDirect(int pid, long address, void* buffer, size_t size) {
    if (pid != g_io_pid || !g_io_in || !g_io_out || size == 0 || size > 16384) return false;
    std::lock_guard<std::mutex> lock(g_io_mutex);
    if (!g_io_out || !g_io_in || pid != g_io_pid) return false;

    if (fprintf(g_io_out, "R %llx %zx\n", (unsigned long long)address, size) < 0) {
        if (g_io_in) fclose(g_io_in);
        if (g_io_out) fclose(g_io_out);
        g_io_in = nullptr;
        g_io_out = nullptr;
        g_io_pid = -1;
        return false;
    }
    fflush(g_io_out);

    std::string hex(1 + size * 2 + 8, '\0');
    if (fgets(hex.data(), (int)hex.size(), g_io_in) == nullptr) {
        if (g_io_in) fclose(g_io_in);
        if (g_io_out) fclose(g_io_out);
        g_io_in = nullptr;
        g_io_out = nullptr;
        g_io_pid = -1;
        return false;
    }
    if (hex.compare(0, 3, "ERR") == 0) return false;
    uint8_t* buf = static_cast<uint8_t*>(buffer);
    for (size_t i = 0; i < size; i++) {
        unsigned int byte = 0;
        if (sscanf(hex.data() + i * 2, "%2x", &byte) != 1) return false;
        buf[i] = (uint8_t)byte;
    }
    return true;
}

// Lee múltiples regiones de memoria en 1 sola syscall al kernel (Batch I/O Vectorial)
bool rootMemIOReadVectorDirect(int pid, const long* addresses, const size_t* sizes, void** outBuffers, int count) {
    if (pid != g_io_pid || !g_io_in || !g_io_out || count <= 0 || count > 64) return false;
    std::lock_guard<std::mutex> lock(g_io_mutex);
    if (!g_io_out || !g_io_in || pid != g_io_pid) return false;

    std::string cmd = "V ";
    cmd += std::to_string(count);
    size_t totalBytes = 0;
    for (int i = 0; i < count; i++) {
        char tmp[64];
        snprintf(tmp, sizeof(tmp), " %llx %zx", (unsigned long long)addresses[i], sizes[i]);
        cmd += tmp;
        totalBytes += sizes[i];
    }
    cmd += '\n';

    if (fputs(cmd.c_str(), g_io_out) < 0) {
        if (g_io_in) fclose(g_io_in);
        if (g_io_out) fclose(g_io_out);
        g_io_in = nullptr;
        g_io_out = nullptr;
        g_io_pid = -1;
        return false;
    }
    fflush(g_io_out);

    std::string hex(1 + totalBytes * 2 + 16, '\0');
    if (fgets(hex.data(), (int)hex.size(), g_io_in) == nullptr) {
        if (g_io_in) fclose(g_io_in);
        if (g_io_out) fclose(g_io_out);
        g_io_in = nullptr;
        g_io_out = nullptr;
        g_io_pid = -1;
        return false;
    }
    if (hex.compare(0, 3, "ERR") == 0) return false;

    size_t hexOffset = 0;
    for (int i = 0; i < count; i++) {
        uint8_t* buf = static_cast<uint8_t*>(outBuffers[i]);
        for (size_t b = 0; b < sizes[i]; b++) {
            unsigned int byteVal = 0;
            if (sscanf(hex.data() + hexOffset, "%2x", &byteVal) != 1) return false;
            buf[b] = (uint8_t)byteVal;
            hexOffset += 2;
        }
    }
    return true;
}

// Resuelve la dirección base de un módulo (.so) a través del helper stealth en memoria.
bool rootMemIOGetModuleBase(int pid, const char* modName, long& outBase, int& outPtrWidth) {
    outBase = 0;
    outPtrWidth = 4;
    ensureHelperSpawned(pid);
    if (pid != g_io_pid || !g_io_in || !g_io_out) return false;
    std::lock_guard<std::mutex> lock(g_io_mutex);
    if (!g_io_out || !g_io_in || pid != g_io_pid) return false;

    if (fprintf(g_io_out, "B %s\n", modName) < 0) {
        if (g_io_in) fclose(g_io_in);
        if (g_io_out) fclose(g_io_out);
        g_io_in = nullptr;
        g_io_out = nullptr;
        g_io_pid = -1;
        return false;
    }
    fflush(g_io_out);

    char resp[128] = {0};
    if (fgets(resp, sizeof(resp), g_io_in) == nullptr) {
        if (g_io_in) fclose(g_io_in);
        if (g_io_out) fclose(g_io_out);
        g_io_in = nullptr;
        g_io_out = nullptr;
        g_io_pid = -1;
        return false;
    }
    if (strncmp(resp, "ERR", 3) == 0) return false;

    unsigned long long base = 0;
    int ptrW = 4;
    if (sscanf(resp, "%llx %d", &base, &ptrW) >= 1 && base > 0) {
        outBase = (long)base;
        outPtrWidth = ptrW;
        return true;
    }
    return false;
}

// ==============================================================================================
// [OFFSETS DEL DUMP - CADENA REAL IL2CPP (32-BIT, Free Fire)]
// ==============================================================================================

// Cadena raíz: libil2cpp.so + InitBase -> facade -> +StaticClass -> staticFacade -> currentGame
const long OFF_INIT_BASE = 0xA986E9C;
const long OFF_STATIC_CLASS = 0x5C;
const long OFF_CURRENT_MATCH = 0x50;
const long OFF_LOCAL_PLAYER = 0x94;
const long OFF_DICT_ENTITIES = 0x68;

// Player
const long OFF_PLAYER_IS_DEAD = 0x50;
const long OFF_PLAYER_DATA = 0x48;
const long OFF_PLAYER_NAME = 0x2DC;
const long OFF_AVATAR_MANAGER = 0x4C0;
const long OFF_AVATAR = 0xA8;
const long OFF_AVATAR_IS_VISIBLE = 0x95;
const long OFF_AVATAR_DATA = 0x14;
const long OFF_AVATAR_DATA_IS_TEAM = 0x59;
const long OFF_IS_CLIENT_BOT = 0x2E4;
const long OFF_SHADOW_BASE = 0x18B8;
const long OFF_XPOSE = 0x78;
const long OFF_WEAPON = 0x3F4;
const long OFF_WEAPON_ITEM_ID = 0x494;

// Camera
const long OFF_FOLLOW_CAMERA = 0x450;
const long OFF_CAMERA = 0x18;
const long OFF_CAMERA_BASE = 0x8;
const long OFF_VIEW_MATRIX = 0xE8;
const long OFF_MAIN_CAMERA_TRANSFORM = 0x24C;
const long OFF_AIM_ROTATION = 0x400;

// Aimbot
const long OFF_COLLIDER = 0x4A4;
const long OFF_LOCKED_AIMING_COLLIDER = 0x54;

// Huesos (Bones enum)
const long OFF_BONE_HEAD = 0x458;
const long OFF_BONE_NECK = 0x460;
const long OFF_BONE_HIP = 0x45C;
const long OFF_BONE_ROOT = 0x46C;
const long OFF_BONE_GROIN = 0x468;
const long OFF_BONE_LEFT_SHOULDER = 0x48C;
const long OFF_BONE_RIGHT_SHOULDER = 0x490;
const long OFF_BONE_LEFT_ELBOW = 0x4A0;
const long OFF_BONE_RIGHT_ELBOW = 0x49C;
const long OFF_BONE_LEFT_WRIST = 0x498;
const long OFF_BONE_RIGHT_WRIST = 0x494;
const long OFF_BONE_LEFT_ANKLE = 0x474;
const long OFF_BONE_RIGHT_ANKLE = 0x478;
const long OFF_BONE_LEFT_FOOT = 0x47C;
const long OFF_BONE_RIGHT_FOOT = 0x480;

// Orden de huesos del esqueleto (índice = posición en el array "skel" del snapshot)
const long SKELETON_BONES[14] = {
    OFF_BONE_HEAD,           //  0 cabeza
    OFF_BONE_NECK,           //  1 cuello
    OFF_BONE_HIP,            //  2 cadera
    OFF_BONE_GROIN,          //  3 ingle
    OFF_BONE_LEFT_SHOULDER,  //  4 hombro izq
    OFF_BONE_RIGHT_SHOULDER, //  5 hombro der
    OFF_BONE_LEFT_ELBOW,     //  6 codo izq
    OFF_BONE_RIGHT_ELBOW,    //  7 codo der
    OFF_BONE_LEFT_WRIST,     //  8 muñeca izq
    OFF_BONE_RIGHT_WRIST,    //  9 muñeca der
    OFF_BONE_LEFT_ANKLE,     // 10 tobillo izq
    OFF_BONE_RIGHT_ANKLE,    // 11 tobillo der
    OFF_BONE_LEFT_FOOT,      // 12 pie izq
    OFF_BONE_RIGHT_FOOT,     // 13 pie der
};

// Estructura del Dictionary en IL2CPP (dump real)
const long OFF_DICT_COUNT = 0x10;
const long OFF_DICT_ENTRIES_PTR = 0xC;
const long OFF_DICT_START = 0x10;      // inicio del buffer de entradas
const long OFF_ENTRY_HASH = 0x0;
const long OFF_ENTRY_ENTITY = 0xC;

// ==============================================================================================
// [FUNCIONES DE MEMORIA CON ROOT]
// ==============================================================================================

bool readGameMemoryRoot(int pid, long address, void* buffer, size_t size) {
    if (pid <= 0 || address <= 0 || size == 0) return false;
    
    // dd con bs=1: el skip es byte exacto, funciona con direcciones no alineadas
    std::string cmd = "su -c 'dd if=/proc/" + std::to_string(pid) + "/mem bs=1 skip=" + 
                      std::to_string(address) + " count=" + std::to_string(size) + " 2>/dev/null'";
    FILE* fp = popen(cmd.c_str(), "r");
    if (!fp) {
        LOGE("[FREEZY] readGameMemoryRoot: popen falló");
        return false;
    }
    size_t read = fread(buffer, 1, size, fp);
    int rc = pclose(fp);
    if (read != size) {
        LOGD("[FREEZY] readGameMemoryRoot: FALLO pid=%d addr=0x%llx size=%zu read=%zu rc=%d",
             pid, (unsigned long long)address, size, read, rc);
    }
    return read == size;
}

// Intentar lanzar el helper persistente una sola vez por PID (evita spam de su).
static std::atomic<int> g_helper_tried_pid{0};
bool ensureHelperSpawned(int pid) {
    if (pid <= 0) return false;
    if (g_helper_path.empty()) return false;
    if (g_io_in && g_io_out && g_io_pid == pid) return true;
    if (g_helper_tried_pid.load() == pid) return false;   // ya se intentó y falló
    if (rootMemIOSpawn(pid)) return true;
    g_helper_tried_pid = pid;
    return false;
}

bool readGameMemory(int pid, long address, void* buffer, size_t size) {
    if (pid <= 0 || address <= 0 || size == 0) return false;

    // 0) Lanzar el helper persistente una vez por PID (una única petición root).
    ensureHelperSpawned(pid);

    // 1) Helper persistente (rápido, root)
    if (rootMemIOReadDirect(pid, address, buffer, size)) return true;
    
    // 2) Intentar process_vm_readv primero
    struct iovec local_iov = { buffer, size };
    struct iovec remote_iov = { reinterpret_cast<void*>(address), size };
    ssize_t result = syscall(__NR_process_vm_readv, pid, &local_iov, 1, &remote_iov, 1, 0);
    
    if (result == static_cast<ssize_t>(size)) return true;
    
    // 3) Fallback con root (popen dd)
    return readGameMemoryRoot(pid, address, buffer, size);
}

bool writeGameMemory(int pid, long address, const void* buffer, size_t size) {
    // Modo 100% Solo Lectura: Escritura deshabilitada para seguridad anti-ban.
    return false;
}

// ==============================================================================================
// [FUNCIÓN PARA OBTENER LA BASE DEL JUEGO - CORREGIDA PARA 64 BITS]
// ==============================================================================================

// Lee /proc/<pid>/maps directamente o vía su si SELinux bloquea cross-app.
bool readProcessMaps(int pid, std::string& content) {
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    std::ifstream f(mapsPath);
    if (f.is_open()) {
        std::stringstream ss;
        ss << f.rdbuf();
        content = ss.str();
        if (!content.empty()) return true;
    }
    std::string cmd = "su -c 'cat " + mapsPath + " 2>/dev/null'";
    FILE* fp = popen(cmd.c_str(), "r");
    if (!fp) return false;
    char buf[8192];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), fp)) > 0) {
        content.append(buf, n);
    }
    pclose(fp);
    return !content.empty();
}

long getGameBase(int pid) {
    if (pid <= 0) return 0;
    if (g_base_pid.load() == pid && g_game_base.load() != 0) {
        return g_game_base.load();
    }

    // 1. Método primario Stealth: Resolver base directamente por el helper en RAM
    long base = 0;
    int ptrW = 4;
    if (rootMemIOGetModuleBase(pid, "libil2cpp.so", base, ptrW) && base > 0) {
        g_ptr_width = ptrW;
        g_base_pid = pid;
        g_game_base = base;
        LOGI("[FREEZY] getGameBase (via stealth helper): libil2cpp.so en 0x%llx ptr_width=%d",
             (unsigned long long)base, ptrW);
        return base;
    }

    // 2. Fallback de lectura de maps
    std::string content;
    if (readProcessMaps(pid, content)) {
        size_t pos = 0;
        while (pos < content.size()) {
            size_t eol = content.find('\n', pos);
            if (eol == std::string::npos) eol = content.size();
            std::string line = content.substr(pos, eol - pos);
            pos = eol + 1;

            if (line.find("libil2cpp.so") != std::string::npos) {
                if (line.find("/arm64") != std::string::npos || line.find("arm64-v8a") != std::string::npos) {
                    g_ptr_width = 8;
                } else {
                    g_ptr_width = 4;
                }
                size_t dash = line.find('-');
                if (dash != std::string::npos) {
                    std::string addrStr = line.substr(0, dash);
                    try {
                        base = std::stoull(addrStr, nullptr, 16);
                        g_base_pid = pid;
                        g_game_base = base;
                        return base;
                    } catch (...) {}
                }
            }
        }
    }
    LOGE("[FREEZY] getGameBase: no se pudo obtener base de libil2cpp.so");
    return 0;
}

// Registra un resumen de las librerías .so mapeadas en el proceso
void logMappedLibs(int pid, const char* context) {
    std::string content;
    if (!readProcessMaps(pid, content)) return;

    int lines = 0;
    int soLines = 0;
    std::vector<std::string> soNames;
    std::string line;
    size_t pos = 0;
    while (pos < content.size()) {
        size_t eol = content.find('\n', pos);
        if (eol == std::string::npos) eol = content.size();
        line = content.substr(pos, eol - pos);
        pos = eol + 1;

        lines++;
        if (line.find(".so") != std::string::npos) {
            soLines++;
            size_t slash = line.find_last_of('/');
            if (slash != std::string::npos) {
                std::string name = line.substr(slash + 1);
                size_t sp = name.find(' ');
                if (sp != std::string::npos) name = name.substr(0, sp);
                if (std::find(soNames.begin(), soNames.end(), name) == soNames.end()) {
                    soNames.push_back(name);
                }
            }
        }
    }

    LOGI("[FREEZY] [%s] maps: %d líneas, %d con .so, %zu libs distintas",
         context, lines, soLines, soNames.size());
}

// ==============================================================================================
// [FUNCIÓN PARA ENCONTRAR EL PID]
// ==============================================================================================

int findGamePidNative() {
    const char* PACKAGES[] = {
        "com.dts.freefireth",
        "com.dts.freefiremax",
        "com.dts.freefire"
    };

    // 1. Intento directo por /proc (si los permisos SELinux lo permiten)
    DIR* dir = opendir("/proc");
    if (dir) {
        struct dirent* entry;
        while ((entry = readdir(dir)) != nullptr) {
            char* endptr = nullptr;
            long pid = strtol(entry->d_name, &endptr, 10);
            if (*endptr != '\0' || pid <= 0) continue;

            char cmdPath[64];
            snprintf(cmdPath, sizeof(cmdPath), "/proc/%ld/cmdline", pid);
            FILE* fp = fopen(cmdPath, "r");
            if (!fp) continue;
            char cmdline[128] = {0};
            size_t n = fread(cmdline, 1, sizeof(cmdline) - 1, fp);
            fclose(fp);
            if (n > 0) {
                for (int i = 0; i < 3; i++) {
                    if (strstr(cmdline, PACKAGES[i]) != nullptr) {
                        closedir(dir);
                        LOGI("[FREEZY] PID encontrado (/proc): %ld (%s)", pid, PACKAGES[i]);
                        return (int)pid;
                    }
                }
            }
        }
        closedir(dir);
    }

    // 2. Fallback rápido y confiable con pidof
    for (int i = 0; i < 3; i++) {
        std::string cmd = "su -c 'pidof " + std::string(PACKAGES[i]) + " 2>/dev/null'";
        FILE* fp = popen(cmd.c_str(), "r");
        if (fp) {
            char buffer[64] = {0};
            if (fgets(buffer, sizeof(buffer), fp) != nullptr) {
                int pid = atoi(buffer);
                pclose(fp);
                if (pid > 0) {
                    LOGI("[FREEZY] PID encontrado (pidof): %d (%s)", pid, PACKAGES[i]);
                    return pid;
                }
            } else {
                pclose(fp);
            }
        }
    }

    return -1;
}

// ==============================================================================================
// [RESOLVER PUNTEROS Y POSICIONES - CADENA REAL IL2CPP]
// ==============================================================================================

// Heurística para descartar punteros basura ANTES de lanzar lecturas costosas.
// El rango depende del ancho de puntero configurado (32 o 64 bits).
inline bool isPlausiblePtr(long p) {
    uintptr_t u = (uintptr_t)p;
    if (g_ptr_width.load() == 4) {
        return u >= 0x10000 && u <= 0xFFFFFFFFU && (u & 3) == 0;
    }
    return u >= 0x10000 && u < 0x7fffffffffffULL && (u & 7) == 0;
}

bool readPtr(int pid, long addr, long& out) {
    if (g_ptr_width.load() == 4) {
        uint32_t v = 0;
        if (!readGameMemory(pid, addr, &v, 4)) return false;
        out = (long)v;
        return true;
    }
    return readGameMemory(pid, addr, &out, 8);
}

bool readU8(int pid, long addr, uint8_t& out) {
    return readGameMemory(pid, addr, &out, 1);
}
bool readI32(int pid, long addr, int& out) {
    return readGameMemory(pid, addr, &out, 4);
}
bool readU32(int pid, long addr, uint32_t& out) {
    return readGameMemory(pid, addr, &out, 4);
}
bool readF32(int pid, long addr, float& out) {
    return readGameMemory(pid, addr, &out, 4);
}

struct GamePointers {
    long base = 0;
    long facade = 0;
    long staticFacade = 0;
    long currentGame = 0;
    long currentMatch = 0;
    long localPlayer = 0;
    bool valid = false;
};

// Camina la cadena: libil2cpp + InitBase -> baseGameFacade -> gameFacade
//                 -> +StaticClass -> staticGameFacade -> currentGame
//                 -> +CurrentMatch -> currentMatch -> +LocalPlayer -> localPlayer
// Devuelve false y registra el paso que falló para poder ajustar offsets por build.
bool resolveGamePointers(int pid, GamePointers& gp) {
    gp.valid = false;
    gp.base = getGameBase(pid);
    if (gp.base == 0) { LOGD("[FREEZY] chain: falló getGameBase"); return false; }

    if (!readPtr(pid, gp.base + OFF_INIT_BASE, gp.facade) || !isPlausiblePtr(gp.facade)) {
        LOGD("[FREEZY] chain: falló baseGameFacade (base+0x%llx) = 0x%llx",
             (unsigned long long)OFF_INIT_BASE, (unsigned long long)gp.facade);
        return false;
    }
    long gameFacade = 0;
    if (!readPtr(pid, gp.facade, gameFacade) || !isPlausiblePtr(gameFacade)) {
        LOGD("[FREEZY] chain: falló gameFacade (*baseGameFacade)");
        return false;
    }
    if (!readPtr(pid, gameFacade + OFF_STATIC_CLASS, gp.staticFacade) || !isPlausiblePtr(gp.staticFacade)) {
        LOGD("[FREEZY] chain: falló staticFacade (gameFacade+0x%llx)", (unsigned long long)OFF_STATIC_CLASS);
        return false;
    }
    if (!readPtr(pid, gp.staticFacade, gp.currentGame) || !isPlausiblePtr(gp.currentGame)) {
        LOGD("[FREEZY] chain: falló currentGame (staticFacade+0x0)");
        return false;
    }
    if (!readPtr(pid, gp.currentGame + OFF_CURRENT_MATCH, gp.currentMatch)) {
        LOGD("[FREEZY] chain: falló lectura currentMatch (currentGame+0x%llx)", (unsigned long long)OFF_CURRENT_MATCH);
        return false;
    }
    if (!isPlausiblePtr(gp.currentMatch)) {
        LOGD("[FREEZY] chain: currentMatch=0x%llx (¿estás en partida?)",
             (unsigned long long)gp.currentMatch);
        return false;
    }
    if (!readPtr(pid, gp.currentMatch + OFF_LOCAL_PLAYER, gp.localPlayer) || !isPlausiblePtr(gp.localPlayer)) {
        LOGD("[FREEZY] chain: falló localPlayer (currentMatch+0x%llx)", (unsigned long long)OFF_LOCAL_PLAYER);
        return false;
    }
    gp.valid = true;
    return true;
}

// TMatrix del motor Unity: position(Vector4) + rotation(Quaternion) + scale(Vector4) = 0x30 bytes.
struct TMatrix {
    float position[4];
    float rotation[4];
    float scale[4];
};

// Posición mundial de un nodo transform (port de Transform.cs del dump de referencia).
// Optimizado: la cadena de ancestros se cachea por índice (la jerarquía es estable),
// y la jerarquía matrixList/matrixIndices se cachea por PID.
bool getTransformPosition(int pid, long transform, float* outPos) {
    long transformObj = 0;
    if (!readPtr(pid, transform + 0x8, transformObj) || !isPlausiblePtr(transformObj)) return false;

    uint32_t index = 0;
    long matrix = 0;
    if (!readU32(pid, transformObj + 0x24, index)) return false;
    if (!readPtr(pid, transformObj + 0x20, matrix) || !isPlausiblePtr(matrix)) return false;

    long matrixList = 0, matrixIndices = 0;
    if (!readPtr(pid, matrix + 0x18, matrixList) || !isPlausiblePtr(matrixList)) return false;
    if (!readPtr(pid, matrix + 0x1C, matrixIndices) || !isPlausiblePtr(matrixIndices)) return false;

    float result[3] = {0, 0, 0};
    if (!readGameMemory(pid, matrixList + (long)index * 0x30, result, 12)) return false;

    int transformIndex = 0;
    if (!readI32(pid, matrixIndices + (long)index * 4, transformIndex)) return false;

    int tries = 0;
    const int maxTries = 20;
    while (transformIndex >= 0 && tries < maxTries) {
        tries++;
        TMatrix tm;
        if (!readGameMemory(pid, matrixList + (long)transformIndex * 0x30, &tm, sizeof(TMatrix))) return false;

        float rx = tm.rotation[0], ry = tm.rotation[1], rz = tm.rotation[2], rw = tm.rotation[3];
        float scaleX = result[0] * tm.scale[0];
        float scaleY = result[1] * tm.scale[1];
        float scaleZ = result[2] * tm.scale[2];

        result[0] = tm.position[0] + scaleX +
            (scaleX * ((ry*ry*-2.0f) - (rz*rz*2.0f))) +
            (scaleY * ((rw*rz*-2.0f) - (ry*rx*-2.0f))) +
            (scaleZ * ((rz*rx*2.0f) - (rw*ry*-2.0f)));
        result[1] = tm.position[1] + scaleY +
            (scaleX * ((rx*ry*2.0f) - (rw*rz*-2.0f))) +
            (scaleY * ((rz*rz*-2.0f) - (rx*rx*2.0f))) +
            (scaleZ * ((rw*rx*-2.0f) - (rz*ry*-2.0f)));
        result[2] = tm.position[2] + scaleZ +
            (scaleX * ((rw*ry*-2.0f) - (rx*rz*-2.0f))) +
            (scaleY * ((ry*rz*2.0f) - (rw*rx*-2.0f))) +
            (scaleZ * ((rx*rx*-2.0f) - (ry*ry*2.0f)));

        int next = 0;
        if (!readI32(pid, matrixIndices + (long)transformIndex * 4, next)) return false;
        transformIndex = next;
    }

    outPos[0] = result[0];
    outPos[1] = result[1];
    outPos[2] = result[2];
    return tries < maxTries;
}

// Lee la posición de un hueso (puntero a nodo transform).
bool getBonePosition(int pid, long entity, long boneOffset, float* outPos) {
    long bone = 0;
    if (!readPtr(pid, entity + boneOffset, bone) || !isPlausiblePtr(bone)) return false;
    long transform = 0;
    if (!readPtr(pid, bone + 0x8, transform) || !isPlausiblePtr(transform)) return false;
    return getTransformPosition(pid, transform, outPos);
}

// ==============================================================================================
// [OPTIMIZACIÓN ESP: SNAPSHOT DE JERARQUÍAS DE TRANSFORMS]
// ==============================================================================================
// El cuello de botella era leer TODA la cadena de ancestros por cada hueso. Ahora se fotografía
// la jerarquía (matrixList/matrixIndices) una vez por frame POR CADA jerarquía distinta
// (en BR puede haber varias: cámara, jugadores, mundo) y se resuelve la cadena 100% en memoria.
// Los punteros bone->transform->matrix/index se leen SIEMPRE frescos (correcto).
// IMPORTANTE: el helper limita a 16384 B por read -> se lee en fragmentos de 16384.

static std::mutex g_hier_mutex;
static std::atomic<int> g_hier_frame{0};
static const int HIER_MAX = 96; // 96 entradas cubren todos los huesos (0..64) en solo 4.6 KB

struct HierSnap {
    int pid = -1;
    long matrix = 0;
    long list = 0;
    long indices = 0;
    std::vector<uint8_t> list_data;    // copia de matrixList
    std::vector<uint8_t> indices_data; // copia de matrixIndices
    int list_count = 0;
    int indices_count = 0;
    bool ready = false;
    int last_frame = -1;
};
static std::unordered_map<long, HierSnap> g_hier_snaps; // key = matrix

// Devuelve true si el snapshot de (pid,matrix) está listo para este frame (refrescándolo si toca).
// Se llama bajo g_hier_mutex.
static bool refreshHierSnapLocked(int pid, long matrix, int frame) {
    HierSnap& s = g_hier_snaps[matrix];
    if (s.pid == pid && s.last_frame == frame && s.ready) return true;

    s.pid = pid;
    s.matrix = matrix;
    s.last_frame = frame;
    s.ready = false;
    s.list_count = 0;
    s.indices_count = 0;

    long matrixList = 0, matrixIndices = 0;
    if (!readPtr(pid, matrix + 0x18, matrixList) || !isPlausiblePtr(matrixList)) return false;
    if (!readPtr(pid, matrix + 0x1C, matrixIndices) || !isPlausiblePtr(matrixIndices)) return false;
    s.list = matrixList;
    s.indices = matrixIndices;

    // matrixIndices: HIER_MAX * 4 bytes
    s.indices_data.assign(HIER_MAX * 4, 0);
    if (!readGameMemory(pid, matrixIndices, s.indices_data.data(), HIER_MAX * 4)) return false;
    s.indices_count = HIER_MAX;

    // matrixList: HIER_MAX * 0x30 bytes
    s.list_data.assign(HIER_MAX * 0x30, 0);
    if (!readGameMemory(pid, matrixList, s.list_data.data(), HIER_MAX * 0x30)) return false;
    s.list_count = HIER_MAX;
    s.ready = true;

    // Si el mapa tiene muchas entradas, eliminamos solo las jerarquías de frames anteriores
    if (g_hier_snaps.size() > 64) {
        for (auto it = g_hier_snaps.begin(); it != g_hier_snaps.end(); ) {
            if (it->second.last_frame != frame) {
                it = g_hier_snaps.erase(it);
            } else {
                ++it;
            }
        }
    }
    return s.ready;
}

// Resuelve la posición mundial de un transform usando el snapshot (0 lecturas extra).
bool resolvePosFromHier(int pid, long matrix, uint32_t index, float* outPos) {
    std::lock_guard<std::mutex> lock(g_hier_mutex);
    auto it = g_hier_snaps.find(matrix);
    if (it == g_hier_snaps.end() || it->second.pid != pid || !it->second.ready) return false;
    const HierSnap& s = it->second;
    if ((int)index >= s.indices_count || (int)index >= s.list_count) return false;

    const uint8_t* idxData = s.indices_data.data();
    const uint8_t* listData = s.list_data.data();

    float result[3];
    memcpy(result, listData + (long)index * 0x30, 12);

    int transformIndex = 0;
    memcpy(&transformIndex, idxData + (long)index * 4, 4);
    int tries = 0;
    const int maxTries = 20;
    while (transformIndex >= 0 && tries < maxTries) {
        tries++;
        if (transformIndex < 0 || (int)transformIndex >= s.list_count) return false;
        TMatrix tm;
        memcpy(&tm, listData + (long)transformIndex * 0x30, sizeof(TMatrix));

        float rx = tm.rotation[0], ry = tm.rotation[1], rz = tm.rotation[2], rw = tm.rotation[3];
        float scaleX = result[0] * tm.scale[0];
        float scaleY = result[1] * tm.scale[1];
        float scaleZ = result[2] * tm.scale[2];

        result[0] = tm.position[0] + scaleX +
            (scaleX * ((ry*ry*-2.0f) - (rz*rz*2.0f))) +
            (scaleY * ((rw*rz*-2.0f) - (ry*rx*-2.0f))) +
            (scaleZ * ((rz*rx*2.0f) - (rw*ry*-2.0f)));
        result[1] = tm.position[1] + scaleY +
            (scaleX * ((rx*ry*2.0f) - (rw*rz*-2.0f))) +
            (scaleY * ((rz*rz*-2.0f) - (rx*rx*2.0f))) +
            (scaleZ * ((rw*rx*-2.0f) - (rz*ry*-2.0f)));
        result[2] = tm.position[2] + scaleZ +
            (scaleX * ((rw*ry*-2.0f) - (rx*rz*-2.0f))) +
            (scaleY * ((ry*rz*2.0f) - (rw*rx*-2.0f))) +
            (scaleZ * ((rx*rx*-2.0f) - (ry*ry*2.0f)));

        memcpy(&transformIndex, idxData + (long)transformIndex * 4, 4);
    }
    outPos[0] = result[0];
    outPos[1] = result[1];
    outPos[2] = result[2];
    return tries < maxTries;
}

// Asegura el snapshot para (pid, matrix) en el frame actual.
bool ensureHierarchy(int pid, long matrix) {
    if (matrix <= 0) return false;
    int frame = g_hier_frame.load();
    std::lock_guard<std::mutex> lock(g_hier_mutex);
    return refreshHierSnapLocked(pid, matrix, frame);
}

// Lee los 14 punteros de hueso de una entidad en UNA llamada (bloque contiguo 0x458..0x4A4).
// Fallback a lecturas individuales si el bloque falla.
bool readBonePtrBlock(int pid, long entity, long out[14]) {
    uint8_t raw[0x60];
    memset(raw, 0, sizeof(raw));
    bool ok = readGameMemory(pid, entity + 0x458, raw, sizeof(raw));
    for (int i = 0; i < 14; i++) {
        long bone = 0;
        if (ok) {
            size_t off = (size_t)(SKELETON_BONES[i] - 0x458);
            if (off + 4 <= sizeof(raw)) {
                uint32_t b32 = 0;
                memcpy(&b32, raw + off, 4);
                bone = (long)b32;
            }
        }
        if (bone == 0 || !isPlausiblePtr(bone)) {
            if (!readPtr(pid, entity + SKELETON_BONES[i], bone)) bone = 0;
        }
        out[i] = bone;
    }
    return true;
}

// Resuelve la posición mundial desde un puntero de hueso ya leído (3 reads: transform,
// transformObj, y matrix+index combinados en un único read de 8 bytes). Usa el snapshot de
// jerarquía para la cadena de ancestros; fallback al método directo si algo falla.
bool getBonePosFromPtr(int pid, long bone, float* outPos) {
    if (!isPlausiblePtr(bone)) return false;
    long transform = 0;
    if (!readPtr(pid, bone + 0x8, transform) || !isPlausiblePtr(transform)) return false;
    long transformObj = 0;
    if (!readPtr(pid, transform + 0x8, transformObj) || !isPlausiblePtr(transformObj)) return false;

    uint32_t index = 0;
    long matrix = 0;
    if (g_ptr_width == 4) {
        uint32_t m32 = 0;
        uint8_t mi[8];
        if (readGameMemory(pid, transformObj + 0x20, mi, 8)) {
            memcpy(&m32, mi, 4);
            memcpy(&index, mi + 4, 4);
            matrix = (long)m32;
        }
    } else {
        readPtr(pid, transformObj + 0x20, matrix);
        readU32(pid, transformObj + 0x28, index);
    }

    if (isPlausiblePtr(matrix) && ensureHierarchy(pid, matrix) &&
        resolvePosFromHier(pid, matrix, index, outPos)) return true;
    return getTransformPosition(pid, transform, outPos);
}

// Posición mundial de un hueso por índice (0..13).
// Lee los punteros FRESCOS por hueso (bone->transform->transformObj->matrix/index), igual que
// el método directo que funciona, y usa el snapshot de jerarquía solo para resolver la cadena
// de ancestros en memoria (elimina ~18 lecturas por hueso sin cambiar el resultado).
bool getBonePosFast(int pid, long entity, int boneIdx, float* outPos) {
    long bones[14];
    readBonePtrBlock(pid, entity, bones);
    return getBonePosFromPtr(pid, bones[boneIdx], outPos);
}

// Diccionario de entidades (port de Data.GetEntities del dump de referencia).
// Lectura por entrada (2 reads por entry) — método probado y seguro documentado en PROGRESS.md.
std::vector<long> getEntities(int pid, long currentGame) {
    std::vector<long> out;
    long dict = 0;
    if (!readPtr(pid, currentGame + OFF_DICT_ENTITIES, dict) || !isPlausiblePtr(dict)) return out;

    int count = 0;
    if (!readI32(pid, dict + OFF_DICT_COUNT, count)) return out;
    if (count < 1 || count > 1000) return out;

    long entries = 0;
    if (!readPtr(pid, dict + OFF_DICT_ENTRIES_PTR, entries) || !isPlausiblePtr(entries)) return out;

    long start = entries + OFF_DICT_START;

    for (int i = 0; i < count; i++) {
        long entry = start + (long)i * 0x10;
        int hash = 0;
        if (!readI32(pid, entry + OFF_ENTRY_HASH, hash)) continue;
        if (hash < 0) continue;
        long entity = 0;
        if (!readPtr(pid, entry + OFF_ENTRY_ENTITY, entity)) continue;
        if (entity == 0 || !isPlausiblePtr(entity)) continue;
        out.push_back(entity);
    }
    return out;
}

// Matriz de vista (localPlayer -> FollowCamera -> Camera -> CameraBase -> ViewMatrix).
bool getViewMatrix(int pid, long localPlayer, float* m) {
    long followCamera = 0;
    if (!readPtr(pid, localPlayer + OFF_FOLLOW_CAMERA, followCamera) || !isPlausiblePtr(followCamera)) return false;
    long camera = 0;
    if (!readPtr(pid, followCamera + OFF_CAMERA, camera) || !isPlausiblePtr(camera)) return false;
    long cameraBase = 0;
    if (!readPtr(pid, camera + OFF_CAMERA_BASE, cameraBase) || !isPlausiblePtr(cameraBase)) return false;
    return readGameMemory(pid, cameraBase + OFF_VIEW_MATRIX, m, 64);
}

// WorldToScreen (port de W2S.cs). Devuelve false si el punto queda detrás de la cámara.
bool worldToScreen(const float* m, const float* pos, int w, int h, float& sx, float& sy) {
    float v9  = pos[0]*m[0]  + pos[1]*m[4]  + pos[2]*m[8]  + m[12];
    float v10 = pos[0]*m[1]  + pos[1]*m[5]  + pos[2]*m[9]  + m[13];
    float v12 = pos[0]*m[3]  + pos[1]*m[7]  + pos[2]*m[11] + m[15];
    if (v12 < 0.001f) return false;
    float cx = w / 2.0f, cy = h / 2.0f;
    sx = cx + (cx * v9) / v12;
    sy = cy - (cy * v10) / v12;
    return true;
}

// Calcula las coordenadas de pantalla de los huesos del esqueleto que se dibujan.
// Recibe los 14 punteros de hueso YA leídos (para no re-leer el bloque).
// OPTIMIZACIÓN: codos (6,7) y tobillos (10,11) NO se calculan (quedan en -1) y el overlay
// dibuja hombro->muñeca / ingle->pie directos: ahorra 4 huesos * 3 reads por enemigo.
// outSkel[2*i] = sx, outSkel[2*i+1] = sy; -1 si el hueso no está en pantalla o se omite.
void getSkeletonScreen(int pid, long localPlayer, long entity, const long* bones, const float* vm, int w, int h, float* outSkel) {
    for (int i = 0; i < 28; i++) outSkel[i] = -1.0f;
    for (int b = 0; b < 14; b++) {
        if (b == 6 || b == 7 || b == 10 || b == 11) continue; // codos y tobillos omitidos
        float pos[3];
        if (!getBonePosFromPtr(pid, bones[b], pos)) continue;
        float sx, sy;
        if (worldToScreen(vm, pos, w, h, sx, sy)) {
            outSkel[b * 2] = sx;
            outSkel[b * 2 + 1] = sy;
        }
    }
}

bool isSniperWeaponId(short id) {
    short v = id;
    if (v < 0) v += 25000;
    return (v == 4 || v == 65 || v == 21 || v == 64 ||
            v == 128 || v == 129 || v == 45 || v == 75 || v == 78 || v == 197);
}

// ¿El jugador local lleva un francotirador? (port de SniperScope.IsLocalPlayerHoldingSniper)
bool isHoldingSniper(int pid, long localPlayer) {
    long dataPool = 0;
    if (!readPtr(pid, localPlayer + OFF_PLAYER_DATA, dataPool) || !isPlausiblePtr(dataPool)) return false;
    long poolObj = 0;
    if (!readPtr(pid, dataPool + 0x8, poolObj) || !isPlausiblePtr(poolObj)) return false;
    long pool = 0;
    if (!readPtr(pid, poolObj + 0x20, pool) || !isPlausiblePtr(pool)) return false;
    short weaponId = 0;
    if (!readGameMemory(pid, pool + 0x10, &weaponId, 2)) return false;
    return isSniperWeaponId(weaponId);
}

// Estado de equipo: -1 desconocido, 1 compañero, 2 enemigo. (port Data.cs avatar chain)
int getTeamStatus(int pid, long entity) {
    long avatarManager = 0;
    if (!readPtr(pid, entity + OFF_AVATAR_MANAGER, avatarManager) || !isPlausiblePtr(avatarManager)) return -1;
    long avatar = 0;
    if (!readPtr(pid, avatarManager + OFF_AVATAR, avatar) || !isPlausiblePtr(avatar)) return -1;
    uint8_t isVisible = 0;
    if (!readU8(pid, avatar + OFF_AVATAR_IS_VISIBLE, isVisible)) return -1;
    if (!isVisible) return -1;
    long avatarData = 0;
    if (!readPtr(pid, avatar + OFF_AVATAR_DATA, avatarData) || !isPlausiblePtr(avatarData)) return -1;
    uint8_t isTeam = 0;
    if (!readU8(pid, avatarData + OFF_AVATAR_DATA_IS_TEAM, isTeam)) return -1;
    return isTeam ? 1 : 2;
}

bool isKnocked(int pid, long entity) {
    long shadowBase = 0;
    if (!readPtr(pid, entity + OFF_SHADOW_BASE, shadowBase) || !isPlausiblePtr(shadowBase)) return false;
    int xpose = 0;
    if (!readI32(pid, shadowBase + OFF_XPOSE, xpose)) return false;
    return xpose == 8;
}

// Slerp entre dos quaternions normalizados (t en [0,1]).
void quatSlerp(const float* a, const float* b, float t, float* out) {
    float dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2] + a[3]*b[3];
    bool flip = false;
    if (dot < 0.0f) { dot = -dot; flip = true; }
    if (dot > 0.9995f) {
        out[0] = a[0] + t*(b[0] - (flip ? -b[0] : b[0]));
        out[1] = a[1] + t*(b[1] - (flip ? -b[1] : b[1]));
        out[2] = a[2] + t*(b[2] - (flip ? -b[2] : b[2]));
        out[3] = a[3] + t*(b[3] - (flip ? -b[3] : b[3]));
    } else {
        float theta0 = acosf(dot);
        float theta = theta0 * t;
        float sinTheta = sinf(theta);
        float sinTheta0 = sinf(theta0);
        if (sinTheta0 < 1e-6f) sinTheta0 = 1e-6f;
        float s0 = cosf(theta) - dot * sinTheta / sinTheta0;
        float s1 = sinTheta / sinTheta0;
        if (flip) s1 = -s1;
        out[0] = s0*a[0] + s1*b[0];
        out[1] = s0*a[1] + s1*b[1];
        out[2] = s0*a[2] + s1*b[2];
        out[3] = s0*a[3] + s1*b[3];
    }
}

// Quaternion LookRotation hacia un punto (port de SniperScope.cs).
void aimAt(int pid, long localPlayer, const float* myPos, const float* targetPos) {
    float fwd[3] = { targetPos[0]-myPos[0], targetPos[1]-myPos[1], targetPos[2]-myPos[2] };
    float up[3] = { 0, 1, 0 };

    float fm = sqrtf(fwd[0]*fwd[0] + fwd[1]*fwd[1] + fwd[2]*fwd[2]);
    if (fm < 1e-6f) return;
    fwd[0] /= fm; fwd[1] /= fm; fwd[2] /= fm;

    float q[4] = {0, 0, 0, 1};

    float dot = fabsf(fwd[0]*up[0] + fwd[1]*up[1] + fwd[2]*up[2]);
    if (1.0f - dot < 1e-8f) {
        // forwards paralelo a up -> FromToRotation (caso raro)
        float right[3] = {0, -fwd[2], fwd[1]};
        float rm = sqrtf(right[0]*right[0] + right[1]*right[1] + right[2]*right[2]);
        if (rm < 1e-6f) return;
        right[0] /= rm; right[1] /= rm; right[2] /= rm;
        q[0] = right[0]; q[1] = right[1]; q[2] = right[2]; q[3] = 0;
    } else {
        float right[3];
        right[0] = up[1]*fwd[2] - up[2]*fwd[1];
        right[1] = up[2]*fwd[0] - up[0]*fwd[2];
        right[2] = up[0]*fwd[1] - up[1]*fwd[0];
        float rm = sqrtf(right[0]*right[0] + right[1]*right[1] + right[2]*right[2]);
        if (rm < 1e-6f) return;
        right[0] /= rm; right[1] /= rm; right[2] /= rm;

        float up2[3];
        up2[0] = fwd[1]*right[2] - fwd[2]*right[1];
        up2[1] = fwd[2]*right[0] - fwd[0]*right[2];
        up2[2] = fwd[0]*right[1] - fwd[1]*right[0];

        float radicand = right[0] + up2[1] + fwd[2];
        float xq, yq, zq, wq;
        if (radicand > 0) {
            wq = sqrtf(1 + radicand) * 0.5f;
            float recip = 1.0f / (4.0f * wq);
            xq = (up2[2] - fwd[1]) * recip;
            yq = (fwd[0] - right[2]) * recip;
            zq = (right[1] - up2[0]) * recip;
        } else if (right[0] >= up2[1] && right[0] >= fwd[2]) {
            xq = sqrtf(1 + right[0] - up2[1] - fwd[2]) * 0.5f;
            float recip = 1.0f / (4.0f * xq);
            wq = (up2[2] - fwd[1]) * recip;
            zq = (fwd[0] + right[2]) * recip;
            yq = (right[1] + up2[0]) * recip;
        } else if (up2[1] > fwd[2]) {
            yq = sqrtf(1 - right[0] + up2[1] - fwd[2]) * 0.5f;
            float recip = 1.0f / (4.0f * yq);
            zq = (up2[2] + fwd[1]) * recip;
            wq = (fwd[0] - right[2]) * recip;
            xq = (right[1] + up2[0]) * recip;
        } else {
            zq = sqrtf(1 - right[0] - up2[1] + fwd[2]) * 0.5f;
            float recip = 1.0f / (4.0f * zq);
            yq = (up2[2] + fwd[1]) * recip;
            xq = (fwd[0] + right[2]) * recip;
            wq = (right[1] - up2[0]) * recip;
        }
        q[0] = xq; q[1] = yq; q[2] = zq; q[3] = wq;
    }

    // Suavizado: interpolamos desde la rotación actual hacia la del objetivo para
    // evitar el "snap" brusco que fija la cámara (port del aim assist de la referencia).
    {
        float cur[4] = {0, 0, 0, 1};
        readGameMemory(pid, localPlayer + OFF_AIM_ROTATION, cur, 16);
        quatSlerp(cur, q, 0.35f, q);
    }

    writeGameMemory(pid, localPlayer + OFF_AIM_ROTATION, q, 16);
}

// ==============================================================================================
// [LÓGICA DEL AIMBOT + SNIPER SCOPE]
// ==============================================================================================

void aimbotThreadFunction() {
    LOGI("[FREEZY] Aimbot thread iniciado");
    int failStreak = 0;
    GamePointers gp;
    float viewMatrix[16] = {0};

    while (g_aimbot_running) {
        int pid = g_game_pid.load();
        if (pid <= 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
            continue;
        }

        bool aimActive = g_aimbot_active.load();
        bool sniperActive = g_sniper_active.load();
        if (!aimActive && !sniperActive) {
            failStreak = 0;
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }

        if (!gp.valid || gp.localPlayer == 0) {
            if (!resolveGamePointers(pid, gp)) {
                failStreak++;
                std::this_thread::sleep_for(std::chrono::milliseconds(failStreak > 5 ? 300 : 100));
                continue;
            }
            failStreak = 0;
            LOGI("[FREEZY] Cadena resuelta: facade=0x%llx game=0x%llx match=0x%llx local=0x%llx",
                 (unsigned long long)gp.facade, (unsigned long long)gp.currentGame,
                 (unsigned long long)gp.currentMatch, (unsigned long long)gp.localPlayer);
        }

        long localPlayer = gp.localPlayer;

        if (!getViewMatrix(pid, localPlayer, viewMatrix)) {
            failStreak++;
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        // Posición del jugador local (cámara)
        float myPos[3] = {0, 0, 0};
        {
            long mt = 0;
            if (readPtr(pid, localPlayer + OFF_MAIN_CAMERA_TRANSFORM, mt) && isPlausiblePtr(mt))
                getTransformPosition(pid, mt, myPos);
        }

        bool holdingSniper = isHoldingSniper(pid, localPlayer);
        if (sniperActive && !holdingSniper) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        std::vector<long> entities = getEntities(pid, gp.currentGame);

        float bestDist = SNIPER_FOV_PX;
        long bestTarget = 0;
        float bestBonePos[3] = {0, 0, 0};
        long bestCollider = 0;

        for (long e : entities) {
            if (e == localPlayer) continue;

            uint8_t isDead = 0;
            if (readU8(pid, e + OFF_PLAYER_IS_DEAD, isDead) && isDead) continue;

            int team = getTeamStatus(pid, e);
            if (team == 1) continue;          // compañero
            if (team != 2) continue;          // desconocido -> no es objetivo

            if (g_sniper_ignore_knocked.load() && isKnocked(pid, e)) continue;
            if (g_sniper_ignore_bots.load()) {
                uint8_t isBot = 0;
                if (readU8(pid, e + OFF_IS_CLIENT_BOT, isBot) && isBot) continue;
            }

            // Posición del objetivo (cabeza o cuerpo según modo)
            long boneOffset = (g_sniper_mode.load() == 1) ? OFF_BONE_HIP : OFF_BONE_HEAD;
            float bonePos[3] = {0, 0, 0};
            if (!getBonePosition(pid, e, boneOffset, bonePos)) continue;
            if (bonePos[0] == 0 && bonePos[1] == 0 && bonePos[2] == 0) continue;

            float dx = bonePos[0] - myPos[0];
            float dy = bonePos[1] - myPos[1];
            float dz = bonePos[2] - myPos[2];
            float dist = sqrtf(dx*dx + dy*dy + dz*dz);
            if (dist > SNIPER_MAX_DIST) continue;

            float sx = 0, sy = 0;
            if (!worldToScreen(viewMatrix, bonePos, g_screen_w.load(), g_screen_h.load(), sx, sy)) continue;
            float cdx = sx - g_screen_w.load() / 2.0f;
            float cdy = sy - g_screen_h.load() / 2.0f;
            float crossDist = sqrtf(cdx*cdx + cdy*cdy);
            if (crossDist > SNIPER_FOV_PX) continue;

            if (crossDist < bestDist) {
                bestDist = crossDist;
                bestTarget = e;
                bestBonePos[0] = bonePos[0];
                bestBonePos[1] = bonePos[1];
                bestBonePos[2] = bonePos[2];
                bestCollider = 0;
                readPtr(pid, e + OFF_COLLIDER, bestCollider);
            }
        }

        if (bestTarget != 0) {
            if (bestDist > AIM_DEADZONE_PX) {
                aimAt(pid, localPlayer, myPos, bestBonePos);
            }
            if (bestCollider != 0) {
                uint32_t collider32 = (uint32_t)bestCollider;
                writeGameMemory(pid, bestTarget + OFF_LOCKED_AIMING_COLLIDER, &collider32, 4);
            }
            failStreak = 0;
            LOGD("[FREEZY] Apuntando a 0x%llx (dist pantalla: %.1f)",
                 (unsigned long long)bestTarget, bestDist);
        } else {
            failStreak++;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(failStreak > 5 ? 100 : 20));
    }

    LOGI("[FREEZY] Aimbot thread finalizado");
}

// Diagnóstico paso a paso de la cadena, para ajustar offsets por build/arch.
std::string chainDiagnostics(int pid) {
    std::stringstream ss;
    GamePointers gp;
    gp.base = getGameBase(pid);
    char buf[64];
    snprintf(buf, sizeof(buf), "base=0x%llx ptr_width=%d", (unsigned long long)gp.base, g_ptr_width.load());
    ss << buf;
    if (gp.base == 0) return ss.str();

    if (readPtr(pid, gp.base + OFF_INIT_BASE, gp.facade)) {
        snprintf(buf, sizeof(buf), " | facade=0x%llx", (unsigned long long)gp.facade); ss << buf;
    } else { ss << " | facade=FAIL"; return ss.str(); }
    if (readPtr(pid, gp.facade + OFF_STATIC_CLASS, gp.staticFacade)) {
        snprintf(buf, sizeof(buf), " | static=0x%llx", (unsigned long long)gp.staticFacade); ss << buf;
    } else { ss << " | static=FAIL"; return ss.str(); }
    if (readPtr(pid, gp.staticFacade, gp.currentGame)) {
        snprintf(buf, sizeof(buf), " | game=0x%llx", (unsigned long long)gp.currentGame); ss << buf;
    } else { ss << " | game=FAIL"; return ss.str(); }
    if (readPtr(pid, gp.currentGame + OFF_CURRENT_MATCH, gp.currentMatch)) {
        snprintf(buf, sizeof(buf), " | match=0x%llx", (unsigned long long)gp.currentMatch); ss << buf;
    } else { ss << " | match=FAIL"; return ss.str(); }
    if (readPtr(pid, gp.currentMatch + OFF_LOCAL_PLAYER, gp.localPlayer)) {
        snprintf(buf, sizeof(buf), " | local=0x%llx", (unsigned long long)gp.localPlayer); ss << buf;
    } else { ss << " | local=FAIL"; return ss.str(); }

    if (gp.localPlayer != 0 && isPlausiblePtr(gp.localPlayer)) {
        snprintf(buf, sizeof(buf), " | holdingSniper=%d", isHoldingSniper(pid, gp.localPlayer) ? 1 : 0);
        ss << buf;
        float myPos[3] = {0,0,0};
        long mt = 0;
        if (readPtr(pid, gp.localPlayer + OFF_MAIN_CAMERA_TRANSFORM, mt) && isPlausiblePtr(mt) &&
            getTransformPosition(pid, mt, myPos)) {
            snprintf(buf, sizeof(buf), " | localPos=(%.1f, %.1f, %.1f)", myPos[0], myPos[1], myPos[2]);
            ss << buf;
        } else {
            ss << " | localPos=FAIL";
        }
        std::vector<long> ents = getEntities(pid, gp.currentGame);
        snprintf(buf, sizeof(buf), " | entities=%zu", ents.size()); ss << buf;
        if (!ents.empty()) {
            long e = ents[0];
            float head[3] = {0,0,0};
            bool ok = getBonePosition(pid, e, OFF_BONE_HEAD, head);
            snprintf(buf, sizeof(buf), " | e0=0x%llx head=%s", (unsigned long long)e, ok ? "OK" : "FAIL");
            ss << buf;
            if (ok) snprintf(buf, sizeof(buf), " (%.1f,%.1f,%.1f)", head[0], head[1], head[2]);
            ss << buf;
            snprintf(buf, sizeof(buf), " | team0=%d", getTeamStatus(pid, e)); ss << buf;
        }
    }
    return ss.str();
}

// ==============================================================================================
// [SNIPER SCOPE - PATCH DE MEMORIA]
// ==============================================================================================

// Lector en bloque (alineado a página) para escaneo rápido de patrones.
// dd bs=4096: lee N páginas contiguas de una sola vez (mucho más rápido que bs=1).
bool readGameMemoryPages(int pid, long pageAlignedAddr, void* buffer, size_t size) {
    if (pid <= 0 || pageAlignedAddr <= 0 || size == 0 || (pageAlignedAddr & 4095) != 0) return false;
    size_t pages = (size + 4095) / 4096;
    std::string cmd = "su -c 'dd if=/proc/" + std::to_string(pid) + "/mem bs=4096 skip=" +
                      std::to_string(pageAlignedAddr / 4096) + " count=" + std::to_string(pages) +
                      " 2>/dev/null'";
    FILE* fp = popen(cmd.c_str(), "r");
    if (!fp) return false;
    size_t read = fread(buffer, 1, size, fp);
    pclose(fp);
    return read == size;
}

// Estados del switch de la mira (patrones extraídos del menú de referencia).
static const std::vector<uint8_t> P_SNIPER_OFF = {
    0x3F,0x00,0x00,0x80,0x3E,0x00,0x00,0x00,0x00,0x04,
    0x00,0x00,0x00,0x00,0x00,0x80,0x3F,0x00,0x00,0x20,
    0x41,0x00,0x00,0x34,0x42,0x01,0x00,0x00,0x00,0x01,
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x80,0x3F};  // 45 bytes: estado OFF actual
static const std::vector<uint8_t> P_SNIPER_ON = {
    0x01,0x00,0x00,0x80,0x00,0x00,0x00,0x00,0x00,0x04,
    0x00,0x00,0x00,0x00,0x00,0x80,0x3F,0x00,0x00,0x20,
    0x41,0x00,0x00,0x34,0x42,0x01,0x00,0x00,0x00,0x01};  // 30 bytes: estado ON

// Delay fix (instrucciones ARM)
static const std::vector<uint8_t> P_DELAY_OFF = {
    0xEA,0x00,0x60,0xA0,0xE3,0x06,0x00,0xA0,0xE1,0x18,
    0xD0,0x8D,0xE2,0xF0,0x87,0xBD,0xE8,0x66,0x2B,0x70,0x05};  // 21 bytes
static const std::vector<uint8_t> P_DELAY_ON = {
    0x01,0x00,0xAF,0xA0,0xE3,0x06,0x00,0xA0,0xE1,0x18,
    0xD0,0x8D,0xE2,0xF0,0x87,0xBD,0xE8,0x66,0x2B,0x70,0x05};  // 21 bytes
static const std::vector<uint8_t> P_DELAY_OFF_RESTORE = {
    0xEA,0x00,0x60,0xA0,0xE3,0x06,0x00,0xA0,0xE1,0x18,
    0xD0,0x4B,0xE2,0x02,0x8B,0xBD,0xEC,0x70,0x8C};  // 19 bytes

static std::mutex g_sniper_mutex;
static std::vector<long> g_sniper_switch_addrs;
static std::vector<long> g_sniper_delay_addrs;
static bool g_sniper_applied = false;
static std::string g_sniper_status;

// Busca un patrón en las regiones legibles del proceso dentro de [0x10000000, 0x70000000].
// Lee regiones en segmentos de 1MB (página-alineados) para minimizar popen(su dd).
void scanPatternInGame(int pid, const std::vector<uint8_t>& pattern, std::vector<long>& results, size_t maxResults) {
    if (pid <= 0 || pattern.empty()) return;
    const long SCAN_START = 0x10000000L;
    const long SCAN_END   = 0x70000000L;
    const long SEGMENT    = 1L << 20; // 1MB por lectura

    std::string content;
    if (!readProcessMaps(pid, content)) return;

    size_t pos = 0;
    while (pos < content.size()) {
        size_t eol = content.find('\n', pos);
        if (eol == std::string::npos) eol = content.size();
        std::string line = content.substr(pos, eol - pos);
        pos = eol + 1;

        if (line.find('r') == std::string::npos) continue;  // sin lectura no sirve
        // Solo regiones respaldadas por archivo (los patrones viven en .so, no en el heap anónimo)
        if (line.find('/') == std::string::npos) continue;

        size_t dash = line.find('-');
        size_t sp = line.find(' ', dash);
        if (dash == std::string::npos || sp == std::string::npos) continue;
        long start = strtoll(line.substr(0, dash).c_str(), nullptr, 16);
        long end = strtoll(line.substr(dash + 1, sp - dash - 1).c_str(), nullptr, 16);

        if (end < SCAN_START || start > SCAN_END) continue;
        long s = std::max(start, SCAN_START);
        long e = std::min(end, SCAN_END);
        if (e - s < (long)pattern.size()) continue;

        for (long segStart = s; segStart < e; segStart += SEGMENT) {
            long segEnd = std::min(segStart + SEGMENT, e);
            size_t len = (size_t)(segEnd - segStart);
            std::vector<uint8_t> buf(len);
            if (!readGameMemoryPages(pid, segStart, buf.data(), len)) continue;

            for (size_t i = 0; i + pattern.size() <= buf.size(); i++) {
                if (memcmp(buf.data() + i, pattern.data(), pattern.size()) == 0) {
                    results.push_back(segStart + (long)i);
                    if (results.size() >= maxResults) return;
                }
            }
        }
    }
}

// Aplica el patch: busca el estado OFF y lo cambia a ON (mira + delay fix).
bool applySniperScopeNative(int pid) {
    std::lock_guard<std::mutex> lock(g_sniper_mutex);
    if (g_sniper_applied) { g_sniper_status = "Ya aplicado"; return true; }

    std::vector<long> switchAddrs;
    scanPatternInGame(pid, P_SNIPER_OFF, switchAddrs, 10);
    if (switchAddrs.empty()) {
        // Quizá ya está en estado ON
        scanPatternInGame(pid, P_SNIPER_ON, switchAddrs, 10);
        g_sniper_status = switchAddrs.empty() ? "Patrón de mira no encontrado" : "Mira ya activa";
    } else {
        g_sniper_status = "Mira encontrada (" + std::to_string(switchAddrs.size()) + ")";
    }
    if (switchAddrs.empty()) return false;

    for (long addr : switchAddrs) {
        writeGameMemory(pid, addr, P_SNIPER_ON.data(), P_SNIPER_ON.size());
        g_sniper_switch_addrs.push_back(addr);
    }

    std::vector<long> delayAddrs;
    scanPatternInGame(pid, P_DELAY_OFF, delayAddrs, 10);
    for (long addr : delayAddrs) {
        writeGameMemory(pid, addr, P_DELAY_ON.data(), P_DELAY_ON.size());
        g_sniper_delay_addrs.push_back(addr);
    }
    if (!delayAddrs.empty()) {
        g_sniper_status += " · delay fix (" + std::to_string(delayAddrs.size()) + ")";
    }

    g_sniper_applied = true;
    g_sniper_status = "Aplicado ✓";
    LOGI("[FREEZY] Sniper scope aplicado: %zu mira(s), %zu delay fix", switchAddrs.size(), delayAddrs.size());
    return true;
}

// Restaura el patch original en las direcciones guardadas.
bool removeSniperScopeNative(int pid) {
    std::lock_guard<std::mutex> lock(g_sniper_mutex);
    if (!g_sniper_applied) { g_sniper_status = "No aplicado"; return true; }

    for (long addr : g_sniper_switch_addrs) {
        writeGameMemory(pid, addr, P_SNIPER_OFF.data(), P_SNIPER_OFF.size());
    }
    for (long addr : g_sniper_delay_addrs) {
        writeGameMemory(pid, addr, P_DELAY_OFF_RESTORE.data(), P_DELAY_OFF_RESTORE.size());
    }

    g_sniper_switch_addrs.clear();
    g_sniper_delay_addrs.clear();
    g_sniper_applied = false;
    g_sniper_status = "Eliminado";
    LOGI("[FREEZY] Sniper scope restaurado");
    return true;
}

// ==============================================================================================
// [FUNCIONES JNI EXPORTADAS]
// ==============================================================================================

extern "C" JNIEXPORT jint JNICALL
Java_com_freezy_NativeBridge_findGamePid(JNIEnv* env, jclass clazz) {
    int pid = findGamePidNative();
    if (pid > 0) {
        g_game_pid = pid;
        LOGI("[FREEZY] PID guardado: %d", pid);
    } else {
        LOGE("[FREEZY] No se encontró el juego");
    }
    return pid;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_NativeBridge_getGamePackageName(JNIEnv* env, jclass clazz) {
    const char* PACKAGES[] = {
        "com.dts.freefiremax",
        "com.dts.freefireth",
        "com.dts.freefire"
    };
    
    for (int i = 0; i < 3; i++) {
        std::string cmd = "su -c 'ps -A | grep " + std::string(PACKAGES[i]) + " | awk \"{print \\$2}\"'";
        FILE* fp = popen(cmd.c_str(), "r");
        if (!fp) continue;
        
        char buffer[32];
        if (fgets(buffer, sizeof(buffer), fp) != nullptr) {
            int pid = atoi(buffer);
            pclose(fp);
            if (pid > 0) {
                return env->NewStringUTF(PACKAGES[i]);
            }
        }
        pclose(fp);
    }
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_freezy_NativeBridge_readGameMemory(JNIEnv* env, jclass clazz,
                                              jint pid, jlong address, jint size) {
    if (pid <= 0 || address <= 0 || size <= 0) {
        LOGE("[FREEZY] readGameMemory: Parámetros inválidos");
        return nullptr;
    }
    
    jbyteArray result = env->NewByteArray(size);
    if (!result) {
        LOGE("[FREEZY] readGameMemory: No se pudo crear ByteArray");
        return nullptr;
    }
    
    jbyte* buffer = env->GetByteArrayElements(result, nullptr);
    if (!buffer) {
        LOGE("[FREEZY] readGameMemory: No se pudo obtener buffer");
        env->DeleteLocalRef(result);
        return nullptr;
    }
    
    bool success = readGameMemory(pid, address, buffer, size);
    env->ReleaseByteArrayElements(result, buffer, 0);
    
    if (!success) {
        LOGD("[FREEZY] readGameMemory: Falló lectura en 0x%llx", (unsigned long long)address);
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_writeGameMemory(JNIEnv* env, jclass clazz,
                                               jint pid, jlong address, jbyteArray value) {
    if (pid <= 0 || address <= 0 || !value) {
        LOGE("[FREEZY] writeGameMemory: Parámetros inválidos");
        return JNI_FALSE;
    }
    
    jsize size = env->GetArrayLength(value);
    if (size <= 0) {
        LOGE("[FREEZY] writeGameMemory: Tamaño inválido");
        return JNI_FALSE;
    }
    
    jbyte* buffer = env->GetByteArrayElements(value, nullptr);
    if (!buffer) {
        LOGE("[FREEZY] writeGameMemory: No se pudo obtener buffer");
        return JNI_FALSE;
    }
    
    bool success = writeGameMemory(pid, address, buffer, size);
    env->ReleaseByteArrayElements(value, buffer, JNI_ABORT);
    
    if (!success) {
        LOGE("[FREEZY] writeGameMemory: Falló escritura en 0x%llx", (unsigned long long)address);
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_isGameMemoryReady(JNIEnv* env, jclass clazz, jint pid) {
    if (pid <= 0) {
        LOGE("[FREEZY] isGameMemoryReady: PID inválido (%d)", pid);
        return JNI_FALSE;
    }
    LOGI("[FREEZY] isGameMemoryReady: comprobando memoria del PID %d", pid);

    long gameBase = getGameBase(pid);
    if (gameBase == 0) {
        LOGE("[FREEZY] isGameMemoryReady: libil2cpp.so NO está mapeada en el PID %d", pid);
        logMappedLibs(pid, "il2cpp-no-mapeada");
        return JNI_FALSE;
    }

    // 1) process_vm_readv (rápido, sin root)
    unsigned char probe = 0;
    errno = 0;
    struct iovec local_iov = { &probe, sizeof(probe) };
    struct iovec remote_iov = { reinterpret_cast<void*>(gameBase), sizeof(probe) };
    ssize_t r = syscall(__NR_process_vm_readv, pid, &local_iov, 1, &remote_iov, 1, 0);
    if (r == static_cast<ssize_t>(sizeof(probe))) {
        LOGI("[FREEZY] isGameMemoryReady: process_vm_readv OK (base 0x%llx)",
             (unsigned long long)gameBase);
        return JNI_TRUE;
    }
    LOGE("[FREEZY] isGameMemoryReady: process_vm_readv falló (r=%zd errno=%d: %s)",
         r, errno, strerror(errno));

    // 2) Fallback root con dd
    if (readGameMemoryRoot(pid, gameBase, &probe, sizeof(probe))) {
        LOGI("[FREEZY] isGameMemoryReady: dd root OK (base 0x%llx)",
             (unsigned long long)gameBase);
        return JNI_TRUE;
    }
    LOGE("[FREEZY] isGameMemoryReady: dd root también falló (base 0x%llx). "
         "Revisa su concedido/denylist/SELinux.",
         (unsigned long long)gameBase);
    return JNI_FALSE;
}

// Devuelve una cadena con el diagnóstico completo del acceso a memoria para depurar
// el "Memoria no encontrada, aimbot no aplicado".
extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_NativeBridge_getGameMemoryDiagnostics(JNIEnv* env, jclass clazz, jint pid) {
    std::stringstream ss;
    ss << "pid=" << pid;

    long base = getGameBase(pid);
    if (base == 0) {
        ss << " | il2cpp=NO_MAPEADA";
    } else {
        char baseHex[32];
        snprintf(baseHex, sizeof(baseHex), "0x%llx", (unsigned long long)base);
        ss << " | il2cpp=" << baseHex;
    }

    std::string mapsContent;
    if (readProcessMaps(pid, mapsContent)) {
        int lines = 0, soLines = 0;
        size_t pos = 0;
        while (pos < mapsContent.size()) {
            size_t eol = mapsContent.find('\n', pos);
            if (eol == std::string::npos) eol = mapsContent.size();
            std::string line = mapsContent.substr(pos, eol - pos);
            pos = eol + 1;
            lines++;
            if (line.find(".so") != std::string::npos) soLines++;
        }
        ss << " | maps_lines=" << lines << " | so_lines=" << soLines;
    } else {
        ss << " | maps=NO_ACCESIBLE";
    }

    if (base > 0) {
        unsigned char probe = 0;
        errno = 0;
        struct iovec l = { &probe, 1 };
        struct iovec r = { reinterpret_cast<void*>(base), 1 };
        ssize_t rr = syscall(__NR_process_vm_readv, pid, &l, 1, &r, 1, 0);
        if (rr == 1) {
            ss << " | process_vm_readv=OK";
        } else {
            ss << " | process_vm_readv=FAIL(errno=" << errno << ")";
        }
        bool ddOk = readGameMemoryRoot(pid, base, &probe, 1);
        ss << " | dd_root=" << (ddOk ? "OK" : "FAIL");
    }

    // ¿su disponible y a qué usuario da acceso?
    FILE* fp = popen("su -c 'id' 2>/dev/null", "r");
    if (fp) {
        char buf[64] = {0};
        if (fgets(buf, sizeof(buf), fp) != nullptr) {
            ss << " | su_id=" << std::string(buf);
        } else {
            ss << " | su_id=(vacio)";
        }
        pclose(fp);
    } else {
        ss << " | su=NO_ACCESIBLE";
    }

    LOGI("[FREEZY] diagnostico: %s", ss.str().c_str());
    return env->NewStringUTF(ss.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_startAimbot(JNIEnv* env, jclass clazz) {
    // Deshabilitado por seguridad anti-ban
    LOGI("[FREEZY] startAimbot() deshabilitado (solo lectura activo)");
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_stopAimbot(JNIEnv* env, jclass clazz) {
    LOGI("[FREEZY] stopAimbot() llamado");
    g_aimbot_running = false;
    g_aimbot_active = false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_NativeBridge_getMenuStatus(JNIEnv* env, jclass clazz) {
    std::string status = "Aimbot: " + std::string(g_aimbot_active ? "ON ✅" : "OFF ❌") +
                         " | PID: " + std::to_string(g_game_pid.load());
    return env->NewStringUTF(status.c_str());
}

// ==============================================================================================
// [SNIPER SWITCH - JNI]
// ==============================================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_sniperSwitchApply(JNIEnv* env, jclass clazz) {
    int pid = g_game_pid.load();
    if (pid <= 0) {
        pid = findGamePidNative();
        if (pid > 0) g_game_pid = pid;
    }
    if (pid <= 0) return JNI_FALSE;
    if (rootMemIOSpawn(pid)) LOGI("[FREEZY] helper activo para el escaneo de patrones");
    return applySniperScopeNative(pid) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_sniperSwitchRemove(JNIEnv* env, jclass clazz) {
    int pid = g_game_pid.load();
    if (pid <= 0) return JNI_TRUE;
    return removeSniperScopeNative(pid) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_sniperSwitchIsApplied(JNIEnv* env, jclass clazz) {
    return g_sniper_applied ? JNI_TRUE : JNI_FALSE;
}

// ==============================================================================================
// [SNIPER SCOPE AIM-ASSIST - JNI]
// ==============================================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setSniperScope(JNIEnv* env, jclass clazz, jboolean active) {
    g_sniper_active = active ? true : false;
    LOGI("[FREEZY] Sniper Scope aim-assist: %s", g_sniper_active.load() ? "ON" : "OFF");
    if (g_sniper_active.load() && !g_aimbot_running) {
        g_aimbot_running = true;
        g_aimbot_thread = std::thread(aimbotThreadFunction);
        g_aimbot_thread.detach();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setSniperMode(JNIEnv* env, jclass clazz, jint mode) {
    g_sniper_mode = (mode == 1) ? 1 : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setSniperIgnoreKnocked(JNIEnv* env, jclass clazz, jboolean ignore) {
    g_sniper_ignore_knocked = ignore ? true : false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setSniperIgnoreBots(JNIEnv* env, jclass clazz, jboolean ignore) {
    g_sniper_ignore_bots = ignore ? true : false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setScreenSize(JNIEnv* env, jclass clazz, jint w, jint h) {
    if (w > 0 && h > 0) {
        int screenW = std::max(w, h);
        int screenH = std::min(w, h);
        g_screen_w = screenW;
        g_screen_h = screenH;
        LOGI("[FREEZY] Screen size configurado: %d x %d", screenW, screenH);
    }
}

// ==============================================================================================
// [CONFIG NATIVA - JNI]
// ==============================================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setMemoryHelperPath(JNIEnv* env, jclass clazz, jstring path) {
    const char* cpath = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    if (cpath) {
        g_helper_path = cpath;
        env->ReleaseStringUTFChars(path, cpath);
        LOGI("[FREEZY] Helper path: %s", g_helper_path.c_str());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setPointerWidth(JNIEnv* env, jclass clazz, jint width) {
    if (width == 4 || width == 8) {
        g_ptr_width = width;
        LOGI("[FREEZY] Punteros de %d bytes", width);
    }
}

// ==============================================================================================
// [DIAGNÓSTICO DE CADENA - JNI]
// ==============================================================================================

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_NativeBridge_getChainDiagnostics(JNIEnv* env, jclass clazz, jint pid) {
    std::string diag = chainDiagnostics(pid);
    LOGI("[FREEZY] chainDiagnostics: %s", diag.c_str());
    return env->NewStringUTF(diag.c_str());
}

// ==============================================================================================
// [ESP SNAPSHOT - JNI] (solo lectura de datos; el dibujado queda del lado de la app)
// ==============================================================================================

// Lee una cadena UTF-16 de longitud prefijada (port de InternalMemory.ReadString).
std::string readUtf16String(int pid, long addr, int maxBytes) {
    if (maxBytes <= 0 || maxBytes > 4096) return "";
    std::vector<char> raw((size_t)maxBytes);
    if (!readGameMemory(pid, addr, raw.data(), maxBytes)) return "";
    std::string out;
    for (int i = 0; i + 1 < maxBytes; i += 2) {
        char16_t c = (char16_t)((uint8_t)raw[i] | ((uint8_t)raw[i+1] << 8));
        if (c == 0) break;
        if (c < 0x20) continue;
        out += (char)(c < 0x80 ? c : '?');
    }
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_NativeBridge_getEspSnapshot(JNIEnv* env, jclass clazz, jint pid) {
    auto snapStart = std::chrono::steady_clock::now();
    std::stringstream ss;
    GamePointers gp;
    if (!resolveGamePointers(pid, gp)) {
        ss << "{\"ok\":false,\"chain\":\"FAIL\"}";
        return env->NewStringUTF(ss.str().c_str());
    }

    float myPos[3] = {0, 0, 0};
    {
        long mt = 0;
        if (readPtr(pid, gp.localPlayer + OFF_MAIN_CAMERA_TRANSFORM, mt) && isPlausiblePtr(mt))
            getTransformPosition(pid, mt, myPos);
    }

    // ViewMatrix leída una sola vez para todo el frame actual
    float vm[16];
    bool vmOk = getViewMatrix(pid, gp.localPlayer, vm);

    g_hier_frame.fetch_add(1); // refresca el snapshot de jerarquía una vez por snapshot

    ss << "{\"ok\":true,\"local\":{\"ptr\":\"0x" << std::hex << gp.localPlayer << "\","
       << "\"x\":" << std::dec << myPos[0] << ",\"y\":" << myPos[1] << ",\"z\":" << myPos[2] << "},"
       << "\"holdingSniper\":" << (isHoldingSniper(pid, gp.localPlayer) ? "true" : "false") << ",";

    std::vector<long> ents = getEntities(pid, gp.currentGame);
    ss << "\"entities\":[";
    int shown = 0;

    const float ESP_MAX_DIST = 150.0f;

    for (long e : ents) {
        if (shown >= 50) break;
        if (e == gp.localPlayer) continue;

        // 1. FILTRO ULTRA RÁPIDO: ¿Muerto?
        uint8_t isDead = 0;
        if (!readU8(pid, e + OFF_PLAYER_IS_DEAD, isDead) || isDead) continue;

        // 2. FILTRO ULTRA RÁPIDO: ¿Es enemigo? (team == 2)
        int team = getTeamStatus(pid, e);
        if (team != 2) continue;

        // 3. Punteros de huesos y cabeza
        long bones[14];
        readBonePtrBlock(pid, e, bones);
        float head[3] = {0, 0, 0};
        bool headOk = getBonePosFromPtr(pid, bones[0], head);
        if (!headOk) continue;

        float dist = sqrtf((head[0]-myPos[0])*(head[0]-myPos[0]) +
                           (head[1]-myPos[1])*(head[1]-myPos[1]) +
                           (head[2]-myPos[2])*(head[2]-myPos[2]));

        // Filtro de distancia máxima
        if (dist > ESP_MAX_DIST) continue;

        // 4. Proyección a pantalla usando la matriz de vista más reciente (garantiza sincronización con giro de cámara)
        float skel[28];
        float vm[16];
        if (getViewMatrix(pid, gp.localPlayer, vm)) {
            getSkeletonScreen(pid, gp.localPlayer, e, bones, vm, g_screen_w.load(), g_screen_h.load(), skel);
        } else {
            for (int i = 0; i < 28; i++) skel[i] = -1.0f;
        }

        // Si la cabeza no proyecta en pantalla, omitir
        if (skel[0] <= 0 || skel[1] <= 0) continue;

        bool knocked = isKnocked(pid, e);

        if (shown > 0) ss << ",";
        ss << "{\"ptr\":\"0x" << std::hex << e << "\""
           << ",\"dead\":false"
           << ",\"team\":2"
           << ",\"knocked\":" << (knocked ? "true" : "false")
           << ",\"dist\":" << dist
           << ",\"skel\":[";
        for (int i = 0; i < 28; i++) {
            if (i > 0) ss << ",";
            ss << skel[i];
        }
        ss << "]}";
        shown++;
    }
    ss << "]}";
    auto snapEnd = std::chrono::steady_clock::now();
    auto snapMs = std::chrono::duration_cast<std::chrono::microseconds>(snapEnd - snapStart).count() / 1000.0;
    LOGD("[FREEZY] ESP snapshot: %d entidades, %.1f ms", shown, snapMs);
    return env->NewStringUTF(ss.str().c_str());
}

int getPlayerHealth(int pid, long entity) {
    // 1. Escanear PlayerAttributes (+0x4BC)
    long attr = 0;
    if (readPtr(pid, entity + 0x4BC, attr) && isPlausiblePtr(attr)) {
        uint8_t buf[128];
        if (readGameMemory(pid, attr + 0x8, buf, sizeof(buf))) {
            // A) Pares enteros (CurHP, MaxHP) o (MaxHP, CurHP)
            int32_t* ints = (int32_t*)buf;
            int count = sizeof(buf) / sizeof(int32_t);
            for (int i = 0; i < count - 1; i++) {
                int a = ints[i];
                int b = ints[i+1];
                if (b >= 200 && b <= 300 && a >= 1 && a <= b) return a;
                if (a >= 200 && a <= 300 && b >= 1 && b <= a) return b;
            }
            // B) Pares floats
            float* floats = (float*)buf;
            for (int i = 0; i < count - 1; i++) {
                float a = floats[i];
                float b = floats[i+1];
                if (b >= 200.0f && b <= 300.0f && a >= 1.0f && a <= b) return (int)a;
                if (a >= 200.0f && a <= 300.0f && b >= 1.0f && b <= a) return (int)b;
            }
        }
    }

    // 2. Escanear DataPool / m_PlayerData (+0x48)
    long dataPool = 0;
    if (readPtr(pid, entity + OFF_PLAYER_DATA, dataPool) && isPlausiblePtr(dataPool)) {
        long poolObj = 0;
        if (readPtr(pid, dataPool + 0x8, poolObj) && isPlausiblePtr(poolObj)) {
            long pool = 0;
            if (readPtr(pid, poolObj + 0x20, pool) && isPlausiblePtr(pool)) {
                uint8_t buf[128];
                if (readGameMemory(pid, pool + 0x8, buf, sizeof(buf))) {
                    int32_t* ints = (int32_t*)buf;
                    int count = sizeof(buf) / sizeof(int32_t);
                    for (int i = 0; i < count - 1; i++) {
                        int a = ints[i];
                        int b = ints[i+1];
                        if (b >= 200 && b <= 300 && a >= 1 && a <= b) return a;
                        if (a >= 200 && a <= 300 && b >= 1 && b <= a) return b;
                    }
                    float* floats = (float*)buf;
                    for (int i = 0; i < count - 1; i++) {
                        float a = floats[i];
                        float b = floats[i+1];
                        if (b >= 200.0f && b <= 300.0f && a >= 1.0f && a <= b) return (int)a;
                        if (a >= 200.0f && a <= 300.0f && b >= 1.0f && b <= a) return (int)b;
                    }
                }
            }
        }
    }

    // 3. Escanear directamente en Player (+0x100..+0x200)
    uint8_t pbuf[128];
    if (readGameMemory(pid, entity + 0x100, pbuf, sizeof(pbuf))) {
        int32_t* ints = (int32_t*)pbuf;
        int count = sizeof(pbuf) / sizeof(int32_t);
        for (int i = 0; i < count - 1; i++) {
            int a = ints[i];
            int b = ints[i+1];
            if (b >= 200 && b <= 300 && a >= 1 && a <= b) return a;
            if (a >= 200 && a <= 300 && b >= 1 && b <= a) return b;
        }
    }

    return 200;
}

short getPlayerWeaponId(int pid, long entity) {
    long dataPool = 0;
    if (!readPtr(pid, entity + OFF_PLAYER_DATA, dataPool) || !isPlausiblePtr(dataPool)) return 0;
    long poolObj = 0;
    if (!readPtr(pid, dataPool + 0x8, poolObj) || !isPlausiblePtr(poolObj)) return 0;
    long pool = 0;
    if (!readPtr(pid, poolObj + 0x20, pool) || !isPlausiblePtr(pool)) return 0;
    short weaponId = 0;
    if (!readGameMemory(pid, pool + 0x10, &weaponId, 2)) return 0;
    if (weaponId < 0) weaponId += 25000;
    return weaponId;
}

void readPlayerNamePacked(int pid, long entity, float* outNameFloats, float& outIsBot) {
    for (int i = 0; i < 6; i++) outNameFloats[i] = 0.0f;
    outIsBot = 0.0f;

    uint8_t isBot = 0;
    if (readU8(pid, entity + OFF_IS_CLIENT_BOT, isBot) && isBot) {
        outIsBot = 1.0f;
        const char* botStr = "BOT";
        uint16_t p0 = (uint16_t)(((uint8_t)botStr[0]) << 8 | (uint8_t)botStr[1]);
        uint16_t p1 = (uint16_t)(((uint8_t)botStr[2]) << 8);
        outNameFloats[0] = (float)p0;
        outNameFloats[1] = (float)p1;
        return;
    }

    long namePtr = 0;
    if (!readPtr(pid, entity + OFF_PLAYER_NAME, namePtr) || !isPlausiblePtr(namePtr)) return;

    int length = 0;
    long lenOff = (g_ptr_width.load() == 4) ? 0x8 : 0x10;
    long dataOff = (g_ptr_width.load() == 4) ? 0xC : 0x14;

    if (!readI32(pid, namePtr + lenOff, length) || length <= 0) return;

    uint16_t utf16[12];
    int toRead = std::min<int>(length, 12);
    if (!readGameMemory(pid, namePtr + dataOff, utf16, toRead * 2)) return;

    for (int i = 0; i < 6; i++) {
        uint8_t c1 = 0, c2 = 0;
        int idx1 = i * 2;
        int idx2 = i * 2 + 1;
        if (idx1 < toRead) {
            uint16_t ch = utf16[idx1];
            c1 = (ch >= 32 && ch <= 126) ? (uint8_t)ch : '?';
        }
        if (idx2 < toRead) {
            uint16_t ch = utf16[idx2];
            c2 = (ch >= 32 && ch <= 126) ? (uint8_t)ch : '?';
        }
        uint16_t packed = (uint16_t)((c1 << 8) | c2);
        outNameFloats[i] = (float)packed;
    }
}

struct EspCandidate {
    long entity;
    float dist;
    bool knocked;
    int team;
    long bones[14];
};

extern "C" JNIEXPORT jint JNICALL
Java_com_freezy_NativeBridge_getEspSnapshotDirect(JNIEnv* env, jclass clazz, jint pid, jfloatArray outData, jint flags) {
    if (pid <= 0 || !outData) return 0;

    GamePointers gp;
    if (!resolveGamePointers(pid, gp)) return 0;

    float myPos[3] = {0, 0, 0};
    {
        long mt = 0;
        if (readPtr(pid, gp.localPlayer + OFF_MAIN_CAMERA_TRANSFORM, mt) && isPlausiblePtr(mt))
            getTransformPosition(pid, mt, myPos);
    }

    g_hier_frame.fetch_add(1);

    std::vector<long> ents = getEntities(pid, gp.currentGame);
    const float ESP_MAX_DIST = 150.0f;
    const int MAX_ENTS = 15; // Maximo 15 enemigos mas cercanos proyectados simultaneamente
    float tempBuffer[MAX_ENTS * 40]; // 40 floats por entidad
    int shown = 0;
    int totalEnemiesInRange = 0;

    int screenW = g_screen_w.load();
    int screenH = g_screen_h.load();

    bool reqHealth = (flags & 8) != 0;
    bool reqName = (flags & 16) != 0;
    bool reqWeapon = (flags & 64) != 0;
    bool reqTeam = (flags & 128) != 0;
    bool reqIgnoreKnocked = (flags & 256) != 0;

    std::vector<EspCandidate> candidates;
    candidates.reserve(ents.size());

    // 1. PRE-FILTRADO Y CONTEO TOTAL REAL
    for (long e : ents) {
        if (e == gp.localPlayer) continue;

        // 1. ¿Muerto?
        uint8_t isDead = 0;
        if (!readU8(pid, e + OFF_PLAYER_IS_DEAD, isDead) || isDead) continue;

        // 2. Equipo
        int team = getTeamStatus(pid, e);
        if (team <= 0) continue;
        if (!reqTeam && team == 1) continue;

        // 3. Knocked
        bool knocked = isKnocked(pid, e);
        if (reqIgnoreKnocked && knocked) continue;

        // 4. Punteros de huesos (1 sola lectura en bloque de 0x60 bytes)
        long bones[14];
        readBonePtrBlock(pid, e, bones);
        if (bones[0] == 0 || !isPlausiblePtr(bones[0])) continue;

        float head[3] = {0, 0, 0};
        if (!getBonePosFromPtr(pid, bones[0], head)) continue;

        float dist = sqrtf((head[0]-myPos[0])*(head[0]-myPos[0]) +
                           (head[1]-myPos[1])*(head[1]-myPos[1]) +
                           (head[2]-myPos[2])*(head[2]-myPos[2]));

        if (dist > ESP_MAX_DIST) continue;

        // Es un enemigo/objetivo valido en rango
        totalEnemiesInRange++;

        EspCandidate cand;
        cand.entity = e;
        cand.dist = dist;
        cand.knocked = knocked;
        cand.team = team;
        memcpy(cand.bones, bones, sizeof(bones));
        candidates.push_back(cand);
    }

    // 2. ORDENAR POR PROXIMIDAD (los 15 mas cercanos tienen maxima prioridad)
    if (candidates.size() > (size_t)MAX_ENTS) {
        std::sort(candidates.begin(), candidates.end(), [](const EspCandidate& a, const EspCandidate& b) {
            return a.dist < b.dist;
        });
    }

    // 3. PROCESAMIENTO COMPLETO DE MATRIZ Y PANTALLA SOLO PARA EL TOP 15
    for (const auto& cand : candidates) {
        if (shown >= MAX_ENTS) break;
        long e = cand.entity;

        float vm[16];
        if (!getViewMatrix(pid, gp.localPlayer, vm)) continue;

        float skel[28];
        getSkeletonScreen(pid, gp.localPlayer, e, cand.bones, vm, screenW, screenH, skel);

        // Si la cabeza no proyecta en pantalla, omitir render
        if (skel[0] <= 0 || skel[1] <= 0) continue;

        int hp = reqHealth ? getPlayerHealth(pid, e) : 200;
        short wepId = reqWeapon ? getPlayerWeaponId(pid, e) : 0;
        float isBot = 0.0f;
        float nameFloats[6] = {0, 0, 0, 0, 0, 0};
        if (reqName) {
            readPlayerNamePacked(pid, e, nameFloats, isBot);
        }

        int baseIdx = shown * 40;
        tempBuffer[baseIdx + 0] = cand.knocked ? 1.0f : 0.0f;
        tempBuffer[baseIdx + 1] = cand.dist;
        tempBuffer[baseIdx + 2] = (float)cand.team;
        tempBuffer[baseIdx + 3] = (float)hp;
        tempBuffer[baseIdx + 4] = (float)wepId;
        tempBuffer[baseIdx + 5] = isBot;
        for (int i = 0; i < 6; i++) {
            tempBuffer[baseIdx + 6 + i] = nameFloats[i];
        }
        for (int i = 0; i < 28; i++) {
            tempBuffer[baseIdx + 12 + i] = skel[i];
        }
        shown++;
    }

    if (shown > 0) {
        env->SetFloatArrayRegion(outData, 0, shown * 40, tempBuffer);
    }

    // Empaquetar conteo total de enemigos en los 16 bits altos, y entidades dibujadas en los 16 bits bajos
    int result = ((totalEnemiesInRange & 0xFFFF) << 16) | (shown & 0xFFFF);
    return result;
}