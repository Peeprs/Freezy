package com.freezy

object NativeBridge {
    init {
        System.loadLibrary("ncx")
    }

    @JvmStatic external fun setNativeMaxDesyncMs(ms: Long)

    @JvmStatic external fun setNativeJitterMs(ms: Int)

    @JvmStatic external fun setNativeDropProbability(probability: Int)

    /** Activa el retardo selectivo para payloads UDP salientes de 50 a 150 bytes. */
    @JvmStatic external fun setSelectiveUdpDelay(active: Boolean, delayMs: Int)

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

    /** Activa/desactiva Aim Visible (selección por FOV y fijación del collider de cabeza). */
    @JvmStatic external fun setAimVisible(active: Boolean): Boolean

    /** Selecciona el bone objetivo: 0 Head, 1 Neck, 2 Root, 3 Hip, 4 Foot. */
    @JvmStatic external fun setAimbotTarget(target: Int)

    /** Configura el radio de Aim Visible en píxeles. */
    @JvmStatic external fun setAimVisibleFov(pixels: Int)

    /** Aimbot de cámara; no modifica ni retrasa tráfico de red. */
    @JvmStatic external fun setCameraAimbot(active: Boolean): Boolean

    /** Redirige el disparo hacia el enemigo visible más cercano mientras se dispara. */
    @JvmStatic external fun setSilentAim(active: Boolean): Boolean

    /** Activa/desactiva Enemy Pull después de seleccionar una dirección. */
    @JvmStatic external fun setEnemyPull(active: Boolean): Boolean

    /** Dirección de Enemy Pull: 0 Ninguna, 1 Arriba, 2 Abajo, 3 Izquierda, 4 Derecha. */
    @JvmStatic external fun setEnemyPullDirection(direction: Int)

    /** Eleva y mantiene al jugador mediante la posición segura de su Transform Root. */
    @JvmStatic external fun setFlyHack(active: Boolean): Boolean

    /** Fuerza el atributo NoReload del jugador local. */
    @JvmStatic external fun setNoReload(active: Boolean): Boolean

    /** Cierra el helper root persistente y descarta el PID de la partida anterior. */
    @JvmStatic external fun shutdownMemoryAccess()

    /** Devuelve un diagnóstico detallado del acceso a memoria (para depurar fallos) */
    @JvmStatic external fun getGameMemoryDiagnostics(pid: Int): String

    /** Lee memoria del juego */
    @JvmStatic external fun readGameMemory(pid: Int, address: Long, size: Int): ByteArray?

    /** Escribe memoria del juego */
    @JvmStatic external fun writeGameMemory(pid: Int, address: Long, value: ByteArray): Boolean

    /** Tamaño de pantalla (para el FOV y W2S del ESP) */
    @JvmStatic external fun setScreenSize(w: Int, h: Int)

    // ============ Config nativa ============

    /** Ruta del helper ffmem (para lecturas rápidas como root) */
    @JvmStatic external fun setMemoryHelperPath(path: String)

    /** Ancho de los punteros del juego: 4 (emulador) u 8 (teléfono 64-bit) */
    @JvmStatic external fun setPointerWidth(width: Int)

    /** Diagnóstico paso a paso de la cadena de punteros IL2CPP */
    @JvmStatic external fun getChainDiagnostics(pid: Int): String

    /** Snapshot directo a FloatArray sin overhead de JSON ni recolección de basura (ultra rápido con flags) */
    @JvmStatic external fun getEspSnapshotDirect(pid: Int, outData: FloatArray, flags: Int): Int

    /** Posiciones relativas y rotadas para el minimapa del overlay. */
    @JvmStatic external fun getRadarSnapshot(pid: Int, outData: FloatArray): Int

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
    const val S113 = 113
    const val S114 = 114
    const val S115 = 115
    const val S116 = 116
    const val S117 = 117
    const val S118 = 118
    const val S119 = 119
    const val S120 = 120
    const val S121 = 121
    const val S122 = 122
    const val S123 = 123
    const val S124 = 124
    const val S125 = 125
    const val S126 = 126
    const val S127 = 127
    const val S128 = 128
    const val S129 = 129
    const val S130 = 130
    const val S131 = 131
    const val S132 = 132
    const val S133 = 133
    const val S134 = 134
    const val S135 = 135
    const val S136 = 136
    const val S137 = 137
    const val S138 = 138
    const val S139 = 139
    const val S140 = 140
    const val S141 = 141
    const val S142 = 142
    const val S143 = 143
    const val S144 = 144
    const val S145 = 145
    const val S146 = 146
    const val S147 = 147
    const val S148 = 148
    const val S149 = 149
    const val S150 = 150
    const val S151 = 151
    const val S152 = 152
    const val S153 = 153
    const val S154 = 154
    const val S155 = 155
    const val S156 = 156
    const val S157 = 157
    const val S158 = 158
    const val S159 = 159
    const val S160 = 160
    const val S161 = 161
    const val S162 = 162
    const val S163 = 163
    const val S164 = 164
    const val S165 = 165
    const val S166 = 166
    const val S167 = 167
    const val S168 = 168
    const val S169 = 169
    const val S170 = 170
    const val S171 = 171
    const val S172 = 172
    const val S173 = 173
    const val S174 = 174
    const val S175 = 175
    const val S176 = 176
    const val S177 = 177
    const val S178 = 178
    const val S179 = 179
    const val S180 = 180
    const val S181 = 181
    const val S182 = 182
    const val S183 = 183
    const val S184 = 184
    const val S185 = 185
    const val S186 = 186
    const val S187 = 187
    const val S188 = 188
    const val S189 = 189
    const val S190 = 190
    const val S191 = 191
    const val S192 = 192
    const val S193 = 193
    const val S194 = 194
    const val S195 = 195
    const val S196 = 196
    const val S197 = 197
    const val S198 = 198
    const val S199 = 199
    const val S200 = 200
    const val S201 = 201
    const val S202 = 202
    const val S203 = 203
    const val S204 = 204
    const val S205 = 205
    const val S206 = 206
    const val S207 = 207
    const val S208 = 208
    const val S209 = 209
    const val S210 = 210
    const val S211 = 211
    const val S212 = 212
    const val S213 = 213
    const val S214 = 214
    const val S215 = 215
    const val S216 = 216
    const val S217 = 217
    const val S218 = 218
    const val S219 = 219
    const val S220 = 220
    const val S221 = 221
    const val S222 = 222
    const val S223 = 223
    const val S224 = 224
    const val S225 = 225
    const val S226 = 226
    const val S227 = 227
    const val S228 = 228
    const val S229 = 229
    const val S230 = 230
    const val S231 = 231
    const val S232 = 232
    const val S233 = 233
    const val S234 = 234
    const val S235 = 235
    const val S236 = 236
    const val S237 = 237
    const val S238 = 238
    const val S239 = 239
    const val S240 = 240
    const val S241 = 241
    const val S242 = 242
    const val S243 = 243
    const val S244 = 244
    const val S245 = 245
    const val S246 = 246
}
