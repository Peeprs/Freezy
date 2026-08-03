package com.freezy

import android.content.Context

/**
 * SecureLogger — Utilidades para evitar filtrar secretos en los logs.
 * Toda escritura de logs debe pasar por [log], que redacta tokens,
 * firmas HMAC, llaves de licencia y payloads cifrados antes de tocar disco.
 */
object SecureLogger {

    // Campos sensibles en respuestas del servidor: se reemplaza el valor
    // por "***" aunque venga anidado dentro de un JSON.
    private val SENSITIVE_FIELD = Regex(
        """(?i)("(?:session_token|nonce|hmac|iv|encrypted_payload|saved_key|license|key)"\s*:\s*")[^"]*(")"""
    )

    private val HWID_LIKE = Regex("(?i)\\b[0-9a-f]{64}\\b")

    @JvmStatic
    fun redact(message: String): String {
        return message
            .replace(SENSITIVE_FIELD) { m -> m.groupValues[1] + "***" + m.groupValues[2] }
            .replace(HWID_LIKE) { m -> m.value.take(8) + "…" }
    }

    @JvmStatic
    fun log(context: Context, message: String) {
        Logger.log(context, redact(message))
    }
}
