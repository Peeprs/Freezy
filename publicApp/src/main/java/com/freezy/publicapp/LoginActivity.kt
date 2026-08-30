package com.freezy.publicapp

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {
    private enum class VerificationState {
        VALIDATING, VALID, EXPIRED, NETWORK_ERROR, MAINTENANCE, INVALID
    }

    private lateinit var loginLayout: View
    private lateinit var splashLayout: View
    private lateinit var loginButton: TextView
    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignatureGuard.verify(this)
        SecurePrefs.migrateLegacy(this)
        setContentView(R.layout.activity_login)

        loginLayout = findViewById(R.id.layout_login)
        splashLayout = findViewById(R.id.layout_splash)
        loginButton = findViewById(R.id.btn_login)
        val userInput = findViewById<EditText>(R.id.et_user)
        val keyInput = findViewById<EditText>(R.id.et_key)

        // Inicializar textos y hints desde cadenas nativas cifradas con XOR
        findViewById<TextView>(R.id.tv_app_name)?.text = N.a(N.APP_NAME)
        findViewById<TextView>(R.id.tv_login_subtitle)?.text = N.a(N.LOGIN_SUBTITLE)
        findViewById<TextView>(R.id.tv_label_user)?.text = N.a(N.LABEL_USER)
        userInput.hint = N.a(N.HINT_USER)
        findViewById<TextView>(R.id.tv_label_license_login)?.text = N.a(N.LABEL_LICENSE)
        keyInput.hint = N.a(N.HINT_LICENSE)
        loginButton.text = N.a(N.BTN_LOGIN)
        findViewById<TextView>(R.id.btn_getkey)?.text = N.a(N.BTN_GETKEY)
        findViewById<TextView>(R.id.tv_social_channels)?.text = N.a(N.OFFICIAL_CHANNELS)
        findViewById<TextView>(R.id.btn_state_return)?.text = N.a(N.BTN_RETURN)
        findViewById<View>(R.id.btn_social_whatsapp)?.contentDescription = N.a(N.DESC_WA)
        findViewById<View>(R.id.btn_social_tiktok)?.contentDescription = N.a(N.DESC_TT)

        val savedUser = SecurePrefs.get(this, "saved_username")
        val savedKey = SecurePrefs.get(this, "saved_key")
        userInput.setText(savedUser)
        keyInput.setText(savedKey)

        intent?.data?.takeIf { it.scheme == "freezy" && it.host == "activate" }
            ?.getQueryParameter("key")
            ?.takeIf(String::isNotBlank)
            ?.let { keyInput.setText(it.uppercase()) }

        findViewById<TextView>(R.id.btn_state_return).setOnClickListener { showLogin() }
        loginButton.setOnClickListener {
            val username = userInput.text.toString().trim()
            val key = keyInput.text.toString().trim().uppercase()
            if (username.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, "Completa usuario y licencia.", Toast.LENGTH_SHORT).show()
            } else {
                verifyLicense(username, key)
            }
        }
        findViewById<TextView>(R.id.btn_getkey).setOnClickListener { requestFreeKey() }
        findViewById<View>(R.id.btn_social_whatsapp).setOnClickListener {
            UpdateManager.openUrl(this, N.a(N.WHATSAPP_URL))
        }
        findViewById<View>(R.id.btn_social_tiktok).setOnClickListener {
            UpdateManager.openUrl(this, N.a(N.TIKTOK_URL))
        }
        startSocialRingRotation()

        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val isLoggedIn = prefs.getBoolean("is_logged_in", false)

        if (isLoggedIn && savedUser.isNotEmpty() && savedKey.isNotEmpty()) {
            // Revalidar sesión en segundo plano
            verifyLicense(savedUser, savedKey, isAutoLogin = true)
        } else {
            showLogin()
        }

        showDisclaimerIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.check(this)
    }

    private fun showDisclaimerIfNeeded() {
        val preferences = getSharedPreferences(
            N.a(N.PREFS_NAME),
            Context.MODE_PRIVATE
        )
        val preview = BuildConfig.DEBUG && intent.getBooleanExtra("preview_disclaimer_modal", false)
        if (preferences.getBoolean("public_disclaimer_accepted", false) && !preview) return

        val content = layoutInflater.inflate(R.layout.dialog_public_disclaimer, null)
        content.findViewById<TextView>(R.id.tv_disclaimer_badge)?.text = N.a(N.BADGE_DISCLAIMER)
        content.findViewById<TextView>(R.id.tv_disclaimer_title)?.text = N.a(N.TITLE_DISCLAIMER)
        content.findViewById<TextView>(R.id.tv_disclaimer_subtitle)?.text = N.a(N.SUBTITLE_DISCLAIMER)
        content.findViewById<TextView>(R.id.tv_disclaimer_body)?.text = N.a(N.DISCLAIMER_BODY)
        content.findViewById<TextView>(R.id.btn_accept_risk)?.text = N.a(N.BTN_ACCEPT_RISK)
        content.findViewById<TextView>(R.id.btn_exit_app)?.text = N.a(N.BTN_EXIT_APP)

        val dialog = AlertDialog.Builder(this).setView(content).setCancelable(false).create()
        content.findViewById<TextView>(R.id.btn_accept_risk).setOnClickListener {
            if (!preview) preferences.edit().putBoolean("public_disclaimer_accepted", true).apply()
            dialog.dismiss()
        }
        content.findViewById<TextView>(R.id.btn_exit_app).setOnClickListener { finishAffinity() }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.82f }
            setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun verifyLicense(username: String, key: String, isAutoLogin: Boolean = false) {
        showState(VerificationState.VALIDATING)
        Thread {
            try {
                val endpoint = N.a(N.ENDPOINT)
                val challengeEndpoint = if (endpoint.endsWith("/verify")) {
                    endpoint.removeSuffix("/verify") + "/challenge"
                } else "$endpoint/challenge"
                val verifyEndpoint = if (endpoint.endsWith("/verify")) endpoint else "$endpoint/verify"
                val hwid = N.getHwid(this)
                val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

                val currentVersion = BuildConfig.VERSION_NAME.substringBefore("-")
                val challengeBody = JSONObject().apply {
                    put("key", key)
                    put("hwid", hwid)
                    put("username", username)
                    put("device_model", deviceModel)
                    put("app_version", currentVersion)
                }
                val challenge = postJson(challengeEndpoint, challengeBody)
                if (challenge.first != 200) {
                    rejectFor(challenge.first, extractMessage(challenge.second))
                    return@Thread
                }

                val nonce = JSONObject(challenge.second).optString("nonce")
                if (nonce.isNullOrEmpty()) {
                    rejectFor(403, N.a(N.STATUS_DENIED))
                    return@Thread
                }

                val mac = Mac.getInstance("HmacSHA256").apply {
                    init(SecretKeySpec(N.a(N.HMAC_SECRET).toByteArray(Charsets.UTF_8), "HmacSHA256"))
                }
                val hmac = mac.doFinal(nonce.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                val verifyBody = JSONObject().apply {
                    put("key", key)
                    put("hwid", hwid)
                    put("hmac", hmac)
                    put("app_version", currentVersion)
                }

                val verification = postJson(verifyEndpoint, verifyBody)
                if (verification.first != 200) {
                    rejectFor(verification.first, extractMessage(verification.second))
                    return@Thread
                }

                val response = JSONObject(verification.second)
                if (!response.optBoolean("valid", false)) {
                    rejectFor(403, response.optString("message", N.a(N.STATUS_DENIED)))
                    return@Thread
                }

                SecurePrefs.put(this, "saved_username", username)
                SecurePrefs.put(this, "saved_key", key)
                SecurePrefs.put(this, "activation_date", response.optString("created_at", "--"))
                SecurePrefs.put(this, "expiration_date", response.optString("expires_at", "--"))
                SecurePrefs.put(this, "session_token", response.optString("session_token", ""))

                val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
                prefs.edit().putBoolean("is_logged_in", true).apply()

                runOnUiThread {
                    showState(VerificationState.VALID)
                    window.decorView.postDelayed({
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }, 900)
                }
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) error.printStackTrace()
                runOnUiThread {
                    if (isAutoLogin) {
                        showLogin()
                    } else {
                        showState(VerificationState.NETWORK_ERROR)
                    }
                }
            }
        }.start()
    }

    private fun postJson(url: String, body: JSONObject): Pair<Int, String> {
        val connection = WebSecurity.open(url)
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
    }

    private fun requestFreeKey() {
        val button = findViewById<TextView>(R.id.btn_getkey)
        button.isEnabled = false
        button.text = "GENERANDO..."

        Thread {
            try {
                val endpoint = N.a(N.ENDPOINT)
                val base = endpoint.substringBefore("/api")
                val beginUrl = "$base/api/ads/begin"
                val hwid = N.getHwid(this)
                val response = postJson(beginUrl, JSONObject().apply { put("hwid", hwid) })

                if (response.first == 200) {
                    val token = JSONObject(response.second).optString("token")
                    val getKeyUrl = "$base/getkey?token=$token&hwid=${Uri.encode(hwid)}"
                    runOnUiThread {
                        button.isEnabled = true
                        button.text = N.a(N.BTN_GETKEY)
                        UpdateManager.openUrl(this, getKeyUrl)
                    }
                } else {
                    throw IllegalStateException("HTTP ${response.first}")
                }
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) error.printStackTrace()
                runOnUiThread {
                    button.isEnabled = true
                    button.text = N.a(N.BTN_GETKEY)
                    Toast.makeText(this, "No se pudo iniciar GET KEY.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun rejectFor(code: Int, message: String) {
        runOnUiThread {
            when {
                message.contains("obsolet", true) || message.contains("actualiza", true) || message.contains("mediafire", true) -> {
                    showLogin()
                    UpdateManager.showUpdateModal(this, message)
                }
                code == 503 || message.contains("mantenimiento", true) -> showState(VerificationState.MAINTENANCE, message)
                message.contains("expir", true) -> showState(VerificationState.EXPIRED, message)
                code in 400..499 -> showState(VerificationState.INVALID, message)
                else -> showState(VerificationState.NETWORK_ERROR, message)
            }
        }
    }

    private fun extractMessage(body: String): String = runCatching {
        JSONObject(body).optString("message", N.a(N.STATUS_DENIED))
    }.getOrDefault(N.a(N.STATUS_DENIED))

    private fun showState(state: VerificationState, detail: String? = null) {
        loginLayout.visibility = View.GONE
        splashLayout.visibility = View.VISIBLE
        val logo = findViewById<ImageView>(R.id.iv_splash_logo)
        val badge = findViewById<TextView>(R.id.tv_state_badge)
        val title = findViewById<TextView>(R.id.tv_state_title)
        val message = findViewById<TextView>(R.id.tv_splash_status)
        val progress = findViewById<ProgressBar>(R.id.progress_verification)
        val returnButton = findViewById<TextView>(R.id.btn_state_return)
        stopPulse()
        progress.visibility = View.GONE
        returnButton.visibility = View.GONE
        logo.clearColorFilter()

        when (state) {
            VerificationState.VALIDATING -> {
                logo.setImageResource(R.drawable.freezy_logo)
                badge.text = N.a(N.BADGE_VALIDATING); badge.setTextColor(Color.parseColor("#D996FF"))
                title.text = N.a(N.TITLE_VALIDATING); title.setTextColor(Color.parseColor("#F8F5FC"))
                message.text = detail ?: N.a(N.STATUS_VALIDATING)
                progress.visibility = View.VISIBLE
                startPulse()
            }
            VerificationState.VALID -> {
                logo.setImageResource(R.drawable.ic_cyber_check)
                badge.text = N.a(N.BADGE_VALID); badge.setTextColor(Color.parseColor("#58E6B0"))
                title.text = N.a(N.TITLE_VALID); title.setTextColor(Color.parseColor("#F8F5FC"))
                message.text = detail ?: N.a(N.STATUS_VALID)
            }
            VerificationState.EXPIRED -> renderError(logo, badge, title, message, returnButton, R.drawable.ic_cross_red, N.a(N.BADGE_EXPIRED), N.a(N.TITLE_EXPIRED), detail ?: N.a(N.STATUS_EXPIRED))
            VerificationState.NETWORK_ERROR -> renderError(logo, badge, title, message, returnButton, R.drawable.ic_network_error, N.a(N.BADGE_NETWORK), N.a(N.TITLE_NETWORK), detail ?: N.a(N.STATUS_NETWORK))
            VerificationState.MAINTENANCE -> renderError(logo, badge, title, message, returnButton, R.drawable.ic_maintenance, N.a(N.BADGE_MAINTENANCE), N.a(N.TITLE_MAINTENANCE), detail ?: N.a(N.STATUS_MAINTENANCE), true)
            VerificationState.INVALID -> renderError(logo, badge, title, message, returnButton, R.drawable.ic_cross_red, N.a(N.BADGE_DENIED), N.a(N.TITLE_DENIED), detail ?: N.a(N.STATUS_DENIED_DESC))
        }
    }

    private fun renderError(logo: ImageView, badge: TextView, title: TextView, message: TextView, button: TextView, icon: Int, badgeText: String, titleText: String, detail: String, warning: Boolean = false) {
        val color = Color.parseColor(if (warning) "#FFB84D" else "#FF6977")
        logo.setImageResource(icon)
        badge.text = badgeText; badge.setTextColor(color)
        title.text = titleText; title.setTextColor(color)
        message.text = detail
        button.text = N.a(N.BTN_RETURN)
        button.visibility = View.VISIBLE
    }

    private fun showLogin() {
        stopPulse()
        splashLayout.visibility = View.GONE
        loginLayout.visibility = View.VISIBLE
        loginButton.isEnabled = true
        loginButton.text = N.a(N.BTN_LOGIN)
    }

    private fun startPulse() {
        pulseAnimator = ObjectAnimator.ofFloat(findViewById(R.id.iv_splash_logo), "alpha", 1f, 0.3f, 1f).apply {
            duration = 1_500
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        findViewById<ImageView>(R.id.iv_splash_logo).alpha = 1f
    }

    private fun startSocialRingRotation() {
        listOf(R.id.iv_social_ring_wa, R.id.iv_social_ring_tt).forEachIndexed { index, id ->
            ObjectAnimator.ofFloat(findViewById<View>(id), View.ROTATION, 0f, 360f).apply {
                duration = 3_600L + index * 600L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                start()
            }
        }
    }
}
