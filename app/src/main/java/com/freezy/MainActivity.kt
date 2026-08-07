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
import android.widget.LinearLayout
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
    private var permissionDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)
        

        // Migración puntual: credenciales y URLs pasan de texto plano a AES-GCM
        SecurePrefs.migrateLegacy(this)
        startAntiDebugChecks()
        // Rechazar APKs re-firmadas (crack por apktool/MT Manager)
        SignatureGuard.verify(this)
        
        // Modal de verificación persistente de permisos al entrar al inicio
        checkPermissionsModal()

        setupBottomNavigation()
        setupExtrasSection()

        val btnFreezy = findViewById<Button>(R.id.btn_freezy)
        
        
        val btnModeCustom = findViewById<TextView>(R.id.btn_mode_custom)
        val btnModeManual = findViewById<TextView>(R.id.btn_mode_manual)
        val indicatorView = findViewById<View>(R.id.indicator_view)
        
        val layoutCustomTime = findViewById<View>(R.id.layout_custom_time)
        val tvTimeLabel = findViewById<TextView>(R.id.tv_time_label)
        val seekbarTime = findViewById<SeekBar>(R.id.seekbar_time)
        val tvDamageWarning = findViewById<TextView>(R.id.tv_damage_warning)
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)

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

        // Ajustar el ancho del indicador dinámicamente (2 modos: Personalizado y Manual)
        indicatorView.post {
            val parent = btnModeCustom.parent as View
            val padding = parent.paddingLeft + parent.paddingRight
            val width = (parent.width - padding) / 2
            indicatorView.layoutParams.width = width
            indicatorView.requestLayout()
            
            // Cargar estado inicial (migrando modo 0 a modo 1)
            val rawMode = prefs.getInt("mode", 1)
            val currentMode = if (rawMode == 0) 1 else rawMode
            if (rawMode == 0) prefs.edit().putInt("mode", 1).apply()
            updateModeUI(currentMode, btnModeCustom, btnModeManual, indicatorView, layoutCustomTime, false)
        }

        // Siempre forzar feature 0 (Fake Lag)
        prefs.edit().putInt("selected_feature", 0).apply()

        val customTimeFloat = prefs.getFloat("custom_time_float", 3.0f).coerceAtLeast(0.5f).coerceAtMost(3.0f)
        seekbarTime.max = 30 // Máximo 3.0 segundos (30 / 10)
        seekbarTime.progress = (customTimeFloat * 10).toInt()
        tvTimeLabel.text = String.format("%.1f Segundos", customTimeFloat)
        tvDamageWarning?.visibility = if (customTimeFloat >= 2.0f) View.VISIBLE else View.GONE

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

            val accentHex = if (useRoot) "#B026FF" else "#00E5FF"
            val accentColorStateList = android.content.res.ColorStateList.valueOf(Color.parseColor(accentHex))

            if (useRoot) {
                btnModeNoroot.setTextColor(Color.parseColor("#6E7582"))
                btnModeRoot.setTextColor(Color.parseColor("#F5F6F8"))
                btnFreezy.setBackgroundTintList(accentColorStateList)
                btnFreezy.setTextColor(Color.parseColor("#F5F6F8"))
                
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
                btnFreezy.setBackgroundTintList(accentColorStateList)
                btnFreezy.setTextColor(Color.parseColor("#0D0E12"))
                
                rootGlowAnimator?.cancel()
                rootGlowAnimator = null
                indicatorRootView.background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.shape_pill_white)
                indicatorRootView.scaleX = 1f
                indicatorRootView.scaleY = 1f
                indicatorRootView.elevation = 0f
            }

            // Sincronizar todos los componentes con el color del tema (Morado para Root, Azul para No-Root)
            seekbarTime.progressTintList = accentColorStateList
            seekbarTime.thumbTintList = accentColorStateList
            findViewById<SeekBar>(R.id.seekbar_bubble_size)?.apply {
                progressTintList = accentColorStateList
                thumbTintList = accentColorStateList
            }
            progressLicenseDays.progressTintList = accentColorStateList
            tvLicensePercent?.setTextColor(Color.parseColor(accentHex))

            viewLedStatus?.backgroundTintList = accentColorStateList
            findViewById<View>(R.id.view_led_extras)?.backgroundTintList = accentColorStateList

            findViewById<TextView>(R.id.tv_hwid)?.setTextColor(Color.parseColor(accentHex))
            findViewById<TextView>(R.id.btn_refresh_root)?.backgroundTintList = accentColorStateList
            findViewById<Button>(R.id.btn_view_logs)?.apply {
                backgroundTintList = accentColorStateList
                setTextColor(if (useRoot) Color.parseColor("#F5F6F8") else Color.parseColor("#0C0D10"))
            }

            findViewById<com.freezy.ui.CyberBubbleView>(R.id.cyber_bubble_preview)?.setMode(useRoot)

            updateBottomNavigationColors()
            setupToneSection()
            updateCardGlowAnimation(useRoot)

            // Actualizar la interfaz de los selectores de modo inmediatamente
            updateModeUI(currentMode, btnModeCustom, btnModeManual, indicatorView, layoutCustomTime, false)
            
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
            val tvRootError = findViewById<TextView>(R.id.tv_root_error)
            tvRootError?.visibility = View.GONE
            btnModeRoot.isEnabled = false

            Thread {
                val ok = hasRootAccess() // dispara el prompt de su y espera hasta 6s
                runOnUiThread {
                    btnModeRoot.isEnabled = true
                    if (ok) {
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

                        // Si hay un gestor instalado pero su no concede, lo más común es
                        // deny list (ocultar root a la app) o permiso denegado.
                        val msg = when {
                            RootTools.hasKitsune(this) -> {
                                NativeBridge.getNativeString(NativeBridge.S109)
                            }
                            RootTools.hasRootManager(this) -> {
                                NativeBridge.getNativeString(NativeBridge.S110)
                            }
                            else -> {
                                NativeBridge.getNativeString(NativeBridge.STRING_ROOT_REQ)
                            }
                        }
                        tvRootError?.text = msg
                        tvRootError?.visibility = View.VISIBLE

                        Handler(Looper.getMainLooper()).postDelayed({
                            tvRootError.visibility = View.GONE
                        }, 7000)
                    }
                }
            }.start()
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



        btnModeCustom.setOnClickListener {
            prefs.edit().putInt("mode", 1).apply()
            updateModeUI(1, btnModeCustom, btnModeManual, indicatorView, layoutCustomTime, true)
        }
        btnModeManual.setOnClickListener {
            prefs.edit().putInt("mode", 2).apply()
            updateModeUI(2, btnModeCustom, btnModeManual, indicatorView, layoutCustomTime, true)
        }

        seekbarTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress < 5) {
                    seekBar?.progress = 5
                    return
                }
                val timeFloat = progress / 10f
                tvTimeLabel.text = String.format("%.1f Segundos", timeFloat)
                tvDamageWarning?.visibility = if (timeFloat >= 2.0f) View.VISIBLE else View.GONE
                prefs.edit().putFloat("custom_time_float", timeFloat).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    val progress = it.progress
                    val realProgress = if (progress < 5) 5 else progress
                    val timeFloat = realProgress / 10f
                    tvDamageWarning?.visibility = if (timeFloat >= 2.0f) View.VISIBLE else View.GONE
                    prefs.edit().putFloat("custom_time_float", timeFloat).commit()
                }
            }
        })



        btnFreezy.setOnClickListener {
            val hasOverlay = Settings.canDrawOverlays(this)
            val hasUsage = hasUsageStatsPermission()
            val hasBattery = checkBatteryOptimizationPermission()
            if (!hasOverlay || !hasUsage || !hasBattery) {
                checkPermissionsModal()
                return@setOnClickListener
            }



            val useRoot = prefs.getBoolean("use_root", false)
            if (useRoot) {
                btnFreezy.isEnabled = false
                Thread {
                    val ok = hasRootAccess() // puede disparar el prompt; no bloquear el hilo UI
                    runOnUiThread {
                        btnFreezy.isEnabled = true
                        btnFreezy.alpha = 1.0f
                        if (ok) {
                            checkLicenseAndLaunch()
                        } else {
                            Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_ROOT_DENIED_TOAST), Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
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

        // Revalidación automática de la licencia contra el servidor al arrancar
        autoRevalidateOnLaunch()
    }

    private var activeNavBtn: View? = null
    private var cardGlowAnimator: android.animation.ValueAnimator? = null

    private fun setupBottomNavigation() {
        val btnInicio = findViewById<View>(R.id.btn_nav_inicio)
        val btnExtras = findViewById<View>(R.id.btn_nav_extras)
        val btnConfig = findViewById<View>(R.id.btn_nav_config)

        val navMap = mapOf(
            btnInicio to findViewById<View>(R.id.section_inicio),
            btnExtras to findViewById<View>(R.id.section_extras),
            btnConfig to findViewById<View>(R.id.section_config)
        )

        fun selectTab(selectedBtn: View) {
            activeNavBtn = selectedBtn
            navMap.forEach { (btn, section) -> section.visibility = if (btn == selectedBtn) View.VISIBLE else View.GONE }
            updateBottomNavigationColors()
        }

        navMap.keys.forEach { btn ->
            btn?.setOnClickListener { selectTab(btn) }
        }
        if (btnInicio != null) selectTab(btnInicio)
    }

    private fun updateBottomNavigationColors() {
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)
        val accentHex = if (useRoot) "#B026FF" else "#00E5FF"

        val tabs = mapOf(
            findViewById<View>(R.id.btn_nav_inicio) to findViewById<TextView>(R.id.tv_nav_inicio),
            findViewById<View>(R.id.btn_nav_extras) to findViewById<TextView>(R.id.tv_nav_extras),
            findViewById<View>(R.id.btn_nav_config) to findViewById<TextView>(R.id.tv_nav_config)
        )

        val currentBtn = activeNavBtn ?: findViewById(R.id.btn_nav_inicio)

        tabs.forEach { (btn, label) ->
            val active = btn == currentBtn
            label?.setTextColor(Color.parseColor(if (active) accentHex else "#6E7582"))
            val underline = (btn as? LinearLayout)?.getChildAt(1)
            underline?.setBackgroundColor(Color.parseColor(if (active) accentHex else "#00000000"))
        }
    }

    private fun updateCardGlowAnimation(useRoot: Boolean) {
        cardGlowAnimator?.cancel()

        val cardIds = intArrayOf(
            R.id.card_header,
            R.id.card_game_selector,
            R.id.card_bubble_size,
            R.id.card_tones,
            R.id.card_device_status,
            R.id.card_license,
            R.id.card_app_config,
            R.id.card_about
        )

        val cards = mutableListOf<View>()
        for (id in cardIds) {
            val v = findViewById<View>(id)
            if (v != null) cards.add(v)
        }
        if (cards.isEmpty()) return

        val drawables = mutableListOf<GradientDrawable>()
        for (v in cards) {
            val d = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#0F1117"))
            }
            v.background = d
            drawables.add(d)
        }

        val startColor = if (useRoot) Color.parseColor("#3B0764") else Color.parseColor("#003543")
        val endColor = if (useRoot) Color.parseColor("#E9D5FF") else Color.parseColor("#00E5FF")

        cardGlowAnimator = android.animation.ValueAnimator.ofFloat(0.2f, 1.0f).apply {
            duration = 1400
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val strokeColor = androidx.core.graphics.ColorUtils.blendARGB(startColor, endColor, fraction)
                val strokeWidth = (1 + fraction * 1.5f).toInt()

                for (d in drawables) {
                    d.setStroke(strokeWidth, strokeColor)
                }
            }
            start()
        }
    }

    // Sección Extras: estado de root real verificado en vivo con botón recargar, HWID y marca del teléfono
    private fun setupExtrasSection() {
        val tvRootStatus = findViewById<TextView>(R.id.tv_root_status)
        val btnRefreshRoot = findViewById<TextView>(R.id.btn_refresh_root)

        fun verifyRootAccess() {
            tvRootStatus?.text = "VERIFICANDO..."
            tvRootStatus?.setTextColor(Color.parseColor("#FFD54F"))
            tvRootStatus?.setBackgroundColor(Color.parseColor("#3E2723"))
            btnRefreshRoot?.isEnabled = false

            Thread {
                val ok = RootTools.hasRootAccess()
                runOnUiThread {
                    btnRefreshRoot?.isEnabled = true
                    if (ok) {
                        tvRootStatus?.text = NativeBridge.getNativeString(NativeBridge.STRING_ROOT_DETECTED)
                        tvRootStatus?.setTextColor(Color.parseColor("#00E676"))
                        tvRootStatus?.setBackgroundColor(Color.parseColor("#1B5E20"))
                    } else {
                        tvRootStatus?.text = NativeBridge.getNativeString(NativeBridge.STRING_ROOT_NOT_DETECTED)
                        tvRootStatus?.setTextColor(Color.parseColor("#FF5252"))
                        tvRootStatus?.setBackgroundColor(Color.parseColor("#B71C1C"))
                    }
                }
            }.start()
        }

        btnRefreshRoot?.setOnClickListener { verifyRootAccess() }
        verifyRootAccess()

        findViewById<TextView>(R.id.tv_hwid).text = NativeBridge.getHWID(this)
        findViewById<TextView>(R.id.tv_device_brand).text = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        setupBubbleSizeControl()
        setupToneSection()
    }

    // Selector de tonos de activación/desactivación con preview audible
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setupToneSection() {
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        val container = findViewById<LinearLayout>(R.id.container_tones) ?: return
        var selected = prefs.getInt("tone_type", 0)
        val useRoot = prefs.getBoolean("use_root", false)

        val activeAccent = if (useRoot) Color.parseColor("#B026FF") else Color.parseColor("#00E5FF")
        val activeBg = if (useRoot) Color.parseColor("#3B0764") else Color.parseColor("#0F2B33")

        fun render() {
            container.removeAllViews()
            ToneManager.tones.forEach { tone ->
                val chip = TextView(this)
                chip.text = tone.name
                chip.textSize = 13f
                chip.setTypeface(chip.typeface, android.graphics.Typeface.BOLD)
                val bg = GradientDrawable()
                bg.cornerRadius = dp(20).toFloat()
                val isSel = (tone.id == selected)
                bg.setStroke(dp(1), if (isSel) activeAccent else Color.parseColor("#2A2E3A"))
                bg.setColor(if (isSel) activeBg else Color.parseColor("#1A1C24"))
                chip.background = bg
                chip.setTextColor(if (isSel) activeAccent else Color.parseColor("#E2E8F0"))
                chip.setPadding(dp(16), dp(8), dp(16), dp(8))
                chip.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                chip.setOnClickListener {
                    selected = tone.id
                    prefs.edit().putInt("tone_type", tone.id).apply()
                    render()
                    ToneManager.play(this, tone.id)
                }
                container.addView(chip)
            }
        }
        render()
    }

    // Control de tamaño de la burbuja flotante (0% = 50px, 100% = 150px)
    private fun setupBubbleSizeControl() {
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
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
                // 0% = 50dp, 100% = 150dp (lineal)
                val px = ((50 + size) * density).toInt()
                previewView.layoutParams.width = px
                previewView.layoutParams.height = px
                previewView.requestLayout()
            }
        }

        val savedSize = prefs.getInt("bubble_size", 20).coerceIn(0, 100)
        seekbar.progress = savedSize
        tvValue?.text = "$savedSize%"
        updatePreview(savedSize)

        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress.coerceIn(0, 100)
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
                // Cierre de sesión manual desde configuración:
                // Se MANTIENEN (aguardan) saved_username y saved_key para comodidad del usuario.
                prefs.edit()
                    .putBoolean("is_logged_in", false)
                    .remove("activation_date")
                    .remove("expiration_date")
                    .remove("secure_endpoint")
                    .apply()
                stopService(Intent(this@MainActivity, BubbleService::class.java))
                val intent = Intent(this@MainActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsModal()
        // Verificación real para mostrar/ocultar el botón de cierre
        val isRunning = isServiceRunning(BubbleService::class.java)
        findViewById<Button>(R.id.btn_close_bubble).visibility = if (isRunning) View.VISIBLE else View.GONE
        
        startLicenseCountdown()
        // Recargar el valor de tiempo guardado para asegurar consistencia al volver a entrar
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        val customTimeFloat = prefs.getFloat("custom_time_float", 3.0f).coerceAtLeast(0.5f).coerceAtMost(3.0f)
        val seekbarTime = findViewById<SeekBar>(R.id.seekbar_time)
        val tvTimeLabel = findViewById<TextView>(R.id.tv_time_label)
        val tvDamageWarning = findViewById<TextView>(R.id.tv_damage_warning)
        seekbarTime?.progress = (customTimeFloat * 10).toInt()
        tvTimeLabel?.text = String.format("%.1f Segundos", customTimeFloat)
        tvDamageWarning?.visibility = if (customTimeFloat >= 2.0f) View.VISIBLE else View.GONE
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

    private sealed class ValidationOutcome {
        data class Valid(val json: org.json.JSONObject, val hmacHex: String) : ValidationOutcome()
        data class Rejected(val message: String) : ValidationOutcome()
        data class NetworkError(val message: String) : ValidationOutcome()
    }

    private fun checkLicenseAndLaunch() {
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), android.content.Context.MODE_PRIVATE)
        val key = SecurePrefs.getSecureString(this, "saved_key")
        val username = SecurePrefs.getSecureString(this, "saved_username")

        if (key.isEmpty() || username.isEmpty()) {
            android.widget.Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_INCOMPLETE_DATA), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        android.widget.Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_VALIDATING), android.widget.Toast.LENGTH_SHORT).show()

        val btnFreezy = findViewById<Button>(R.id.btn_freezy)
        btnFreezy.isEnabled = false
        btnFreezy.alpha = 0.5f

        Thread {
            when (val outcome = validateWithServer()) {
                is ValidationOutcome.Valid -> {
                    applyServerSession(outcome.json, outcome.hmacHex)
                    runOnUiThread {
                        btnFreezy.isEnabled = true
                        btnFreezy.alpha = 1.0f
                        Logger.log(this@MainActivity, "Licencia Validada al iniciar")

                        val warning = outcome.json.optString("update_warning", "")
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
                    }
                }
                is ValidationOutcome.Rejected -> {
                    runOnUiThread {
                        btnFreezy.isEnabled = true
                        btnFreezy.alpha = 1.0f
                        handleFatalValidation(this@MainActivity, outcome.message)
                    }
                }
                is ValidationOutcome.NetworkError -> {
                    runOnUiThread {
                        btnFreezy.isEnabled = true
                        btnFreezy.alpha = 1.0f
                        android.widget.Toast.makeText(this@MainActivity, NativeBridge.getNativeString(NativeBridge.STRING_PLEASE_WAIT), android.widget.Toast.LENGTH_LONG).show()
                        Logger.log(this@MainActivity, "Error de conexión al iniciar: ${outcome.message}")
                    }
                }
            }
        }.start()
    }

    /**
     * Validación real contra el servidor: challenge (nonce) → HMAC-SHA256(nonce, secret) → verify.
     * Devuelve el resultado sin tocar la UI; la lógica de UI vive en quien la invoca.
     */
    private fun validateWithServer(): ValidationOutcome {
        val endpointUrl = SecurePrefs.getSecureString(this, "secure_endpoint").ifEmpty {
            NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT)
        }
        val key = SecurePrefs.getSecureString(this, "saved_key")
        val username = SecurePrefs.getSecureString(this, "saved_username")
        val hwid = NativeBridge.getHWID(this)
        val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        if (key.isEmpty() || username.isEmpty()) {
            return ValidationOutcome.Rejected(NativeBridge.getNativeString(NativeBridge.STRING_INCOMPLETE_DATA))
        }

        return try {
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
                return ValidationOutcome.NetworkError(NativeBridge.getNativeString(NativeBridge.STRING_VALIDATION_ERROR_INIT))
            }

            val nonce = org.json.JSONObject(challengeConn.inputStream.bufferedReader().readText()).getString("nonce")

            val algorithm = "HmacSHA256"
            val mac = javax.crypto.Mac.getInstance(algorithm)
            mac.init(javax.crypto.spec.SecretKeySpec(NativeBridge.getHmacSecret().toByteArray(Charsets.UTF_8), algorithm))
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
                if (jsonResponse.optBoolean("valid", false)) {
                    ValidationOutcome.Valid(jsonResponse, hmacHex)
                } else {
                    ValidationOutcome.Rejected(jsonResponse.optString("message", "Licencia inválida"))
                }
            } else {
                val errorBody = verifyConn.errorStream?.bufferedReader()?.readText() ?: ""
                val serverMessage = try {
                    org.json.JSONObject(errorBody).optString("message", "Error: $responseCode")
                } catch (e: Exception) {
                    "Error: $responseCode"
                }
                ValidationOutcome.Rejected(serverMessage)
            }
        } catch (e: Exception) {
            ValidationOutcome.NetworkError(e.message ?: "Network error")
        }
    }

    /**
     * Persiste lo que el servidor confirma tras una validación válida:
     * session_token cifrado, fechas, y el payload cifrado (AES-GCM con clave = HMAC)
     * descifrado y guardado en memoria nativa (el motor de recoil lo exige).
     */
    private fun applyServerSession(json: org.json.JSONObject, hmacHex: String) {
        val sessionToken = json.optString("session_token", "")
        if (sessionToken.isNotEmpty()) {
            SecurePrefs.putSecureString(this, "session_token", sessionToken)
        }

        val createdAt = json.optString("created_at", "")
        val expiresAt = json.optString("expires_at", "")
        if (createdAt.isNotEmpty() && expiresAt.isNotEmpty()) {
            SecurePrefs.putSecureString(this, "activation_date", createdAt)
            SecurePrefs.putSecureString(this, "expiration_date", expiresAt)
        }

        val encryptedPayloadHex = json.optString("encrypted_payload", "")
        val ivHex = json.optString("iv", "")
        if (encryptedPayloadHex.isNotEmpty() && ivHex.isNotEmpty()) {
            try {
                val aesKeyBytes = SecureCrypto.hexToBytes(hmacHex)
                val ivBytes = SecureCrypto.hexToBytes(ivHex)
                val encryptedBytes = SecureCrypto.hexToBytes(encryptedPayloadHex)
                val decrypted = SecureCrypto.decryptGcm(aesKeyBytes, ivBytes, encryptedBytes)
                NativeBridge.setSecurePayload(decrypted)
            } catch (e: javax.crypto.AEADBadTagException) {
                if (com.system.network.ui.BuildConfig.DEBUG) android.util.Log.e("MainActivity", "GCM auth tag mismatch — payload tampered")
            } catch (e: Exception) {
                if (com.system.network.ui.BuildConfig.DEBUG) android.util.Log.e("MainActivity", "Payload error: ${e.message}")
            }
        }
    }

    /**
     * Revalidación automática al arrancar (sin interacción del usuario).
     * Cierra sesión si el servidor rechaza la licencia (ban/expiración).
     * Sin conexión: grace period, se mantiene la sesión local.
     */
    private fun autoRevalidateOnLaunch() {
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("is_logged_in", false)) return
        if (SecurePrefs.getSecureString(this, "saved_key").isEmpty() ||
            SecurePrefs.getSecureString(this, "saved_username").isEmpty()) return

        Thread {
            when (val outcome = validateWithServer()) {
                is ValidationOutcome.Valid -> {
                    applyServerSession(outcome.json, outcome.hmacHex)
                    runOnUiThread {
                        Logger.log(this@MainActivity, "Revalidación automática en arranque: OK")
                        startLicenseCountdown()
                    }
                }
                is ValidationOutcome.Rejected -> {
                    runOnUiThread {
                        Logger.log(this@MainActivity, "Revalidación automática rechazada: ${outcome.message}")
                        handleFatalValidation(this@MainActivity, outcome.message)
                    }
                }
                is ValidationOutcome.NetworkError -> {
                    runOnUiThread {
                        Logger.log(this@MainActivity, "Revalidación automática sin red (grace period): ${outcome.message}")
                    }
                }
            }
        }.start()
    }

    // Maneja el resultado de validación: ban/expiración cierran la sesión en automático mostrando la razón.
    private fun handleFatalValidation(activity: MainActivity, message: String) {
        if (SessionGuard.isBan(message)) {
            Logger.log(activity, "Baneo detectado al iniciar: $message")
            SessionGuard.forceLogout(activity, "CUENTA BANEADA", message)
        } else if (SessionGuard.isExpired(message)) {
            Logger.log(activity, "Licencia expirada al iniciar. Cerrando sesión.")
            SessionGuard.forceLogout(activity, "LICENCIA EXPIRADA", message)
        } else {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
            Logger.log(activity, "Fallo de licencia al iniciar: $message")
        }
    }

    private fun proceedWithLaunch() {
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        val useMax = prefs.getBoolean("use_ff_max", false)
        val preferredPkg = if (useMax) NativeBridge.getNativeString(NativeBridge.S98) else NativeBridge.getNativeString(NativeBridge.S99)
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

    private fun updateModeUI(mode: Int, btnC: TextView, btnM: TextView, indicator: View, layoutCustomTime: View, animate: Boolean) {
        val effectiveMode = if (mode == 0) 1 else mode
        val parent = btnC.parent as View
        val padding = parent.paddingLeft + parent.paddingRight
        val segmentWidth = if (parent.width > 0) (parent.width - padding) / 2f else indicator.layoutParams.width.toFloat()
        val index = if (effectiveMode == 2) 1 else 0
        val targetX = index * segmentWidth
        if (animate) {
            ObjectAnimator.ofFloat(indicator, "translationX", targetX).apply {
                duration = 300
                start()
            }
        } else {
            indicator.translationX = targetX
        }

        val useRoot = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE).getBoolean("use_root", false)
        if (useRoot) {
            indicator.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_pill_purple)
            findViewById<TextView>(R.id.tv_time_label)?.setTextColor(Color.parseColor("#B026FF"))
            
            btnC.setTextColor(if (effectiveMode == 1) Color.parseColor("#F5F6F8") else Color.parseColor("#6E7582"))
            btnM.setTextColor(if (effectiveMode == 2) Color.parseColor("#F5F6F8") else Color.parseColor("#6E7582"))
        } else {
            indicator.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_pill_white)
            findViewById<TextView>(R.id.tv_time_label)?.setTextColor(Color.parseColor("#00E5FF"))
            
            btnC.setTextColor(if (effectiveMode == 1) Color.parseColor("#0D0E12") else Color.parseColor("#6E7582"))
            btnM.setTextColor(if (effectiveMode == 2) Color.parseColor("#0D0E12") else Color.parseColor("#6E7582"))
        }

        layoutCustomTime.visibility = if (effectiveMode == 1) View.VISIBLE else View.GONE
    }

    private fun launchGameAndBubble() {
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        val useMax = prefs.getBoolean("use_ff_max", false)
        val pkg = targetPackageToLaunch ?: (if (useMax) NativeBridge.getNativeString(NativeBridge.S98) else NativeBridge.getNativeString(NativeBridge.S99))
        
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
            putExtra(NativeBridge.getNativeString(NativeBridge.S92), pkg)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun detectFreeFire(): String? {
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        val useMax = prefs.getBoolean("use_ff_max", false)
        
        val primaryPkg = if (useMax) NativeBridge.getNativeString(NativeBridge.S98) else NativeBridge.getNativeString(NativeBridge.S99)
        val secondaryPkg = if (useMax) NativeBridge.getNativeString(NativeBridge.S99) else NativeBridge.getNativeString(NativeBridge.S98)
        
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

    private fun checkBatteryOptimizationPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    private fun checkPermissionsModal() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasUsage = hasUsageStatsPermission()
        val hasBattery = checkBatteryOptimizationPermission()

        val allGranted = hasOverlay && hasUsage && hasBattery

        if (allGranted) {
            permissionDialog?.dismiss()
            permissionDialog = null
            return
        }

        if (permissionDialog == null || !(permissionDialog?.isShowing == true)) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_permissions, null)
            val builder = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)

            permissionDialog = builder.create().apply {
                setCanceledOnTouchOutside(false)
                show()
            }
            permissionDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }

        permissionDialog?.let { dialog ->
            val tvOverlay = dialog.findViewById<TextView>(R.id.tv_status_overlay)
            val btnOverlay = dialog.findViewById<Button>(R.id.btn_grant_overlay)
            if (hasOverlay) {
                tvOverlay?.text = "✓ CONCEDIDO"
                tvOverlay?.setTextColor(Color.parseColor("#00E5FF"))
                btnOverlay?.isEnabled = false
                btnOverlay?.text = "CONCEDIDO"
                btnOverlay?.alpha = 0.5f
            } else {
                tvOverlay?.text = "❌ REQUERIDO"
                tvOverlay?.setTextColor(Color.parseColor("#FF3B30"))
                btnOverlay?.isEnabled = true
                btnOverlay?.text = "ACTIVAR PERMISO"
                btnOverlay?.alpha = 1.0f
                btnOverlay?.setOnClickListener {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        startActivityForResult(intent, 123)
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            val tvUsage = dialog.findViewById<TextView>(R.id.tv_status_usage)
            val btnUsage = dialog.findViewById<Button>(R.id.btn_grant_usage)
            if (hasUsage) {
                tvUsage?.text = "✓ CONCEDIDO"
                tvUsage?.setTextColor(Color.parseColor("#00E5FF"))
                btnUsage?.isEnabled = false
                btnUsage?.text = "CONCEDIDO"
                btnUsage?.alpha = 0.5f
            } else {
                tvUsage?.text = "❌ REQUERIDO"
                tvUsage?.setTextColor(Color.parseColor("#FF3B30"))
                btnUsage?.isEnabled = true
                btnUsage?.text = "ACTIVAR PERMISO"
                btnUsage?.alpha = 1.0f
                btnUsage?.setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            val tvBattery = dialog.findViewById<TextView>(R.id.tv_status_battery)
            val btnBattery = dialog.findViewById<Button>(R.id.btn_grant_battery)
            if (hasBattery) {
                tvBattery?.text = "✓ CONCEDIDO"
                tvBattery?.setTextColor(Color.parseColor("#00E5FF"))
                btnBattery?.isEnabled = false
                btnBattery?.text = "CONCEDIDO"
                btnBattery?.alpha = 0.5f
            } else {
                tvBattery?.text = "❌ REQUERIDO"
                tvBattery?.setTextColor(Color.parseColor("#FF3B30"))
                btnBattery?.isEnabled = true
                btnBattery?.text = "ACTIVAR PERMISO"
                btnBattery?.alpha = 1.0f
                btnBattery?.setOnClickListener {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }

            val tvHint = dialog.findViewById<TextView>(R.id.tv_permission_hint)
            if (tvHint != null) {
                val countMissing = (if (!hasOverlay) 1 else 0) + (if (!hasUsage) 1 else 0) + (if (!hasBattery) 1 else 0)
                tvHint.text = "Faltan $countMissing permiso(s) por conceder"
            }
        }
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
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
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
                    stopLicenseCountdown()
                    SessionGuard.forceLogout(
                        this@MainActivity,
                        NativeBridge.getNativeString(NativeBridge.STRING_LICENSE_EXPIRED),
                        NativeBridge.getNativeString(NativeBridge.STRING_LICENSE_EXPIRED)
                    )
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
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
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
            System.loadLibrary("ncx")
        }
    }
    private external fun getSecureEndpoint(): String
}

