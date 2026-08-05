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
        "io.github.vvb2060.magisk", // forks oficiales ocultadores
        "io.github.huskydg.magisk" // Kitsune (Magisk Delta)
    )

    /** Root con confirmación real (ejecuta `su` interactivo para que el gestor dispare el prompt de permiso). */
    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /** Hay un gestor de superusuario instalado (Magisk/KernelSU/SuperSU...). */
    fun hasRootManager(context: Context?): Boolean = hasRootPackages(context)

    /** Es Kitsune (Magisk Delta/fork). */
    fun hasKitsune(context: Context?): Boolean = hasPackage(context, "io.github.huskydg.magisk")

    private fun hasPackage(context: Context?, pkg: String): Boolean {
        if (context == null) return false
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
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
