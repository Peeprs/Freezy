package com.freezy

import android.content.Context
import android.util.Log
import com.system.network.ui.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger Seguro — Solo opera en builds de debug.
 *
 * En release (BuildConfig.DEBUG == false):
 *   - log() no escribe nada a disco ni a Logcat
 *   - No se filtra HWID, modelo del teléfono, ni datos de licencia
 *
 * En debug (BuildConfig.DEBUG == true):
 *   - Escribe logs a archivo interno para diagnóstico
 *   - Imprime a Logcat con tag "Freezy"
 *
 * getLogs() y clearLogs() siempre funcionan para mantener la UI
 * del visor de logs operativa en ambos modos.
 */
object Logger {
    private const val LOG_FILE_NAME = "app_logs.txt"
    private const val TAG = "Freezy"

    /**
     * Registra un mensaje SOLO en builds de debug.
     * En release, esta función es un no-op completo — no toca disco ni Logcat.
     */
    fun log(context: Context, message: String) {
        // Gate de seguridad: en release, no filtramos nada
        if (!BuildConfig.DEBUG) return

        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val logMessage = "[$timestamp] $message\n"

            FileWriter(file, true).use { writer ->
                writer.append(logMessage)
            }

            // También imprimir a Logcat en debug para conveniencia
            Log.d(TAG, message)
        } catch (e: Exception) {
            // Solo imprimir stacktrace en debug
            if (BuildConfig.DEBUG) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Obtiene los logs almacenados. Funciona en ambos modos para que
     * el visor de logs en Settings no se rompa.
     */
    fun getLogs(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) {
                file.readText()
            } else {
                if (BuildConfig.DEBUG) {
                    "No hay logs disponibles."
                } else {
                    "Logging deshabilitado en release."
                }
            }
        } catch (e: Exception) {
            "Error al leer logs: ${e.message}"
        }
    }

    /**
     * Elimina los logs almacenados.
     */
    fun clearLogs(context: Context) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Log seguro para datos sensibles (HWID, modelo, etc.)
     * Aplica sanitización adicional incluso en debug.
     */
    fun logSensitive(context: Context, label: String, value: String) {
        if (!BuildConfig.DEBUG) return

        // En debug, mostrar solo los primeros 8 caracteres del valor sensible
        val sanitized = if (value.length > 8) "${value.take(8)}..." else value
        log(context, "$label: $sanitized")
    }
}
