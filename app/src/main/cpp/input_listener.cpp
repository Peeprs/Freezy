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
    struct input_absinfo abs_x;
    
    // Intento 1: Buscar dispositivo que soporte ABS_MT_POSITION_X (100% fiable para pantallas multitáctiles)
    for (int i = 0; i < 32; i++) {
        std::string path = "/dev/input/event" + std::to_string(i);
        int fd = open(path.c_str(), O_RDONLY);
        if (fd >= 0) {
            if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &abs_x) >= 0) {
                ioctl(fd, EVIOCGNAME(sizeof(name)), name);
                close(fd);
                LOGI("Dispositivo multitáctil detectado por ioctl ABS_MT_POSITION_X: %s (%s)", path.c_str(), name);
                return path;
            }
            close(fd);
        }
    }

    // Intento 2: Búsqueda por palabras clave en el nombre si no responde ioctl
    for (int i = 0; i < 32; i++) {
        std::string path = "/dev/input/event" + std::to_string(i);
        int fd = open(path.c_str(), O_RDONLY);
        if (fd >= 0) {
            ioctl(fd, EVIOCGNAME(sizeof(name)), name);
            std::string dev_name(name);
            if (dev_name.find("touch") != std::string::npos || 
                dev_name.find("Touch") != std::string::npos ||
                dev_name.find("ts") != std::string::npos ||
                dev_name.find("TS") != std::string::npos) {
                close(fd);
                LOGI("Dispositivo de toque detectado por nombre: %s (%s)", path.c_str(), name);
                return path;
            }
            close(fd);
        }
    }

    LOGI("No se encontró dispositivo de toque por nombre o ioctl. Usando /dev/input/event4 como fallback.");
    return "/dev/input/event4"; // Fallback en Redmi K30 Pro
}

int max_raw_x = 4095; // Fallback
int max_raw_y = 4095; // Fallback
int g_screen_width = 1080;
int g_screen_height = 2400;
int g_rotation = 0;

struct TouchSlot {
    int id = -1;
    int raw_x = 0;
    int raw_y = 0;
    bool started_in_fire_zone = false;
    bool new_touch = false;
};

const int MAX_SLOTS = 10;
TouchSlot g_slots[MAX_SLOTS];
int g_current_slot = 0;

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

