#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdint>
#include <cerrno>
#include <cstring>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/select.h>
#include <sys/epoll.h>
#include <atomic>
#include <vector>
#include <cstdlib>
#include <fstream>
#include <string>
#include <sstream>
#include <iomanip>
#include <thread>
#include <chrono>
#include <algorithm>
#include <sys/resource.h>

#define TAG  "FreezyNative"
#ifdef NDEBUG
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#define LOGW(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#endif

/* ── CSPRNG: Generador de números aleatorios seguro (CWE-330 fix) ────────── */
/* Lee entropía del kernel Linux via /dev/urandom en lugar de usar rand()     */
static uint16_t secure_random_u16() {
    uint16_t val = 0;
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd >= 0) {
        read(fd, &val, sizeof(val));
        close(fd);
    }
    return val;
}

/* ── IP / UDP / TCP header structs ───────────────────────────────────────── */
struct IpHdr {
    uint8_t  ihl_ver;
    uint8_t  tos;
    uint16_t tot_len;
    uint16_t id;
    uint16_t frag_off;
    uint8_t  ttl;
    uint8_t  proto;
    uint16_t check;
    uint32_t saddr;
    uint32_t daddr;
} __attribute__((packed));

struct Ip6Hdr {
    uint32_t vt_tc_fl; // Version (4 bits), Traffic Class (8 bits), Flow Label (20 bits)
    uint16_t payload_len; // Length of payload
    uint8_t  next_hdr; // Next header (protocol)
    uint8_t  hop_limit;
    uint8_t  saddr[16];
    uint8_t  daddr[16];
} __attribute__((packed));

struct UdpHdr {
    uint16_t sport;
    uint16_t dport;
    uint16_t len;
    uint16_t check;
} __attribute__((packed));

struct TcpHdr {
    uint16_t sport;
    uint16_t dport;
    uint32_t seq;
    uint32_t ack_seq;
    uint8_t  off;
    uint8_t  flags;
    uint16_t win;
    uint16_t check;
    uint16_t urgp;
} __attribute__((packed));

/* ── Telemetry & Secure Debug System ─────────────────────────────────────── */
#define DEBUG 1
#ifdef DEBUG
#define DBG_LOG(fmt, ...) LOGI("[DEBUG-NET] " fmt, ##__VA_ARGS__)
#else
#define DBG_LOG(fmt, ...) do {} while (0)
#endif

/* ── Per-flow state ──────────────────────────────────────────────────────── */
struct Flow {
    int      sock;         // protected UDP socket
    bool     is_ipv6;
    uint32_t game_ip;      // game's source IP (IPv4)
    uint16_t game_port;    // game's source port (big-endian)
    uint32_t srv_ip;       // server IP (IPv4)
    uint16_t srv_port;     // server port (big-endian)
    uint8_t  game_ip6[16]; // game's source IP (IPv6)
    uint8_t  srv_ip6[16];  // server IP (IPv6)
    uint64_t last_activity; // Timestamp of last packet activity (POSIX monotonic)
};

struct TcpFlow {
    int      sock;       // protected TCP socket
    uint32_t client_ip;
    uint16_t client_port;
    uint32_t server_ip;
    uint16_t server_port;
    uint32_t seq_to_client;
    uint32_t seq_from_client;
    bool     connected;
    uint64_t last_activity;
};

static const int MAX_FLOWS = 64;
static Flow          g_flows[MAX_FLOWS];
static int           g_flow_count = 0;

static const int MAX_TCP_FLOWS = 256;
static TcpFlow      g_tcp_flows[MAX_TCP_FLOWS];
static int          g_tcp_flow_count = 0;

static pthread_mutex_t g_flows_mtx = PTHREAD_MUTEX_INITIALIZER;

struct PacketBuffer {
    uint8_t data[65536]; // Ajustado para evitar truncado de paquetes con MTU de 65535
    int len;
};

static const int BUFFER_POOL_SIZE = 64; // Optimización de pool para limitar consumo a 4MB
static PacketBuffer g_buffer_pool[BUFFER_POOL_SIZE];
static std::atomic<int> g_buffer_pool_index{0};

static PacketBuffer* acquire_buffer() {
    int idx = g_buffer_pool_index.fetch_add(1) % BUFFER_POOL_SIZE;
    return &g_buffer_pool[idx];
}

/* ── Global engine state ─────────────────────────────────────────────────── */
static volatile int   g_tun_fd   = -1;
static int            g_pipe[2]  = {-1, -1};
static pthread_t      g_thread;
static std::atomic<bool> g_running{false};

static int g_epoll_fd = -1;

static bool epoll_add(int fd) {
    if (g_epoll_fd < 0) return false;
    struct epoll_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.events = EPOLLIN;
    ev.data.fd = fd;
    if (epoll_ctl(g_epoll_fd, EPOLL_CTL_ADD, fd, &ev) < 0) {
        LOGE("epoll_ctl ADD failed for fd %d: %s", fd, strerror(errno));
        return false;
    }
    return true;
}

static bool epoll_add_write(int fd) {
    if (g_epoll_fd < 0) return false;
    struct epoll_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.events = EPOLLIN | EPOLLOUT;
    ev.data.fd = fd;
    if (epoll_ctl(g_epoll_fd, EPOLL_CTL_ADD, fd, &ev) < 0) {
        LOGE("epoll_ctl ADD (write) failed for fd %d: %s", fd, strerror(errno));
        return false;
    }
    return true;
}

static bool epoll_mod_read(int fd) {
    if (g_epoll_fd < 0) return false;
    struct epoll_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.events = EPOLLIN;
    ev.data.fd = fd;
    if (epoll_ctl(g_epoll_fd, EPOLL_CTL_MOD, fd, &ev) < 0) {
        LOGE("epoll_ctl MOD failed for fd %d: %s", fd, strerror(errno));
        return false;
    }
    return true;
}

static bool epoll_del(int fd) {
    if (g_epoll_fd < 0) return false;
    if (epoll_ctl(g_epoll_fd, EPOLL_CTL_DEL, fd, nullptr) < 0) {
        return false;
    }
    return true;
}

// Set from Kotlin: true = drop incoming UDP (lag switch ON)
extern "C" std::atomic<bool> gLagActive{false};
// Ghost es una ruta independiente: filtra únicamente UDP saliente y no usa
// la cola, watchdog ni estado de Fake Lag.
static std::atomic<bool> gGhostActive{false};
static std::atomic<uint64_t> gGhostDroppedPackets{0};
static std::atomic<uint64_t> g_last_keepalive_time{0};
static std::atomic<uint64_t> g_max_desync_ms{800}; // Configurable dinámicamente por JNI (Paso 7 Watchdog)

/* ── JNI callback to protect a socket ───────────────────────────────────── */
static JavaVM*    g_jvm     = nullptr;
static jobject    g_svc_ref = nullptr;   // GlobalRef to AntigravityFirewall
static jmethodID  g_protect = nullptr;

static bool protect_fd(int fd) {
    if (g_jvm == nullptr || g_svc_ref == nullptr || g_protect == nullptr) {
        LOGE("protect_fd: JNI not initialized properly");
        return false;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("protect_fd: Failed to attach thread");
            return false;
        }
        attached = true;
    } else if (res != JNI_OK) {
        LOGE("protect_fd: GetEnv failed with error %d", res);
        return false;
    }

    bool ok = env->CallBooleanMethod(g_svc_ref, g_protect, (jint)fd);
    
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
    
    if (!ok) {
        LOGE("protect_fd: VpnService.protect(%d) returned false!", fd);
    }
    return ok;
}

/* ── Checksum helpers ────────────────────────────────────────────────────── */
static uint16_t ip_csum(const void* data, int len) {
    const uint16_t* p = (const uint16_t*)data;
    uint32_t sum = 0;
    while (len > 1) { sum += *p++; len -= 2; }
    if (len) sum += *(const uint8_t*)p;
    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t)~sum;
}

static uint16_t transport_csum(uint32_t saddr, uint32_t daddr, uint8_t proto,
                          const uint8_t* data, uint16_t len) {
    uint32_t sum = 0;
    // Pseudo-header
    sum += (saddr >> 16) & 0xFFFF;
    sum += saddr & 0xFFFF;
    sum += (daddr >> 16) & 0xFFFF;
    sum += daddr & 0xFFFF;
    sum += htons(proto);
    sum += htons(len);

    const uint16_t* p = (const uint16_t*)data;
    int n = len;
    while (n > 1) { sum += *p++; n -= 2; }
    if (n) sum += (uint32_t)(*(const uint8_t*)p) << (htons(1) == 1 ? 0 : 8);

    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t)~sum;
}

static uint16_t transport_csum_v6(const uint8_t* saddr, const uint8_t* daddr, uint8_t proto,
                                  const uint8_t* data, uint16_t len) {
    uint32_t sum = 0;
    
    // Pseudo-header IPv6
    for (int i = 0; i < 8; ++i) {
        sum += (saddr[i*2] << 8) | saddr[i*2+1];
        sum += (daddr[i*2] << 8) | daddr[i*2+1];
    }
    
    sum += htons(proto);
    sum += htons(len);

    const uint16_t* p = (const uint16_t*)data;
    int n = len;
    while (n > 1) { sum += *p++; n -= 2; }
    if (n) sum += (uint32_t)(*(const uint8_t*)p) << (htons(1) == 1 ? 0 : 8);

    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t)~sum;
}

/* ── Monotonic time helper ────────────────────────────────────────────────── */
static uint64_t get_now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ── Asymmetric Delay Queue Structure ──────────────────────────────────────── */
static const int MAX_DELAY_QUEUE_SIZE = 256; // Optimización de consumo de memoria RAM
struct DelayedPacket {
    bool     is_ipv6;
    uint32_t src_ip;
    uint16_t src_port;
    uint32_t dst_ip;
    uint16_t dst_port;
    uint8_t  src_ip6[16];
    uint8_t  dst_ip6[16];
    
    uint8_t  payload[65536]; // Ajustado a 64KB para evitar truncado de paquetes grandes de juego
    int      payload_len;
    uint64_t timestamp; // Time when it was buffered
};

static DelayedPacket g_delay_queue[MAX_DELAY_QUEUE_SIZE];
static int g_delay_queue_head = 0;
static int g_delay_queue_tail = 0;
static int g_delay_queue_count = 0;
static pthread_mutex_t g_delay_queue_mtx = PTHREAD_MUTEX_INITIALIZER;

static void delay_queue_push(bool is_ipv6, 
                             const uint8_t* src_ip, uint16_t src_port,
                             const uint8_t* dst_ip, uint16_t dst_port,
                             const uint8_t* payload, int plen) {
    if (plen > 65536) return; // Supera tamaño del buffer de cola
    
    pthread_mutex_lock(&g_delay_queue_mtx);
    if (g_delay_queue_count >= MAX_DELAY_QUEUE_SIZE) {
        // Cola llena: descartamos el más antiguo (head)
        g_delay_queue_head = (g_delay_queue_head + 1) % MAX_DELAY_QUEUE_SIZE;
        g_delay_queue_count--;
    }
    
    int idx = g_delay_queue_tail;
    g_delay_queue[idx].is_ipv6 = is_ipv6;
    g_delay_queue[idx].src_port = src_port;
    g_delay_queue[idx].dst_port = dst_port;
    if (is_ipv6) {
        memcpy(g_delay_queue[idx].src_ip6, src_ip, 16);
        memcpy(g_delay_queue[idx].dst_ip6, dst_ip, 16);
    } else {
        g_delay_queue[idx].src_ip = *(const uint32_t*)src_ip;
        g_delay_queue[idx].dst_ip = *(const uint32_t*)dst_ip;
    }
    memcpy(g_delay_queue[idx].payload, payload, plen);
    g_delay_queue[idx].payload_len = plen;
    g_delay_queue[idx].timestamp = get_now_ms();
    
    g_delay_queue_tail = (g_delay_queue_tail + 1) % MAX_DELAY_QUEUE_SIZE;
    g_delay_queue_count++;
    pthread_mutex_unlock(&g_delay_queue_mtx);
}

static void write_to_tun(int tun_fd,
                          uint32_t src_ip, uint16_t src_port,
                          uint32_t dst_ip, uint16_t dst_port,
                          const uint8_t* payload, int plen);

static void write_ipv6_to_tun(int tun_fd,
                              const uint8_t* src_ip, uint16_t src_port,
                              const uint8_t* dst_ip, uint16_t dst_port,
                              const uint8_t* payload, int plen);

// Vaciar la cola inyectando todos los paquetes acumulados al TUN de golpe
static void delay_queue_flush(int tun_fd) {
    pthread_mutex_lock(&g_delay_queue_mtx);
    int flushed_count = g_delay_queue_count;
    uint64_t now = get_now_ms();
    while (g_delay_queue_count > 0) {
        int idx = g_delay_queue_head;
        DelayedPacket& pkt = g_delay_queue[idx];
        
        DBG_LOG("LAG-FLUSH: Releasing packet size=%d, held for %llu ms", pkt.payload_len, (unsigned long long)(now - pkt.timestamp));
        
        if (pkt.is_ipv6) {
            write_ipv6_to_tun(tun_fd,
                pkt.src_ip6, pkt.src_port,
                pkt.dst_ip6, pkt.dst_port,
                pkt.payload, pkt.payload_len);
        } else {
            write_to_tun(tun_fd,
                pkt.src_ip, pkt.src_port,
                pkt.dst_ip, pkt.dst_port,
                pkt.payload, pkt.payload_len);
        }
        
        g_delay_queue_head = (g_delay_queue_head + 1) % MAX_DELAY_QUEUE_SIZE;
        g_delay_queue_count--;
    }
    if (flushed_count > 0) {
        LOGI("Flushed delay queue containing %d packets.", flushed_count);
    }
    pthread_mutex_unlock(&g_delay_queue_mtx);
}

/* ── Jitter/Heartbeat and Anti-Detection Evasion ───────────────────────────── */
static std::atomic<uint64_t> g_last_heartbeat_time{0};

static bool handle_lag_evasion(int plen) {
    uint64_t now = get_now_ms();
    uint64_t last = g_last_heartbeat_time.load();
    // Si el paquete es muy pequeño (latidos keep-alive del motor de juego)
    // o han pasado más de 300ms, lo dejamos pasar para mantener la conexión activa.
    if (plen <= 60 || (now - last > 300)) {
        g_last_heartbeat_time.store(now);
        return true; // Permitir paso (bypass)
    }
    return false; // Retener en cola
}

/* ── Jitter Outbound Queue (Paso 12) ───────────────────────────────────────── */
struct OutboundPacket {
    int      sock;
    uint8_t  payload[65536];
    int      payload_len;
    sockaddr_storage dst_addr;
    socklen_t dst_addr_len;
    uint64_t timestamp;
    uint32_t delay_ms;
};

static const int MAX_OUTBOUND_QUEUE_SIZE = 256;
static OutboundPacket g_outbound_queue[MAX_OUTBOUND_QUEUE_SIZE];
static int g_outbound_queue_head = 0;
static int g_outbound_queue_tail = 0;
static int g_outbound_queue_count = 0;
static pthread_mutex_t g_outbound_queue_mtx = PTHREAD_MUTEX_INITIALIZER;

static std::atomic<uint32_t> g_outbound_jitter_ms{0};
static std::atomic<int> g_drop_probability{0};
static std::atomic<bool> g_selective_udp_delay_active{false};
static std::atomic<uint32_t> g_selective_udp_delay_ms{1};

/* ── Teleport Drop: captura/replay saliente, independiente de Fake Lag ───── */
// Teleport no reproduce una ruta completa: conserva únicamente las últimas
// muestras de cada firma de movimiento. Una cola grande enviaba segundos de
// estado obsoleto y provocaba rollback/ping alto al desactivarla.
static constexpr int TELEPORT_QUEUE_CAPACITY = 24;
static constexpr int TELEPORT_PAYLOAD_CAPACITY = 2048;
struct TeleportPacket {
    int sock;
    uint8_t payload[TELEPORT_PAYLOAD_CAPACITY];
    int payload_len;
    sockaddr_storage dst_addr;
    socklen_t dst_addr_len;
};

static TeleportPacket g_teleport_capture[TELEPORT_QUEUE_CAPACITY];
static TeleportPacket g_teleport_replay[TELEPORT_QUEUE_CAPACITY];
static int g_teleport_capture_head = 0;
static int g_teleport_capture_count = 0;
static int g_teleport_replay_head = 0;
static int g_teleport_replay_count = 0;
static uint64_t g_teleport_next_release_ms = 0;
static uint32_t g_teleport_release_interval_ms = 4;
static pthread_mutex_t g_teleport_mtx = PTHREAD_MUTEX_INITIALIZER;
// 0 apagado, 1 capturando, 2 reproduciendo.
static std::atomic<int> g_teleport_drop_state{0};

static inline bool is_teleport_position_port(uint16_t port) {
    return (port >= 10000 && port <= 10030) ||
           (port >= 20000 && port <= 20030);
}

/* ── Clasificador adaptativo de movimiento saliente ─────────────────────── */
// El payload del juego está cifrado; no se puede leer una coordenada de forma
// fiable. La señal útil es la cadencia: posición se repite varias veces por
// segundo con puerto y tamaño estables, mientras disparos/daño son eventos
// esporádicos. Solo se retiene una firma después de observar esa repetición.
struct MotionProfile {
    uint16_t port;
    uint16_t size_bucket;
    uint64_t last_seen_ms;
    uint8_t cadence_score;
};

static constexpr int MOTION_PROFILE_CAPACITY = 48;
static MotionProfile g_motion_profiles[MOTION_PROFILE_CAPACITY]{};

static bool observe_likely_position(uint16_t destination_port, int payload_length) {
    if (!is_teleport_position_port(destination_port) ||
        payload_length < 50 || payload_length > 200) return false;

    const uint16_t bucket = (uint16_t)((payload_length + 3) & ~3);
    const uint64_t now = get_now_ms();
    int free_slot = -1;
    int oldest_slot = 0;
    uint64_t oldest_seen = UINT64_MAX;

    for (int i = 0; i < MOTION_PROFILE_CAPACITY; ++i) {
        MotionProfile& profile = g_motion_profiles[i];
        if (profile.last_seen_ms == 0 && free_slot < 0) free_slot = i;
        if (profile.last_seen_ms < oldest_seen) {
            oldest_seen = profile.last_seen_ms;
            oldest_slot = i;
        }
        if (profile.port != destination_port || profile.size_bucket != bucket) continue;

        const uint64_t gap = now - profile.last_seen_ms;
        const uint8_t previous_score = profile.cadence_score;
        if (gap >= 8 && gap <= 180) {
            profile.cadence_score = (uint8_t)std::min(20, profile.cadence_score + 2);
        } else if (gap <= 320) {
            profile.cadence_score = (uint8_t)std::min(20, profile.cadence_score + 1);
        } else {
            profile.cadence_score = 1;
        }
        profile.last_seen_ms = now;
        if (previous_score < 6 && profile.cadence_score >= 6) {
            LOGI("Movement signature learned: dport=%u payload~%u",
                 destination_port, bucket);
        }
        return profile.cadence_score >= 6;
    }

    MotionProfile& profile = g_motion_profiles[free_slot >= 0 ? free_slot : oldest_slot];
    profile = {destination_port, bucket, now, 1};
    return false;
}

static void clear_teleport_queues_locked() {
    g_teleport_capture_head = 0;
    g_teleport_capture_count = 0;
    g_teleport_replay_head = 0;
    g_teleport_replay_count = 0;
    g_teleport_next_release_ms = 0;
}

static bool capture_teleport_packet(int sock, const uint8_t* payload, int plen,
                                    const sockaddr* dst, socklen_t dst_len) {
    if (sock < 0 || payload == nullptr || plen < 25 ||
        plen > TELEPORT_PAYLOAD_CAPACITY || dst == nullptr ||
        dst_len > sizeof(sockaddr_storage)) return false;

    pthread_mutex_lock(&g_teleport_mtx);
    if (g_teleport_drop_state.load() != 1) {
        pthread_mutex_unlock(&g_teleport_mtx);
        return false;
    }
    // Sustituir la muestra anterior de la misma firma. Al desactivar solo se
    // envía el estado más reciente, no todo el recorrido acumulado.
    const int size_bucket = (plen + 3) & ~3;
    for (int i = 0; i < g_teleport_capture_count; ++i) {
        int index = (g_teleport_capture_head + i) % TELEPORT_QUEUE_CAPACITY;
        TeleportPacket& existing = g_teleport_capture[index];
        if (existing.sock == sock && ((existing.payload_len + 3) & ~3) == size_bucket) {
            existing.payload_len = plen;
            memcpy(existing.payload, payload, plen);
            memcpy(&existing.dst_addr, dst, dst_len);
            existing.dst_addr_len = dst_len;
            pthread_mutex_unlock(&g_teleport_mtx);
            return true;
        }
    }
    if (g_teleport_capture_count >= TELEPORT_QUEUE_CAPACITY) {
        // Mantener exclusivamente las firmas más recientes.
        g_teleport_capture_head =
            (g_teleport_capture_head + 1) % TELEPORT_QUEUE_CAPACITY;
        --g_teleport_capture_count;
    }
    int tail = (g_teleport_capture_head + g_teleport_capture_count) %
               TELEPORT_QUEUE_CAPACITY;
    TeleportPacket& packet = g_teleport_capture[tail];
    packet.sock = sock;
    packet.payload_len = plen;
    memcpy(packet.payload, payload, plen);
    memcpy(&packet.dst_addr, dst, dst_len);
    packet.dst_addr_len = dst_len;
    ++g_teleport_capture_count;
    pthread_mutex_unlock(&g_teleport_mtx);
    return true;
}

