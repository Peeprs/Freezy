# ══════════════════════════════════════════════════════════════════════════
# ProGuard / R8 Rules — Freezy
# Ofuscación agresiva para que MobSF no pueda leer la lógica de negocio
# ══════════════════════════════════════════════════════════════════════════

# ── 1. OFUSCACIÓN MÁXIMA ────────────────────────────────────────────────
# Renombrar paquetes a una sola letra (a.a.a en vez de com.freezy.LoginActivity)
-repackageclasses ''
-allowaccessmodification

# Ocultar nombre de archivo fuente en stack traces
-renamesourcefileattribute ''
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,EnclosingMethod

# ── 2. PRESERVAR JNI (CRÍTICO) ──────────────────────────────────────────
# C++ busca funciones por nombre exacto: Java_com_freezy_NativeBridge_*
# Si R8 renombra estas clases, el JNI falla con UnsatisfiedLinkError

# NativeBridge — Todos los métodos nativos y constantes
-keep class com.freezy.NativeBridge {
    *;
}

# LoginActivity — Tiene getSecureEndpoint() como método nativo
-keep class com.freezy.LoginActivity {
    private native <methods>;
}

# AntigravityFirewall — Tiene startNativeEngine/stopNativeEngine/setLagActive
-keep class com.freezy.AntigravityFirewall {
    private native <methods>;
    public native <methods>;
    public boolean protectSocket(int);
}

# BubbleService — Callback de JNI para onFiringStateChanged
-keep class com.freezy.BubbleService {
    public void onFiringStateChanged(boolean);
}

# ── 3. PRESERVAR REFLEXIÓN Y JSON ──────────────────────────────────────
# JSONObject usa reflexión para parsear — mantener modelos de datos
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── 4. PRESERVAR SERVICIOS DE ANDROID ──────────────────────────────────
# Servicios y Activities declarados en el Manifest deben mantener su nombre
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ── 5. CRYPTO / SECURITY ──────────────────────────────────────────────
# Preservar clases de cifrado para evitar que R8 las elimine como "no usadas"
-keep class com.freezy.SecureCrypto { *; }
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# ── 6. ELIMINAR LOGS EN RELEASE ────────────────────────────────────────
# R8 puede eliminar completamente las llamadas a Log.* en release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Eliminar también System.out/err (por si se escapó algún println)
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# ── 7. PRESERVAR ATRIBUTOS NECESARIOS ──────────────────────────────────
# Mantener anotaciones de AndroidX
-keepattributes *Annotation*
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# FileProvider (declarado en Manifest)
-keep class androidx.core.content.FileProvider { *; }

# ── 8. REGLAS ANTI-DECOMPILACIÓN ───────────────────────────────────────
# Optimizaciones agresivas que hacen más difícil la ingeniería inversa
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-verbose