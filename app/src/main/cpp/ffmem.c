#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/uio.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <stdint.h>

#define MAX_PAYLOAD 16384

int main(int argc, char** argv) {
    if (argc < 2) return 1;
    int pid = atoi(argv[1]);

    // Camuflaje de proceso en el kernel
    prctl(PR_SET_NAME, "logd", 0, 0, 0);

    char line[16384];
    while (fgets(line, sizeof(line), stdin)) {
        if (line[0] == 'R') {
            unsigned long long addr = 0, size = 0;
            if (sscanf(line + 1, "%llx %llx", &addr, &size) != 2 || size == 0 || size > MAX_PAYLOAD) {
                fputs("ERR\n", stdout); fflush(stdout);
                continue;
            }
            static unsigned char buf[MAX_PAYLOAD];

            // Lectura directa por syscall process_vm_readv (0 descriptores de archivo en /proc/pid/mem)
            struct iovec local_iov = { buf, (size_t)size };
            struct iovec remote_iov = { (void*)(uintptr_t)addr, (size_t)size };
            ssize_t got = syscall(__NR_process_vm_readv, pid, &local_iov, 1, &remote_iov, 1, 0);

            if (got != (ssize_t)size) {
                fputs("ERR\n", stdout); fflush(stdout);
                continue;
            }
            for (unsigned long long i = 0; i < size; i++) printf("%02x", buf[i]);
            fputc('\n', stdout); fflush(stdout);
        } else if (line[0] == 'B') {
            // Comando 'B <module_name>': Resolver base del módulo sin popen ni su
            char modName[128] = {0};
            if (sscanf(line + 1, "%127s", modName) != 1) {
                fputs("ERR\n", stdout); fflush(stdout);
                continue;
            }
            char mapPath[64];
            snprintf(mapPath, sizeof(mapPath), "/proc/%d/maps", pid);
            FILE* fp = fopen(mapPath, "r");
            if (!fp) {
                fputs("ERR\n", stdout); fflush(stdout);
                continue;
            }
            char mapLine[512];
            unsigned long long baseAddr = 0;
            int ptrWidth = 4;
            while (fgets(mapLine, sizeof(mapLine), fp)) {
                if (strstr(mapLine, modName) != NULL) {
                    if (strstr(mapLine, "/arm64") != NULL || strstr(mapLine, "arm64-v8a") != NULL) {
                        ptrWidth = 8;
                    }
                    char* dash = strchr(mapLine, '-');
                    if (dash) {
                        *dash = '\0';
                        baseAddr = strtoull(mapLine, NULL, 16);
                        break;
                    }
                }
            }
            fclose(fp);
            if (baseAddr > 0) {
                printf("%llx %d\n", baseAddr, ptrWidth);
            } else {
                fputs("ERR\n", stdout);
            }
            fflush(stdout);
        } else if (line[0] == 'V') {
            // Comando 'V <count> <addr1> <size1> ...': Batch Vectorial con 1 sola syscall
            int count = 0;
            char* ptr = line + 1;
            int bytesRead = 0;
            if (sscanf(ptr, "%d%n", &count, &bytesRead) != 1 || count <= 0 || count > 64) {
                fputs("ERR\n", stdout); fflush(stdout);
                continue;
            }
            ptr += bytesRead;

            struct iovec local_iov[64];
            struct iovec remote_iov[64];
            static unsigned char batch_buf[MAX_PAYLOAD];
            size_t buf_offset = 0;
            size_t total_expected = 0;
            int valid = 1;

            for (int i = 0; i < count; i++) {
                unsigned long long addr = 0, sz = 0;
                if (sscanf(ptr, "%llx %llx%n", &addr, &sz, &bytesRead) != 2 || sz == 0 || (buf_offset + sz) > MAX_PAYLOAD) {
                    valid = 0;
                    break;
                }
                ptr += bytesRead;
                local_iov[i].iov_base = batch_buf + buf_offset;
                local_iov[i].iov_len = (size_t)sz;
                remote_iov[i].iov_base = (void*)(uintptr_t)addr;
                remote_iov[i].iov_len = (size_t)sz;
                buf_offset += sz;
                total_expected += sz;
            }

            if (!valid) {
                fputs("ERR\n", stdout); fflush(stdout);
                continue;
            }

            // 1 sola llamada de sistema al kernel para todos los vectores
            ssize_t got = syscall(__NR_process_vm_readv, pid, local_iov, count, remote_iov, count, 0);
            if (got != (ssize_t)total_expected) {
                fputs("ERR\n", stdout); fflush(stdout);
                continue;
            }

            for (size_t i = 0; i < total_expected; i++) printf("%02x", batch_buf[i]);
            fputc('\n', stdout); fflush(stdout);
        } else {
            fputs("ERR\n", stdout); fflush(stdout);
        }
    }
    return 0;
}