static void begin_teleport_replay() {
    pthread_mutex_lock(&g_teleport_mtx);
    g_teleport_replay_head = 0;
    g_teleport_replay_count = g_teleport_capture_count;
    for (int i = 0; i < g_teleport_capture_count; ++i) {
        int source = (g_teleport_capture_head + i) % TELEPORT_QUEUE_CAPACITY;
        g_teleport_replay[i] = g_teleport_capture[source];
    }
    g_teleport_capture_head = 0;
    g_teleport_capture_count = 0;

    if (g_teleport_replay_count == 0) {
        g_teleport_drop_state.store(0);
    } else {
        g_teleport_release_interval_ms = 1;
        g_teleport_next_release_ms = get_now_ms();
        g_teleport_drop_state.store(2);
    }
    pthread_mutex_unlock(&g_teleport_mtx);
}

static void process_teleport_replay() {
    if (g_teleport_drop_state.load() != 2) return;
    pthread_mutex_lock(&g_teleport_mtx);
    uint64_t now = get_now_ms();
    int released = 0;
    while (g_teleport_replay_count > 0 && released < TELEPORT_QUEUE_CAPACITY &&
           now >= g_teleport_next_release_ms) {
        TeleportPacket& packet = g_teleport_replay[g_teleport_replay_head];
        sendto(packet.sock, packet.payload, packet.payload_len, 0,
               (sockaddr*)&packet.dst_addr, packet.dst_addr_len);
        g_teleport_replay_head =
            (g_teleport_replay_head + 1) % TELEPORT_QUEUE_CAPACITY;
        --g_teleport_replay_count;
        ++released;
        g_teleport_next_release_ms += g_teleport_release_interval_ms;
    }
    if (g_teleport_replay_count == 0) {
        clear_teleport_queues_locked();
        g_teleport_drop_state.store(0);
    }
    pthread_mutex_unlock(&g_teleport_mtx);
}

static void cancel_teleport_drop() {
    pthread_mutex_lock(&g_teleport_mtx);
    clear_teleport_queues_locked();
    g_teleport_drop_state.store(0);
    pthread_mutex_unlock(&g_teleport_mtx);
}

static void outbound_queue_push(int sock, const uint8_t* payload, int plen,
                                const sockaddr* dst, socklen_t dst_len,
                                uint32_t delay_ms) {
    if (plen > 65536) return;
    pthread_mutex_lock(&g_outbound_queue_mtx);
    if (g_outbound_queue_count >= MAX_OUTBOUND_QUEUE_SIZE) {
        OutboundPacket& oldest = g_outbound_queue[g_outbound_queue_head];
        sendto(oldest.sock, oldest.payload, oldest.payload_len, 0, (sockaddr*)&oldest.dst_addr, oldest.dst_addr_len);
        g_outbound_queue_head = (g_outbound_queue_head + 1) % MAX_OUTBOUND_QUEUE_SIZE;
        g_outbound_queue_count--;
    }
    
    int idx = g_outbound_queue_tail;
    g_outbound_queue[idx].sock = sock;
    memcpy(g_outbound_queue[idx].payload, payload, plen);
    g_outbound_queue[idx].payload_len = plen;
    memcpy(&g_outbound_queue[idx].dst_addr, dst, dst_len);
    g_outbound_queue[idx].dst_addr_len = dst_len;
    g_outbound_queue[idx].timestamp = get_now_ms();
    g_outbound_queue[idx].delay_ms = delay_ms;
    
    g_outbound_queue_tail = (g_outbound_queue_tail + 1) % MAX_OUTBOUND_QUEUE_SIZE;
    g_outbound_queue_count++;
    pthread_mutex_unlock(&g_outbound_queue_mtx);
}

static void process_outbound_queue() {
    pthread_mutex_lock(&g_outbound_queue_mtx);
    uint64_t now = get_now_ms();
    while (g_outbound_queue_count > 0) {
        int idx = g_outbound_queue_head;
        OutboundPacket& pkt = g_outbound_queue[idx];
        if (now - pkt.timestamp >= pkt.delay_ms) {
            sendto(pkt.sock, pkt.payload, pkt.payload_len, 0, (sockaddr*)&pkt.dst_addr, pkt.dst_addr_len);
            g_outbound_queue_head = (g_outbound_queue_head + 1) % MAX_OUTBOUND_QUEUE_SIZE;
            g_outbound_queue_count--;
        } else {
            break;
        }
    }
    pthread_mutex_unlock(&g_outbound_queue_mtx);
}

static void clear_outbound_queue() {
    pthread_mutex_lock(&g_outbound_queue_mtx);
    while (g_outbound_queue_count > 0) {
        int idx = g_outbound_queue_head;
        OutboundPacket& pkt = g_outbound_queue[idx];
        sendto(pkt.sock, pkt.payload, pkt.payload_len, 0, (sockaddr*)&pkt.dst_addr, pkt.dst_addr_len);
        g_outbound_queue_head = (g_outbound_queue_head + 1) % MAX_OUTBOUND_QUEUE_SIZE;
        g_outbound_queue_count--;
    }
    pthread_mutex_unlock(&g_outbound_queue_mtx);
}

/* ── QoS Local Filter (Paso 13) ────────────────────────────────────────────── */
static bool qos_should_throttle_tcp() {
    static uint64_t last_tcp_time = 0;
    static int tcp_packet_count = 0;
    uint64_t now = get_now_ms();
    if (now - last_tcp_time > 100) {
        last_tcp_time = now;
        tcp_packet_count = 0;
    }
    tcp_packet_count++;
    return (tcp_packet_count > 10);
}

/* ── Probabilistic Packet Drop (Paso 14) ───────────────────────────────────── */
static bool should_probabilistic_drop() {
    int prob = g_drop_probability.load();
    if (prob <= 0) return false;
    uint16_t r = secure_random_u16() % 100;
    return (r < prob);
}

/* Helper para identificar puertos de servidores del juego (Free Fire) */
static inline bool is_game_port(uint16_t port) {
    return (port >= 7000 && port <= 25000);
}

static inline bool should_ghost_drop(bool likely_position) {
    // Ghost retiene únicamente la firma repetitiva de posición ya aprendida.
    // ACK, daño, disparos y eventos de tamaño/cadencia distintos siguen pasando.
    return gGhostActive.load() && likely_position;
}

/* ── Get or create a protected socket for a given game source port ──────── */
static int get_or_create_flow(bool is_ipv6, const uint8_t* game_ip, uint16_t game_port,
                               const uint8_t* srv_ip, uint16_t srv_port) {
    pthread_mutex_lock(&g_flows_mtx);
    for (int i = 0; i < g_flow_count; i++) {
        if (g_flows[i].is_ipv6 == is_ipv6 && g_flows[i].game_port == game_port) {
            bool ip_match = false;
            if (is_ipv6) {
                ip_match = (memcmp(g_flows[i].game_ip6, game_ip, 16) == 0);
            } else {
                ip_match = (g_flows[i].game_ip == *(const uint32_t*)game_ip);
            }
            if (ip_match) {
                int fd = g_flows[i].sock;
                g_flows[i].last_activity = get_now_ms(); // Update activity
                pthread_mutex_unlock(&g_flows_mtx);
                return fd;
            }
        }
    }
    if (g_flow_count >= MAX_FLOWS) {
        pthread_mutex_unlock(&g_flows_mtx);
        LOGE("MAX_FLOWS reached");
        return -1;
    }

    int sock = socket(is_ipv6 ? AF_INET6 : AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) { pthread_mutex_unlock(&g_flows_mtx); return -1; }

    int opt = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    setsockopt(sock, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt));

    if (!is_ipv6) {
        sockaddr_in bind_addr{};
        bind_addr.sin_family = AF_INET;
        bind_addr.sin_addr.s_addr = htonl(INADDR_ANY);
        bind_addr.sin_port = game_port;
        bind(sock, (sockaddr*)&bind_addr, sizeof(bind_addr));
    } else {
        sockaddr_in6 bind_addr6{};
        bind_addr6.sin6_family = AF_INET6;
        bind_addr6.sin6_addr = in6addr_any;
        bind_addr6.sin6_port = game_port;
        bind(sock, (sockaddr*)&bind_addr6, sizeof(bind_addr6));
    }

    if (!protect_fd(sock)) {
        LOGE("protect() failed for sock=%d", sock);
        close(sock);
        pthread_mutex_unlock(&g_flows_mtx);
        return -1;
    }
    // Non-blocking so epoll wait stays responsive
    fcntl(sock, F_SETFL, fcntl(sock, F_GETFL, 0) | O_NONBLOCK);

    Flow new_flow{};
    new_flow.sock = sock;
    new_flow.is_ipv6 = is_ipv6;
    new_flow.game_port = game_port;
    new_flow.srv_port = srv_port;
    if (is_ipv6) {
        memcpy(new_flow.game_ip6, game_ip, 16);
        memcpy(new_flow.srv_ip6, srv_ip, 16);
    } else {
        new_flow.game_ip = *(const uint32_t*)game_ip;
        new_flow.srv_ip = *(const uint32_t*)srv_ip;
    }
    new_flow.last_activity = get_now_ms(); // Initialize activity

    g_flows[g_flow_count++] = new_flow;
    
    if (is_ipv6) {
        LOGI("New IPv6 flow game_port=%u → srv_port=%u (sock=%d)",
             ntohs(game_port), ntohs(srv_port), sock);
    } else {
        LOGI("New IPv4 flow game_port=%u → srv %08x:%u (sock=%d)",
             ntohs(game_port), ntohl(*(const uint32_t*)srv_ip), ntohs(srv_port), sock);
    }

    epoll_add(sock);

    pthread_mutex_unlock(&g_flows_mtx);
    return sock;
}

/* ── NAT Garbage Collector ───────────────────────────────────────────────── */
static void nat_garbage_collector() {
    pthread_mutex_lock(&g_flows_mtx);
    uint64_t now = get_now_ms();
    
    // 1. GC para flujos UDP (60 segundos de inactividad)
    for (int i = 0; i < g_flow_count; i++) {
        if (now - g_flows[i].last_activity > 60000) {
            int fd = g_flows[i].sock;
            DBG_LOG("NAT GC: Removing inactive UDP flow game_port=%u (sock=%d) due to 60s timeout.", 
                    ntohs(g_flows[i].game_port), fd);
            if (fd >= 0) {
                epoll_del(fd);
                close(fd);
            }
            // Swap with the last element
            g_flows[i] = g_flows[g_flow_count - 1];
            g_flow_count--;
            i--; // Re-evaluate index
        }
    }
    
    // 2. GC para flujos TCP (30 segundos de inactividad)
    for (int i = 0; i < g_tcp_flow_count; i++) {
        if (g_tcp_flows[i].sock >= 0 && (now - g_tcp_flows[i].last_activity > 30000)) {
            int fd = g_tcp_flows[i].sock;
            DBG_LOG("NAT TCP GC: Removing inactive TCP flow port=%u (sock=%d) due to 30s timeout.", 
                    ntohs(g_tcp_flows[i].client_port), fd);
            epoll_del(fd);
            close(fd);
            g_tcp_flows[i].sock = -1;
        }
    }
    
    pthread_mutex_unlock(&g_flows_mtx);
}

/* ── Write a reconstructed UDP/IP packet back to the tun interface (IPv4) ── */
static void write_to_tun(int tun_fd,
                          uint32_t src_ip, uint16_t src_port,
                          uint32_t dst_ip, uint16_t dst_port,
                          const uint8_t* payload, int plen) {
    if (plen > 1480) return; // Supera MTU (1500 - 20 - 8)

    int udp_len = 8 + plen;
    int ip_len  = 20 + udp_len;
    if (ip_len > 2000) return;

    uint8_t pkt[2000];
    memset(pkt, 0, ip_len);

    // IP header
    IpHdr* iph = (IpHdr*)pkt;
    iph->ihl_ver  = 0x45;
    iph->tot_len  = htons(ip_len);
    iph->id       = htons(secure_random_u16());
    iph->ttl      = 64;
    iph->proto    = 17;
    iph->saddr    = src_ip;
    iph->daddr    = dst_ip;
    iph->check    = ip_csum(iph, 20);

    // UDP header
    UdpHdr* udph = (UdpHdr*)(pkt + 20);
    udph->sport = src_port;
    udph->dport = dst_port;
    udph->len   = htons(udp_len);
    memcpy(pkt + 28, payload, plen);
    udph->check = htons(transport_csum(src_ip, dst_ip, 17, (uint8_t*)udph, udp_len));

    write(tun_fd, pkt, ip_len);
}

/* ── Write a reconstructed UDP/IPv6 packet back to the tun interface (IPv6) ── */
static void write_ipv6_to_tun(int tun_fd,
                              const uint8_t* src_ip, uint16_t src_port,
                              const uint8_t* dst_ip, uint16_t dst_port,
                              const uint8_t* payload, int plen) {
    if (plen > 1460) return; // Supera MTU (1500 - 40 - 8)

    int udp_len = 8 + plen;
    int ip_len  = 40 + udp_len;
    if (ip_len > 2000) return;

    uint8_t pkt[2000];
    memset(pkt, 0, ip_len);

    // IPv6 header
    Ip6Hdr* ip6h = (Ip6Hdr*)pkt;
    ip6h->vt_tc_fl = htonl(0x60000000); // Version 6
    ip6h->payload_len = htons(udp_len);
    ip6h->next_hdr = 17; // UDP
    ip6h->hop_limit = 64;
    memcpy(ip6h->saddr, src_ip, 16);
    memcpy(ip6h->daddr, dst_ip, 16);

    // UDP header
    UdpHdr* udph = (UdpHdr*)(pkt + 40);
    udph->sport = src_port;
    udph->dport = dst_port;
    udph->len   = htons(udp_len);
    memcpy(pkt + 48, payload, plen);
    udph->check = htons(transport_csum_v6(src_ip, dst_ip, 17, (uint8_t*)udph, udp_len));

    write(tun_fd, pkt, ip_len);
}

/* ── Cleanup ─────────────────────────────────────────────────────────────── */
static void cleanup_flows() {
    pthread_mutex_lock(&g_flows_mtx);
    for (int i = 0; i < g_flow_count; i++) {
        epoll_del(g_flows[i].sock);
        close(g_flows[i].sock);
    }
    g_flow_count = 0;
    for (int i = 0; i < g_tcp_flow_count; i++) {
        if (g_tcp_flows[i].sock >= 0) {
            epoll_del(g_tcp_flows[i].sock);
            close(g_tcp_flows[i].sock);
        }
    }
    g_tcp_flow_count = 0;
    pthread_mutex_unlock(&g_flows_mtx);
    clear_outbound_queue();
}

static void write_tcp_to_tun(int tun_fd,
                              uint32_t src_ip, uint16_t src_port,
                              uint32_t dst_ip, uint16_t dst_port,
                              uint32_t seq, uint32_t ack_seq, uint8_t flags,
                              const uint8_t* payload, int plen) {
    int tcph_len = 20;
    int ip_len = 20 + tcph_len + plen;
    if (ip_len > 2000) return;

    uint8_t pkt[2000];
    memset(pkt, 0, ip_len);

    // IP header
    IpHdr* iph = (IpHdr*)pkt;
    iph->ihl_ver  = 0x45;
    iph->tot_len  = htons(ip_len);
    iph->id       = htons(secure_random_u16());
    iph->ttl      = 64;
    iph->proto    = 6; // TCP
    iph->saddr    = src_ip;
    iph->daddr    = dst_ip;
    iph->check    = ip_csum(iph, 20);

    // TCP header
    TcpHdr* tcph = (TcpHdr*)(pkt + 20);
    tcph->sport = src_port;
    tcph->dport = dst_port;
    tcph->seq = htonl(seq);
    tcph->ack_seq = htonl(ack_seq);
    tcph->off = (tcph_len / 4) << 4;
    tcph->flags = flags;
    tcph->win = htons(65535);
    
    if (plen > 0) {
        memcpy(pkt + 20 + tcph_len, payload, plen);
    }
    
    tcph->check = htons(transport_csum(src_ip, dst_ip, 6, (uint8_t*)tcph, tcph_len + plen));

    write(tun_fd, pkt, ip_len);
}

static void handle_tcp_packet(int tun_fd, IpHdr* iph, uint8_t* buf, int n) {
    int ihl = (iph->ihl_ver & 0x0f) * 4;
    TcpHdr* tcph = (TcpHdr*)(buf + ihl);
    int tcph_len = (tcph->off >> 4) * 4;
    uint8_t* payload = buf + ihl + tcph_len;
    int plen = ntohs(iph->tot_len) - ihl - tcph_len;

    uint8_t flags = tcph->flags;

    // 1. Si es un FIN o RST (Cierre/Reinicio de conexión por el cliente) -> Limpiar flow
    if (flags & (0x01 | 0x04)) {
        pthread_mutex_lock(&g_flows_mtx);
        for (int i = 0; i < g_tcp_flow_count; i++) {
            if (g_tcp_flows[i].sock >= 0 && 
                g_tcp_flows[i].client_port == tcph->sport && 
                g_tcp_flows[i].server_ip == iph->daddr) {
                
                int sock_to_close = g_tcp_flows[i].sock;
                if (sock_to_close >= 0) {
                    epoll_del(sock_to_close);
                    close(sock_to_close);
                    g_tcp_flows[i].sock = -1;
                    LOGI("TCP Connection closed by client (FIN/RST) on port=%u", ntohs(tcph->sport));
                }
                break;
            }
        }
        pthread_mutex_unlock(&g_flows_mtx);
        return;
    }
    
    // 2. Si es un SYN (intento de conexión) -> Crear/Reutilizar flow
    if (flags & 0x02) {
        LOGI("TCP SYN from %u to %u", ntohs(tcph->sport), ntohs(tcph->dport));
        
        int sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (sock >= 0) {
            int opt = 1;
            setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
            setsockopt(sock, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt));
            sockaddr_in bind_addr{};
            bind_addr.sin_family = AF_INET;
            bind_addr.sin_addr.s_addr = htonl(INADDR_ANY);
            bind_addr.sin_port = tcph->sport;
            bind(sock, (sockaddr*)&bind_addr, sizeof(bind_addr));
            protect_fd(sock);
            fcntl(sock, F_SETFL, fcntl(sock, F_GETFL, 0) | O_NONBLOCK);
            
            sockaddr_in dst{};
            dst.sin_family = AF_INET;
            dst.sin_addr.s_addr = iph->daddr;
            dst.sin_port = tcph->dport;
            
            connect(sock, (sockaddr*)&dst, sizeof(dst));
            
            pthread_mutex_lock(&g_flows_mtx);
            int slot = -1;
            // Buscar un slot libre para reutilizar y evitar desborde de MAX_TCP_FLOWS
            for (int i = 0; i < g_tcp_flow_count; i++) {
                if (g_tcp_flows[i].sock == -1) {
                    slot = i;
                    break;
                }
            }
            if (slot == -1 && g_tcp_flow_count < MAX_TCP_FLOWS) {
                slot = g_tcp_flow_count++;
            }
            
            if (slot != -1) {
                g_tcp_flows[slot] = {
                    sock, iph->saddr, tcph->sport, iph->daddr, tcph->dport,
                    1000, ntohl(tcph->seq) + 1, false, get_now_ms()
                };
                LOGI("Created TCP flow at slot %d for port %u, waiting for connect...", slot, ntohs(tcph->sport));
                epoll_add_write(sock); // Escuchar lecturas y escrituras (finalización del connect)
            } else {
                close(sock);
                LOGE("MAX_TCP_FLOWS (%d) reached! Dropping TCP connection.", MAX_TCP_FLOWS);
            }
            pthread_mutex_unlock(&g_flows_mtx);
        }
        return;
    }
    
    // 3. Si es un ACK con datos
    if ((flags & 0x10) && plen > 0) {
        int flow_idx = -1;
        pthread_mutex_lock(&g_flows_mtx);
        for (int i = 0; i < g_tcp_flow_count; i++) {
            if (g_tcp_flows[i].sock >= 0 && 
                g_tcp_flows[i].client_port == tcph->sport && 
                g_tcp_flows[i].server_ip == iph->daddr) {
                flow_idx = i;
                break;
            }
        }
        
        if (flow_idx != -1) {
            TcpFlow& flow = g_tcp_flows[flow_idx];
            if (flow.sock >= 0) {
                send(flow.sock, payload, plen, 0);
                flow.seq_from_client += plen;
                flow.last_activity = get_now_ms(); // Actualizar actividad para evitar GC
                
                // Responder con ACK para que el juego siga enviando
                write_tcp_to_tun(tun_fd, 
                                 iph->daddr, tcph->dport,
                                 iph->saddr, tcph->sport,
                                 flow.seq_to_client, flow.seq_from_client,
                                 0x10, // ACK
                                 nullptr, 0);
            }
        }
        pthread_mutex_unlock(&g_flows_mtx);
    }
}

