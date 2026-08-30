package com.freezy

import com.system.network.ui.R

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import android.net.Uri
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LoginActivity : AppCompatActivity() {

    private enum class VerificationState { VALIDATING, VALID, EXPIRED, NETWORK_ERROR, MAINTENANCE, INVALID }

    // Ingeniera inversa de la inversa (Carga librería nativa y llama ofuscador)
    init {
        System.loadLibrary("ncx")
    }
    private external fun getSecureEndpoint(): String

    override fun onResume() {
        super.onResume()
        AppUpdateManager.check(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Rechazar APKs re-firmadas (crack por apktool/MT Manager)
        SignatureGuard.verify(this)

        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)

        // Migrar a cifrado cualquier valor sensible guardado por versiones viejas
        SecurePrefs.migrateLegacy(this)

        // Guardar el endpoint (cifrado) para que otros servicios lo usen
        try {
            val endpointUrl = try {
                getSecureEndpoint()
            } catch (e: Throwable) {
                NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT)
            }.ifEmpty { NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT) }
            SecurePrefs.putSecureString(this, "secure_endpoint", endpointUrl)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Mostrar descargo de responsabilidad si no lo ha aceptado
        val previewDisclaimer = com.system.network.ui.BuildConfig.DEBUG &&
            intent.getBooleanExtra("preview_disclaimer_modal", false)
        val previewVerificationState = if (com.system.network.ui.BuildConfig.DEBUG) {
            intent.getStringExtra("preview_verification_state")
        } else null
        val previewLogin = com.system.network.ui.BuildConfig.DEBUG &&
            intent.getBooleanExtra("preview_login_screen", false)
        if (!prefs.getBoolean("disclaimer_accepted", false) || previewDisclaimer) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_disclaimer, null)
            val btnAccept = dialogView.findViewById<TextView>(R.id.btn_accept_risk)
            val btnExit = dialogView.findViewById<TextView>(R.id.btn_exit_app)
            val tvBadge = dialogView.findViewById<TextView>(R.id.tv_disclaimer_badge)
            val tvTitle = dialogView.findViewById<TextView>(R.id.tv_disclaimer_title)
            val tvSubtitle = dialogView.findViewById<TextView>(R.id.tv_disclaimer_subtitle)
            val tvBody = dialogView.findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_disclaimer_body)
            
            // Cargar strings ofuscados de C++
            tvBadge?.text = NativeBridge.getNativeString(NativeBridge.STRING_BADGE_IMPORTANT_NOTICE)
            tvTitle?.text = NativeBridge.getNativeString(NativeBridge.STRING_DISCLAIMER_HEADER)
            tvSubtitle?.text = NativeBridge.getNativeString(NativeBridge.STRING_DISCLAIMER_SUBTITLE)
            tvBody?.text = NativeBridge.getNativeString(NativeBridge.STRING_DISCLAIMER_BODY)
            btnAccept?.text = NativeBridge.getNativeString(NativeBridge.STRING_BTN_ACCEPT_CONTINUE)
            btnExit?.text = NativeBridge.getNativeString(NativeBridge.STRING_BTN_EXIT_DECLINE)

            val dialog = AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setCancelable(false)
                    .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            btnAccept.setOnClickListener {
                if (!previewDisclaimer) prefs.edit().putBoolean("disclaimer_accepted", true).apply()
                dialog.dismiss()
            }

            btnExit.setOnClickListener {
                finishAffinity()
            }

            dialog.show()
            dialog.setCanceledOnTouchOutside(false)
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.82f }
                setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }

        setContentView(com.system.network.ui.R.layout.activity_login)

        val layoutSplash = findViewById<android.view.View>(com.system.network.ui.R.id.layout_splash)
        val layoutLogin = findViewById<android.view.View>(com.system.network.ui.R.id.layout_login)
        val tvSplashStatus = findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_splash_status)

        // Cargar strings ofuscados de C++ para los campos de login (Inicializar siempre primero)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_app_name)?.text = NativeBridge.getNativeString(NativeBridge.STRING_APP_NAME)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_login_subtitle)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_SUBTITLE)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_label_user)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LABEL_USER)
        findViewById<android.widget.EditText>(com.system.network.ui.R.id.et_user)?.hint = NativeBridge.getNativeString(NativeBridge.STRING_HINT_USER)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_label_license_login)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LABEL_LICENSE)
        findViewById<android.widget.EditText>(com.system.network.ui.R.id.et_key)?.hint = NativeBridge.getNativeString(NativeBridge.STRING_HINT_LICENSE)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.btn_login)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.btn_getkey)?.text = NativeBridge.getNativeString(NativeBridge.STRING_BTN_GET_KEY)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_social_channels)?.text = NativeBridge.getNativeString(NativeBridge.STRING_OFFICIAL_CHANNELS)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.btn_state_return)?.text = NativeBridge.getNativeString(NativeBridge.STRING_BTN_RETURN_LOGIN)

        val etUser = findViewById<EditText>(R.id.et_user)
        val etKey = findViewById<EditText>(R.id.et_key)
        val btnLogin = findViewById<TextView>(R.id.btn_login)
        val btnGetKey = findViewById<TextView>(R.id.btn_getkey)
        findViewById<TextView>(R.id.btn_state_return).setOnClickListener {
            showLoginForm(btnLogin)
        }

        if (previewVerificationState != null) {
            layoutLogin.visibility = android.view.View.GONE
            layoutSplash.visibility = android.view.View.VISIBLE
            val state = when (previewVerificationState.lowercase()) {
                "valid" -> VerificationState.VALID
                "expired" -> VerificationState.EXPIRED
                "network" -> VerificationState.NETWORK_ERROR
                "maintenance" -> VerificationState.MAINTENANCE
                "invalid" -> VerificationState.INVALID
                else -> VerificationState.VALIDATING
            }
            renderVerificationState(state)
            if (state == VerificationState.VALIDATING) startPulseAnimation()
        }

        // Deep link: freezy://activate?key=FREEZY-XXXX... -> rellenar la licencia
        try {
            val dataUri: Uri? = intent?.data
            if (dataUri != null && dataUri.scheme == "freezy" && dataUri.host == "activate") {
                dataUri.getQueryParameter("key")?.let { deepKey ->
                    if (deepKey.isNotBlank()) {
                        etKey.setText(deepKey.uppercase())
                        Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.S216), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        btnGetKey.setOnLongClickListener {
            Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.S217), Toast.LENGTH_LONG).show()
            true
        }

        // Botones sociales (esquinas inferiores): WhatsApp y TikTok con outline animado
        val btnWhatsApp = findViewById<android.widget.ImageButton>(R.id.btn_social_whatsapp)
        val btnTikTok = findViewById<android.widget.ImageButton>(R.id.btn_social_tiktok)

        btnWhatsApp.setOnClickListener {
            Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.S218), Toast.LENGTH_SHORT).show()
            openScheme("https://whatsapp.com/channel/0029Vb9K5FJ545usgyohs136", "com.whatsapp")
        }

        btnTikTok.setOnClickListener {
            Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.S219), Toast.LENGTH_SHORT).show()
            openScheme("https://www.tiktok.com/@freezyt", "com.zhiliaoapp.musically")
        }

        // Outline animado de los botones sociales (rotación a nivel de vista, robusta)
        startSocialRingRotation()

        // Prueba controlada: ADB puede solicitar una vista previa sin alterar producción.
        if (com.system.network.ui.BuildConfig.DEBUG && intent.getBooleanExtra("preview_update_modal", false)) {
            window.decorView.postDelayed({ AppUpdateManager.showDebugPreview(this) }, 900)
        }

        btnGetKey.setOnClickListener {
            btnGetKey.isEnabled = false
            btnGetKey.text = "GENERANDO..."
            Thread {
                try {
                    val endpointUrl = try {
                        getSecureEndpoint()
                    } catch (e: Throwable) {
                        NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT)
                    }.ifEmpty { NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT) }

                    // Derivar base para la página y endpoint begin
                    val baseApi = endpointUrl.substringBefore("/api")
                    val beginEndpoint = baseApi + "/api/ads/begin"

                    val hwid = NativeBridge.getHWID(this@LoginActivity)

                    val beginConn = WebSecurity.open(beginEndpoint) as HttpURLConnection
                    beginConn.requestMethod = "POST"
                    beginConn.setRequestProperty("Content-Type", "application/json")
                    beginConn.connectTimeout = 30000
                    beginConn.readTimeout = 30000
                    beginConn.doOutput = true
                    val beginJson = "{\"hwid\": \"$hwid\"}"
                    beginConn.outputStream.use { os ->
                        val input = beginJson.toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }

                    if (beginConn.responseCode != 200) {
                        val errorStream = beginConn.errorStream
                        val errorMessage = if (errorStream != null) {
                            try { JSONObject(errorStream.bufferedReader().readText()).getString("message") }
                            catch (e: Exception) { "No se pudo iniciar GET KEY." }
                        } else { "No se pudo iniciar GET KEY." }

                        runOnUiThread {
                            Toast.makeText(this@LoginActivity, errorMessage, Toast.LENGTH_LONG).show()
                            btnGetKey.isEnabled = true
                            btnGetKey.text = "GET KEY GRATIS"
                        }
                        return@Thread
                    }

                    val beginResponse = beginConn.inputStream.bufferedReader().readText()
                    val json = JSONObject(beginResponse)
                    val token = json.getString("token")

                    val getkeyUrl = "${baseApi}/getkey?token=${token}&hwid=${Uri.encode(hwid)}"

                    runOnUiThread {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getkeyUrl)))
                        } catch (e: Exception) {
                            Toast.makeText(this@LoginActivity, NativeBridge.getNativeString(NativeBridge.S220), Toast.LENGTH_LONG).show()
                        }
                        btnGetKey.isEnabled = true
                        btnGetKey.text = "GET KEY GRATIS"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this@LoginActivity, NativeBridge.getNativeString(NativeBridge.S221), Toast.LENGTH_LONG).show()
                        btnGetKey.isEnabled = true
                        btnGetKey.text = "GET KEY GRATIS"
                    }
                }
            }.start()
        }

        // Restaurar datos guardados para comodidad del usuario (cifrados)
        etUser.setText(SecurePrefs.getSecureString(this, "saved_username"))
        etKey.setText(SecurePrefs.getSecureString(this, "saved_key"))

        // Si el usuario ya metió la Key correcta antes, hacemos la carga (Splash)
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

            // Mostrar splash
            layoutLogin.visibility = android.view.View.GONE
            layoutSplash.visibility = android.view.View.VISIBLE
            renderVerificationState(VerificationState.VALIDATING)
            startPulseAnimation()

            // Conexión real al servidor privado
            Thread {
                try {
                    val endpointUrl = try {
                        getSecureEndpoint()
                    } catch (e: Throwable) {
                        NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT)
                    }.ifEmpty { NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT) }
                    
                    // PASO 1: Solicitar Desafío (Challenge)
                    val challengeEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl.replace("/verify", "/challenge") else "$endpointUrl/challenge"
                    val verifyEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl else "$endpointUrl/verify"

                    val hwid = NativeBridge.getHWID(this@LoginActivity)
                    val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                    
                    // PASO 1.5: Conexión HTTPS segura
                    val challengeConn = WebSecurity.open(challengeEndpoint) as HttpURLConnection
                    challengeConn.requestMethod = "POST"
                    challengeConn.setRequestProperty("Content-Type", "application/json")
                    challengeConn.connectTimeout = 30000
                    challengeConn.readTimeout = 30000
                    challengeConn.doOutput = true

                    val currentAppVersion = if (com.system.network.ui.BuildConfig.DEBUG) "4.0.0" else com.system.network.ui.BuildConfig.VERSION_NAME.substringBefore("-")
                    val challengeJson = "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"username\": \"$username\", \"device_model\": \"$deviceModel\", \"app_version\": \"$currentAppVersion\"}"
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
                        
                        val isUpdate = errorMessage.contains("obsolet", true) || errorMessage.contains("actualiza", true) || errorMessage.contains("mediafire", true)
                        if (isUpdate) {
                            runOnUiThread {
                                showLoginForm(btnLogin)
                                AppUpdateManager.showUpdateModal(this@LoginActivity, errorMessage)
                            }
                            return@Thread
                        }

                        runOnUiThread {
                            btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
                            btnLogin.isEnabled = true
                            stopPulseAnimation()
                            val state = if (challengeConn.responseCode == 503 || errorMessage.contains("mantenimiento", true) || errorMessage.contains("maintenance", true)) {
                                VerificationState.MAINTENANCE
                            } else VerificationState.INVALID
                            val cleanMsg = if (errorMessage.contains("mediafire", true) || errorMessage.contains("http", true)) "Licencia no válida." else errorMessage
                            renderVerificationState(state, cleanMsg)
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

                    // PASO 3: Enviar HMAC para Verificación (mismo pinning)
                    val verifyConn = WebSecurity.open(verifyEndpoint) as HttpURLConnection
                    verifyConn.requestMethod = "POST"
                    verifyConn.setRequestProperty("Content-Type", "application/json")
                    verifyConn.connectTimeout = 30000
                    verifyConn.readTimeout = 30000
                    verifyConn.doOutput = true

                    val verifyJson = "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"hmac\": \"$hmacHex\", \"app_version\": \"$currentAppVersion\"}"
                    verifyConn.outputStream.use { os ->
                        val input = verifyJson.toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }

                    if (verifyConn.responseCode == 200) {
                        val responseBody = verifyConn.inputStream.bufferedReader().readText()
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
                            
                            val activationDate = createdAt
                            val expirationDate = expiresAt
                            
                            runOnUiThread {
                                SecureLogger.log(this@LoginActivity, "Licencia Validada")
                                prefs.edit()
                                        .putBoolean("is_logged_in", true)
                                        .apply()
                                // Datos sensibles cifrados con la clave del AndroidKeyStore
                                SecurePrefs.putSecureString(this@LoginActivity, "saved_username", username)
                                SecurePrefs.putSecureString(this@LoginActivity, "saved_key", key)
                                SecurePrefs.putSecureString(this@LoginActivity, "activation_date", activationDate)
                                SecurePrefs.putSecureString(this@LoginActivity, "expiration_date", expirationDate)
                                LicenseEntitlements.updateFromServer(this@LoginActivity, jsonObject)
                                if (sessionToken.isNotEmpty()) {
                                    SecurePrefs.putSecureString(this@LoginActivity, "session_token", sessionToken)
                                }
                                // NO se guarda el payload descifrado en SharedPreferences
                                Toast.makeText(
                                                this@LoginActivity,
                                                NativeBridge.getNativeString(NativeBridge.STRING_ACCESS_GRANTED),
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()

                                playAccessGrantedAnimation {
                                    startActivity(Intent(this@LoginActivity, MainActivity::class.java).apply {
                                        if (intent.getBooleanExtra("preview_update_modal", false)) putExtra("preview_update_modal", true)
                                    })
                                    finish()
                                }
                            }
                        } else {
                            val message = jsonObject.optString("message", NativeBridge.getNativeString(NativeBridge.STRING_INVALID_LICENSE))
                            runOnUiThread {
                                handleSplashRejected(this@LoginActivity, message, tvSplashStatus, layoutSplash, layoutLogin, btnLogin)
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
                            val normalized = if (verifyConn.responseCode == 503) "Servidor en mantenimiento. Inténtalo más tarde." else errorMessage
                            handleSplashRejected(this@LoginActivity, normalized, tvSplashStatus, layoutSplash, layoutLogin, btnLogin)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        blockNoInternet(tvSplashStatus, layoutSplash, layoutLogin, btnLogin)
                    }
                }
            }
            .start()
        }
        if (!previewDisclaimer && previewVerificationState == null && !previewLogin && prefs.getBoolean("is_logged_in", false)) {
            layoutSplash.visibility = android.view.View.VISIBLE
            layoutLogin.visibility = android.view.View.GONE
            renderVerificationState(VerificationState.VALIDATING, "Revalidando tu sesión con el servidor de Freezy")
            startPulseAnimation()

            val expDateStr = SecurePrefs.getSecureString(this, "expiration_date")
            var isExpired = false
            if (expDateStr.isNotEmpty() && expDateStr != "--") {
                try {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    val d2 = sdf.parse(expDateStr)
                    if (d2 != null) {
                        val endOfDay = d2.time + (24L * 60 * 60 * 1000 - 1000)
                        if (System.currentTimeMillis() > endOfDay) {
                            isExpired = true
                        }
                    }
                } catch (e: Exception) {
                    try {
                        val expTime = java.lang.Long.parseLong(expDateStr)
                        if (System.currentTimeMillis() > expTime) {
                            isExpired = true
                        }
                    } catch (ex: Exception) {}
                }
            }

            if (isExpired) {
                stopPulseAnimation()
                SessionGuard.clearSession(this@LoginActivity)
                renderVerificationState(VerificationState.EXPIRED)
                return
            }

            // Validación REAL de sesión contra el servidor: sin internet, NO se entra al Main.
            Thread {
                try {
                    val endpointUrl = try {
                        getSecureEndpoint()
                    } catch (e: Throwable) {
                        NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT)
                    }.ifEmpty { NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT) }

                    val savedUser = SecurePrefs.getSecureString(this@LoginActivity, "saved_username")
                    val savedKey = SecurePrefs.getSecureString(this@LoginActivity, "saved_key")
                    if (savedUser.isEmpty() || savedKey.isEmpty()) {
                        throw IllegalStateException("No session")
                    }

                    val challengeEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl.replace("/verify", "/challenge") else "$endpointUrl/challenge"
                    val verifyEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl else "$endpointUrl/verify"

                    val hwid = NativeBridge.getHWID(this@LoginActivity)
                    val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

                    val challengeConn = WebSecurity.open(challengeEndpoint)
                    challengeConn.requestMethod = "POST"
                    challengeConn.setRequestProperty("Content-Type", "application/json")
                    challengeConn.connectTimeout = 30000
                    challengeConn.readTimeout = 30000
                    challengeConn.doOutput = true
                    val currentAppVersion = if (com.system.network.ui.BuildConfig.DEBUG) "4.0.0" else com.system.network.ui.BuildConfig.VERSION_NAME.substringBefore("-")
                    val challengeJson = "{\"key\": \"$savedKey\", \"hwid\": \"$hwid\", \"username\": \"$savedUser\", \"device_model\": \"$deviceModel\", \"app_version\": \"$currentAppVersion\"}"
                    challengeConn.outputStream.write(challengeJson.toByteArray(Charsets.UTF_8))

                    if (challengeConn.responseCode != 200) {
                        runOnUiThread {
                            stopPulseAnimation()
                            btnLogin.isEnabled = true
                            val state = if (challengeConn.responseCode == 503) VerificationState.MAINTENANCE else VerificationState.NETWORK_ERROR
                            renderVerificationState(state)
                        }
                        return@Thread
                    }

                    val nonce = JSONObject(challengeConn.inputStream.bufferedReader().readText()).getString("nonce")

                    val algorithm = "HmacSHA256"
                    val mac = Mac.getInstance(algorithm)
                    mac.init(SecretKeySpec(NativeBridge.getHmacSecret().toByteArray(Charsets.UTF_8), algorithm))
                    val hmacHex = mac.doFinal(nonce.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

                    val verifyConn = WebSecurity.open(verifyEndpoint)
                    verifyConn.requestMethod = "POST"
                    verifyConn.setRequestProperty("Content-Type", "application/json")
                    verifyConn.connectTimeout = 30000
                    verifyConn.readTimeout = 30000
                    verifyConn.doOutput = true
                    val verifyJson = "{\"key\": \"$savedKey\", \"hwid\": \"$hwid\", \"hmac\": \"$hmacHex\", \"app_version\": \"$currentAppVersion\"}"
                    verifyConn.outputStream.write(verifyJson.toByteArray(Charsets.UTF_8))

                    if (verifyConn.responseCode == 200) {
                        val responseBody = verifyConn.inputStream.bufferedReader().readText()
                        val jsonObject = JSONObject(responseBody)
                        val isValid = jsonObject.optBoolean("valid", false)
                        if (isValid) {
                            val sessionToken = jsonObject.optString("session_token", "")
                            if (sessionToken.isNotEmpty()) {
                                SecurePrefs.putSecureString(this@LoginActivity, "session_token", sessionToken)
                            }
                            val createdAt = jsonObject.optString("created_at", "")
                            val expiresAt = jsonObject.optString("expires_at", "")
                            if (createdAt.isNotEmpty() && expiresAt.isNotEmpty()) {
                                SecurePrefs.putSecureString(this@LoginActivity, "activation_date", createdAt)
                                SecurePrefs.putSecureString(this@LoginActivity, "expiration_date", expiresAt)
                            }
                            LicenseEntitlements.updateFromServer(this@LoginActivity, jsonObject)
                            val encryptedPayloadHex = jsonObject.optString("encrypted_payload", "")
                            val ivHex = jsonObject.optString("iv", "")
                            if (encryptedPayloadHex.isNotEmpty() && ivHex.isNotEmpty()) {
                                try {
                                    val aesKeyBytes = SecureCrypto.hexToBytes(hmacHex)
                                    val ivBytes = SecureCrypto.hexToBytes(ivHex)
                                    val encryptedBytes = SecureCrypto.hexToBytes(encryptedPayloadHex)
                                    val decrypted = SecureCrypto.decryptGcm(aesKeyBytes, ivBytes, encryptedBytes)
                                    NativeBridge.setSecurePayload(decrypted)
                                } catch (e: Exception) {
                                    if (com.system.network.ui.BuildConfig.DEBUG) e.printStackTrace()
                                }
                            }
                            runOnUiThread {
                                playAccessGrantedAnimation {
                                    startActivity(Intent(this@LoginActivity, MainActivity::class.java).apply {
                                        if (intent.getBooleanExtra("preview_update_modal", false)) putExtra("preview_update_modal", true)
                                    })
                                    finish()
                                }
                            }
                        } else {
                            val message = jsonObject.optString("message", NativeBridge.getNativeString(NativeBridge.STRING_INVALID_LICENSE))
                            runOnUiThread {
                                handleSplashRejected(this@LoginActivity, message, tvSplashStatus, layoutSplash, layoutLogin, btnLogin)
                            }
                        }
                    } else {
                        runOnUiThread {
                            val message = if (verifyConn.responseCode == 503) "Servidor en mantenimiento. Inténtalo más tarde." else NativeBridge.getNativeString(NativeBridge.STRING_INVALID_LICENSE)
                            handleSplashRejected(this@LoginActivity, message, tvSplashStatus, layoutSplash, layoutLogin, btnLogin)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        blockNoInternet(tvSplashStatus, layoutSplash, layoutLogin, btnLogin)
                    }
                }
            }.start()
            return
        } else if (previewVerificationState == null) {
            layoutSplash.visibility = android.view.View.GONE
            layoutLogin.visibility = android.view.View.VISIBLE
        }

    }

    private fun renderVerificationState(state: VerificationState, detail: String? = null) {
        val logo = findViewById<ImageView>(R.id.iv_splash_logo)
        val badge = findViewById<TextView>(R.id.tv_state_badge)
        val title = findViewById<TextView>(R.id.tv_state_title)
        val message = findViewById<TextView>(R.id.tv_splash_status)
        val progress = findViewById<android.widget.ProgressBar>(R.id.progress_verification)
        val returnButton = findViewById<TextView>(R.id.btn_state_return)

        logo.animate().cancel()
        logo.scaleX = 1f
        logo.scaleY = 1f
        logo.clearColorFilter()
        progress.visibility = android.view.View.GONE
        returnButton.visibility = android.view.View.GONE

        when (state) {
            VerificationState.VALIDATING -> {
                logo.setImageResource(R.drawable.freezy_logo)
                badge.text = NativeBridge.getNativeString(NativeBridge.STRING_BADGE_SECURE_VERIF)
                badge.setTextColor(android.graphics.Color.parseColor("#D996FF"))
                title.text = NativeBridge.getNativeString(NativeBridge.STRING_TITLE_VALIDATING)
                title.setTextColor(android.graphics.Color.parseColor("#F8F5FC"))
                message.text = detail ?: NativeBridge.getNativeString(NativeBridge.STRING_STATUS_CHECKING_ACCESS)
                progress.visibility = android.view.View.VISIBLE
            }
            VerificationState.VALID -> {
                logo.setImageResource(R.drawable.ic_cyber_check)
                badge.text = NativeBridge.getNativeString(NativeBridge.STRING_BADGE_ACCESS_AUTHORIZED)
                badge.setTextColor(android.graphics.Color.parseColor("#58E6B0"))
                title.text = NativeBridge.getNativeString(NativeBridge.STRING_TITLE_LICENSE_VALID)
                title.setTextColor(android.graphics.Color.parseColor("#F8F5FC"))
                message.text = detail ?: NativeBridge.getNativeString(NativeBridge.STRING_STATUS_VALID_DESC)
            }
            VerificationState.EXPIRED -> {
                logo.setImageResource(R.drawable.ic_cross_red)
                badge.text = NativeBridge.getNativeString(NativeBridge.STRING_BADGE_EXPIRED)
                badge.setTextColor(android.graphics.Color.parseColor("#FF8792"))
                title.text = NativeBridge.getNativeString(NativeBridge.STRING_TITLE_EXPIRED)
                title.setTextColor(android.graphics.Color.parseColor("#FF6977"))
                message.text = detail ?: NativeBridge.getNativeString(NativeBridge.STRING_STATUS_EXPIRED_DESC)
                returnButton.visibility = android.view.View.VISIBLE
            }
            VerificationState.NETWORK_ERROR -> {
                logo.setImageResource(R.drawable.ic_network_error)
                badge.text = NativeBridge.getNativeString(NativeBridge.STRING_BADGE_NET_ERROR)
                badge.setTextColor(android.graphics.Color.parseColor("#FF8792"))
                title.text = NativeBridge.getNativeString(NativeBridge.STRING_TITLE_NET_ERROR)
                title.setTextColor(android.graphics.Color.parseColor("#FF6977"))
                message.text = detail ?: NativeBridge.getNativeString(NativeBridge.STRING_STATUS_NET_ERROR_DESC)
                returnButton.visibility = android.view.View.VISIBLE
            }
            VerificationState.MAINTENANCE -> {
                logo.setImageResource(R.drawable.ic_maintenance)
                badge.text = NativeBridge.getNativeString(NativeBridge.STRING_BADGE_MAINTENANCE)
                badge.setTextColor(android.graphics.Color.parseColor("#FFC66D"))
                title.text = NativeBridge.getNativeString(NativeBridge.STRING_TITLE_MAINTENANCE)
                title.setTextColor(android.graphics.Color.parseColor("#FFB84D"))
                message.text = detail ?: NativeBridge.getNativeString(NativeBridge.STRING_STATUS_MAINTENANCE_DESC)
                returnButton.visibility = android.view.View.VISIBLE
            }
            VerificationState.INVALID -> {
                logo.setImageResource(R.drawable.ic_cross_red)
                badge.text = NativeBridge.getNativeString(NativeBridge.STRING_BADGE_DENIED)
                badge.setTextColor(android.graphics.Color.parseColor("#FF8792"))
                title.text = NativeBridge.getNativeString(NativeBridge.STRING_TITLE_DENIED)
                title.setTextColor(android.graphics.Color.parseColor("#FF6977"))
                message.text = detail ?: NativeBridge.getNativeString(NativeBridge.STRING_STATUS_DENIED_DESC)
                returnButton.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun showLoginForm(btnLogin: TextView? = findViewById(R.id.btn_login)) {
        stopPulseAnimation()
        btnLogin?.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
        btnLogin?.isEnabled = true
        findViewById<android.view.View>(R.id.layout_splash).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.layout_login).visibility = android.view.View.VISIBLE
    }

    private fun startPulseAnimation() {
        val logo = findViewById<android.widget.ImageView>(com.system.network.ui.R.id.iv_splash_logo)
        val pulse = android.animation.ObjectAnimator.ofFloat(logo, "alpha", 1f, 0.3f, 1f)
        pulse.duration = 1500
        pulse.repeatCount = android.animation.ObjectAnimator.INFINITE
        pulse.start()
        logo.setTag(com.system.network.ui.R.id.iv_splash_logo, pulse)
    }

    /**
     * Bloquea el acceso sin internet: muestra el aviso, cancela la animación
     * y devuelve al usuario al formulario de login (NO entra al Main).
     */
    private fun blockNoInternet(
        tvSplashStatus: android.widget.TextView?,
        layoutSplash: android.view.View,
        layoutLogin: android.view.View,
        btnLogin: android.widget.TextView
    ) {
        stopPulseAnimation()
        btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
        btnLogin.isEnabled = true
        layoutLogin.visibility = android.view.View.GONE
        layoutSplash.visibility = android.view.View.VISIBLE
        renderVerificationState(VerificationState.NETWORK_ERROR)
    }

    /**
     * Maneja una licencia rechazada durante el splash de revalidación
     * (ban/expiración/desconocida) sin permitir entrar al Main.
     */
    private fun handleSplashRejected(
        activity: LoginActivity,
        message: String,
        tvSplashStatus: android.widget.TextView?,
        layoutSplash: android.view.View,
        layoutLogin: android.view.View,
        btnLogin: android.widget.TextView
    ) {
        if (SessionGuard.isBan(message)) {
            stopPulseAnimation()
            SessionGuard.showBlocked(activity, "CUENTA BANEADA", message)
            btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
            btnLogin.isEnabled = true
            layoutSplash.visibility = android.view.View.GONE
            layoutLogin.visibility = android.view.View.VISIBLE
        } else if (SessionGuard.isExpired(message)) {
            stopPulseAnimation()
            SessionGuard.clearSession(activity)
            layoutLogin.visibility = android.view.View.GONE
            layoutSplash.visibility = android.view.View.VISIBLE
            renderVerificationState(VerificationState.EXPIRED, message)
        } else if (message.contains("obsolet", true) || message.contains("actualiza", true) || message.contains("mediafire", true)) {
            stopPulseAnimation()
            showLoginForm(btnLogin)
            AppUpdateManager.showUpdateModal(activity, message)
        } else {
            btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
            btnLogin.isEnabled = true
            stopPulseAnimation()
            layoutLogin.visibility = android.view.View.GONE
            layoutSplash.visibility = android.view.View.VISIBLE
            val state = if (message.contains("mantenimiento", true) || message.contains("maintenance", true)) {
                VerificationState.MAINTENANCE
            } else VerificationState.INVALID
            val cleanMsg = if (message.contains("mediafire", true) || message.contains("http", true)) "Licencia no válida." else message
            renderVerificationState(state, cleanMsg)
        }
    }

    private fun stopPulseAnimation() {
        val logo = findViewById<android.widget.ImageView>(com.system.network.ui.R.id.iv_splash_logo)
        (logo.getTag(com.system.network.ui.R.id.iv_splash_logo) as? android.animation.ObjectAnimator)?.cancel()
        logo.alpha = 1f
    }

    private fun playAccessGrantedAnimation(onComplete: () -> Unit) {
        runOnUiThread {
            stopPulseAnimation()
            val logo = findViewById<android.widget.ImageView>(com.system.network.ui.R.id.iv_splash_logo)
            renderVerificationState(VerificationState.VALID)
            logo.animate().scaleX(1.3f).scaleY(1.3f).setDuration(400).setInterpolator(android.view.animation.OvershootInterpolator()).start()
            
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                onComplete()
            }, 1200)
        }
    }

    fun clearInputFields() {
        runOnUiThread {
            findViewById<EditText>(R.id.et_user)?.setText("")
            findViewById<EditText>(R.id.et_key)?.setText("")
        }
    }

    /** Abre el enlace en la app oficial si está instalada; si no, en el navegador. Devuelve true si tuvo éxito. */
    private fun openScheme(url: String, appPackage: String): Boolean {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(appPackage))
            return true
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            } catch (e2: Exception) {
                Toast.makeText(this, "No se pudo abrir el enlace", Toast.LENGTH_LONG).show()
                return false
            }
        }
    }

    /** Rota los anillos de los botones sociales (outline animado, infinito). */
    private fun startSocialRingRotation() {
        val ringIds = listOf(R.id.iv_social_ring_wa, R.id.iv_social_ring_tt)
        for (id in ringIds) {
            findViewById<android.widget.ImageView>(id)?.let { ring ->
                val anim = android.view.animation.RotateAnimation(
                    0f, 360f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                )
                anim.duration = 5000
                anim.repeatCount = android.view.animation.Animation.INFINITE
                anim.interpolator = android.view.animation.LinearInterpolator()
                ring.startAnimation(anim)
            }
        }
    }

}
