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
#include <atomic>
#include <vector>
#include <cstdlib>
#include <fstream>
#include <string>
#include <sstream>
#include <iomanip>
#include <thread>
#include <chrono>

#define TAG  "FreezyNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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

/* ── Per-flow state ──────────────────────────────────────────────────────── */
struct Flow {
    int      sock;       // protected UDP socket
    uint32_t game_ip;    // game's source IP
    uint16_t game_port;  // game's source port (big-endian)
    uint32_t srv_ip;     // server IP the game was talking to
    uint16_t srv_port;   // server port (big-endian)
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
};

static const int MAX_FLOWS = 64;
static Flow          g_flows[MAX_FLOWS];
static int           g_flow_count = 0;

static const int MAX_TCP_FLOWS = 32;
static TcpFlow      g_tcp_flows[MAX_TCP_FLOWS];
static int          g_tcp_flow_count = 0;

static pthread_mutex_t g_flows_mtx = PTHREAD_MUTEX_INITIALIZER;

/* ── Global engine state ─────────────────────────────────────────────────── */
static volatile int   g_tun_fd   = -1;
static int            g_pipe[2]  = {-1, -1};
static pthread_t      g_thread;
static std::atomic<bool> g_running{false};

// Set from Kotlin: true = drop incoming UDP (lag switch ON)
extern "C" std::atomic<bool> gLagActive{false};

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

/* ── Get or create a protected socket for a given game source port ──────── */
static int get_or_create_flow(uint32_t game_ip, uint16_t game_port,
                               uint32_t srv_ip,  uint16_t srv_port) {
    pthread_mutex_lock(&g_flows_mtx);
    for (int i = 0; i < g_flow_count; i++) {
        if (g_flows[i].game_port == game_port) {
            int fd = g_flows[i].sock;
            pthread_mutex_unlock(&g_flows_mtx);
            return fd;
        }
    }
    if (g_flow_count >= MAX_FLOWS) {
        pthread_mutex_unlock(&g_flows_mtx);
        LOGE("MAX_FLOWS reached");
        return -1;
    }

    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) { pthread_mutex_unlock(&g_flows_mtx); return -1; }

    if (!protect_fd(sock)) {
        LOGE("protect() failed for sock=%d", sock);
        close(sock);
        pthread_mutex_unlock(&g_flows_mtx);
        return -1;
    }
    // Non-blocking so select() stays responsive
    fcntl(sock, F_SETFL, fcntl(sock, F_GETFL, 0) | O_NONBLOCK);

    g_flows[g_flow_count++] = {sock, game_ip, game_port, srv_ip, srv_port};
    LOGI("New flow game_port=%u → srv %08x:%u (sock=%d)",
         ntohs(game_port), ntohl(srv_ip), ntohs(srv_port), sock);

    pthread_mutex_unlock(&g_flows_mtx);
    return sock;
}

