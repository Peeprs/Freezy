package com.freezy.publicapp

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
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
import kotlin.math.abs

class BubbleService : Service() {
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val incomingPackage = intent.getStringExtra("target_package")
            if (!incomingPackage.isNullOrEmpty()) targetPackage = incomingPackage

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
                return START_STICKY
            }
        }

        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)
        if (::cyberBubble.isInitialized) {
            cyberBubble.setMode(useRoot)
            cyberBubble.setActiveState(isFreezing)
        }
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
    }

    private fun updateBubbleSize() {
        if (::windowManager.isInitialized && ::bubbleView.isInitialized && bubbleView.parent != null) {
            val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
            val density = resources.displayMetrics.density
            val sizePercent = prefs.getInt("bubble_size", 20).coerceIn(0, 100)
            val sizePx = ((50 + sizePercent) * density).toInt()
            bubbleFaceOverlay.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            params.width = sizePx
            params.height = sizePx
            try {
                windowManager.updateViewLayout(bubbleView, params)
            } catch (_: Exception) {}
        }
    }

    private fun setupTouchListener() {
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

    private fun onBubbleTapped() {
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)
        val mode = prefs.getInt("mode", 1)

        // Modo Manual: el tap siempre hace toggle (ON/OFF)
        if (mode == 2) {
            toggleManual(useRoot)
            return
        }

        // Modo Personalizado: temporizador con animación
        if (isFreezing) return

        val customSeconds = prefs.getFloat("custom_time_float", 1.0f).coerceIn(0.5f, 3.0f)
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
            cyberBubble.setActiveState(true)
            cyberBubble.setProgress(1f)
        }
    }

    private fun startFreeze(useRoot: Boolean, durationMs: Long) {
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
        cyberBubble.setMode(useRoot)
        cyberBubble.setActiveState(true)
    }

    private fun stopFreeze(useRoot: Boolean) {
        playSelectedTone()
        isFreezing = false
        fillAnimator?.cancel()
        cyberBubble.setProgress(0f)
        cyberBubble.setActiveState(false)

        if (useRoot) {
            LagController.toggleFakeLag(false, true)
        } else {
            try {
                LagController.toggleFakeLag(false, false)
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
        cyberBubble.setActiveState(true)
        cyberBubble.setProgress(0f)

        fillAnimator?.cancel()
        fillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener {
                val p = it.animatedValue as Float
                cyberBubble.setProgress(p)
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) {
                    cyberBubble.setProgress(0f)
                }
                override fun onAnimationCancel(a: android.animation.Animator) {
                    cyberBubble.setProgress(0f)
                }
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
            start()
        }
    }

    override fun onDestroy() {
        val prefs = getSharedPreferences(N.a(N.PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)
        stopFreeze(useRoot)
        fillAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (::windowManager.isInitialized && ::bubbleView.isInitialized && bubbleView.parent != null) {
            try { windowManager.removeView(bubbleView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
