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
#include <array>
#include <limits>
#include <mutex>
#include <unordered_map>
#include <dirent.h>
#include <sys/wait.h>
#include <signal.h>

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
static std::atomic<bool> g_aimbot_active{false};
static std::atomic<bool> g_camera_aim_active{false};
static std::atomic<bool> g_aimbot_running{false};
static std::thread g_aimbot_thread;
// 0 Head, 1 Neck, 2 Root, 3 Hip, 4 Foot.
static std::atomic<int> g_aim_target{0};
static std::atomic<int> g_aim_visible_fov{200};

// Controlador compartido para las funciones que escriben posición/dirección.
// Un solo hilo evita que Fly y Enemy Pull creen bucles concurrentes.
static std::atomic<bool> g_silent_aim_active{false};
static std::atomic<bool> g_enemy_pull_active{false};
// 0 Ninguna, 1 Arriba, 2 Abajo, 3 Izquierda, 4 Derecha.
static std::atomic<int> g_enemy_pull_direction{0};
static std::atomic<bool> g_fly_active{false};
static std::atomic<bool> g_no_reload_active{false};
static std::atomic<bool> g_feature_thread_running{false};
static std::thread g_feature_thread;

// Sniper Scope (aim-assist) estado
static std::atomic<bool> g_sniper_active{false};
static std::atomic<int> g_sniper_mode{0};                // 0 = Cabeza, 1 = Cuerpo
static std::atomic<bool> g_sniper_ignore_knocked{true};
static std::atomic<bool> g_sniper_ignore_bots{false};
static std::atomic<int> g_screen_w{1080};
static std::atomic<int> g_screen_h{1920};
static const float SNIPER_FOV_PX = 200.0f;
static const float SNIPER_MAX_DIST = 800.0f;
static const float AIM_DEADZONE_PX = 1.5f;   // precisión final cerca del centro

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
static pid_t g_io_child_pid = -1;
static std::atomic<int> g_helper_tried_pid{0};

static void closeRootMemIOLocked() {
    // Cerrar stdin primero entrega EOF a ffmem y permite que `su` termine limpio.
    if (g_io_out) { fclose(g_io_out); g_io_out = nullptr; }
    if (g_io_in) { fclose(g_io_in); g_io_in = nullptr; }
    g_io_pid = -1;

    if (g_io_child_pid > 0) {
        int status = 0;
        pid_t result = 0;
        for (int i = 0; i < 10; i++) {
            result = waitpid(g_io_child_pid, &status, WNOHANG);
            if (result == g_io_child_pid || result == -1) break;
            usleep(10000);
        }
        if (result == 0) {
            kill(g_io_child_pid, SIGTERM);
            waitpid(g_io_child_pid, &status, 0);
        }
        g_io_child_pid = -1;
    }
}

bool ensureHelperSpawned(int pid);

