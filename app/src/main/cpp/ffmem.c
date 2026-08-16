#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/uio.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <stdint.h>
#include <errno.h>

#define MAX_PAYLOAD 16384

int main(int argc, char** argv) {
    if (argc < 2) return 1;
    int pid = atoi(argv[1]);

    // Camuflaje de proceso en el kernel (se reporta como 'logd')
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
        } else {
            fputs("ERR\n", stdout); fflush(stdout);
        }
    }
    return 0;
}