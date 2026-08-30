package com.freezy.publicapp

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.freezy.AntigravityFirewall
import com.freezy.publicapp.R
import com.freezy.ui.CyberBubbleView
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class BubbleService : Service() {

    private var licenseCheckFailCount = 0
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var cyberBubble: CyberBubbleView
    private lateinit var bubbleFaceOverlay: View

    private val handler = Handler(Looper.getMainLooper())

    private var isFreezing = false
    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var fillAnimator: ValueAnimator? = null
    private var targetPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        LagController.initLicencia(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        setupBubble()
        startLicenseCheck()

        getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_bubble_running", true)
            .apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val incomingPackage = intent.getStringExtra("target_package")
            val targetChanged = !incomingPackage.isNullOrEmpty() && incomingPackage != targetPackage
            if (!incomingPackage.isNullOrEmpty()) targetPackage = incomingPackage

            if (intent.action == "com.freezy.RECREATE_BUBBLES") {
                recreateBubbles()
                return START_STICKY
            }
            if (intent.action == "APPLY_BUBBLE_SIZE") {
                updateBubbleSize()
                return START_STICKY
            }
            if (intent.action == "UPDATE_BUBBLE_MODE") {
                val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
                val useRoot = prefs.getBoolean("use_root", false)
                if (::cyberBubble.isInitialized) {
                    cyberBubble.setMode(useRoot)
                }
                if (useRoot && AntigravityFirewall.isTunnelRunning) {
                    maybeStopSharedVpn()
                }
                actualizarUI()
                return START_STICKY
            }
            if (targetChanged && ::bubbleView.isInitialized) {
                recreateBubbles()
                return START_STICKY
            }
        }

        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)
        if (::cyberBubble.isInitialized) {
            cyberBubble.setMode(useRoot)
            cyberBubble.setActiveState(isFreezing)
        }
        actualizarUI()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "freezy_public_bubble"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Burbuja Freezy",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Freezy Activo")
            .setContentText("Toca la burbuja en pantalla para activar Fake Lag")
            .setSmallIcon(R.drawable.ic_notification_public)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notif)
            }
        } catch (_: Exception) {
            startForeground(1, notif)
        }
    }

    private fun setupBubble() {
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)

        bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_layout, null)
        cyberBubble = bubbleView.findViewById(R.id.cyber_bubble_view)
        bubbleFaceOverlay = bubbleView.findViewById(R.id.bubble_face_overlay)

        cyberBubble.setMode(useRoot)
        cyberBubble.setActiveState(isFreezing)

        val density = resources.displayMetrics.density
        val sizePercent = prefs.getInt("bubble_size", 20).coerceIn(0, 100)
        val sizePx = ((50 + sizePercent) * density).toInt()

        bubbleFaceOverlay.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubble_x", 100)
            y = prefs.getInt("bubble_y", 200)
        }

        windowManager.addView(bubbleView, params)
        setupTouchListener()
        actualizarUI()
    }

    private fun updateBubbleSize() {
        if (::windowManager.isInitialized && ::bubbleView.isInitialized && bubbleView.parent != null) {
            val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
            val density = resources.displayMetrics.density
            val sizePercent = prefs.getInt("bubble_size", 20).coerceIn(0, 100)
            val sizePx = ((50 + sizePercent) * density).toInt()

            if (::bubbleFaceOverlay.isInitialized) {
                bubbleFaceOverlay.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            }
            params.width = sizePx
            params.height = sizePx
            try {
                windowManager.updateViewLayout(bubbleView, params)
            } catch (_: Exception) {
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
            } catch (_: Exception) {}
            setupBubble()
        }
    }

    private fun actualizarUI() {
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)
        val isActive = if (useRoot) LagController.fakeLagActivo else isFreezing

        if (::cyberBubble.isInitialized) {
            cyberBubble.setMode(useRoot)
            cyberBubble.setActiveState(isActive)
        }
    }

    private fun setupTouchListener() {
        if (::bubbleFaceOverlay.isInitialized) {
            bubbleFaceOverlay.setOnTouchListener { _, event ->
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
                            bubbleFaceOverlay.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onBubbleTapped()
                        } else {
                            getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
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
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)
        val mode = prefs.getInt("mode", 1)

        // Modo Manual: el tap siempre hace toggle (ON/OFF)
        if (mode == 2) {
            toggleManual(useRoot)
            return
        }

        // Modos Auto y Personalizado: ignorar taps mientras ya está activo
        if (isFreezing) return

        val customSeconds = if (mode == 0) 1.0f else prefs.getFloat("custom_time_float", 1.0f).coerceIn(0.5f, 3.0f)
        val duration = (customSeconds * 1000).toLong()
        isFreezing = true
        startFreeze(useRoot, duration)
        startArcAnimation(duration)
        handler.postDelayed({ if (isFreezing) stopFreeze(useRoot) }, duration)
    }

    private fun toggleManual(useRoot: Boolean) {
        if (isFreezing) {
            stopFreeze(useRoot)
        } else {
            isFreezing = true
            startFreeze(useRoot, 60000L)
            if (::cyberBubble.isInitialized) {
                cyberBubble.setActiveState(true)
                cyberBubble.setProgress(1f)
            }
        }
    }

    private fun startFreeze(useRoot: Boolean, durationMs: Long = 3000L) {
        playSelectedTone()
        if (useRoot) {
            LagController.toggleFakeLag(true, true)
        } else {
            try {
                if (!AntigravityFirewall.isTunnelRunning) {
                    val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
                    val pkg = targetPackage ?: if (prefs.getBoolean("use_ff_max", false)) N.a(N.PKG_FF_MAX) else N.a(N.PKG_FF_NORMAL)
                    val vpnIntent = Intent(this, AntigravityFirewall::class.java).apply {
                        putExtra("target_package", pkg)
                    }
                    startService(vpnIntent)
                }
                LagController.toggleFakeLag(true, false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        actualizarUI()
    }

    private fun stopFreeze(useRoot: Boolean) {
        playSelectedTone()
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
                maybeStopSharedVpn()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        actualizarUI()
    }

    private fun maybeStopSharedVpn() {
        if (!isFreezing && !LagController.fakeLagActivo && AntigravityFirewall.isTunnelRunning) {
            try {
                startService(Intent(this, AntigravityFirewall::class.java).apply {
                    action = AntigravityFirewall.ACTION_STOP
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun playSelectedTone() {
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        ToneManager.play(this, prefs.getInt("tone_type", 0))
    }

    private fun startArcAnimation(duration: Long) {
        if (::cyberBubble.isInitialized) {
            cyberBubble.setActiveState(true)
            cyberBubble.setProgress(0f)
        }

        fillAnimator?.cancel()
        fillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener {
                val p = it.animatedValue as Float
                if (::cyberBubble.isInitialized) {
                    cyberBubble.setProgress(p)
                }
            }
            addListener(object : android.animation.Animator.AnimatorListener {
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
            })
            start()
        }
    }

    private val licenseCheckRunnable = object : Runnable {
        override fun run() {
            if (isAppOrGameInForeground() && isNetworkConnected()) {
                checkLicense()
            }
            handler.postDelayed(this, 5 * 60 * 1000) // Cada 5 minutos
        }
    }

    private fun startLicenseCheck() {
        handler.post(licenseCheckRunnable)
    }

    private fun checkLicense() {
        val username = SecurePrefs.get(this, "saved_username")
        val key = SecurePrefs.get(this, "saved_key")
        if (username.isEmpty() || key.isEmpty()) return
        val endpointUrl = SecurePrefs.get(this, "secure_endpoint").ifEmpty {
            N.a(N.ENDPOINT)
        }

        Thread {
            try {
                val challengeEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl.replace("/verify", "/challenge") else "$endpointUrl/challenge"
                val verifyEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl else "$endpointUrl/verify"
                val hwid = N.getHwid(this)
                val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"

                val challengeConn = WebSecurity.open(challengeEndpoint)
                challengeConn.requestMethod = "POST"
                challengeConn.setRequestProperty("Content-Type", "application/json")
                challengeConn.connectTimeout = 10000
                challengeConn.readTimeout = 10000
                challengeConn.doOutput = true

                val currentAppVersion = BuildConfig.VERSION_NAME.substringBefore("-")
                val challengeJson = JSONObject().apply {
                    put("key", key)
                    put("hwid", hwid)
                    put("username", username)
                    put("device_model", deviceModel)
                    put("app_version", currentAppVersion)
                }
                challengeConn.outputStream.use { it.write(challengeJson.toString().toByteArray(Charsets.UTF_8)) }

                if (challengeConn.responseCode != 200) {
                    licenseCheckFailCount++
                    if (licenseCheckFailCount >= 3) {
                        handleLicenseExpired("Error de conexión al verificar licencia")
                    }
                    return@Thread
                }

                val nonce = JSONObject(challengeConn.inputStream.bufferedReader().readText()).getString("nonce")
                val secretKey = N.a(N.HMAC_SECRET)
                val mac = Mac.getInstance("HmacSHA256").apply {
                    init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                }
                val hmacHex = mac.doFinal(nonce.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

                val verifyConn = WebSecurity.open(verifyEndpoint)
                verifyConn.requestMethod = "POST"
                verifyConn.setRequestProperty("Content-Type", "application/json")
                verifyConn.connectTimeout = 10000
                verifyConn.readTimeout = 10000
                verifyConn.doOutput = true

                val verifyJson = JSONObject().apply {
                    put("key", key)
                    put("hwid", hwid)
                    put("hmac", hmacHex)
                    put("app_version", currentAppVersion)
                }
                verifyConn.outputStream.use { it.write(verifyJson.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = verifyConn.responseCode
                if (responseCode == 200) {
                    val responseBody = verifyConn.inputStream.bufferedReader().readText()
                    val jsonObject = JSONObject(responseBody)
                    val isValid = jsonObject.optBoolean("valid", false)
                    if (!isValid) {
                        val message = jsonObject.optString("message", "Licencia expirada o inválida")
                        handleLicenseExpired(message)
                    } else {
                        licenseCheckFailCount = 0
                    }
                } else {
                    val errorBody = verifyConn.errorStream?.bufferedReader()?.readText() ?: ""
                    val serverMessage = try {
                        JSONObject(errorBody).optString("message", "Licencia expirada o inválida")
                    } catch (_: Exception) {
                        "Error de validación: $responseCode"
                    }
                    handleLicenseExpired(serverMessage)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                licenseCheckFailCount++
                if (licenseCheckFailCount >= 3) {
                    handleLicenseExpired("Error de conexión con el servidor de licencias")
                }
            }
        }.start()
    }

    private fun handleLicenseExpired(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            SecurePrefs.put(this, "saved_key", "")
            SecurePrefs.put(this, "saved_username", "")
            stopSelf()

            if (isAppOrGameInForeground()) {
                val intent = Intent(this, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
            }
        }
    }

    private fun isAppOrGameInForeground(): Boolean {
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val targetPkg = targetPackage ?: if (prefs.getBoolean("use_ff_max", false)) N.a(N.PKG_FF_MAX) else N.a(N.PKG_FF_NORMAL)

        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val time = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 20000, time)
                if (!stats.isNullOrEmpty()) {
                    var recentActive: UsageStats? = null
                    for (usage in stats) {
                        if (recentActive == null || usage.lastTimeUsed > recentActive.lastTimeUsed) {
                            recentActive = usage
                        }
                    }
                    if (recentActive != null) {
                        val pkgName = recentActive.packageName
                        if (pkgName == packageName || pkgName == targetPkg || pkgName == N.a(N.PKG_FF_MAX) || pkgName == N.a(N.PKG_FF_NORMAL)) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val appProcesses = (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.runningAppProcesses
            if (appProcesses != null) {
                for (appProcess in appProcesses) {
                    if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        if (appProcess.processName == packageName) return true
                    }
                }
            }
        } catch (_: Exception) {}

        return true
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = cm.activeNetwork ?: return false
            val actNw = cm.getNetworkCapabilities(nw) ?: return false
            return actNw.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            return cm.activeNetworkInfo?.isConnected == true
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(licenseCheckRunnable)
        isFreezing = false
        fillAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)

        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)
        if (useRoot) {
            try { LagController.desactivarFakeLagRoot() } catch (_: Exception) {}
        } else {
            try { LagController.toggleFakeLag(false, false) } catch (_: Exception) {}
        }
        LagController.fakeLagActivo = false

        try {
            startService(Intent(this, AntigravityFirewall::class.java).apply {
                action = AntigravityFirewall.ACTION_STOP
            })
            stopService(Intent(this, AntigravityFirewall::class.java))
        } catch (_: Exception) {}

        if (::windowManager.isInitialized && ::bubbleView.isInitialized && bubbleView.parent != null) {
            try { windowManager.removeView(bubbleView) } catch (_: Exception) {}
        }

        prefs.edit().putBoolean("is_bubble_running", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
