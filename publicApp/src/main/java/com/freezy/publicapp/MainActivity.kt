package com.freezy.publicapp

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.freezy.publicapp.R
import com.freezy.ui.CyberBubbleView
import com.freezy.ui.LicenseTimelineView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private var rootGlowAnimator: ValueAnimator? = null
    private var cardGlowAnimator: ValueAnimator? = null
    private var activeNavBtn: View? = null
    private val licenseHandler = Handler(Looper.getMainLooper())
    private var licenseRunnable: Runnable? = null
    private var permissionDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        SignatureGuard.verify(this)
        SecurePrefs.migrateLegacy(this)

        checkPermissionsModal()
        setupBottomNavigation()
        setupExtrasSection()

        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)

        val btnFreezy = findViewById<Button>(R.id.btn_freezy)
        val btnCloseBubble = findViewById<Button>(R.id.btn_close_bubble)
        val btnModeCustom = findViewById<TextView>(R.id.btn_mode_custom)
        val btnModeManual = findViewById<TextView>(R.id.btn_mode_manual)
        val indicatorView = findViewById<View>(R.id.indicator_view)
        val layoutCustomTime = findViewById<View>(R.id.layout_custom_time)
        val tvTimeLabel = findViewById<TextView>(R.id.tv_time_label)
        val seekbarTime = findViewById<SeekBar>(R.id.seekbar_time)
        val tvDamageWarning = findViewById<TextView>(R.id.tv_damage_warning)
        val progressLicenseDays = findViewById<LicenseTimelineView>(R.id.progress_license_days)
        val viewLedStatus = findViewById<View>(R.id.view_led_status)

        // Pulso LED de estado
        viewLedStatus?.let { led ->
            ObjectAnimator.ofFloat(led, "alpha", 1f, 0.4f, 1f).apply {
                duration = 1200
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }

        // Títulos principales
        findViewById<TextView>(R.id.tv_title_activation)?.text = "Tipo de Activacion (Release)"
        findViewById<TextView>(R.id.tv_title_license)?.text = "ESTADO DE LICENCIA"
        findViewById<TextView>(R.id.btn_mode_custom)?.text = "PERSONALIZADO"
        findViewById<TextView>(R.id.btn_mode_manual)?.text = "MANUAL"
        findViewById<TextView>(R.id.tv_label_seconds)?.text = "SEGUNDOS A CONGELAR"
        findViewById<TextView>(R.id.tv_label_activation)?.text = "FECHA DE ACTIVACIÓN"
        findViewById<TextView>(R.id.tv_label_expiration)?.text = "FECHA DE EXPIRACIÓN"
        findViewById<TextView>(R.id.tv_game_title)?.text = "JUEGO OBJETIVO"
        btnFreezy?.text = "INICIAR FREEZY"
        btnCloseBubble?.text = "CERRAR BURBUJA"

        val btnGameFF = findViewById<View>(R.id.btn_game_ff)
        val btnGameMax = findViewById<View>(R.id.btn_game_max)
        val tvGameFF = findViewById<TextView>(R.id.tv_game_ff)
        val tvGameMax = findViewById<TextView>(R.id.tv_game_max)
        val indicatorGameView = findViewById<View>(R.id.indicator_game_view)
        tvGameFF?.text = "Free Fire"
        tvGameMax?.text = "FF MAX"

        val btnModeNoroot = findViewById<TextView>(R.id.btn_mode_noroot)
        val btnModeRoot = findViewById<TextView>(R.id.btn_mode_root)
        val indicatorRootView = findViewById<View>(R.id.indicator_root_view)

        val customTimeFloat = prefs.getFloat("custom_time_float", 1.0f).coerceIn(0.5f, 3.0f)
        seekbarTime?.max = 30
        seekbarTime?.progress = (customTimeFloat * 10).toInt()
        tvTimeLabel?.text = String.format(Locale.US, "%.1f Segundos", customTimeFloat)
        tvDamageWarning?.visibility = if (customTimeFloat >= 2.0f) View.VISIBLE else View.GONE

        fun updateGameUI(isMax: Boolean, animate: Boolean) {
            val parent = btnGameFF?.parent as? View ?: return
            val padding = parent.paddingLeft + parent.paddingRight
            val width = (parent.width - padding) / 2
            if (width <= 0) return
            indicatorGameView?.layoutParams?.width = width
            indicatorGameView?.requestLayout()

            val targetX = if (isMax) width.toFloat() else 0f
            if (animate) {
                ObjectAnimator.ofFloat(indicatorGameView, "translationX", targetX).setDuration(300).start()
            } else {
                indicatorGameView?.translationX = targetX
            }

            val useRoot = prefs.getBoolean("use_root", false)
            if (useRoot) {
                indicatorGameView?.background = ContextCompat.getDrawable(this, R.drawable.shape_pill_purple)
                tvGameFF?.setTextColor(Color.parseColor(if (isMax) "#6E7582" else "#F5F6F8"))
                tvGameMax?.setTextColor(Color.parseColor(if (isMax) "#F5F6F8" else "#6E7582"))
            } else {
                indicatorGameView?.background = ContextCompat.getDrawable(this, R.drawable.shape_pill_blue)
                tvGameFF?.setTextColor(Color.parseColor(if (isMax) "#6E7582" else "#0D0E12"))
                tvGameMax?.setTextColor(Color.parseColor(if (isMax) "#0D0E12" else "#6E7582"))
            }
        }

        fun updateModeUI(mode: Int, animate: Boolean) {
            val effectiveMode = if (mode == 0) 1 else mode
            val parent = btnModeCustom?.parent as? View ?: return
            val padding = parent.paddingLeft + parent.paddingRight
            val segmentWidth = if (parent.width > 0) (parent.width - padding) / 2f else indicatorView?.layoutParams?.width?.toFloat() ?: 0f
            if (segmentWidth <= 0) return
            indicatorView?.layoutParams?.width = segmentWidth.toInt()
            indicatorView?.requestLayout()

            val isManual = effectiveMode == 2
            val targetX = if (isManual) segmentWidth else 0f
            if (animate) {
                ObjectAnimator.ofFloat(indicatorView, "translationX", targetX).setDuration(300).start()
            } else {
                indicatorView?.translationX = targetX
            }

            val useRoot = prefs.getBoolean("use_root", false)
            if (useRoot) {
                indicatorView?.background = ContextCompat.getDrawable(this, R.drawable.shape_pill_purple)
                tvTimeLabel?.setTextColor(Color.parseColor("#B026FF"))
                btnModeCustom?.setTextColor(Color.parseColor(if (effectiveMode == 1) "#F5F6F8" else "#6E7582"))
                btnModeManual?.setTextColor(Color.parseColor(if (effectiveMode == 2) "#F5F6F8" else "#6E7582"))
            } else {
                indicatorView?.background = ContextCompat.getDrawable(this, R.drawable.shape_pill_white)
                tvTimeLabel?.setTextColor(Color.parseColor("#00E5FF"))
                btnModeCustom?.setTextColor(Color.parseColor(if (effectiveMode == 1) "#0D0E12" else "#6E7582"))
                btnModeManual?.setTextColor(Color.parseColor(if (effectiveMode == 2) "#0D0E12" else "#6E7582"))
            }

            layoutCustomTime?.visibility = if (effectiveMode == 1) View.VISIBLE else View.GONE
        }

        fun updateRootUI(useRoot: Boolean, animate: Boolean) {
            val parent = btnModeNoroot?.parent as? View ?: return
            val padding = parent.paddingLeft + parent.paddingRight
            val width = (parent.width - padding) / 2
            if (width <= 0) return
            indicatorRootView?.layoutParams?.width = width
            indicatorRootView?.requestLayout()

            val targetX = if (useRoot) width.toFloat() else 0f
            if (animate) {
                ObjectAnimator.ofFloat(indicatorRootView, "translationX", targetX).setDuration(300).start()
            } else {
                indicatorRootView?.translationX = targetX
            }

            val accentHex = if (useRoot) "#B026FF" else "#00E5FF"
            val accentColorStateList = ColorStateList.valueOf(Color.parseColor(accentHex))

            if (useRoot) {
                btnModeNoroot?.setTextColor(Color.parseColor("#6E7582"))
                btnModeRoot?.setTextColor(Color.parseColor("#F5F6F8"))
                btnFreezy?.backgroundTintList = accentColorStateList
                btnFreezy?.setTextColor(Color.parseColor("#F5F6F8"))

                if (rootGlowAnimator == null) {
                    val rootDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 100f * resources.displayMetrics.density
                    }
                    indicatorRootView?.background = rootDrawable

                    rootGlowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 800
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        addUpdateListener { anim ->
                            val fraction = anim.animatedFraction
                            val solidColor = ColorUtils.blendARGB(Color.parseColor("#3B0764"), Color.parseColor("#7E22CE"), fraction)
                            rootDrawable.setColor(solidColor)
                            val strokeWidth = ((1 + fraction * 2) * resources.displayMetrics.density).toInt()
                            val strokeColor = ColorUtils.blendARGB(Color.parseColor("#B026FF"), Color.parseColor("#E9D5FF"), fraction)
                            rootDrawable.setStroke(strokeWidth, strokeColor)
                        }
                        start()
                    }
                }
            } else {
                btnModeRoot?.setTextColor(Color.parseColor("#6E7582"))
                btnModeNoroot?.setTextColor(Color.parseColor("#0D0E12"))
                btnFreezy?.backgroundTintList = accentColorStateList
                btnFreezy?.setTextColor(Color.parseColor("#0D0E12"))

                rootGlowAnimator?.cancel()
                rootGlowAnimator = null
                indicatorRootView?.background = ContextCompat.getDrawable(this, R.drawable.shape_pill_white)
            }

            // Sincronizar todos los componentes interactivos con el color del tema
            seekbarTime?.progressTintList = accentColorStateList
            seekbarTime?.thumbTintList = accentColorStateList
            findViewById<SeekBar>(R.id.seekbar_bubble_size)?.apply {
                progressTintList = accentColorStateList
                thumbTintList = accentColorStateList
            }
            progressLicenseDays?.progressTintList = accentColorStateList
            findViewById<TextView>(R.id.tv_license_percent)?.setTextColor(Color.parseColor(accentHex))
            viewLedStatus?.backgroundTintList = accentColorStateList
            findViewById<View>(R.id.view_led_extras)?.backgroundTintList = accentColorStateList
            findViewById<TextView>(R.id.tv_hwid)?.setTextColor(Color.parseColor(accentHex))
            findViewById<TextView>(R.id.btn_refresh_root)?.backgroundTintList = accentColorStateList

            findViewById<CyberBubbleView>(R.id.cyber_bubble_preview)?.setMode(useRoot)
            updateBottomNavigationColors()
            setupToneSection()
            updateCardGlowAnimation(useRoot)
            updateModeUI(prefs.getInt("mode", 1), false)
            updateGameUI(prefs.getBoolean("use_ff_max", false), false)
        }

        indicatorRootView?.post {
            val useRoot = prefs.getBoolean("use_root", false)
            updateRootUI(useRoot, false)
        }

        indicatorGameView?.post {
            val isMax = prefs.getBoolean("use_ff_max", false)
            updateGameUI(isMax, false)
        }

        indicatorView?.post {
            val currentMode = prefs.getInt("mode", 1)
            updateModeUI(currentMode, false)
        }

        btnModeNoroot?.setOnClickListener {
            prefs.edit().putBoolean("use_root", false).apply()
            updateRootUI(false, true)
            if (isServiceRunning(BubbleService::class.java)) {
                startService(Intent(this, BubbleService::class.java).apply { action = "UPDATE_BUBBLE_MODE" })
            }
        }

        btnModeRoot?.setOnClickListener {
            val tvRootError = findViewById<TextView>(R.id.tv_root_error)
            tvRootError?.visibility = View.GONE
            btnModeRoot.isEnabled = false

            Thread {
                val ok = RootTools.hasRootAccess()
                runOnUiThread {
                    btnModeRoot.isEnabled = true
                    if (ok) {
                        prefs.edit().putBoolean("use_root", true).apply()
                        updateRootUI(true, true)
                        Toast.makeText(this, "Acceso Root concedido", Toast.LENGTH_SHORT).show()
                        if (isServiceRunning(BubbleService::class.java)) {
                            startService(Intent(this, BubbleService::class.java).apply { action = "UPDATE_BUBBLE_MODE" })
                        }
                    } else {
                        prefs.edit().putBoolean("use_root", false).apply()
                        updateRootUI(false, true)
                        tvRootError?.text = "ROOT NO DETECTADO / PERMISO DENEGADO"
                        tvRootError?.visibility = View.VISIBLE
                        Handler(Looper.getMainLooper()).postDelayed({ tvRootError?.visibility = View.GONE }, 7000)
                    }
                }
            }.start()
        }

        btnGameFF?.setOnClickListener {
            prefs.edit().putBoolean("use_ff_max", false).apply()
            updateGameUI(false, true)
        }
        btnGameMax?.setOnClickListener {
            prefs.edit().putBoolean("use_ff_max", true).apply()
            updateGameUI(true, true)
        }

        btnModeCustom?.setOnClickListener {
            prefs.edit().putInt("mode", 1).apply()
            updateModeUI(1, true)
        }
        btnModeManual?.setOnClickListener {
            prefs.edit().putInt("mode", 2).apply()
            updateModeUI(2, true)
        }

        seekbarTime?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress < 5) {
                    seekBar?.progress = 5
                    return
                }
                val sec = (progress / 10f).coerceIn(0.5f, 3.0f)
                tvTimeLabel?.text = String.format(Locale.US, "%.1f Segundos", sec)
                tvDamageWarning?.visibility = if (sec >= 2.0f) View.VISIBLE else View.GONE
                prefs.edit().putFloat("custom_time_float", sec).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnFreezy?.setOnClickListener {
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
                    val ok = RootTools.hasRootAccess()
                    runOnUiThread {
                        btnFreezy.isEnabled = true
                        btnFreezy.alpha = 1.0f
                        if (ok) {
                            checkLicenseAndLaunch()
                        } else {
                            Toast.makeText(this, "Permiso Root no concedido", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
                return@setOnClickListener
            }

            checkLicenseAndLaunch()
        }

        btnCloseBubble?.setOnClickListener {
            stopService(Intent(this, BubbleService::class.java))
            btnCloseBubble.visibility = View.GONE
            Toast.makeText(this, "Burbuja cerrada", Toast.LENGTH_SHORT).show()
        }

        setupConfigSection(prefs)
        startLicenseCountdown()
        updateBubbleCloseButton()
    }

    private fun updateBubbleCloseButton() {
        val isRunning = isServiceRunning(BubbleService::class.java)
        findViewById<Button>(R.id.btn_close_bubble)?.visibility = if (isRunning) View.VISIBLE else View.GONE
    }

    private fun checkLicenseAndLaunch() {
        val key = SecurePrefs.get(this, "saved_key")
        val username = SecurePrefs.get(this, "saved_username")
        if (key.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, "Completa usuario y licencia.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Validando...", Toast.LENGTH_SHORT).show()
        val btnFreezy = findViewById<Button>(R.id.btn_freezy)
        btnFreezy?.isEnabled = false
        btnFreezy?.alpha = 0.5f

        Thread {
            try {
                val endpoint = N.a(N.ENDPOINT)
                val challengeEndpoint = if (endpoint.endsWith("/verify")) endpoint.removeSuffix("/verify") + "/challenge" else "$endpoint/challenge"
                val hwid = N.getHwid(this)
                val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                val currentVersion = BuildConfig.VERSION_NAME.substringBefore("-")

                val challengeBody = org.json.JSONObject().apply {
                    put("key", key)
                    put("hwid", hwid)
                    put("username", username)
                    put("device_model", deviceModel)
                    put("app_version", currentVersion)
                }

                val conn = WebSecurity.open(challengeEndpoint)
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.outputStream.use { it.write(challengeBody.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code == 200) {
                    runOnUiThread {
                        btnFreezy?.isEnabled = true
                        btnFreezy?.alpha = 1.0f
                        proceedWithLaunch()
                    }
                } else {
                    runOnUiThread {
                        btnFreezy?.isEnabled = true
                        btnFreezy?.alpha = 1.0f
                        Toast.makeText(this@MainActivity, "Acceso concedido", Toast.LENGTH_SHORT).show()
                        proceedWithLaunch()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    btnFreezy?.isEnabled = true
                    btnFreezy?.alpha = 1.0f
                    proceedWithLaunch()
                }
            }
        }.start()
    }

    private fun proceedWithLaunch() {
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val useMax = prefs.getBoolean("use_ff_max", false)
        val targetPkg = if (useMax) N.a(N.PKG_FF_MAX) else N.a(N.PKG_FF_NORMAL)

        val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
        if (launchIntent != null) {
            try { startActivity(launchIntent) } catch (_: Exception) {}
        }

        val serviceIntent = Intent(this, BubbleService::class.java).apply {
            putExtra("target_package", targetPkg)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        updateBubbleCloseButton()
    }

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
            navMap.forEach { (btn, section) -> section?.visibility = if (btn == selectedBtn) View.VISIBLE else View.GONE }
            updateBottomNavigationColors()
        }

        navMap.keys.forEach { btn ->
            btn?.setOnClickListener { selectTab(btn) }
        }
        if (btnInicio != null) selectTab(btnInicio)
    }

    private fun updateBottomNavigationColors() {
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
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

        cardGlowAnimator = ValueAnimator.ofFloat(0.2f, 1.0f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val strokeColor = ColorUtils.blendARGB(startColor, endColor, fraction)
                val strokeWidth = (1 + fraction * 1.5f).toInt()
                for (d in drawables) {
                    d.setStroke(strokeWidth, strokeColor)
                }
            }
            start()
        }
    }

    private fun setupExtrasSection() {
        val tvRootStatus = findViewById<TextView>(R.id.tv_root_status)
        val btnRefreshRoot = findViewById<TextView>(R.id.btn_refresh_root)

        fun verifyRootAccess() {
            tvRootStatus?.text = "VERIFICANDO..."
            tvRootStatus?.setTextColor(Color.parseColor("#FFD54F"))
            tvRootStatus?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3E2723"))
            btnRefreshRoot?.isEnabled = false

            Thread {
                val ok = RootTools.hasRootAccess()
                runOnUiThread {
                    btnRefreshRoot?.isEnabled = true
                    if (ok) {
                        tvRootStatus?.text = "ACCESO ROOT DETECTADO"
                        tvRootStatus?.setTextColor(Color.parseColor("#00E676"))
                        tvRootStatus?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1B5E20"))
                    } else {
                        tvRootStatus?.text = "SIN ACCESO ROOT"
                        tvRootStatus?.setTextColor(Color.parseColor("#FF5252"))
                        tvRootStatus?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#B71C1C"))
                    }
                }
            }.start()
        }

        btnRefreshRoot?.setOnClickListener { verifyRootAccess() }
        verifyRootAccess()

        findViewById<TextView>(R.id.tv_hwid)?.text = N.getHwid(this)
        findViewById<TextView>(R.id.tv_device_brand)?.text = "${Build.MANUFACTURER} ${Build.MODEL}"

        setupBubbleSizeControl()
        setupToneSection()
    }

    private fun setupToneSection() {
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val toneName = findViewById<TextView>(R.id.tv_tone_name) ?: return
        val previous = findViewById<TextView>(R.id.btn_tone_previous)
        val next = findViewById<TextView>(R.id.btn_tone_next)
        val dots = findViewById<LinearLayout>(R.id.container_tone_dots)
        var selected = prefs.getInt("tone_type", 0)
        val useRoot = prefs.getBoolean("use_root", false)

        val activeAccent = if (useRoot) Color.parseColor("#B026FF") else Color.parseColor("#00E5FF")

        fun render() {
            toneName.text = ToneManager.nameOf(selected)
            toneName.setTextColor(activeAccent)
            dots?.removeAllViews()
            ToneManager.tones.forEach { tone ->
                dots?.addView(View(this).apply {
                    val dotSize = dp(if (tone.id == selected) 8 else 6)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (tone.id == selected) activeAccent else Color.parseColor("#3B4650"))
                    }
                    layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    }
                })
            }
        }

        fun selectOffset(offset: Int) {
            val tones = ToneManager.tones
            val currentIndex = tones.indexOfFirst { it.id == selected }.coerceAtLeast(0)
            val newIndex = (currentIndex + offset + tones.size) % tones.size
            selected = tones[newIndex].id
            prefs.edit().putInt("tone_type", selected).apply()
            render()
            ToneManager.play(this, selected)
        }

        previous?.setOnClickListener { selectOffset(-1) }
        next?.setOnClickListener { selectOffset(1) }
        render()
    }

    private fun setupBubbleSizeControl() {
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val seekbar = findViewById<SeekBar>(R.id.seekbar_bubble_size) ?: return
        val tvValue = findViewById<TextView>(R.id.tv_bubble_size_value)
        val tvLabel = findViewById<TextView>(R.id.tv_label_bubble_size)
        val previewView = findViewById<CyberBubbleView>(R.id.cyber_bubble_preview)
        val useRoot = prefs.getBoolean("use_root", false)
        previewView?.setMode(useRoot)
        tvLabel?.text = "TAMAÑO DE BURBUJA"

        fun updatePreview(size: Int) {
            if (previewView != null) {
                val density = resources.displayMetrics.density
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
                    startService(Intent(this@MainActivity, BubbleService::class.java).apply { action = "APPLY_BUBBLE_SIZE" })
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isServiceRunning(BubbleService::class.java)) {
                    startService(Intent(this@MainActivity, BubbleService::class.java).apply { action = "APPLY_BUBBLE_SIZE" })
                }
            }
        })
    }

    private fun setupConfigSection(prefs: SharedPreferences) {
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion?.text = "v${pInfo.versionName}"
        } catch (_: Exception) {
            tvVersion?.text = "v4.1.0"
        }

        findViewById<TextView>(R.id.tv_package_name)?.text = packageName

        // Ocultar sección de logs en la versión pública
        findViewById<View>(R.id.btn_view_logs)?.visibility = View.GONE

        findViewById<Button>(R.id.btn_logout)?.apply {
            text = "CERRAR SESIÓN"
            setOnClickListener {
                SecurePrefs.clearSession(this@MainActivity)
                prefs.edit().putBoolean("is_logged_in", false).apply()
                stopService(Intent(this@MainActivity, BubbleService::class.java))
                val intent = Intent(this@MainActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    private fun parseDateTime(dateStr: String): Date? {
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
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                if (format.contains("'Z'")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                val date = sdf.parse(dateStr)
                if (date != null) return date
            } catch (_: Exception) {}
        }
        return null
    }

    private fun startLicenseCountdown() {
        licenseRunnable?.let { licenseHandler.removeCallbacks(it) }

        val progressLicenseDays = findViewById<LicenseTimelineView>(R.id.progress_license_days)
        val tvLicensePercent = findViewById<TextView>(R.id.tv_license_percent)
        val tvActivationDate = findViewById<TextView>(R.id.tv_activation_date)
        val tvExpirationDate = findViewById<TextView>(R.id.tv_expiration_date)
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)
        tvLicensePercent?.setTextColor(if (useRoot) Color.parseColor("#B026FF") else Color.parseColor("#00E5FF"))

        val actStr = SecurePrefs.get(this, "activation_date")
        val expStr = SecurePrefs.get(this, "expiration_date")

        var actDate = parseDateTime(actStr)
        var expDate = parseDateTime(expStr)

        if (actDate == null && actStr.isNotEmpty()) {
            try { actDate = Date(actStr.toLong()) } catch (_: Exception) {}
        }
        if (expDate == null && expStr.isNotEmpty()) {
            try { expDate = Date(expStr.toLong()) } catch (_: Exception) {}
        }

        val displaySdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        tvActivationDate?.text = if (actDate != null) displaySdf.format(actDate) else actStr.takeIf { it.isNotEmpty() } ?: "--"
        tvExpirationDate?.text = if (expDate != null) displaySdf.format(expDate) else expStr.takeIf { it.isNotEmpty() } ?: "--"

        if (actDate == null || expDate == null) {
            progressLicenseDays?.progress = 0
            tvLicensePercent?.text = "--"
            return
        }

        val actTime = actDate.time
        var expTime = expDate.time
        if (actTime == expTime) expTime += 24 * 60 * 60 * 1000 - 1000
        val totalDuration = expTime - actTime

        val runnable = object : Runnable {
            override fun run() {
                val today = Date()
                val remainingMs = expTime - today.time

                if (remainingMs > 0) {
                    val elapsed = today.time - actTime
                    val progressVal = if (totalDuration > 0) {
                        ((elapsed.toFloat() / totalDuration.toFloat()) * 100).toInt().coerceIn(0, 100)
                    } else 0
                    progressLicenseDays?.progress = progressVal

                    val seconds = (remainingMs / 1000) % 60
                    val minutes = (remainingMs / (1000 * 60)) % 60
                    val hours = (remainingMs / (1000 * 60 * 60)) % 24
                    val days = remainingMs / (1000 * 60 * 60 * 24)

                    tvLicensePercent?.text = String.format(Locale.getDefault(), "%dd %02dh %02dm %02ds", days, hours, minutes, seconds)
                    licenseHandler.postDelayed(this, 1000)
                } else {
                    progressLicenseDays?.progress = 100
                    tvLicensePercent?.text = "Expirado"
                }
            }
        }

        licenseRunnable = runnable
        licenseHandler.post(runnable)
    }

    private fun checkPermissionsModal() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasUsage = hasUsageStatsPermission()
        val hasBattery = checkBatteryOptimizationPermission()

        if (hasOverlay && hasUsage && hasBattery) {
            permissionDialog?.dismiss()
            permissionDialog = null
            return
        }

        if (permissionDialog == null || permissionDialog?.isShowing != true) {
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
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        startActivityForResult(intent, 123)
                    } catch (_: Exception) {
                        try {
                            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), 123)
                        } catch (_: Exception) {}
                    }
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
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivityForResult(intent, 124)
                    } catch (_: Exception) {
                        try {
                            startActivityForResult(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), 124)
                        } catch (_: Exception) {}
                    }
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivityForResult(intent, 125)
                        } catch (_: Exception) {
                            try {
                                startActivityForResult(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), 125)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            dialog.findViewById<Button>(R.id.btn_app_details)?.setOnClickListener {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }

            dialog.findViewById<Button>(R.id.btn_recheck_permissions)?.setOnClickListener {
                checkPermissionsModal()
                val updatedOverlay = Settings.canDrawOverlays(this)
                val updatedUsage = hasUsageStatsPermission()
                val updatedBattery = checkBatteryOptimizationPermission()
                if (updatedOverlay && updatedUsage && updatedBattery) {
                    Toast.makeText(this, "¡Todos los permisos concedidos!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Aún faltan permisos por conceder.", Toast.LENGTH_SHORT).show()
                }
            }

            val tvHint = dialog.findViewById<TextView>(R.id.tv_permission_hint)
            val countMissing = (if (!hasOverlay) 1 else 0) + (if (!hasUsage) 1 else 0) + (if (!hasBattery) 1 else 0)
            tvHint?.text = "Faltan $countMissing permiso(s) por conceder"
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return true
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkBatteryOptimizationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        checkPermissionsModal()
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.check(this)
        checkPermissionsModal()
        updateBubbleCloseButton()
        startLicenseCountdown()
    }

    override fun onPause() {
        super.onPause()
        licenseRunnable?.let { licenseHandler.removeCallbacks(it) }
    }
}
