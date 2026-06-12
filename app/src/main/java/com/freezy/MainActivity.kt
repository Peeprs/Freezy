package com.freezy

import com.system.network.ui.R


import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

import android.content.pm.PackageManager
import android.widget.Button
import android.widget.Toast
import android.widget.Switch
import android.widget.TextView
import android.widget.SeekBar
import android.widget.ProgressBar
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.os.Process
import android.net.VpnService
import android.os.Handler
import android.os.Looper

class MainActivity : AppCompatActivity() {
    private var rootGlowAnimator: android.animation.ValueAnimator? = null

    private var targetPackageToLaunch: String? = null

    private val licenseHandler = Handler(Looper.getMainLooper())
    private var licenseRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        

        checkDebugger()
        
        // Pedir permiso de superposición si no lo tenemos
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivityForResult(intent, 123)
        }

        // Pedir permiso de uso de datos
        if (!hasUsageStatsPermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_USAGE_ACCESS_REQ), Toast.LENGTH_LONG).show()
        }

        // Pedir ignorar optimizaciones de bateria (Vital para gama baja y antiban)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Para evitar desincronizacion (antiban en gama baja), selecciona SIN RESTRICCIONES en el ahorro de bateria para Freezy.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (Settings.canDrawOverlays(this)) {
            Logger.log(this, "Permiso Overlay Permitido")
        }
        if (hasUsageStatsPermission()) {
            Logger.log(this, "Permiso de Uso Permitido")
        }

        val btnFreezy = findViewById<Button>(R.id.btn_freezy)
        
        
        val btnModeAuto = findViewById<TextView>(R.id.btn_mode_auto)
        val btnModeCustom = findViewById<TextView>(R.id.btn_mode_custom)
        val btnModeManual = findViewById<TextView>(R.id.btn_mode_manual)
        val indicatorView = findViewById<View>(R.id.indicator_view)
        
        val layoutCustomTime = findViewById<View>(R.id.layout_custom_time)
        val tvTimeLabel = findViewById<TextView>(R.id.tv_time_label)
        val seekbarTime = findViewById<SeekBar>(R.id.seekbar_time)
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)

        val progressLicenseDays = findViewById<ProgressBar>(R.id.progress_license_days)
        val viewLedStatus = findViewById<View>(R.id.view_led_status)

        // Pulsing animation for status LED
        viewLedStatus?.let { led ->
            val pulseAlpha = ObjectAnimator.ofFloat(led, "alpha", 1f, 0.4f, 1f).apply {
                duration = 1200
                repeatCount = ObjectAnimator.INFINITE
            }
            pulseAlpha.start()
        }

        // Safe License Progress Calculation
        startLicenseCountdown()

        // Ajustar el ancho del indicador dinámicamente
        indicatorView.post {
            val parent = btnModeAuto.parent as View
            val padding = parent.paddingLeft + parent.paddingRight
            val width = (parent.width - padding) / 3
            indicatorView.layoutParams.width = width
            indicatorView.requestLayout()
            
            // Cargar estado inicial
            val currentMode = prefs.getInt("mode", 0)
            updateModeUI(currentMode, btnModeAuto, btnModeCustom, btnModeManual, indicatorView, layoutCustomTime, false)
        }

        val customTimeFloat = prefs.getFloat("custom_time_float", 3.0f).coerceAtLeast(1.0f).coerceAtMost(5.0f)
        seekbarTime.max = 50 // Máximo 5.0 segundos (50 / 10)
        seekbarTime.progress = (customTimeFloat * 10).toInt()
        tvTimeLabel.text = String.format("%.1f Segundos", customTimeFloat)

        // Root/No Root Selector Logic
        val btnModeNoroot = findViewById<TextView>(R.id.btn_mode_noroot)
        val btnModeRoot = findViewById<TextView>(R.id.btn_mode_root)
        val indicatorRootView = findViewById<View>(R.id.indicator_root_view)

        fun updateRootUI(useRoot: Boolean, animate: Boolean) {
            val parent = btnModeNoroot.parent as View
            val padding = parent.paddingLeft + parent.paddingRight
            val width = (parent.width - padding) / 2
            indicatorRootView.layoutParams.width = width
            indicatorRootView.requestLayout()

            val targetX = if (useRoot) width.toFloat() else 0f
            if (animate) {
                android.animation.ObjectAnimator.ofFloat(indicatorRootView, "translationX", targetX).apply {
                    duration = 300
                    start()
                }
            } else {
                indicatorRootView.translationX = targetX
            }

            val currentMode = prefs.getInt("mode", 0)

            if (useRoot) {
                btnModeNoroot.setTextColor(Color.parseColor("#6E7582"))
                btnModeRoot.setTextColor(Color.parseColor("#F5F6F8"))
                
                btnFreezy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5900")))
                btnFreezy.setTextColor(Color.parseColor("#F5F6F8"))
                
                // Color dynamically for seekbar and progress using safe platform Tint APIs
                seekbarTime.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5900"))
                seekbarTime.thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5900"))
                progressLicenseDays.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5900"))

                viewLedStatus?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5900"))
                
                if (rootGlowAnimator == null) {
                    val rootDrawable = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 100f * resources.displayMetrics.density
                    }
                    indicatorRootView.background = rootDrawable
                    
                    rootGlowAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 800
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        repeatMode = android.animation.ValueAnimator.REVERSE
                        addUpdateListener { anim ->
                            val fraction = anim.animatedFraction
                            
                            // Fondo: Orange profundo a naranja brillante
                            val solidColor = androidx.core.graphics.ColorUtils.blendARGB(
                                Color.parseColor("#5C2000"), 
                                Color.parseColor("#B33E00"), 
                                fraction
                            )
                            rootDrawable.setColor(solidColor)
                            
                            // Outline: Neon orange a light neon orange
                            val strokeWidth = (1 + fraction * 2).toInt() * resources.displayMetrics.density.toInt()
                            val strokeColor = androidx.core.graphics.ColorUtils.blendARGB(
                                Color.parseColor("#FF5900"), 
                                Color.parseColor("#FF8F55"), 
                                fraction
                            )
                            rootDrawable.setStroke(strokeWidth, strokeColor)
                            
                            indicatorRootView.scaleX = 1f
                            indicatorRootView.scaleY = 1f
                            indicatorRootView.elevation = 0f
                        }
                        start()
                    }
                }
            } else {
                btnModeRoot.setTextColor(Color.parseColor("#6E7582"))
                btnModeNoroot.setTextColor(Color.parseColor("#0D0E12"))
                
                btnFreezy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00FF9D")))
                btnFreezy.setTextColor(Color.parseColor("#0D0E12"))
                
                // Color dynamically for seekbar and progress using safe platform Tint APIs
                seekbarTime.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00FF9D"))
                seekbarTime.thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00FF9D"))
                progressLicenseDays.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00FF9D"))

                viewLedStatus?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00FF9D"))
                
                rootGlowAnimator?.cancel()
                rootGlowAnimator = null
                
                indicatorRootView.background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.shape_pill_white)
                indicatorRootView.scaleX = 1f
                indicatorRootView.scaleY = 1f
                indicatorRootView.elevation = 0f
            }

            // Actualizar la interfaz de los selectores de modo (auto/custom/manual) inmediatamente para que coincidan los colores
            updateModeUI(currentMode, btnModeAuto, btnModeCustom, btnModeManual, indicatorView, layoutCustomTime, false)
        }

        indicatorRootView.post {
            val useRoot = prefs.getBoolean("use_root", false)
            updateRootUI(useRoot, false)
        }

        // Game Selector Logic
        val btnGameFF = findViewById<View>(R.id.btn_game_ff)
        val btnGameMax = findViewById<View>(R.id.btn_game_max)
        val tvGameFF = findViewById<TextView>(R.id.tv_game_ff)
        val tvGameMax = findViewById<TextView>(R.id.tv_game_max)
        val indicatorGameView = findViewById<View>(R.id.indicator_game_view)
        
        findViewById<TextView>(R.id.tv_game_title)?.text = NativeBridge.getNativeString(NativeBridge.STRING_GAME_TARGET)
        tvGameFF.text = NativeBridge.getNativeString(NativeBridge.STRING_FREE_FIRE)
        tvGameMax.text = NativeBridge.getNativeString(NativeBridge.STRING_FF_MAX)

        fun updateGameUI(isMax: Boolean, animate: Boolean) {
            val parent = btnGameFF.parent as View
            val padding = parent.paddingLeft + parent.paddingRight
            val width = (parent.width - padding) / 2
            indicatorGameView.layoutParams.width = width
            indicatorGameView.requestLayout()

            val targetX = if (isMax) width.toFloat() else 0f
            if (animate) {
                android.animation.ObjectAnimator.ofFloat(indicatorGameView, "translationX", targetX).apply {
                    duration = 300
                    start()
                }
            } else {
                indicatorGameView.translationX = targetX
            }

            if (isMax) {
                tvGameFF.setTextColor(Color.parseColor("#888888"))
                tvGameMax.setTextColor(Color.parseColor("#FFFFFF"))
            } else {
                tvGameFF.setTextColor(Color.parseColor("#FFFFFF"))
                tvGameMax.setTextColor(Color.parseColor("#888888"))
            }
        }

        indicatorGameView.post {
            val isMax = prefs.getBoolean("use_ff_max", false)
            updateGameUI(isMax, false)
        }

        btnGameFF.setOnClickListener {
            prefs.edit().putBoolean("use_ff_max", false).apply()
            updateGameUI(false, true)
        }

        btnGameMax.setOnClickListener {
            prefs.edit().putBoolean("use_ff_max", true).apply()
            updateGameUI(true, true)
        }

        btnModeNoroot.setOnClickListener {
            prefs.edit().putBoolean("use_root", false).apply()
            updateRootUI(false, true)
            if (isServiceRunning(BubbleService::class.java)) {
                val serviceIntent = Intent(this, BubbleService::class.java).apply {
                    action = "UPDATE_BUBBLE_MODE"
                }
                startService(serviceIntent)
            }
        }

        btnModeRoot.setOnClickListener {
            if (hasRootAccess()) {
                prefs.edit().putBoolean("use_root", true).apply()
                updateRootUI(true, true)
                Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_ROOT_ACTIVATED), Toast.LENGTH_SHORT).show()
                if (isServiceRunning(BubbleService::class.java)) {
                    val serviceIntent = Intent(this, BubbleService::class.java).apply {
                        action = "UPDATE_BUBBLE_MODE"
                    }
                    startService(serviceIntent)
                }
            } else {
                prefs.edit().putBoolean("use_root", false).apply()
                updateRootUI(false, true)
                
                // Mostrar texto de error en rojo inline usando NativeBridge para seguridad XOR
                val tvRootError = findViewById<TextView>(R.id.tv_root_error)
                tvRootError?.text = NativeBridge.getNativeString(NativeBridge.STRING_ROOT_REQ)
                tvRootError?.visibility = View.VISIBLE
                
                Handler(Looper.getMainLooper()).postDelayed({
                    tvRootError.visibility = View.GONE
                }, 3500)
            }
        }

        // Mostrar fechas de licencia
        val tvActivationDate = findViewById<TextView>(R.id.tv_activation_date)
        val tvExpirationDate = findViewById<TextView>(R.id.tv_expiration_date)
        
        val actStrRaw = prefs.getString("activation_date", "") ?: ""
        val expStrRaw = prefs.getString("expiration_date", "") ?: ""
        val actDateObj = parseDateTime(actStrRaw)
        val expDateObj = parseDateTime(expStrRaw)
        
        val displaySdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        tvActivationDate.text = if (actDateObj != null) displaySdf.format(actDateObj) else actStrRaw.takeIf { it.isNotEmpty() } ?: "--"
        tvExpirationDate.text = if (expDateObj != null) displaySdf.format(expDateObj) else expStrRaw.takeIf { it.isNotEmpty() } ?: "--"

        // Cargar strings ofuscados de C++ para los títulos y etiquetas
        findViewById<TextView>(R.id.tv_title_activation)?.text = NativeBridge.getNativeString(NativeBridge.STRING_TITLE_ACTIVATION)
        findViewById<TextView>(R.id.tv_title_license)?.text = NativeBridge.getNativeString(NativeBridge.STRING_TITLE_LICENSE)
        
        findViewById<TextView>(R.id.btn_mode_auto)?.text = NativeBridge.getNativeString(NativeBridge.STRING_MODE_AUTO)
        findViewById<TextView>(R.id.btn_mode_custom)?.text = NativeBridge.getNativeString(NativeBridge.STRING_MODE_CUSTOM)
        findViewById<TextView>(R.id.btn_mode_manual)?.text = NativeBridge.getNativeString(NativeBridge.STRING_MODE_MANUAL)
        
        findViewById<TextView>(R.id.tv_label_seconds)?.text = NativeBridge.getNativeString(NativeBridge.STRING_SECONDS_TO_FREEZE)
        tvTimeLabel.text = "3${NativeBridge.getNativeString(NativeBridge.STRING_SECONDS)}"
        
        findViewById<TextView>(R.id.tv_label_activation)?.text = NativeBridge.getNativeString(NativeBridge.STRING_ACTIVATION)
        findViewById<TextView>(R.id.tv_label_expiration)?.text = NativeBridge.getNativeString(NativeBridge.STRING_EXPIRATION)

        btnFreezy.text = NativeBridge.getNativeString(NativeBridge.STRING_BTN_START)
        findViewById<Button>(R.id.btn_close_bubble)?.text = NativeBridge.getNativeString(NativeBridge.STRING_BTN_CLOSE_BUBBLE)



        btnModeAuto.setOnClickListener {
            prefs.edit().putInt("mode", 0).apply()
            updateModeUI(0, btnModeAuto, btnModeCustom, btnModeManual, indicatorView, layoutCustomTime, true)
        }
        btnModeCustom.setOnClickListener {
            prefs.edit().putInt("mode", 1).apply()
            updateModeUI(1, btnModeAuto, btnModeCustom, btnModeManual, indicatorView, layoutCustomTime, true)
        }
        btnModeManual.setOnClickListener {
            prefs.edit().putInt("mode", 2).apply()
            updateModeUI(2, btnModeAuto, btnModeCustom, btnModeManual, indicatorView, layoutCustomTime, true)
        }

        seekbarTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress < 10) {
                    seekBar?.progress = 10
                    return
                }
                val timeFloat = progress / 10f
                tvTimeLabel.text = String.format("%.1f Segundos", timeFloat)
                prefs.edit().putFloat("custom_time_float", timeFloat).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    val progress = it.progress
                    val realProgress = if (progress < 10) 10 else progress
                    val timeFloat = realProgress / 10f
                    prefs.edit().putFloat("custom_time_float", timeFloat).commit()
                }
            }
        })



        btnFreezy.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_OVERLAY_TOAST), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!hasUsageStatsPermission()) {
                Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_USAGE_TOAST), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }



            val useRoot = prefs.getBoolean("use_root", false)
            if (useRoot && !hasRootAccess()) {
                Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_ROOT_DENIED_TOAST), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            checkLicenseAndLaunch()
        }

        val btnCloseBubble = findViewById<Button>(R.id.btn_close_bubble)
        btnCloseBubble.setOnClickListener {
            val serviceIntent = Intent(this, BubbleService::class.java)
            stopService(serviceIntent)
            btnCloseBubble.visibility = View.GONE
            Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_BTN_CLOSE_BUBBLE), Toast.LENGTH_SHORT).show()
        }
        
    }

    override fun onResume() {
        super.onResume()
        // Verificación real para mostrar/ocultar el botón de cierre
        val isRunning = isServiceRunning(BubbleService::class.java)
        findViewById<Button>(R.id.btn_close_bubble).visibility = if (isRunning) View.VISIBLE else View.GONE
        
        startLicenseCountdown()

        // Recargar el valor de tiempo guardado para asegurar consistencia al volver a entrar
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val customTimeFloat = prefs.getFloat("custom_time_float", 3.0f).coerceAtLeast(1.0f).coerceAtMost(5.0f)
        val seekbarTime = findViewById<SeekBar>(R.id.seekbar_time)
        val tvTimeLabel = findViewById<TextView>(R.id.tv_time_label)
        seekbarTime?.progress = (customTimeFloat * 10).toInt()
        tvTimeLabel?.text = String.format("%.1f Segundos", customTimeFloat)
    }

    override fun onPause() {
        super.onPause()
        stopLicenseCountdown()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun showSettingsDialog(prefs: android.content.SharedPreferences) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val tvVersion = dialogView.findViewById<TextView>(R.id.tv_version)
        
        // Cargar strings ofuscados
        dialogView.findViewById<TextView>(R.id.tv_title_settings)?.text = NativeBridge.getNativeString(NativeBridge.STRING_TITLE_SETTINGS)
        dialogView.findViewById<TextView>(R.id.tv_label_info)?.text = NativeBridge.getNativeString(NativeBridge.STRING_INFO)
        dialogView.findViewById<TextView>(R.id.tv_version)?.text = "${NativeBridge.getNativeString(NativeBridge.STRING_APP_VERSION)}: v2.2"
        dialogView.findViewById<TextView>(R.id.tv_label_support)?.text = NativeBridge.getNativeString(NativeBridge.STRING_SUPPORT)
        dialogView.findViewById<Button>(R.id.btn_view_logs)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGS_VER_LOGS)
        dialogView.findViewById<Button>(R.id.btn_logout)?.text = NativeBridge.getNativeString(NativeBridge.STRING_LOGOUT)

        val btnClose = dialogView.findViewById<TextView>(R.id.btn_close_dialog_x)
        val btnLogout = dialogView.findViewById<Button>(R.id.btn_logout)



        btnLogout.setOnClickListener {
            prefs.edit()
                .putBoolean("is_logged_in", false)
                .apply()
            
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        val btnViewLogs = dialogView.findViewById<Button>(R.id.btn_view_logs)
        btnViewLogs.setOnClickListener {
            showLogsDialog()
        }

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "${NativeBridge.getNativeString(NativeBridge.STRING_APP_VERSION)}${pInfo.versionName}"
        } catch (e: Exception) {
            tvVersion.text = "${NativeBridge.getNativeString(NativeBridge.STRING_APP_VERSION)}v1.0"
        }



        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnClose.setOnClickListener {
            dialog.dismiss()
        }



        dialog.show()
    }

    private fun showLogsDialog() {
        val scrollView = android.widget.ScrollView(this)
        val textView = android.widget.TextView(this).apply {
            text = Logger.getLogs(this@MainActivity)
            setPadding(32, 32, 32, 32)
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        scrollView.addView(textView)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(NativeBridge.getNativeString(NativeBridge.STRING_LOGS_TITLE))
            .setView(scrollView)
            .setPositiveButton(NativeBridge.getNativeString(NativeBridge.STRING_LOGS_CLOSE), null)
            .setNegativeButton(NativeBridge.getNativeString(NativeBridge.STRING_LOGS_CLEAR)) { _, _ ->
                Logger.clearLogs(this)
                Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_LOGS_CLEARED), Toast.LENGTH_SHORT).show()
            }
            .create()

        dialog.show()
    }

    private fun checkLicenseAndLaunch() {
        val prefs = getSharedPreferences("FreezyPrefs", android.content.Context.MODE_PRIVATE)
        val endpointUrl = prefs.getString("secure_endpoint", "") ?: ""
        val key = prefs.getString("saved_key", "") ?: ""
        val username = prefs.getString("saved_username", "") ?: ""
        val hwid = NativeBridge.getNativeHWID()
        val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        if (endpointUrl.isEmpty() || key.isEmpty() || username.isEmpty()) {
            android.widget.Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_INCOMPLETE_DATA), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        android.widget.Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_VALIDATING), android.widget.Toast.LENGTH_SHORT).show()
        
        val btnFreezy = findViewById<Button>(R.id.btn_freezy)
        btnFreezy.isEnabled = false
        btnFreezy.alpha = 0.5f

        Thread {
            try {
                val challengeEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl.replace("/verify", "/challenge") else "$endpointUrl/challenge"
                val verifyEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl else "$endpointUrl/verify"

                val challengeConn = java.net.URL(challengeEndpoint).openConnection() as java.net.HttpURLConnection
                challengeConn.requestMethod = "POST"
                challengeConn.setRequestProperty("Content-Type", "application/json")
                challengeConn.connectTimeout = 30000
                challengeConn.readTimeout = 30000
                challengeConn.doOutput = true

                val currentAppVersion = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "1.08" }
                val challengeJson = "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"username\": \"$username\", \"device_model\": \"$deviceModel\", \"app_version\": \"$currentAppVersion\"}"
                challengeConn.outputStream.write(challengeJson.toByteArray(Charsets.UTF_8))

                if (challengeConn.responseCode != 200) {
                    runOnUiThread {
                        btnFreezy.isEnabled = true
                        btnFreezy.alpha = 1.0f
                        android.widget.Toast.makeText(this@MainActivity, NativeBridge.getNativeString(NativeBridge.STRING_VALIDATION_ERROR_INIT), android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val nonce = org.json.JSONObject(challengeConn.inputStream.bufferedReader().readText()).getString("nonce")

                val HWID_PRIVADO = NativeBridge.getHmacSecret()
                val algorithm = "HmacSHA256"
                val mac = javax.crypto.Mac.getInstance(algorithm)
                mac.init(javax.crypto.spec.SecretKeySpec(HWID_PRIVADO.toByteArray(Charsets.UTF_8), algorithm))
                val hmacHex = mac.doFinal(nonce.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

                val verifyConn = java.net.URL(verifyEndpoint).openConnection() as java.net.HttpURLConnection
                verifyConn.requestMethod = "POST"
                verifyConn.setRequestProperty("Content-Type", "application/json")
                verifyConn.connectTimeout = 30000
                verifyConn.readTimeout = 30000
                verifyConn.doOutput = true

                val verifyJson = "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"hmac\": \"$hmacHex\", \"app_version\": \"$currentAppVersion\"}"
                verifyConn.outputStream.write(verifyJson.toByteArray(Charsets.UTF_8))

                val responseCode = verifyConn.responseCode
                if (responseCode == 200) {
                    val responseBody = verifyConn.inputStream.bufferedReader().readText()
                    val jsonResponse = org.json.JSONObject(responseBody)
                    val isValid = jsonResponse.optBoolean("valid", false)

                    runOnUiThread {
                        btnFreezy.isEnabled = true
                        btnFreezy.alpha = 1.0f
                        if (isValid) {
                            Logger.log(this@MainActivity, "Licencia Validada al iniciar")
                            
                            val warning = jsonResponse.optString("update_warning", "")
                            if (warning.isNotEmpty()) {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle(NativeBridge.getNativeString(NativeBridge.STRING_UPDATE_TITLE))
                                    .setMessage(warning)
                                    .setPositiveButton(NativeBridge.getNativeString(NativeBridge.STRING_UNDERSTOOD)) { _, _ ->
                                        proceedWithLaunch()
                                    }
                                    .setCancelable(false)
                                    .show()
                            } else {
                                proceedWithLaunch()
                            }
                        } else {
                            val message = jsonResponse.optString("message", "Licencia inválida")
                            android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
                            Logger.log(this@MainActivity, "Fallo de licencia al iniciar: $message")
                        }
                    }
                } else {
                    val errorBody = verifyConn.errorStream?.bufferedReader()?.readText() ?: ""
                    val serverMessage = try {
                        org.json.JSONObject(errorBody).optString("message", "Error: $responseCode")
                    } catch (e: Exception) {
                        "Error: $responseCode"
                    }
                    runOnUiThread {
                        btnFreezy.isEnabled = true
                        btnFreezy.alpha = 1.0f
                        android.widget.Toast.makeText(this@MainActivity, serverMessage, android.widget.Toast.LENGTH_LONG).show()
                        Logger.log(this@MainActivity, "Fallo al iniciar: $serverMessage")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnFreezy.isEnabled = true
                    btnFreezy.alpha = 1.0f
                    android.widget.Toast.makeText(this@MainActivity, NativeBridge.getNativeString(NativeBridge.STRING_PLEASE_WAIT), android.widget.Toast.LENGTH_LONG).show()
                    Logger.log(this@MainActivity, "Error de conexión al iniciar: ${e.message}")
                }
            }
        }.start()
    }

    private fun proceedWithLaunch() {
        val targetPackage = detectFreeFire()

        if (targetPackage != null) {
            targetPackageToLaunch = targetPackage
            val vpnIntent = android.net.VpnService.prepare(this)
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, 124)
            } else {
                launchGameAndBubble()
            }
        } else {
            android.widget.Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_FF_NOT_DETECTED), android.widget.Toast.LENGTH_SHORT).show()
        }
    }



    private fun checkDebugger() {
        if (android.os.Debug.isDebuggerConnected()) {
            Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_DEBUGGER_DETECTED), Toast.LENGTH_SHORT).show()
            Logger.log(this, "Alerta: Debugger conectado.")
        }
    }

    private fun updateModeUI(mode: Int, btnA: TextView, btnC: TextView, btnM: TextView, indicator: View, layoutCustomTime: View, animate: Boolean) {
        val parent = btnA.parent as View
        val padding = parent.paddingLeft + parent.paddingRight
        val segmentWidth = if (parent.width > 0) (parent.width - padding) / 3f else indicator.layoutParams.width.toFloat()
        val targetX = mode * segmentWidth
        if (animate) {
            ObjectAnimator.ofFloat(indicator, "translationX", targetX).apply {
                duration = 300
                start()
            }
        } else {
            indicator.translationX = targetX
        }

        val useRoot = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).getBoolean("use_root", false)
        if (useRoot) {
            indicator.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_pill_purple)
            findViewById<TextView>(R.id.tv_time_label)?.setTextColor(Color.parseColor("#FF5900"))
            
            btnA.setTextColor(if (mode == 0) Color.parseColor("#F5F6F8") else Color.parseColor("#6E7582"))
            btnC.setTextColor(if (mode == 1) Color.parseColor("#F5F6F8") else Color.parseColor("#6E7582"))
            btnM.setTextColor(if (mode == 2) Color.parseColor("#F5F6F8") else Color.parseColor("#6E7582"))
        } else {
            indicator.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_pill_white)
            findViewById<TextView>(R.id.tv_time_label)?.setTextColor(Color.parseColor("#00FF9D"))
            
            btnA.setTextColor(if (mode == 0) Color.parseColor("#0D0E12") else Color.parseColor("#6E7582"))
            btnC.setTextColor(if (mode == 1) Color.parseColor("#0D0E12") else Color.parseColor("#6E7582"))
            btnM.setTextColor(if (mode == 2) Color.parseColor("#0D0E12") else Color.parseColor("#6E7582"))
        }

        layoutCustomTime.visibility = if (mode == 1) View.VISIBLE else View.GONE
    }

    private fun launchGameAndBubble() {
        val pkg = targetPackageToLaunch ?: return
        
        // 1. Lanzamos el juego automáticamente
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            startActivity(launchIntent)
        }

        // 2. Lanzamos nuestra Burbuja Flotante (BubbleService)
        val serviceIntent = Intent(this, BubbleService::class.java).apply {
            putExtra("TARGET_PACKAGE", pkg)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun detectFreeFire(): String? {
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val useMax = prefs.getBoolean("use_ff_max", false)
        
        val primaryPkg = if (useMax) "com.dts.freefiremax" else "com.dts.freefireth"
        val secondaryPkg = if (useMax) "com.dts.freefireth" else "com.dts.freefiremax"
        
        val pm = packageManager
        
        try {
            pm.getPackageInfo(primaryPkg, 0)
            return primaryPkg
        } catch (e: PackageManager.NameNotFoundException) {
            try {
                pm.getPackageInfo(secondaryPkg, 0)
                // Si el primario no está, retorna el secundario (con un log útil si tuvieras)
                return secondaryPkg
            } catch (e2: PackageManager.NameNotFoundException) {
                return null
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c echo test")
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extrae la URL base del servidor (ej: "https://mi-servidor.com")
     * reutilizando el endpoint ya ofuscado en C++ de LoginActivity.
     * Si falla, usa un fallback guardado en prefs por LoginActivity.
     */
    private fun getServerBaseUrl(): String {
        return try {
            val fullUrl = getSecureEndpoint() // ej: "https://host.com/api/keys/verify"
            val url = java.net.URL(fullUrl)
            val port = if (url.port != -1) ":${url.port}" else ""
            "${url.protocol}://${url.host}$port"
        } catch (e: Exception) {
            // Fallback: base guardada durante el login
            getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
                .getString("server_base_url", "https://licencias-freezy.onrender.com/api") ?: ""
        }
    }



    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 124 && resultCode == RESULT_OK) {
            launchGameAndBubble()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
            showSettingsDialog(prefs)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun parseDateTime(dateStr: String): java.util.Date? {
        if (dateStr.isEmpty() || dateStr == "--") return null
        val formats = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "dd/MM/yyyy"
        )
        for (format in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(format, java.util.Locale.getDefault())
                if (format.contains("'Z'")) {
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                val date = sdf.parse(dateStr)
                if (date != null) return date
            } catch (e: Exception) {
                // Try next format
            }
        }
        return null
    }

    private fun startLicenseCountdown() {
        licenseRunnable?.let { licenseHandler.removeCallbacks(it) }

        val progressLicenseDays = findViewById<ProgressBar>(R.id.progress_license_days)
        val tvLicensePercent = findViewById<TextView>(R.id.tv_license_percent)
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)

        val actStr = prefs.getString("activation_date", "") ?: ""
        val expStr = prefs.getString("expiration_date", "") ?: ""

        var actDate = parseDateTime(actStr)
        var expDate = parseDateTime(expStr)

        if (actDate == null && actStr.isNotEmpty()) {
            try { actDate = java.util.Date(java.lang.Long.parseLong(actStr)) } catch (e: Exception) {}
        }
        if (expDate == null && expStr.isNotEmpty()) {
            try { expDate = java.util.Date(java.lang.Long.parseLong(expStr)) } catch (e: Exception) {}
        }

        if (actDate == null || expDate == null) {
            progressLicenseDays?.progress = 100
            tvLicensePercent?.text = "--"
            return
        }

        val actTime = actDate.time
        var expTime = expDate.time

        // Si la activación y expiración ocurren en el mismo milisegundo (ej. formatos truncados sin hora)
        // se asume que la licencia expira al final de ese día (+ 23h 59m 59s).
        if (actTime == expTime) {
            expTime += 24 * 60 * 60 * 1000 - 1000
        }

        val totalDuration = expTime - actTime

        val runnable = object : Runnable {
            override fun run() {
                val today = java.util.Date()
                val remainingMs = expTime - today.time

                if (remainingMs > 0) {
                    val elapsed = today.time - actTime
                    val progressVal = if (totalDuration > 0) {
                        val percent = 100 - ((elapsed.toFloat() / totalDuration.toFloat()) * 100).toInt()
                        percent.coerceIn(0, 100)
                    } else {
                        100
                    }
                    progressLicenseDays?.progress = progressVal

                    val seconds = (remainingMs / 1000) % 60
                    val minutes = (remainingMs / (1000 * 60)) % 60
                    val hours = (remainingMs / (1000 * 60 * 60)) % 24
                    val days = remainingMs / (1000 * 60 * 60 * 24)

                    val countdownText = String.format(
                        java.util.Locale.getDefault(),
                        "%dd %02dh %02dm %02ds",
                        days, hours, minutes, seconds
                    )
                    tvLicensePercent?.text = countdownText

                    licenseHandler.postDelayed(this, 1000)
                } else {
                    progressLicenseDays?.progress = 0
                    tvLicensePercent?.text = "Expirado"
                }
            }
        }

        licenseRunnable = runnable
        licenseHandler.post(runnable)
    }

    private fun stopLicenseCountdown() {
        licenseRunnable?.let {
            licenseHandler.removeCallbacks(it)
            licenseRunnable = null
        }
    }

    private fun isPremiumLicense(): Boolean {
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        var isPremiumLicense = false
        val actDate = prefs.getString("activation_date", "")
        val expDate = prefs.getString("expiration_date", "")
        if (!actDate.isNullOrEmpty() && !expDate.isNullOrEmpty() && actDate != "--" && expDate != "--") {
            try {
                val d1 = parseDateTime(actDate)
                val d2 = parseDateTime(expDate)
                if (d1 != null && d2 != null) {
                    val diffMs = d2.time - d1.time
                    val diffDays = java.util.concurrent.TimeUnit.DAYS.convert(diffMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (diffDays >= 14) { // 14 días de diferencia para abarcar licencias de 15 días
                        isPremiumLicense = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return isPremiumLicense
    }

    // Carga librería nativa para obtener el endpoint ofuscado
    companion object {
        init {
            System.loadLibrary("freezy_net")
        }
    }
    private external fun getSecureEndpoint(): String
}

