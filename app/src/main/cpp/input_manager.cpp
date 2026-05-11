#include <fcntl.h>
#include <unistd.h>
#include <linux/uinput.h>
#include <cstring>
#include <cstdio>
#include <android/log.h>

#define LOG_TAG "InputManager"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

int init_virtual_device() {
    // Abrir el driver de usuario de entrada
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("Error al abrir /dev/uinput. ¿Tienes permisos de Root?");
        return -1;
    }

    // Definir que enviará movimientos relativos
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_RELBIT, REL_Y); // Solo eje Y (vertical)

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "Virtual-Recoil-Pad");
    
    if (write(fd, &uidev, sizeof(uidev)) < 0) {
        LOGE("Error al escribir la configuración del dispositivo.");
        close(fd);
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("Error al crear el dispositivo virtual.");
        close(fd);
        return -1;
    }

    LOGI("Dispositivo virtual creado con éxito.");
    return fd;
}

void destroy_virtual_device(int fd) {
    if (fd >= 0) {
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
        LOGI("Dispositivo virtual destruido.");
    }
}
