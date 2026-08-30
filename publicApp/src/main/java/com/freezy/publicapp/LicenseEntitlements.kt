package com.freezy.publicapp

import android.content.Context
import org.json.JSONObject
import java.util.Locale

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
        val savedKey = SecurePrefs.get(context, "saved_key", "")
        val responseKey = firstLicenseKey(response)
        val keyToClassify = responseKey.ifEmpty { savedKey }
        val tierSaysTrial = looksLikeTrial(rawTier)
        val keySaysTrial = looksLikeTrial(keyToClassify)
        val tier = if (explicitTrial || tierSaysTrial || keySaysTrial) {
            TIER_TRIAL
        } else {
            TIER_PAID
        }
        val source = when {
            explicitTrial || explicitPaid || rawTier.isNotEmpty() -> "SERVER"
            responseKey.isNotEmpty() || savedKey.isNotEmpty() -> "LICENSE_KEY"
            else -> "VALID_RESPONSE"
        }

        SecurePrefs.put(context, STORAGE_KEY, tier)
        SecurePrefs.put(context, SOURCE_KEY, source)
        return tier == TIER_PAID
    }

    @JvmStatic
    fun hasPaidFeatures(context: Context): Boolean {
        return when (SecurePrefs.get(context, STORAGE_KEY, "").uppercase(Locale.ROOT)) {
            TIER_PAID -> true
            TIER_TRIAL -> false
            else -> {
                val savedKey = SecurePrefs.get(context, "saved_key", "")
                savedKey.isNotEmpty() && !looksLikeTrial(savedKey)
            }
        }
    }

    private fun looksLikeTrial(value: String): Boolean =
        value.contains("TRIAL", ignoreCase = true) || value.contains("PRUEBA", ignoreCase = true)

    private fun firstTierValue(response: JSONObject): String {
        val keys = listOf("tier", "tipo", "type", "plan", "role", "license_type")
        for (k in keys) {
            val v = response.optString(k, "")
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun firstLicenseKey(response: JSONObject): String {
        val keys = listOf("key", "license", "licencia", "key_string")
        for (k in keys) {
            val v = response.optString(k, "")
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun booleanValue(response: JSONObject, key: String): Boolean? {
        if (!response.has(key)) return null
        return when (val v = response.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() == 1
            is String -> v.equals("true", true) || v == "1"
            else -> null
        }
    }
}
