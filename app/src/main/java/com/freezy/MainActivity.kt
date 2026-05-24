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

    private var targetPackageToLaunch: String? = null

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

        if (Settings.canDrawOverlays(this)) {
            Logger.log(this, "Permiso Overlay Permitido")
        }
        if (hasUsageStatsPermission()) {
            Logger.log(this, "Permiso de Uso Permitido")
        }

        val btnFreezy = findViewById<Button>(R.id.btn_freezy)
        
        // Animación pulsante llamativa
        val pulseX = ObjectAnimator.ofFloat(btnFreezy, "scaleX", 1f, 1.05f, 1f).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
        }
        val pulseY = ObjectAnimator.ofFloat(btnFreezy, "scaleY", 1f, 1.05f, 1f).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
        }
        pulseX.start()
        pulseY.start()
        
        val btnModeAuto = findViewById<TextView>(R.id.btn_mode_auto)
        val btnModeCustom = findViewById<TextView>(R.id.btn_mode_custom)
        val btnModeManual = findViewById<TextView>(R.id.btn_mode_manual)
        val indicatorView = findViewById<View>(R.id.indicator_view)
        
        val layoutCustomTime = findViewById<View>(R.id.layout_custom_time)
        val tvTimeLabel = findViewById<TextView>(R.id.tv_time_label)
        val seekbarTime = findViewById<SeekBar>(R.id.seekbar_time)
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)

        // Ajustar el ancho del indicador dinámicamente
        indicatorView.post {
            val width = (btnModeAuto.parent as View).width / 3
            indicatorView.layoutParams.width = width
            indicatorView.requestLayout()
            
            // Cargar estado inicial
            val currentMode = prefs.getInt("mode", 0)
            updateModeUI(currentMode, btnModeAuto, btnModeCustom, btnModeManual, indicatorView, layoutCustomTime, false)
        }

        val customTimeFloat = prefs.getFloat("custom_time_float", 3.0f).coerceAtMost(5.0f)
        seekbarTime.max = 50 // Máximo 5.0 segundos (50 / 10)
        seekbarTime.progress = (customTimeFloat * 10).toInt()
        tvTimeLabel.text = String.format("%.1f Segundos", customTimeFloat)

        // Mostrar fechas de licencia
        val tvActivationDate = findViewById<TextView>(R.id.tv_activation_date)
        val tvExpirationDate = findViewById<TextView>(R.id.tv_expiration_date)
        
        tvActivationDate.text = prefs.getString("activation_date", "--")
        tvExpirationDate.text = prefs.getString("expiration_date", "--")

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
                val realProgress = if (progress < 10) 10 else progress // Minimo 1.0s
                val timeFloat = realProgress / 10f
                tvTimeLabel.text = String.format("%.1f Segundos", timeFloat)
                prefs.edit().putFloat("custom_time_float", timeFloat).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val switchAutoLag = findViewById<Switch>(R.id.switch_autolag)
        val btnResetAutoLag = findViewById<Button>(R.id.btn_reset_autolag)
        
        val isPremium = checkIsPremiumLicense(prefs)
        if (!isPremium) {
            prefs.edit()
                .putBoolean("auto_lag_enabled", false)
                .putBoolean("modo_mapeo_activo", false)
                .apply()
        }

        switchAutoLag.isChecked = prefs.getBoolean("auto_lag_enabled", false)
        btnResetAutoLag.visibility = if (switchAutoLag.isChecked) View.VISIBLE else View.GONE

        switchAutoLag.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !checkIsPremiumLicense(prefs)) {
                buttonView.isChecked = false
                Toast.makeText(this, "El modo Auto-Lag requiere una licencia Premium (mínimo 15 días).", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("auto_lag_enabled", isChecked).apply()
            btnResetAutoLag.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                // Si no tiene coordenadas previas guardadas, iniciar el mapeo
                if (!prefs.contains("shoot_left")) {
                    AlertDialog.Builder(this)
                        .setTitle("Modo Auto-Lag")
                        .setMessage("Deberá colocar el círculo sobre su botón de disparo en el juego. Ajústelo al tamaño lo más preciso posible.")
                        .setCancelable(false)
                        .setPositiveButton("Entendido") { _, _ ->
                            iniciarCuentaRegresivaAutoLag()
                        }
                        .setNegativeButton("Cancelar") { dialog, _ ->
                            switchAutoLag.isChecked = false
                            btnResetAutoLag.visibility = View.GONE
                            dialog.dismiss()
                        }
                        .show()
                } else {
                    Toast.makeText(this, "Modo Auto-Lag activado usando mapeo existente.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnResetAutoLag.setOnClickListener {
            prefs.edit()
                .remove("shoot_left")
                .remove("shoot_top")
                .remove("shoot_right")
                .remove("shoot_bottom")
                .putBoolean("modo_mapeo_activo", true)
                .apply()
            
            AlertDialog.Builder(this)
                .setTitle("Restablecer Mapeo")
                .setMessage("Las coordenadas anteriores fueron eliminadas. ¿Deseas iniciar la calibración del botón de disparo ahora?")
                .setPositiveButton("Sí, iniciar") { _, _ ->
                    iniciarCuentaRegresivaAutoLag()
                }
                .setNegativeButton("No, después") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        btnFreezy.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Necesitas dar permiso para mostrar sobre otras apps.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!hasUsageStatsPermission()) {
                Toast.makeText(this, "Necesitas dar permiso de acceso de uso a Freezy.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isAutoLag = prefs.getBoolean("auto_lag_enabled", false)
            if (isAutoLag) {
                // El modo Auto-Lag requiere Root obligatorio para leer /dev/input/event* y bloquear udp
                if (!hasRootAccess()) {
                    Toast.makeText(this, "El modo Auto-Lag requiere permisos de Root obligatorios.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit().putBoolean("use_root", true).apply()
            }

            val useRoot = prefs.getBoolean("use_root", false)
            if (useRoot && !hasRootAccess()) {
                Toast.makeText(this, "Permiso Root no disponible o denegado.", Toast.LENGTH_SHORT).show()
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
        val switchRoot = dialogView.findViewById<Switch>(R.id.switch_root)
        val tvVersion = dialogView.findViewById<TextView>(R.id.tv_version)
        
        // Cargar strings ofuscados
        dialogView.findViewById<TextView>(R.id.tv_title_settings)?.text = NativeBridge.getNativeString(NativeBridge.STRING_TITLE_SETTINGS)
        dialogView.findViewById<TextView>(R.id.tv_label_system)?.text = NativeBridge.getNativeString(NativeBridge.STRING_SYSTEM)
        dialogView.findViewById<Switch>(R.id.switch_root)?.text = NativeBridge.getNativeString(NativeBridge.STRING_ALLOW_ROOT)
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

        switchRoot.isChecked = prefs.getBoolean("use_root", false)

        switchRoot.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasRootAccess()) {
                    Logger.log(this, NativeBridge.getNativeString(NativeBridge.STRING_ROOT_DETECTED))
                    prefs.edit().putBoolean("use_root", true).apply()
                    Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_ROOT_ENABLED), Toast.LENGTH_SHORT).show()
                } else {
                    Logger.log(this, NativeBridge.getNativeString(NativeBridge.STRING_ROOT_NOT_DETECTED))
                    switchRoot.isChecked = false
                    Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_ROOT_DENIED), Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.edit().putBoolean("use_root", false).apply()
            }
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
            android.widget.Toast.makeText(this, "Error: Datos de configuración incompletos", android.widget.Toast.LENGTH_SHORT).show()
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
                        android.widget.Toast.makeText(this@MainActivity, "Error de validación al iniciar", android.widget.Toast.LENGTH_LONG).show()
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
                                    .setTitle("Aviso de Actualización")
                                    .setMessage(warning)
                                    .setPositiveButton("Entendido") { _, _ ->
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
                    android.widget.Toast.makeText(this@MainActivity, "Por favor espere un momento.", android.widget.Toast.LENGTH_LONG).show()
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
            android.widget.Toast.makeText(this, "Free Fire no detectado", android.widget.Toast.LENGTH_SHORT).show()
        }
    }



    private fun checkDebugger() {
        if (android.os.Debug.isDebuggerConnected()) {
            Toast.makeText(this, "Debugger detectado.", Toast.LENGTH_SHORT).show()
            Logger.log(this, "Alerta: Debugger conectado.")
        }
    }

    private fun updateModeUI(mode: Int, btnA: TextView, btnC: TextView, btnM: TextView, indicator: View, layoutCustomTime: View, animate: Boolean) {
        val targetX = mode * indicator.width.toFloat()
        if (animate) {
            ObjectAnimator.ofFloat(indicator, "translationX", targetX).apply {
                duration = 300
                start()
            }
        } else {
            indicator.translationX = targetX
        }

        btnA.setTextColor(if (mode == 0) Color.WHITE else Color.parseColor("#888888"))
        btnC.setTextColor(if (mode == 1) Color.WHITE else Color.parseColor("#888888"))
        btnM.setTextColor(if (mode == 2) Color.WHITE else Color.parseColor("#888888"))

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
        val packages = listOf("com.dts.freefiremax", "com.dts.freefireth")
        val pm = packageManager
        
        for (pkg in packages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg // Retorna el primero que encuentre
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }
        }
        return null // No se encontró ninguna versión
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

    private fun iniciarCuentaRegresivaAutoLag() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Iniciando")
            .setMessage("Abriendo Free Fire en 3 segundos...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        var count = 3
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                if (count > 0) {
                    progressDialog.setMessage("Abriendo Free Fire en $count segundos...")
                    count--
                    handler.postDelayed(this, 1000)
                } else {
                    progressDialog.dismiss()
                    getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("modo_mapeo_activo", true)
                        .apply()
                    checkLicenseAndLaunch()
                }
            }
        })
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

    private fun checkIsPremiumLicense(prefs: SharedPreferences): Boolean {
        var isPremiumLicense = false
        val actDate = prefs.getString("activation_date", "")
        val expDate = prefs.getString("expiration_date", "")
        if (!actDate.isNullOrEmpty() && !expDate.isNullOrEmpty() && actDate != "--" && expDate != "--") {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val d1 = sdf.parse(actDate)
                val d2 = sdf.parse(expDate)
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

