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
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)
        

        // Migración puntual: credenciales y URLs pasan de texto plano a AES-GCM
        SecurePrefs.migrateLegacy(this)
        startAntiDebugChecks()
        
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
                    Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_BATTERY_HINT), Toast.LENGTH_LONG).show()
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

        setupBottomNavigation()
        setupExtrasSection()

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

        // Siempre forzar feature 0 (Fake Lag)
        prefs.edit().putInt("selected_feature", 0).apply()

        val customTimeFloat = prefs.getFloat("custom_time_float", 3.0f).coerceAtLeast(1.0f).coerceAtMost(5.0f)
        seekbarTime.max = 50 // Máximo 5.0 segundos (50 / 10)
        seekbarTime.progress = (customTimeFloat * 10).toInt()
        tvTimeLabel.text = String.format("%.1f Segundos", customTimeFloat)

        // Root/No Root Selector Logic
        val btnModeNoroot = findViewById<TextView>(R.id.btn_mode_noroot)
        val btnModeRoot = findViewById<TextView>(R.id.btn_mode_root)
        val indicatorRootView = findViewById<View>(R.id.indicator_root_view)

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

            val useRoot = prefs.getBoolean("use_root", false)
            if (useRoot) {
                indicatorGameView.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_pill_purple)
                if (isMax) {
                    tvGameFF.setTextColor(Color.parseColor("#6E7582"))
                    tvGameMax.setTextColor(Color.parseColor("#F5F6F8"))
                } else {
                    tvGameFF.setTextColor(Color.parseColor("#F5F6F8"))
                    tvGameMax.setTextColor(Color.parseColor("#6E7582"))
                }
            } else {
                indicatorGameView.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_pill_blue)
                if (isMax) {
                    tvGameFF.setTextColor(Color.parseColor("#6E7582"))
                    tvGameMax.setTextColor(Color.parseColor("#0D0E12"))
                } else {
                    tvGameFF.setTextColor(Color.parseColor("#0D0E12"))
                    tvGameMax.setTextColor(Color.parseColor("#6E7582"))
                }
            }
        }

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

            val tvLicensePercent = findViewById<TextView>(R.id.tv_license_percent)

            if (useRoot) {
                btnModeNoroot.setTextColor(Color.parseColor("#6E7582"))
                btnModeRoot.setTextColor(Color.parseColor("#F5F6F8"))
                
                btnFreezy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#B026FF")))
                btnFreezy.setTextColor(Color.parseColor("#F5F6F8"))
                
                // Color dynamically for seekbar, progress and license percent
                seekbarTime.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B026FF"))
                seekbarTime.thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B026FF"))
                findViewById<SeekBar>(R.id.seekbar_bubble_size)?.apply {
                    progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B026FF"))
                    thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B026FF"))
                }
                progressLicenseDays.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B026FF"))
                tvLicensePercent?.setTextColor(Color.parseColor("#B026FF"))

                viewLedStatus?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B026FF"))
                findViewById<com.freezy.ui.CyberBubbleView>(R.id.cyber_bubble_preview)?.setMode(true)
                
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
                            
                            // Fondo: Violeta profundo a púrpura brillante
                            val solidColor = androidx.core.graphics.ColorUtils.blendARGB(
                                Color.parseColor("#3B0764"), 
                                Color.parseColor("#7E22CE"), 
                                fraction
                            )
                            rootDrawable.setColor(solidColor)
                            
                            // Outline: Neon violet a light neon violet
                            val strokeWidth = (1 + fraction * 2).toInt() * resources.displayMetrics.density.toInt()
                            val strokeColor = androidx.core.graphics.ColorUtils.blendARGB(
                                Color.parseColor("#B026FF"), 
                                Color.parseColor("#E9D5FF"), 
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
                
                btnFreezy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")))
                btnFreezy.setTextColor(Color.parseColor("#0D0E12"))
                
                // Color dynamically for seekbar, progress and license percent
                seekbarTime.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                seekbarTime.thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                findViewById<SeekBar>(R.id.seekbar_bubble_size)?.apply {
                    progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                    thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                }
                progressLicenseDays.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                tvLicensePercent?.setTextColor(Color.parseColor("#00E5FF"))

                viewLedStatus?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                findViewById<com.freezy.ui.CyberBubbleView>(R.id.cyber_bubble_preview)?.setMode(false)
                
                rootGlowAnimator?.cancel()
                rootGlowAnimator = null
                
                indicatorRootView.background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.shape_pill_white)
                indicatorRootView.scaleX = 1f
                indicatorRootView.scaleY = 1f
                indicatorRootView.elevation = 0f
            }

            // Actualizar la interfaz de los selectores de modo (auto/custom/manual) inmediatamente para que coincidan los colores
            updateModeUI(currentMode, btnModeAuto, btnModeCustom, btnModeManual, indicatorView, layoutCustomTime, false)
            
            // Actualizar la interfaz del selector de juego para sincronizar color morado / azul
            val isMax = prefs.getBoolean("use_ff_max", false)
            updateGameUI(isMax, false)
        }

        indicatorRootView.post {
            val useRoot = prefs.getBoolean("use_root", false)
            updateRootUI(useRoot, false)
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
        
        val actStrRaw = SecurePrefs.getSecureString(this, "activation_date")
        val expStrRaw = SecurePrefs.getSecureString(this, "expiration_date")
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

        setupConfigSection(prefs)

    }

    // Navegación inferior entre las tres secciones
    private fun setupBottomNavigation() {
        val sections = mapOf(
            findViewById<View>(R.id.btn_nav_inicio) to findViewById<View>(R.id.section_inicio),
            findViewById<View>(R.id.btn_nav_extras) to findViewById<View>(R.id.section_extras),
            findViewById<View>(R.id.btn_nav_config) to findViewById<View>(R.id.section_config)
        )
        val tabs = mapOf(
            findViewById<View>(R.id.btn_nav_inicio) to findViewById<TextView>(R.id.tv_nav_inicio),
            findViewById<View>(R.id.btn_nav_extras) to findViewById<TextView>(R.id.tv_nav_extras),
            findViewById<View>(R.id.btn_nav_config) to findViewById<TextView>(R.id.tv_nav_config)
        )

        fun selectTab(selectedBtn: View) {
            sections.forEach { (btn, section) -> section.visibility = if (btn == selectedBtn) View.VISIBLE else View.GONE }
            tabs.forEach { (btn, label) ->
                val active = btn == selectedBtn
                label.setTextColor(Color.parseColor(if (active) "#00E5FF" else "#6E7582"))
                val underline = (btn as android.widget.LinearLayout).getChildAt(1)
                underline.setBackgroundColor(Color.parseColor(if (active) "#00E5FF" else "#00000000"))
            }
        }

        tabs.keys.forEach { btn ->
            btn.setOnClickListener { selectTab(btn) }
        }
    }

    // Sección Extras: estado de root, HWID y marca del teléfono
    private fun setupExtrasSection() {
        val tvRootStatus = findViewById<TextView>(R.id.tv_root_status)
        val hasRoot = RootTools.hasRootAccess()
        // En Extras basta con indicios (sin disparar el prompt de su)
        val hasRootHints = hasRoot || RootTools.isRootDeviceHintActive(this)
        if (hasRootHints) {
            tvRootStatus.text = NativeBridge.getNativeString(NativeBridge.STRING_ROOT_DETECTED)
            tvRootStatus.setTextColor(Color.parseColor("#00E676"))
            tvRootStatus.setBackgroundColor(Color.parseColor("#1B5E20"))
        } else {
            tvRootStatus.text = NativeBridge.getNativeString(NativeBridge.STRING_ROOT_NOT_DETECTED)
            tvRootStatus.setTextColor(Color.parseColor("#FF5252"))
            tvRootStatus.setBackgroundColor(Color.parseColor("#B71C1C"))
        }

        findViewById<TextView>(R.id.tv_hwid).text = NativeBridge.getHWID(this)
        findViewById<TextView>(R.id.tv_device_brand).text = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        setupBubbleSizeControl()
    }

    // Control de tamaño de la burbuja flotante (40% - 100%)
    private fun setupBubbleSizeControl() {
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val seekbar = findViewById<SeekBar>(R.id.seekbar_bubble_size) ?: return
        val tvValue = findViewById<TextView>(R.id.tv_bubble_size_value)
        val tvLabel = findViewById<TextView>(R.id.tv_label_bubble_size)
        val previewView = findViewById<com.freezy.ui.CyberBubbleView>(R.id.cyber_bubble_preview)
        val useRoot = prefs.getBoolean("use_root", false)
        previewView?.setMode(useRoot)
        tvLabel?.text = "TAMANO DE BURBUJA"

        fun updatePreview(size: Int) {
            if (previewView != null) {
                val density = resources.displayMetrics.density
                val px = (66 * density * size / 100f).toInt()
                previewView.layoutParams.width = px
                previewView.layoutParams.height = px
                previewView.requestLayout()
            }
        }

        val savedSize = prefs.getInt("bubble_size", 100).coerceIn(40, 100)
        seekbar.progress = savedSize
        tvValue?.text = "$savedSize%"
        updatePreview(savedSize)

        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress.coerceIn(40, 100)
                prefs.edit().putInt("bubble_size", size).apply()
                tvValue?.text = "$size%"
                updatePreview(size)
                if (fromUser && isServiceRunning(BubbleService::class.java)) {
                    startService(Intent(this@MainActivity, BubbleService::class.java).apply {
                        action = "APPLY_BUBBLE_SIZE"
                    })
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isServiceRunning(BubbleService::class.java)) {
                    startService(Intent(this@MainActivity, BubbleService::class.java).apply {
                        action = "APPLY_BUBBLE_SIZE"
                    })
                }
            }
        })
    }

    // Sección Configuración: versión, paquete, cerrar sesión y logs
    private fun setupConfigSection(prefs: android.content.SharedPreferences) {
        val useRoot = prefs.getBoolean("use_root", false)
        val accentColor = if (useRoot) Color.parseColor("#B026FF") else Color.parseColor("#00E5FF")

        val tvVersion = findViewById<TextView>(R.id.tv_version)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "v${pInfo.versionName}"
        } catch (e: Exception) {
            tvVersion.text = "v1.0"
        }

        findViewById<TextView>(R.id.tv_package_name)?.text = packageName

        findViewById<Button>(R.id.btn_view_logs)?.apply {
            text = NativeBridge.getNativeString(NativeBridge.STRING_LOGS_VER_LOGS)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
            setTextColor(if (useRoot) Color.parseColor("#F5F6F8") else Color.parseColor("#0C0D10"))
            setOnClickListener { showLogsDialog() }
        }

        findViewById<Button>(R.id.btn_logout)?.apply {
            text = NativeBridge.getNativeString(NativeBridge.STRING_LOGOUT)
            setOnClickListener {
                prefs.edit()
                    .putBoolean("is_logged_in", false)
                    .remove("saved_key")
                    .remove("saved_username")
                    .remove("secure_endpoint")
                    .apply()
                val intent = Intent(this@MainActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
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

    override fun onDestroy() {
        antiDebugHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
        val endpointUrl = SecurePrefs.getSecureString(this, "secure_endpoint").ifEmpty {
            NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT)
        }
        val key = SecurePrefs.getSecureString(this, "saved_key")
        val username = SecurePrefs.getSecureString(this, "saved_username")
        val hwid = NativeBridge.getHWID(this)
        val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        if (key.isEmpty() || username.isEmpty()) {
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

                val challengeConn = WebSecurity.open(challengeEndpoint)
                challengeConn.requestMethod = "POST"
                challengeConn.setRequestProperty("Content-Type", "application/json")
                challengeConn.connectTimeout = 30000
                challengeConn.readTimeout = 30000
                challengeConn.doOutput = true

                val currentAppVersion = com.system.network.ui.BuildConfig.VERSION_NAME
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

                val verifyConn = WebSecurity.open(verifyEndpoint)
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
                            val isExp = message.contains("expir", true) || message.contains("expired", true)
                            if (isExp) {
                                android.widget.Toast.makeText(this@MainActivity, NativeBridge.getNativeString(NativeBridge.STRING_LICENSE_EXPIRED), android.widget.Toast.LENGTH_LONG).show()
                                Logger.log(this@MainActivity, "Licencia expirada al iniciar. Cerrando sesión.")
                                
                                prefs.edit()
                                    .putBoolean("is_logged_in", false)
                                    .remove("expiration_date")
                                    .remove("activation_date")
                                    .apply()
                                
                                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                                finish()
                            } else {
                                android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
                                Logger.log(this@MainActivity, "Fallo de licencia al iniciar: $message")
                            }
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
                        val isExp = serverMessage.contains("expir", true) || serverMessage.contains("expired", true)
                        if (isExp) {
                            android.widget.Toast.makeText(this@MainActivity, NativeBridge.getNativeString(NativeBridge.STRING_LICENSE_EXPIRED), android.widget.Toast.LENGTH_LONG).show()
                            Logger.log(this@MainActivity, "Licencia expirada al iniciar (Error). Cerrando sesión.")
                            
                            prefs.edit()
                                .putBoolean("is_logged_in", false)
                                .remove("expiration_date")
                                .remove("activation_date")
                                .apply()
                            
                            startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                            finish()
                        } else {
                            android.widget.Toast.makeText(this@MainActivity, serverMessage, android.widget.Toast.LENGTH_LONG).show()
                            Logger.log(this@MainActivity, "Fallo al iniciar: $serverMessage")
                        }
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
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val useMax = prefs.getBoolean("use_ff_max", false)
        val preferredPkg = if (useMax) "com.dts.freefiremax" else "com.dts.freefireth"
        val detectedPkg = detectFreeFire()

        targetPackageToLaunch = detectedPkg ?: preferredPkg
        val vpnIntent = android.net.VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, 124)
        } else {
            launchGameAndBubble()
        }
    }



    // Verificación periódica anti-debug: en release cierra la app, en debug solo registra
    private val antiDebugHandler = Handler(Looper.getMainLooper())
    private val antiDebugRunnable = object : Runnable {
        override fun run() {
            val debuggerAttached = android.os.Debug.isDebuggerConnected() ||
                    (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggerAttached) {
                if (com.system.network.ui.BuildConfig.DEBUG) {
                    Logger.log(this@MainActivity, "Alerta: Debugger conectado (build debug, se permite).")
                } else {
                    Logger.log(this@MainActivity, "Alerta: Debugger conectado. Cerrando.")
                    Toast.makeText(this@MainActivity, NativeBridge.getNativeString(NativeBridge.STRING_DEBUGGER_DETECTED), Toast.LENGTH_SHORT).show()
                    antiDebugHandler.postDelayed({ finishAffinity() }, 800)
                    return
                }
            }
            antiDebugHandler.postDelayed(this, 3000)
        }
    }

    private fun startAntiDebugChecks() {
        antiDebugHandler.removeCallbacks(antiDebugRunnable)
        antiDebugHandler.post(antiDebugRunnable)
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
            findViewById<TextView>(R.id.tv_time_label)?.setTextColor(Color.parseColor("#B026FF"))
            
            btnA.setTextColor(if (mode == 0) Color.parseColor("#F5F6F8") else Color.parseColor("#6E7582"))
            btnC.setTextColor(if (mode == 1) Color.parseColor("#F5F6F8") else Color.parseColor("#6E7582"))
            btnM.setTextColor(if (mode == 2) Color.parseColor("#F5F6F8") else Color.parseColor("#6E7582"))
        } else {
            indicator.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_pill_white)
            findViewById<TextView>(R.id.tv_time_label)?.setTextColor(Color.parseColor("#00E5FF"))
            
            btnA.setTextColor(if (mode == 0) Color.parseColor("#0D0E12") else Color.parseColor("#6E7582"))
            btnC.setTextColor(if (mode == 1) Color.parseColor("#0D0E12") else Color.parseColor("#6E7582"))
            btnM.setTextColor(if (mode == 2) Color.parseColor("#0D0E12") else Color.parseColor("#6E7582"))
        }

        layoutCustomTime.visibility = if (mode == 1) View.VISIBLE else View.GONE
    }

    private fun launchGameAndBubble() {
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val useMax = prefs.getBoolean("use_ff_max", false)
        val pkg = targetPackageToLaunch ?: (if (useMax) "com.dts.freefiremax" else "com.dts.freefireth")
        
        // 1. Lanzamos el juego si está disponible
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

    private fun hasRootAccess(): Boolean = RootTools.hasRootAccess()

    /**
     * Extrae la URL base del servidor (ej: "https://mi-servidor.com")
     * reutilizando el endpoint ofuscado en C++.
     */
    private fun getServerBaseUrl(): String {
        return try {
            val fullUrl = NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT)
            val url = java.net.URL(fullUrl)
            val port = if (url.port != -1) ":${url.port}" else ""
            "${url.protocol}://${url.host}$port"
        } catch (e: Exception) {
            "https://freezy-backend-v1ax.onrender.com"
        }
    }



    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 124 && resultCode == RESULT_OK) {
            launchGameAndBubble()
        }
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
        val useRoot = prefs.getBoolean("use_root", false)
        tvLicensePercent?.setTextColor(if (useRoot) Color.parseColor("#B026FF") else Color.parseColor("#00E5FF"))

        val actStr = SecurePrefs.getSecureString(this, "activation_date")
        val expStr = SecurePrefs.getSecureString(this, "expiration_date")

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
        val actDate = SecurePrefs.getSecureString(this, "activation_date")
        val expDate = SecurePrefs.getSecureString(this, "expiration_date")
        if (actDate.isNotEmpty() && expDate.isNotEmpty() && actDate != "--" && expDate != "--") {
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

