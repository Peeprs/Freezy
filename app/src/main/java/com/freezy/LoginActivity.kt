package com.freezy

import com.system.network.ui.R

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
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

    // Ingeniera inversa de la inversa (Carga librería nativa y llama ofuscador)
    init {
        System.loadLibrary("ncx")
    }
    private external fun getSecureEndpoint(): String

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
        if (!prefs.getBoolean("disclaimer_accepted", false)) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_disclaimer, null)
            val btnAccept = dialogView.findViewById<Button>(R.id.btn_accept_risk)
            val btnExit = dialogView.findViewById<Button>(R.id.btn_exit_app)
            
            btnAccept.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#00E5FF")))
            btnAccept.setTextColor(android.graphics.Color.parseColor("#0D0E12"))
            
            btnExit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF3B30")))
            btnExit.setTextColor(android.graphics.Color.parseColor("#F5F6F8"))
            
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

        setContentView(com.system.network.ui.R.layout.activity_login)

        val layoutSplash = findViewById<android.view.View>(com.system.network.ui.R.id.layout_splash)
        val layoutLogin = findViewById<android.view.View>(com.system.network.ui.R.id.layout_login)
        val tvSplashStatus = findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_splash_status)

        // Cargar strings ofuscados de C++ para los campos de login (Inicializar siempre primero)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_app_name)?.text = NativeBridge.getNativeString(NativeBridge.STRING_APP_NAME)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_label_user)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LABEL_USER)
        findViewById<android.widget.EditText>(com.system.network.ui.R.id.et_user)?.hint = NativeBridge.getNativeString(NativeBridge.STRING_HINT_USER)
        findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_label_license_login)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LABEL_LICENSE)
        findViewById<android.widget.EditText>(com.system.network.ui.R.id.et_key)?.hint = NativeBridge.getNativeString(NativeBridge.STRING_HINT_LICENSE)
        findViewById<android.widget.Button>(com.system.network.ui.R.id.btn_login)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)

        val etUser = findViewById<EditText>(R.id.et_user)
        val etKey = findViewById<EditText>(R.id.et_key)
        val btnLogin = findViewById<Button>(R.id.btn_login)
        btnLogin.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#00E5FF")))
        btnLogin.setTextColor(android.graphics.Color.parseColor("#0D0E12"))

        val btnGetKey = findViewById<Button>(R.id.btn_getkey)
        btnGetKey.text = "GET KEY GRATIS"
        btnGetKey.setTextColor(android.graphics.Color.parseColor("#00E5FF"))

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

        // Botones sociales (esquinas inferiores): Telegram y TikTok con outline animado
        val btnTelegram = findViewById<android.widget.ImageButton>(R.id.btn_social_telegram)
        val btnTikTok = findViewById<android.widget.ImageButton>(R.id.btn_social_tiktok)

        btnTelegram.setOnClickListener {
            Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.S218), Toast.LENGTH_SHORT).show()
            // tg://resolve abre directamente el chat del bot; fallback a la URL web
            if (!openScheme("tg://resolve?domain=FreezyTBot", "org.telegram.messenger")) {
                openScheme("https://t.me/FreezyTBot", "org.telegram.messenger")
            }
        }

        btnTikTok.setOnClickListener {
            Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.S219), Toast.LENGTH_SHORT).show()
            openScheme("https://www.tiktok.com/@freezyt", "com.zhiliaoapp.musically")
        }

        // Outline animado de los botones sociales (rotación a nivel de vista, robusta)
        startSocialRingRotation()

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
            tvSplashStatus.text = NativeBridge.getNativeString(NativeBridge.STRING_SPLASH_FETCHING)
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

                    val currentAppVersion = com.system.network.ui.BuildConfig.VERSION_NAME
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
                        
                        runOnUiThread {
                            Toast.makeText(this@LoginActivity, errorMessage, Toast.LENGTH_LONG).show()
                            btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
                            btnLogin.isEnabled = true
                            layoutSplash.visibility = android.view.View.GONE
                            layoutLogin.visibility = android.view.View.VISIBLE
                            stopPulseAnimation()
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

                                val warning = jsonObject.optString("update_warning", "")
                                if (warning.isNotEmpty()) {
                                    AlertDialog.Builder(this@LoginActivity)
                                        .setTitle(NativeBridge.getNativeString(NativeBridge.STRING_UPDATE_TITLE))
                                        .setMessage(warning)
                                        .setPositiveButton(NativeBridge.getNativeString(NativeBridge.STRING_UNDERSTOOD)) { _, _ ->
                                            playAccessGrantedAnimation {
                                                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                                finish()
                                            }
                                        }
                                        .setCancelable(false)
                                        .show()
                                } else {
                                    playAccessGrantedAnimation {
                                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                        finish()
                                    }
                                }
                            }
                        } else {
                            val message = jsonObject.optString("message", NativeBridge.getNativeString(NativeBridge.STRING_INVALID_LICENSE))
                            runOnUiThread {
                                if (SessionGuard.isBan(message)) {
                                    stopPulseAnimation()
                                    SessionGuard.showBlocked(this@LoginActivity, "CUENTA BANEADA", message)
                                    btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
                                    btnLogin.isEnabled = true
                                    layoutSplash.visibility = android.view.View.GONE
                                    layoutLogin.visibility = android.view.View.VISIBLE
                                } else if (SessionGuard.isExpired(message)) {
                                    stopPulseAnimation()
                                    val ivSplashLogo = findViewById<ImageView>(R.id.iv_splash_logo)
                                    ivSplashLogo.setImageResource(com.system.network.ui.R.drawable.ic_cross_red)
                                    ivSplashLogo.setColorFilter(android.graphics.Color.parseColor("#FF3B30"), android.graphics.PorterDuff.Mode.SRC_IN)
                                    tvSplashStatus.text = NativeBridge.getNativeString(NativeBridge.STRING_LICENSE_EXPIRED)
                                    tvSplashStatus.setTextColor(android.graphics.Color.parseColor("#FF3B30"))

                                    SessionGuard.clearSession(this@LoginActivity)

                                    Thread {
                                        Thread.sleep(2500)
                                        runOnUiThread {
                                            ivSplashLogo.setImageResource(com.system.network.ui.R.mipmap.ic_launcher)
                                            ivSplashLogo.clearColorFilter()
                                            tvSplashStatus.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                                            tvSplashStatus.text = NativeBridge.getNativeString(NativeBridge.STRING_SPLASH_FETCHING)

                                            layoutSplash.visibility = android.view.View.GONE
                                            layoutLogin.visibility = android.view.View.VISIBLE
                                            btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
                                            btnLogin.isEnabled = true
                                        }
                                    }.start()
                                } else {
                                    Toast.makeText(this@LoginActivity, message, Toast.LENGTH_LONG).show()
                                    btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
                                    btnLogin.isEnabled = true
                                    layoutSplash.visibility = android.view.View.GONE
                                    layoutLogin.visibility = android.view.View.VISIBLE
                                    stopPulseAnimation()
                                }
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
                            if (SessionGuard.isBan(errorMessage)) {
                                stopPulseAnimation()
                                SessionGuard.showBlocked(this@LoginActivity, "CUENTA BANEADA", errorMessage)
                                btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
                                btnLogin.isEnabled = true
                                layoutSplash.visibility = android.view.View.GONE
                                layoutLogin.visibility = android.view.View.VISIBLE
                            } else if (SessionGuard.isExpired(errorMessage)) {
                                stopPulseAnimation()
                                val ivSplashLogo = findViewById<ImageView>(R.id.iv_splash_logo)
                                ivSplashLogo.setImageResource(com.system.network.ui.R.drawable.ic_cross_red)
                                ivSplashLogo.setColorFilter(android.graphics.Color.parseColor("#FF3B30"), android.graphics.PorterDuff.Mode.SRC_IN)
                                tvSplashStatus.text = NativeBridge.getNativeString(NativeBridge.STRING_LICENSE_EXPIRED)
                                tvSplashStatus.setTextColor(android.graphics.Color.parseColor("#FF3B30"))

                                SessionGuard.clearSession(this@LoginActivity)

                                Thread {
                                    Thread.sleep(2500)
                                    runOnUiThread {
                                        ivSplashLogo.setImageResource(com.system.network.ui.R.mipmap.ic_launcher)
                                        ivSplashLogo.clearColorFilter()
                                        tvSplashStatus.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                                        tvSplashStatus.text = NativeBridge.getNativeString(NativeBridge.STRING_SPLASH_FETCHING)

                                        layoutSplash.visibility = android.view.View.GONE
                                        layoutLogin.visibility = android.view.View.VISIBLE
                                        btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
                                        btnLogin.isEnabled = true
                                    }
                                }.start()
                            } else {
                                Toast.makeText(this@LoginActivity, errorMessage, Toast.LENGTH_LONG).show()
                                btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
                                btnLogin.isEnabled = true
                                layoutSplash.visibility = android.view.View.GONE
                                layoutLogin.visibility = android.view.View.VISIBLE
                                stopPulseAnimation()
                            }
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
                        btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
                        btnLogin.isEnabled = true
                        
                        // Regresar al login form
                        layoutSplash.visibility = android.view.View.GONE
                        layoutLogin.visibility = android.view.View.VISIBLE
                        stopPulseAnimation()
                    }
                }
            }
            .start()
        }
        if (prefs.getBoolean("is_logged_in", false)) {
            layoutSplash.visibility = android.view.View.VISIBLE
            layoutLogin.visibility = android.view.View.GONE
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
                Thread {
                    Thread.sleep(1500)
                    runOnUiThread {
                        stopPulseAnimation()
                        val ivSplashLogo = findViewById<ImageView>(R.id.iv_splash_logo)
                        ivSplashLogo.setImageResource(com.system.network.ui.R.drawable.ic_cross_red)
                        ivSplashLogo.setColorFilter(android.graphics.Color.parseColor("#FF3B30"), android.graphics.PorterDuff.Mode.SRC_IN)
                        tvSplashStatus.text = NativeBridge.getNativeString(NativeBridge.STRING_LICENSE_EXPIRED)
                        tvSplashStatus.setTextColor(android.graphics.Color.parseColor("#FF3B30"))

                        // Cierra sesión limpiando datos
                        SessionGuard.clearSession(this@LoginActivity)

                        Thread {
                            Thread.sleep(2500)
                            runOnUiThread {
                                ivSplashLogo.setImageResource(com.system.network.ui.R.mipmap.ic_launcher)
                                ivSplashLogo.clearColorFilter()
                                tvSplashStatus.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                                tvSplashStatus.text = NativeBridge.getNativeString(NativeBridge.STRING_SPLASH_FETCHING)

                                layoutSplash.visibility = android.view.View.GONE
                                layoutLogin.visibility = android.view.View.VISIBLE
                                btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
                                btnLogin.isEnabled = true
                            }
                        }.start()
                    }
                }.start()
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
                    val currentAppVersion = com.system.network.ui.BuildConfig.VERSION_NAME
                    val challengeJson = "{\"key\": \"$savedKey\", \"hwid\": \"$hwid\", \"username\": \"$savedUser\", \"device_model\": \"$deviceModel\", \"app_version\": \"$currentAppVersion\"}"
                    challengeConn.outputStream.write(challengeJson.toByteArray(Charsets.UTF_8))

                    if (challengeConn.responseCode != 200) {
                        runOnUiThread {
                            blockNoInternet(tvSplashStatus, layoutSplash, layoutLogin, btnLogin)
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
                                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
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
                            handleSplashRejected(this@LoginActivity, NativeBridge.getNativeString(NativeBridge.STRING_INVALID_LICENSE), tvSplashStatus, layoutSplash, layoutLogin, btnLogin)
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
        } else {
            layoutSplash.visibility = android.view.View.GONE
            layoutLogin.visibility = android.view.View.VISIBLE
        }

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
        btnLogin: android.widget.Button
    ) {
        stopPulseAnimation()
        tvSplashStatus?.text = NativeBridge.getNativeString(NativeBridge.STRING_NO_INTERNET)
        tvSplashStatus?.setTextColor(android.graphics.Color.parseColor("#FF3B30"))
        btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
        btnLogin.isEnabled = true
        layoutSplash.visibility = android.view.View.GONE
        layoutLogin.visibility = android.view.View.VISIBLE
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
        btnLogin: android.widget.Button
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
            val ivSplashLogo = activity.findViewById<ImageView>(R.id.iv_splash_logo)
            ivSplashLogo.setImageResource(com.system.network.ui.R.drawable.ic_cross_red)
            ivSplashLogo.setColorFilter(android.graphics.Color.parseColor("#FF3B30"), android.graphics.PorterDuff.Mode.SRC_IN)
            tvSplashStatus?.text = NativeBridge.getNativeString(NativeBridge.STRING_LICENSE_EXPIRED)
            tvSplashStatus?.setTextColor(android.graphics.Color.parseColor("#FF3B30"))

            SessionGuard.clearSession(activity)

            Thread {
                Thread.sleep(2500)
                activity.runOnUiThread {
                    ivSplashLogo.setImageResource(com.system.network.ui.R.mipmap.ic_launcher)
                    ivSplashLogo.clearColorFilter()
                    tvSplashStatus?.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                    tvSplashStatus?.text = NativeBridge.getNativeString(NativeBridge.STRING_SPLASH_FETCHING)

                    layoutSplash.visibility = android.view.View.GONE
                    layoutLogin.visibility = android.view.View.VISIBLE
                    btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
                    btnLogin.isEnabled = true
                }
            }.start()
        } else {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            btnLogin.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGIN_BTN)
            btnLogin.isEnabled = true
            layoutSplash.visibility = android.view.View.GONE
            layoutLogin.visibility = android.view.View.VISIBLE
            stopPulseAnimation()
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
            val textStatus = findViewById<android.widget.TextView>(com.system.network.ui.R.id.tv_splash_status)
            
            // Cyber aesthetic checkmark: Change image to check, clear filter, scale up, text update
            logo.clearColorFilter()
            logo.setImageResource(com.system.network.ui.R.drawable.ic_cyber_check)
            logo.animate().scaleX(1.3f).scaleY(1.3f).setDuration(400).setInterpolator(android.view.animation.OvershootInterpolator()).start()
            
            textStatus.text = NativeBridge.getNativeString(NativeBridge.STRING_SPLASH_GRANTED)
            textStatus.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
            
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
        val ringIds = listOf(R.id.iv_social_ring_tg, R.id.iv_social_ring_tt)
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