void raw_to_pixel(int raw_x, int raw_y, int& pixel_x, int& pixel_y) {
    if (max_raw_x <= 0 || max_raw_y <= 0) {
        pixel_x = raw_x;
        pixel_y = raw_y;
        return;
    }
    
    switch (g_rotation) {
        case 1: // Surface.ROTATION_90 (Landscape izquierdo)
            pixel_x = (raw_y * g_screen_width) / max_raw_y;
            pixel_y = ((max_raw_x - raw_x) * g_screen_height) / max_raw_x;
            break;
        case 2: // Surface.ROTATION_180
            pixel_x = ((max_raw_x - raw_x) * g_screen_width) / max_raw_x;
            pixel_y = ((max_raw_y - raw_y) * g_screen_height) / max_raw_y;
            break;
        case 3: // Surface.ROTATION_270 (Landscape derecho)
            pixel_x = ((max_raw_y - raw_y) * g_screen_width) / max_raw_y;
            pixel_y = (raw_x * g_screen_height) / max_raw_x;
            break;
        case 0: // Surface.ROTATION_0 (Portrait)
        default:
            pixel_x = (raw_x * g_screen_width) / max_raw_x;
            pixel_y = (raw_y * g_screen_height) / max_raw_y;
            break;
    }
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
    calibrate_touch_node(fd);
    
    struct input_event ev;

    while (monitor_running) {
        if (read(fd, &ev, sizeof(struct input_event)) < (int)sizeof(struct input_event)) {
            usleep(1000);
            continue;
        }
        
        if (ev.type == EV_ABS) {
            if (ev.code == ABS_MT_SLOT) {
                if (ev.value >= 0 && ev.value < MAX_SLOTS) {
                    g_current_slot = ev.value;
                }
            } else if (ev.code == ABS_MT_TRACKING_ID) {
                g_slots[g_current_slot].id = ev.value;
                if (ev.value == -1) {
                    // Finger lifted
                    if (g_slots[g_current_slot].started_in_fire_zone) {
                        g_slots[g_current_slot].started_in_fire_zone = false;
                        
                        // Check if any other slot is still firing
                        bool any_active_fire = false;
                        for (int i = 0; i < MAX_SLOTS; i++) {
                            if (g_slots[i].id != -1 && g_slots[i].started_in_fire_zone) {
                                any_active_fire = true;
                                break;
                            }
                        }
                        if (!any_active_fire) {
                            set_firing(false);
                            notify_ui_firing_state(false);
                            LOGI("Disparo AUTO-LAG finalizado (dedo levantado)");
                        }
                    }
                    g_slots[g_current_slot].new_touch = false;
                    touch_pressed = false;
                } else {
                    // New finger touch down
                    g_slots[g_current_slot].started_in_fire_zone = false;
                    g_slots[g_current_slot].new_touch = true;
                    touch_pressed = true;
                }
            } else if (ev.code == ABS_MT_POSITION_X) {
                g_slots[g_current_slot].raw_x = ev.value;
                current_x = ev.value;
            } else if (ev.code == ABS_MT_POSITION_Y) {
                g_slots[g_current_slot].raw_y = ev.value;
                current_y = ev.value;
            }
        } else if (ev.type == EV_KEY && ev.code == BTN_TOUCH) {
            if (ev.value == 1) {
                touch_pressed = true;
            } else {
                touch_pressed = false;
                // Fallback: If all touches are released, force stop everything just in case
                bool was_firing = false;
                for (int i = 0; i < MAX_SLOTS; i++) {
                    if (g_slots[i].started_in_fire_zone) {
                        was_firing = true;
                    }
                    g_slots[i].id = -1;
                    g_slots[i].started_in_fire_zone = false;
                    g_slots[i].new_touch = false;
                }
                if (was_firing) {
                    set_firing(false);
                    notify_ui_firing_state(false);
                    LOGI("Disparo AUTO-LAG finalizado por BTN_TOUCH = 0");
                }
            }
        } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
            // Process slots that just started touching
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (g_slots[i].id != -1 && g_slots[i].new_touch) {
                    g_slots[i].new_touch = false;
                    
                    int pixel_x, pixel_y;
                    raw_to_pixel(g_slots[i].raw_x, g_slots[i].raw_y, pixel_x, pixel_y);
                    
                    LOGD("Slot %d Touch DOWN: raw(%d, %d) -> pixel(%d, %d)", i, g_slots[i].raw_x, g_slots[i].raw_y, pixel_x, pixel_y);
                    
                    // 1. Check mapped Auto-Lag zone
                    if (is_in_fire_zone(pixel_x, pixel_y)) {
                        g_slots[i].started_in_fire_zone = true;
                        set_firing(true);
                        notify_ui_firing_state(true);
                        LOGI("Disparo AUTO-LAG detectado en Slot %d", i);
                    }
                    
                    // 2. Check FOV overlay
                    if (is_inside_fov(pixel_x, pixel_y)) {
                        set_firing(true);
                        notify_ui_firing_state(true);
                        LOGD("Disparo detectado dentro del FOV");
                    }
                }
            }
        }
    }

    close(fd);
    LOGI("Monitoreo de toques detenido.");
    return NULL;
}

bool start_touch_monitor(const char* device_path, int x1, int y1, int x2, int y2, int screen_w, int screen_h, int rotation) {
    if (monitor_running) return false;

    fire_zone_x1 = x1;
    fire_zone_y1 = y1;
    fire_zone_x2 = x2;
    fire_zone_y2 = y2;

    g_screen_width = screen_w;
    g_screen_height = screen_h;
    g_rotation = rotation;

    screen_cx = screen_w / 2;
    screen_cy = screen_h / 2;

    // Reset slots
    for (int i = 0; i < MAX_SLOTS; i++) {
        g_slots[i].id = -1;
        g_slots[i].started_in_fire_zone = false;
        g_slots[i].new_touch = false;
    }

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