/* ── Main engine thread ──────────────────────────────────────────────────── */
void* engine_thread(void*) {
    LOGI("Asymmetric UDP Proxy started. tun_fd=%d", g_tun_fd);

    // Establecer prioridad alta para el hilo de red nativo (evitar retrasos/jitter)
    if (setpriority(PRIO_PROCESS, 0, -20) < 0) { // Set maximum priority (nice -20)
        LOGE("Failed to set thread priority: %s", strerror(errno));
    } else {
        LOGI("Native network thread priority set to high (-20)");
    }

    // Elevate schedule priority using pthread_setschedparam (Fase 4)
    struct sched_param param;
    param.sched_priority = sched_get_priority_max(SCHED_FIFO);
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &param) != 0) {
        // Fallback to SCHED_RR
        param.sched_priority = sched_get_priority_max(SCHED_RR);
        if (pthread_setschedparam(pthread_self(), SCHED_RR, &param) != 0) {
            LOGW("Could not set RT scheduling policy: %s. Relying on nice (-20).", strerror(errno));
        } else {
            LOGI("Native network thread scheduler set to SCHED_RR (Real-Time)");
        }
    } else {
        LOGI("Native network thread scheduler set to SCHED_FIFO (Real-Time)");
    }

    g_epoll_fd = epoll_create1(0);
    if (g_epoll_fd < 0) {
        LOGE("epoll_create1 failed: %s", strerror(errno));
        return nullptr;
    }

    int tun = g_tun_fd;
    if (tun < 0) {
        close(g_epoll_fd);
        g_epoll_fd = -1;
        return nullptr;
    }

    // Configurar el FD del TUN como no bloqueante (lecturas asíncronas no bloqueantes)
    int flags = fcntl(tun, F_GETFL, 0);
    if (flags < 0 || fcntl(tun, F_SETFL, flags | O_NONBLOCK) < 0) {
        LOGE("Failed to set TUN FD to non-blocking: %s", strerror(errno));
    } else {
        LOGI("TUN FD successfully set to non-blocking mode");
    }

    epoll_add(tun);
    epoll_add(g_pipe[0]);

    const int MAX_EVENTS = 64;
    struct epoll_event events[MAX_EVENTS];

    while (g_running) {
        int timeout_ms = 100;
        pthread_mutex_lock(&g_outbound_queue_mtx);
        if (g_outbound_queue_count > 0) {
            timeout_ms = 1;
        }
        pthread_mutex_unlock(&g_outbound_queue_mtx);
        if (g_teleport_drop_state.load() == 2) timeout_ms = 1;

        int nfds = epoll_wait(g_epoll_fd, events, MAX_EVENTS, timeout_ms);
        if (nfds < 0) {
            if (errno == EINTR) continue;
            LOGE("epoll_wait error: %s", strerror(errno));
            break;
        }

        // Procesar cualquier paquete con retardo (jitter general o filtro selectivo).
        process_outbound_queue();
        process_teleport_replay();

        // Safety timeout flush check for delayed packets (Requirement 12)
        // Safety timeout flush check for delayed packets (Paso 7: Watchdog Dinámico)
        if (gLagActive.load()) {
            uint64_t now = get_now_ms();
            pthread_mutex_lock(&g_delay_queue_mtx);
            if (g_delay_queue_count > 0) {
                uint64_t oldest_age = now - g_delay_queue[g_delay_queue_head].timestamp;
                if (oldest_age > g_max_desync_ms.load()) { // Límite de seguridad dinámico
                    pthread_mutex_unlock(&g_delay_queue_mtx);
                    LOGI("Safety timeout limit reached: flushing delay queue (%d packets)", g_delay_queue_count);
                    delay_queue_flush(tun);
                } else {
                    pthread_mutex_unlock(&g_delay_queue_mtx);
                }
            } else {
                pthread_mutex_unlock(&g_delay_queue_mtx);
            }
        }

        // Run NAT GC every 5 seconds (Requirement: garbage collect inactive flows)
        static uint64_t last_gc_run = 0;
        uint64_t now_gc = get_now_ms();
        if (now_gc - last_gc_run > 5000) {
            last_gc_run = now_gc;
            nat_garbage_collector();
        }

        for (int i = 0; i < nfds; i++) {
            int fd = events[i].data.fd;
            uint32_t evs = events[i].events;

            // Paso 3: Gestión activa de errores y hangup en epoll
            if (evs & (EPOLLERR | EPOLLHUP)) {
                LOGE("EPOLLERR/EPOLLHUP detectado en fd %d", fd);
                if (fd != tun && fd != g_pipe[0]) {
                    epoll_del(fd);
                    close(fd);
                    pthread_mutex_lock(&g_flows_mtx);
                    for (int j = 0; j < g_flow_count; j++) {
                        if (g_flows[j].sock == fd) {
                            g_flows[j].sock = -1;
                        }
                    }
                    for (int j = 0; j < g_tcp_flow_count; j++) {
                        if (g_tcp_flows[j].sock == fd) {
                            g_tcp_flows[j].sock = -1;
                        }
                    }
                    pthread_mutex_unlock(&g_flows_mtx);
                }
                continue;
            }

            if (fd == g_pipe[0]) {
                LOGI("Stop signal received via pipe.");
                g_running = false;
                break;
            }

            if (fd == tun) {
                // Leer todos los paquetes disponibles del TUN de forma no bloqueante (Zero-Allocation)
                while (g_running) {
                    PacketBuffer* pkt_buf = acquire_buffer();
                    int n = read(tun, pkt_buf->data, sizeof(pkt_buf->data));
                    if (n < 0) {
                        if (errno == EAGAIN || errno == EWOULDBLOCK) {
                            break; // No hay más datos por leer en este ciclo
                        }
                        LOGE("TUN read error: %s", strerror(errno));
                        break;
                    }
                    if (n == 0) {
                        break; // EOF
                    }
                    if (n > 20) {
                        uint8_t version = (pkt_buf->data[0] >> 4) & 0x0f;
                        if (version == 4) { // IPv4 Zero-Copy Parsing
                            IpHdr* iph = (IpHdr*)pkt_buf->data;
                            int ihl = (iph->ihl_ver & 0x0f) * 4;
                            if (iph->proto == 17 && n > ihl + 8) { // UDP
                                UdpHdr* udph = (UdpHdr*)(pkt_buf->data + ihl);
                                uint8_t* payload = pkt_buf->data + ihl + 8;
                                int plen = ntohs(udph->len) - 8;
                                
                                // BYPASS INMEDIATO DE DNS (Puerto 53)
                                if (udph->sport == htons(53) || udph->dport == htons(53)) {
                                    int sock = get_or_create_flow(
                                        false, 
                                        (const uint8_t*)&iph->saddr, udph->sport,
                                        (const uint8_t*)&iph->daddr, udph->dport);
                                    if (sock > 0) {
                                        sockaddr_in dst{};
                                        dst.sin_family      = AF_INET;
                                        dst.sin_addr.s_addr = iph->daddr;
                                        dst.sin_port        = udph->dport;
                                        sendto(sock, payload, plen, 0, (sockaddr*)&dst, sizeof(dst));
                                    }
                                } else if (plen > 0 && plen <= n - ihl - 8) {
                                    uint16_t dport = ntohs(udph->dport);
                                    bool is_game = is_game_port(dport);
                                    int sock = get_or_create_flow(
                                        false, 
                                        (const uint8_t*)&iph->saddr, udph->sport,
                                        (const uint8_t*)&iph->daddr, udph->dport);
                                    if (sock > 0) {
                                        sockaddr_in dst{};
                                        dst.sin_family      = AF_INET;
                                        dst.sin_addr.s_addr = iph->daddr;
                                        dst.sin_port        = udph->dport;

                                        bool likelyPosition =
                                            observe_likely_position(dport, plen);
                                        int teleportState = g_teleport_drop_state.load();
                                        if (likelyPosition) {
                                            if (teleportState == 1 &&
                                                capture_teleport_packet(
                                                    sock, payload, plen,
                                                    (sockaddr*)&dst, sizeof(dst))) {
                                                continue;
                                            }
                                            if (teleportState == 2) {
                                                // Preservar el orden mientras sale la ruta retenida.
                                                continue;
                                            }
                                        }
                                        if (should_ghost_drop(likelyPosition)) {
                                            gGhostDroppedPackets.fetch_add(
                                                1, std::memory_order_relaxed);
                                            continue;
                                        }
                                        
                                        uint32_t delay_ms = is_game ? g_outbound_jitter_ms.load() : 0;
                                        if (g_selective_udp_delay_active.load() && plen >= 50 && plen <= 150) {
                                            delay_ms = std::max(delay_ms, g_selective_udp_delay_ms.load());
                                        }
                                        if (delay_ms > 0) {
                                            outbound_queue_push(sock, payload, plen, (sockaddr*)&dst, sizeof(dst), delay_ms);
                                        } else {
                                            sendto(sock, payload, plen, 0,
                                                   (sockaddr*)&dst, sizeof(dst));
                                        }
                                    }
                                }
                            } else if (iph->proto == 6 && n > ihl + 20) { // TCP (Early Protocol Filter: instant transparent bypass with QoS)
                                if (qos_should_throttle_tcp()) {
                                    usleep(1000); // 1ms slowdown to prioritize UDP
                                }
                                handle_tcp_packet(tun, iph, pkt_buf->data, n);
                            }
                        } else if (version == 6 && n > 40) { // IPv6 Zero-Copy Parsing
                            Ip6Hdr* ip6h = (Ip6Hdr*)pkt_buf->data;
                            if (ip6h->next_hdr == 17 && n > 40 + 8) { // UDP over IPv6
                                UdpHdr* udph = (UdpHdr*)(pkt_buf->data + 40);
                                uint8_t* payload = pkt_buf->data + 40 + 8;
                                int plen = ntohs(udph->len) - 8;
                                
                                // BYPASS INMEDIATO DE DNS EN IPv6 (Puerto 53)
                                if (udph->sport == htons(53) || udph->dport == htons(53)) {
                                    int sock = get_or_create_flow(
                                        true,
                                        ip6h->saddr, udph->sport,
                                        ip6h->daddr, udph->dport);
                                    if (sock > 0) {
                                        sockaddr_in6 dst{};
                                        dst.sin6_family = AF_INET6;
                                        memcpy(&dst.sin6_addr, ip6h->daddr, 16);
                                        dst.sin6_port = udph->dport;
                                        sendto(sock, payload, plen, 0, (sockaddr*)&dst, sizeof(dst));
                                    }
                                } else if (plen > 0 && plen <= n - 48) {
                                    uint16_t dport = ntohs(udph->dport);
                                    int sock = get_or_create_flow(
                                        true,
                                        ip6h->saddr, udph->sport,
                                        ip6h->daddr, udph->dport);
                                    if (sock > 0) {
                                        sockaddr_in6 dst{};
                                        dst.sin6_family = AF_INET6;
                                        memcpy(&dst.sin6_addr, ip6h->daddr, 16);
                                        dst.sin6_port = udph->dport;

                                        bool likelyPosition =
                                            observe_likely_position(dport, plen);
                                        int teleportState = g_teleport_drop_state.load();
                                        if (likelyPosition) {
                                            if (teleportState == 1 &&
                                                capture_teleport_packet(
                                                    sock, payload, plen,
                                                    (sockaddr*)&dst, sizeof(dst))) {
                                                continue;
                                            }
                                            if (teleportState == 2) {
                                                continue;
                                            }
                                        }
                                        if (should_ghost_drop(likelyPosition)) {
                                            gGhostDroppedPackets.fetch_add(
                                                1, std::memory_order_relaxed);
                                            continue;
                                        }
                                        
                                        bool is_game = is_game_port(dport);
                                        uint32_t delay_ms = is_game ? g_outbound_jitter_ms.load() : 0;
                                        if (g_selective_udp_delay_active.load() && plen >= 50 && plen <= 150) {
                                            delay_ms = std::max(delay_ms, g_selective_udp_delay_ms.load());
                                        }
                                        if (delay_ms > 0) {
                                            outbound_queue_push(sock, payload, plen, (sockaddr*)&dst, sizeof(dst), delay_ms);
                                        } else {
                                            sendto(sock, payload, plen, 0,
                                                   (sockaddr*)&dst, sizeof(dst));
                                        }
                                    }
                                }
                            }
                            // TCP over IPv6 is bypassed natively by the Android VPN routing table 
                            // or handled here if needed.
                        }
                    }
                }
            } else {
                bool processed = false;

                pthread_mutex_lock(&g_flows_mtx);
                // Check UDP flows
                for (int j = 0; j < g_flow_count; j++) {
                    if (g_flows[j].sock == fd) {
                        processed = true;
                        g_flows[j].last_activity = get_now_ms(); // Update activity for GC
                        // Leer todos los paquetes recibidos en el socket proxy UDP de forma no bloqueante (Zero-Allocation)
                        while (g_running) {
                            PacketBuffer* pkt_buf = acquire_buffer();
                            sockaddr_storage from{};
                            socklen_t fromlen = sizeof(from);
                            int n = recvfrom(fd, pkt_buf->data, sizeof(pkt_buf->data), 0,
                                             (sockaddr*)&from, &fromlen);
                            if (n < 0) {
                                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                                    break; // No hay más datos por recibir
                                }
                                LOGE("recvfrom error: %s", strerror(errno));
                                break;
                            }
                            if (n == 0) {
                                break;
                            }
                            
                            // Determinar si debemos aplicar el lag switch.
                            // Solo se aplica si el lag está activo Y es un puerto de partida del juego.
                            // Tráfico UDP ajeno al juego (por ejemplo DNS) se enruta al instante sin retraso.
                            uint16_t srv_port = g_flows[j].srv_port;
                            bool is_game = is_game_port(ntohs(srv_port));
                            // Ghost/Teleport son modos salientes: nunca deben
                            // detener las actualizaciones entrantes de enemigos.
                            const bool outboundPositionMode =
                                gGhostActive.load() || g_teleport_drop_state.load() != 0;
                            bool drop_packet = gLagActive.load() && is_game &&
                                               !outboundPositionMode;

                            if (drop_packet) {
                                // 1. Descarte Probabilístico Realista (Evitar detección abrupta)
                                if (should_probabilistic_drop()) {
                                    DBG_LOG("LAG-DROP: probabilistic discard of UDP packet. size=%d, srv_port=%u", n, ntohs(srv_port));
                                    continue;
                                }

                                // 2. Ofuscación de Tráfico (Jitter / Latido Heartbeat para evitar baneo)
                                if (handle_lag_evasion(n)) {
                                    DBG_LOG("LAG-EVASION: Keep-alive heartbeat/small packet bypassed. size=%d, srv_port=%u", n, ntohs(srv_port));
                                    // Dejar pasar latidos o keep-alive de inmediato
                                    if (g_flows[j].is_ipv6) {
                                        sockaddr_in6* from6 = (sockaddr_in6*)&from;
                                        write_ipv6_to_tun(tun,
                                            from6->sin6_addr.s6_addr, from6->sin6_port,
                                            g_flows[j].game_ip6, g_flows[j].game_port,
                                            pkt_buf->data, n);
                                    } else {
                                        sockaddr_in* from4 = (sockaddr_in*)&from;
                                        write_to_tun(tun,
                                            from4->sin_addr.s_addr, from4->sin_port,
                                            g_flows[j].game_ip, g_flows[j].game_port,
                                            pkt_buf->data, n);
                                    }
                                } else {
                                    DBG_LOG("LAG-CHOKE: Queuing game UDP packet. size=%d, srv_port=%u", n, ntohs(srv_port));
                                    // 3. Cola de Retención (Fake Lag / Choke)
                                    if (g_flows[j].is_ipv6) {
                                        sockaddr_in6* from6 = (sockaddr_in6*)&from;
                                        delay_queue_push(true,
                                            from6->sin6_addr.s6_addr, from6->sin6_port,
                                            g_flows[j].game_ip6, g_flows[j].game_port,
                                            pkt_buf->data, n);
                                    } else {
                                        sockaddr_in* from4 = (sockaddr_in*)&from;
                                        delay_queue_push(false,
                                            (const uint8_t*)&from4->sin_addr.s_addr, from4->sin_port,
                                            (const uint8_t*)&g_flows[j].game_ip, g_flows[j].game_port,
                                            pkt_buf->data, n);
                                    }
                                }
                            } else {
                                // Tráfico no retenido o lag apagado
                                if (g_flows[j].is_ipv6) {
                                    sockaddr_in6* from6 = (sockaddr_in6*)&from;
                                    write_ipv6_to_tun(tun,
                                        from6->sin6_addr.s6_addr, from6->sin6_port,
                                        g_flows[j].game_ip6, g_flows[j].game_port,
                                        pkt_buf->data, n);
                                } else {
                                    sockaddr_in* from4 = (sockaddr_in*)&from;
                                    write_to_tun(tun,
                                        from4->sin_addr.s_addr, from4->sin_port,
                                        g_flows[j].game_ip, g_flows[j].game_port,
                                        pkt_buf->data, n);
                                }
                            }
                        }
                        break;
                    }
                }

                // Check TCP flows
                if (!processed) {
                    for (int j = 0; j < g_tcp_flow_count; j++) {
                        if (g_tcp_flows[j].sock == fd) {
                            processed = true;
                            
                            // Si la conexión real con el servidor no se ha completado, este evento es del connect()
                            if (!g_tcp_flows[j].connected) {
                                int error = 0;
                                socklen_t len = sizeof(error);
                                if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &len) < 0 || error != 0) {
                                    LOGE("TCP connect failed for port %u: %s", ntohs(g_tcp_flows[j].client_port), strerror(error));
                                    epoll_del(fd);
                                    close(fd);
                                    g_tcp_flows[j].sock = -1;
                                    break;
                                }
                                
                                g_tcp_flows[j].connected = true;
                                g_tcp_flows[j].last_activity = get_now_ms();
                                epoll_mod_read(fd); // Volver a escuchar solo lectura normal (quitar EPOLLOUT)
                                
                                // Ahora sí, responder con el SYN-ACK al juego para completar el handshake
                                write_tcp_to_tun(tun, 
                                                 g_tcp_flows[j].server_ip, g_tcp_flows[j].server_port,
                                                 g_tcp_flows[j].client_ip, g_tcp_flows[j].client_port,
                                                 1000, g_tcp_flows[j].seq_from_client,
                                                 0x12, // SYN | ACK
                                                 nullptr, 0);
                                LOGI("TCP connect successful! SYN-ACK sent to client for port %u", ntohs(g_tcp_flows[j].client_port));
                                break;
                            }
                            
                            while (g_running) {
                                PacketBuffer* pkt_buf = acquire_buffer();
                                int n = recv(fd, pkt_buf->data, sizeof(pkt_buf->data), 0);
                                if (n < 0) {
                                    if (errno == EAGAIN || errno == EWOULDBLOCK) {
                                        break;
                                    }
                                    LOGE("recv error: %s", strerror(errno));
                                    epoll_del(fd);
                                    close(fd);
                                    g_tcp_flows[j].sock = -1;
                                    break;
                                }
                                if (n == 0) {
                                    epoll_del(fd);
                                    close(fd);
                                    g_tcp_flows[j].sock = -1;
                                    break;
                                }
                                write_tcp_to_tun(tun, 
                                                 g_tcp_flows[j].server_ip, g_tcp_flows[j].server_port,
                                                 g_tcp_flows[j].client_ip, g_tcp_flows[j].client_port,
                                                 g_tcp_flows[j].seq_to_client, g_tcp_flows[j].seq_from_client,
                                                 0x18, // PSH | ACK
                                                 pkt_buf->data, n);
                                g_tcp_flows[j].seq_to_client += n;
                                g_tcp_flows[j].last_activity = get_now_ms(); // Actualizar actividad
                            }
                            break;
                        }
                    }
                }
                pthread_mutex_unlock(&g_flows_mtx);
            }
        }
    }

    cleanup_flows();
    close(g_epoll_fd);
    g_epoll_fd = -1;
    // Paso 1: Cerrar el extremo de lectura del pipe de forma segura en su propio hilo
    if (g_pipe[0] > 0) {
        close(g_pipe[0]);
        g_pipe[0] = -1;
    }
    LOGI("Asymmetric UDP Proxy stopped.");
    return nullptr;
}

