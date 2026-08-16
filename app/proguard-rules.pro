# ══════════════════════════════════════════════════════════════════════════
# ProGuard / R8 Rules
# Ofuscación agresiva para proteger la lógica de negocio
# ══════════════════════════════════════════════════════════════════════════

# ── 1. OFUSCACIÓN MÁXIMA ────────────────────────────────────────────────
# Renombrar paquetes a una sola letra (a.a.a en vez de com.freezy.LoginActivity)
-repackageclasses ''
-allowaccessmodification

# Ocultar nombre de archivo fuente en stack traces
-renamesourcefileattribute ''
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,EnclosingMethod

# ── 2. PRESERVAR JNI (CRÍTICO) ──────────────────────────────────────────
# C++ busca clases y métodos por nombre exacto. Si R8 los renombra, el JNI
# falla con UnsatisfiedLinkError. Solo se mantienen las clases con enlace JNI.

# NativeBridge — Solo los métodos enlazados por JNI; los campos constantes STRING_*
# deben renombrarse para no filtrar pistas léxicas en el DEX
-keepclassmembers class com.freezy.NativeBridge {
    native <methods>;
}

# LoginActivity — Tiene getSecureEndpoint() como método nativo + está en el Manifest
-keep class com.freezy.LoginActivity {
    private native <methods>;
}

# MainActivity — Tiene getSecureEndpoint() como método nativo + está en el Manifest
-keep class com.freezy.MainActivity {
    private native <methods>;
}

# AntigravityFirewall — startNativeEngine/stopNativeEngine/setLagActive + VpnService del Manifest
-keep class com.freezy.AntigravityFirewall {
    private native <methods>;
    public native <methods>;
    public boolean protectSocket(int);
}

# BubbleService — está en el Manifest
-keep class com.freezy.BubbleService

# ── 3. PRESERVAR REFLEXIÓN Y JSON ──────────────────────────────────────
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── 4. PRESERVAR COMPONENTES DEL MANIFEST ──────────────────────────────
# Activities y Services declarados en el Manifest deben mantener su nombre
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service

# ── 5. ELIMINAR LOGS E INTRINSICS EN RELEASE ───────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# Eliminar validaciones de parámetros de Kotlin que filtran nombres en DEX
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(...);
    public static void checkParameterIsNotNull(...);
    public static void checkNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
}

# ── 6. SUPRIMIR METADATOS Y TRAZAS DE KOTLIN ───────────────────────────
# NO conservar @kotlin.Metadata para evitar que decompiladores lean nombres .kt
-dontwarn kotlin.**
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,EnclosingMethod

-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# ── 7. REGLAS ANTI-DECOMPILACIÓN Y REPAQUETIZACIÓN TOTAL ────────────────
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-flattenpackagehierarchy ''
-repackageclasses ''
-verbose
