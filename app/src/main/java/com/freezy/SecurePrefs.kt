package com.freezy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SecurePrefs — Preferencias cifradas para datos sensibles.
 *
 * Usa AES-256-GCM con una clave simétrica que vive SOLO en el
 * AndroidKeyStore (hardware/software-backed del dispositivo):
 * la clave nunca aparece en el código ni en archivos de la app.
 *
 * Formato en disco: "s1:" + Base64( IV(12) || ciphertext || tag(16) )
 * Los valores sin prefijo se consideran texto plano legado y se
 * migran a cifrado en la primera lectura.
 */
object SecurePrefs {
    private val KEYSTORE_ALIAS by lazy { NativeBridge.getNativeString(NativeBridge.S107) }
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PREFIX = "s1:"

    private const val KEY_HWID_SALT = "hwid_salt"

    /** Claves actualmente protegidas con cifrado. */
    private val ENCRYPTED_KEYS = setOf(
        "saved_key",
        "saved_username",
        "secure_endpoint",
        "server_base_url",
        "activation_date",
        "expiration_date",
        "session_token"
    )

    private fun getSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded.substring(PREFIX.length), Base64.NO_WRAP)
        val iv = data.copyOfRange(0, GCM_IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plainBytes = cipher.doFinal(data.copyOfRange(GCM_IV_BYTES, data.size))
        return String(plainBytes, Charsets.UTF_8)
    }

    /** Guarda un dato sensible cifrado. */
    @JvmStatic
    fun putSecureString(context: Context, key: String, value: String) {
        try {
            context.getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
                .edit()
                .putString(key, encrypt(value))
                .apply()
        } catch (e: Exception) {
            if (com.system.network.ui.BuildConfig.DEBUG) e.printStackTrace()
        }
    }

    /**
     * Lee un dato sensible. Si el valor almacenado es texto plano legado,
     * lo migra automáticamente a cifrado antes de devolverlo.
     */
    @JvmStatic
    fun getSecureString(context: Context, key: String, defaultValue: String = ""): String {
        return try {
            val prefs = context.getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
            val raw = prefs.getString(key, null) ?: return defaultValue
            val valStr = if (raw.startsWith(PREFIX)) {
                decrypt(raw)
            } else {
                // Migración en caliente: reescribir cifrado
                try {
                    prefs.edit().putString(key, encrypt(raw)).apply()
                } catch (e: Exception) {
                    if (com.system.network.ui.BuildConfig.DEBUG) e.printStackTrace()
                }
                raw
            }
            if (key == "secure_endpoint" && valStr.contains("onrender.com")) {
                return defaultValue
            }
            valStr
        } catch (e: Exception) {
            if (com.system.network.ui.BuildConfig.DEBUG) e.printStackTrace()
            defaultValue
        }
    }

    /**
     * Salt aleatorio por dispositivo para el HWID (punto anti-clonación).
     * Se genera una sola vez con SecureRandom y se conserva cifrado.
     */
    @JvmStatic
    fun getOrCreateHwidSalt(context: Context): String {
        val existing = getSecureString(context, KEY_HWID_SALT)
        if (existing.isNotEmpty()) return existing
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        val salt = bytes.joinToString("") { "%02x".format(it) }
        putSecureString(context, KEY_HWID_SALT, salt)
        return salt
    }

    /**
     * Migración de una pasada para valores sensibles escritos antes del
     * cifrado (logout no los borra). Recorre las claves conocidas y las
     * reescribe cifradas si están en texto plano.
     */
    @JvmStatic
    fun migrateLegacy(context: Context) {
        ENCRYPTED_KEYS.forEach { getSecureString(context, it) }
    }
}
