package com.freezy

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Teleport Drop Root independiente del VPN.
 *
 * Mientras está activo corta únicamente datagramas salientes compatibles con
 * actualización de posición. Al quitar la regla, el siguiente estado actual
 * sale inmediatamente; no se reproduce una ruta vieja ni se toca tráfico
 * entrante, por lo que los enemigos continúan actualizándose.
 */
object RootTeleportDropController {
    private const val TAG = "RootTeleportDrop"
    private const val CHAIN = "FREEZY_TELE_DROP"

    @Volatile
    var active: Boolean = false
        private set

    fun enable(context: Context, targetPackage: String): Boolean {
        if (!LicenseEntitlements.hasPaidFeatures(context) ||
            !GameCompatibility.inspect(context, targetPackage).supportsAdvancedFeatures) return false
        val uid = try {
            context.packageManager.getApplicationInfo(targetPackage, 0).uid
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo resolver el UID de $targetPackage", e)
            return false
        }
        val enabled = runRootScript(buildScript(uid, true))
        active = enabled
        return enabled
    }

    fun disable(): Boolean {
        val disabled = runRootScript(buildScript(0, false))
        active = false
        return disabled
    }

    private fun buildScript(uid: Int, enable: Boolean): String {
        val cleanupV4 = """
            iptables -D OUTPUT -p udp -j $CHAIN 2>/dev/null || true
            iptables -F $CHAIN 2>/dev/null || true
            iptables -X $CHAIN 2>/dev/null || true
        """.trimIndent()
        val cleanupV6 = """
            if command -v ip6tables >/dev/null 2>&1; then
              ip6tables -D OUTPUT -p udp -j $CHAIN 2>/dev/null || true
              ip6tables -F $CHAIN 2>/dev/null || true
              ip6tables -X $CHAIN 2>/dev/null || true
            fi
        """.trimIndent()
        if (!enable) return "$cleanupV4\n$cleanupV6\nexit 0"

        return """
            $cleanupV4
            $cleanupV6
            iptables -N $CHAIN || exit 30
            iptables -A $CHAIN -m owner ! --uid-owner $uid -j RETURN || exit 31
            iptables -A $CHAIN -p udp --dport 10000:10030 -m length --length 78:228 -j DROP || exit 32
            iptables -A $CHAIN -p udp --dport 20000:20030 -m length --length 78:228 -j DROP || exit 33
            iptables -I OUTPUT 1 -p udp -j $CHAIN || exit 34
            if command -v ip6tables >/dev/null 2>&1; then
              ip6tables -N $CHAIN 2>/dev/null && \
              ip6tables -A $CHAIN -m owner ! --uid-owner $uid -j RETURN && \
              ip6tables -A $CHAIN -p udp --dport 10000:10030 -m length --length 98:248 -j DROP && \
              ip6tables -A $CHAIN -p udp --dport 20000:20030 -m length --length 98:248 -j DROP && \
              ip6tables -I OUTPUT 1 -p udp -j $CHAIN || true
            fi
            iptables -C OUTPUT -p udp -j $CHAIN >/dev/null 2>&1 || exit 35
            exit 0
        """.trimIndent()
    }

    private fun runRootScript(script: String): Boolean {
        return try {
            val process = ProcessBuilder(
                NativeBridge.getNativeString(NativeBridge.STRING_SU),
                "-c",
                script
            ).redirectErrorStream(true).start()
            val completed = process.waitFor(6, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                false
            } else {
                val output = process.inputStream.bufferedReader().readText().take(500)
                val ok = process.exitValue() == 0
                if (!ok) Log.e(TAG, "iptables falló (${process.exitValue()}): $output")
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo configurar Teleport Drop Root", e)
            false
        }
    }
}
