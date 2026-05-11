#include <fcntl.h>
#include <unistd.h>
#include <linux/input.h>
#include <pthread.h>
#include <atomic>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <string>

#define LOG_TAG "InputListener"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "FreezyDebug", __VA_ARGS__)

// Declaraciones externas (de recoil_engine.cpp)
extern void set_firing(bool firing);
extern void notify_ui_firing_state(bool is_firing);

std::string find_touch_event_node() {
    char name[256];
    for (int i = 0; i < 32; i++) {
        std::string path = "/dev/input/event" + std::to_string(i);
        int fd = open(path.c_str(), O_RDONLY);
        if (fd >= 0) {
            ioctl(fd, EVIOCGNAME(sizeof(name)), name);
            // Buscamos descriptores comunes de pantallas táctiles
            if (strstr(name, "touch") || strstr(name, "Touch")) {
                close(fd);
                LOGI("Dispositivo de toque encontrado: %s (%s)", path.c_str(), name);
                return path;
            }
            close(fd);
        }
    }
    LOGI("No se encontró dispositivo de toque por nombre. Usando /dev/input/event2 como fallback.");
    return "/dev/input/event2"; // Fallback común
}

int max_raw_x = 4095; // Fallback
int max_raw_y = 4095; // Fallback

void calibrate_touch_node(int fd) {
    struct input_absinfo abs_info;
    
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &abs_info) >= 0) {
        max_raw_x = abs_info.maximum;
        LOGI("Rango máximo X del hardware: %d", max_raw_x);
    }
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &abs_info) >= 0) {
        max_raw_y = abs_info.maximum;
        LOGI("Rango máximo Y del hardware: %d", max_raw_y);
    }
}

int raw_to_pixel_x(int raw_x, int screen_width) {
    return (raw_x * screen_width) / max_raw_x;
}

int raw_to_pixel_y(int raw_y, int screen_height) {
    return (raw_y * screen_height) / max_raw_y;
}

// Variables de control
std::atomic<bool> monitor_running(false);
pthread_t monitor_thread;

int current_x = 0;
int current_y = 0;
bool touch_pressed = false;

// Coordenadas de la zona de disparo (rectángulo)
int fire_zone_x1 = 0;
int fire_zone_y1 = 0;
int fire_zone_x2 = 0;
int fire_zone_y2 = 0;

bool is_in_fire_zone(int x, int y) {
    return (x >= fire_zone_x1 && x <= fire_zone_x2 && y >= fire_zone_y1 && y <= fire_zone_y2);
}

// Variables y funciones FOV
int g_fov_radius = 100;
bool g_fov_enabled = false;

// Obtenemos el centro de la pantalla (puedes pasarlo desde Kotlin)
int screen_cx = 1080 / 2; 
int screen_cy = 2400 / 2;

bool is_inside_fov(int touch_x, int touch_y) {
    // Si el radio es 0 o está apagado, el filtro no deja pasar nada
    if (!g_fov_enabled || g_fov_radius <= 0) return false;

    // Pitágoras para saber si el toque está dentro del círculo
    long long dx = touch_x - screen_cx;
    long long dy = touch_y - screen_cy;
    
    // Si la distancia al centro es menor o igual al radio, el disparo es válido
    return (dx * dx + dy * dy) <= (long long)g_fov_radius * g_fov_radius;
}

void* touch_monitor_thread(void* arg) {
    std::string node = find_touch_event_node();
    int fd = open(node.c_str(), O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("Error al abrir %s. ¿Tienes permisos de Root?", node.c_str());
        return NULL;
    }

    LOGI("Monitoreando toques en %s...", node.c_str());
    struct input_event ev;

    while (monitor_running) {
        if (read(fd, &ev, sizeof(struct input_event)) < (int)sizeof(struct input_event)) {
            usleep(1000);
            continue;
        }
        
        // Detectar tipo de evento
        if (ev.type == EV_ABS) {
            LOGD("Raw Value: %d, Code: %d", ev.value, ev.code);
            if (ev.code == ABS_MT_POSITION_X) {
                current_x = ev.value;
            } else if (ev.code == ABS_MT_POSITION_Y) {
                current_y = ev.value;
            } else if (ev.code == ABS_MT_TRACKING_ID) {
                if (ev.value == -1) {
                    touch_pressed = false; // Dedo levantado
                } else {
                    touch_pressed = true;  // Dedo en pantalla
                }
            }
        } else if (ev.type == EV_KEY && ev.code == BTN_TOUCH) {
            if (ev.value == 1) {
                // Toque iniciado - verificar si está dentro del FOV
                if (is_inside_fov(current_x, current_y)) {
                    set_firing(true);
                    notify_ui_firing_state(true); // Avisa a Kotlin: PONLO ROJO
                    LOGD("Disparo detectado dentro del FOV");
                }
                touch_pressed = true;
            } else {
                // Toque finalizado
                touch_pressed = false;
                set_firing(false);
                notify_ui_firing_state(false); // Avisa a Kotlin: REGRESA A BLANCO
            }
        }
    }

    close(fd);
    LOGI("Monitoreo de toques detenido.");
    return NULL;
}

bool start_touch_monitor(const char* device_path, int x1, int y1, int x2, int y2) {
    if (monitor_running) return false;

    fire_zone_x1 = x1;
    fire_zone_y1 = y1;
    fire_zone_x2 = x2;
    fire_zone_y2 = y2;

    monitor_running = true;
    
    // Duplicar el string para que persista en el hilo
    char* path_copy = strdup(device_path);
    
    if (pthread_create(&monitor_thread, NULL, touch_monitor_thread, (void*)path_copy) != 0) {
        LOGE("Error al crear el hilo de monitoreo.");
        monitor_running = false;
        free(path_copy);
        return false;
    }

    return true;
}

void stop_touch_monitor() {
    if (!monitor_running) return;
    monitor_running = false;
    pthread_join(monitor_thread, NULL);
}

void update_fire_zone(int x1, int y1, int x2, int y2) {
    fire_zone_x1 = x1;
    fire_zone_y1 = y1;
    fire_zone_x2 = x2;
    fire_zone_y2 = y2;
    LOGI("Zona de disparo actualizada: (%d, %d) a (%d, %d)", x1, y1, x2, y2);
}
