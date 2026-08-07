package com.freezy

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Detección de root robustecida.
 *
 * Además de probar `su` (que requiere que el usuario acepte el prompt del
 * gestor de superusuario), detecta binarios comunes, gestores típicos
 * (Magisk, SuperSU, KernelSU...) y builds test-keys.
 *
 * Todas las rutas y paquetes sensibles vienen ofuscados desde la capa
 * nativa (XOR) para que no sean legibles en el DEX.
 */
object RootTools {

    private fun suPaths(): List<String> =
        NativeBridge.getNativeString(NativeBridge.S96)
            .split(",")
            .filter { it.isNotEmpty() }

    private fun rootPackages(): List<String> =
        NativeBridge.getNativeString(NativeBridge.S97)
            .split(",")
            .filter { it.isNotEmpty() }

    /** Root con confirmación real (ejecuta `su` interactivo para que el gestor dispare el prompt de permiso). */
    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(NativeBridge.getNativeString(NativeBridge.STRING_SU))
            val os = java.io.DataOutputStream(process.outputStream)
            os.writeBytes(NativeBridge.getNativeString(NativeBridge.STRING_SU_CMD_ID))
            os.writeBytes(NativeBridge.getNativeString(NativeBridge.STRING_SU_CMD_EXIT))
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
    fun hasKitsune(context: Context?): Boolean =
        hasPackage(context, rootPackages().firstOrNull { it.contains("huskydg") } ?: "")

    private fun hasPackage(context: Context?, pkg: String): Boolean {
        if (context == null || pkg.isEmpty()) return false
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
        return suPaths().any { path ->
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
        return rootPackages().any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
