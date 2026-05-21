package com.freezy

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SecureCrypto — Helper de cifrado seguro para Freezy
 * 
 * Usa AES-256-GCM (Galois/Counter Mode) en lugar de AES/CBC:
 *   - Provee autenticación e integridad de datos (AEAD)
 *   - No requiere padding (inmune a padding oracle attacks)
 *   - El tag de autenticación GCM valida que el ciphertext no fue manipulado
 * 
 * Formato del payload cifrado (hex-encoded):
 *   [IV 12 bytes] + [Ciphertext + AuthTag 16 bytes]
 *   
 * El backend debe enviar iv_hex y encrypted_payload_hex por separado.
 */
object SecureCrypto {

    private const val GCM_TAG_LENGTH_BITS = 128  // 16 bytes de tag
    private const val GCM_IV_LENGTH_BYTES = 12   // 12 bytes de IV (estándar NIST)
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Descifra un payload cifrado con AES-256-GCM.
     *
     * @param keyBytes     La llave AES de 32 bytes (256 bits)
     * @param ivBytes      El IV/Nonce de 12 bytes
     * @param cipherBytes  El ciphertext + AuthTag (los últimos 16 bytes son el tag)
     * @return El texto plano descifrado
     * @throws javax.crypto.AEADBadTagException si el tag de autenticación no coincide
     */
    fun decryptGcm(keyBytes: ByteArray, ivBytes: ByteArray, cipherBytes: ByteArray): String {
        require(keyBytes.size == 32) { "La llave AES debe ser de 32 bytes (256 bits)" }
        require(ivBytes.size == GCM_IV_LENGTH_BYTES) { "El IV GCM debe ser de 12 bytes" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(keyBytes, ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        val decryptedBytes = cipher.doFinal(cipherBytes)

        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * Convierte un string hex a ByteArray.
     * Ejemplo: "4a6f" → byteArrayOf(0x4a, 0x6f)
     */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "El string hex debe tener longitud par" }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