/* ── Write a reconstructed UDP/IP packet back to the tun interface ───────── */
static void write_to_tun(int tun_fd,
                          uint32_t src_ip, uint16_t src_port,
                          uint32_t dst_ip, uint16_t dst_port,
                          const uint8_t* payload, int plen) {
    if (plen > 1500) return; // Supera MTU

    int udp_len = 8 + plen;
    int ip_len  = 20 + udp_len;
    if (ip_len > 2000) return;

    uint8_t pkt[2000];
    memset(pkt, 0, ip_len);

    // IP header
    IpHdr* iph = (IpHdr*)pkt;
    iph->ihl_ver  = 0x45;
    iph->tot_len  = htons(ip_len);
    iph->id       = htons((uint16_t)(rand() & 0xffff));
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

/* ── Cleanup ─────────────────────────────────────────────────────────────── */
static void cleanup_flows() {
    pthread_mutex_lock(&g_flows_mtx);
    for (int i = 0; i < g_flow_count; i++) close(g_flows[i].sock);
    g_flow_count = 0;
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
    iph->id       = htons((uint16_t)(rand() & 0xffff));
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
    
    // Si es un SYN (intento de conexión)
    if (flags & 0x02) {
        LOGI("TCP SYN from %u to %u", ntohs(tcph->sport), ntohs(tcph->dport));
        
        int sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (sock >= 0) {
            protect_fd(sock);
            fcntl(sock, F_SETFL, fcntl(sock, F_GETFL, 0) | O_NONBLOCK);
            
            sockaddr_in dst{};
            dst.sin_family = AF_INET;
            dst.sin_addr.s_addr = iph->daddr;
            dst.sin_port = tcph->dport;
            
            connect(sock, (sockaddr*)&dst, sizeof(dst));
            
            pthread_mutex_lock(&g_flows_mtx);
            if (g_tcp_flow_count < MAX_TCP_FLOWS) {
                g_tcp_flows[g_tcp_flow_count++] = {
                    sock, iph->saddr, tcph->sport, iph->daddr, tcph->dport,
                    1000, ntohl(tcph->seq) + 1, false
                };
                LOGI("Created TCP flow for port %u", ntohs(tcph->sport));
            } else {
                close(sock);
                LOGE("MAX_TCP_FLOWS reached");
            }
            pthread_mutex_unlock(&g_flows_mtx);
            
            // Responder con SYN-ACK
            write_tcp_to_tun(tun_fd, 
                             iph->daddr, tcph->dport,
                             iph->saddr, tcph->sport,
                             1000, ntohl(tcph->seq) + 1,
                             0x12, // SYN | ACK
                             nullptr, 0);
        }
        return;
    }
    
    // Si es un ACK con datos
    if ((flags & 0x10) && plen > 0) {
        LOGI("TCP DATA from %u to %u, len=%d", ntohs(tcph->sport), ntohs(tcph->dport), plen);
        
        int flow_idx = -1;
        pthread_mutex_lock(&g_flows_mtx);
        for (int i = 0; i < g_tcp_flow_count; i++) {
            if (g_tcp_flows[i].client_port == tcph->sport && g_tcp_flows[i].server_ip == iph->daddr) {
                flow_idx = i;
                break;
            }
        }
        pthread_mutex_unlock(&g_flows_mtx);
        
        if (flow_idx != -1) {
            pthread_mutex_lock(&g_flows_mtx);
            TcpFlow& flow = g_tcp_flows[flow_idx];
            if (flow.sock >= 0) {
                send(flow.sock, payload, plen, 0);
                flow.seq_from_client += plen;
                
                // Responder con ACK para que el juego siga enviando
                write_tcp_to_tun(tun_fd, 
                                 iph->daddr, tcph->dport,
                                 iph->saddr, tcph->sport,
                                 flow.seq_to_client, flow.seq_from_client,
                                 0x10, // ACK
                                 nullptr, 0);
            }
            pthread_mutex_unlock(&g_flows_mtx);
        }
    }
}

/* ── Main engine thread ──────────────────────────────────────────────────── */
void* engine_thread(void*) {
    LOGI("Asymmetric UDP Proxy started. tun_fd=%d", g_tun_fd);
    uint8_t buf[65535];

    while (g_running) {
        int tun = g_tun_fd;
        if (tun < 0) break;

        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(tun, &fds);
        FD_SET(g_pipe[0], &fds);
        int maxfd = (tun > g_pipe[0]) ? tun : g_pipe[0];

        pthread_mutex_lock(&g_flows_mtx);
        for (int i = 0; i < g_flow_count; i++) {
            FD_SET(g_flows[i].sock, &fds);
            if (g_flows[i].sock > maxfd) maxfd = g_flows[i].sock;
        }
        for (int i = 0; i < g_tcp_flow_count; i++) {
            if (g_tcp_flows[i].sock >= 0) {
                FD_SET(g_tcp_flows[i].sock, &fds);
                if (g_tcp_flows[i].sock > maxfd) maxfd = g_tcp_flows[i].sock;
            }
        }
        pthread_mutex_unlock(&g_flows_mtx);

        timeval tv{1, 0};
        int ret = select(maxfd + 1, &fds, nullptr, nullptr, &tv);
        if (ret < 0) { if (errno == EINTR) continue; break; }
        if (ret == 0) continue;
        if (FD_ISSET(g_pipe[0], &fds)) break; // stop signal

        /* OUTGOING: game → VPN tun → protected socket → real server */
        if (FD_ISSET(tun, &fds)) {
            int n = read(tun, buf, sizeof(buf));
            if (n > 20) {
                uint8_t version = (buf[0] >> 4) & 0x0f;
                if (version == 4) { // SOLO IPv4
                    IpHdr* iph = (IpHdr*)buf;
                    int ihl = (iph->ihl_ver & 0x0f) * 4;
                    if (iph->proto == 17 && n > ihl + 8) {
                        UdpHdr* udph = (UdpHdr*)(buf + ihl);
                        uint8_t* payload = buf + ihl + 8;
                        int plen = ntohs(udph->len) - 8;
                        if (plen > 0 && plen <= n - ihl - 8) {
                            int sock = get_or_create_flow(
                                iph->saddr, udph->sport,
                                iph->daddr, udph->dport);
                            if (sock > 0) {
                                sockaddr_in dst{};
                                dst.sin_family      = AF_INET;
                                dst.sin_addr.s_addr = iph->daddr;
                                dst.sin_port        = udph->dport;
                                sendto(sock, payload, plen, 0,
                                       (sockaddr*)&dst, sizeof(dst));
                            }
                        }
                    } else if (iph->proto == 6 && n > ihl + 20) {
                        // Llamar al manejador TCP para Opción 2
                        handle_tcp_packet(tun, iph, buf, n);
                    }
                }
            }
        }

        /* INCOMING: real server → protected socket → drop or → VPN tun → game */
        pthread_mutex_lock(&g_flows_mtx);
        // UDP Incoming
        for (int i = 0; i < g_flow_count; i++) {
            if (!FD_ISSET(g_flows[i].sock, &fds)) continue;
            sockaddr_in from{};
            socklen_t fromlen = sizeof(from);
            int n = recvfrom(g_flows[i].sock, buf, sizeof(buf), 0,
                             (sockaddr*)&from, &fromlen);
            if (n > 0 && !gLagActive.load()) {
                write_to_tun(tun,
                    from.sin_addr.s_addr, from.sin_port,
                    g_flows[i].game_ip,   g_flows[i].game_port,
                    buf, n);
            }
        }
        
        // TCP Incoming (Opción 2)
        for (int i = 0; i < g_tcp_flow_count; i++) {
            if (g_tcp_flows[i].sock >= 0 && FD_ISSET(g_tcp_flows[i].sock, &fds)) {
                uint8_t t_buf[4096];
                int n = recv(g_tcp_flows[i].sock, t_buf, sizeof(t_buf), 0);
                if (n > 0) {
                    write_tcp_to_tun(tun, 
                                     g_tcp_flows[i].server_ip, g_tcp_flows[i].server_port,
                                     g_tcp_flows[i].client_ip, g_tcp_flows[i].client_port,
                                     g_tcp_flows[i].seq_to_client, g_tcp_flows[i].seq_from_client,
                                     0x18, // PSH | ACK
                                     t_buf, n);
                    g_tcp_flows[i].seq_to_client += n;
                } else if (n == 0 || (n < 0 && errno != EAGAIN)) {
                    close(g_tcp_flows[i].sock);
                    g_tcp_flows[i].sock = -1;
                }
            }
        }
        pthread_mutex_unlock(&g_flows_mtx);
    }

    cleanup_flows();
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
        unsigned char s[] = {0x16, 0x30, 0x27, 0x27, 0x34, 0x27, 0x75, 0x17, 0x20, 0x27, 0x37, 0x20, 0x32, 0x34, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 10) {
        unsigned char s[] = {0x01, 0x3C, 0x25, 0x3A, 0x75, 0x31, 0x3A, 0x75, 0x14, 0x36, 0x21, 0x3C, 0x23, 0x34, 0x36, 0x3C, 0x3A, 0x3B, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 11) {
        unsigned char s[] = {0x1C, 0x3B, 0x33, 0x3A, 0x27, 0x38, 0x34, 0x36, 0x3C, 0x3A, 0x3B, 0x75, 0x31, 0x3A, 0x75, 0x19, 0x3C, 0x36, 0x30, 0x3B, 0x36, 0x3C, 0x34, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 12) {
        unsigned char s[] = {0x74, 0x74, 0x75, 0x11, 0x10, 0x06, 0x16, 0x14, 0x07, 0x12, 0x1A, 0x75, 0x11, 0x10, 0x75, 0x07, 0x10, 0x06, 0x05, 0x1A, 0x1B, 0x06, 0x14, 0x17, 0x1C, 0x19, 0x1C, 0x11, 0x1C, 0x11, 0x14, 0x11, 0x00};
        xor_cipher(s, sizeof(s) - 1);
        return env->NewStringUTF((char*)s);
    } else if (id == 13) {
        unsigned char s[] = {0x6, 0x3c, 0x75, 0x37, 0x3c, 0x30, 0x3b, 0x75, 0x30, 0x26, 0x21, 0x34, 0x75, 0x3d, 0x30, 0x27, 0x27, 0x34, 0x38, 0x3c, 0x30, 0x3b, 0x21, 0x34, 0x75, 0x1b, 0x1a, 0x75, 0x34, 0x39, 0x21, 0x30, 0x27, 0x34, 0x75, 0x39, 0x3a, 0x26, 0x75, 0x34, 0x27, 0x36, 0x3d, 0x3c, 0x23, 0x3a, 0x26, 0x75, 0x3a, 0x27, 0x3c, 0x32, 0x3c, 0x3b, 0x34, 0x39, 0x30, 0x26, 0x75, 0x31, 0x30, 0x39, 0x75, 0x3f, 0x20, 0x30, 0x32, 0x3a, 0x79, 0x75, 0x21, 0x30, 0x75, 0x3a, 0x21, 0x3a, 0x27, 0x32, 0x34, 0x75, 0x20, 0x3b, 0x34, 0x75, 0x23, 0x30, 0x3b, 0x21, 0x34, 0x3f, 0x34, 0x75, 0x30, 0x2d, 0x21, 0x27, 0x30, 0x38, 0x34, 0x7b, 0x5f, 0x5f, 0x0, 0x26, 0x3a, 0x75, 0x31, 0x30, 0x75, 0x11, 0x34, 0x21, 0x3a, 0x26, 0x75, 0x2c, 0x75, 0x11, 0x3c, 0x26, 0x25, 0x3a, 0x26, 0x3c, 0x21, 0x3c, 0x23, 0x3a, 0x6f, 0x5f, 0x6, 0x3a, 0x39, 0x3c, 0x36, 0x3c, 0x21, 0x34, 0x38, 0x3a, 0x26, 0x75, 0x34, 0x36, 0x36, 0x30, 0x26, 0x3a, 0x75, 0x34, 0x39, 0x75, 0x72, 0x0, 0x26, 0x3a, 0x75, 0x31, 0x30, 0x75, 0x11, 0x34, 0x21, 0x3a, 0x26, 0x72, 0x75, 0x25, 0x34, 0x27, 0x34, 0x75, 0x38, 0x3a, 0x3b, 0x3c, 0x21, 0x3a, 0x27, 0x30, 0x34, 0x27, 0x75, 0x39, 0x34, 0x75, 0x30, 0x3f, 0x30, 0x36, 0x20, 0x36, 0x3c, 0x96, 0xe6, 0x3b, 0x75, 0x31, 0x30, 0x39, 0x75, 0x3f, 0x20, 0x30, 0x32, 0x3a, 0x75, 0x2c, 0x75, 0x34, 0x36, 0x21, 0x3c, 0x23, 0x34, 0x27, 0x75, 0x39, 0x34, 0x26, 0x75, 0x33, 0x20, 0x3b, 0x36, 0x3c, 0x3a, 0x3b, 0x30, 0x26, 0x75, 0x36, 0x3a, 0x27, 0x27, 0x30, 0x36, 0x21, 0x34, 0x38, 0x30, 0x3b, 0x21, 0x30, 0x7b, 0x75, 0x1, 0x34, 0x38, 0x37, 0x3c, 0x96, 0xfc, 0x3b, 0x75, 0x34, 0x39, 0x38, 0x34, 0x36, 0x30, 0x3b, 0x34, 0x38, 0x3a, 0x26, 0x75, 0x30, 0x39, 0x75, 0x3b, 0x3a, 0x38, 0x37, 0x27, 0x30, 0x75, 0x31, 0x30, 0x75, 0x21, 0x20, 0x75, 0x31, 0x3c, 0x26, 0x25, 0x3a, 0x26, 0x3c, 0x21, 0x3c, 0x23, 0x3a, 0x75, 0x25, 0x34, 0x27, 0x34, 0x75, 0x39, 0x34, 0x75, 0x31, 0x30, 0x21, 0x30, 0x36, 0x36, 0x3c, 0x96, 0xe6, 0x3b, 0x75, 0x2c, 0x75, 0x25, 0x27, 0x30, 0x23, 0x30, 0x3b, 0x36, 0x3c, 0x96, 0xe6, 0x3b, 0x75, 0x31, 0x30, 0x75, 0x33, 0x34, 0x39, 0x39, 0x34, 0x26, 0x75, 0x21, 0x96, 0xfc, 0x36, 0x3b, 0x3c, 0x36, 0x34, 0x26, 0x75, 0x30, 0x26, 0x25, 0x30, 0x36, 0x96, 0xf8, 0x33, 0x3c, 0x36, 0x34, 0x26, 0x75, 0x27, 0x30, 0x25, 0x3a, 0x27, 0x21, 0x34, 0x31, 0x34, 0x26, 0x75, 0x34, 0x3b, 0x21, 0x30, 0x27, 0x3c, 0x3a, 0x27, 0x38, 0x30, 0x3b, 0x21, 0x30, 0x75, 0x30, 0x3b, 0x75, 0x38, 0x3a, 0x31, 0x30, 0x39, 0x3a, 0x26, 0x75, 0x26, 0x3c, 0x38, 0x3c, 0x39, 0x34, 0x27, 0x30, 0x26, 0x7b, 0x5f, 0x5f, 0x10, 0x39, 0x75, 0x20, 0x26, 0x3a, 0x75, 0x34, 0x37, 0x20, 0x26, 0x3c, 0x23, 0x3a, 0x75, 0x25, 0x20, 0x30, 0x31, 0x30, 0x75, 0x36, 0x34, 0x20, 0x26, 0x34, 0x27, 0x75, 0x37, 0x34, 0x3b, 0x30, 0x3a, 0x26, 0x7b, 0x75, 0x10, 0x39, 0x75, 0x20, 0x26, 0x3a, 0x75, 0x31, 0x30, 0x75, 0x30, 0x26, 0x21, 0x34, 0x75, 0x3d, 0x30, 0x27, 0x27, 0x34, 0x38, 0x3c, 0x30, 0x3b, 0x21, 0x34, 0x75, 0x30, 0x26, 0x75, 0x37, 0x34, 0x3f, 0x3a, 0x75, 0x21, 0x20, 0x75, 0x25, 0x27, 0x3a, 0x25, 0x3c, 0x34, 0x75, 0x27, 0x30, 0x26, 0x25, 0x3a, 0x3b, 0x26, 0x34, 0x37, 0x3c, 0x39, 0x3c, 0x31, 0x34, 0x31, 0x7b, 0x0};
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
        unsigned char s[] = {0x13, 0x07, 0x10, 0x10, 0x0F, 0x0C, 0x75, 0x18, 0x10, 0x1B, 0x10, 0x00};
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

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    std::thread(anti_frida_loop).detach();
    return JNI_VERSION_1_6;
}
