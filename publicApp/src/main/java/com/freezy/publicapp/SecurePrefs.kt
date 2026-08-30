package com.freezy.publicapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Preferencias AES-256-GCM respaldadas por Android Keystore. */
object SecurePrefs {
    private const val PREFIX = "s1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val encryptedKeys = setOf(
        "saved_username",
        "saved_key",
        "activation_date",
        "expiration_date",
        "session_token"
    )

    private fun preferences(context: Context) = context.getSharedPreferences(
        N.a(N.PREFS_NAME),
        Context.MODE_PRIVATE
    )

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = N.a(N.KEYSTORE_ALIAS)
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return PREFIX + Base64.encodeToString(
            cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
    }

    private fun decrypt(value: String): String {
        val data = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
        require(data.size > IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, data.copyOfRange(0, IV_BYTES))
        )
        return String(cipher.doFinal(data.copyOfRange(IV_BYTES, data.size)), Charsets.UTF_8)
    }

    fun put(context: Context, key: String, value: String) {
        runCatching { preferences(context).edit().putString(key, encrypt(value)).apply() }
            .onFailure { if (BuildConfig.DEBUG) it.printStackTrace() }
    }

    fun get(context: Context, key: String, defaultValue: String = ""): String = try {
        val raw = preferences(context).getString(key, null) ?: return defaultValue
        if (raw.startsWith(PREFIX)) decrypt(raw) else raw.also { put(context, key, it) }
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) error.printStackTrace()
        defaultValue
    }

    fun migrateLegacy(context: Context) = encryptedKeys.forEach { get(context, it) }

    fun clearSession(context: Context) {
        preferences(context).edit()
            .remove("saved_username")
            .remove("saved_key")
            .remove("activation_date")
            .remove("expiration_date")
            .remove("session_token")
            .apply()
    }
}
