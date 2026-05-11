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

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_LoginActivity_getSecureEndpoint(JNIEnv* env, jobject) {
    char url[80];
    url[0]='h';url[1]='t';url[2]='t';url[3]='p';url[4]='s';url[5]=':';url[6]='/';
    url[7]='/';url[8]='l';url[9]='i';url[10]='c';url[11]='e';url[12]='n';url[13]='c';
    url[14]='i';url[15]='a';url[16]='s';url[17]='-';url[18]='f';url[19]='r';url[20]='e';
    url[21]='e';url[22]='z';url[23]='y';url[24]='.';url[25]='o';url[26]='n';url[27]='r';
    url[28]='e';url[29]='n';url[30]='d';url[31]='e';url[32]='r';url[33]='.';url[34]='c';
    url[35]='o';url[36]='m';url[37]='/';url[38]='a';url[39]='p';url[40]='i';url[41]='/';
    url[42]='k';url[43]='e';url[44]='y';url[45]='s';url[46]='/';url[47]='v';url[48]='e';
    url[49]='r';url[50]='i';url[51]='f';url[52]='y';url[53]='\0';
    return env->NewStringUTF(url);
}
