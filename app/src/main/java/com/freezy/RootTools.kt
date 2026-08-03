package com.freezy

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * RootTools — Detección de root robustecida.
 *
 * Además de probar `su` (que requiere que el usuario acepte el prompt del
 * gestor de superusuario), detecta binarios comunes, gestores típicos
 * (Magisk, SuperSU, KernelSU...) y builds test-keys.
 */
object RootTools {

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/su",
        "/su/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/data/local/su"
    )

    private val ROOT_PACKAGES = arrayOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "me.weishu.kernelsu",
        "com.kingroot.kinguser",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.yellowes.su",
        "io.github.vvb2060.magisk" // forks oficiales ocultadores
    )

    /** Root con confirmación real (ejecuta `su`, puede disparar prompt). */
    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return isRootDeviceHintActive(null)
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            isRootDeviceHintActive(null)
        }
    }

    /**
     * Indicios de dispositivo rooteado sin pedir permisos.
     * Usado por la sección Extras para pintar el estado.
     */
    fun isRootDeviceHintActive(context: Context?): Boolean {
        return hasSuBinary() || isTestKeysBuild() || hasRootPackages(context)
    }

    private fun hasSuBinary(): Boolean {
        return SU_PATHS.any { path ->
            try {
                File(path).exists()
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun isTestKeysBuild(): Boolean {
        val tags = Build.TAGS
        return tags != null && tags.contains("test-keys")
    }

    private fun hasRootPackages(context: Context?): Boolean {
        if (context == null) return false
        val pm = context.packageManager
        return ROOT_PACKAGES.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
