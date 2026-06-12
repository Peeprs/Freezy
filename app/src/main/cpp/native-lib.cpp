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
#include <sys/resource.h>

#define TAG  "FreezyNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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
    uint8_t data[4096];
    int len;
};

static const int BUFFER_POOL_SIZE = 128;
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
static std::atomic<uint64_t> g_last_keepalive_time{0};

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
static const int MAX_DELAY_QUEUE_SIZE = 512;
struct DelayedPacket {
    bool     is_ipv6;
    uint32_t src_ip;
    uint16_t src_port;
    uint32_t dst_ip;
    uint16_t dst_port;
    uint8_t  src_ip6[16];
    uint8_t  dst_ip6[16];
    
    uint8_t  payload[2048];
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
    if (plen > 2048) return; // Supera tamaño del buffer de cola
    
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
        
        DBG_LOG("LAG-FLUSH: Releasing packet size=%d, held for %llu ms", pkt.payload_len, now - pkt.timestamp);
        
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

/* ── Probabilistic Packet Drop ─────────────────────────────────────────────── */
static bool should_probabilistic_drop() {
    uint16_t r = secure_random_u16();
    return (r % 10 == 0); // 10% probabilidad de descarte realista
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
    if (setpriority(PRIO_PROCESS, 0, -16) < 0) {
        LOGE("Failed to set thread priority: %s", strerror(errno));
    } else {
        LOGI("Native network thread priority set to high (-16)");
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
        int nfds = epoll_wait(g_epoll_fd, events, MAX_EVENTS, 100);
        if (nfds < 0) {
            if (errno == EINTR) continue;
            LOGE("epoll_wait error: %s", strerror(errno));
            break;
        }

        // Safety timeout flush check for delayed packets (Requirement 12)
        if (gLagActive.load()) {
            uint64_t now = get_now_ms();
            pthread_mutex_lock(&g_delay_queue_mtx);
            if (g_delay_queue_count > 0) {
                uint64_t oldest_age = now - g_delay_queue[g_delay_queue_head].timestamp;
                if (oldest_age > 2200) { // Safety limit: 2200ms
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
                                        sendto(sock, payload, plen, 0,
                                               (sockaddr*)&dst, sizeof(dst));
                                    }
                                }
                            } else if (iph->proto == 6 && n > ihl + 20) { // TCP (Early Protocol Filter: instant transparent bypass)
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
                                    int sock = get_or_create_flow(
                                        true,
                                        ip6h->saddr, udph->sport,
                                        ip6h->daddr, udph->dport);
                                    if (sock > 0) {
                                        sockaddr_in6 dst{};
                                        dst.sin6_family = AF_INET6;
                                        memcpy(&dst.sin6_addr, ip6h->daddr, 16);
                                        dst.sin6_port = udph->dport;
                                        sendto(sock, payload, plen, 0,
                                               (sockaddr*)&dst, sizeof(dst));
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

    if (pipe(g_pipe) < 0) { LOGE("pipe() failed"); return; }
    g_tun_fd = fd;
    gLagActive = false;
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
    g_tun_fd  = -1;
    if (g_pipe[1] > 0) { uint8_t b=1; write(g_pipe[1],&b,1);
                          close(g_pipe[1]); close(g_pipe[0]);
                          g_pipe[0]=g_pipe[1]=-1; }
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
void xor_cipher(unsigned char* data, size_t len) {
    unsigned char key = 0x55; // Llave de cifrado
    for (size_t i = 0; i < len; i++) {
        data[i] ^= key;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_NativeBridge_getNativeString(JNIEnv* env, jclass, jint id) {
    if (id == 1) {
        unsigned char s[] = {0x3D, 0x21, 0x21, 0x25, 0x26, 0x6F, 0x7A, 0x7A, 0x39, 0x3C, 0x36, 0x30, 0x3B, 0x36, 0x3C, 0x34, 0x26, 0x78, 0x33, 0x27, 0x30, 0x30, 0x2F, 0x2C, 0x7B, 0x3A, 0x3B, 0x27, 0x30, 0x3B, 0x31, 0x30, 0x27, 0x7B, 0x36, 0x3A, 0x38, 0x7A, 0x34, 0x25, 0x3C, 0x7A, 0x3E, 0x30, 0x2C, 0x26, 0x7A, 0x23, 0x30, 0x27, 0x3C, 0x33, 0x2C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 2) {
        unsigned char s[] = {0x1C, 0x1B, 0x1C, 0x16, 0x1C, 0x14, 0x07, 0x75, 0x13, 0x07, 0x10, 0x10, 0x0F, 0x0C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 3) {
        unsigned char s[] = {0x03, 0x34, 0x39, 0x3C, 0x31, 0x34, 0x3B, 0x31, 0x3A, 0x75, 0x36, 0x3A, 0x3B, 0x30, 0x2D, 0x3C, 0x3A, 0x3B, 0x75, 0x2C, 0x75, 0x39, 0x3C, 0x36, 0x30, 0x3B, 0x36, 0x3C, 0x34, 0x7B, 0x7B, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 4) {
        unsigned char s[] = {0x19, 0x34, 0x3B, 0x2F, 0x34, 0x3B, 0x31, 0x3A, 0x75, 0x38, 0x3A, 0x21, 0x3A, 0x27, 0x75, 0x13, 0x27, 0x30, 0x30, 0x2F, 0x2C, 0x7B, 0x7B, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 5) {
        unsigned char s[] = {0x14, 0x36, 0x36, 0x30, 0x26, 0x3A, 0x75, 0x16, 0x3A, 0x3B, 0x36, 0x30, 0x31, 0x3C, 0x31, 0x3A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 6) {
        unsigned char s[] = {0x19, 0x3C, 0x36, 0x30, 0x3B, 0x36, 0x3C, 0x34, 0x75, 0x3C, 0x3B, 0x23, 0x34, 0x39, 0x3C, 0x31, 0x34, 0x75, 0x3A, 0x75, 0x3C, 0x3B, 0x3A, 0x2D, 0x3C, 0x26, 0x21, 0x3A, 0x3B, 0x21, 0x3A, 0x7B, 0x75, 0x14, 0x31, 0x20, 0x20, 0x3C, 0x3A, 0x27, 0x30, 0x75, 0x20, 0x3B, 0x34, 0x75, 0x3A, 0x33, 0x3C, 0x36, 0x3C, 0x34, 0x39, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 7) {
        unsigned char s[] = {0x05, 0x3A, 0x27, 0x75, 0x33, 0x34, 0x23, 0x3A, 0x27, 0x2C, 0x75, 0x36, 0x3A, 0x38, 0x25, 0x39, 0x3A, 0x21, 0x34, 0x75, 0x21, 0x3A, 0x31, 0x3A, 0x26, 0x75, 0x39, 0x3A, 0x26, 0x75, 0x36, 0x34, 0x38, 0x25, 0x3A, 0x26, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 8) {
        unsigned char s[] = {0x03, 0x10, 0x07, 0x1C, 0x13, 0x1C, 0x16, 0x14, 0x1B, 0x11, 0x1A, 0x7B, 0x7B, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 9) {
        unsigned char s[] = {0x16, 0x30, 0x27, 0x27, 0x34, 0x27, 0x75, 0x17, 0x20, 0x27, 0x37, 0x20, 0x3F, 0x34, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 10) {
        unsigned char s[] = {0x01, 0x3C, 0x25, 0x3A, 0x75, 0x31, 0x30, 0x75, 0x14, 0x36, 0x21, 0x3C, 0x23, 0x34, 0x36, 0x3C, 0x3A, 0x3B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 11) {
        unsigned char s[] = {0x1C, 0x3B, 0x33, 0x3A, 0x27, 0x38, 0x34, 0x36, 0x3C, 0x3A, 0x3B, 0x75, 0x31, 0x30, 0x75, 0x19, 0x3C, 0x36, 0x30, 0x3B, 0x36, 0x3C, 0x34, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 12) {
        unsigned char s[] = {0x74, 0x74, 0x75, 0x11, 0x10, 0x06, 0x16, 0x14, 0x07, 0x12, 0x1A, 0x75, 0x11, 0x10, 0x75, 0x07, 0x10, 0x06, 0x05, 0x1A, 0x1B, 0x06, 0x14, 0x17, 0x1C, 0x19, 0x1C, 0x11, 0x1C, 0x11, 0x14, 0x11, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 13) {
        unsigned char s[] = {0x06, 0x3C, 0x75, 0x37, 0x3C, 0x30, 0x3B, 0x75, 0x30, 0x26, 0x21, 0x34, 0x75, 0x3D, 0x30, 0x27, 0x27, 0x34, 0x38, 0x3C, 0x30, 0x3B, 0x21, 0x34, 0x75, 0x1B, 0x1A, 0x75, 0x34, 0x39, 0x21, 0x30, 0x27, 0x34, 0x75, 0x39, 0x3A, 0x26, 0x75, 0x34, 0x27, 0x36, 0x3D, 0x3C, 0x23, 0x3A, 0x26, 0x75, 0x3A, 0x27, 0x3C, 0x32, 0x3C, 0x3B, 0x34, 0x39, 0x30, 0x26, 0x75, 0x31, 0x30, 0x39, 0x75, 0x3F, 0x20, 0x30, 0x32, 0x3A, 0x79, 0x75, 0x21, 0x30, 0x75, 0x3A, 0x21, 0x3A, 0x27, 0x32, 0x34, 0x75, 0x20, 0x3B, 0x34, 0x75, 0x23, 0x30, 0x3B, 0x21, 0x34, 0x3F, 0x34, 0x75, 0x30, 0x2D, 0x21, 0x27, 0x30, 0x38, 0x34, 0x7B, 0x5F, 0x5F, 0xB7, 0xCF, 0xF5, 0xBA, 0xED, 0xDA, 0x75, 0x00, 0x26, 0x3A, 0x75, 0x31, 0x30, 0x75, 0x11, 0x34, 0x21, 0x3A, 0x26, 0x75, 0x2C, 0x75, 0x11, 0x3C, 0x26, 0x25, 0x3A, 0x26, 0x3C, 0x21, 0x3C, 0x23, 0x3A, 0x6F, 0x5F, 0x06, 0x3A, 0x39, 0x3C, 0x36, 0x3C, 0x21, 0x34, 0x38, 0x3A, 0x26, 0x75, 0x34, 0x36, 0x36, 0x30, 0x26, 0x3A, 0x75, 0x34, 0x39, 0x75, 0x72, 0x00, 0x26, 0x3A, 0x75, 0x31, 0x30, 0x75, 0x11, 0x34, 0x21, 0x3A, 0x26, 0x72, 0x75, 0x25, 0x34, 0x27, 0x34, 0x75, 0x38, 0x3A, 0x3B, 0x3C, 0x21, 0x3A, 0x27, 0x30, 0x34, 0x27, 0x75, 0x39, 0x34, 0x75, 0x30, 0x3F, 0x30, 0x36, 0x20, 0x36, 0x3C, 0x3A, 0x3B, 0x75, 0x31, 0x30, 0x39, 0x75, 0x3F, 0x20, 0x30, 0x32, 0x3A, 0x75, 0x2C, 0x75, 0x34, 0x36, 0x21, 0x3C, 0x23, 0x34, 0x27, 0x75, 0x39, 0x34, 0x26, 0x75, 0x33, 0x20, 0x3B, 0x36, 0x3C, 0x3A, 0x3B, 0x30, 0x26, 0x75, 0x36, 0x3A, 0x27, 0x27, 0x30, 0x36, 0x21, 0x34, 0x38, 0x30, 0x3B, 0x21, 0x30, 0x7B, 0x75, 0x14, 0x39, 0x38, 0x34, 0x36, 0x30, 0x3B, 0x34, 0x38, 0x3A, 0x26, 0x75, 0x30, 0x39, 0x75, 0x3B, 0x3A, 0x38, 0x37, 0x27, 0x30, 0x75, 0x31, 0x30, 0x75, 0x21, 0x20, 0x75, 0x31, 0x3C, 0x26, 0x25, 0x3A, 0x26, 0x3C, 0x21, 0x3C, 0x23, 0x3A, 0x75, 0x25, 0x34, 0x27, 0x34, 0x75, 0x26, 0x3A, 0x25, 0x3A, 0x27, 0x21, 0x30, 0x7B, 0x5F, 0x5F, 0xB7, 0xCF, 0xF5, 0xBA, 0xED, 0xDA, 0x75, 0x14, 0x1B, 0x01, 0x1C, 0x17, 0x14, 0x1B, 0x75, 0x7A, 0x75, 0x12, 0x14, 0x18, 0x14, 0x75, 0x17, 0x14, 0x1F, 0x14, 0x6F, 0x5F, 0x05, 0x34, 0x27, 0x34, 0x75, 0x30, 0x23, 0x3C, 0x21, 0x34, 0x27, 0x75, 0x24, 0x20, 0x30, 0x75, 0x14, 0x3B, 0x31, 0x27, 0x3A, 0x3C, 0x31, 0x75, 0x26, 0x20, 0x26, 0x25, 0x30, 0x3B, 0x31, 0x34, 0x75, 0x30, 0x39, 0x75, 0x38, 0x3A, 0x21, 0x3A, 0x27, 0x75, 0x31, 0x30, 0x75, 0x27, 0x30, 0x31, 0x75, 0x3B, 0x34, 0x21, 0x3C, 0x23, 0x3A, 0x75, 0x7D, 0x36, 0x34, 0x20, 0x26, 0x34, 0x3B, 0x31, 0x3A, 0x75, 0x27, 0x30, 0x21, 0x27, 0x34, 0x26, 0x3A, 0x26, 0x75, 0x31, 0x30, 0x21, 0x30, 0x36, 0x21, 0x34, 0x37, 0x39, 0x30, 0x26, 0x75, 0x25, 0x3A, 0x27, 0x75, 0x30, 0x39, 0x75, 0x3F, 0x20, 0x30, 0x32, 0x3A, 0x7C, 0x79, 0x75, 0x30, 0x26, 0x75, 0x23, 0x3C, 0x21, 0x34, 0x39, 0x75, 0x31, 0x30, 0x26, 0x34, 0x36, 0x21, 0x3C, 0x23, 0x34, 0x27, 0x75, 0x30, 0x39, 0x75, 0x34, 0x3D, 0x3A, 0x27, 0x27, 0x3A, 0x75, 0x31, 0x30, 0x75, 0x37, 0x34, 0x21, 0x30, 0x27, 0x3C, 0x34, 0x75, 0x7D, 0x26, 0x3C, 0x3B, 0x75, 0x27, 0x30, 0x26, 0x21, 0x27, 0x3C, 0x36, 0x36, 0x3C, 0x3A, 0x3B, 0x30, 0x26, 0x7C, 0x75, 0x25, 0x34, 0x27, 0x34, 0x75, 0x13, 0x27, 0x30, 0x30, 0x2F, 0x2C, 0x7B, 0x75, 0x10, 0x39, 0x75, 0x20, 0x26, 0x3A, 0x75, 0x30, 0x26, 0x75, 0x37, 0x34, 0x3F, 0x3A, 0x75, 0x21, 0x20, 0x75, 0x25, 0x27, 0x3A, 0x25, 0x3C, 0x34, 0x75, 0x27, 0x30, 0x26, 0x25, 0x3A, 0x3B, 0x26, 0x34, 0x37, 0x3C, 0x39, 0x3C, 0x31, 0x34, 0x31, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 14) {
        unsigned char s[] = {0x14, 0x1F, 0x00, 0x06, 0x01, 0x10, 0x06, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 15) {
        unsigned char s[] = {0x14, 0x16, 0x10, 0x05, 0x01, 0x1A, 0x75, 0x10, 0x19, 0x75, 0x07, 0x1C, 0x10, 0x06, 0x12, 0x1A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 16) {
        unsigned char s[] = {0x13, 0x27, 0x30, 0x30, 0x2F, 0x2C, 0x75, 0x14, 0x36, 0x21, 0x3C, 0x23, 0x3A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 17) {
        unsigned char s[] = {0x01, 0x3A, 0x36, 0x34, 0x75, 0x39, 0x34, 0x75, 0x37, 0x20, 0x27, 0x37, 0x20, 0x32, 0x34, 0x75, 0x25, 0x34, 0x27, 0x34, 0x75, 0x34, 0x36, 0x21, 0x3C, 0x23, 0x34, 0x27, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 18) {
        unsigned char s[] = {0x1B, 0x3A, 0x78, 0x07, 0x30, 0x36, 0x3A, 0x3C, 0x39, 0x6F, 0x75, 0x1A, 0x13, 0x13, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 19) {
        unsigned char s[] = {0x10, 0x33, 0x30, 0x36, 0x21, 0x3C, 0x23, 0x3C, 0x31, 0x3C, 0x31, 0x34, 0x31, 0x6F, 0x75, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 20) {
        unsigned char s[] = {0x07, 0x34, 0x31, 0x3C, 0x3A, 0x75, 0x13, 0x1A, 0x03, 0x6F, 0x75, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 21) {
        unsigned char s[] = {0x13, 0x34, 0x3E, 0x30, 0x75, 0x19, 0x34, 0x32, 0x75, 0x14, 0x36, 0x21, 0x3C, 0x23, 0x34, 0x31, 0x3A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 22) {
        unsigned char s[] = {0x10, 0x27, 0x27, 0x3A, 0x27, 0x75, 0x34, 0x39, 0x75, 0x3A, 0x37, 0x21, 0x3A, 0x3B, 0x3A, 0x27, 0x75, 0x25, 0x3A, 0x27, 0x38, 0x3C, 0x26, 0x3A, 0x26, 0x75, 0x07, 0x3A, 0x3A, 0x21, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 23) {
        unsigned char s[] = {0x13, 0x34, 0x3E, 0x30, 0x75, 0x19, 0x34, 0x32, 0x75, 0x11, 0x30, 0x26, 0x34, 0x36, 0x21, 0x3C, 0x23, 0x34, 0x31, 0x3A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 24) {
        unsigned char s[] = {0x10, 0x27, 0x27, 0x3A, 0x27, 0x75, 0x31, 0x30, 0x75, 0x36, 0x3A, 0x3B, 0x30, 0x2D, 0x3C, 0x3A, 0x3B, 0x7B, 0x75, 0x06, 0x30, 0x26, 0x3C, 0x3A, 0x3B, 0x75, 0x36, 0x30, 0x27, 0x27, 0x34, 0x31, 0x34, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 25) {
        unsigned char s[] = {0x19, 0x3C, 0x36, 0x30, 0x3B, 0x36, 0x3C, 0x34, 0x75, 0x10, 0x2D, 0x25, 0x3C, 0x27, 0x34, 0x31, 0x34, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 26) {
        unsigned char s[] = {0x07, 0x10, 0x12, 0x1C, 0x06, 0x01, 0x07, 0x1A, 0x06, 0x75, 0x7D, 0x19, 0x1A, 0x12, 0x06, 0x7C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 27) {
        unsigned char s[] = {0x16, 0x10, 0x07, 0x07, 0x14, 0x07, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 28) {
        unsigned char s[] = {0x19, 0x1C, 0x18, 0x05, 0x1C, 0x14, 0x07, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 29) {
        unsigned char s[] = {0x07, 0x30, 0x32, 0x3C, 0x26, 0x21, 0x27, 0x3A, 0x26, 0x75, 0x39, 0x3C, 0x38, 0x25, 0x3C, 0x34, 0x31, 0x3A, 0x26, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 30) {
        unsigned char s[] = {0x03, 0x10, 0x07, 0x75, 0x07, 0x10, 0x12, 0x1C, 0x06, 0x01, 0x07, 0x1A, 0x06, 0x75, 0x7D, 0x19, 0x1A, 0x12, 0x06, 0x7C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 31) {
        unsigned char s[] = {0x16, 0x10, 0x07, 0x07, 0x14, 0x07, 0x75, 0x06, 0x10, 0x06, 0x1C, 0x1A, 0x1B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 32) {
        unsigned char s[] = {0x07, 0x1A, 0x1A, 0x01, 0x75, 0x11, 0x10, 0x01, 0x10, 0x16, 0x01, 0x14, 0x11, 0x1A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 33) {
        unsigned char s[] = {0x07, 0x1A, 0x1A, 0x01, 0x75, 0x1B, 0x1A, 0x75, 0x11, 0x10, 0x01, 0x10, 0x16, 0x01, 0x14, 0x11, 0x1A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 34) {
        unsigned char s[] = {0x07, 0x3A, 0x3A, 0x21, 0x75, 0x05, 0x30, 0x27, 0x38, 0x3C, 0x21, 0x3C, 0x31, 0x3A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 35) {
        unsigned char s[] = {0x07, 0x3A, 0x3A, 0x21, 0x75, 0x11, 0x30, 0x3B, 0x30, 0x32, 0x34, 0x31, 0x3A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 36) {
        unsigned char s[] = {0x03, 0x30, 0x27, 0x26, 0x3C, 0x3A, 0x3B, 0x75, 0x34, 0x36, 0x21, 0x20, 0x34, 0x39, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 37) {
        unsigned char s[] = {0x05, 0x3A, 0x27, 0x75, 0x33, 0x34, 0x23, 0x3A, 0x27, 0x79, 0x75, 0x3A, 0x21, 0x3A, 0x27, 0x32, 0x34, 0x75, 0x34, 0x36, 0x36, 0x30, 0x26, 0x3A, 0x75, 0x31, 0x30, 0x75, 0x20, 0x26, 0x3A, 0x75, 0x34, 0x75, 0x13, 0x27, 0x30, 0x30, 0x2F, 0x2C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 38) {
        unsigned char s[] = {0x13, 0x07, 0x10, 0x10, 0x0F, 0x0C, 0x75, 0x18, 0x10, 0x1B, 0x00, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 39) {
        unsigned char s[] = {0x1B, 0x3A, 0x07, 0x30, 0x36, 0x3A, 0x3C, 0x39, 0x75, 0x10, 0x2D, 0x21, 0x30, 0x27, 0x3B, 0x34, 0x39, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 40) {
        unsigned char s[] = {0x13, 0x1A, 0x03, 0x75, 0x10, 0x2D, 0x21, 0x30, 0x27, 0x3B, 0x34, 0x39, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 41) {
        unsigned char s[] = {0x14, 0x20, 0x21, 0x3A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 42) {
        unsigned char s[] = {0x16, 0x20, 0x26, 0x21, 0x3A, 0x38, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 43) {
        unsigned char s[] = {0x18, 0x34, 0x3B, 0x20, 0x34, 0x39, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 44) {
        unsigned char s[] = {0x06, 0x30, 0x32, 0x20, 0x3B, 0x31, 0x3A, 0x26, 0x75, 0x34, 0x75, 0x16, 0x3A, 0x3B, 0x32, 0x30, 0x39, 0x34, 0x27, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 45) {
        unsigned char s[] = {0x75, 0x06, 0x30, 0x32, 0x20, 0x3B, 0x31, 0x3A, 0x26, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 46) {
        unsigned char s[] = {0x14, 0x36, 0x21, 0x3C, 0x23, 0x34, 0x36, 0x3C, 0x3A, 0x3B, 0x6F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 47) {
        unsigned char s[] = {0x10, 0x2D, 0x25, 0x3C, 0x27, 0x34, 0x36, 0x3C, 0x3A, 0x3B, 0x6F, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 48) {
        unsigned char s[] = {0x06, 0x1C, 0x06, 0x01, 0x10, 0x18, 0x14, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 49) {
        unsigned char s[] = {0x05, 0x30, 0x27, 0x38, 0x3C, 0x21, 0x3C, 0x27, 0x75, 0x07, 0x3A, 0x3A, 0x21, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 50) {
        unsigned char s[] = {0x1C, 0x1B, 0x13, 0x1A, 0x07, 0x18, 0x14, 0x16, 0x1C, 0x1A, 0x1B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 51) {
        unsigned char s[] = {0x16, 0x00, 0x10, 0x1B, 0x01, 0x14, 0x75, 0x0C, 0x75, 0x06, 0x1A, 0x05, 0x1A, 0x07, 0x01, 0x10, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 52) {
        unsigned char s[] = {0x13, 0x07, 0x10, 0x10, 0x0F, 0x0C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 53) {
        unsigned char s[] = {0x00, 0x06, 0x00, 0x14, 0x07, 0x1C, 0x1A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 54) {
        unsigned char s[] = {0x1C, 0x3B, 0x32, 0x27, 0x30, 0x26, 0x34, 0x75, 0x21, 0x20, 0x75, 0x20, 0x26, 0x20, 0x34, 0x27, 0x3C, 0x3A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 55) {
        unsigned char s[] = {0x19, 0x1C, 0x16, 0x10, 0x1B, 0x16, 0x1C, 0x14, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 56) {
        unsigned char s[] = {0x05, 0x30, 0x32, 0x34, 0x75, 0x21, 0x20, 0x75, 0x39, 0x3C, 0x36, 0x30, 0x3B, 0x36, 0x3C, 0x34, 0x75, 0x34, 0x24, 0x20, 0x3C, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 57) {
        unsigned char s[] = {0x1C, 0x1B, 0x12, 0x07, 0x10, 0x06, 0x14, 0x07, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 58) {
        unsigned char s[] = {0x1B, 0x30, 0x36, 0x30, 0x26, 0x3C, 0x21, 0x34, 0x26, 0x75, 0x31, 0x34, 0x27, 0x75, 0x25, 0x30, 0x27, 0x38, 0x3C, 0x26, 0x3A, 0x75, 0x25, 0x34, 0x27, 0x34, 0x75, 0x38, 0x3A, 0x26, 0x21, 0x27, 0x34, 0x27, 0x75, 0x26, 0x3A, 0x37, 0x27, 0x30, 0x75, 0x3A, 0x21, 0x27, 0x34, 0x26, 0x75, 0x34, 0x25, 0x25, 0x26, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 59) {
        unsigned char s[] = {0x05, 0x30, 0x27, 0x38, 0x3C, 0x26, 0x3A, 0x75, 0x07, 0x3A, 0x3A, 0x21, 0x75, 0x3B, 0x3A, 0x75, 0x31, 0x3C, 0x26, 0x25, 0x3A, 0x3B, 0x3C, 0x37, 0x39, 0x30, 0x75, 0x3A, 0x75, 0x31, 0x30, 0x3B, 0x30, 0x32, 0x34, 0x31, 0x3A, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 60) {
        unsigned char s[] = {0x03, 0x34, 0x39, 0x3C, 0x31, 0x34, 0x3B, 0x31, 0x3A, 0x75, 0x39, 0x3C, 0x36, 0x30, 0x3B, 0x36, 0x3C, 0x34, 0x7B, 0x7B, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 61) {
        unsigned char s[] = {0x14, 0x16, 0x16, 0x10, 0x06, 0x1A, 0x75, 0x16, 0x1A, 0x1B, 0x16, 0x10, 0x11, 0x1C, 0x11, 0x1A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 62) {
        unsigned char s[] = {0x14, 0x16, 0x01, 0x00, 0x14, 0x19, 0x1C, 0x0F, 0x14, 0x16, 0x1C, 0x1A, 0x1B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 63) {
        unsigned char s[] = {0x10, 0x1B, 0x01, 0x10, 0x1B, 0x11, 0x1C, 0x11, 0x1A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 64) {
        unsigned char s[] = {0x11, 0x34, 0x21, 0x3A, 0x26, 0x75, 0x3C, 0x3B, 0x36, 0x3A, 0x38, 0x25, 0x39, 0x30, 0x21, 0x3A, 0x26, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 65) {
        unsigned char s[] = {0x10, 0x27, 0x27, 0x3A, 0x27, 0x75, 0x31, 0x30, 0x75, 0x36, 0x3A, 0x3B, 0x30, 0x2D, 0x3C, 0x3A, 0x3B, 0x75, 0x34, 0x39, 0x75, 0x3C, 0x3B, 0x3C, 0x36, 0x3C, 0x34, 0x27, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 66) {
        unsigned char s[] = {0x18, 0x3A, 0x31, 0x3A, 0x75, 0x07, 0x3A, 0x3A, 0x21, 0x75, 0x34, 0x36, 0x21, 0x3C, 0x23, 0x34, 0x31, 0x3A, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 67) {
        unsigned char s[] = {0x14, 0x36, 0x36, 0x30, 0x26, 0x3A, 0x75, 0x07, 0x3A, 0x3A, 0x21, 0x75, 0x31, 0x30, 0x3B, 0x30, 0x32, 0x34, 0x31, 0x3A, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 68) {
        unsigned char s[] = {0x1A, 0x21, 0x3A, 0x27, 0x32, 0x34, 0x75, 0x30, 0x39, 0x75, 0x25, 0x30, 0x27, 0x38, 0x3C, 0x26, 0x3A, 0x75, 0x31, 0x30, 0x75, 0x26, 0x20, 0x25, 0x30, 0x27, 0x25, 0x3A, 0x26, 0x3C, 0x36, 0x3C, 0x3A, 0x3B, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 69) {
        unsigned char s[] = {0x1A, 0x21, 0x3A, 0x27, 0x32, 0x34, 0x75, 0x30, 0x39, 0x75, 0x25, 0x30, 0x27, 0x38, 0x3C, 0x26, 0x3A, 0x75, 0x31, 0x30, 0x75, 0x34, 0x36, 0x36, 0x30, 0x26, 0x3A, 0x75, 0x31, 0x30, 0x75, 0x20, 0x26, 0x3A, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 70) {
        unsigned char s[] = {0x13, 0x27, 0x30, 0x30, 0x75, 0x13, 0x3C, 0x27, 0x30, 0x75, 0x3B, 0x3A, 0x75, 0x31, 0x30, 0x21, 0x30, 0x36, 0x21, 0x34, 0x31, 0x3A, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 71) {
        unsigned char s[] = {0x11, 0x30, 0x37, 0x20, 0x32, 0x32, 0x30, 0x27, 0x75, 0x31, 0x30, 0x21, 0x30, 0x36, 0x21, 0x34, 0x31, 0x3A, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 72) {
        unsigned char s[] = {0x05, 0x3A, 0x27, 0x75, 0x33, 0x34, 0x23, 0x3A, 0x27, 0x75, 0x30, 0x26, 0x25, 0x30, 0x27, 0x34, 0x7B, 0x7B, 0x7B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 73) {
        unsigned char s[] = {0x1F, 0x00, 0x10, 0x12, 0x1A, 0x75, 0x1A, 0x17, 0x1F, 0x10, 0x01, 0x1C, 0x03, 0x1A, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 74) {
        unsigned char s[] = {0x13, 0x27, 0x30, 0x30, 0x75, 0x13, 0x3C, 0x27, 0x30, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 75) {
        unsigned char s[] = {0x13, 0x13, 0x75, 0x18, 0x14, 0x0D, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    }
    return env->NewStringUTF("");
}

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
Java_com_freezy_NativeBridge_getNativeHWID(JNIEnv* env, jclass) {
    std::string storage_name = read_system_file("/sys/block/mmcblk0/device/name"); 
    if(storage_name.empty()) storage_name = read_system_file("/sys/block/sda/device/model");
    if(storage_name.empty()) storage_name = "UNKNOWN_HWID_FALLBACK";

    std::string salt = "FREEZY_SECRET_SALT_2026";
    std::string hwid = sha256(storage_name + salt);
    
    return env->NewStringUTF(hwid.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_LoginActivity_getSecureEndpoint(JNIEnv* env, jobject thiz) {
    return Java_com_freezy_NativeBridge_getNativeString(env, nullptr, 1);
}

// Secreto HMAC ofuscado con XOR — NO aparece como string legible en el binario
// Valor original: "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="
extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_NativeBridge_getHmacSecret(JNIEnv* env, jclass) {
    // XOR key diferente al usado para strings UI para mayor seguridad
    unsigned char xor_key = 0xA3;
    unsigned char s[] = {
        0x34^0xA3, 0x37^0xA3, 0x44^0xA3, 0x45^0xA3, 0x51^0xA3,
        0x70^0xA3, 0x6A^0xA3, 0x38^0xA3, 0x48^0xA3, 0x42^0xA3,
        0x53^0xA3, 0x61^0xA3, 0x2B^0xA3, 0x2F^0xA3, 0x54^0xA3,
        0x49^0xA3, 0x6D^0xA3, 0x57^0xA3, 0x2B^0xA3, 0x35^0xA3,
        0x4A^0xA3, 0x43^0xA3, 0x65^0xA3, 0x75^0xA3, 0x51^0xA3,
        0x65^0xA3, 0x52^0xA3, 0x6B^0xA3, 0x6D^0xA3, 0x35^0xA3,
        0x4E^0xA3, 0x4D^0xA3, 0x70^0xA3, 0x4A^0xA3, 0x57^0xA3,
        0x5A^0xA3, 0x47^0xA3, 0x33^0xA3, 0x68^0xA3, 0x53^0xA3,
        0x75^0xA3, 0x46^0xA3, 0x55^0xA3, 0x3D^0xA3
    };
    size_t len = sizeof(s);
    for (size_t i = 0; i < len; i++) {
        s[i] ^= xor_key;
    }
    // Null-terminate
    char result[45];
    memcpy(result, s, len);
    result[len] = '\0';
    return env->NewStringUTF(result);
}

std::string g_secure_payload = "";

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setSecurePayload(JNIEnv* env, jclass, jstring payloadObj) {
    if (payloadObj != nullptr) {
        const char* payloadChars = env->GetStringUTFChars(payloadObj, nullptr);
        g_secure_payload = std::string(payloadChars);
        env->ReleaseStringUTFChars(payloadObj, payloadChars);
        LOGI("Secure payload stored in memory: %s", g_secure_payload.c_str());
    }
}

void anti_frida_loop() {
    while (true) {
        std::ifstream maps("/proc/self/maps");
        std::string line;
        bool found = false;
        if (maps.is_open()) {
            while (std::getline(maps, line)) {
                if (line.find("frida") != std::string::npos || 
                    line.find("re.frida.server") != std::string::npos ||
                    line.find("frida-agent") != std::string::npos) {
                    found = true;
                    break;
                }
            }
            maps.close();
        }
        if (found) {
            LOGE("Frida detected in memory! App suiciding...");
            exit(0);
        }
        std::this_thread::sleep_for(std::chrono::seconds(5));
    }
}

void start_anti_frida() {
    std::thread(anti_frida_loop).detach();
}