/* ── JNI Exports ─────────────────────────────────────────────────────────── */

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_AntigravityFirewall_startNativeEngine(
        JNIEnv* env, jobject thiz, jint fd) {
    if (g_running) return;

    // Store JavaVM + service reference for protect() callbacks
    env->GetJavaVM(&g_jvm);
    if (g_svc_ref) { env->DeleteGlobalRef(g_svc_ref); g_svc_ref = nullptr; }
    g_svc_ref = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    g_protect  = env->GetMethodID(cls, "protectSocket", "(I)Z");
    env->DeleteLocalRef(cls);

    if (pipe(g_pipe) < 0) { LOGE("pipe() failed"); return; }
    g_tun_fd = fd;
    // Conservar los estados solicitados mientras Android termina de crear o
    // recrear el TUN; reiniciarlos aquí producía una carrera al activar modos.
    g_running  = true;

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&g_thread, &attr, engine_thread, nullptr);
    pthread_attr_destroy(&attr);
    LOGI("Engine started. fd=%d", fd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_AntigravityFirewall_stopNativeEngine(
        JNIEnv* env, jobject /*thiz*/) {
    if (!g_running) return;
    g_running = false;
    cancel_teleport_drop();
    g_tun_fd  = -1;
    // Paso 1: Solo cerrar el extremo de escritura y notificar al hilo
    if (g_pipe[1] > 0) { 
        uint8_t b = 1; 
        write(g_pipe[1], &b, 1);
        close(g_pipe[1]); 
        g_pipe[1] = -1; 
    }
    if (g_svc_ref) { env->DeleteGlobalRef(g_svc_ref); g_svc_ref = nullptr; }
    LOGI("Engine stop signaled.");
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_AntigravityFirewall_setLagActive(
        JNIEnv* /*env*/, jclass /*cls*/, jboolean active) {
    gLagActive = (bool)active;
    LOGI("Lag switch: %s", (bool)active ? "ON (enemy frozen)" : "OFF (normal)");
    if (!active && g_tun_fd >= 0) {
        delay_queue_flush(g_tun_fd);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_AntigravityFirewall_setGhostActive(
        JNIEnv* /*env*/, jclass /*cls*/, jboolean active) {
    gGhostActive.store((bool)active);
    if (active) gGhostDroppedPackets.store(0, std::memory_order_relaxed);
    LOGI("Ghost outbound filter: %s (adaptive movement signature)",
         (bool)active ? "ON" : "OFF");
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_AntigravityFirewall_setTeleportDropActive(
        JNIEnv* /*env*/, jclass /*cls*/, jboolean active) {
    if (active) {
        pthread_mutex_lock(&g_teleport_mtx);
        clear_teleport_queues_locked();
        g_teleport_drop_state.store(1);
        pthread_mutex_unlock(&g_teleport_mtx);
        LOGI("Teleport Drop: CAPTURE");
    } else if (g_teleport_drop_state.load() == 1) {
        begin_teleport_replay();
        LOGI("Teleport Drop: REPLAY");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_freezy_AntigravityFirewall_getTeleportDropState(
        JNIEnv* /*env*/, jclass /*cls*/) {
    return (jint)g_teleport_drop_state.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_AntigravityFirewall_cancelTeleportDrop(
        JNIEnv* /*env*/, jclass /*cls*/) {
    cancel_teleport_drop();
    LOGI("Teleport Drop: CANCELLED");
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setNativeMaxDesyncMs(
        JNIEnv* /*env*/, jclass /*cls*/, jlong ms) {
    g_max_desync_ms.store((uint64_t)ms);
    LOGI("Native safety desync timeout updated to: %llu ms", (unsigned long long)ms);
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setNativeJitterMs(
        JNIEnv* /*env*/, jclass /*cls*/, jint ms) {
    g_outbound_jitter_ms.store((uint32_t)ms);
    LOGI("Native outbound jitter buffer latency updated to: %d ms", ms);
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setNativeDropProbability(
        JNIEnv* /*env*/, jclass /*cls*/, jint pct) {
    g_drop_probability.store(pct);
    LOGI("Native packet drop probability updated to: %d%%", pct);
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setSelectiveUdpDelay(
        JNIEnv* /*env*/, jclass /*cls*/, jboolean active, jint delay_ms) {
    uint32_t safe_delay_ms = (uint32_t)std::max(0, std::min((int)delay_ms, 1000));
    g_selective_udp_delay_ms.store(safe_delay_ms);
    g_selective_udp_delay_active.store((bool)active);
    LOGI("Selective outbound UDP delay: %s (%u ms, payload 50..150)",
         (bool)active ? "ON" : "OFF", safe_delay_ms);

    // Al apagar, enviar inmediatamente cualquier datagrama retenido.
    if (!active) {
        clear_outbound_queue();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_AntigravityFirewall_notifyNetworkChange(JNIEnv* /*env*/, jclass) {
    LOGI("Network change detected! Closing old native sockets and resetting flows table...");
    pthread_mutex_lock(&g_flows_mtx);
    for (int i = 0; i < g_flow_count; i++) {
        if (g_flows[i].sock >= 0) {
            epoll_del(g_flows[i].sock);
            close(g_flows[i].sock);
            g_flows[i].sock = -1;
        }
    }
    g_flow_count = 0; // Reset UDP NAT table entries

    for (int i = 0; i < g_tcp_flow_count; i++) {
        if (g_tcp_flows[i].sock >= 0) {
            epoll_del(g_tcp_flows[i].sock);
            close(g_tcp_flows[i].sock);
            g_tcp_flows[i].sock = -1;
        }
    }
    g_tcp_flow_count = 0; // Reset TCP NAT table entries
    pthread_mutex_unlock(&g_flows_mtx);
    LOGI("All native proxy flows successfully reset. Sockets will be re-created on demand.");
}

// Función simple de descifrado XOR
// ==== XOR_SECTION_BEGIN (generado por encrypt_strings.py) ====
// XOR multi-byte: cada byte se descifra con key[i % KEY_LEN]
static const unsigned char XOR_KEY[] = {0x7B, 0xE2, 0x4D, 0x93, 0x18, 0x6A, 0xC5, 0x3F};
static const size_t XOR_KEY_LEN = sizeof(XOR_KEY);

void xor_cipher(unsigned char* data, size_t len) {
    for (size_t i = 0; i < len; i++) {
        data[i] ^= XOR_KEY[i % XOR_KEY_LEN];
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_NativeBridge_getNativeString(JNIEnv* env, jclass, jint id) {
    if (id == 1) {
        unsigned char s[] = {0x13, 0x96, 0x39, 0xE3, 0x6B, 0x50, 0xEA, 0x10, 0x17, 0x8B, 0x2E, 0xF6, 0x76, 0x09, 0xAC, 0x5E, 0x08, 0x84, 0x3F, 0xF6, 0x7D, 0x10, 0xBC, 0x11, 0x0D, 0x87, 0x3F, 0xF0, 0x7D, 0x06, 0xEB, 0x5E, 0x0B, 0x92, 0x62, 0xF2, 0x68, 0x03, 0xEA, 0x54, 0x1E, 0x9B, 0x3E, 0xBC, 0x6E, 0x0F, 0xB7, 0x56, 0x1D, 0x9B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 2) {
        unsigned char s[] = {0x32, 0xAC, 0x04, 0xD0, 0x51, 0x2B, 0x97, 0x1F, 0x3D, 0xB0, 0x08, 0xD6, 0x42, 0x33, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 3) {
        unsigned char s[] = {0x2D, 0x83, 0x21, 0xFA, 0x7C, 0x0B, 0xAB, 0x5B, 0x14, 0xC2, 0x2E, 0xFC, 0x76, 0x0F, 0xBD, 0x56, 0x14, 0x8C, 0x6D, 0xEA, 0x38, 0x06, 0xAC, 0x5C, 0x1E, 0x8C, 0x2E, 0xFA, 0x79, 0x44, 0xEB, 0x11, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 4) {
        unsigned char s[] = {0x37, 0x83, 0x23, 0xE9, 0x79, 0x04, 0xA1, 0x50, 0x5B, 0x8F, 0x22, 0xE7, 0x77, 0x18, 0xE5, 0x79, 0x09, 0x87, 0x28, 0xE9, 0x61, 0x44, 0xEB, 0x11, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 5) {
        unsigned char s[] = {0x3A, 0x81, 0x2E, 0xF6, 0x6B, 0x05, 0xE5, 0x7C, 0x14, 0x8C, 0x2E, 0xF6, 0x7C, 0x03, 0xA1, 0x50, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 6) {
        unsigned char s[] = {0x37, 0x8B, 0x2E, 0xF6, 0x76, 0x09, 0xAC, 0x5E, 0x5B, 0x8B, 0x23, 0xE5, 0x79, 0x06, 0xAC, 0x5B, 0x1A, 0xC2, 0x22, 0xB3, 0x71, 0x04, 0xA0, 0x47, 0x12, 0x91, 0x39, 0xF6, 0x76, 0x1E, 0xA0, 0x11, 0x5B, 0xA3, 0x29, 0xE2, 0x6D, 0x03, 0xA0, 0x4D, 0x1E, 0xC2, 0x38, 0xFD, 0x79, 0x4A, 0xAA, 0x59, 0x12, 0x81, 0x24, 0xF2, 0x74, 0x44, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 7) {
        unsigned char s[] = {0x2B, 0x8D, 0x3F, 0xB3, 0x7E, 0x0B, 0xB3, 0x50, 0x09, 0xCE, 0x6D, 0xF0, 0x77, 0x07, 0xB5, 0x53, 0x1E, 0x96, 0x2C, 0xB3, 0x6C, 0x05, 0xA1, 0x50, 0x08, 0xC2, 0x21, 0xFC, 0x6B, 0x4A, 0xA6, 0x5E, 0x16, 0x92, 0x22, 0xE0, 0x36, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 8) {
        unsigned char s[] = {0x2D, 0xA7, 0x1F, 0xDA, 0x5E, 0x23, 0x86, 0x7E, 0x35, 0xA6, 0x02, 0xBD, 0x36, 0x44, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 9) {
        unsigned char s[] = {0x38, 0x87, 0x3F, 0xE1, 0x79, 0x18, 0xE5, 0x7D, 0x0E, 0x90, 0x2F, 0xE6, 0x72, 0x0B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 10) {
        unsigned char s[] = {0x2F, 0x8B, 0x3D, 0xFC, 0x38, 0x0E, 0xA0, 0x1F, 0x3A, 0x81, 0x39, 0xFA, 0x6E, 0x0B, 0xA6, 0x56, 0x14, 0x8C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 11) {
        unsigned char s[] = {0x32, 0x8C, 0x2B, 0xFC, 0x6A, 0x07, 0xA4, 0x5C, 0x12, 0x8D, 0x23, 0xB3, 0x7C, 0x0F, 0xE5, 0x73, 0x12, 0x81, 0x28, 0xFD, 0x7B, 0x03, 0xA4, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 12) {
        unsigned char s[] = {0x5A, 0xC3, 0x6D, 0xD7, 0x5D, 0x39, 0x86, 0x7E, 0x29, 0xA5, 0x02, 0xB3, 0x5C, 0x2F, 0xE5, 0x6D, 0x3E, 0xB1, 0x1D, 0xDC, 0x56, 0x39, 0x84, 0x7D, 0x32, 0xAE, 0x04, 0xD7, 0x59, 0x2E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 13) {
        unsigned char s[] = {0x28, 0x8B, 0x6D, 0xF1, 0x71, 0x0F, 0xAB, 0x1F, 0x1E, 0x91, 0x39, 0xF2, 0x38, 0x02, 0xA0, 0x4D, 0x09, 0x83, 0x20, 0xFA, 0x7D, 0x04, 0xB1, 0x5E, 0x5B, 0xAC, 0x02, 0xB3, 0x79, 0x06, 0xB1, 0x5A, 0x09, 0x83, 0x6D, 0xFF, 0x77, 0x19, 0xE5, 0x5E, 0x09, 0x81, 0x25, 0xFA, 0x6E, 0x05, 0xB6, 0x1F, 0x14, 0x90, 0x24, 0xF4, 0x71, 0x04, 0xA4, 0x53, 0x1E, 0x91, 0x6D, 0xF7, 0x7D, 0x06, 0xE5, 0x55, 0x0E, 0x87, 0x2A, 0xFC, 0x34, 0x4A, 0xB1, 0x5A, 0x5B, 0x8D, 0x39, 0xFC, 0x6A, 0x0D, 0xA4, 0x1F, 0x0E, 0x8C, 0x2C, 0xB3, 0x6E, 0x0F, 0xAB, 0x4B, 0x1A, 0x88, 0x2C, 0xB3, 0x7D, 0x12, 0xB1, 0x4D, 0x1E, 0x8F, 0x2C, 0xBD, 0x12, 0x60, 0x27, 0xA5, 0xDB, 0x0D, 0xF5, 0x1C, 0x38, 0x3F, 0xB6, 0x50, 0x5B, 0x86, 0x28, 0xB3, 0x5C, 0x0B, 0xB1, 0x50, 0x08, 0xC2, 0x34, 0xB3, 0x5C, 0x03, 0xB6, 0x4F, 0x14, 0x91, 0x24, 0xE7, 0x71, 0x1C, 0xAA, 0x05, 0x71, 0xB1, 0x22, 0xFF, 0x71, 0x09, 0xAC, 0x4B, 0x1A, 0x8F, 0x22, 0xE0, 0x38, 0x0B, 0xA6, 0x5C, 0x1E, 0x91, 0x22, 0xB3, 0x79, 0x06, 0xE5, 0x18, 0x2E, 0x91, 0x22, 0xB3, 0x7C, 0x0F, 0xE5, 0x7B, 0x1A, 0x96, 0x22, 0xE0, 0x3F, 0x4A, 0xB5, 0x5E, 0x09, 0x83, 0x6D, 0xFE, 0x77, 0x04, 0xAC, 0x4B, 0x14, 0x90, 0x28, 0xF2, 0x6A, 0x4A, 0xA9, 0x5E, 0x5B, 0x87, 0x27, 0xF6, 0x7B, 0x1F, 0xA6, 0x56, 0xB8, 0x51, 0x23, 0xB3, 0x7C, 0x0F, 0xA9, 0x1F, 0x11, 0x97, 0x28, 0xF4, 0x77, 0x4A, 0xBC, 0x1F, 0x1A, 0x81, 0x39, 0xFA, 0x6E, 0x0B, 0xB7, 0x1F, 0x17, 0x83, 0x3E, 0xB3, 0x7E, 0x1F, 0xAB, 0x5C, 0x12, 0x8D, 0x23, 0xF6, 0x6B, 0x4A, 0xA6, 0x50, 0x09, 0x90, 0x28, 0xF0, 0x6C, 0x0B, 0xA8, 0x5A, 0x15, 0x96, 0x28, 0xBD, 0x38, 0x3E, 0xA4, 0x52, 0x19, 0x8B, 0x8E, 0x3A, 0x76, 0x4A, 0xA4, 0x53, 0x16, 0x83, 0x2E, 0xF6, 0x76, 0x0B, 0xA8, 0x50, 0x08, 0xC2, 0x28, 0xFF, 0x38, 0x04, 0xAA, 0x52, 0x19, 0x90, 0x28, 0xB3, 0x7C, 0x0F, 0xE5, 0x4B, 0x0E, 0xC2, 0x29, 0xFA, 0x6B, 0x1A, 0xAA, 0x4C, 0x12, 0x96, 0x24, 0xE5, 0x77, 0x4A, 0xB5, 0x5E, 0x09, 0x83, 0x6D, 0xFF, 0x79, 0x4A, 0xA1, 0x5A, 0x0F, 0x87, 0x2E, 0xF0, 0x71, 0xA9, 0x76, 0x51, 0x5B, 0x9B, 0x6D, 0xE3, 0x6A, 0x0F, 0xB3, 0x5A, 0x15, 0x81, 0x24, 0x50, 0xAB, 0x04, 0xE5, 0x5B, 0x1E, 0xC2, 0x2B, 0xF2, 0x74, 0x06, 0xA4, 0x4C, 0x5B, 0x96, 0x8E, 0x3A, 0x7B, 0x04, 0xAC, 0x5C, 0x1A, 0x91, 0x6D, 0xF6, 0x6B, 0x1A, 0xA0, 0x5C, 0xB8, 0x4F, 0x2B, 0xFA, 0x7B, 0x0B, 0xB6, 0x1F, 0x09, 0x87, 0x3D, 0xFC, 0x6A, 0x1E, 0xA4, 0x5B, 0x1A, 0x91, 0x6D, 0xF2, 0x76, 0x1E, 0xA0, 0x4D, 0x12, 0x8D, 0x3F, 0xFE, 0x7D, 0x04, 0xB1, 0x5A, 0x5B, 0x87, 0x23, 0xB3, 0x75, 0x05, 0xA1, 0x5A, 0x17, 0x8D, 0x3E, 0xB3, 0x6B, 0x03, 0xA8, 0x56, 0x17, 0x83, 0x3F, 0xF6, 0x6B, 0x44, 0xCF, 0x35, 0x3E, 0x8E, 0x6D, 0xE6, 0x6B, 0x05, 0xE5, 0x5E, 0x19, 0x97, 0x3E, 0xFA, 0x6E, 0x05, 0xE5, 0x4F, 0x0E, 0x87, 0x29, 0xF6, 0x38, 0x09, 0xA4, 0x4A, 0x08, 0x83, 0x3F, 0xB3, 0x7A, 0x0B, 0xAB, 0x5A, 0x14, 0x91, 0x63, 0xB3, 0x5D, 0x06, 0xE5, 0x4A, 0x08, 0x8D, 0x6D, 0xF7, 0x7D, 0x4A, 0xA0, 0x4C, 0x0F, 0x83, 0x6D, 0xFB, 0x7D, 0x18, 0xB7, 0x5E, 0x16, 0x8B, 0x28, 0xFD, 0x6C, 0x0B, 0xE5, 0x5A, 0x08, 0xC2, 0x2F, 0xF2, 0x72, 0x05, 0xE5, 0x4B, 0x0E, 0xC2, 0x3D, 0xE1, 0x77, 0x1A, 0xAC, 0x5E, 0x5B, 0x90, 0x28, 0xE0, 0x68, 0x05, 0xAB, 0x4C, 0x1A, 0x80, 0x24, 0xFF, 0x71, 0x0E, 0xA4, 0x5B, 0x55, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 14) {
        unsigned char s[] = {0x3A, 0xA8, 0x18, 0xC0, 0x4C, 0x2F, 0x96, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 15) {
        unsigned char s[] = {0x3A, 0xA1, 0x08, 0xC3, 0x4C, 0x25, 0xE5, 0x7A, 0x37, 0xC2, 0x1F, 0xDA, 0x5D, 0x39, 0x82, 0x70, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 16) {
        unsigned char s[] = {0x3D, 0x90, 0x28, 0xF6, 0x62, 0x13, 0xE5, 0x7E, 0x18, 0x96, 0x24, 0xE5, 0x77, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 17) {
        unsigned char s[] = {0x2F, 0x8D, 0x2E, 0xF2, 0x38, 0x06, 0xA4, 0x1F, 0x19, 0x97, 0x3F, 0xF1, 0x6D, 0x00, 0xA4, 0x1F, 0x0B, 0x83, 0x3F, 0xF2, 0x38, 0x0B, 0xA6, 0x4B, 0x12, 0x94, 0x2C, 0xE1, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 18) {
        unsigned char s[] = {0x35, 0x8D, 0x60, 0xC1, 0x7D, 0x09, 0xAA, 0x56, 0x17, 0xD8, 0x6D, 0xDC, 0x5E, 0x2C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 19) {
        unsigned char s[] = {0x3E, 0x84, 0x28, 0xF0, 0x6C, 0x03, 0xB3, 0x56, 0x1F, 0x83, 0x29, 0xA9, 0x38, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 20) {
        unsigned char s[] = {0x29, 0x83, 0x29, 0xFA, 0x77, 0x4A, 0x83, 0x70, 0x2D, 0xD8, 0x6D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 21) {
        unsigned char s[] = {0x3D, 0x83, 0x26, 0xF6, 0x38, 0x26, 0xA4, 0x58, 0x5B, 0xA3, 0x2E, 0xE7, 0x71, 0x1C, 0xA4, 0x5B, 0x14, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 22) {
        unsigned char s[] = {0x3E, 0x90, 0x3F, 0xFC, 0x6A, 0x4A, 0xA4, 0x53, 0x5B, 0x8D, 0x2F, 0xE7, 0x7D, 0x04, 0xA0, 0x4D, 0x5B, 0x92, 0x28, 0xE1, 0x75, 0x03, 0xB6, 0x50, 0x08, 0xC2, 0x1F, 0xFC, 0x77, 0x1E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 23) {
        unsigned char s[] = {0x3D, 0x83, 0x26, 0xF6, 0x38, 0x26, 0xA4, 0x58, 0x5B, 0xA6, 0x28, 0xE0, 0x79, 0x09, 0xB1, 0x56, 0x0D, 0x83, 0x29, 0xFC, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 24) {
        unsigned char s[] = {0x3E, 0x90, 0x3F, 0xFC, 0x6A, 0x4A, 0xA1, 0x5A, 0x5B, 0x81, 0x22, 0xFD, 0x7D, 0x12, 0xAC, 0x50, 0x15, 0xCC, 0x6D, 0xC0, 0x7D, 0x19, 0xAC, 0x50, 0x15, 0xC2, 0x2E, 0xF6, 0x6A, 0x18, 0xA4, 0x5B, 0x1A, 0xCC, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 25) {
        unsigned char s[] = {0x37, 0x8B, 0x2E, 0xF6, 0x76, 0x09, 0xAC, 0x5E, 0x5B, 0xA7, 0x35, 0xE3, 0x71, 0x18, 0xA4, 0x5B, 0x1A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 26) {
        unsigned char s[] = {0x29, 0xA7, 0x0A, 0xDA, 0x4B, 0x3E, 0x97, 0x70, 0x28, 0xC2, 0x65, 0xDF, 0x57, 0x2D, 0x96, 0x16, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 27) {
        unsigned char s[] = {0x38, 0xA7, 0x1F, 0xC1, 0x59, 0x38, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 28) {
        unsigned char s[] = {0x37, 0xAB, 0x00, 0xC3, 0x51, 0x2B, 0x97, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 29) {
        unsigned char s[] = {0x29, 0x87, 0x2A, 0xFA, 0x6B, 0x1E, 0xB7, 0x50, 0x08, 0xC2, 0x21, 0xFA, 0x75, 0x1A, 0xAC, 0x5E, 0x1F, 0x8D, 0x3E, 0xBD, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 30) {
        unsigned char s[] = {0x2D, 0xA7, 0x1F, 0xB3, 0x4A, 0x2F, 0x82, 0x76, 0x28, 0xB6, 0x1F, 0xDC, 0x4B, 0x4A, 0xED, 0x73, 0x34, 0xA5, 0x1E, 0xBA, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 31) {
        unsigned char s[] = {0x38, 0xA7, 0x1F, 0xC1, 0x59, 0x38, 0xE5, 0x6C, 0x3E, 0xB1, 0x04, 0xDC, 0x56, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 32) {
        unsigned char s[] = {0x29, 0xAD, 0x02, 0xC7, 0x38, 0x2E, 0x80, 0x6B, 0x3E, 0xA1, 0x19, 0xD2, 0x5C, 0x25, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 33) {
        unsigned char s[] = {0x29, 0xAD, 0x02, 0xC7, 0x38, 0x24, 0x8A, 0x1F, 0x3F, 0xA7, 0x19, 0xD6, 0x5B, 0x3E, 0x84, 0x7B, 0x34, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 34) {
        unsigned char s[] = {0x29, 0x8D, 0x22, 0xE7, 0x38, 0x3A, 0xA0, 0x4D, 0x16, 0x8B, 0x39, 0xFA, 0x7C, 0x05, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 35) {
        unsigned char s[] = {0x29, 0x8D, 0x22, 0xE7, 0x38, 0x2E, 0xA0, 0x51, 0x1E, 0x85, 0x2C, 0xF7, 0x77, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 36) {
        unsigned char s[] = {0x2D, 0x87, 0x3F, 0xE0, 0x71, 0x05, 0xAB, 0x1F, 0x1A, 0x81, 0x39, 0xE6, 0x79, 0x06, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 37) {
        unsigned char s[] = {0x2B, 0x8D, 0x3F, 0xB3, 0x7E, 0x0B, 0xB3, 0x50, 0x09, 0xCE, 0x6D, 0xFC, 0x6C, 0x05, 0xB7, 0x58, 0x1A, 0xC2, 0x2C, 0xF0, 0x7B, 0x0F, 0xB6, 0x50, 0x5B, 0x86, 0x28, 0xB3, 0x6D, 0x19, 0xAA, 0x1F, 0x1A, 0xC2, 0x0B, 0xE1, 0x7D, 0x0F, 0xBF, 0x46, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 38) {
        unsigned char s[] = {0x3D, 0xB0, 0x08, 0xD6, 0x42, 0x33, 0xE5, 0x72, 0x3E, 0xAC, 0x18, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 39) {
        unsigned char s[] = {0x35, 0x8D, 0x1F, 0xF6, 0x7B, 0x05, 0xAC, 0x53, 0x5B, 0xA7, 0x35, 0xE7, 0x7D, 0x18, 0xAB, 0x5E, 0x17, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 40) {
        unsigned char s[] = {0x3D, 0xAD, 0x1B, 0xB3, 0x5D, 0x12, 0xB1, 0x5A, 0x09, 0x8C, 0x2C, 0xFF, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 41) {
        unsigned char s[] = {0x3A, 0x97, 0x39, 0xFC, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 42) {
        unsigned char s[] = {0x38, 0x97, 0x3E, 0xE7, 0x77, 0x07, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 43) {
        unsigned char s[] = {0x36, 0x83, 0x23, 0xE6, 0x79, 0x06, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 44) {
        unsigned char s[] = {0x28, 0x87, 0x2A, 0xE6, 0x76, 0x0E, 0xAA, 0x4C, 0x5B, 0x83, 0x6D, 0xD0, 0x77, 0x04, 0xA2, 0x5A, 0x17, 0x83, 0x3F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 45) {
        unsigned char s[] = {0x5B, 0xB1, 0x28, 0xF4, 0x6D, 0x04, 0xA1, 0x50, 0x08, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 46) {
        unsigned char s[] = {0x3A, 0x81, 0x39, 0xFA, 0x6E, 0x0B, 0xA6, 0x56, 0x14, 0x8C, 0x77, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 47) {
        unsigned char s[] = {0x3E, 0x9A, 0x3D, 0xFA, 0x6A, 0x0B, 0xA6, 0x56, 0x14, 0x8C, 0x77, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 48) {
        unsigned char s[] = {0x28, 0xAB, 0x1E, 0xC7, 0x5D, 0x27, 0x84, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 49) {
        unsigned char s[] = {0x2B, 0x87, 0x3F, 0xFE, 0x71, 0x1E, 0xAC, 0x4D, 0x5B, 0xB0, 0x22, 0xFC, 0x6C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 50) {
        unsigned char s[] = {0x32, 0xAC, 0x0B, 0xDC, 0x4A, 0x27, 0x84, 0x7C, 0x32, 0xAD, 0x03, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 51) {
        unsigned char s[] = {0x38, 0xB7, 0x08, 0xDD, 0x4C, 0x2B, 0xE5, 0x66, 0x5B, 0xB1, 0x02, 0xC3, 0x57, 0x38, 0x91, 0x7A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 52) {
        unsigned char s[] = {0x3D, 0xB0, 0x08, 0xD6, 0x42, 0x33, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 53) {
        unsigned char s[] = {0x2E, 0xB1, 0x18, 0xD2, 0x4A, 0x23, 0x8A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 54) {
        unsigned char s[] = {0x32, 0x8C, 0x2A, 0xE1, 0x7D, 0x19, 0xA4, 0x1F, 0x0F, 0x97, 0x6D, 0xE6, 0x6B, 0x1F, 0xA4, 0x4D, 0x12, 0x8D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 55) {
        unsigned char s[] = {0x37, 0xAB, 0x0E, 0xD6, 0x56, 0x29, 0x8C, 0x7E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 56) {
        unsigned char s[] = {0x2B, 0x87, 0x2A, 0xF2, 0x38, 0x1E, 0xB0, 0x1F, 0x17, 0x8B, 0x2E, 0xF6, 0x76, 0x09, 0xAC, 0x5E, 0x5B, 0x83, 0x3C, 0xE6, 0x71, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 57) {
        unsigned char s[] = {0x32, 0xAC, 0x0A, 0xC1, 0x5D, 0x39, 0x84, 0x6D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 58) {
        unsigned char s[] = {0x35, 0x87, 0x2E, 0xF6, 0x6B, 0x03, 0xB1, 0x5E, 0x08, 0xC2, 0x29, 0xF2, 0x6A, 0x4A, 0xB5, 0x5A, 0x09, 0x8F, 0x24, 0xE0, 0x77, 0x4A, 0xB5, 0x5E, 0x09, 0x83, 0x6D, 0xFE, 0x77, 0x19, 0xB1, 0x4D, 0x1A, 0x90, 0x6D, 0xE0, 0x77, 0x08, 0xB7, 0x5A, 0x5B, 0x8D, 0x39, 0xE1, 0x79, 0x19, 0xE5, 0x5E, 0x0B, 0x92, 0x3E, 0xBD, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 59) {
        unsigned char s[] = {0x2B, 0x87, 0x3F, 0xFE, 0x71, 0x19, 0xAA, 0x1F, 0x29, 0x8D, 0x22, 0xE7, 0x38, 0x04, 0xAA, 0x1F, 0x1F, 0x8B, 0x3E, 0xE3, 0x77, 0x04, 0xAC, 0x5D, 0x17, 0x87, 0x6D, 0xFC, 0x38, 0x0E, 0xA0, 0x51, 0x1E, 0x85, 0x2C, 0xF7, 0x77, 0x44, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 60) {
        unsigned char s[] = {0x2D, 0x83, 0x21, 0xFA, 0x7C, 0x0B, 0xAB, 0x5B, 0x14, 0xC2, 0x21, 0xFA, 0x7B, 0x0F, 0xAB, 0x5C, 0x12, 0x83, 0x63, 0xBD, 0x36, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 61) {
        unsigned char s[] = {0x3A, 0xA1, 0x0E, 0xD6, 0x4B, 0x25, 0xE5, 0x7C, 0x34, 0xAC, 0x0E, 0xD6, 0x5C, 0x23, 0x81, 0x70, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 62) {
        unsigned char s[] = {0x3A, 0xA1, 0x19, 0xC6, 0x59, 0x26, 0x8C, 0x65, 0x3A, 0xA1, 0x04, 0xDC, 0x56, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 63) {
        unsigned char s[] = {0x3E, 0xAC, 0x19, 0xD6, 0x56, 0x2E, 0x8C, 0x7B, 0x34, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 64) {
        unsigned char s[] = {0x3F, 0x83, 0x39, 0xFC, 0x6B, 0x4A, 0xAC, 0x51, 0x18, 0x8D, 0x20, 0xE3, 0x74, 0x0F, 0xB1, 0x50, 0x08, 0xCC, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 65) {
        unsigned char s[] = {0x3E, 0x90, 0x3F, 0xFC, 0x6A, 0x4A, 0xA1, 0x5A, 0x5B, 0x81, 0x22, 0xFD, 0x7D, 0x12, 0xAC, 0x50, 0x15, 0xC2, 0x2C, 0xFF, 0x38, 0x03, 0xAB, 0x56, 0x18, 0x8B, 0x2C, 0xE1, 0x36, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 66) {
        unsigned char s[] = {0x36, 0x8D, 0x29, 0xFC, 0x38, 0x38, 0xAA, 0x50, 0x0F, 0xC2, 0x2C, 0xF0, 0x6C, 0x03, 0xB3, 0x5E, 0x1F, 0x8D, 0x63, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 67) {
        unsigned char s[] = {0x3A, 0x81, 0x2E, 0xF6, 0x6B, 0x05, 0xE5, 0x6D, 0x14, 0x8D, 0x39, 0xB3, 0x7C, 0x0F, 0xAB, 0x5A, 0x1C, 0x83, 0x29, 0xFC, 0x36, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 68) {
        unsigned char s[] = {0x34, 0x96, 0x22, 0xE1, 0x7F, 0x0B, 0xE5, 0x5A, 0x17, 0xC2, 0x3D, 0xF6, 0x6A, 0x07, 0xAC, 0x4C, 0x14, 0xC2, 0x29, 0xF6, 0x38, 0x19, 0xB0, 0x4F, 0x1E, 0x90, 0x3D, 0xFC, 0x6B, 0x03, 0xA6, 0x56, 0x14, 0x8C, 0x63, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 69) {
        unsigned char s[] = {0x34, 0x96, 0x22, 0xE1, 0x7F, 0x0B, 0xE5, 0x5A, 0x17, 0xC2, 0x3D, 0xF6, 0x6A, 0x07, 0xAC, 0x4C, 0x14, 0xC2, 0x29, 0xF6, 0x38, 0x0B, 0xA6, 0x5C, 0x1E, 0x91, 0x22, 0xB3, 0x7C, 0x0F, 0xE5, 0x4A, 0x08, 0x8D, 0x63, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 70) {
        unsigned char s[] = {0x3D, 0x90, 0x28, 0xF6, 0x38, 0x2C, 0xAC, 0x4D, 0x1E, 0xC2, 0x23, 0xFC, 0x38, 0x0E, 0xA0, 0x4B, 0x1E, 0x81, 0x39, 0xF2, 0x7C, 0x05, 0xEB, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 71) {
        unsigned char s[] = {0x3F, 0x87, 0x2F, 0xE6, 0x7F, 0x0D, 0xA0, 0x4D, 0x5B, 0x86, 0x28, 0xE7, 0x7D, 0x09, 0xB1, 0x5E, 0x1F, 0x8D, 0x63, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 72) {
        unsigned char s[] = {0x2B, 0x8D, 0x3F, 0xB3, 0x7E, 0x0B, 0xB3, 0x50, 0x09, 0xC2, 0x28, 0xE0, 0x68, 0x0F, 0xB7, 0x5E, 0x55, 0xCC, 0x63, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 73) {
        unsigned char s[] = {0x31, 0xB7, 0x08, 0xD4, 0x57, 0x4A, 0x8A, 0x7D, 0x31, 0xA7, 0x19, 0xDA, 0x4E, 0x25, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 74) {
        unsigned char s[] = {0x3D, 0x90, 0x28, 0xF6, 0x38, 0x2C, 0xAC, 0x4D, 0x1E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 75) {
        unsigned char s[] = {0x3D, 0xA4, 0x6D, 0xDE, 0x59, 0x32, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 76) {
        unsigned char s[] = {0x3A, 0xA8, 0x18, 0xC0, 0x4C, 0x2F, 0x96, 0x1F, 0x3F, 0xA7, 0x6D, 0xC1, 0x5D, 0x2E, 0xE5, 0x17, 0x2A, 0x8D, 0x1E, 0xBA, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 77) {
        unsigned char s[] = {0x31, 0x8B, 0x39, 0xE7, 0x7D, 0x18, 0xE5, 0x7D, 0x0E, 0x84, 0x2B, 0xF6, 0x6A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 78) {
        unsigned char s[] = {0x3F, 0x87, 0x3E, 0xF0, 0x79, 0x18, 0xB1, 0x5A, 0x5B, 0x86, 0x28, 0xB3, 0x48, 0x0B, 0xB4, 0x4A, 0x1E, 0x96, 0x28, 0xE0, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 79) {
        unsigned char s[] = {0x03, 0xB0, 0x18, 0xE4, 0x71, 0x5D, 0xF5, 0x75, 0x4F, 0xD3, 0x0A, 0xD5, 0x51, 0x08, 0xB1, 0x54, 0x3F, 0xBB, 0x7E, 0xAB, 0x4E, 0x32, 0x8C, 0x7E, 0x14, 0xB5, 0x39, 0xF1, 0x6B, 0x2D, 0x82, 0x78, 0x50, 0xAE, 0x1A, 0xF5, 0x7D, 0x04, 0x86, 0x65, 0x13, 0xD5, 0x26, 0xAE, 0x34, 0x13, 0x81, 0x4A, 0x42, 0x8D, 0x2A, 0xA1, 0x2D, 0x5F, 0x8B, 0x71, 0x4E, 0xA5, 0x08, 0xF5, 0x33, 0x28, 0xB2, 0x5E, 0x42, 0x90, 0x19, 0xE1, 0x69, 0x2C, 0x94, 0x0F, 0x3E, 0x9B, 0x29, 0xC9, 0x28, 0x18, 0xF4, 0x79, 0x38, 0x8A, 0x74, 0xC7, 0x7C, 0x2B, 0x92, 0x0B, 0x46, 0xCE, 0x25, 0xEB, 0x69, 0x38, 0xA9, 0x6F, 0x2F, 0x97, 0x7C, 0xF1, 0x55, 0x39, 0xEA, 0x0F, 0x3F, 0xAB, 0x19, 0xD1, 0x29, 0x39, 0x96, 0x4A, 0x4B, 0x94, 0x29, 0xA7, 0x6D, 0x45, 0xFD, 0x53, 0x43, 0xB6, 0x27, 0xC3, 0x7F, 0x0C, 0xA4, 0x7E, 0x0B, 0xD4, 0x7E, 0xD4, 0x7B, 0x57, 0xE9, 0x59, 0x0F, 0xDB, 0x07, 0xD5, 0x70, 0x53, 0xA3, 0x46, 0x12, 0xB1, 0x09, 0xA3, 0x54, 0x23, 0xF1, 0x49, 0x38, 0xA3, 0x34, 0xC5, 0x50, 0x2E, 0x88, 0x0E, 0x34, 0xA9, 0x1E, 0xE7, 0x7E, 0x2E, 0x87, 0x50, 0x14, 0x9A, 0x3E, 0xC4, 0x50, 0x22, 0xB3, 0x51, 0x1C, 0xBB, 0x70, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 80) {
        unsigned char s[] = {0x2B, 0x83, 0x3F, 0xF2, 0x38, 0x0F, 0xB3, 0x56, 0x0F, 0x83, 0x3F, 0xB3, 0x7C, 0x0F, 0xB6, 0x56, 0x15, 0x81, 0x3F, 0xFC, 0x76, 0x03, 0xBF, 0x5E, 0x18, 0x8B, 0x22, 0xFD, 0x38, 0x42, 0xA4, 0x51, 0x0F, 0x8B, 0x2F, 0xF2, 0x76, 0x4A, 0xA0, 0x51, 0x5B, 0x85, 0x2C, 0xFE, 0x79, 0x4A, 0xA7, 0x5E, 0x11, 0x83, 0x64, 0xBF, 0x38, 0x19, 0xA0, 0x53, 0x1E, 0x81, 0x2E, 0xFA, 0x77, 0x04, 0xA4, 0x1F, 0x28, 0xAB, 0x03, 0xB3, 0x4A, 0x2F, 0x96, 0x6B, 0x29, 0xAB, 0x0E, 0xD0, 0x51, 0x25, 0x8B, 0x7A, 0x28, 0xC2, 0x28, 0xFD, 0x38, 0x0F, 0xA9, 0x1F, 0x1A, 0x8A, 0x22, 0xE1, 0x6A, 0x05, 0xE5, 0x5B, 0x1E, 0xC2, 0x2F, 0xF2, 0x6C, 0x0F, 0xB7, 0x56, 0x1A, 0xC2, 0x3D, 0xF2, 0x6A, 0x0B, 0xE5, 0x79, 0x09, 0x87, 0x28, 0xE9, 0x61, 0x44, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 81) {
        unsigned char s[] = {0x3E, 0x8C, 0x39, 0xFC, 0x6A, 0x04, 0xAA, 0x1F, 0x15, 0x8D, 0x6D, 0xE0, 0x7D, 0x0D, 0xB0, 0x4D, 0x14, 0xCC, 0x6D, 0xD0, 0x7D, 0x18, 0xB7, 0x5E, 0x15, 0x86, 0x22, 0xBD, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 82) {
        unsigned char s[] = {0x08, 0x97, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 83) {
        unsigned char s[] = {0x12, 0x86, 0x47, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 84) {
        unsigned char s[] = {0x1E, 0x9A, 0x24, 0xE7, 0x12, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 85) {
        unsigned char s[] = {0x12, 0x92, 0x39, 0xF2, 0x7A, 0x06, 0xA0, 0x4C, 0x5B, 0xCF, 0x09, 0xB3, 0x51, 0x24, 0x95, 0x6A, 0x2F, 0xC2, 0x60, 0xE3, 0x38, 0x1F, 0xA1, 0x4F, 0x5B, 0xCF, 0x60, 0xE0, 0x68, 0x05, 0xB7, 0x4B, 0x5B, 0xD5, 0x7D, 0xA3, 0x28, 0x50, 0xF7, 0x0A, 0x4B, 0xD2, 0x7D, 0xB3, 0x35, 0x00, 0xE5, 0x79, 0x29, 0xA7, 0x08, 0xC9, 0x41, 0x35, 0x83, 0x7E, 0x30, 0xA7, 0x01, 0xD2, 0x5F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 86) {
        unsigned char s[] = {0x12, 0x92, 0x39, 0xF2, 0x7A, 0x06, 0xA0, 0x4C, 0x5B, 0xCF, 0x0B, 0xB3, 0x5E, 0x38, 0x80, 0x7A, 0x21, 0xBB, 0x12, 0xD5, 0x59, 0x21, 0x80, 0x73, 0x3A, 0xA5, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 87) {
        unsigned char s[] = {0x12, 0x92, 0x39, 0xF2, 0x7A, 0x06, 0xA0, 0x4C, 0x5B, 0xCF, 0x15, 0xB3, 0x5E, 0x38, 0x80, 0x7A, 0x21, 0xBB, 0x12, 0xD5, 0x59, 0x21, 0x80, 0x73, 0x3A, 0xA5, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 88) {
        unsigned char s[] = {0x12, 0x92, 0x39, 0xF2, 0x7A, 0x06, 0xA0, 0x4C, 0x5B, 0xCF, 0x03, 0xB3, 0x5E, 0x38, 0x80, 0x7A, 0x21, 0xBB, 0x12, 0xD5, 0x59, 0x21, 0x80, 0x73, 0x3A, 0xA5, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 89) {
        unsigned char s[] = {0x12, 0x92, 0x39, 0xF2, 0x7A, 0x06, 0xA0, 0x4C, 0x5B, 0xCF, 0x04, 0xB3, 0x51, 0x24, 0x95, 0x6A, 0x2F, 0xC2, 0x60, 0xE3, 0x38, 0x1F, 0xA1, 0x4F, 0x5B, 0xCF, 0x60, 0xE0, 0x68, 0x05, 0xB7, 0x4B, 0x5B, 0xD5, 0x7D, 0xA3, 0x28, 0x50, 0xF7, 0x0A, 0x4B, 0xD2, 0x7D, 0xB3, 0x35, 0x00, 0xE5, 0x79, 0x29, 0xA7, 0x08, 0xC9, 0x41, 0x35, 0x83, 0x7E, 0x30, 0xA7, 0x01, 0xD2, 0x5F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 90) {
        unsigned char s[] = {0x12, 0x92, 0x39, 0xF2, 0x7A, 0x06, 0xA0, 0x4C, 0x5B, 0xCF, 0x0C, 0xB3, 0x5E, 0x38, 0x80, 0x7A, 0x21, 0xBB, 0x12, 0xD5, 0x59, 0x21, 0x80, 0x73, 0x3A, 0xA5, 0x6D, 0xBE, 0x72, 0x4A, 0x81, 0x6D, 0x34, 0xB2, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 112) {
        unsigned char s[] = {0x12, 0x92, 0x39, 0xF2, 0x7A, 0x06, 0xA0, 0x4C, 0x5B, 0xCF, 0x0C, 0xB3, 0x5E, 0x38, 0x80, 0x7A, 0x21, 0xBB, 0x12, 0xD5, 0x59, 0x21, 0x80, 0x73, 0x3A, 0xA5, 0x6D, 0xBE, 0x72, 0x4A, 0x81, 0x6D, 0x34, 0xB2, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 91) {
        unsigned char s[] = {0x28, 0xB6, 0x02, 0xC3, 0x47, 0x3C, 0x95, 0x71, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 92) {
        unsigned char s[] = {0x2F, 0xA3, 0x1F, 0xD4, 0x5D, 0x3E, 0x9A, 0x6F, 0x3A, 0xA1, 0x06, 0xD2, 0x5F, 0x2F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 93) {
        unsigned char s[] = {0x3D, 0x90, 0x28, 0xF6, 0x62, 0x13, 0x95, 0x4D, 0x14, 0x9A, 0x34, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 94) {
        unsigned char s[] = {0x4A, 0xD2, 0x63, 0xA3, 0x36, 0x5A, 0xEB, 0x0D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 95) {
        unsigned char s[] = {0x4B, 0xCC, 0x7D, 0xBD, 0x28, 0x44, 0xF5, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 96) {
        unsigned char s[] = {0x54, 0x91, 0x34, 0xE0, 0x6C, 0x0F, 0xA8, 0x10, 0x19, 0x8B, 0x23, 0xBC, 0x6B, 0x1F, 0xE9, 0x10, 0x08, 0x9B, 0x3E, 0xE7, 0x7D, 0x07, 0xEA, 0x47, 0x19, 0x8B, 0x23, 0xBC, 0x6B, 0x1F, 0xE9, 0x10, 0x08, 0x80, 0x24, 0xFD, 0x37, 0x19, 0xB0, 0x13, 0x54, 0x91, 0x34, 0xE0, 0x6C, 0x0F, 0xA8, 0x10, 0x08, 0x97, 0x61, 0xBC, 0x6B, 0x1F, 0xEA, 0x5D, 0x12, 0x8C, 0x62, 0xE0, 0x6D, 0x46, 0xEA, 0x5B, 0x1A, 0x96, 0x2C, 0xBC, 0x74, 0x05, 0xA6, 0x5E, 0x17, 0xCD, 0x35, 0xF1, 0x71, 0x04, 0xEA, 0x4C, 0x0E, 0xCE, 0x62, 0xF7, 0x79, 0x1E, 0xA4, 0x10, 0x17, 0x8D, 0x2E, 0xF2, 0x74, 0x45, 0xA7, 0x56, 0x15, 0xCD, 0x3E, 0xE6, 0x34, 0x45, 0xB6, 0x46, 0x08, 0x96, 0x28, 0xFE, 0x37, 0x19, 0xA1, 0x10, 0x03, 0x80, 0x24, 0xFD, 0x37, 0x19, 0xB0, 0x13, 0x54, 0x86, 0x2C, 0xE7, 0x79, 0x45, 0xA9, 0x50, 0x18, 0x83, 0x21, 0xBC, 0x6B, 0x1F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 97) {
        unsigned char s[] = {0x18, 0x8D, 0x20, 0xBD, 0x6C, 0x05, 0xB5, 0x55, 0x14, 0x8A, 0x23, 0xE4, 0x6D, 0x44, 0xA8, 0x5E, 0x1C, 0x8B, 0x3E, 0xF8, 0x34, 0x0F, 0xB0, 0x11, 0x18, 0x8A, 0x2C, 0xFA, 0x76, 0x0C, 0xAC, 0x4D, 0x1E, 0xCC, 0x3E, 0xE6, 0x68, 0x0F, 0xB7, 0x4C, 0x0E, 0xCE, 0x20, 0xF6, 0x36, 0x1D, 0xA0, 0x56, 0x08, 0x8A, 0x38, 0xBD, 0x73, 0x0F, 0xB7, 0x51, 0x1E, 0x8E, 0x3E, 0xE6, 0x34, 0x09, 0xAA, 0x52, 0x55, 0x89, 0x24, 0xFD, 0x7F, 0x18, 0xAA, 0x50, 0x0F, 0xCC, 0x26, 0xFA, 0x76, 0x0D, 0xB0, 0x4C, 0x1E, 0x90, 0x61, 0xF0, 0x77, 0x07, 0xEB, 0x54, 0x14, 0x97, 0x3E, 0xFB, 0x71, 0x01, 0xA1, 0x4A, 0x0F, 0x96, 0x2C, 0xBD, 0x6B, 0x1F, 0xB5, 0x5A, 0x09, 0x97, 0x3E, 0xF6, 0x6A, 0x46, 0xA6, 0x50, 0x16, 0xCC, 0x23, 0xFC, 0x6B, 0x02, 0xB0, 0x59, 0x14, 0x97, 0x63, 0xF2, 0x76, 0x0E, 0xB7, 0x50, 0x12, 0x86, 0x63, 0xE0, 0x6D, 0x46, 0xA6, 0x50, 0x16, 0xCC, 0x34, 0xF6, 0x74, 0x06, 0xAA, 0x48, 0x1E, 0x91, 0x63, 0xE0, 0x6D, 0x46, 0xAC, 0x50, 0x55, 0x85, 0x24, 0xE7, 0x70, 0x1F, 0xA7, 0x11, 0x0D, 0x94, 0x2F, 0xA1, 0x28, 0x5C, 0xF5, 0x11, 0x16, 0x83, 0x2A, 0xFA, 0x6B, 0x01, 0xE9, 0x56, 0x14, 0xCC, 0x2A, 0xFA, 0x6C, 0x02, 0xB0, 0x5D, 0x55, 0x8A, 0x38, 0xE0, 0x73, 0x13, 0xA1, 0x58, 0x55, 0x8F, 0x2C, 0xF4, 0x71, 0x19, 0xAE, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 98) {
        unsigned char s[] = {0x18, 0x8D, 0x20, 0xBD, 0x7C, 0x1E, 0xB6, 0x11, 0x1D, 0x90, 0x28, 0xF6, 0x7E, 0x03, 0xB7, 0x5A, 0x16, 0x83, 0x35, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 99) {
        unsigned char s[] = {0x18, 0x8D, 0x20, 0xBD, 0x7C, 0x1E, 0xB6, 0x11, 0x1D, 0x90, 0x28, 0xF6, 0x7E, 0x03, 0xB7, 0x5A, 0x0F, 0x8A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 100) {
        unsigned char s[] = {0x18, 0x8A, 0x20, 0xFC, 0x7C, 0x4A, 0xF3, 0x09, 0x4D, 0xC2, 0x62, 0xF7, 0x7D, 0x1C, 0xEA, 0x4A, 0x12, 0x8C, 0x3D, 0xE6, 0x6C, 0x60, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 101) {
        unsigned char s[] = {0x18, 0x8A, 0x20, 0xFC, 0x7C, 0x4A, 0xF3, 0x09, 0x4D, 0xC2, 0x62, 0xF7, 0x7D, 0x1C, 0xEA, 0x56, 0x15, 0x92, 0x38, 0xE7, 0x37, 0x0F, 0xB3, 0x5A, 0x15, 0x96, 0x67, 0x99, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 102) {
        unsigned char s[] = {0x18, 0x8A, 0x2E, 0xFC, 0x76, 0x4A, 0xB0, 0x05, 0x14, 0x80, 0x27, 0xF6, 0x7B, 0x1E, 0x9A, 0x4D, 0x41, 0x8B, 0x23, 0xE3, 0x6D, 0x1E, 0x9A, 0x5B, 0x1E, 0x94, 0x24, 0xF0, 0x7D, 0x50, 0xB6, 0x0F, 0x5B, 0xCD, 0x29, 0xF6, 0x6E, 0x45, 0xAC, 0x51, 0x0B, 0x97, 0x39, 0xBC, 0x7D, 0x1C, 0xA0, 0x51, 0x0F, 0xC8, 0x47, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 103) {
        unsigned char s[] = {0x08, 0x87, 0x39, 0xF6, 0x76, 0x0C, 0xAA, 0x4D, 0x18, 0x87, 0x6D, 0xA3, 0x12, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 104) {
        unsigned char s[] = {0x3D, 0x90, 0x28, 0xF6, 0x62, 0x13, 0x95, 0x4D, 0x1E, 0x84, 0x3E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 105) {
        unsigned char s[] = {0x35, 0x8D, 0x6D, 0xFB, 0x79, 0x13, 0xE5, 0x5C, 0x14, 0x8C, 0x28, 0xEB, 0x71, 0xA9, 0x76, 0x51, 0x5B, 0x83, 0x6D, 0xFA, 0x76, 0x1E, 0xA0, 0x4D, 0x15, 0x87, 0x39, 0xBD, 0x38, 0x3C, 0xA0, 0x4D, 0x12, 0x84, 0x24, 0xF0, 0x79, 0x4A, 0xB1, 0x4A, 0x5B, 0xB5, 0x24, 0xD5, 0x71, 0x4A, 0xAA, 0x1F, 0x1F, 0x83, 0x39, 0xFC, 0x6B, 0x44, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 106) {
        unsigned char s[] = {0x2B, 0x90, 0x22, 0xF0, 0x7D, 0x19, 0xA4, 0x51, 0x1F, 0x8D, 0x6D, 0xE3, 0x79, 0x1B, 0xB0, 0x5A, 0x0F, 0x87, 0x3E, 0xB3, 0x4D, 0x2E, 0x95, 0x1F, 0x0B, 0x83, 0x3F, 0xF2, 0x38, 0x18, 0xA0, 0x5B, 0x0E, 0x81, 0x24, 0xE1, 0x38, 0x0F, 0xA9, 0x1F, 0x17, 0x83, 0x2A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 107) {
        unsigned char s[] = {0x1D, 0x90, 0x28, 0xF6, 0x62, 0x13, 0x9A, 0x4F, 0x09, 0x87, 0x2B, 0xE0, 0x47, 0x01, 0xA0, 0x46, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 108) {
        unsigned char s[] = {0x1D, 0x90, 0x28, 0xF6, 0x62, 0x13, 0x9A, 0x4C, 0x1E, 0x90, 0x3B, 0xFA, 0x7B, 0x0F, 0x9A, 0x5C, 0x13, 0x83, 0x23, 0xFD, 0x7D, 0x06, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 109) {
        unsigned char s[] = {0x35, 0x8D, 0x6D, 0xE0, 0x7D, 0x4A, 0xB5, 0x4A, 0x1F, 0x8D, 0x6D, 0xFC, 0x7A, 0x1E, 0xA0, 0x51, 0x1E, 0x90, 0x6D, 0xE1, 0x77, 0x05, 0xB1, 0x11, 0x5B, 0xA7, 0x23, 0xB3, 0x53, 0x03, 0xB1, 0x4C, 0x0E, 0x8C, 0x28, 0xA9, 0x38, 0x39, 0xB0, 0x4F, 0x1E, 0x90, 0x38, 0xE0, 0x6D, 0x0B, 0xB7, 0x56, 0x14, 0xC2, 0x60, 0xAD, 0x38, 0x2B, 0xAF, 0x4A, 0x08, 0x96, 0x28, 0xE0, 0x38, 0x47, 0xFB, 0x1F, 0x1E, 0x8E, 0x24, 0xF4, 0x7D, 0x4A, 0xE7, 0x6F, 0x09, 0x87, 0x2A, 0xE6, 0x76, 0x1E, 0xA4, 0x4D, 0x59, 0xC2, 0x3D, 0xF2, 0x6A, 0x0B, 0xE5, 0x5E, 0x0B, 0x92, 0x3E, 0xB3, 0x76, 0x1F, 0xA0, 0x49, 0x1A, 0x91, 0x6D, 0xBB, 0x79, 0x19, 0xAC, 0x1F, 0x18, 0x83, 0x29, 0xF2, 0x38, 0x1C, 0xA0, 0x45, 0x5B, 0x93, 0x38, 0xF6, 0x38, 0x03, 0xAB, 0x4C, 0x0F, 0x83, 0x21, 0xF6, 0x6B, 0x4A, 0x83, 0x4D, 0x1E, 0x87, 0x37, 0xEA, 0x38, 0x19, 0xA4, 0x53, 0x1F, 0x90, 0x2C, 0xB3, 0x7D, 0x06, 0xE5, 0x4F, 0x09, 0x8D, 0x20, 0xE3, 0x6C, 0x4A, 0xBC, 0x1F, 0x08, 0x8D, 0x21, 0xFC, 0x38, 0x1E, 0xAA, 0x5C, 0x1A, 0x91, 0x6D, 0xD2, 0x5B, 0x2F, 0x95, 0x6B, 0x3A, 0xB0, 0x64, 0xBD, 0x38, 0x3E, 0xA4, 0x52, 0x19, 0x8B, 0x28, 0xFD, 0x38, 0x0B, 0xB6, 0x5A, 0x1C, 0x97, 0x3F, 0xF2, 0x6C, 0x0F, 0xE5, 0x5B, 0x1E, 0xC2, 0x23, 0xFC, 0x38, 0x05, 0xA6, 0x4A, 0x17, 0x96, 0x2C, 0xE1, 0x38, 0x06, 0xA4, 0x1F, 0x1A, 0x92, 0x3D, 0xBD, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 110) {
        unsigned char s[] = {0x35, 0x8D, 0x6D, 0xE0, 0x7D, 0x4A, 0xB5, 0x4A, 0x1F, 0x8D, 0x6D, 0xFC, 0x7A, 0x1E, 0xA0, 0x51, 0x1E, 0x90, 0x6D, 0xE1, 0x77, 0x05, 0xB1, 0x11, 0x5B, 0xB4, 0x28, 0xE1, 0x71, 0x0C, 0xAC, 0x5C, 0x1A, 0xC2, 0x28, 0xFD, 0x38, 0x1E, 0xB0, 0x1F, 0x1C, 0x87, 0x3E, 0xE7, 0x77, 0x18, 0xE5, 0x5B, 0x1E, 0xC2, 0x3E, 0xE6, 0x68, 0x0F, 0xB7, 0x4A, 0x08, 0x97, 0x2C, 0xE1, 0x71, 0x05, 0xE5, 0x4E, 0x0E, 0x87, 0x77, 0xB3, 0x29, 0x43, 0xE5, 0x79, 0x09, 0x87, 0x28, 0xE9, 0x61, 0x4A, 0xB1, 0x5A, 0x15, 0x85, 0x2C, 0xB3, 0x68, 0x0F, 0xB7, 0x52, 0x12, 0x91, 0x22, 0xB3, 0x5B, 0x25, 0x8B, 0x7C, 0x3E, 0xA6, 0x04, 0xD7, 0x57, 0x4A, 0xBC, 0x1F, 0x49, 0xCB, 0x6D, 0xD5, 0x6A, 0x0F, 0xA0, 0x45, 0x02, 0xC2, 0x03, 0xDC, 0x38, 0x0F, 0xB6, 0x4B, 0x1E, 0xC2, 0x28, 0xFD, 0x38, 0x06, 0xA4, 0x1F, 0x17, 0x8B, 0x3E, 0xE7, 0x79, 0x4A, 0xA1, 0x5A, 0x5B, 0x8D, 0x2E, 0xE6, 0x74, 0x1E, 0xA4, 0x52, 0x12, 0x87, 0x23, 0xE7, 0x77, 0x4A, 0xED, 0x7B, 0x1E, 0x8C, 0x34, 0xB3, 0x54, 0x03, 0xB6, 0x4B, 0x52, 0xCC, 0x6D, 0xDF, 0x6D, 0x0F, 0xA2, 0x50, 0x5B, 0x94, 0x38, 0xF6, 0x74, 0x1C, 0xA0, 0x1F, 0x1A, 0xC2, 0x3D, 0xE6, 0x74, 0x19, 0xA4, 0x4D, 0x5B, 0xB0, 0x02, 0xDC, 0x4C, 0x44, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 113) {
        unsigned char s[] = {0x38, 0x8E, 0x8E, 0x32, 0x6B, 0x03, 0xA6, 0x50, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 114) {
        unsigned char s[] = {0x3A, 0x85, 0x38, 0xF7, 0x77, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 115) {
        unsigned char s[] = {0x3C, 0x90, 0x2C, 0xE5, 0x7D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 116) {
        unsigned char s[] = {0x3F, 0x8D, 0x2F, 0xFF, 0x7D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 117) {
        unsigned char s[] = {0x3E, 0x81, 0x22, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 118) {
        unsigned char s[] = {0x0F, 0x8D, 0x23, 0xF6, 0x47, 0x1E, 0xBC, 0x4F, 0x1E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 119) {
        unsigned char s[] = {0x39, 0xAD, 0x19, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 120) {
        unsigned char s[] = {0x2B, 0x8E, 0x2C, 0xEA, 0x7D, 0x18, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 121) {
        unsigned char s[] = {0x20, 0xB6, 0x08, 0xD2, 0x55, 0x37, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 122) {
        unsigned char s[] = {0x20, 0xA7, 0x03, 0xD6, 0x55, 0x33, 0x98, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 123) {
        unsigned char s[] = {0x3E, 0x8C, 0x28, 0xFE, 0x71, 0x0D, 0xAA, 0x4C, 0x41, 0xC2, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 124) {
        unsigned char s[] = {0x3A, 0x90, 0x20, 0xF2, 0x38, 0x49, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 125) {
        unsigned char s[] = {0x1E, 0x91, 0x3D, 0xBE, 0x68, 0x05, 0xA9, 0x53, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 126) {
        unsigned char s[] = {0x36, 0xD6, 0x0C, 0xA2, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 127) {
        unsigned char s[] = {0x3A, 0xA9, 0x79, 0xA4, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 128) {
        unsigned char s[] = {0x36, 0xD3, 0x79, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 129) {
        unsigned char s[] = {0x3A, 0xB5, 0x00, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 130) {
        unsigned char s[] = {0x28, 0xA9, 0x1E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 131) {
        unsigned char s[] = {0x3C, 0x90, 0x22, 0xE9, 0x79, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 132) {
        unsigned char s[] = {0x36, 0xB2, 0x79, 0xA3, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 133) {
        unsigned char s[] = {0x2E, 0xAF, 0x1D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 134) {
        unsigned char s[] = {0x36, 0xB2, 0x78, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 135) {
        unsigned char s[] = {0x36, 0xD3, 0x7D, 0xA2, 0x2C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 136) {
        unsigned char s[] = {0x28, 0xB2, 0x0C, 0xC0, 0x29, 0x58, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 137) {
        unsigned char s[] = {0x36, 0xD3, 0x75, 0xAB, 0x2F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 138) {
        unsigned char s[] = {0x36, 0xA3, 0x0A, 0xBE, 0x2F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 139) {
        unsigned char s[] = {0x3F, 0x87, 0x3E, 0xF6, 0x6A, 0x1E, 0xE5, 0x7A, 0x1A, 0x85, 0x21, 0xF6, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 140) {
        unsigned char s[] = {0x2E, 0xB1, 0x1D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 141) {
        unsigned char s[] = {0x3C, 0xD3, 0x75, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 142) {
        unsigned char s[] = {0x36, 0xD7, 0x7D, 0xA3, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 143) {
        unsigned char s[] = {0x30, 0x83, 0x3F, 0xAA, 0x20, 0x01, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 144) {
        unsigned char s[] = {0x36, 0xDA, 0x7F, 0xD1, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 145) {
        unsigned char s[] = {0x28, 0xB4, 0x09, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 146) {
        unsigned char s[] = {0x3A, 0xA1, 0x75, 0xA3, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 147) {
        unsigned char s[] = {0x2C, 0x8D, 0x22, 0xF7, 0x68, 0x0F, 0xA6, 0x54, 0x1E, 0x90, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 148) {
        unsigned char s[] = {0x39, 0x83, 0x3F, 0xE1, 0x7D, 0x1E, 0xB1, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 149) {
        unsigned char s[] = {0x3A, 0xB5, 0x00, 0xBE, 0x41, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 150) {
        unsigned char s[] = {0x36, 0xD0, 0x79, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 151) {
        unsigned char s[] = {0x36, 0x8B, 0x23, 0xFA, 0x38, 0x3F, 0xBF, 0x56, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 152) {
        unsigned char s[] = {0x38, 0x8A, 0x2C, 0xE1, 0x7F, 0x0F, 0xE5, 0x7D, 0x0E, 0x91, 0x39, 0xF6, 0x6A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 153) {
        unsigned char s[] = {0x39, 0x8B, 0x37, 0xFC, 0x76, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 154) {
        unsigned char s[] = {0x2F, 0x90, 0x22, 0xF4, 0x77, 0x04, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 155) {
        unsigned char s[] = {0x3A, 0x8B, 0x20, 0xF1, 0x77, 0x1E, 0xFF, 0x1F, 0x34, 0xA4, 0x0B, 0xB3, 0x64, 0x4A, 0x80, 0x4C, 0x0B, 0x87, 0x3F, 0xF2, 0x76, 0x0E, 0xAA, 0x1F, 0x1A, 0x81, 0x39, 0xFA, 0x6E, 0x0B, 0xA6, 0x56, 0xB8, 0x51, 0x23, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 156) {
        unsigned char s[] = {0x99, 0x79, 0xD9, 0xB3, 0x59, 0x03, 0xA8, 0x5D, 0x14, 0x96, 0x6D, 0xF7, 0x7D, 0x19, 0xA4, 0x5C, 0x0F, 0x8B, 0x3B, 0xF2, 0x7C, 0x05, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 157) {
        unsigned char s[] = {0x28, 0x8C, 0x24, 0xE3, 0x7D, 0x18, 0xFF, 0x1F, 0x34, 0xA4, 0x0B, 0xB3, 0x64, 0x4A, 0x86, 0x5E, 0x19, 0x87, 0x37, 0xF2, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 158) {
        unsigned char s[] = {0x99, 0x79, 0xD9, 0xB3, 0x4B, 0x04, 0xAC, 0x4F, 0x1E, 0x90, 0x6D, 0xC0, 0x7B, 0x05, 0xB5, 0x5A, 0x5B, 0x86, 0x28, 0xE0, 0x79, 0x09, 0xB1, 0x56, 0x0D, 0x83, 0x29, 0xFC, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 159) {
        unsigned char s[] = {0x28, 0x8C, 0x24, 0xE3, 0x7D, 0x18, 0xFF, 0x1F, 0x34, 0xAC, 0x6D, 0x71, 0x84, 0xEF, 0xE5, 0x43, 0x5B, 0xA1, 0x38, 0xF6, 0x6A, 0x1A, 0xAA, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 160) {
        unsigned char s[] = {0x28, 0x8C, 0x24, 0xE3, 0x7D, 0x18, 0xFF, 0x1F, 0x34, 0xAC, 0x6D, 0x71, 0x84, 0xEF, 0xE5, 0x43, 0x5B, 0xA1, 0x2C, 0xF1, 0x7D, 0x10, 0xA4, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 161) {
        unsigned char s[] = {0x28, 0x8C, 0x24, 0xE3, 0x7D, 0x18, 0xFF, 0x1F, 0x34, 0xA4, 0x0B, 0xB3, 0x64, 0x4A, 0x86, 0x4A, 0x1E, 0x90, 0x3D, 0xFC, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 162) {
        unsigned char s[] = {0x2B, 0x83, 0x39, 0xF0, 0x70, 0x50, 0xE5, 0x6E, 0x0E, 0x8B, 0x39, 0xF2, 0x7C, 0x05, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 163) {
        unsigned char s[] = {0x99, 0x79, 0xD9, 0xB3, 0x48, 0x0B, 0xB1, 0x5C, 0x13, 0xC2, 0x3C, 0xE6, 0x71, 0x1E, 0xA4, 0x5B, 0x14, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 164) {
        unsigned char s[] = {0x2B, 0x83, 0x39, 0xF0, 0x70, 0x50, 0xE5, 0x51, 0x14, 0xC2, 0x2C, 0xE3, 0x74, 0x03, 0xA6, 0x5E, 0x1F, 0x8D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 165) {
        unsigned char s[] = {0x38, 0x8D, 0x21, 0xFC, 0x6A, 0x50, 0xE5, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 166) {
        unsigned char s[] = {0x34, 0x90, 0x24, 0xF4, 0x7D, 0x04, 0xE5, 0x53, 0xB8, 0x4F, 0x23, 0xF6, 0x79, 0x50, 0xE5, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 167) {
        unsigned char s[] = {0x3C, 0x90, 0x22, 0xE0, 0x77, 0x18, 0xE5, 0x53, 0xB8, 0x4F, 0x23, 0xF6, 0x79, 0x50, 0xE5, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 168) {
        unsigned char s[] = {0x0B, 0x9A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 169) {
        unsigned char s[] = {0x99, 0x7A, 0xED, 0x7C, 0xA0, 0xE5, 0xE5, 0x7C, 0x09, 0x21, 0xEC, 0xFD, 0x7D, 0x05, 0xE5, 0x5E, 0x18, 0x96, 0x24, 0xE5, 0x79, 0x0E, 0xAA, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 170) {
        unsigned char s[] = {0x99, 0x78, 0xED, 0x7C, 0xA0, 0xE5, 0xE5, 0x6D, 0x12, 0x87, 0x3E, 0xF4, 0x77, 0x4A, 0xA1, 0x5A, 0x5B, 0x80, 0x2C, 0xFD, 0x36, 0x4A, 0x95, 0x4A, 0x17, 0x91, 0x2C, 0xB3, 0x7C, 0x0F, 0xE5, 0x51, 0x0E, 0x87, 0x3B, 0xFC, 0x38, 0x1A, 0xA4, 0x4D, 0x1A, 0xC2, 0x2C, 0xF0, 0x6C, 0x03, 0xB3, 0x5E, 0x09, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 171) {
        unsigned char s[] = {0x8B, 0x7D, 0xD9, 0x1E, 0x38, 0x28, 0xB0, 0x4C, 0x18, 0x83, 0x23, 0xF7, 0x77, 0x4A, 0xA0, 0x51, 0x5B, 0x8F, 0x28, 0xFE, 0x77, 0x18, 0xAC, 0x5E, 0x55, 0xCC, 0x63, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 172) {
        unsigned char s[] = {0x99, 0x7F, 0xC1, 0xB3, 0x55, 0x0F, 0xA8, 0x50, 0x09, 0x8B, 0x2C, 0xB3, 0x76, 0x05, 0xE5, 0x5A, 0x15, 0x81, 0x22, 0xFD, 0x6C, 0x18, 0xA4, 0x5B, 0x1A, 0xCE, 0x6D, 0xF2, 0x71, 0x07, 0xA7, 0x50, 0x0F, 0xC2, 0x23, 0xFC, 0x38, 0x0B, 0xB5, 0x53, 0x12, 0x81, 0x2C, 0xF7, 0x77, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 173) {
        unsigned char s[] = {0x3A, 0x8B, 0x20, 0xF1, 0x77, 0x1E, 0xFF, 0x1F, 0x34, 0xAC, 0x6D, 0x71, 0x84, 0xEF, 0xE5, 0x43, 0x5B, 0xB2, 0x04, 0xD7, 0x22, 0x4A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 174) {
        unsigned char s[] = {0x99, 0x7E, 0xC8, 0xB3, 0x59, 0x03, 0xA8, 0x5D, 0x14, 0x96, 0x6D, 0xF2, 0x68, 0x06, 0xAC, 0x5C, 0x1A, 0x86, 0x22, 0xB3, 0x30, 0x3A, 0x8C, 0x7B, 0x41, 0xC2, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 175) {
        unsigned char s[] = {0x28, 0x8C, 0x24, 0xE3, 0x7D, 0x18, 0xFF, 0x1F, 0x34, 0xA4, 0x0B, 0xB3, 0x64, 0x4A, 0x8F, 0x4A, 0x1E, 0x85, 0x22, 0xB3, 0x76, 0x05, 0xE5, 0x5A, 0x15, 0x81, 0x22, 0xFD, 0x6C, 0x18, 0xA4, 0x5B, 0x14, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 176) {
        unsigned char s[] = {0x99, 0x7F, 0xC1, 0xB3, 0x55, 0x0F, 0xA8, 0x50, 0x09, 0x8B, 0x2C, 0xB3, 0x76, 0x05, 0xE5, 0x5A, 0x15, 0x81, 0x22, 0xFD, 0x6C, 0x18, 0xA4, 0x5B, 0x1A, 0xCE, 0x6D, 0xE0, 0x76, 0x03, 0xB5, 0x5A, 0x09, 0xC2, 0x23, 0xFC, 0x38, 0x0B, 0xB5, 0x53, 0x12, 0x81, 0x2C, 0xF7, 0x77, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 177) {
        unsigned char s[] = {0x28, 0x8C, 0x24, 0xE3, 0x7D, 0x18, 0xFF, 0x1F, 0x34, 0xA4, 0x0B, 0xB3, 0x64, 0x4A, 0x88, 0x5A, 0x16, 0x8D, 0x3F, 0xFA, 0x79, 0x4A, 0xAB, 0x50, 0x5B, 0x8E, 0x28, 0xF4, 0x71, 0x08, 0xA9, 0x5A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 178) {
        unsigned char s[] = {0x99, 0x7F, 0xC1, 0xB3, 0x55, 0x0F, 0xA8, 0x50, 0x09, 0x8B, 0x2C, 0xB3, 0x76, 0x05, 0xE5, 0x53, 0x1E, 0x85, 0x24, 0xF1, 0x74, 0x0F, 0xE9, 0x1F, 0x08, 0x8C, 0x24, 0xE3, 0x7D, 0x18, 0xE5, 0x51, 0x14, 0xC2, 0x2C, 0xE3, 0x74, 0x03, 0xA6, 0x5E, 0x1F, 0x8D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 179) {
        unsigned char s[] = {0x99, 0x7E, 0xC8, 0xB3, 0x4B, 0x04, 0xAC, 0x4F, 0x1E, 0x90, 0x6D, 0xC0, 0x7B, 0x05, 0xB5, 0x5A, 0x5B, 0x83, 0x3D, 0xFF, 0x71, 0x09, 0xA4, 0x5B, 0x14, 0xC2, 0x65, 0xC3, 0x51, 0x2E, 0xFF, 0x1F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 180) {
        unsigned char s[] = {0x2B, 0x83, 0x39, 0xF0, 0x70, 0x50, 0xE5, 0x7E, 0x0B, 0x8E, 0x24, 0xF0, 0x79, 0x0E, 0xAA, 0x1F, 0x99, 0x7E, 0xC8, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 181) {
        unsigned char s[] = {0x99, 0x7E, 0xC8, 0xB3, 0x4B, 0x04, 0xAC, 0x4F, 0x1E, 0x90, 0x6D, 0xC0, 0x6F, 0x03, 0xB1, 0x5C, 0x13, 0xC2, 0x2C, 0xE3, 0x74, 0x03, 0xA6, 0x5E, 0x1F, 0x8D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 182) {
        unsigned char s[] = {0x2B, 0x83, 0x39, 0xF0, 0x70, 0x50, 0xE5, 0x6F, 0x1A, 0x96, 0x3F, 0x50, 0xAB, 0x04, 0xE5, 0x51, 0x14, 0xC2, 0x28, 0xFD, 0x7B, 0x05, 0xAB, 0x4B, 0x09, 0x83, 0x29, 0xFC, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 183) {
        unsigned char s[] = {0x99, 0x7F, 0xC1, 0xB3, 0x48, 0x0B, 0xB1, 0x4D, 0xB8, 0x51, 0x23, 0xB3, 0x76, 0x05, 0xE5, 0x5A, 0x15, 0x81, 0x22, 0xFD, 0x6C, 0x18, 0xA4, 0x5B, 0x14, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 184) {
        unsigned char s[] = {0x99, 0x7F, 0xC1, 0xB3, 0x52, 0x1F, 0xA0, 0x58, 0x14, 0xC2, 0x23, 0xFC, 0x38, 0x0F, 0xAB, 0x5C, 0x14, 0x8C, 0x39, 0xE1, 0x79, 0x0E, 0xAA, 0x13, 0x5B, 0xA7, 0x1E, 0xC3, 0x38, 0x04, 0xAA, 0x1F, 0x1A, 0x81, 0x39, 0xFA, 0x6E, 0x0B, 0xA1, 0x50, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 185) {
        unsigned char s[] = {0x99, 0x7E, 0xC8, 0xB3, 0x5D, 0x39, 0x95, 0x1F, 0x1A, 0x81, 0x39, 0xFA, 0x6E, 0x0B, 0xA1, 0x50, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 186) {
        unsigned char s[] = {0x29, 0x8D, 0x27, 0xFC, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 187) {
        unsigned char s[] = {0x2D, 0x87, 0x3F, 0xF7, 0x7D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 188) {
        unsigned char s[] = {0x3A, 0x98, 0x38, 0xFF, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 189) {
        unsigned char s[] = {0x38, 0x9B, 0x2C, 0xFD, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 190) {
        unsigned char s[] = {0x29, 0x8D, 0x3E, 0xF2, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 191) {
        unsigned char s[] = {0x36, 0x8D, 0x3F, 0xF2, 0x7C, 0x05, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 192) {
        unsigned char s[] = {0x39, 0x8E, 0x2C, 0xFD, 0x7B, 0x05, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 193) {
        unsigned char s[] = {0x3A, 0x8F, 0x2C, 0xE1, 0x71, 0x06, 0xA9, 0x50, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 194) {
        unsigned char s[] = {0x3A, 0x80, 0x2C, 0xF9, 0x77, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 195) {
        unsigned char s[] = {0x36, 0x87, 0x29, 0xFA, 0x77, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 196) {
        unsigned char s[] = {0x3A, 0x90, 0x3F, 0xFA, 0x7A, 0x0B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 197) {
        unsigned char s[] = {0x1E, 0x91, 0x3D, 0xCC, 0x7B, 0x05, 0xA9, 0x50, 0x09, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 198) {
        unsigned char s[] = {0x1E, 0x91, 0x3D, 0xCC, 0x6A, 0x0D, 0xA7, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 199) {
        unsigned char s[] = {0x1E, 0x91, 0x3D, 0xCC, 0x77, 0x18, 0xAC, 0x58, 0x12, 0x8C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 200) {
        unsigned char s[] = {0x1E, 0x91, 0x3D, 0xCC, 0x6F, 0x03, 0xA1, 0x4B, 0x13, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 201) {
        unsigned char s[] = {0x1E, 0x91, 0x3D, 0xCC, 0x7B, 0x05, 0xB0, 0x51, 0x0F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 202) {
        unsigned char s[] = {0x19, 0x97, 0x2F, 0xF1, 0x74, 0x0F, 0x9A, 0x4C, 0x12, 0x98, 0x28, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 203) {
        unsigned char s[] = {0x19, 0x97, 0x2F, 0xF1, 0x74, 0x0F, 0x9A, 0x47, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 204) {
        unsigned char s[] = {0x19, 0x97, 0x2F, 0xF1, 0x74, 0x0F, 0x9A, 0x46, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 205) {
        unsigned char s[] = {0x0E, 0x91, 0x28, 0xCC, 0x6A, 0x05, 0xAA, 0x4B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 206) {
        unsigned char s[] = {0x0B, 0x96, 0x3F, 0xCC, 0x6F, 0x03, 0xA1, 0x4B, 0x13, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 207) {
        unsigned char s[] = {0x12, 0x91, 0x12, 0xF1, 0x6D, 0x08, 0xA7, 0x53, 0x1E, 0xBD, 0x3F, 0xE6, 0x76, 0x04, 0xAC, 0x51, 0x1C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 208) {
        unsigned char s[] = {0x08, 0x83, 0x3B, 0xF6, 0x7C, 0x35, 0xB0, 0x4C, 0x1E, 0x90, 0x23, 0xF2, 0x75, 0x0F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 209) {
        unsigned char s[] = {0x08, 0x83, 0x3B, 0xF6, 0x7C, 0x35, 0xAE, 0x5A, 0x02, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 210) {
        unsigned char s[] = {0x08, 0x87, 0x2E, 0xE6, 0x6A, 0x0F, 0x9A, 0x5A, 0x15, 0x86, 0x3D, 0xFC, 0x71, 0x04, 0xB1, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 211) {
        unsigned char s[] = {0x2E, 0xB2, 0x09, 0xD2, 0x4C, 0x2F, 0x9A, 0x7D, 0x2E, 0xA0, 0x0F, 0xDF, 0x5D, 0x35, 0x88, 0x70, 0x3F, 0xA7, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 212) {
        unsigned char s[] = {0x3A, 0xB2, 0x1D, 0xDF, 0x41, 0x35, 0x87, 0x6A, 0x39, 0xA0, 0x01, 0xD6, 0x47, 0x39, 0x8C, 0x65, 0x3E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 213) {
        unsigned char s[] = {0x09, 0x8F, 0x6D, 0xBE, 0x7E, 0x4A, 0xEA, 0x5B, 0x1A, 0x96, 0x2C, 0xBC, 0x74, 0x05, 0xA6, 0x5E, 0x17, 0xCD, 0x39, 0xFE, 0x68, 0x45, 0xA3, 0x59, 0x16, 0x87, 0x20, 0xB3, 0x37, 0x0E, 0xA4, 0x4B, 0x1A, 0xCD, 0x21, 0xFC, 0x7B, 0x0B, 0xA9, 0x10, 0x0F, 0x8F, 0x3D, 0xBC, 0x36, 0x19, 0xBC, 0x4C, 0x51, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 214) {
        unsigned char s[] = {0x55, 0x91, 0x34, 0xE0, 0x47, 0x06, 0xAA, 0x58, 0x1F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 215) {
        unsigned char s[] = {0x1D, 0x84, 0x20, 0xF6, 0x75, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 216) {
        unsigned char s[] = {0x37, 0x8B, 0x2E, 0xF6, 0x76, 0x09, 0xAC, 0x5E, 0x5B, 0x81, 0x2C, 0xE1, 0x7F, 0x0B, 0xA1, 0x5E, 0x5B, 0x86, 0x28, 0xE0, 0x7C, 0x0F, 0xE5, 0x78, 0x3E, 0xB6, 0x6D, 0xD8, 0x5D, 0x33, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 217) {
        unsigned char s[] = {0x3C, 0x87, 0x23, 0xF6, 0x6A, 0x0B, 0xE5, 0x4A, 0x15, 0x83, 0x6D, 0xF8, 0x7D, 0x13, 0xE5, 0x49, 0x12, 0x87, 0x23, 0xF7, 0x77, 0x4A, 0xB0, 0x51, 0x14, 0x91, 0x6D, 0xE3, 0x79, 0x19, 0xAA, 0x4C, 0x5B, 0x81, 0x22, 0xE1, 0x6C, 0x05, 0xB6, 0x1F, 0x53, 0xA5, 0x08, 0xC7, 0x38, 0x21, 0x80, 0x66, 0x52, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 218) {
        unsigned char s[] = {0x3A, 0x80, 0x3F, 0xFA, 0x7D, 0x04, 0xA1, 0x50, 0x5B, 0xB5, 0x25, 0xF2, 0x6C, 0x19, 0x84, 0x4F, 0x0B, 0xCC, 0x63, 0xBD, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 219) {
        unsigned char s[] = {0x3A, 0x80, 0x3F, 0xFA, 0x7D, 0x04, 0xA1, 0x50, 0x5B, 0xB6, 0x24, 0xF8, 0x4C, 0x05, 0xAE, 0x11, 0x55, 0xCC, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 220) {
        unsigned char s[] = {0x35, 0x8D, 0x6D, 0xFB, 0x79, 0x13, 0xE5, 0x51, 0x1A, 0x94, 0x28, 0xF4, 0x79, 0x0E, 0xAA, 0x4D, 0x5B, 0x86, 0x24, 0xE0, 0x68, 0x05, 0xAB, 0x56, 0x19, 0x8E, 0x28, 0xBD, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 221) {
        unsigned char s[] = {0x3E, 0x90, 0x3F, 0xFC, 0x6A, 0x4A, 0xA1, 0x5A, 0x5B, 0x81, 0x22, 0xFD, 0x7D, 0x12, 0xAC, 0xFC, 0xC8, 0x8C, 0x63, 0xB3, 0x51, 0x04, 0xB1, 0x5A, 0x15, 0x96, 0x2C, 0xB3, 0x7C, 0x0F, 0xE5, 0x51, 0x0E, 0x87, 0x3B, 0xFC, 0x36, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 222) {
        unsigned char s[] = {0x35, 0x8D, 0x6D, 0xE0, 0x7D, 0x4A, 0xB5, 0x4A, 0x1F, 0x8D, 0x6D, 0xF2, 0x7A, 0x18, 0xAC, 0x4D, 0x5B, 0x87, 0x21, 0xB3, 0x7D, 0x04, 0xA9, 0x5E, 0x18, 0x87, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 223) {
        unsigned char s[] = {0x3D, 0xB0, 0x08, 0xD6, 0x42, 0x33, 0xE5, 0xDD, 0xFB, 0x40, 0x6D, 0xDB, 0x4D, 0x2E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 224) {
        unsigned char s[] = {0x34, 0xAC, 0x01, 0xDA, 0x56, 0x2F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 225) {
        unsigned char s[] = {0x3A, 0xAB, 0x00, 0xD1, 0x57, 0x3E, 0xE5, 0x72, 0x3A, 0xAB, 0x03, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 226) {
        unsigned char s[] = {0x3E, 0x8C, 0x2C, 0xF1, 0x74, 0x0F, 0xE5, 0x7E, 0x12, 0x8F, 0x2F, 0xFC, 0x6C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 227) {
        unsigned char s[] = {0x28, 0x8C, 0x24, 0xE3, 0x7D, 0x18, 0xE5, 0x6F, 0x1A, 0x96, 0x2E, 0xFB, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 228) {
        unsigned char s[] = {0x28, 0xAC, 0x04, 0xC3, 0x5D, 0x38, 0xE5, 0x7E, 0x28, 0xB1, 0x04, 0xC0, 0x4C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 229) {
        unsigned char s[] = {0x28, 0x8C, 0x24, 0xE3, 0x7D, 0x18, 0xE5, 0x6C, 0x18, 0x8D, 0x3D, 0xF6, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 230) {
        unsigned char s[] = {0x3A, 0x92, 0x38, 0xFD, 0x6C, 0x0B, 0xB7, 0x1F, 0x1A, 0x8E, 0x6D, 0xD0, 0x6D, 0x0F, 0xB7, 0x4F, 0x14, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 231) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x4C, 0x38, 0x84, 0x65, 0x3A, 0xA6, 0x02, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 232) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x55, 0x0B, 0xB6, 0x4B, 0x1E, 0x90, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 233) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x5A, 0x05, 0xBD, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 234) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x4B, 0x01, 0xA0, 0x53, 0x1E, 0x96, 0x22, 0xFD, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 235) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x54, 0xA9, 0x68, 0x51, 0x1E, 0x83, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 236) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x5B, 0x05, 0xB0, 0x51, 0x0F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 237) {
        unsigned char s[] = {0x3D, 0xAB, 0x01, 0xC7, 0x4A, 0x25, 0x96, 0x1F, 0x5D, 0xC2, 0x08, 0xC2, 0x4D, 0x23, 0x95, 0x70, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 238) {
        unsigned char s[] = {0x32, 0x85, 0x23, 0xFC, 0x6A, 0x0F, 0xE5, 0x74, 0x15, 0x8D, 0x2E, 0xF8, 0x7D, 0x0E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 239) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x4C, 0x0F, 0xA4, 0x52, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 240) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x51, 0x24, 0x83, 0x70, 0x29, 0xAF, 0x0C, 0xD0, 0x51, 0xA9, 0x56, 0x71, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 241) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x50, 0x0F, 0xA4, 0x53, 0x0F, 0x8A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 242) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x56, 0x0B, 0xA8, 0x5A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 243) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x5C, 0x03, 0xB6, 0x4B, 0x1A, 0x8C, 0x2E, 0xF6, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 244) {
        unsigned char s[] = {0x3E, 0xB1, 0x1D, 0xB3, 0x4F, 0x0F, 0xA4, 0x4F, 0x14, 0x8C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 245) {
        unsigned char s[] = {0x38, 0xB7, 0x1E, 0xC7, 0x57, 0x27, 0x8C, 0x65, 0x3A, 0xB6, 0x04, 0xDC, 0x56, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 246) {
        unsigned char s[] = {0x38, 0x8D, 0x21, 0xFC, 0x6A, 0x4A, 0x97, 0x78, 0x39, 0xC2, 0x65, 0xD2, 0x76, 0x03, 0xA8, 0x16, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 247) {
        unsigned char s[] = {0x32, 0x8C, 0x24, 0xF0, 0x71, 0x0B, 0xE5, 0x4C, 0x1E, 0x91, 0x24, 0x50, 0xAB, 0x04, 0xE5, 0x5C, 0x14, 0x8C, 0x6D, 0xE7, 0x6D, 0x4A, 0xA9, 0x56, 0x18, 0x87, 0x23, 0xF0, 0x71, 0x0B, 0xE5, 0x50, 0x1D, 0x8B, 0x2E, 0xFA, 0x79, 0x06, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 248) {
        unsigned char s[] = {0x34, 0xA0, 0x19, 0xD6, 0x56, 0x2F, 0x97, 0x1F, 0x37, 0xAB, 0x0E, 0xD6, 0x56, 0x29, 0x8C, 0x7E, 0x5B, 0xA5, 0x1F, 0xD2, 0x4C, 0x23, 0x96, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 249) {
        unsigned char s[] = {0x38, 0x83, 0x23, 0xF2, 0x74, 0x0F, 0xB6, 0x1F, 0x14, 0x84, 0x24, 0xF0, 0x71, 0x0B, 0xA9, 0x5A, 0x08, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 250) {
        unsigned char s[] = {0x2D, 0xA7, 0x1F, 0xDA, 0x5E, 0x23, 0x86, 0x7E, 0x38, 0xAB, 0x8E, 0x00, 0x56, 0x4A, 0x96, 0x7A, 0x3C, 0xB7, 0x1F, 0xD2, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 251) {
        unsigned char s[] = {0x2D, 0x83, 0x21, 0xFA, 0x7C, 0x0B, 0xAB, 0x5B, 0x14, 0xC2, 0x21, 0xFA, 0x7B, 0x0F, 0xAB, 0x5C, 0x12, 0x83, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 252) {
        unsigned char s[] = {0x38, 0x8D, 0x20, 0xE3, 0x6A, 0x05, 0xA7, 0x5E, 0x15, 0x86, 0x22, 0xB3, 0x6C, 0x1F, 0xE5, 0x5E, 0x18, 0x81, 0x28, 0xE0, 0x77, 0x4A, 0xA6, 0x50, 0x15, 0xC2, 0x28, 0xFF, 0x38, 0x19, 0xA0, 0x4D, 0x0D, 0x8B, 0x29, 0xFC, 0x6A, 0x4A, 0xA1, 0x5A, 0x5B, 0xA4, 0x3F, 0xF6, 0x7D, 0x10, 0xBC, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 253) {
        unsigned char s[] = {0x2D, 0xAD, 0x01, 0xC5, 0x5D, 0x38, 0xE5, 0x7E, 0x37, 0xC2, 0x01, 0xDC, 0x5F, 0x23, 0x8B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 254) {
        unsigned char s[] = {0x3A, 0xB4, 0x04, 0xC0, 0x57, 0x4A, 0x8C, 0x72, 0x2B, 0xAD, 0x1F, 0xC7, 0x59, 0x24, 0x91, 0x7A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 255) {
        unsigned char s[] = {0x3F, 0xA7, 0x1E, 0xD0, 0x59, 0x38, 0x82, 0x70, 0x5B, 0xA6, 0x08, 0xB3, 0x4A, 0x2F, 0x96, 0x6F, 0x34, 0xAC, 0x1E, 0xD2, 0x5A, 0x23, 0x89, 0x76, 0x3F, 0xA3, 0x09, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 256) {
        unsigned char s[] = {0x37, 0x87, 0x28, 0xB3, 0x7B, 0x1F, 0xAC, 0x5B, 0x1A, 0x86, 0x22, 0xE0, 0x79, 0x07, 0xA0, 0x51, 0x0F, 0x87, 0x6D, 0xF2, 0x76, 0x1E, 0xA0, 0x4C, 0x5B, 0x86, 0x28, 0xB3, 0x7B, 0x05, 0xAB, 0x4B, 0x12, 0x8C, 0x38, 0xF2, 0x6A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 257) {
        unsigned char s[] = {0x3A, 0xA1, 0x08, 0xC3, 0x4C, 0x25, 0xE5, 0x66, 0x5B, 0xA1, 0x02, 0xDD, 0x4C, 0x23, 0x8B, 0xFC, 0xE1, 0xAD, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 258) {
        unsigned char s[] = {0x35, 0xAD, 0x6D, 0xD2, 0x5B, 0x2F, 0x95, 0x6B, 0x34, 0xC2, 0x8F, 0x24, 0x38, 0x39, 0x84, 0x73, 0x32, 0xB0, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 259) {
        unsigned char s[] = {0x3A, 0xA1, 0x19, 0xC6, 0x59, 0x26, 0x8C, 0x65, 0x3A, 0xA1, 0x04, 0x50, 0x8B, 0x24, 0xE5, 0x6D, 0x3E, 0xB3, 0x18, 0xD6, 0x4A, 0x23, 0x81, 0x7E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 260) {
        unsigned char s[] = {0x33, 0x83, 0x34, 0xB3, 0x6D, 0x04, 0xA4, 0x1F, 0x15, 0x97, 0x28, 0xE5, 0x79, 0x4A, 0xB3, 0x5A, 0x09, 0x91, 0x24, 0x50, 0xAB, 0x04, 0xE5, 0x5B, 0x12, 0x91, 0x3D, 0xFC, 0x76, 0x03, 0xA7, 0x53, 0x1E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 261) {
        unsigned char s[] = {0x3A, 0x81, 0x39, 0xE6, 0x79, 0x06, 0xAC, 0x45, 0x1A, 0xC2, 0x3D, 0xF2, 0x6A, 0x0B, 0xE5, 0x5C, 0x14, 0x8C, 0x39, 0xFA, 0x76, 0x1F, 0xA4, 0x4D, 0x5B, 0x97, 0x3E, 0xF2, 0x76, 0x0E, 0xAA, 0x1F, 0x3D, 0x90, 0x28, 0xF6, 0x62, 0x13, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 262) {
        unsigned char s[] = {0x3A, 0xA0, 0x1F, 0xDA, 0x4A, 0x4A, 0x92, 0x77, 0x3A, 0xB6, 0x1E, 0xD2, 0x48, 0x3A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 263) {
        unsigned char s[] = {0x35, 0x8D, 0x3B, 0xF6, 0x7C, 0x0B, 0xA1, 0x5A, 0x08, 0xC2, 0x28, 0xFD, 0x38, 0x3E, 0xAC, 0x54, 0x2F, 0x8D, 0x26, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 264) {
        unsigned char s[] = {0x38, 0x83, 0x20, 0xF1, 0x71, 0x05, 0xB6, 0x13, 0x5B, 0x83, 0x3B, 0xFA, 0x6B, 0x05, 0xB6, 0x1F, 0x02, 0xC2, 0x29, 0xF6, 0x75, 0x05, 0xB6, 0x4B, 0x09, 0x83, 0x2E, 0xFA, 0x77, 0x04, 0xA0, 0x4C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 265) {
        unsigned char s[] = {0x3A, 0xA1, 0x0E, 0xD6, 0x4B, 0x25, 0xE5, 0x7E, 0x2E, 0xB6, 0x02, 0xC1, 0x51, 0x30, 0x84, 0x7B, 0x34, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 266) {
        unsigned char s[] = {0x37, 0x8B, 0x2E, 0xF6, 0x76, 0x09, 0xAC, 0x5E, 0x5B, 0x94, 0x8E, 0x32, 0x74, 0x03, 0xA1, 0x5E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 267) {
        unsigned char s[] = {0x32, 0x86, 0x28, 0xFD, 0x6C, 0x03, 0xA1, 0x5E, 0x1F, 0xC2, 0x34, 0xB3, 0x74, 0x03, 0xA6, 0x5A, 0x15, 0x81, 0x24, 0xF2, 0x38, 0x1C, 0xA0, 0x4D, 0x12, 0x84, 0x24, 0xF0, 0x79, 0x0E, 0xA4, 0x4C, 0x5B, 0x81, 0x22, 0xE1, 0x6A, 0x0F, 0xA6, 0x4B, 0x1A, 0x8F, 0x28, 0xFD, 0x6C, 0x0F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 268) {
        unsigned char s[] = {0x3A, 0xA1, 0x0E, 0xD6, 0x4B, 0x25, 0xE5, 0x69, 0x3E, 0xAC, 0x0E, 0xDA, 0x5C, 0x25, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 269) {
        unsigned char s[] = {0x37, 0x8B, 0x2E, 0xF6, 0x76, 0x09, 0xAC, 0x5E, 0x5B, 0x87, 0x35, 0xE3, 0x71, 0x18, 0xA4, 0x5B, 0x1A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 270) {
        unsigned char s[] = {0x29, 0x87, 0x23, 0xE6, 0x7D, 0x1C, 0xA4, 0x1F, 0x0F, 0x97, 0x6D, 0xFF, 0x71, 0x09, 0xA0, 0x51, 0x18, 0x8B, 0x2C, 0xB3, 0x68, 0x0B, 0xB7, 0x5E, 0x5B, 0x94, 0x22, 0xFF, 0x6E, 0x0F, 0xB7, 0x1F, 0x1A, 0xC2, 0x38, 0xE7, 0x71, 0x06, 0xAC, 0x45, 0x1A, 0x90, 0x6D, 0xD5, 0x6A, 0x0F, 0xA0, 0x45, 0x02, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 271) {
        unsigned char s[] = {0x38, 0xAD, 0x03, 0xD6, 0x40, 0x23, 0x06, 0xAC, 0x35, 0xC2, 0x04, 0xDD, 0x4C, 0x2F, 0x97, 0x6D, 0x2E, 0xAF, 0x1D, 0xDA, 0x5C, 0x2B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 272) {
        unsigned char s[] = {0x3E, 0x90, 0x3F, 0xFC, 0x6A, 0x4A, 0xA1, 0x5A, 0x5B, 0x90, 0x28, 0xF7, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 273) {
        unsigned char s[] = {0x38, 0x8D, 0x20, 0xE3, 0x6A, 0x1F, 0xA0, 0x5D, 0x1A, 0xC2, 0x39, 0xE6, 0x38, 0x09, 0xAA, 0x51, 0x1E, 0x9A, 0x24, 0x50, 0xAB, 0x04, 0xE5, 0x5E, 0x5B, 0x8B, 0x23, 0xE7, 0x7D, 0x18, 0xAB, 0x5A, 0x0F, 0xC2, 0x28, 0xB3, 0x71, 0x04, 0xB1, 0xFC, 0xD2, 0x8C, 0x39, 0xF2, 0x74, 0x05, 0xE5, 0x51, 0x0E, 0x87, 0x3B, 0xF2, 0x75, 0x0F, 0xAB, 0x4B, 0x1E, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 274) {
        unsigned char s[] = {0x28, 0xA7, 0x1F, 0xC5, 0x51, 0x29, 0x8C, 0x70, 0x5B, 0xB6, 0x08, 0xDE, 0x48, 0x25, 0x97, 0x7E, 0x37, 0xAF, 0x08, 0xDD, 0x4C, 0x2F, 0xE5, 0x6F, 0x3A, 0xB7, 0x1E, 0xD2, 0x5C, 0x25, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 275) {
        unsigned char s[] = {0x28, 0x87, 0x3F, 0xE5, 0x71, 0x0E, 0xAA, 0x4D, 0x5B, 0x87, 0x23, 0xB3, 0x75, 0x0B, 0xAB, 0x4B, 0x1E, 0x8C, 0x24, 0xFE, 0x71, 0x0F, 0xAB, 0x4B, 0x14, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 276) {
        unsigned char s[] = {0x3E, 0x91, 0x39, 0xF2, 0x75, 0x05, 0xB6, 0x1F, 0x09, 0x87, 0x2C, 0xFF, 0x71, 0x10, 0xA4, 0x51, 0x1F, 0x8D, 0x6D, 0xFE, 0x7D, 0x00, 0xAA, 0x4D, 0x1A, 0x91, 0x63, 0xB3, 0x51, 0x04, 0xB1, 0xFC, 0xD2, 0x8C, 0x39, 0xF2, 0x74, 0x05, 0xE5, 0x5B, 0x1E, 0xC2, 0x23, 0xE6, 0x7D, 0x1C, 0xAA, 0x1F, 0x16, 0x21, 0xEC, 0xE0, 0x38, 0x1E, 0xA4, 0x4D, 0x1F, 0x87, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 277) {
        unsigned char s[] = {0x3A, 0xA1, 0x0E, 0xD6, 0x4B, 0x25, 0xE5, 0x7B, 0x3E, 0xAC, 0x08, 0xD4, 0x59, 0x2E, 0x8A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 278) {
        unsigned char s[] = {0x37, 0x8B, 0x2E, 0xF6, 0x76, 0x09, 0xAC, 0x5E, 0x5B, 0x8C, 0x22, 0xB3, 0x6E, 0xA9, 0x64, 0x53, 0x12, 0x86, 0x2C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 279) {
        unsigned char s[] = {0x29, 0x87, 0x3B, 0xFA, 0x6B, 0x0B, 0xE5, 0x53, 0x14, 0x91, 0x6D, 0xF7, 0x79, 0x1E, 0xAA, 0x4C, 0x5B, 0x8B, 0x23, 0xF4, 0x6A, 0x0F, 0xB6, 0x5E, 0x1F, 0x8D, 0x3E, 0xB3, 0x61, 0x4A, 0xB3, 0x4A, 0x1E, 0x8E, 0x3B, 0xF6, 0x38, 0x0B, 0xE5, 0x56, 0x15, 0x96, 0x28, 0xFD, 0x6C, 0x0B, 0xB7, 0x53, 0x14, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 280) {
        unsigned char s[] = {0x38, 0x83, 0x23, 0xF2, 0x74, 0x4A, 0xA1, 0x5A, 0x5B, 0xB5, 0x25, 0xF2, 0x6C, 0x19, 0x84, 0x4F, 0x0B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    else if (id == 281) {
        unsigned char s[] = {0x2F, 0x8B, 0x26, 0xC7, 0x77, 0x01, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    return env->NewStringUTF("");
}
// ==== XOR_SECTION_END ====

// Simple SHA-256 implementation
#define ROTRIGHT(word,bits) (((word) >> (bits)) | ((word) << (32-(bits))))
#define SSIG0(x) (ROTRIGHT(x,7) ^ ROTRIGHT(x,18) ^ ((x) >> 3))
#define SSIG1(x) (ROTRIGHT(x,17) ^ ROTRIGHT(x,19) ^ ((x) >> 10))
#define CH(x,y,z) (((x) & (y)) ^ (~(x) & (z)))
#define MAJ(x,y,z) (((x) & (y)) ^ ((x) & (z)) ^ ((y) & (z)))
#define EP0(x) (ROTRIGHT(x,2) ^ ROTRIGHT(x,13) ^ ROTRIGHT(x,22))
#define EP1(x) (ROTRIGHT(x,6) ^ ROTRIGHT(x,11) ^ ROTRIGHT(x,25))

static const uint32_t sha256_k[64] = {
	0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
	0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
	0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
	0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
	0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
	0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
	0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
	0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

std::string sha256(const std::string& input) {
    uint32_t h[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };
    
    std::vector<uint8_t> msg(input.begin(), input.end());
    uint64_t bit_len = msg.size() * 8;
    msg.push_back(0x80);
    while ((msg.size() * 8) % 512 != 448) msg.push_back(0x00);
    for (int i = 7; i >= 0; --i) msg.push_back((bit_len >> (i * 8)) & 0xFF);
    
    for (size_t i = 0; i < msg.size(); i += 64) {
        uint32_t w[64], a, b, c, d, e, f, g, h_tmp, t1, t2;
        for (int j = 0; j < 16; ++j)
            w[j] = (msg[i + j*4] << 24) | (msg[i + j*4 + 1] << 16) | (msg[i + j*4 + 2] << 8) | msg[i + j*4 + 3];
        for (int j = 16; j < 64; ++j)
            w[j] = SSIG1(w[j-2]) + w[j-7] + SSIG0(w[j-15]) + w[j-16];
            
        a = h[0]; b = h[1]; c = h[2]; d = h[3];
        e = h[4]; f = h[5]; g = h[6]; h_tmp = h[7];
        
        for (int j = 0; j < 64; ++j) {
            t1 = h_tmp + EP1(e) + CH(e,f,g) + sha256_k[j] + w[j];
            t2 = EP0(a) + MAJ(a,b,c);
            h_tmp = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        
        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += h_tmp;
    }
    
    std::stringstream ss;
    for (int i = 0; i < 8; ++i) ss << std::hex << std::setw(8) << std::setfill('0') << h[i];
    return ss.str();
}

std::string read_system_file(const char* path) {
    std::ifstream file(path);
    std::string content;
    if (file >> content) return content;
    return "";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_NativeBridge_getNativeHWID(JNIEnv* env, jclass, jstring androidId, jstring hardwareInfo) {
    const char* id_str = env->GetStringUTFChars(androidId, nullptr);
    const char* hw_str = env->GetStringUTFChars(hardwareInfo, nullptr);

    std::string combined = std::string(id_str ? id_str : "") + "|" + std::string(hw_str ? hw_str : "");

    if (id_str) env->ReleaseStringUTFChars(androidId, id_str);
    if (hw_str) env->ReleaseStringUTFChars(hardwareInfo, hw_str);

    // Salt ensamblada en runtime (no aparece como string único en el binario)
    std::string salt = std::string("FREEZY_") + std::string("SECRET_") + std::string("SALT_") + std::string("20") + std::string("26");
    std::string hwid = sha256(combined + salt);

    return env->NewStringUTF(hwid.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_LoginActivity_getSecureEndpoint(JNIEnv* env, jobject thiz) {
    return Java_com_freezy_NativeBridge_getNativeString(env, nullptr, 1);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_MainActivity_getSecureEndpoint(JNIEnv* env, jobject thiz) {
    return Java_com_freezy_NativeBridge_getNativeString(env, nullptr, 1);
}

// Secreto HMAC de licencia ofuscado con XOR multibyte.
extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_NativeBridge_getHmacSecret(JNIEnv* env, jclass) {
    static const unsigned char xor_key[] = {0xA3, 0x5C, 0x7E, 0x19, 0x44, 0xD8, 0x0B, 0x62};
    unsigned char encrypted[] = {
        0xEF, 0x65, 0x2C, 0x53, 0x14, 0x8E, 0x3D, 0x0E,
        0xE1, 0x15, 0x4E, 0x50, 0x03, 0x91, 0x47, 0x01,
        0xF7, 0x0D, 0x31, 0x2B, 0x0A, 0xEA, 0x24, 0x17,
        0xE4, 0x3F, 0x2D, 0x77, 0x36, 0x93, 0x6C, 0x24,
        0x9A, 0x2A, 0x19, 0x28, 0x06, 0xA9, 0x7E, 0x30,
        0xDA, 0x38, 0x4A, 0x24
    };
    char result[sizeof(encrypted) + 1];
    for (size_t i = 0; i < sizeof(encrypted); ++i) {
        result[i] = static_cast<char>(encrypted[i] ^ xor_key[i % sizeof(xor_key)]);
    }
    result[sizeof(encrypted)] = '\0';
    return env->NewStringUTF(result);
}

std::string g_secure_payload = "";

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setSecurePayload(JNIEnv* env, jclass, jstring payloadObj) {
    if (payloadObj != nullptr) {
        const char* payloadChars = env->GetStringUTFChars(payloadObj, nullptr);
        g_secure_payload = std::string(payloadChars);
        env->ReleaseStringUTFChars(payloadObj, payloadChars);
        // No volcar el contenido del payload a Logcat (fuga de datos a logs).
        LOGI("Secure payload stored in memory");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_NativeBridge_isPayloadReady(JNIEnv*, jclass) {
    return g_secure_payload.empty() ? JNI_FALSE : JNI_TRUE;
}

void anti_frida_loop() {
    const char* markers[] = {
        "frida",            // agente frida inyectado (maps, threads, etc.)
        "re.frida.server",  // frida-server propio
        "frida-agent",      // payload del agente
        "frida-gadget",     // gadget embebido
        "xposed",           // hooks del framework Xposed
        "lspd",             // LSPosed
        "riru",             // Riru (base de LSPosed)
        "Zygisk",           // Zygisk (Magisk)
        "substrate",        // Substrate/Cydia hooks
        "gadget.config",    // config del gadget de Frida
        "linjector"         // inyección via ptrace
    };
    const size_t marker_count = sizeof(markers) / sizeof(markers[0]);
    // Puertos típicos de frida-server (27042/27043) en little-endian hex dentro de /proc/net/tcp
    const char* frida_ports[] = {"69A2", "69A3"};

    while (true) {
        bool found = false;

        std::ifstream maps("/proc/self/maps");
        if (maps.is_open()) {
            std::string line;
            while (std::getline(maps, line)) {
                for (size_t m = 0; m < marker_count; m++) {
                    if (line.find(markers[m]) != std::string::npos) {
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
            maps.close();
        }

        if (!found) {
            std::ifstream net_tcp("/proc/net/tcp");
            if (net_tcp.is_open()) {
                std::string line;
                while (std::getline(net_tcp, line)) {
                    for (const char* port : frida_ports) {
                        if (line.find(port) != std::string::npos) {
                            found = true;
                            break;
                        }
                    }
                    if (found) break;
                }
                net_tcp.close();
            }
        }

        if (found) {
            LOGE("Frida detected in memory! App suiciding...");
            exit(0);
        }
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
}

void start_anti_frida() {
    std::thread(anti_frida_loop).detach();
}
