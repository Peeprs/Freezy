package com.freezy

object NativeBridge {
    init {
        System.loadLibrary("ncx")
    }

    @JvmStatic external fun setNativeMaxDesyncMs(ms: Long)

    @JvmStatic external fun setNativeJitterMs(ms: Int)

    @JvmStatic external fun setNativeDropProbability(probability: Int)

    @JvmStatic external fun getNativeString(id: Int): String

    @JvmStatic private external fun getNativeHWID(androidId: String, hardwareInfo: String): String

    @JvmStatic
    fun getHWID(context: android.content.Context): String {
        val androidId =
                android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                )
                        ?: "UNKNOWN_ANDROID_ID"
        val hardwareInfo =
                "${android.os.Build.BOARD}-${android.os.Build.BRAND}-${android.os.Build.DEVICE}-${android.os.Build.HARDWARE}-${android.os.Build.MODEL}-${android.os.Build.PRODUCT}"
        return getNativeHWID(androidId, hardwareInfo)
    }

    @JvmStatic external fun setSecurePayload(payload: String)

    @JvmStatic external fun isPayloadReady(): Boolean

    @JvmStatic external fun getHmacSecret(): String

    /** Encuentra el PID del juego */
    @JvmStatic external fun findGamePid(): Int

    /** Obtiene el nombre del paquete del juego */
    @JvmStatic external fun getGamePackageName(): String

    /** Verifica que la memoria del juego (libil2cpp.so) sea legible */
    @JvmStatic external fun isGameMemoryReady(pid: Int): Boolean

    /** Devuelve un diagnóstico detallado del acceso a memoria (para depurar fallos) */
    @JvmStatic external fun getGameMemoryDiagnostics(pid: Int): String

    /** Lee memoria del juego */
    @JvmStatic external fun readGameMemory(pid: Int, address: Long, size: Int): ByteArray?

    /** Escribe memoria del juego */
    @JvmStatic external fun writeGameMemory(pid: Int, address: Long, value: ByteArray): Boolean

    /** Inicia el aimbot (se llama automáticamente al abrir la app) */
    @JvmStatic external fun startAimbot()

    /** Detiene el aimbot */
    @JvmStatic external fun stopAimbot()

    /** Obtiene el estado del menú */
    @JvmStatic external fun getMenuStatus(): String

    // ============ Sniper Switch (patch de patrones) ============

    /** Busca el patrón de la mira y aplica el patch */
    @JvmStatic external fun sniperSwitchApply(): Boolean

    /** Restaura el patch original */
    @JvmStatic external fun sniperSwitchRemove(): Boolean

    /** ¿El patch de la mira está aplicado? */
    @JvmStatic external fun sniperSwitchIsApplied(): Boolean

    // ============ Sniper Scope (aim-assist) ============

    /** Activa/desactiva el aim-assist de francotirador */
    @JvmStatic external fun setSniperScope(active: Boolean)

    /** Modo de puntería: 0 = cabeza, 1 = cuerpo */
    @JvmStatic external fun setSniperMode(mode: Int)

    /** Ignorar jugadores derribados */
    @JvmStatic external fun setSniperIgnoreKnocked(ignore: Boolean)

    /** Ignorar bots */
    @JvmStatic external fun setSniperIgnoreBots(ignore: Boolean)

    /** Tamaño de pantalla (para el FOV del aim assist) */
    @JvmStatic external fun setScreenSize(w: Int, h: Int)

    // ============ Config nativa ============

    /** Ruta del helper ffmem (para lecturas rápidas como root) */
    @JvmStatic external fun setMemoryHelperPath(path: String)

    /** Ancho de los punteros del juego: 4 (emulador) u 8 (teléfono 64-bit) */
    @JvmStatic external fun setPointerWidth(width: Int)

    /** Diagnóstico paso a paso de la cadena de punteros IL2CPP */
    @JvmStatic external fun getChainDiagnostics(pid: Int): String

    /** Snapshot JSON con datos ESP (posición, salud, arma, nombre, team...) */
    @JvmStatic external fun getEspSnapshot(pid: Int): String

    /** Snapshot directo a FloatArray sin overhead de JSON ni recolección de basura (ultra rápido) */
    @JvmStatic external fun getEspSnapshotDirect(pid: Int, outData: FloatArray): Int

    const val STRING_ENDPOINT = 1
    const val STRING_BTN_START = 2
    const val STRING_VALIDATING = 3
    const val STRING_LAUNCHING = 4
    const val STRING_ACCESS_GRANTED = 5
    const val STRING_INVALID_LICENSE = 6
    const val STRING_FILL_FIELDS = 7
    const val STRING_VERIFYING = 8
    const val STRING_BTN_CLOSE_BUBBLE = 9
    const val STRING_TITLE_ACTIVATION = 10
    const val STRING_TITLE_LICENSE = 11
    const val STRING_DISCLAIMER_TITLE = 12
    const val STRING_DISCLAIMER_BODY = 13
    const val STRING_TITLE_SETTINGS = 14
    const val STRING_BTN_ACCEPT_RISK = 15
    const val STRING_BUBBLE_NOTIF_TITLE = 16
    const val STRING_BUBBLE_NOTIF_TEXT = 17
    const val STRING_RECOIL_OFF = 18
    const val STRING_EFFECTIVENESS = 19
    const val STRING_FOV_RADIUS = 20
    const val STRING_FAKE_LAG_ACTIVE = 21
    const val STRING_ROOT_ERROR = 22
    const val STRING_FAKE_LAG_DEACTIVATED = 23
    const val STRING_CONN_ERROR = 24
    const val STRING_LICENSE_EXPIRED = 25
    const val STRING_LOGS_TITLE = 26
    const val STRING_LOGS_CLOSE = 27
    const val STRING_LOGS_CLEAR = 28
    const val STRING_LOGS_CLEARED = 29
    const val STRING_LOGS_VER_LOGS = 30
    const val STRING_LOGOUT = 31
    const val STRING_ROOT_DETECTED = 32
    const val STRING_ROOT_NOT_DETECTED = 33
    const val STRING_ROOT_ENABLED = 34
    const val STRING_ROOT_DENIED = 35
    const val STRING_APP_VERSION = 36
    const val STRING_USAGE_ACCESS_REQ = 37
    const val STRING_BUBBLE_TITLE = 38
    const val STRING_RECOIL_EXTERNAL = 39
    const val STRING_FOV_EXTERNAL = 40
    const val STRING_MODE_AUTO = 41
    const val STRING_MODE_CUSTOM = 42
    const val STRING_MODE_MANUAL = 43
    const val STRING_SECONDS_TO_FREEZE = 44
    const val STRING_SECONDS = 45
    const val STRING_ACTIVATION = 46
    const val STRING_EXPIRATION = 47
    const val STRING_SYSTEM = 48
    const val STRING_ALLOW_ROOT = 49
    const val STRING_INFO = 50
    const val STRING_SUPPORT = 51
    const val STRING_APP_NAME = 52
    const val STRING_LABEL_USER = 53
    const val STRING_HINT_USER = 54
    const val STRING_LABEL_LICENSE = 55
    const val STRING_HINT_LICENSE = 56
    const val STRING_LOGIN_BTN = 57
    const val STRING_OVERLAY_REQ = 58
    const val STRING_ROOT_REQ = 59
    const val STRING_SPLASH_FETCHING = 60
    const val STRING_SPLASH_GRANTED = 61
    const val STRING_UPDATE_TITLE = 62
    const val STRING_UNDERSTOOD = 63
    const val STRING_INCOMPLETE_DATA = 64
    const val STRING_VALIDATION_ERROR_INIT = 65
    const val STRING_ROOT_ACTIVATED = 66
    const val STRING_ROOT_DENIED_TOAST = 67
    const val STRING_OVERLAY_TOAST = 68
    const val STRING_USAGE_TOAST = 69
    const val STRING_FF_NOT_DETECTED = 70
    const val STRING_DEBUGGER_DETECTED = 71
    const val STRING_PLEASE_WAIT = 72
    const val STRING_GAME_TARGET = 73
    const val STRING_FREE_FIRE = 74
    const val STRING_FF_MAX = 75
    const val STRING_QOS_TITLE = 76
    const val STRING_JITTER_LABEL = 77
    const val STRING_DROP_LABEL = 78
    const val STRING_CERT_PIN = 79
    const val STRING_BATTERY_HINT = 80
    const val STRING_SEC_ALERT = 81
    const val STRING_SU = 82
    const val STRING_SU_CMD_ID = 83
    const val STRING_SU_CMD_EXIT = 84
    const val S85 = 85
    const val S86 = 86
    const val S87 = 87
    const val S88 = 88
    const val S89 = 89
    const val S90 = 90
    const val S91 = 91
    const val S92 = 92
    const val S93 = 93
    const val S94 = 94
    const val S95 = 95
    const val S96 = 96
    const val S97 = 97
    const val S98 = 98
    const val S99 = 99
    const val S100 = 100
    const val S101 = 101
    const val S102 = 102
    const val S103 = 103
    const val STRING_PREFS_NAME = 104
    const val STRING_NO_INTERNET = 105
    const val S106 = 106
    const val S107 = 107
    const val S108 = 108
    const val S109 = 109
    const val S110 = 110
    const val S112 = 112
}
