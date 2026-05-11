#include <unistd.h>
#include <linux/uinput.h>
#include <cstring>
#include <atomic>
#include <android/log.h>
#include <chrono>
#include <thread>
#include "patterns.h"

#define LOG_TAG "RecoilEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

std::atomic<bool> is_firing(false);
RecoilPattern current_pattern = DEFAULT_AR_PATTERN;

void apply_progressive_recoil(int fd) {
    LOGI("Motor de retroceso progresivo iniciado.");
    auto start_time = std::chrono::steady_clock::now();
    
    while (is_firing) {
        auto current_time = std::chrono::steady_clock::now();
        long elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            current_time - start_time).count();

        // Cálculo de fuerza progresiva
        int current_strength = current_pattern.base_strength + 
                               (int)(current_pattern.increment_factor * (elapsed_ms / 100.0f));
        
        if (current_strength > current_pattern.max_strength) 
            current_strength = current_pattern.max_strength;

        struct input_event ev;
        
        // Evento de movimiento relativo en Y
        memset(&ev, 0, sizeof(ev));
        ev.type = EV_REL;
        ev.code = REL_Y;
        ev.value = current_strength; // Valor positivo baja la mira
        write(fd, &ev, sizeof(ev)); // Inyectar movimiento

        
        // Sincronizar evento
        memset(&ev, 0, sizeof(ev));
        ev.type = EV_SYN;
        ev.code = SYN_REPORT;
        ev.value = 0;
        write(fd, &ev, sizeof(ev));

        std::this_thread::sleep_for(std::chrono::milliseconds(current_pattern.interval_ms));
    }
    LOGI("Motor de retroceso progresivo detenido.");
}

void set_firing(bool firing) {
    if (is_firing != firing) {
        if (firing) {
        } else {
        }
    }
    is_firing = firing;
}
