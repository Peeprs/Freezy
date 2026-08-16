/*
 * ffmem - Helper de memoria raíz persistente para Freezy.
 *
 * Se ejecuta como root via su y mantiene abierto /proc/<pid>/mem.
 * Protocolo por stdin/stdout (líneas de texto):
 *   R <addr_hex> <size_hex>            -> <size*2 bytes hex> + '\n'  o "ERR\n"
 *   W <addr_hex> <size_hex> <hexdata>  -> "OK\n" o "ERR\n"
 *
 * Al ser un proceso raíz persistente, las lecturas/escrituras son
 * prácticamente instantáneas (un pread/pwrite por operación) en lugar de
 * lanzar un `su -c dd` por cada acceso.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>

#define MAX_PAYLOAD 16384

int main(int argc, char** argv) {
    if (argc < 2) return 1;
    int pid = atoi(argv[1]);
    char mempath[64];
    snprintf(mempath, sizeof(mempath), "/proc/%d/mem", pid);
    int fd = open(mempath, O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "ffmem: open %s fallo: %s\n", mempath, strerror(errno));
        return 1;
    }

    char line[16384];
    while (fgets(line, sizeof(line), stdin)) {
        if (line[0] == 'R') {
            unsigned long long addr = 0, size = 0;
            if (sscanf(line + 1, "%llx %llx", &addr, &size) != 2 || size == 0 || size > MAX_PAYLOAD) {
                fputs("ERR\n", stdout); fflush(stdout);
                continue;
            }
            static unsigned char buf[MAX_PAYLOAD];
            ssize_t got = pread(fd, buf, (size_t)size, (off_t)addr);
            if (got != (ssize_t)size) {
                fputs("ERR\n", stdout); fflush(stdout);
                continue;
            }
            for (unsigned long long i = 0; i < size; i++) printf("%02x", buf[i]);
            fputc('\n', stdout); fflush(stdout);
        } else if (line[0] == 'W') {
            unsigned long long addr = 0, size = 0;
            char* p = line + 1;
            char* endp = NULL;
            addr = strtoull(p, &endp, 16);
            if (endp == p) { fputs("ERR\n", stdout); fflush(stdout); continue; }
            p = endp;
            size = strtoull(p, &endp, 16);
            if (endp == p || size == 0 || size > MAX_PAYLOAD) { fputs("ERR\n", stdout); fflush(stdout); continue; }
            p = endp;
            static unsigned char buf[MAX_PAYLOAD];
            size_t n = 0;
            while (n < size && *p && *p != '\n') {
                unsigned int byte;
                if (sscanf(p, "%2x", &byte) != 1) break;
                buf[n++] = (unsigned char)byte;
                p += 2;
            }
            if (n != size) { fputs("ERR\n", stdout); fflush(stdout); continue; }
            ssize_t w = pwrite(fd, buf, (size_t)size, (off_t)addr);
            fputs(w == (ssize_t)size ? "OK\n" : "ERR\n", stdout);
            fflush(stdout);
        }
    }
    close(fd);
    return 0;
}