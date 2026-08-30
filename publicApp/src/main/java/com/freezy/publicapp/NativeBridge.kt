package com.freezy.publicapp

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Punto de entrada JNI con ofuscación de strings nativas por XOR. */
internal object N {
    init {
        System.loadLibrary("freezy_public")
    }

    @JvmStatic external fun a(id: Int): String
    @JvmStatic private external fun b(androidId: String, hardwareInfo: String): String
    @JvmStatic external fun c(signerDigest: String): Boolean
    @JvmStatic external fun d(service: Any, fd: Int)
    @JvmStatic external fun e()
    @JvmStatic external fun f(active: Boolean)
    @JvmStatic external fun g()

    fun getHwid(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN_ANDROID_ID"
        val hardwareInfo = listOf(
            Build.BOARD,
            Build.BRAND,
            Build.DEVICE,
            Build.HARDWARE,
            Build.MODEL,
            Build.PRODUCT
        ).joinToString("-")
        return b(androidId, hardwareInfo)
    }

    const val ENDPOINT = 1
    const val HMAC_SECRET = 2
    const val PREFS_NAME = 3
    const val KEYSTORE_ALIAS = 4
    const val CERT_PINS = 5
    const val WHATSAPP_URL = 6
    const val TIKTOK_URL = 7
    const val DISCLAIMER_BODY = 8
    const val RELEASE_SIGNER = 9
    const val APP_NAME = 10
    const val LOGIN_SUBTITLE = 11
    const val LABEL_USER = 12
    const val HINT_USER = 13
    const val LABEL_LICENSE = 14
    const val HINT_LICENSE = 15
    const val BTN_LOGIN = 16
    const val BTN_GETKEY = 17
    const val OFFICIAL_CHANNELS = 18
    const val BADGE_VALIDATING = 19
    const val TITLE_VALIDATING = 20
    const val STATUS_VALIDATING = 21
    const val BTN_RETURN = 22
    const val BADGE_DISCLAIMER = 23
    const val TITLE_DISCLAIMER = 24
    const val SUBTITLE_DISCLAIMER = 25
    const val BTN_ACCEPT_RISK = 26
    const val BTN_EXIT_APP = 27
    const val BADGE_UPDATE = 28
    const val SUBTITLE_UPDATE = 29
    const val HINT_UPDATE = 30
    const val BTN_UPDATE_WA = 31
    const val TITLE_UPDATE_TT = 32
    const val SUBTITLE_UPDATE_TT = 33
    const val BADGE_VALID = 34
    const val TITLE_VALID = 35
    const val STATUS_VALID = 36
    const val BADGE_EXPIRED = 37
    const val TITLE_EXPIRED = 38
    const val STATUS_EXPIRED = 39
    const val BADGE_NETWORK = 40
    const val TITLE_NETWORK = 41
    const val STATUS_NETWORK = 42
    const val BADGE_MAINTENANCE = 43
    const val TITLE_MAINTENANCE = 44
    const val STATUS_MAINTENANCE = 45
    const val BADGE_DENIED = 46
    const val TITLE_DENIED = 47
    const val STATUS_DENIED = 48
    const val STATUS_DENIED_DESC = 48
    const val DESC_WA = 49
    const val DESC_TT = 50
    const val SU = 51
    const val SU_EXIT = 52
    const val IPTABLES_CLEAN_1 = 53
    const val IPTABLES_CLEAN_2 = 54
    const val IPTABLES_CLEAN_3 = 55
    const val IPTABLES_CREATE = 56
    const val IPTABLES_INSERT = 57
    const val IPTABLES_DROP = 58
    const val PKG_FF_MAX = 59
    const val PKG_FF_NORMAL = 60
}
