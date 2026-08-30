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
// la cola, watchdog ni estado de Fake Lag.
static std::atomic<uint64_t> g_last_keepalive_time{0};
static std::atomic<uint64_t> g_max_desync_ms{800}; // Configurable dinámicamente por JNI (Paso 7 Watchdog)

/* ── JNI callback to protect a socket ───────────────────────────────────── */
static JavaVM*    g_jvm     = nullptr;
static jobject    g_svc_ref = nullptr;   // GlobalRef al servicio VPN
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

        int nfds = epoll_wait(g_epoll_fd, events, MAX_EVENTS, timeout_ms);
        if (nfds < 0) {
            if (errno == EINTR) continue;
            LOGE("epoll_wait error: %s", strerror(errno));
            break;
        }

        // Procesar cualquier paquete con retardo (jitter general o filtro selectivo).
        process_outbound_queue();

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
                            bool drop_packet = gLagActive.load() && is_game;

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
Java_com_freezy_publicapp_N_d(
        JNIEnv* env, jclass, jobject service, jint fd) {
    if (g_running) return;

    // Store JavaVM + service reference for protect() callbacks
    env->GetJavaVM(&g_jvm);
    if (g_svc_ref) { env->DeleteGlobalRef(g_svc_ref); g_svc_ref = nullptr; }
    g_svc_ref = env->NewGlobalRef(service);
    jclass cls = env->GetObjectClass(service);
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
Java_com_freezy_publicapp_N_e(
        JNIEnv* env, jclass) {
    if (!g_running) return;
    g_running = false;
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
Java_com_freezy_publicapp_N_f(
        JNIEnv* /*env*/, jclass /*cls*/, jboolean active) {
    gLagActive = (bool)active;
    LOGI("Lag switch: %s", (bool)active ? "ON" : "OFF");
    if (!active && g_tun_fd >= 0) {
        delay_queue_flush(g_tun_fd);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_publicapp_N_g(JNIEnv* /*env*/, jclass) {
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
