package com.freezy

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Fuente única para las capacidades asociadas a la licencia.
 *
 * El valor confirmado por el servidor se conserva cifrado. TRIAL solo se
 * asigna ante una marca explícita del servidor o de la clave; la duración de
 * la licencia nunca determina su categoría.
 */
object LicenseEntitlements {
    const val STORAGE_KEY = "license_tier"
    const val SOURCE_KEY = "license_tier_source"

    private const val TIER_TRIAL = "TRIAL"
    private const val TIER_PAID = "PAID"

    @JvmStatic
    fun updateFromServer(context: Context, response: JSONObject): Boolean {
        val explicitTrial = booleanValue(response, "is_trial") == true ||
            booleanValue(response, "trial") == true
        val explicitPaid = booleanValue(response, "is_paid") == true ||
            booleanValue(response, "premium") == true

        val rawTier = firstTierValue(response)
        val savedKey = SecurePrefs.getSecureString(context, "saved_key")
        val responseKey = firstLicenseKey(response)
        val keyToClassify = responseKey.ifEmpty { savedKey }
        val tierSaysTrial = looksLikeTrial(rawTier)
        val keySaysTrial = looksLikeTrial(keyToClassify)
        val tier = if (explicitTrial || tierSaysTrial || keySaysTrial) {
            TIER_TRIAL
        } else {
            // Una licencia válida que no viene marcada como TRIAL es pagada.
            // Esto cubre licencias de creador, vitalicias y planes cortos.
            TIER_PAID
        }
        val source = when {
            explicitTrial || explicitPaid || rawTier.isNotEmpty() -> "SERVER"
            responseKey.isNotEmpty() || savedKey.isNotEmpty() -> "LICENSE_KEY"
            else -> "VALID_RESPONSE"
        }

        SecurePrefs.putSecureString(context, STORAGE_KEY, tier)
        SecurePrefs.putSecureString(context, SOURCE_KEY, source)
        return tier == TIER_PAID
    }

    @JvmStatic
    fun hasPaidFeatures(context: Context): Boolean {
        return when (SecurePrefs.getSecureString(context, STORAGE_KEY).uppercase(Locale.ROOT)) {
            TIER_PAID -> true
            TIER_TRIAL -> false
            else -> {
                val savedKey = SecurePrefs.getSecureString(context, "saved_key")
                savedKey.isNotEmpty() && !looksLikeTrial(savedKey)
            }
        }
    }

    @JvmStatic
    fun isTrial(context: Context): Boolean = !hasPaidFeatures(context)

    private fun firstTierValue(root: JSONObject): String {
        val keys = arrayOf(
            "license_type", "licenseType", "license_plan", "plan", "tier",
            "subscription_type", "type"
        )
        val containers = arrayOf(
            root,
            root.optJSONObject("license"),
            root.optJSONObject("subscription"),
            root.optJSONObject("data")
        )
        for (container in containers) {
            if (container == null) continue
            for (key in keys) {
                val value = container.optString(key, "").trim()
                if (value.isNotEmpty()) return value
            }
        }
        val licenseValue = root.opt("license")
        return if (licenseValue is String) licenseValue.trim() else ""
    }

    private fun firstLicenseKey(root: JSONObject): String {
        val keys = arrayOf("license_key", "licenseKey", "key")
        val containers = arrayOf(root, root.optJSONObject("license"), root.optJSONObject("data"))
        for (container in containers) {
            if (container == null) continue
            for (key in keys) {
                val value = container.optString(key, "").trim()
                if (value.isNotEmpty()) return value
            }
        }
        return ""
    }

    private fun looksLikeTrial(value: String): Boolean {
        return value.contains("TRIAL", true) ||
            value.contains("PRUEBA", true) ||
            value.equals("FREE", true)
    }

    private fun booleanValue(root: JSONObject, key: String): Boolean? {
        val containers = arrayOf(root, root.optJSONObject("license"), root.optJSONObject("data"))
        for (container in containers) {
            if (container == null || !container.has(key)) continue
            val value = container.opt(key)
            return when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> value.equals("true", true) || value == "1"
                else -> null
            }
        }
        return null
    }

}
