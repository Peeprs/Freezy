package com.freezy

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.freezy.network.FovOverlay
import com.system.network.ui.R
import kotlin.math.abs
import org.json.JSONObject

class BubbleService : Service() {

    private var licenseCheckFailCount = 0
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubbleIcon: ImageView
    private lateinit var btnFakeLag: ImageView
    private lateinit var cyberBubble: com.freezy.ui.CyberBubbleView
    private lateinit var arcOverlay: ArcProgressView
    private lateinit var caraFakeLag: View

    private lateinit var fovOverlay: FovOverlay
    private var fovParams = WindowManager.LayoutParams()

    private var suProcess: Process? = null
    private var suOutputStream: java.io.DataOutputStream? = null

    private val handler = Handler(Looper.getMainLooper())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private var isNoRecoilEnabled = false
    private var recoilStrength = 50
    private var inputMonitor: com.freezy.network.InputMonitor? = null

    private var isFreezing = false
    private var fillAnimator: ValueAnimator? = null

    // Vista personalizada que dibuja el arco circular de progreso
    // Vista personalizada que dibuja el arco circular de progreso
    inner class ArcProgressView(context: Context, var isRootMode: Boolean) : View(context) {
        fun updateMode(rootMode: Boolean) {
            this.isRootMode = rootMode
            invalidate()
        }
        var progress = 0f // 0.0 a 1.0

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                isForceDarkAllowed = false
            }
        }

        private fun getActiveColor(): Int {
            return if (isRootMode) Color.parseColor("#B026FF") else Color.parseColor("#00E5FF")
        }

        private val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = getActiveColor()
                    style = Paint.Style.STROKE
                    strokeWidth = 16f
                    strokeCap = Paint.Cap.ROUND
                    alpha = 255
                }
        private val rect = RectF()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = (59 * resources.displayMetrics.density).toInt()
            setMeasuredDimension(size, size)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = paint.strokeWidth / 2f + 2f
            rect.set(pad, pad, width - pad, height - pad)

            paint.color = getActiveColor()
            paint.alpha = 255
            canvas.drawArc(rect, -90f, 360f * progress, false, paint)
        }
    }

    private var targetPackage: String? = null

    private fun updateBubbleSize() {
        if (::windowManager.isInitialized && ::bubbleView.isInitialized && bubbleView.parent != null) {
            val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
            val density = resources.displayMetrics.density
            val sizePercent = prefs.getInt("bubble_size", 100).coerceIn(40, 100)
            val sizePx = (66 * density * sizePercent / 100f).toInt()
            params.width = sizePx
            params.height = sizePx
            try {
                windowManager.updateViewLayout(bubbleView, params)
            } catch (e: Exception) {
                recreateBubbles()
            }
        } else {
            recreateBubbles()
        }
    }

    private fun recreateBubbles() {
        if (::windowManager.isInitialized) {
            try {
                if (::bubbleView.isInitialized && bubbleView.parent != null) {
                    windowManager.removeView(bubbleView)
                }
            } catch (e: Exception) {}
            setupBubble()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            targetPackage = intent.getStringExtra("TARGET_PACKAGE")
            if (intent.action == "UPDATE_BUBBLE_MODE") {
                recreateBubbles()
                return START_STICKY
            }
            if (intent.action == "APPLY_BUBBLE_SIZE") {
                updateBubbleSize()
                return START_STICKY
            }
        }

        // Actualizar el color y modo de la burbuja
        val isRootMode =
                getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
                        .getBoolean("use_root", false)
        if (this::cyberBubble.isInitialized) {
            cyberBubble.setMode(isRootMode)
        }
        if (this::bubbleIcon.isInitialized) {
            if (isRootMode) {
                bubbleIcon.setColorFilter(
                        Color.parseColor("#B026FF"),
                        android.graphics.PorterDuff.Mode.SRC_IN
                )
            } else {
                bubbleIcon.setColorFilter(
                        Color.parseColor("#00E5FF"),
                        android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
            if (this::arcOverlay.isInitialized) {
                arcOverlay.updateMode(isRootMode)
            }
        }

        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        LagController.initLicencia(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        setupFov()

        // Registrar siempre el callback para recibir notificaciones JNI del disparo
        NativeBridge.registerUiCallback(this)

        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)

        // Otorgar permisos SU para lectura de /dev/input/event* SOLO si el usuario activó el modo
        // root
        if (useRoot) {
            Thread {
                        executeRootCommand("chmod 666 /dev/input/event*")
                        executeRootCommand("chcon u:object_r:input_device:s0 /dev/input/event*")
                        executeRootCommand("setenforce 0")
                    }
                    .start()
        }

        setupBubble()

        startLicenseCheck()

        getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_bubble_running", true)
                .apply()
    }

    private val licenseCheckRunnable =
            object : Runnable {
                override fun run() {
                    if (isAppOrGameInForeground()) {
                        if (isNetworkConnected()) {
                            checkLicense()
                        }
                    }
                    handler.postDelayed(this, 5 * 60 * 1000) // Cada 5 minutos
                }
            }

    private fun startLicenseCheck() {
        handler.post(licenseCheckRunnable)
    }

    private fun checkLicense() {
        val username = SecurePrefs.getSecureString(this@BubbleService, "saved_username")
        val key = SecurePrefs.getSecureString(this@BubbleService, "saved_key")
        if (username.isEmpty() || key.isEmpty()) return
        val endpointUrl = SecurePrefs.getSecureString(this@BubbleService, "secure_endpoint").ifEmpty {
            NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT)
        }

        Thread {
                    try {
                        val challengeEndpoint =
                                if (endpointUrl.endsWith("/verify"))
                                        endpointUrl.replace("/verify", "/challenge")
                                else "$endpointUrl/challenge"
                        val verifyEndpoint =
                                if (endpointUrl.endsWith("/verify")) endpointUrl
                                else "$endpointUrl/verify"
                        val hwid = NativeBridge.getHWID(this@BubbleService)
                        val deviceModel =
                                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

                        val challengeConn = WebSecurity.open(challengeEndpoint)
                        challengeConn.requestMethod = "POST"
                        challengeConn.setRequestProperty("Content-Type", "application/json")
                        challengeConn.connectTimeout = 10000
                        challengeConn.readTimeout = 10000
                        challengeConn.doOutput = true

                        val currentAppVersion = com.system.network.ui.BuildConfig.VERSION_NAME
                        val challengeJson =
                                "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"username\": \"$username\", \"device_model\": \"$deviceModel\", \"app_version\": \"$currentAppVersion\"}"
                        challengeConn.outputStream.write(challengeJson.toByteArray(Charsets.UTF_8))

                        if (challengeConn.responseCode != 200) {
                            licenseCheckFailCount++
                            if (licenseCheckFailCount >= 3) {
                                handleLicenseExpired(
                                        NativeBridge.getNativeString(NativeBridge.STRING_CONN_ERROR)
                                )
                            }
                            return@Thread
                        }

                        val nonce =
                                JSONObject(challengeConn.inputStream.bufferedReader().readText())
                                        .getString("nonce")

                        val HWID_PRIVADO = NativeBridge.getHmacSecret()
                        val algorithm = "HmacSHA256"
                        val mac = javax.crypto.Mac.getInstance(algorithm)
                        mac.init(
                                javax.crypto.spec.SecretKeySpec(
                                        HWID_PRIVADO.toByteArray(Charsets.UTF_8),
                                        algorithm
                                )
                        )
                        val hmacHex =
                                mac.doFinal(nonce.toByteArray(Charsets.UTF_8)).joinToString("") {
                                    "%02x".format(it)
                                }

                        val verifyConn = WebSecurity.open(verifyEndpoint)
                        verifyConn.requestMethod = "POST"
                        verifyConn.setRequestProperty("Content-Type", "application/json")
                        verifyConn.connectTimeout = 10000
                        verifyConn.readTimeout = 10000
                        verifyConn.doOutput = true

                        val verifyJson =
                                "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"hmac\": \"$hmacHex\", \"app_version\": \"$currentAppVersion\"}"
                        verifyConn.outputStream.write(verifyJson.toByteArray(Charsets.UTF_8))

                        val responseCode = verifyConn.responseCode
                        if (responseCode == 200) {
                            val responseBody = verifyConn.inputStream.bufferedReader().readText()
                            val jsonObject = JSONObject(responseBody)
                            val isValid = jsonObject.getBoolean("valid")

                            if (!isValid) {
                                val message =
                                        jsonObject.optString(
                                                "message",
                                                NativeBridge.getNativeString(
                                                        NativeBridge.STRING_LICENSE_EXPIRED
                                                )
                                        )
                                handleLicenseExpired(message)
                            } else {
                                licenseCheckFailCount = 0
                                val warning = jsonObject.optString("update_warning", "")
                                if (warning.isNotEmpty()) {
                                    handler.post {
                                        Toast.makeText(
                                                        this@BubbleService,
                                                        warning,
                                                        Toast.LENGTH_LONG
                                                )
                                                .show()
                                    }
                                }
                            }
                        } else {
                            val errorBody =
                                    verifyConn.errorStream?.bufferedReader()?.readText() ?: ""
                            val serverMessage =
                                    try {
                                        JSONObject(errorBody)
                                                .optString(
                                                        "message",
                                                        NativeBridge.getNativeString(
                                                                NativeBridge.STRING_LICENSE_EXPIRED
                                                        )
                                                )
                                    } catch (e: Exception) {
                                        "Error: $responseCode"
                                    }
                            handleLicenseExpired(serverMessage)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        licenseCheckFailCount++
                        if (licenseCheckFailCount >= 3) {
                            handleLicenseExpired(
                                    NativeBridge.getNativeString(NativeBridge.STRING_CONN_ERROR)
                            )
                        }
                    }
                }
                .start()
    }

    private fun handleLicenseExpired(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()

            val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_logged_in", false).apply()

            stopSelf()

            if (isAppOrGameInForeground()) {
                val intent = Intent(this, LoginActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            }
        }
    }

    private fun startForegroundNotification() {
        val channelId = "freezy_service_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch =
                    NotificationChannel(
                            channelId,
                            NativeBridge.getNativeString(NativeBridge.STRING_BUBBLE_NOTIF_TITLE),
                            NotificationManager.IMPORTANCE_LOW
                    )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
        val notif =
                NotificationCompat.Builder(this, channelId)
                        .setContentTitle(
                                NativeBridge.getNativeString(NativeBridge.STRING_BUBBLE_NOTIF_TITLE)
                        )
                        .setContentText(
                                NativeBridge.getNativeString(NativeBridge.STRING_BUBBLE_NOTIF_TEXT)
                        )
                        .setSmallIcon(android.R.drawable.ic_secure)
                        .build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34)
                    startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notif)
        } catch (e: Exception) {
            startForeground(1, notif)
        }
    }

    private fun setupFov() {
        fovOverlay = FovOverlay(this)

        fovParams =
                WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                )
    }

    private fun setupBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)

        bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_layout, null)
        btnFakeLag = bubbleView.findViewById(R.id.btn_fake_lag)
        bubbleIcon = btnFakeLag
        cyberBubble = bubbleView.findViewById(R.id.cyber_bubble_view)
        caraFakeLag = bubbleView.findViewById(R.id.cara_fake_lag)

        cyberBubble.setMode(useRoot)
        cyberBubble.setActiveState(isFreezing)

        btnFakeLag.isClickable = false
        btnFakeLag.isFocusable = false

        arcOverlay = ArcProgressView(this, useRoot)
        arcOverlay.visibility = View.GONE

        val density = resources.displayMetrics.density
        val sizePercent = prefs.getInt("bubble_size", 100).coerceIn(40, 100)
        val sizePx = (66 * density * sizePercent / 100f).toInt()

        params =
                WindowManager.LayoutParams(
                                sizePx,
                                sizePx,
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                        WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                                PixelFormat.TRANSLUCENT
                        )
                        .apply {
                            gravity = Gravity.TOP or Gravity.START
                            x = prefs.getInt("bubble_x", 100)
                            y = prefs.getInt("bubble_y", 200)
                        }
        windowManager.addView(bubbleView, params)

        setupTouchListener()
        actualizarUI()
    }

    private fun actualizarUI() {
        val useRoot =
                getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
                        .getBoolean("use_root", false)
        val isActive = if (useRoot) LagController.fakeLagActivo else isFreezing

        if (::cyberBubble.isInitialized) {
            cyberBubble.setMode(useRoot)
            cyberBubble.setActiveState(isActive)
        }

        if (::btnFakeLag.isInitialized && ::caraFakeLag.isInitialized) {
            val colorStr = if (useRoot) "#B026FF" else "#00E5FF"
            btnFakeLag.setColorFilter(
                    Color.parseColor(colorStr),
                    android.graphics.PorterDuff.Mode.SRC_IN
            )
            btnFakeLag.alpha = if (isActive) 1.0f else (if (useRoot) 0.6f else 1.0f)
        }

        if (::arcOverlay.isInitialized) {
            arcOverlay.updateMode(useRoot)
        }
    }

    private fun setupTouchListener() {
        if (::caraFakeLag.isInitialized) {
            caraFakeLag.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isDragging = false
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 8 || abs(dy) > 8) {
                            isDragging = true
                        }
                        if (isDragging) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(bubbleView, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            caraFakeLag.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.KEYBOARD_TAP
                            )
                            onBubbleTapped()
                        } else {
                            getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
                                    .edit()
                                    .putInt("bubble_x", params.x)
                                    .putInt("bubble_y", params.y)
                                    .apply()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun onBubbleTapped() {
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)

        val mode = prefs.getInt("mode", 0)

        // Modo Manual: el tap siempre hace toggle (ON/OFF)
        if (mode == 2) {
            toggleManual(useRoot)
            return
        }

        // Modos Auto y Personalizado: ignorar taps mientras ya está activo
        if (isFreezing) return

        val duration =
                when (mode) {
                    1 -> (prefs.getFloat("custom_time_float", 3f) * 1000).toLong()
                    else -> 3000L
                }
        isFreezing = true
        startFreeze(useRoot)
        startArcAnimation(duration)
        handler.postDelayed({ if (isFreezing) stopFreeze(useRoot) }, duration)
    }

    fun onFiringStateChanged(isFiring: Boolean) {
        if (::fovOverlay.isInitialized) {
            fovOverlay.setFiringState(isFiring)
        }
    }

    private fun toggleManual(useRoot: Boolean) {
        if (isFreezing) {
            stopFreeze(useRoot)
        } else {
            isFreezing = true
            startFreeze(useRoot)
            if (::cyberBubble.isInitialized) {
                cyberBubble.setActiveState(true)
                cyberBubble.setProgress(1f)
            }
        }
    }

    private fun executeRootCommand(command: String) {
        if (suProcess == null) {
            try {
                suProcess = Runtime.getRuntime().exec("su")
                suOutputStream = java.io.DataOutputStream(suProcess!!.outputStream)
            } catch (e: Exception) {
                e.printStackTrace()
                handler.post {
                    Toast.makeText(
                                    this,
                                    NativeBridge.getNativeString(NativeBridge.STRING_ROOT_ERROR),
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
                return
            }
        }
        try {
            suOutputStream?.writeBytes(command + "\n")
            suOutputStream?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
            suProcess = null // Forzar reinicio en el próximo comando
            suOutputStream = null
        }
    }

    private fun startFreeze(useRoot: Boolean) {
        playSoundFromRes(R.raw.coin_on)
        Logger.log(
                this,
                NativeBridge.getNativeString(NativeBridge.STRING_FAKE_LAG_ACTIVE) +
                        " (Root: $useRoot)"
        )

        if (useRoot) {
            LagController.toggleFakeLag(true, true)
        } else {
            try {
                // Iniciar la VPN dinámicamente
                val vpnIntent =
                        Intent(this, AntigravityFirewall::class.java).apply {
                            putExtra("TARGET_PACKAGE", targetPackage ?: "com.dts.freefiremax")
                        }
                startService(vpnIntent)
                LagController.toggleFakeLag(true, false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        actualizarUI()
    }

    private fun stopFreeze(useRoot: Boolean) {
        playSoundFromRes(R.raw.coin_off)
        Logger.log(this, NativeBridge.getNativeString(NativeBridge.STRING_FAKE_LAG_DEACTIVATED))
        isFreezing = false

        fillAnimator?.cancel()
        if (::cyberBubble.isInitialized) {
            cyberBubble.setProgress(0f)
            cyberBubble.setActiveState(false)
        }
        if (useRoot) {
            LagController.toggleFakeLag(false, true)
        } else {
            try {
                LagController.toggleFakeLag(false, false)

                // Detener la VPN de inmediato
                val vpnIntent =
                        Intent(this, AntigravityFirewall::class.java).apply { action = "STOP_VPN" }
                startService(vpnIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        actualizarUI()
    }

    private fun playSoundFromRes(resId: Int) {
        try {
            val mediaPlayer = android.media.MediaPlayer.create(this, resId)
            mediaPlayer?.setOnCompletionListener { mp -> mp.release() }
            mediaPlayer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startArcAnimation(duration: Long) {
        if (::cyberBubble.isInitialized) {
            cyberBubble.setActiveState(true)
            cyberBubble.setProgress(0f)
        }

        fillAnimator?.cancel()
        fillAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    this.duration = duration
                    addUpdateListener {
                        val p = it.animatedValue as Float
                        if (::cyberBubble.isInitialized) {
                            cyberBubble.setProgress(p)
                        }
                    }
                    addListener(
                            object : android.animation.Animator.AnimatorListener {
                                override fun onAnimationStart(a: android.animation.Animator) {}
                                override fun onAnimationEnd(a: android.animation.Animator) {
                                    if (::cyberBubble.isInitialized) {
                                        cyberBubble.setProgress(0f)
                                    }
                                }
                                override fun onAnimationCancel(a: android.animation.Animator) {
                                    if (::cyberBubble.isInitialized) {
                                        cyberBubble.setProgress(0f)
                                    }
                                }
                                override fun onAnimationRepeat(a: android.animation.Animator) {}
                            }
                    )
                    start()
                }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Limpieza de iptables si estaban activos en LagController
        if (LagController.fakeLagActivo) {
            LagController.desactivarFakeLagRoot()
            LagController.fakeLagActivo = false
        }

        if (isFreezing)
                stopFreeze(
                        getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
                                .getBoolean("use_root", false)
                )
        fillAnimator?.cancel()

        // Detener No-Recoil y Monitoreo de Entrada
        try {
            val recoilIntent =
                    Intent(this, com.freezy.network.RecoilService::class.java).apply {
                        action = "STOP_RECOIL"
                    }
            startService(recoilIntent)
            inputMonitor?.stopMonitoring()
        } catch (e: Exception) {}

        // Limpieza de Overlays para evitar que queden pegados en pantalla
        if (::bubbleView.isInitialized && bubbleView.parent != null) {
            windowManager.removeView(bubbleView)
        }
        if (::fovOverlay.isInitialized && fovOverlay.parent != null) {
            windowManager.removeView(fovOverlay)
        }

        // Cerrar el shell root correctamente
        try {
            suOutputStream?.writeBytes("exit\n")
            suOutputStream?.flush()
            suOutputStream?.close()
            suProcess?.destroy()
        } catch (e: Exception) {}

        getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_bubble_running", false)
                .apply()
    }

    private fun isAppOrGameInForeground(): Boolean {
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val targetPkg =
                prefs.getString("TARGET_PACKAGE", "com.dts.freefiremax") ?: "com.dts.freefiremax"

        // 1. Usar UsageStatsManager (si está permitido)
        try {
            val usm =
                    getSystemService(Context.USAGE_STATS_SERVICE) as?
                            android.app.usage.UsageStatsManager
            if (usm != null) {
                val time = System.currentTimeMillis()
                // Consultamos las estadísticas de los últimos 20 segundos
                val stats =
                        usm.queryUsageStats(
                                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                                time - 1000 * 20,
                                time
                        )
                if (!stats.isNullOrEmpty()) {
                    var recentActiveUsage: android.app.usage.UsageStats? = null
                    for (usage in stats) {
                        if (recentActiveUsage == null ||
                                        usage.lastTimeUsed > recentActiveUsage.lastTimeUsed
                        ) {
                            recentActiveUsage = usage
                        }
                    }
                    if (recentActiveUsage != null) {
                        val pkgName = recentActiveUsage.packageName
                        if (pkgName == packageName ||
                                        pkgName == targetPkg ||
                                        pkgName == "com.dts.freefiremax" ||
                                        pkgName == "com.dts.freefireth"
                        ) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback: Revisar procesos en primer plano a través de ActivityManager (solo detectará
        // nuestra app)
        try {
            val appProcesses =
                    (getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
                            ?.runningAppProcesses
            if (appProcesses != null) {
                for (appProcess in appProcesses) {
                    if (appProcess.importance ==
                                    android.app.ActivityManager.RunningAppProcessInfo
                                            .IMPORTANCE_FOREGROUND
                    ) {
                        if (appProcess.processName == packageName) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }

    private fun isNetworkConnected(): Boolean {
        try {
            val cm =
                    getSystemService(Context.CONNECTIVITY_SERVICE) as?
                            android.net.ConnectivityManager
                            ?: return false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val nw = cm.activeNetwork ?: return false
                val actNw = cm.getNetworkCapabilities(nw) ?: return false
                return actNw.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        actNw.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        actNw.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        actNw.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            } else {
                @Suppress("DEPRECATION") val nwInfo = cm.activeNetworkInfo ?: return false
                @Suppress("DEPRECATION") return nwInfo.isConnected
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return true // Asumimos conectado para evitar crasheos catastróficos por políticas del
            // OS
        }
    }

    override fun onBind(intent: Intent): IBinder? = null
}