bool rootMemIOSpawn(int pid) {
    std::lock_guard<std::mutex> lock(g_io_mutex);
    if (g_io_in && g_io_out && g_io_pid == pid) return true;

    closeRootMemIOLocked();

    if (g_helper_path.empty() || pid <= 0) return false;
    g_helper_tried_pid = pid;

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
        g_io_child_pid = child;
        closeRootMemIOLocked();
        return false;
    }
    g_io_child_pid = child;
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
        closeRootMemIOLocked();
        return false;
    }
    fflush(g_io_out);

    std::string hex(1 + size * 2 + 8, '\0');
    if (fgets(hex.data(), (int)hex.size(), g_io_in) == nullptr) {
        closeRootMemIOLocked();
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

// Escribe mediante el helper persistente. El protocolo hexadecimal evita abrir un
// proceso `su` por cada frame y mantiene serializadas las operaciones de memoria.
bool rootMemIOWriteDirect(int pid, long address, const void* buffer, size_t size) {
    if (pid != g_io_pid || !g_io_in || !g_io_out || !buffer || size == 0 || size > 4096) {
        return false;
    }
    std::lock_guard<std::mutex> lock(g_io_mutex);
    if (!g_io_out || !g_io_in || pid != g_io_pid) return false;

    static const char HEX[] = "0123456789abcdef";
    const uint8_t* bytes = static_cast<const uint8_t*>(buffer);
    std::string command;
    command.reserve(32 + size * 2);
    char header[64];
    snprintf(header, sizeof(header), "W %llx %zx ", (unsigned long long)address, size);
    command += header;
    for (size_t i = 0; i < size; i++) {
        command.push_back(HEX[bytes[i] >> 4]);
        command.push_back(HEX[bytes[i] & 0x0F]);
    }
    command.push_back('\n');

    if (fputs(command.c_str(), g_io_out) < 0 || fflush(g_io_out) != 0) return false;
    char response[16] = {0};
    return fgets(response, sizeof(response), g_io_in) != nullptr && strncmp(response, "OK", 2) == 0;
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
        closeRootMemIOLocked();
        return false;
    }
    fflush(g_io_out);

    std::string hex(1 + totalBytes * 2 + 16, '\0');
    if (fgets(hex.data(), (int)hex.size(), g_io_in) == nullptr) {
        closeRootMemIOLocked();
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
        closeRootMemIOLocked();
        return false;
    }
    fflush(g_io_out);

    char resp[128] = {0};
    if (fgets(resp, sizeof(resp), g_io_in) == nullptr) {
        closeRootMemIOLocked();
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

// Silent Aim. Valores confirmados en el archivo Offsets recibido.
const long OFF_IS_FIRING = 0x540;              // Offsets.sAim1
const long OFF_IS_FIRING_ALT = 0x4E0;          // Offsets.IS_FIRING (mismo archivo)
const long OFF_SILENT_WEAPON = 0x978;          // Offsets.sAim2
const long OFF_WEAPON_START_POSITION = 0x38;   // Offsets.sAim3
const long OFF_WEAPON_AIM_DIRECTION = 0x2C;    // Offsets.sAim4

// FastReload / NoReload: localPlayer + PlayerAttributes -> +NoReload.
const long OFF_PLAYER_ATTRIBUTES = 0x4BC;
const long OFF_NO_RELOAD = 0x99;

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

static long getSelectedAimBoneOffset() {
    switch (g_aim_target.load()) {
        case 1: return OFF_BONE_NECK;
        case 2: return OFF_BONE_ROOT;
        case 3: return OFF_BONE_HIP;
        case 4: return OFF_BONE_RIGHT_FOOT;
        default: return OFF_BONE_HEAD;
    }
}

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

static bool processStillExists(int pid) {
    if (pid <= 0) return false;
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d", pid);
    return access(path, F_OK) == 0;
}

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
    
    // No lanzar `su -c dd` por cada lectura: si el helper fue denegado esto
    // generaba cientos de procesos/avisos por segundo. El error se propaga limpio.
    return false;
}

bool writeGameMemory(int pid, long address, const void* buffer, size_t size) {
    if (pid <= 0 || address <= 0 || !buffer || size == 0) return false;

    ensureHelperSpawned(pid);
    if (rootMemIOWriteDirect(pid, address, buffer, size)) return true;

    struct iovec local_iov = { const_cast<void*>(buffer), size };
    struct iovec remote_iov = { reinterpret_cast<void*>(address), size };
    ssize_t result = syscall(__NR_process_vm_writev, pid, &local_iov, 1, &remote_iov, 1, 0);
    return result == static_cast<ssize_t>(size);
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
#if UINTPTR_MAX > 0xFFFFFFFFU
    return u >= 0x10000 && u < 0x7fffffffffffULL && (u & 7) == 0;
#else
    return false;
#endif
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

// El offset principal corresponde al bool usado por el botón de disparo. El
// alternativo solo se consulta si el principal no puede leerse o no contiene
// un valor bool válido; hacer OR entre ambos podía dejar el Aimbot enganchado
// permanentemente cuando uno de los offsets pertenecía a otra build.
static bool readLocalFiringState(int pid, long localPlayer, bool& isFiring) {
    isFiring = false;
    uint8_t value = 0;
    if (readU8(pid, localPlayer + OFF_IS_FIRING, value) && value <= 1) {
        isFiring = value != 0;
        return true;
    }
    value = 0;
    if (readU8(pid, localPlayer + OFF_IS_FIRING_ALT, value) && value <= 1) {
        isFiring = value != 0;
        return true;
    }
    return false;
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

    bool is64 = (g_ptr_width.load() == 8);

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

    long offStatic = is64 ? 0xB8 : OFF_STATIC_CLASS;
    if (!readPtr(pid, gameFacade + offStatic, gp.staticFacade) || !isPlausiblePtr(gp.staticFacade)) {
        if (!readPtr(pid, gameFacade + OFF_STATIC_CLASS, gp.staticFacade) || !isPlausiblePtr(gp.staticFacade)) {
            LOGD("[FREEZY] chain: falló staticFacade");
            return false;
        }
    }

    if (!readPtr(pid, gp.staticFacade, gp.currentGame) || !isPlausiblePtr(gp.currentGame)) {
        LOGD("[FREEZY] chain: falló currentGame (staticFacade+0x0)");
        return false;
    }

    long offMatch = is64 ? 0x90 : OFF_CURRENT_MATCH;
    if (!readPtr(pid, gp.currentGame + offMatch, gp.currentMatch) || !isPlausiblePtr(gp.currentMatch)) {
        readPtr(pid, gp.currentGame + OFF_CURRENT_MATCH, gp.currentMatch);
    }

    if (isPlausiblePtr(gp.currentMatch)) {
        long offLocal = is64 ? 0x120 : OFF_LOCAL_PLAYER;
        if (!readPtr(pid, gp.currentMatch + offLocal, gp.localPlayer) || !isPlausiblePtr(gp.localPlayer)) {
            readPtr(pid, gp.currentMatch + OFF_LOCAL_PLAYER, gp.localPlayer);
        }
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

// Lee los 14 punteros de hueso de una entidad en UNA llamada.
// Fallback a lecturas individuales si el bloque falla.
bool readBonePtrBlock(int pid, long entity, long out[14]) {
    bool is64 = (g_ptr_width.load() == 8);
    if (is64) {
        for (int i = 0; i < 14; i++) {
            long bone = 0;
            readPtr(pid, entity + SKELETON_BONES[i], bone);
            out[i] = bone;
        }
        return true;
    }

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

// Resuelve muchas cadenas bone -> transform -> transformObj -> matrix/index usando el
// comando vectorial del helper. Si un lote contiene un puntero que dejó de ser válido,
// únicamente ese lote cae al resolvedor individual; el snapshot completo sigue siendo útil.
static void batchReadPointers(int pid, const std::vector<long>& addresses,
                              std::vector<long>& values, std::vector<uint8_t>& valid) {
    const int ptrWidth = g_ptr_width.load() == 8 ? 8 : 4;
    values.assign(addresses.size(), 0);
    valid.assign(addresses.size(), 0);

    for (size_t start = 0; start < addresses.size(); start += 64) {
        const int count = (int)std::min<size_t>(64, addresses.size() - start);
        long batchAddresses[64] = {0};
        size_t batchSizes[64] = {0};
        void* batchBuffers[64] = {nullptr};
        std::array<std::array<uint8_t, 8>, 64> raw{};
        for (int i = 0; i < count; ++i) {
            batchAddresses[i] = addresses[start + i];
            batchSizes[i] = (size_t)ptrWidth;
            batchBuffers[i] = raw[i].data();
        }
        if (!rootMemIOReadVectorDirect(pid, batchAddresses, batchSizes, batchBuffers, count)) continue;
        for (int i = 0; i < count; ++i) {
            long value = 0;
            if (ptrWidth == 4) {
                uint32_t value32 = 0;
                memcpy(&value32, raw[i].data(), sizeof(value32));
                value = (long)value32;
            } else {
                uint64_t value64 = 0;
                memcpy(&value64, raw[i].data(), sizeof(value64));
                value = (long)value64;
            }
            if (isPlausiblePtr(value)) {
                values[start + i] = value;
                valid[start + i] = 1;
            }
        }
    }
}

static void resolveBonePositionsBatch(int pid, const std::vector<long>& bones,
                                      std::vector<std::array<float, 3>>& positions,
                                      std::vector<uint8_t>& resolved) {
    const size_t count = bones.size();
    positions.assign(count, {0.0f, 0.0f, 0.0f});
    resolved.assign(count, 0);
    if (count == 0) return;

    std::vector<long> phaseAddresses;
    std::vector<size_t> phaseToBone;
    phaseAddresses.reserve(count);
    phaseToBone.reserve(count);
    for (size_t i = 0; i < count; ++i) {
        if (!isPlausiblePtr(bones[i])) continue;
        phaseAddresses.push_back(bones[i] + 0x8);
        phaseToBone.push_back(i);
    }

    std::vector<long> phaseValues;
    std::vector<uint8_t> phaseValid;
    batchReadPointers(pid, phaseAddresses, phaseValues, phaseValid);
    std::vector<long> transforms(count, 0);
    for (size_t i = 0; i < phaseAddresses.size(); ++i) {
        if (phaseValid[i]) transforms[phaseToBone[i]] = phaseValues[i];
    }

    phaseAddresses.clear();
    phaseToBone.clear();
    for (size_t i = 0; i < count; ++i) {
        if (!isPlausiblePtr(transforms[i])) continue;
        phaseAddresses.push_back(transforms[i] + 0x8);
        phaseToBone.push_back(i);
    }
    batchReadPointers(pid, phaseAddresses, phaseValues, phaseValid);
    std::vector<long> transformObjects(count, 0);
    for (size_t i = 0; i < phaseAddresses.size(); ++i) {
        if (phaseValid[i]) transformObjects[phaseToBone[i]] = phaseValues[i];
    }

    const bool is64 = g_ptr_width.load() == 8;
    std::vector<long> matrices(count, 0);
    std::vector<uint32_t> indices(count, 0);
    phaseToBone.clear();
    for (size_t i = 0; i < count; ++i) {
        if (isPlausiblePtr(transformObjects[i])) phaseToBone.push_back(i);
    }
    for (size_t start = 0; start < phaseToBone.size(); start += 64) {
        const int batchCount = (int)std::min<size_t>(64, phaseToBone.size() - start);
        long batchAddresses[64] = {0};
        size_t batchSizes[64] = {0};
        void* batchBuffers[64] = {nullptr};
        std::array<std::array<uint8_t, 12>, 64> raw{};
        for (int i = 0; i < batchCount; ++i) {
            const size_t boneIndex = phaseToBone[start + i];
            batchAddresses[i] = transformObjects[boneIndex] + 0x20;
            batchSizes[i] = is64 ? 12u : 8u;
            batchBuffers[i] = raw[i].data();
        }
        if (!rootMemIOReadVectorDirect(pid, batchAddresses, batchSizes, batchBuffers, batchCount)) continue;
        for (int i = 0; i < batchCount; ++i) {
            const size_t boneIndex = phaseToBone[start + i];
            if (is64) {
                uint64_t matrix64 = 0;
                memcpy(&matrix64, raw[i].data(), 8);
                matrices[boneIndex] = (long)matrix64;
                memcpy(&indices[boneIndex], raw[i].data() + 8, 4);
            } else {
                uint32_t matrix32 = 0;
                memcpy(&matrix32, raw[i].data(), 4);
                matrices[boneIndex] = (long)matrix32;
                memcpy(&indices[boneIndex], raw[i].data() + 4, 4);
            }
        }
    }

    for (size_t i = 0; i < count; ++i) {
        float pos[3] = {0, 0, 0};
        if (isPlausiblePtr(matrices[i]) && ensureHierarchy(pid, matrices[i]) &&
            resolvePosFromHier(pid, matrices[i], indices[i], pos)) {
            positions[i] = {pos[0], pos[1], pos[2]};
            resolved[i] = 1;
            continue;
        }
        // Una entidad puede desaparecer entre las tres fases. El fallback evita que
        // una región inválida elimine a los demás jugadores del snapshot.
        if (getBonePosFromPtr(pid, bones[i], pos)) {
            positions[i] = {pos[0], pos[1], pos[2]};
            resolved[i] = 1;
        }
    }
}

// Posición mundial de un hueso por índice (0..13).
bool getBonePosFast(int pid, long entity, int boneIdx, float* outPos) {
    long bones[14];
    readBonePtrBlock(pid, entity, bones);
    return getBonePosFromPtr(pid, bones[boneIdx], outPos);
}

// Diccionario de entidades (compatible 64-bit y 32-bit).
std::vector<long> getEntities(int pid, long currentGame) {
    std::vector<long> out;
    if (pid <= 0 || currentGame <= 0) return out;

    bool is64 = (g_ptr_width.load() == 8);

    long dict = 0;
    long offDict = is64 ? 0xC0 : OFF_DICT_ENTITIES; // 0xC0 en MatchGame (64-bit) o 0x68 (32-bit)
    if (!readPtr(pid, currentGame + offDict, dict) || !isPlausiblePtr(dict)) {
        if (!readPtr(pid, currentGame + OFF_DICT_ENTITIES, dict) || !isPlausiblePtr(dict)) return out;
    }

    long offCount = is64 ? 0x20 : OFF_DICT_COUNT;
    long offEntries = is64 ? 0x18 : OFF_DICT_ENTRIES_PTR;
    long offStart = is64 ? 0x20 : OFF_DICT_START;
    long offEntity = is64 ? 0x10 : OFF_ENTRY_ENTITY;
    long entryStride = is64 ? 0x18 : 0x10;

    int count = 0;
    if (!readI32(pid, dict + offCount, count)) return out;
    if (count < 1 || count > 1000) return out;

    long entries = 0;
    if (!readPtr(pid, dict + offEntries, entries) || !isPlausiblePtr(entries)) return out;

    const long start = entries + offStart;
    out.reserve((size_t)std::min(count, 128));

    // Las entradas son contiguas. Leerlas por páginas elimina dos viajes al helper
    // por cada hueco del Dictionary y mantiene cada solicitud por debajo de 16 KiB.
    const int entriesPerChunk = std::max(1, 16384 / (int)entryStride);
    for (int first = 0; first < count; first += entriesPerChunk) {
        const int chunkCount = std::min(entriesPerChunk, count - first);
        std::vector<uint8_t> raw((size_t)chunkCount * (size_t)entryStride);
        if (!readGameMemory(pid, start + (long)first * entryStride, raw.data(), raw.size())) {
            // Fallback conservador para diccionarios que cambian durante la lectura.
            for (int j = 0; j < chunkCount; ++j) {
                const long entry = start + (long)(first + j) * entryStride;
                int hash = -1;
                long entity = 0;
                if (!readI32(pid, entry + OFF_ENTRY_HASH, hash) || hash < 0) continue;
                if (!readPtr(pid, entry + offEntity, entity) || !isPlausiblePtr(entity)) continue;
                out.push_back(entity);
            }
            continue;
        }
        for (int j = 0; j < chunkCount; ++j) {
            const uint8_t* entry = raw.data() + (size_t)j * (size_t)entryStride;
            int hash = -1;
            memcpy(&hash, entry + OFF_ENTRY_HASH, sizeof(hash));
            if (hash < 0) continue;
            long entity = 0;
            if (is64) {
                uint64_t entity64 = 0;
                memcpy(&entity64, entry + offEntity, 8);
                entity = (long)entity64;
            } else {
                uint32_t entity32 = 0;
                memcpy(&entity32, entry + offEntity, 4);
                entity = (long)entity32;
            }
            if (isPlausiblePtr(entity)) out.push_back(entity);
        }
    }
    return out;
}

// Matriz de vista (localPlayer -> FollowCamera -> Camera -> CameraBase -> ViewMatrix).
bool getViewMatrix(int pid, long localPlayer, float* m) {
    bool is64 = (g_ptr_width.load() == 8);
    long followCamera = 0;
    long offFollow = is64 ? 0x770 : OFF_FOLLOW_CAMERA;
    if (!readPtr(pid, localPlayer + offFollow, followCamera) || !isPlausiblePtr(followCamera)) {
        if (!readPtr(pid, localPlayer + OFF_FOLLOW_CAMERA, followCamera) || !isPlausiblePtr(followCamera)) return false;
    }

    long camera = 0;
    long offCam = is64 ? 0x30 : OFF_CAMERA;
    if (!readPtr(pid, followCamera + offCam, camera) || !isPlausiblePtr(camera)) {
        if (!readPtr(pid, followCamera + OFF_CAMERA, camera) || !isPlausiblePtr(camera)) return false;
    }

    long cameraBase = 0;
    long offCamBase = is64 ? 0x10 : OFF_CAMERA_BASE;
    if (!readPtr(pid, camera + offCamBase, cameraBase) || !isPlausiblePtr(cameraBase)) {
        if (!readPtr(pid, camera + OFF_CAMERA_BASE, cameraBase) || !isPlausiblePtr(cameraBase)) return false;
    }

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

bool normalizeQuaternion(float* q) {
    float length = sqrtf(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
    if (!std::isfinite(length) || length < 0.0001f) return false;
    q[0] /= length;
    q[1] /= length;
    q[2] /= length;
    q[3] /= length;
    return true;
}

// Quaternion LookRotation hacia un punto (port de SniperScope.cs).
void aimAt(int pid, long localPlayer, const float* myPos, const float* targetPos,
           float smoothing) {
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
        if (readGameMemory(pid, localPlayer + OFF_AIM_ROTATION, cur, 16) &&
            normalizeQuaternion(cur)) {
            quatSlerp(cur, q, std::max(0.01f, std::min(1.0f, smoothing)), q);
        }
    }

    if (!normalizeQuaternion(q)) return;

    writeGameMemory(pid, localPlayer + OFF_AIM_ROTATION, q, 16);
}

// ==============================================================================================
// [LÓGICA DEL AIMBOT + SNIPER SCOPE]
// ==============================================================================================

void aimbotThreadFunction() {
    LOGI("[FREEZY] Aimbot thread iniciado");
    int failStreak = 0;
    int resolvedPid = -1;
    long lockedAimTarget = 0;
    long challengerAimTarget = 0;
    auto challengerAimSince = std::chrono::steady_clock::time_point{};
    auto lockedAimLastSeen = std::chrono::steady_clock::time_point{};
    auto lastAimSample = std::chrono::steady_clock::time_point{};
    float lastRawAimPoint[3] = {0, 0, 0};
    float filteredAimPoint[3] = {0, 0, 0};
    bool haveFilteredAimPoint = false;
    int lastAimTargetMode = -1;
    GamePointers gp;
    float viewMatrix[16] = {0};
    auto lastPointerRefresh = std::chrono::steady_clock::time_point{};

    // Hilo único de larga vida. Los modos se pausan mediante sus flags atómicos;
    // así un OFF/ON rápido nunca crea dos escritores concurrentes.
    while (true) {
        int pid = g_game_pid.load();
        if (pid <= 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
            continue;
        }

        if (pid != resolvedPid) {
            gp = GamePointers{};
            resolvedPid = pid;
            lockedAimTarget = 0;
            challengerAimTarget = 0;
            haveFilteredAimPoint = false;
        }

        bool aimActive = g_aimbot_active.load();
        bool cameraAimActive = g_camera_aim_active.load();
        bool sniperActive = g_sniper_active.load();
        int aimTarget = g_aim_target.load();
        if (!aimActive && !cameraAimActive && !sniperActive) {
            failStreak = 0;
            lockedAimTarget = 0;
            challengerAimTarget = 0;
            haveFilteredAimPoint = false;
            lastAimTargetMode = -1;
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }

        auto now = std::chrono::steady_clock::now();
        bool refreshPointers = !gp.valid || gp.localPlayer == 0 ||
            std::chrono::duration_cast<std::chrono::milliseconds>(
                now - lastPointerRefresh).count() >= 1000;
        if (refreshPointers) {
            GamePointers fresh;
            if (!resolveGamePointers(pid, fresh) || !isPlausiblePtr(fresh.localPlayer)) {
                gp = GamePointers{};
                failStreak++;
                std::this_thread::sleep_for(std::chrono::milliseconds(failStreak > 5 ? 300 : 100));
                continue;
            }
            gp = fresh;
            lastPointerRefresh = now;
            failStreak = 0;
            LOGI("[FREEZY] Cadena resuelta: facade=0x%llx game=0x%llx match=0x%llx local=0x%llx",
                 (unsigned long long)gp.facade, (unsigned long long)gp.currentGame,
                 (unsigned long long)gp.currentMatch, (unsigned long long)gp.localPlayer);
        }

        long localPlayer = gp.localPlayer;

        // El Aimbot de cámara solamente toma control mientras el juego confirma
        // que el botón de disparo está presionado. Aim Visible y Sniper conservan
        // su comportamiento independiente.
        bool cameraAimEngaged = cameraAimActive;
        if (cameraAimActive) {
            bool isFiring = false;
            cameraAimEngaged = readLocalFiringState(pid, localPlayer, isFiring) && isFiring;
            if (!cameraAimEngaged && !aimActive && !sniperActive) {
                lockedAimTarget = 0;
                challengerAimTarget = 0;
                haveFilteredAimPoint = false;
                lastAimTargetMode = -1;
                failStreak = 0;
                std::this_thread::sleep_for(std::chrono::milliseconds(4));
                continue;
            }
        }

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
        if (sniperActive && !aimActive && !cameraAimEngaged && !holdingSniper) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        std::vector<long> entities = getEntities(pid, gp.currentGame);

        // Aim Visible conserva un movimiento suave y su propio FOV configurable.
        // Aimbot es el modo de fijación rápida/precisa y usa un radio mayor.
        float activeFov = sniperActive ? SNIPER_FOV_PX : 0.0f;
        if (aimActive) activeFov = std::max(activeFov, (float)g_aim_visible_fov.load());
        if (cameraAimEngaged) activeFov = std::max(activeFov, 400.0f);

        float bestDist = activeFov;
        float bestScore = activeFov;
        long bestTarget = 0;
        float bestBonePos[3] = {0, 0, 0};
        uint32_t bestCollider = 0;
        long lockedCandidate = 0;
        float lockedCandidateCrossDist = 0.0f;
        float lockedCandidateWorldDist = 0.0f;
        float lockedCandidateBonePos[3] = {0, 0, 0};
        uint32_t lockedCandidateCollider = 0;
        long nearestAlternative = 0;
        float nearestAlternativeCrossDist = 0.0f;
        float nearestAlternativeWorldDist = std::numeric_limits<float>::max();
        float nearestAlternativeBonePos[3] = {0, 0, 0};
        uint32_t nearestAlternativeCollider = 0;
        bool deliberateTargetSwitch = false;
        bool lockedTargetDefinitelyInvalid = false;

        for (long e : entities) {
            if (e == localPlayer) continue;
            bool isLockedTarget = (e == lockedAimTarget);

            uint8_t isDead = 0;
            if (readU8(pid, e + OFF_PLAYER_IS_DEAD, isDead) && isDead) {
                if (isLockedTarget) lockedTargetDefinitelyInvalid = true;
                continue;
            }

            int team = getTeamStatus(pid, e);
            if (team == 1) {
                if (isLockedTarget) lockedTargetDefinitelyInvalid = true;
                continue;
            }
            if (team != 2) continue;          // desconocido -> no es objetivo

            if ((aimActive || cameraAimEngaged || g_sniper_ignore_knocked.load()) && isKnocked(pid, e)) {
                if (isLockedTarget) lockedTargetDefinitelyInvalid = true;
                continue;
            }
            if (g_sniper_ignore_bots.load()) {
                uint8_t isBot = 0;
                if (readU8(pid, e + OFF_IS_CLIENT_BOT, isBot) && isBot) continue;
            }

            // Posición del objetivo (cabeza o cuerpo según modo)
            long boneOffset = (aimActive || cameraAimEngaged)
                ? getSelectedAimBoneOffset()
                : ((g_sniper_mode.load() == 1) ? OFF_BONE_HIP : OFF_BONE_HEAD);
            float bonePos[3] = {0, 0, 0};
            bool haveBone = getBonePosition(pid, e, boneOffset, bonePos);
            // Algunos modelos no crean RightFoot. Foot conserva el objetivo elegido,
            // pero cae en LeftFoot/RightAnkle en vez de perderlo y saltar a otro enemigo.
            if (!haveBone && aimTarget == 4 && (aimActive || cameraAimEngaged)) {
                haveBone = getBonePosition(pid, e, OFF_BONE_LEFT_FOOT, bonePos) ||
                           getBonePosition(pid, e, OFF_BONE_RIGHT_ANKLE, bonePos);
            }
            if (!haveBone) continue;
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
            // Histéresis: el objetivo ya fijado puede alejarse un 35% adicional
            // antes de liberarse. Evita el rebote en el borde del FOV.
            float lockFovMultiplier = aimActive ? 1.50f : 1.25f;
            float allowedFov = isLockedTarget ? activeFov * lockFovMultiplier : activeFov;
            if (crossDist > allowedFov) continue;

            uint32_t candidateCollider = 0;
            if (aimActive && !cameraAimEngaged && aimTarget == 0) {
                readGameMemory(pid, e + OFF_COLLIDER, &candidateCollider,
                               sizeof(candidateCollider));
            }

            // La adquisición inicial sigue favoreciendo al enemigo más próximo a
            // la mira. El lock se decide después del recorrido para poder compararlo
            // también con la distancia real de los demás enemigos.
            float selectionScore = crossDist;
            if (selectionScore < bestScore) {
                bestScore = selectionScore;
                bestDist = crossDist;
                bestTarget = e;
                bestBonePos[0] = bonePos[0];
                bestBonePos[1] = bonePos[1];
                bestBonePos[2] = bonePos[2];
                bestCollider = candidateCollider;
            }

            if (isLockedTarget) {
                lockedCandidate = e;
                lockedCandidateCrossDist = crossDist;
                lockedCandidateWorldDist = dist;
                memcpy(lockedCandidateBonePos, bonePos, sizeof(lockedCandidateBonePos));
                lockedCandidateCollider = candidateCollider;
            } else if (crossDist <= activeFov && dist < nearestAlternativeWorldDist) {
                nearestAlternative = e;
                nearestAlternativeCrossDist = crossDist;
                nearestAlternativeWorldDist = dist;
                memcpy(nearestAlternativeBonePos, bonePos,
                       sizeof(nearestAlternativeBonePos));
                nearestAlternativeCollider = candidateCollider;
            }
        }

        if (lockedCandidate != 0) {
            // Por defecto se conserva el lock para que dos jugadores juntos no hagan
            // rebotar la cámara. Camera Aimbot sí puede cambiarlo si aparece un rival
            // claramente más cercano: 60% de la distancia cambia de inmediato;
            // una ventaja menor debe mantenerse 70 ms para confirmar el reemplazo.
            bestTarget = lockedCandidate;
            bestDist = lockedCandidateCrossDist;
            memcpy(bestBonePos, lockedCandidateBonePos, sizeof(bestBonePos));
            bestCollider = lockedCandidateCollider;

            bool switchImmediately = cameraAimEngaged && nearestAlternative != 0 &&
                nearestAlternativeWorldDist <= lockedCandidateWorldDist * 0.60f;
            bool confirmCloser = cameraAimEngaged && nearestAlternative != 0 &&
                nearestAlternativeWorldDist <= lockedCandidateWorldDist * 0.78f &&
                nearestAlternativeCrossDist <= activeFov * 0.90f;

            if (switchImmediately) {
                challengerAimTarget = 0;
                deliberateTargetSwitch = true;
                bestTarget = nearestAlternative;
                bestDist = nearestAlternativeCrossDist;
                memcpy(bestBonePos, nearestAlternativeBonePos, sizeof(bestBonePos));
                bestCollider = nearestAlternativeCollider;
            } else if (confirmCloser) {
                if (challengerAimTarget != nearestAlternative) {
                    challengerAimTarget = nearestAlternative;
                    challengerAimSince = now;
                } else if (std::chrono::duration_cast<std::chrono::milliseconds>(
                               now - challengerAimSince).count() >= 70) {
                    challengerAimTarget = 0;
                    deliberateTargetSwitch = true;
                    bestTarget = nearestAlternative;
                    bestDist = nearestAlternativeCrossDist;
                    memcpy(bestBonePos, nearestAlternativeBonePos, sizeof(bestBonePos));
                    bestCollider = nearestAlternativeCollider;
                }
            } else {
                challengerAimTarget = 0;
            }
        } else {
            challengerAimTarget = 0;
        }

        bool holdingLockGrace = false;
        if (lockedAimTarget != 0 && bestTarget != lockedAimTarget &&
            !deliberateTargetSwitch) {
            long missingMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                now - lockedAimLastSeen).count();
            // Una lectura aislada de bone/avatar/dictionary no cambia de enemigo.
            // Solo se libera inmediatamente si se confirmó muerte/equipo/knocked.
            long graceMs = aimActive ? 1500L : 350L;
            if (!lockedTargetDefinitelyInvalid && missingMs < graceMs) {
                bestTarget = 0;
                holdingLockGrace = true;
            } else {
                lockedAimTarget = 0;
                challengerAimTarget = 0;
                haveFilteredAimPoint = false;
            }
        }

        if (bestTarget != 0) {
            bool targetChanged = bestTarget != lockedAimTarget || aimTarget != lastAimTargetMode;
            lockedAimTarget = bestTarget;
            lockedAimLastSeen = now;
            lastAimTargetMode = aimTarget;

            // Filtrado predictivo corto: responde rápido a cambios bruscos sin copiar
            // cada pequeña oscilación de la animación de cabeza al quaternion de cámara.
            if (targetChanged || !haveFilteredAimPoint) {
                memcpy(filteredAimPoint, bestBonePos, sizeof(filteredAimPoint));
                memcpy(lastRawAimPoint, bestBonePos, sizeof(lastRawAimPoint));
                lastAimSample = now;
                haveFilteredAimPoint = true;
            } else {
                float dt = std::chrono::duration<float>(now - lastAimSample).count();
                dt = std::max(0.001f, std::min(0.100f, dt));
                float velocity[3] = {
                    (bestBonePos[0] - lastRawAimPoint[0]) / dt,
                    (bestBonePos[1] - lastRawAimPoint[1]) / dt,
                    (bestBonePos[2] - lastRawAimPoint[2]) / dt
                };
                float velocityLength = sqrtf(velocity[0]*velocity[0] +
                                             velocity[1]*velocity[1] +
                                             velocity[2]*velocity[2]);
                if (std::isfinite(velocityLength) && velocityLength > 28.0f) {
                    float scale = 28.0f / velocityLength;
                    velocity[0] *= scale;
                    velocity[1] *= scale;
                    velocity[2] *= scale;
                }
                float leadSeconds = cameraAimEngaged ? 0.030f : 0.015f;
                float predicted[3] = {
                    bestBonePos[0] + velocity[0] * leadSeconds,
                    bestBonePos[1] + velocity[1] * leadSeconds,
                    bestBonePos[2] + velocity[2] * leadSeconds
                };
                float pointBlend = cameraAimEngaged ? 0.68f : 0.42f;
                for (int i = 0; i < 3; i++) {
                    filteredAimPoint[i] += (predicted[i] - filteredAimPoint[i]) * pointBlend;
                    lastRawAimPoint[i] = bestBonePos[i];
                }
                lastAimSample = now;
            }

            if ((sniperActive || cameraAimEngaged || aimActive) &&
                bestDist > AIM_DEADZONE_PX) {
                float distanceRatio = activeFov > 1.0f
                    ? std::max(0.0f, std::min(1.0f, bestDist / activeFov))
                    : 0.0f;
                // Lejos: adquisición casi inmediata. Cerca: menor ganancia para
                // eliminar temblor mientras el juego y el usuario mueven la cámara.
                float smoothing = cameraAimEngaged
                    ? (0.38f + 0.56f * sqrtf(distanceRatio))
                    : (0.14f + 0.24f * distanceRatio);
                aimAt(pid, localPlayer, myPos, filteredAimPoint, smoothing);
            }
            if (aimActive && !cameraAimEngaged && aimTarget == 0 && bestCollider != 0) {
                // Una escritura por ciclo basta; repetirla once veces cada 8 ms
                // saturaba el helper de memoria y podía producir tirones severos.
                writeGameMemory(pid, bestTarget + OFF_LOCKED_AIMING_COLLIDER,
                                &bestCollider, sizeof(bestCollider));
            }
            failStreak = 0;
        } else if (!holdingLockGrace) {
            lockedAimTarget = 0;
            challengerAimTarget = 0;
            haveFilteredAimPoint = false;
            failStreak++;
        } else {
            failStreak = 0;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(
            failStreak > 5 ? 100 : (cameraAimEngaged ? 4 : (aimActive ? 6 : 20))));
    }

}

// ==============================================================================================
// [SILENT AIM / ENEMY PULL / FLY / TELEPORT]
// ==============================================================================================

static bool normalizeVector3(float* v) {
    float length = sqrtf(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    if (!std::isfinite(length) || length < 0.0001f) return false;
    v[0] /= length;
    v[1] /= length;
    v[2] /= length;
    return true;
}

static bool isFiniteVector3(const float* v) {
    return std::isfinite(v[0]) && std::isfinite(v[1]) && std::isfinite(v[2]);
}

static bool getTransformPositionStorage(int pid, long transform, long& positionAddress,
                                        float* position) {
    positionAddress = 0;
    long transformObject = 0;
    if (!readPtr(pid, transform + 0x8, transformObject) ||
        !isPlausiblePtr(transformObject)) return false;

    uint32_t transformIndex = 0;
    long hierarchy = 0, matrixList = 0;
    if (!readU32(pid, transformObject + 0x24, transformIndex) || transformIndex > 4096) return false;
    if (!readPtr(pid, transformObject + 0x20, hierarchy) || !isPlausiblePtr(hierarchy)) return false;
    if (!readPtr(pid, hierarchy + 0x18, matrixList) || !isPlausiblePtr(matrixList)) return false;

    positionAddress = matrixList + (long)transformIndex * 0x30;
    return readGameMemory(pid, positionAddress, position, 12) && isFiniteVector3(position);
}

// Obtiene la entrada real de posición del Transform Root. El valor de +0x20 es
// el objeto de jerarquía, NO una matriz de posición; escribir hierarchy+0x80
// corrompe rotación/escala y producía personajes anchos, altos o completamente 2D.
static bool getRootPositionStorage(int pid, long player, long& positionAddress, float* position) {
    positionAddress = 0;
    long root = 0, transform = 0;
    if (!readPtr(pid, player + OFF_BONE_ROOT, root) || !isPlausiblePtr(root)) return false;
    if (!readPtr(pid, root + 0x8, transform) || !isPlausiblePtr(transform)) return false;
    return getTransformPositionStorage(pid, transform, positionAddress, position);
}

// Parche FlyHook recibido por el usuario. Son instrucciones ARM32; antes de
// escribir se exige que los 8 bytes coincidan exactamente con ON u OFF.
static bool applyFlyPhysicsPatch(int pid, bool enabled) {
    if (g_ptr_width.load() != 4) return false;
    long unityBase = 0;
    int unityPointerWidth = 4;
    if (!rootMemIOGetModuleBase(pid, "libunity.so", unityBase, unityPointerWidth) ||
        unityBase <= 0 || unityPointerWidth != 4) return false;

    static const uint8_t PATCH_ON[8]  = {0xAC, 0xC5, 0xA9, 0x3F, 0x00, 0x10, 0xA0, 0xE1};
    static const uint8_t PATCH_OFF[8] = {0xAC, 0xC5, 0x27, 0x37, 0x00, 0x10, 0xA0, 0xE1};
    const long address = unityBase + 0x64DA5C;
    uint8_t current[8] = {0};
    if (!readGameMemory(pid, address, current, sizeof(current))) return false;
    if (enabled && memcmp(current, PATCH_ON, sizeof(current)) == 0) return true;
    if (!enabled && memcmp(current, PATCH_OFF, sizeof(current)) == 0) return true;
    const uint8_t* expected = enabled ? PATCH_OFF : PATCH_ON;
    const uint8_t* replacement = enabled ? PATCH_ON : PATCH_OFF;
    if (memcmp(current, expected, sizeof(current)) != 0) {
        LOGW("[FREEZY] FlyHook omitido: bytes/RVA no corresponden a esta versión");
        return false;
    }
    return writeGameMemory(pid, address, replacement, sizeof(current));
}

static bool getLocalCameraPosition(int pid, long localPlayer, float* position) {
    long cameraTransform = 0;
    return readPtr(pid, localPlayer + OFF_MAIN_CAMERA_TRANSFORM, cameraTransform) &&
           isPlausiblePtr(cameraTransform) &&
           getTransformPosition(pid, cameraTransform, position) &&
           isFiniteVector3(position);
}

// Selecciona el enemigo visible más cercano al centro de pantalla. Un límite <= 0
// deshabilita la restricción correspondiente (Silent Aim usa así todo el viewport).
static bool findClosestFeatureTarget(int pid, const GamePointers& gp, const float* viewMatrix,
                                     const float* localPosition, float maxWorldDistance,
                                     float maxFov, long boneOffset, long& target,
                                     float* targetPosition) {
    target = 0;
    float bestScreenDistance = maxFov > 0.0f ? maxFov : 1.0e9f;
    int width = g_screen_w.load();
    int height = g_screen_h.load();

    for (long entity : getEntities(pid, gp.currentGame)) {
        if (entity == gp.localPlayer) continue;

        uint8_t dead = 0;
        if (readU8(pid, entity + OFF_PLAYER_IS_DEAD, dead) && dead) continue;
        if (getTeamStatus(pid, entity) != 2 || isKnocked(pid, entity)) continue;

        float bonePosition[3] = {0, 0, 0};
        bool haveBone = getBonePosition(pid, entity, boneOffset, bonePosition);
        if (!haveBone && boneOffset == OFF_BONE_RIGHT_FOOT) {
            haveBone = getBonePosition(pid, entity, OFF_BONE_LEFT_FOOT, bonePosition) ||
                       getBonePosition(pid, entity, OFF_BONE_RIGHT_ANKLE, bonePosition);
        }
        if (!haveBone || !isFiniteVector3(bonePosition)) continue;

        float dx = bonePosition[0] - localPosition[0];
        float dy = bonePosition[1] - localPosition[1];
        float dz = bonePosition[2] - localPosition[2];
        float worldDistance = sqrtf(dx * dx + dy * dy + dz * dz);
        if (maxWorldDistance > 0.0f && worldDistance > maxWorldDistance) continue;

        float sx = 0.0f, sy = 0.0f;
        if (!worldToScreen(viewMatrix, bonePosition, width, height, sx, sy)) continue;
        if (sx < 1.0f || sy < 1.0f || sx > width || sy > height) continue;

        float screenDx = sx - width / 2.0f;
        float screenDy = sy - height / 2.0f;
        float screenDistance = sqrtf(screenDx * screenDx + screenDy * screenDy);
        if (screenDistance < bestScreenDistance) {
            bestScreenDistance = screenDistance;
            target = entity;
            memcpy(targetPosition, bonePosition, 12);
        }
    }
    return target != 0;
}

static bool prepareFeatureAccess(int& pid) {
    pid = g_game_pid.load();
    if (pid > 0 && !processStillExists(pid)) {
        std::lock_guard<std::mutex> lock(g_io_mutex);
        closeRootMemIOLocked();
        g_game_pid = -1;
        g_base_pid = -1;
        g_game_base = 0;
        g_helper_tried_pid = 0;
        pid = -1;
    }
    if (pid <= 0) {
        pid = findGamePidNative();
        if (pid > 0) g_game_pid = pid;
    }
    if (pid <= 0 || getGameBase(pid) == 0) return false;
    ensureHelperSpawned(pid);
    return true;
}

static void restorePulledEnemy(int pid, long& target, std::array<float, 3>& appliedOffset,
                               bool& haveTarget) {
    if (target != 0 && haveTarget) {
        long positionAddress = 0;
        float current[3] = {0, 0, 0};
        if (getRootPositionStorage(pid, target, positionAddress, current)) {
            // Retirar solo nuestro último desplazamiento conserva el movimiento
            // natural que el juego haya aplicado desde el frame anterior.
            current[0] -= appliedOffset[0];
            current[1] -= appliedOffset[1];
            current[2] -= appliedOffset[2];
            writeGameMemory(pid, positionAddress, current, 12);
        }
    }
    target = 0;
    appliedOffset = {0.0f, 0.0f, 0.0f};
    haveTarget = false;
}

static bool applyNoReloadState(int pid, long localPlayer, bool enabled) {
    long attributes = 0;
    if (!readPtr(pid, localPlayer + OFF_PLAYER_ATTRIBUTES, attributes) ||
        !isPlausiblePtr(attributes)) return false;
    uint8_t value = enabled ? 1 : 0;
    return writeGameMemory(pid, attributes + OFF_NO_RELOAD, &value, sizeof(value));
}

static void featureThreadFunction() {
    LOGI("[FREEZY] Controlador de funciones iniciado");
    int resolvedPid = -1;
    GamePointers gp;
    long pulledTarget = 0;
    std::array<float, 3> appliedPullOffset{0.0f, 0.0f, 0.0f};
    bool havePulledTarget = false;
    int pulledReadFailures = 0;
    float flyBaseHeight = 0.0f;
    float flyTargetHeight = 0.0f;
    bool flyHeightInitialized = false;
    bool flyWasActive = false;
    bool flyPatchApplied = false;
    bool noReloadWasActive = false;
    auto lastPointerRefresh = std::chrono::steady_clock::time_point{};

    while (true) {
        bool silentActive = g_silent_aim_active.load();
        bool pullActive = g_enemy_pull_active.load();
        int pullDirection = g_enemy_pull_direction.load();
        bool flyActive = g_fly_active.load();
        bool noReloadActive = g_no_reload_active.load();

        int pid = g_game_pid.load();
        if (pid <= 0 || (!silentActive && !pullActive && !flyActive && !noReloadActive)) {
            if ((!pullActive || pullDirection == 0) && havePulledTarget && pid > 0) {
                restorePulledEnemy(pid, pulledTarget, appliedPullOffset, havePulledTarget);
            }
            if (noReloadWasActive && pid > 0 && gp.valid && isPlausiblePtr(gp.localPlayer)) {
                applyNoReloadState(pid, gp.localPlayer, false);
                noReloadWasActive = false;
            }
            if (!flyActive) {
                if (flyPatchApplied && pid > 0) applyFlyPhysicsPatch(pid, false);
                flyPatchApplied = false;
                flyHeightInitialized = false;
                flyWasActive = false;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }

        if (pid != resolvedPid) {
            if (havePulledTarget && resolvedPid > 0) {
                restorePulledEnemy(resolvedPid, pulledTarget, appliedPullOffset,
                                   havePulledTarget);
            }
            gp = GamePointers{};
            resolvedPid = pid;
            pulledReadFailures = 0;
            flyHeightInitialized = false;
            flyPatchApplied = false;
        }
        auto now = std::chrono::steady_clock::now();
        bool refreshPointers = !gp.valid || !isPlausiblePtr(gp.localPlayer) ||
            std::chrono::duration_cast<std::chrono::milliseconds>(
                now - lastPointerRefresh).count() >= 1000;
        if (refreshPointers) {
            GamePointers fresh;
            if (!resolveGamePointers(pid, fresh) || !isPlausiblePtr(fresh.localPlayer)) {
                gp = GamePointers{};
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                continue;
            }
            gp = fresh;
            lastPointerRefresh = now;
        }

        float viewMatrix[16] = {0};
        float cameraPosition[3] = {0, 0, 0};
        bool haveView = getViewMatrix(pid, gp.localPlayer, viewMatrix);
        bool haveCameraPosition = getLocalCameraPosition(pid, gp.localPlayer, cameraPosition);

        // Mantener la dirección lista continuamente evita depender de un único offset
        // IsFiring (el archivo recibido contiene 0x540 y 0x4E0 para builds distintos).
        if (silentActive && haveView && haveCameraPosition) {
            long target = 0;
            float targetPosition[3] = {0, 0, 0};
            int aimTarget = g_aim_target.load();
            if (findClosestFeatureTarget(pid, gp, viewMatrix, cameraPosition,
                                         0.0f, 0.0f, getSelectedAimBoneOffset(),
                                         target, targetPosition)) {
                long weapon = 0;
                if (readPtr(pid, gp.localPlayer + OFF_SILENT_WEAPON, weapon) && isPlausiblePtr(weapon)) {
                    float startPosition[3] = {0, 0, 0};
                    if (readGameMemory(pid, weapon + OFF_WEAPON_START_POSITION,
                                       startPosition, 12) && isFiniteVector3(startPosition)) {
                        if (aimTarget == 0) targetPosition[1] += 0.1f;
                        float direction[3] = {
                            targetPosition[0] - startPosition[0],
                            targetPosition[1] - startPosition[1],
                            targetPosition[2] - startPosition[2]
                        };
                        writeGameMemory(pid, weapon + OFF_WEAPON_AIM_DIRECTION, direction, 12);
                    }
                }
            }
        }

        // Enemy Pull fija una sola entidad cercana al centro (300 px). La dirección
        // es exclusiva y el jugador local nunca se modifica.
        if (pullActive && pullDirection != 0 && haveView && haveCameraPosition) {
            if (pulledTarget != 0) {
                uint8_t dead = 0;
                bool confirmedDead = readU8(pid, pulledTarget + OFF_PLAYER_IS_DEAD, dead) && dead;
                int team = getTeamStatus(pid, pulledTarget);
                if (confirmedDead) {
                    // No escribir sobre una entidad destruida o reciclada.
                    pulledTarget = 0;
                    appliedPullOffset = {0.0f, 0.0f, 0.0f};
                    havePulledTarget = false;
                    pulledReadFailures = 0;
                } else if (team == 1 || isKnocked(pid, pulledTarget)) {
                    restorePulledEnemy(pid, pulledTarget, appliedPullOffset, havePulledTarget);
                    pulledReadFailures = 0;
                }
            }

            if (pulledTarget == 0) {
                float selectedHead[3] = {0, 0, 0};
                long selected = 0;
                if (findClosestFeatureTarget(pid, gp, viewMatrix, cameraPosition,
                                             800.0f, 300.0f, OFF_BONE_HEAD,
                                             selected, selectedHead)) {
                    long positionAddress = 0;
                    float current[3] = {0, 0, 0};
                    if (getRootPositionStorage(pid, selected, positionAddress, current)) {
                        pulledTarget = selected;
                        appliedPullOffset = {0.0f, 0.0f, 0.0f};
                        havePulledTarget = true;
                        pulledReadFailures = 0;
                    }
                }
            }

            if (pulledTarget != 0 && havePulledTarget) {
                long positionAddress = 0;
                float current[3] = {0, 0, 0};
                if (getRootPositionStorage(pid, pulledTarget, positionAddress, current)) {
                    float basePosition[3] = {
                        current[0] - appliedPullOffset[0],
                        current[1] - appliedPullOffset[1],
                        current[2] - appliedPullOffset[2]
                    };
                    float desiredOffset[3] = {0.0f, 0.0f, 0.0f};
                    constexpr float pullDistance = 3.0f;
                    if (pullDirection == 1) {
                        desiredOffset[1] = pullDistance;
                    } else if (pullDirection == 2) {
                        desiredOffset[1] = -pullDistance;
                    } else {
                        float forward[3] = {-viewMatrix[8], 0.0f, -viewMatrix[10]};
                        if (normalizeVector3(forward)) {
                            // forward x up produce el eje derecho horizontal de cámara.
                            float right[3] = {-forward[2], 0.0f, forward[0]};
                            float sign = pullDirection == 3 ? -1.0f : 1.0f;
                            desiredOffset[0] = right[0] * pullDistance * sign;
                            desiredOffset[2] = right[2] * pullDistance * sign;
                        }
                    }
                    float destination[3] = {
                        basePosition[0] + desiredOffset[0],
                        basePosition[1] + desiredOffset[1],
                        basePosition[2] + desiredOffset[2]
                    };
                    if (writeGameMemory(pid, positionAddress, destination, 12)) {
                        appliedPullOffset = {
                            desiredOffset[0], desiredOffset[1], desiredOffset[2]
                        };
                        pulledReadFailures = 0;
                    } else {
                        ++pulledReadFailures;
                    }
                } else {
                    ++pulledReadFailures;
                }

                // Varias lecturas fallidas consecutivas indican una entidad ya reciclada.
                // Liberarla evita conservar punteros obsoletos entre cambios de partida.
                if (pulledReadFailures >= 5) {
                    pulledTarget = 0;
                    appliedPullOffset = {0.0f, 0.0f, 0.0f};
                    havePulledTarget = false;
                    pulledReadFailures = 0;
                }
            }
        } else if (havePulledTarget) {
            restorePulledEnemy(pid, pulledTarget, appliedPullOffset, havePulledTarget);
            pulledReadFailures = 0;
        }

        if (flyActive) {
            // El dump actual identifica Player+0x10A8 como LastLockFireFinishTime (float),
            // no como MovementComponent. Fly usa por ello el Root real: asciende hasta
            // 6 metros y conserva X/Z frescos para que el joystick siga moviendo al jugador.
            if (!flyWasActive) flyPatchApplied = applyFlyPhysicsPatch(pid, true);
            long positionAddress = 0;
            float position[3] = {0, 0, 0};
            if (getRootPositionStorage(pid, gp.localPlayer, positionAddress, position)) {
                if (!flyHeightInitialized || !flyWasActive) {
                    flyBaseHeight = position[1];
                    flyTargetHeight = position[1];
                    flyHeightInitialized = true;
                }
                flyTargetHeight = std::min(flyBaseHeight + 6.0f, flyTargetHeight + 0.012f);
                float verticalDelta = flyTargetHeight - position[1];
                position[1] = flyTargetHeight;
                writeGameMemory(pid, positionAddress, position, 12);

                // Respaldo para builds donde la cámara no sigue el Root escrito.
                long cameraTransform = 0;
                if (fabsf(verticalDelta) < 1.0f &&
                    readPtr(pid, gp.localPlayer + OFF_MAIN_CAMERA_TRANSFORM, cameraTransform) &&
                    isPlausiblePtr(cameraTransform)) {
                    long cameraAddress = 0;
                    float cameraLocal[3] = {0, 0, 0};
                    if (getTransformPositionStorage(pid, cameraTransform, cameraAddress, cameraLocal)) {
                        cameraLocal[1] += verticalDelta;
                        writeGameMemory(pid, cameraAddress, cameraLocal, 12);
                    }
                }
            }
            flyWasActive = true;
        } else {
            if (flyPatchApplied) applyFlyPhysicsPatch(pid, false);
            flyPatchApplied = false;
            flyHeightInitialized = false;
            flyWasActive = false;
        }

        if (noReloadActive) {
            applyNoReloadState(pid, gp.localPlayer, true);
            noReloadWasActive = true;
        } else if (noReloadWasActive) {
            applyNoReloadState(pid, gp.localPlayer, false);
            noReloadWasActive = false;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(6));
    }
}

static void ensureFeatureThread() {
    if (!g_feature_thread_running.exchange(true)) {
        g_feature_thread = std::thread(featureThreadFunction);
        g_feature_thread.detach();
    }
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
        {
            std::lock_guard<std::mutex> lock(g_io_mutex);
            closeRootMemIOLocked();
        }
        g_game_pid = -1;
        g_base_pid = -1;
        g_game_base = 0;
        g_helper_tried_pid = 0;
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_setAimVisible(JNIEnv* env, jclass clazz, jboolean active) {
    if (!active) {
        g_aimbot_active = false;
        LOGI("[FREEZY] Aim Visible: OFF");
        return JNI_TRUE;
    }

    int pid = g_game_pid.load();
    if (pid <= 0) {
        pid = findGamePidNative();
        if (pid > 0) g_game_pid = pid;
    }
    if (pid <= 0 || getGameBase(pid) == 0) {
        LOGE("[FREEZY] Aim Visible: proceso o libil2cpp no disponible");
        return JNI_FALSE;
    }

    ensureHelperSpawned(pid);
    // Aim Visible y Aimbot escriben la misma rotación; el último modo activado
    // toma control, sin requerir que el otro esté encendido.
    g_camera_aim_active = false;
    g_aimbot_active = true;
    if (!g_aimbot_running.exchange(true)) {
        g_aimbot_thread = std::thread(aimbotThreadFunction);
        g_aimbot_thread.detach();
    }
    LOGI("[FREEZY] Aim Visible: ON (PID %d, FOV %.0f, distancia %.0f)",
         pid, SNIPER_FOV_PX, SNIPER_MAX_DIST);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setAimbotTarget(JNIEnv*, jclass, jint target) {
    g_aim_target = std::max(0, std::min(4, (int)target));
    LOGI("[FREEZY] Aimbot Target: %d", g_aim_target.load());
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setAimVisibleFov(JNIEnv*, jclass, jint pixels) {
    g_aim_visible_fov = std::max(50, std::min(500, (int)pixels));
    LOGI("[FREEZY] Aim Visible FOV: %d px", g_aim_visible_fov.load());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_setCameraAimbot(JNIEnv*, jclass, jboolean active) {
    if (active != JNI_TRUE) {
        g_camera_aim_active = false;
        return JNI_TRUE;
    }
    int pid = -1;
    if (!prepareFeatureAccess(pid)) return JNI_FALSE;
    g_aimbot_active = false;
    g_camera_aim_active = true;
    if (!g_aimbot_running.exchange(true)) {
        g_aimbot_thread = std::thread(aimbotThreadFunction);
        g_aimbot_thread.detach();
    }
    LOGI("[FREEZY] Camera Aimbot: ON (PID %d)", pid);
    return JNI_TRUE;
}

static jboolean setLoopFeature(std::atomic<bool>& feature, bool active, const char* name) {
    if (!active) {
        feature = false;
        LOGI("[FREEZY] %s: OFF", name);
        return JNI_TRUE;
    }

    int pid = -1;
    if (!prepareFeatureAccess(pid)) {
        LOGE("[FREEZY] %s: proceso o libil2cpp no disponible", name);
        return JNI_FALSE;
    }
    feature = true;
    ensureFeatureThread();
    LOGI("[FREEZY] %s: ON (PID %d)", name, pid);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_setSilentAim(JNIEnv*, jclass, jboolean active) {
    return setLoopFeature(g_silent_aim_active, active == JNI_TRUE, "Silent Aim");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_setEnemyPull(JNIEnv*, jclass, jboolean active) {
    return setLoopFeature(g_enemy_pull_active, active == JNI_TRUE, "Enemy Pull 360");
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setEnemyPullDirection(JNIEnv*, jclass, jint direction) {
    g_enemy_pull_direction = std::max(0, std::min(4, (int)direction));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_setFlyHack(JNIEnv*, jclass, jboolean active) {
    return setLoopFeature(g_fly_active, active == JNI_TRUE, "Fly Hack");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_setNoReload(JNIEnv*, jclass, jboolean active) {
    return setLoopFeature(g_no_reload_active, active == JNI_TRUE, "NoReload");
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_shutdownMemoryAccess(JNIEnv*, jclass) {
    g_aimbot_active = false;
    g_camera_aim_active = false;
    g_silent_aim_active = false;
    g_enemy_pull_active = false;
    g_enemy_pull_direction = 0;
    g_fly_active = false;
    g_no_reload_active = false;
    g_sniper_active = false;

    std::lock_guard<std::mutex> lock(g_io_mutex);
    closeRootMemIOLocked();
    g_game_pid = -1;
    g_base_pid = -1;
    g_game_base = 0;
    g_helper_tried_pid = 0;
    LOGI("[FREEZY] Acceso de memoria cerrado y PID invalidado");
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_startAimbot(JNIEnv* env, jclass clazz) {
    // Deshabilitado por seguridad anti-ban
    LOGI("[FREEZY] startAimbot() deshabilitado (solo lectura activo)");
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_stopAimbot(JNIEnv* env, jclass clazz) {
    LOGI("[FREEZY] stopAimbot() llamado");
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

// Metadatos que cambian mucho más lento que la posición. Mantenerlos fuera de la ruta
// de 50 Hz evita releer nombres/equipo/arma en cada actualización cercana.
struct EspEntityMeta {
    int pid = -1;
    int team = -1;
    int health = 200;
    short weapon = 0;
    float isBot = 0.0f;
    float name[6] = {0, 0, 0, 0, 0, 0};
    int64_t teamAt = 0;
    int64_t healthAt = 0;
    int64_t weaponAt = 0;
    int64_t nameAt = 0;
    int64_t lastSeenAt = 0;
};

static std::mutex g_esp_meta_mutex;
static std::unordered_map<long, EspEntityMeta> g_esp_meta;

static int64_t espNowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

static int getCachedTeamStatus(int pid, long entity, int64_t now) {
    {
        std::lock_guard<std::mutex> lock(g_esp_meta_mutex);
        auto it = g_esp_meta.find(entity);
        if (it != g_esp_meta.end() && it->second.pid == pid &&
            now - it->second.teamAt <= 250 && it->second.team > 0) {
            it->second.lastSeenAt = now;
            return it->second.team;
        }
    }
    const int team = getTeamStatus(pid, entity);
    {
        std::lock_guard<std::mutex> lock(g_esp_meta_mutex);
        EspEntityMeta& meta = g_esp_meta[entity];
        if (meta.pid != pid) meta = EspEntityMeta{};
        meta.pid = pid;
        meta.team = team;
        meta.teamAt = now;
        meta.lastSeenAt = now;
    }
    return team;
}

static int getCachedHealth(int pid, long entity, int64_t now) {
    {
        std::lock_guard<std::mutex> lock(g_esp_meta_mutex);
        auto it = g_esp_meta.find(entity);
        if (it != g_esp_meta.end() && it->second.pid == pid && now - it->second.healthAt <= 100) {
            it->second.lastSeenAt = now;
            return it->second.health;
        }
    }
    const int health = getPlayerHealth(pid, entity);
    std::lock_guard<std::mutex> lock(g_esp_meta_mutex);
    EspEntityMeta& meta = g_esp_meta[entity];
    if (meta.pid != pid) meta = EspEntityMeta{};
    meta.pid = pid;
    meta.health = health;
    meta.healthAt = now;
    meta.lastSeenAt = now;
    return health;
}

static short getCachedWeapon(int pid, long entity, int64_t now) {
    {
        std::lock_guard<std::mutex> lock(g_esp_meta_mutex);
        auto it = g_esp_meta.find(entity);
        if (it != g_esp_meta.end() && it->second.pid == pid && now - it->second.weaponAt <= 300) {
            it->second.lastSeenAt = now;
            return it->second.weapon;
        }
    }
    const short weapon = getPlayerWeaponId(pid, entity);
    std::lock_guard<std::mutex> lock(g_esp_meta_mutex);
    EspEntityMeta& meta = g_esp_meta[entity];
    if (meta.pid != pid) meta = EspEntityMeta{};
    meta.pid = pid;
    meta.weapon = weapon;
    meta.weaponAt = now;
    meta.lastSeenAt = now;
    return weapon;
}

static void getCachedName(int pid, long entity, int64_t now, float* name, float& isBot) {
    {
        std::lock_guard<std::mutex> lock(g_esp_meta_mutex);
        auto it = g_esp_meta.find(entity);
        if (it != g_esp_meta.end() && it->second.pid == pid && now - it->second.nameAt <= 2000) {
            memcpy(name, it->second.name, sizeof(it->second.name));
            isBot = it->second.isBot;
            it->second.lastSeenAt = now;
            return;
        }
    }
    readPlayerNamePacked(pid, entity, name, isBot);
    std::lock_guard<std::mutex> lock(g_esp_meta_mutex);
    EspEntityMeta& meta = g_esp_meta[entity];
    if (meta.pid != pid) meta = EspEntityMeta{};
    meta.pid = pid;
    memcpy(meta.name, name, sizeof(meta.name));
    meta.isBot = isBot;
    meta.nameAt = now;
    meta.lastSeenAt = now;
}

static void pruneEspMetadata(int pid, int64_t now) {
    std::lock_guard<std::mutex> lock(g_esp_meta_mutex);
    if (g_esp_meta.size() < 96) return;
    for (auto it = g_esp_meta.begin(); it != g_esp_meta.end();) {
        if (it->second.pid != pid || now - it->second.lastSeenAt > 5000) {
            it = g_esp_meta.erase(it);
        } else {
            ++it;
        }
    }
}

struct EspCandidate {
    long entity;
    float dist;
    bool knocked;
    int team;
    long bones[14];
    float head[3];
};

static std::mutex g_radar_cache_mutex;
static std::array<float, 64 * 4> g_radar_cache{};
static int g_radar_cache_pid = -1;
static int g_radar_cache_count = 0;
static std::chrono::steady_clock::time_point g_radar_cache_time{};

extern "C" JNIEXPORT jint JNICALL
Java_com_freezy_NativeBridge_getEspSnapshotDirect(JNIEnv* env, jclass clazz, jint pid, jfloatArray outData, jint flags) {
    if (pid <= 0 || !outData) return 0;

    GamePointers gp;
    if (!resolveGamePointers(pid, gp)) return 0;

    // La cámara pertenece al snapshot completo, no a una entidad. Antes se recorría
    // FollowCamera -> Camera -> CameraBase una vez por enemigo mostrado.
    float vm[16] = {0};
    if (!getViewMatrix(pid, gp.localPlayer, vm)) return 0;

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
    bool reqRadar = (flags & 512) != 0;
    // Box, Skeleton, vida y etiquetas necesitan la geometría corporal para colocar
    // correctamente sus elementos. Line, Count y Radar sólo requieren Head.
    bool reqBodyGeometry = (flags & (1 | 2 | 8 | 16 | 32 | 64 | 128)) != 0;
    const int64_t snapshotNow = espNowMs();

    std::vector<EspCandidate> preliminary;
    preliminary.reserve(ents.size());

    // 1. Estado y punteros. Las posiciones Head se resuelven juntas después.
    for (long e : ents) {
        if (e == gp.localPlayer) continue;

        // 1. ¿Muerto?
        uint8_t isDead = 0;
        if (!readU8(pid, e + OFF_PLAYER_IS_DEAD, isDead) || isDead) continue;

        // 2. Equipo
        int team = getCachedTeamStatus(pid, e, snapshotNow);
        if (team <= 0) continue;

        // 3. Knocked
        bool knocked = isKnocked(pid, e);

        // 4. Punteros de huesos (1 sola lectura en bloque de 0x60 bytes)
        long bones[14];
        readBonePtrBlock(pid, e, bones);
        if (bones[0] == 0 || !isPlausiblePtr(bones[0])) continue;

        EspCandidate cand;
        cand.entity = e;
        cand.dist = 0.0f;
        cand.knocked = knocked;
        cand.team = team;
        memcpy(cand.bones, bones, sizeof(bones));
        cand.head[0] = cand.head[1] = cand.head[2] = 0.0f;
        preliminary.push_back(cand);
    }

    std::vector<long> headBones;
    headBones.reserve(preliminary.size());
    for (const auto& candidate : preliminary) headBones.push_back(candidate.bones[0]);
    std::vector<std::array<float, 3>> headPositions;
    std::vector<uint8_t> headResolved;
    resolveBonePositionsBatch(pid, headBones, headPositions, headResolved);

    const float yaw = -atan2f(vm[8], vm[10]);
    const float cosYaw = cosf(yaw);
    const float sinYaw = sinf(yaw);
    std::array<float, 64 * 4> radarData{};
    int radarCount = 0;

    std::vector<EspCandidate> candidates;
    candidates.reserve(preliminary.size());
    for (size_t i = 0; i < preliminary.size(); ++i) {
        if (!headResolved[i]) continue;
        EspCandidate& cand = preliminary[i];
        cand.head[0] = headPositions[i][0];
        cand.head[1] = headPositions[i][1];
        cand.head[2] = headPositions[i][2];
        const float dx = cand.head[0] - myPos[0];
        const float dy = cand.head[1] - myPos[1];
        const float dz = cand.head[2] - myPos[2];
        cand.dist = sqrtf(dx * dx + dy * dy + dz * dz);
        if (!std::isfinite(cand.dist)) continue;

        // El radar comparte exactamente la misma lectura Head/Team/Knocked del ESP.
        if (reqRadar && radarCount < 64 && cand.dist <= 250.0f) {
            const int base = radarCount * 4;
            radarData[base] = dx * cosYaw - dz * sinYaw;
            radarData[base + 1] = dx * sinYaw + dz * cosYaw;
            radarData[base + 2] = cand.knocked ? 1.0f : 0.0f;
            radarData[base + 3] = (float)cand.team;
            ++radarCount;
        }

        if (cand.dist > ESP_MAX_DIST) continue;
        if (!reqTeam && cand.team == 1) continue;
        if (reqIgnoreKnocked && cand.knocked) continue;
        ++totalEnemiesInRange;
        candidates.push_back(cand);
    }

    if (reqRadar) {
        std::lock_guard<std::mutex> lock(g_radar_cache_mutex);
        g_radar_cache = radarData;
        g_radar_cache_pid = pid;
        g_radar_cache_count = radarCount;
        g_radar_cache_time = std::chrono::steady_clock::now();
    }

    // 2. Los más cercanos se proyectan primero. Se filtra Head antes de leer el resto
    // del cuerpo, por lo que un objetivo fuera de pantalla no consume nueve huesos extra.
    std::sort(candidates.begin(), candidates.end(), [](const EspCandidate& a, const EspCandidate& b) {
        return a.dist < b.dist;
    });
    std::vector<const EspCandidate*> renderCandidates;
    std::vector<std::array<float, 2>> headScreen;
    renderCandidates.reserve(MAX_ENTS);
    headScreen.reserve(MAX_ENTS);
    for (const auto& candidate : candidates) {
        float sx = -1.0f, sy = -1.0f;
        if (!worldToScreen(vm, candidate.head, screenW, screenH, sx, sy) || sx <= 0 || sy <= 0) continue;
        renderCandidates.push_back(&candidate);
        headScreen.push_back({sx, sy});
        if ((int)renderCandidates.size() >= MAX_ENTS) break;
    }

    static const int BODY_BONES[] = {1, 2, 3, 4, 5, 8, 9, 12, 13};
    std::vector<long> bodyBonePtrs;
    std::vector<std::array<float, 3>> bodyPositions;
    std::vector<uint8_t> bodyResolved;
    if (reqBodyGeometry) {
        bodyBonePtrs.reserve(renderCandidates.size() * 9);
        for (const EspCandidate* candidate : renderCandidates) {
            for (int boneIndex : BODY_BONES) bodyBonePtrs.push_back(candidate->bones[boneIndex]);
        }
        resolveBonePositionsBatch(pid, bodyBonePtrs, bodyPositions, bodyResolved);
    }

    // 3. Construir el bloque compacto que consume Kotlin.
    for (size_t renderIndex = 0; renderIndex < renderCandidates.size(); ++renderIndex) {
        const EspCandidate& cand = *renderCandidates[renderIndex];
        const long e = cand.entity;
        float skel[28];
        for (float& value : skel) value = -1.0f;
        skel[0] = headScreen[renderIndex][0];
        skel[1] = headScreen[renderIndex][1];
        if (reqBodyGeometry) {
            for (size_t bodyIndex = 0; bodyIndex < 9; ++bodyIndex) {
                const size_t flatIndex = renderIndex * 9 + bodyIndex;
                if (!bodyResolved[flatIndex]) continue;
                float sx = -1.0f, sy = -1.0f;
                if (worldToScreen(vm, bodyPositions[flatIndex].data(), screenW, screenH, sx, sy)) {
                    const int boneIndex = BODY_BONES[bodyIndex];
                    skel[boneIndex * 2] = sx;
                    skel[boneIndex * 2 + 1] = sy;
                }
            }
        }

        int hp = reqHealth ? getCachedHealth(pid, e, snapshotNow) : 200;
        short wepId = reqWeapon ? getCachedWeapon(pid, e, snapshotNow) : 0;
        float isBot = 0.0f;
        float nameFloats[6] = {0, 0, 0, 0, 0, 0};
        if (reqName) {
            getCachedName(pid, e, snapshotNow, nameFloats, isBot);
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
    pruneEspMetadata(pid, snapshotNow);
    return result;
}

// Snapshot compacto para el minimapa. Cada entidad ocupa cuatro floats:
// X rotada, Z rotada, knocked y team. La rotación se realiza aquí con la cámara
// del juego para que Kotlin solo tenga que escalar y dibujar los puntos.
extern "C" JNIEXPORT jint JNICALL
Java_com_freezy_NativeBridge_getRadarSnapshot(JNIEnv* env, jclass, jint pid, jfloatArray outData) {
    if (pid <= 0 || !outData) return 0;
    jsize capacity = env->GetArrayLength(outData);
    const int maxEntities = std::min<int>(64, capacity / 4);
    if (maxEntities <= 0) return 0;

    // La ruta normal del overlay llama primero al snapshot ESP con el flag Radar.
    // Entregar esa copia evita resolver nuevamente toda la partida en esta JNI.
    {
        std::array<float, 64 * 4> cached{};
        int cachedCount = 0;
        bool fresh = false;
        {
            std::lock_guard<std::mutex> lock(g_radar_cache_mutex);
            const auto age = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - g_radar_cache_time).count();
            fresh = g_radar_cache_pid == pid && age >= 0 && age <= 150;
            if (fresh) {
                cachedCount = std::min(maxEntities, g_radar_cache_count);
                cached = g_radar_cache;
            }
        }
        if (fresh) {
            if (cachedCount > 0) env->SetFloatArrayRegion(outData, 0, cachedCount * 4, cached.data());
            return cachedCount;
        }
    }

    GamePointers gp;
    if (!resolveGamePointers(pid, gp) || !isPlausiblePtr(gp.localPlayer)) return 0;

    float cameraPosition[3] = {0, 0, 0};
    float viewMatrix[16] = {0};
    if (!getLocalCameraPosition(pid, gp.localPlayer, cameraPosition) ||
        !getViewMatrix(pid, gp.localPlayer, viewMatrix)) return 0;

    float yaw = -atan2f(viewMatrix[8], viewMatrix[10]);
    float cosYaw = cosf(yaw);
    float sinYaw = sinf(yaw);
    std::vector<float> radar;
    radar.reserve((size_t)maxEntities * 4);

    for (long entity : getEntities(pid, gp.currentGame)) {
        if ((int)(radar.size() / 4) >= maxEntities || entity == gp.localPlayer) break;
        uint8_t dead = 0;
        if (!readU8(pid, entity + OFF_PLAYER_IS_DEAD, dead) || dead) continue;
        int team = getTeamStatus(pid, entity);
        if (team <= 0) continue;

        float head[3] = {0, 0, 0};
        if (!getBonePosition(pid, entity, OFF_BONE_HEAD, head)) continue;
        float relativeX = head[0] - cameraPosition[0];
        float relativeZ = head[2] - cameraPosition[2];
        float distance = sqrtf(relativeX * relativeX + relativeZ * relativeZ);
        if (!std::isfinite(distance) || distance > 250.0f) continue;

        radar.push_back(relativeX * cosYaw - relativeZ * sinYaw);
        radar.push_back(relativeX * sinYaw + relativeZ * cosYaw);
        radar.push_back(isKnocked(pid, entity) ? 1.0f : 0.0f);
        radar.push_back((float)team);
    }

    int count = (int)(radar.size() / 4);
    if (count > 0) env->SetFloatArrayRegion(outData, 0, count * 4, radar.data());
    return count;
}
