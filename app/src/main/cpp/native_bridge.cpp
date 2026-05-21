#include <jni.h>
#include <android/log.h>
#include "patterns.h"

#define LOG_TAG "NativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "FreezyDebug", __VA_ARGS__)

// Variable global para guardar la referencia al servicio/clase que tiene el overlay
JavaVM* g_jvm = nullptr;
jobject g_ui_callback = nullptr;

extern void start_anti_frida();

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("JNI_OnLoad: JavaVM registrada");
    start_anti_frida();
    return JNI_VERSION_1_6;
}

// Función que C++ usará para avisar a Kotlin
void notify_ui_firing_state(bool is_firing) {
    if (!g_jvm || !g_ui_callback) return;

    JNIEnv* env;
    g_jvm->AttachCurrentThread(&env, NULL);

    jclass callbackClass = env->GetObjectClass(g_ui_callback);
    jmethodID methodId = env->GetMethodID(callbackClass, "onFiringStateChanged", "(Z)V");
    
    if (methodId != nullptr) {
        env->CallVoidMethod(g_ui_callback, methodId, is_firing);
        LOGD("UI callback: firing state = %d", is_firing);
    }
    
    g_jvm->DetachCurrentThread();
}

// Declaraciones externas de los otros archivos
extern int init_virtual_device();
extern void destroy_virtual_device(int fd);
extern void apply_progressive_recoil(int fd);
extern void set_firing(bool firing);
extern bool start_touch_monitor(const char* device_path, int x1, int y1, int x2, int y2);
extern void stop_touch_monitor();
extern void update_fire_zone(int x1, int y1, int x2, int y2);

extern "C"
JNIEXPORT jint JNICALL
Java_com_freezy_network_RecoilService_initEngine(JNIEnv *env, jobject thiz) {
    LOGI("Iniciando motor desde JNI...");
    return init_virtual_device();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_freezy_network_RecoilService_stopEngine(JNIEnv *env, jobject thiz, jint fd) {
    LOGI("Deteniendo motor desde JNI...");
    destroy_virtual_device(fd);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_freezy_network_RecoilService_startRecoil(JNIEnv *env, jobject thiz, jint fd) {
    LOGI("Iniciando compensación de retroceso...");
    set_firing(true);
    apply_progressive_recoil(fd);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_freezy_network_RecoilService_stopRecoil(JNIEnv *env, jobject thiz) {
    LOGI("Deteniendo compensación de retroceso...");
    set_firing(false);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_freezy_network_InputMonitor_startTouchMonitor(JNIEnv *env, jobject thiz, jstring device_path, jint x1, jint y1, jint x2, jint y2) {
    const char *path = env->GetStringUTFChars(device_path, 0);
    LOGI("Iniciando monitoreo de toques en %s", path);
    bool result = start_touch_monitor(path, x1, y1, x2, y2);
    env->ReleaseStringUTFChars(device_path, path);
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_freezy_network_InputMonitor_stopTouchMonitor(JNIEnv *env, jobject thiz) {
    LOGI("Deteniendo monitoreo de toques");
    stop_touch_monitor();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_freezy_network_InputMonitor_updateFireZone(JNIEnv *env, jobject thiz, jint x1, jint y1, jint x2, jint y2) {
    LOGI("Actualizando zona de disparo desde JNI...");
    update_fire_zone(x1, y1, x2, y2);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_freezy_network_RecoilService_setRecoilProfile(JNIEnv* env, jobject thiz, jint base, jfloat inc, jint max) {
    LOGI("Actualizando perfil de retroceso...");
    extern RecoilPattern current_pattern;
    current_pattern.base_strength = base;
    current_pattern.increment_factor = inc;
    current_pattern.max_strength = max;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setNoRecoilState(JNIEnv *env, jclass clazz, jboolean enabled) {
    LOGI("Set NoRecoil State: %d", enabled);
    set_firing(enabled);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setRecoilStrength(JNIEnv *env, jclass clazz, jint strength) {
    LOGI("Set Recoil Strength: %d", strength);
    extern RecoilPattern current_pattern;
    current_pattern.base_strength = strength;
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setFovEnabled(JNIEnv* env, jclass clazz, jboolean enabled) {
    extern int g_fov_enabled;
    extern int g_fov_radius;
    g_fov_enabled = enabled;
    LOGI("FOV Enabled: %d", enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_setFovRadius(JNIEnv* env, jclass clazz, jint radius) {
    extern int g_fov_radius;
    g_fov_radius = radius;
    LOGI("FOV Radius: %d", radius);
}

extern "C" JNIEXPORT void JNICALL
Java_com_freezy_NativeBridge_registerUiCallback(JNIEnv* env, jclass clazz, jobject callback) {
    if (g_ui_callback != nullptr) {
        env->DeleteGlobalRef(g_ui_callback);
    }
    g_ui_callback = env->NewGlobalRef(callback);
    LOGI("UI Callback registrado");
}
