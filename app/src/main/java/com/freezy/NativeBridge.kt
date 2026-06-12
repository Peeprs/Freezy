package com.freezy

object NativeBridge {
    init {
        System.loadLibrary("freezy_net")
    }

    @JvmStatic
    external fun setNoRecoilState(enabled: Boolean)

    @JvmStatic
    external fun setRecoilStrength(strength: Int)

    @JvmStatic
    external fun setFovEnabled(enabled: Boolean)

    @JvmStatic
    external fun setFovRadius(radius: Int)

    @JvmStatic
    external fun registerUiCallback(callback: Any)

    @JvmStatic
    external fun getNativeString(id: Int): String

    @JvmStatic
    external fun getNativeHWID(): String

    @JvmStatic
    external fun setSecurePayload(payload: String)

    @JvmStatic
    external fun getHmacSecret(): String

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
}
