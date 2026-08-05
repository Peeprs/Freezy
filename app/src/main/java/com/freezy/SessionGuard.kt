package com.freezy

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent

/**
 * Manejo de errores fatales de licencia (ban / expiración).
 *
 * Detecta la razón devuelta por el servidor y, cuando aplica, cierra la sesión
 * en automático limpiando las credenciales guardadas y mostrando el motivo.
 */
object SessionGuard {

    fun isBan(message: String): Boolean =
        message.contains("bane", true) ||
            message.contains("banned", true) ||
            message.contains("bloquead", true)

    fun isExpired(message: String): Boolean =
        message.contains("expir", true) || message.contains("expired", true)

    fun clearSession(context: Context) {
        context.getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_logged_in", false)
            .remove("saved_key")
            .remove("saved_username")
            .remove("secure_endpoint")
            .remove("activation_date")
            .remove("expiration_date")
            .apply()

        if (context is LoginActivity) {
            context.clearInputFields()
        }
    }

    // Cierra la sesión en automático y muestra la razón con botón a la pantalla de login.
    fun forceLogout(activity: Activity, title: String, reason: String) {
        clearSession(activity)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton(NativeBridge.getNativeString(NativeBridge.STRING_UNDERSTOOD)) { _, _ ->
                val intent = Intent(activity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                activity.startActivity(intent)
                activity.finish()
            }
            .show()
    }

    // Ya estamos en la pantalla de login: solo muestra la razón y limpia la sesión.
    fun showBlocked(activity: Activity, title: String, reason: String) {
        clearSession(activity)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton(NativeBridge.getNativeString(NativeBridge.STRING_UNDERSTOOD)) { _, _ -> }
            .show()
    }
}
