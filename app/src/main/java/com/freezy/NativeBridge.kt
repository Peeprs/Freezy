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
}
