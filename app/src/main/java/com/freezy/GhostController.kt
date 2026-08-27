package com.freezy

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Controlador exclusivo de Ghost.
 *
 * Root usa una cadena OUTPUT propia, acotada al UID del juego. No-root usa la
 * bandera Ghost del proxy nativo. Ninguna ruta modifica el estado de Fake Lag.
 */
object GhostController {
    private const val TAG = "GhostController"
    private const val CHAIN = "FREEZY_GHOST"

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var usingRoot: Boolean = false
        private set

    fun enableRoot(context: Context, targetPackage: String): Boolean {
        if (!LicenseEntitlements.hasPaidFeatures(context) ||
            !GameCompatibility.inspect(context, targetPackage).supportsAdvancedFeatures) return false
        val uid = try {
            context.packageManager.getApplicationInfo(targetPackage, 0).uid
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo resolver el UID de $targetPackage", e)
            return false
        }

        val script = buildRootScript(uid, enable = true)
        val applied = runRootScript(script)
        active = applied
        usingRoot = applied
        return applied
    }

    fun disableRoot(context: Context, targetPackage: String): Boolean {
        val uid = try {
            context.packageManager.getApplicationInfo(targetPackage, 0).uid
        } catch (_: Exception) {
            // La limpieza no usa el UID en las reglas actuales, pero conservamos
            // uno válido para que el script sea uniforme.
            0
        }
        val removed = runRootScript(buildRootScript(uid, enable = false))
        active = false
        usingRoot = false
        return removed
    }

    fun enableNoRoot(context: Context, targetPackage: String): Boolean {
        if (!LicenseEntitlements.hasPaidFeatures(context) ||
            !GameCompatibility.inspect(context, targetPackage).supportsAdvancedFeatures) return false
        AntigravityFirewall.setGhostActive(true)
        active = true
        usingRoot = false
        return true
    }

    fun disableNoRoot() {
        AntigravityFirewall.setGhostActive(false)
        active = false
        usingRoot = false
    }

    private fun buildRootScript(uid: Int, enable: Boolean): String {
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

        // Limitar Ghost a los puertos de posición evita cortar disparos, daño y
        // confirmaciones que viajan por otros flujos del juego.
        return """
            $cleanupV4
            $cleanupV6
            iptables -N $CHAIN || exit 20
            iptables -A $CHAIN -m owner ! --uid-owner $uid -j RETURN || exit 21
            iptables -A $CHAIN -p udp --dport 10000:10030 -m length --length 78:228 -j DROP || exit 22
            iptables -A $CHAIN -p udp --dport 20000:20030 -m length --length 78:228 -j DROP || exit 23
            iptables -I OUTPUT 1 -p udp -j $CHAIN || exit 24
            if command -v ip6tables >/dev/null 2>&1; then
              ip6tables -N $CHAIN 2>/dev/null && \
              ip6tables -A $CHAIN -m owner ! --uid-owner $uid -j RETURN && \
              ip6tables -A $CHAIN -p udp --dport 10000:10030 -m length --length 98:248 -j DROP && \
              ip6tables -A $CHAIN -p udp --dport 20000:20030 -m length --length 98:248 -j DROP && \
              ip6tables -I OUTPUT 1 -p udp -j $CHAIN || true
            fi
            iptables -C OUTPUT -p udp -j $CHAIN >/dev/null 2>&1 || exit 25
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
                Log.e(TAG, "Timeout configurando la cadena $CHAIN")
                false
            } else {
                val output = process.inputStream.bufferedReader().readText().take(500)
                val ok = process.exitValue() == 0
                if (!ok) Log.e(TAG, "iptables falló (${process.exitValue()}): $output")
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo ejecutar la ruta root", e)
            false
        }
    }
}
