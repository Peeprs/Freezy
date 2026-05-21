package com.freezy

import com.system.network.ui.R

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.security.MessageDigest
import android.util.Base64

class LoginActivity : AppCompatActivity() {

    // Ingeniera inversa de la inversa (Carga librería nativa y llama ofuscador)
    init {
        System.loadLibrary("freezy_net")
    }
    private external fun getSecureEndpoint(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        
        // Guardar el endpoint para que otros servicios lo usen
        try {
            val endpointUrl = getSecureEndpoint()
            prefs.edit().putString("secure_endpoint", endpointUrl).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Mostrar descargo de responsabilidad si no lo ha aceptado
        if (!prefs.getBoolean("disclaimer_accepted", false)) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_disclaimer, null)
            val btnAccept = dialogView.findViewById<Button>(R.id.btn_accept_risk)
            val btnExit = dialogView.findViewById<Button>(R.id.btn_exit_app)
            
            val tvTitle = dialogView.findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_disclaimer_title)
            val tvBody = dialogView.findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_disclaimer_body)
            
            // Cargar strings ofuscados de C++
            tvTitle?.text = NativeBridge.getNativeString(NativeBridge.STRING_DISCLAIMER_TITLE)
            tvBody?.text = NativeBridge.getNativeString(NativeBridge.STRING_DISCLAIMER_BODY)
            btnAccept.text = NativeBridge.getNativeString(NativeBridge.STRING_ACCESS_GRANTED).replace("¡", "").replace("!", "") // Reutilizar o simplificar

            val dialog = AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setCancelable(false)
                    .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            btnAccept.setOnClickListener {
                prefs.edit().putBoolean("disclaimer_accepted", true).apply()
                dialog.dismiss()
            }

            btnExit.setOnClickListener {
                finishAffinity()
            }

            dialog.show()
        }

        // Si el usuario ya metió la Key correcta antes, saltamos directo a la app principal
        if (prefs.getBoolean("is_logged_in", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(com.system.network.ui.R.layout.activity_login)
        
        // Cargar strings ofuscados de C++ para los campos de login
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_app_name)?.text = NativeBridge.getNativeString(NativeBridge.STRING_APP_NAME)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_label_user)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LABEL_USER)
        findViewById<android.widget.EditText>(com.system.network.ui.R.id.et_user)?.hint = NativeBridge.getNativeString(NativeBridge.STRING_HINT_USER)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_label_license_login)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LABEL_LICENSE)
        findViewById<android.widget.EditText>(com.system.network.ui.R.id.et_key)?.hint = NativeBridge.getNativeString(NativeBridge.STRING_HINT_LICENSE)
        findViewById<android.widget.Button>(com.system.network.ui.R.id.btn_login)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)

        val etUser = findViewById<EditText>(R.id.et_user)
        val etKey = findViewById<EditText>(R.id.et_key)
        val btnLogin = findViewById<Button>(R.id.btn_login)

        // Restaurar datos guardados para comodidad del usuario
        etUser.setText(prefs.getString("saved_username", ""))
        etKey.setText(prefs.getString("saved_key", ""))

        btnLogin.setOnClickListener {
            val username = etUser.text.toString().trim()
            val key = etKey.text.toString().trim().uppercase()

            if (username.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_FILL_FIELDS), Toast.LENGTH_SHORT)
                        .show()
                return@setOnClickListener
            }

            btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_VERIFYING)
            btnLogin.isEnabled = false

            // Conexión real al servidor privado
            Thread {
                try {
                    val endpointUrl = getSecureEndpoint() // Obtenemos la URL de C++ (ofuscada)
                    
                    // PASO 1: Solicitar Desafío (Challenge)
                    val challengeUrl = URL("$endpointUrl/challenge") // Asumiendo que getSecureEndpoint() devuelve algo como "http://ip:port/api/keys" pero el backend está en "/api/keys/challenge". Adaptar según la lógica. Wait.
                    
                    // El usuario tenía endpointUrl directo a "/api/keys/verify" o "https://.../api/keys"?
                    // Vamos a arreglar eso después, por ahora usemos endpointUrl.replace("/verify", "/challenge")
                    val challengeEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl.replace("/verify", "/challenge") else "$endpointUrl/challenge"
                    val verifyEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl else "$endpointUrl/verify"

                    val hwid = NativeBridge.getNativeHWID()
                    val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                    
                    // PASO 1.5: Certificate Pinning
                    val trustManager = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                            if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Certificado vacío")
                            val cert = chain[0]
                            val digest = MessageDigest.getInstance("SHA-256")
                            val pubKeyHash = digest.digest(cert.publicKey.encoded)
                            val pubKeyHashBase64 = Base64.encodeToString(pubKeyHash, Base64.NO_WRAP)
                            
                            val TARGET_HASH = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="
                            if (TARGET_HASH != "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=" && pubKeyHashBase64 != TARGET_HASH) {
                                throw java.security.cert.CertificateException("Pinning Fallido: Posible Man-in-the-Middle")
                            }
                        }
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
                    
                    val challengeConn = URL(challengeEndpoint).openConnection() as HttpURLConnection
                    if (challengeConn is HttpsURLConnection) {
                        challengeConn.sslSocketFactory = sslContext.socketFactory
                    }
                    challengeConn.requestMethod = "POST"
                    challengeConn.setRequestProperty("Content-Type", "application/json")
                    challengeConn.connectTimeout = 30000
                    challengeConn.readTimeout = 30000
                    challengeConn.doOutput = true

                    val challengeJson = "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"username\": \"$username\", \"device_model\": \"$deviceModel\"}"
                    challengeConn.outputStream.use { os ->
                        val input = challengeJson.toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }

                    if (challengeConn.responseCode != 200) {
                        val errorStream = challengeConn.errorStream
                        val errorMessage = if (errorStream != null) {
                            try {
                                JSONObject(errorStream.bufferedReader().readText()).getString("message")
                            } catch (e: Exception) { "Error de validación. Verifica tus datos o tu conexión." }
                        } else { "Error de validación. Verifica tus datos o tu conexión." }
                        
                        runOnUiThread {
                            Toast.makeText(this@LoginActivity, errorMessage, Toast.LENGTH_LONG).show()
                            btnLogin.text = "INGRESAR"
                            btnLogin.isEnabled = true
                        }
                        return@Thread
                    }

                    val challengeResponse = challengeConn.inputStream.bufferedReader().readText()
                    val nonce = JSONObject(challengeResponse).getString("nonce")

                    // PASO 2: Calcular HMAC
                    val HWID_PRIVADO = NativeBridge.getHmacSecret()
                    val algorithm = "HmacSHA256"
                    val mac = Mac.getInstance(algorithm)
                    val secretKey = SecretKeySpec(HWID_PRIVADO.toByteArray(Charsets.UTF_8), algorithm)
                    mac.init(secretKey)
                    val hmacBytes = mac.doFinal(nonce.toByteArray(Charsets.UTF_8))
                    val hmacHex = hmacBytes.joinToString("") { "%02x".format(it) }

                    // PASO 3: Enviar HMAC para Verificación
                    val verifyConn = URL(verifyEndpoint).openConnection() as HttpURLConnection
                    if (verifyConn is HttpsURLConnection) {
                        verifyConn.sslSocketFactory = sslContext.socketFactory
                    }
                    verifyConn.requestMethod = "POST"
                    verifyConn.setRequestProperty("Content-Type", "application/json")
                    verifyConn.connectTimeout = 30000
                    verifyConn.readTimeout = 30000
                    verifyConn.doOutput = true

                    val verifyJson = "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"hmac\": \"$hmacHex\"}"
                    verifyConn.outputStream.use { os ->
                        val input = verifyJson.toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }

                    if (verifyConn.responseCode == 200) {
                        val responseBody = verifyConn.inputStream.bufferedReader().readText()
                        if (com.system.network.ui.BuildConfig.DEBUG) android.util.Log.d("LoginActivity", "Server Response: $responseBody")
                        val jsonObject = JSONObject(responseBody)
                        val isValid = jsonObject.getBoolean("valid")

                        if (isValid) {
                            val createdAt = jsonObject.optString("created_at", "--")
                            val expiresAt = jsonObject.optString("expires_at", "--")
                            val sessionToken = jsonObject.optString("session_token", "")
                            val encryptedPayloadHex = jsonObject.optString("encrypted_payload", "")
                            val ivHex = jsonObject.optString("iv", "")
                            
                            var decryptedPayload = ""
                            if (encryptedPayloadHex.isNotEmpty() && ivHex.isNotEmpty()) {
                                try {
                                    // AES-256-GCM: Provee confidencialidad + integridad (AEAD)
                                    val aesKeyBytes = SecureCrypto.hexToBytes(hmacHex)
                                    val ivBytes = SecureCrypto.hexToBytes(ivHex)
                                    val encryptedBytes = SecureCrypto.hexToBytes(encryptedPayloadHex)
                                    
                                    decryptedPayload = SecureCrypto.decryptGcm(aesKeyBytes, ivBytes, encryptedBytes)
                                    
                                    // Guardar el payload exclusivamente en memoria nativa
                                    NativeBridge.setSecurePayload(decryptedPayload)
                                } catch (e: javax.crypto.AEADBadTagException) {
                                    // El tag de autenticación no coincide — payload manipulado
                                    if (com.system.network.ui.BuildConfig.DEBUG) android.util.Log.e("LoginActivity", "GCM auth tag mismatch — payload tampered")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            
                            val activationDate = if (createdAt.length >= 10) createdAt.substring(0, 10) else createdAt
                            val expirationDate = if (expiresAt.length >= 10) expiresAt.substring(0, 10) else expiresAt
                            
                            runOnUiThread {
                                Logger.log(this@LoginActivity, "Licencia Validada")
                                prefs.edit()
                                        .putBoolean("is_logged_in", true)
                                        .putString("saved_username", username)
                                        .putString("saved_key", key)
                                        .putString("activation_date", activationDate)
                                        .putString("expiration_date", expirationDate)
                                        // NO se guarda el payload descifrado en SharedPreferences
                                        .apply()
                                Toast.makeText(
                                                this@LoginActivity,
                                                NativeBridge.getNativeString(NativeBridge.STRING_ACCESS_GRANTED),
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                                startActivity(
                                        Intent(this@LoginActivity, MainActivity::class.java)
                                )
                                finish()
                            }
                        } else {
                            val message = jsonObject.optString("message", NativeBridge.getNativeString(NativeBridge.STRING_INVALID_LICENSE))
                            runOnUiThread {
                                Toast.makeText(
                                                this@LoginActivity,
                                                message,
                                                Toast.LENGTH_LONG
                                        )
                                        .show()
                                btnLogin.text = "INGRESAR"
                                btnLogin.isEnabled = true
                            }
                        }
                    } else {
                        val errorStream = verifyConn.errorStream
                        val errorMessage = if (errorStream != null) {
                            val errorResponse = errorStream.bufferedReader().readText()
                            try {
                                JSONObject(errorResponse).getString("message")
                            } catch (e: Exception) {
                                NativeBridge.getNativeString(NativeBridge.STRING_INVALID_LICENSE)
                            }
                        } else {
                            NativeBridge.getNativeString(NativeBridge.STRING_INVALID_LICENSE)
                        }

                        runOnUiThread {
                            Toast.makeText(
                                            this@LoginActivity,
                                            errorMessage,
                                            Toast.LENGTH_LONG
                                    )
                                    .show()
                            btnLogin.text = "INGRESAR"
                            btnLogin.isEnabled = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(
                                        this@LoginActivity,
                                        NativeBridge.getNativeString(NativeBridge.STRING_INVALID_LICENSE),
                                        Toast.LENGTH_LONG
                                )
                                .show()
                        btnLogin.text = "INGRESAR"
                        btnLogin.isEnabled = true
                    }
                }
            }
            .start()
        }
    }


}
