package com.freezy

import com.system.network.ui.R

import com.freezy.network.FovOverlay
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.Switch
import android.widget.Button
import android.widget.TextView
import android.widget.SeekBar
import android.animation.ValueAnimator
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class BubbleService : Service() {

    private var licenseCheckFailCount = 0
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubbleIcon: ImageView
    private lateinit var btnFakeLag: ImageView
    private lateinit var arcOverlay: ArcProgressView
    private lateinit var caraFakeLag: View
    private lateinit var recoilMenu: LinearLayout
    private var isMenuExpanded = false
    private val longClickRunnable = Runnable { 
        isLongClickTriggered = true
        expandBubbleMenu() 
    }
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
    private var isLongClickTriggered = false

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
            return if (isRootMode) Color.parseColor("#FF5900") else Color.parseColor("#00FF9D")
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        }
        
        // Actualizar el color de la burbuja por si el usuario cambió el modo Root sin matar el servicio
        if (this::bubbleIcon.isInitialized) {
            val isRootMode = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).getBoolean("use_root", false)
            if (isRootMode) {
                bubbleIcon.setColorFilter(Color.parseColor("#FF5900"), android.graphics.PorterDuff.Mode.SRC_IN)
            } else {
                bubbleIcon.setColorFilter(Color.parseColor("#00FF9D"), android.graphics.PorterDuff.Mode.SRC_IN)
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

        // Otorgar permisos SU para lectura de /dev/input/event* SOLO si el usuario activó el modo root
        if (useRoot) {
            Thread {
                executeRootCommand("chmod 666 /dev/input/event*")
                executeRootCommand("chcon u:object_r:input_device:s0 /dev/input/event*")
                executeRootCommand("setenforce 0")
            }.start()
        }
        

        setupBubble()
        
        startLicenseCheck()
        
        getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_bubble_running", true).apply()
    }

    private val licenseCheckRunnable = object : Runnable {
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
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val username = prefs.getString("saved_username", "") ?: return
        val key = prefs.getString("saved_key", "") ?: return
        val endpointUrl = prefs.getString("secure_endpoint", "") ?: return
        
        if (endpointUrl.isEmpty()) return
        
        Thread {
            try {
                val challengeEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl.replace("/verify", "/challenge") else "$endpointUrl/challenge"
                val verifyEndpoint = if (endpointUrl.endsWith("/verify")) endpointUrl else "$endpointUrl/verify"
                val hwid = NativeBridge.getHWID(this@BubbleService)
                val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

                val challengeConn = URL(challengeEndpoint).openConnection() as HttpURLConnection
                challengeConn.requestMethod = "POST"
                challengeConn.setRequestProperty("Content-Type", "application/json")
                challengeConn.connectTimeout = 10000
                challengeConn.readTimeout = 10000
                challengeConn.doOutput = true

                val currentAppVersion = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "1.08" }
                val challengeJson = "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"username\": \"$username\", \"device_model\": \"$deviceModel\", \"app_version\": \"$currentAppVersion\"}"
                challengeConn.outputStream.write(challengeJson.toByteArray(Charsets.UTF_8))

                if (challengeConn.responseCode != 200) {
                    licenseCheckFailCount++
                    if (licenseCheckFailCount >= 3) {
                        handleLicenseExpired(NativeBridge.getNativeString(NativeBridge.STRING_CONN_ERROR))
                    }
                    return@Thread
                }

                val nonce = JSONObject(challengeConn.inputStream.bufferedReader().readText()).getString("nonce")

                val HWID_PRIVADO = NativeBridge.getHmacSecret()
                val algorithm = "HmacSHA256"
                val mac = javax.crypto.Mac.getInstance(algorithm)
                mac.init(javax.crypto.spec.SecretKeySpec(HWID_PRIVADO.toByteArray(Charsets.UTF_8), algorithm))
                val hmacHex = mac.doFinal(nonce.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

                val verifyConn = URL(verifyEndpoint).openConnection() as HttpURLConnection
                verifyConn.requestMethod = "POST"
                verifyConn.setRequestProperty("Content-Type", "application/json")
                verifyConn.connectTimeout = 10000
                verifyConn.readTimeout = 10000
                verifyConn.doOutput = true

                val verifyJson = "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"hmac\": \"$hmacHex\", \"app_version\": \"$currentAppVersion\"}"
                verifyConn.outputStream.write(verifyJson.toByteArray(Charsets.UTF_8))

                val responseCode = verifyConn.responseCode
                if (responseCode == 200) {
                    val responseBody = verifyConn.inputStream.bufferedReader().readText()
                    val jsonObject = JSONObject(responseBody)
                    val isValid = jsonObject.getBoolean("valid")

                    if (!isValid) {
                        val message = jsonObject.optString("message", NativeBridge.getNativeString(NativeBridge.STRING_LICENSE_EXPIRED))
                        handleLicenseExpired(message)
                    } else {
                        licenseCheckFailCount = 0
                        val warning = jsonObject.optString("update_warning", "")
                        if (warning.isNotEmpty()) {
                            handler.post {
                                Toast.makeText(this@BubbleService, warning, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    val errorBody = verifyConn.errorStream?.bufferedReader()?.readText() ?: ""
                    val serverMessage = try {
                        JSONObject(errorBody).optString("message", NativeBridge.getNativeString(NativeBridge.STRING_LICENSE_EXPIRED))
                    } catch (e: Exception) {
                        "Error: $responseCode"
                    }
                    handleLicenseExpired(serverMessage)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                licenseCheckFailCount++
                if (licenseCheckFailCount >= 3) {
                    handleLicenseExpired(NativeBridge.getNativeString(NativeBridge.STRING_CONN_ERROR))
                }
            }
        }.start()
    }

    private fun handleLicenseExpired(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            
            val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("is_logged_in", false)
                .apply()
                
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
            val ch = NotificationChannel(channelId, NativeBridge.getNativeString(NativeBridge.STRING_BUBBLE_NOTIF_TITLE), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle(NativeBridge.getNativeString(NativeBridge.STRING_BUBBLE_NOTIF_TITLE))
            .setContentText(NativeBridge.getNativeString(NativeBridge.STRING_BUBBLE_NOTIF_TEXT))
            .setSmallIcon(android.R.drawable.ic_secure)
            .build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34)
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notif)
        } catch (e: Exception) { startForeground(1, notif) }
    }

    private fun setupFov() {
        fovOverlay = FovOverlay(this)
        
        fovParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun setupBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)

        bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_layout, null)
        btnFakeLag = bubbleView.findViewById(R.id.btn_fake_lag)
        bubbleIcon = btnFakeLag // Bind directly to the visible button so play/pause state is shown correctly
        caraFakeLag = bubbleView.findViewById(R.id.cara_fake_lag)
        recoilMenu = bubbleView.findViewById(R.id.recoil_menu)

        btnFakeLag.isClickable = false
        btnFakeLag.isFocusable = false

        // Aplicar tinte distintivo al ícono de Play según el modo
        if (useRoot) {
            bubbleIcon.setColorFilter(Color.parseColor("#FF5900"), android.graphics.PorterDuff.Mode.SRC_IN)
        } else {
            bubbleIcon.setColorFilter(Color.parseColor("#00FF9D"), android.graphics.PorterDuff.Mode.SRC_IN)
        }

        // Agregar la vista de arco circular programáticamente encima del ícono (en bubbleView)
        arcOverlay = ArcProgressView(this, useRoot)
        arcOverlay.visibility = View.GONE
        val size = (59 * resources.displayMetrics.density).toInt()
        (bubbleView as ViewGroup).addView(
            arcOverlay,
            FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        )

        // Set obfuscated strings for the bubble menu
        bubbleView.findViewById<android.widget.TextView>(R.id.tv_bubble_title)?.text = NativeBridge.getNativeString(NativeBridge.STRING_BUBBLE_TITLE)

        // Configurar clics del menú
        val btnBackToLag = bubbleView.findViewById<ImageButton>(R.id.btn_back_to_lag)
        btnBackToLag.setOnClickListener { returnToFakeLag() }

        // Hacer el menú arrastrable usando el header
        val menuHeader = bubbleView.findViewById<LinearLayout>(R.id.menu_header)
        menuHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).edit()
                        .putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply()
                    true
                }
                else -> false
            }
        }

        // Configurar SeekBar y Switch
        val recoilSeekbar = bubbleView.findViewById<SeekBar>(R.id.recoil_seekbar)
        val recoilPercentage = bubbleView.findViewById<TextView>(R.id.recoil_percentage)
        val recoilSwitch = bubbleView.findViewById<Switch>(R.id.recoil_switch)

        recoilPercentage.text = "${NativeBridge.getNativeString(NativeBridge.STRING_EFFECTIVENESS)}50%"
        recoilSwitch.text = NativeBridge.getNativeString(NativeBridge.STRING_RECOIL_EXTERNAL)

        recoilSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val intent = Intent(this, com.freezy.network.RecoilService::class.java).apply {
                    action = "START_RECOIL"
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                
                if (inputMonitor == null) {
                    inputMonitor = com.freezy.network.InputMonitor(this)
                }
                inputMonitor?.startMonitoring()
            } else {
                val intent = Intent(this, com.freezy.network.RecoilService::class.java).apply {
                    action = "STOP_RECOIL"
                }
                startService(intent)
                inputMonitor?.stopMonitoring()
                Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_RECOIL_OFF), Toast.LENGTH_SHORT).show()
            }
        }
        
        recoilSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                recoilPercentage.text = "${NativeBridge.getNativeString(NativeBridge.STRING_EFFECTIVENESS)}$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                val baseStrength = (progress * 0.5).toInt()
                val maxStrength = (progress * 1.5).toInt()
                
                val intent = Intent(this@BubbleService, com.freezy.network.RecoilService::class.java).apply {
                    action = "SET_PROFILE"
                    putExtra("base", baseStrength)
                    putExtra("inc", 1.2f)
                    putExtra("max", maxStrength)
                }
                startService(intent)
            }
        })

        val fovSwitch = bubbleView.findViewById<Switch>(R.id.fov_switch)
        val fovSeekBar = bubbleView.findViewById<SeekBar>(R.id.fov_seekbar)
        val fovText = bubbleView.findViewById<TextView>(R.id.fov_size_text)

        fovSwitch.text = NativeBridge.getNativeString(NativeBridge.STRING_FOV_EXTERNAL)
        fovText.text = "${NativeBridge.getNativeString(NativeBridge.STRING_FOV_RADIUS)}0px"

        fovSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                windowManager.addView(fovOverlay, fovParams)
                NativeBridge.setFovEnabled(true)
            } else {
                windowManager.removeView(fovOverlay)
                NativeBridge.setFovEnabled(false)
            }
        }

        fovSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                fovText.text = "${NativeBridge.getNativeString(NativeBridge.STRING_FOV_RADIUS)}${progress}px"
                
                if (progress > 0 && fovSwitch.isChecked) {
                    if (fovOverlay.parent == null) {
                        windowManager.addView(fovOverlay, fovParams)
                    }
                    fovOverlay.updateRadius(progress)
                    NativeBridge.setFovEnabled(true)
                    NativeBridge.setFovRadius(progress)
                } else if (progress == 0) {
                    if (fovOverlay.parent != null) {
                        windowManager.removeView(fovOverlay)
                    }
                    NativeBridge.setFovEnabled(false)
                }
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        // Configuración de controles de red Jitter & Packet Drop (Fase 5 y 6)
        val jitterSeekBar = bubbleView.findViewById<SeekBar>(R.id.jitter_seekbar)
        val jitterText = bubbleView.findViewById<TextView>(R.id.tv_jitter_label)
        val dropSeekBar = bubbleView.findViewById<SeekBar>(R.id.drop_seekbar)
        val dropText = bubbleView.findViewById<TextView>(R.id.tv_drop_label)

        val qosTitleText = bubbleView.findViewById<TextView>(R.id.tv_qos_title)
        qosTitleText?.text = NativeBridge.getNativeString(NativeBridge.STRING_QOS_TITLE)

        val labelJitter = NativeBridge.getNativeString(NativeBridge.STRING_JITTER_LABEL)
        val labelDrop = NativeBridge.getNativeString(NativeBridge.STRING_DROP_LABEL)

        val initialJitter = prefs.getInt("jitter_ms", 0)
        val initialDrop = prefs.getInt("drop_probability", 10)

        jitterSeekBar.progress = initialJitter
        jitterText.text = "$labelJitter: ${initialJitter}ms"
        NativeBridge.setNativeJitterMs(initialJitter)

        dropSeekBar.progress = initialDrop
        dropText.text = "$labelDrop: ${initialDrop}%"
        NativeBridge.setNativeDropProbability(initialDrop)

        jitterSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                jitterText.text = "$labelJitter: ${progress}ms"
                if (fromUser) {
                    prefs.edit().putInt("jitter_ms", progress).apply()
                    NativeBridge.setNativeJitterMs(progress)
                    actualizarUI()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        dropSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                dropText.text = "$labelDrop: ${progress}%"
                if (fromUser) {
                    prefs.edit().putInt("drop_probability", progress).apply()
                    NativeBridge.setNativeDropProbability(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
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

    private fun actualizarUI() {
        val useRoot = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).getBoolean("use_root", false)
        
        // 1. Actualizar Fake Lag UI (incluyendo modo Root y No-Root)
        if (::btnFakeLag.isInitialized && ::caraFakeLag.isInitialized) {
            val isActive = if (useRoot) LagController.fakeLagActivo else isFreezing
            val colorStr = if (useRoot) "#FF5900" else "#00FF9D"
            
            // Tintar icono
            btnFakeLag.setColorFilter(Color.parseColor(colorStr), android.graphics.PorterDuff.Mode.SRC_IN)
            if (isActive) {
                btnFakeLag.alpha = 1.0f
            } else {
                btnFakeLag.alpha = if (useRoot) 0.6f else 1.0f
            }
            
            // Tintar fondo de cristal para feedback premium (Naranja para Root, Verde para No-Root)
            val bgFakeLag = caraFakeLag.background.mutate() as? android.graphics.drawable.GradientDrawable
            if (bgFakeLag != null) {
                if (isActive) {
                    val bgStyleColor = if (useRoot) "#33FF5900" else "#3300FF9D"
                    bgFakeLag.setColor(Color.parseColor(bgStyleColor))
                    bgFakeLag.setStroke((2.5f * resources.displayMetrics.density).toInt(), Color.parseColor(colorStr))
                } else {
                    bgFakeLag.setColor(Color.parseColor("#E614161B"))
                    bgFakeLag.setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.parseColor("#222630"))
                }
            }
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
                        isLongClickTriggered = false
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        if (getSharedPreferences("FreezyPrefs", android.content.Context.MODE_PRIVATE).getBoolean("use_root", false)) {
                            handler.postDelayed(longClickRunnable, 1000)
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 8 || abs(dy) > 8) {
                            isDragging = true
                            handler.removeCallbacks(longClickRunnable)
                        }
                        if (isDragging) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(bubbleView, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longClickRunnable)
                        if (!isDragging && !isLongClickTriggered) {
                            // Retroalimentación háptica premium al tacto
                            caraFakeLag.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            onBubbleTapped()
                        } else if (isDragging) {
                            getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).edit()
                                .putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun expandBubbleMenu() {
        isMenuExpanded = true
        recoilMenu.visibility = View.VISIBLE
        caraFakeLag.visibility = View.GONE
        
        // El tamaño de la ventana se adapta al menú (wrap_content)
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        windowManager.updateViewLayout(bubbleView, params)
    }

    private fun returnToFakeLag() {
        recoilMenu.visibility = View.GONE
        caraFakeLag.visibility = View.VISIBLE
        isMenuExpanded = false

        // Volver al tamaño de la burbuja (59dp)
        val density = resources.displayMetrics.density
        val size59dp = (59 * density).toInt()
        params.width = size59dp
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        windowManager.updateViewLayout(bubbleView, params)

        // Recuperar animación de llenado si estaba activa
        if (isFreezing) {
            arcOverlay.visibility = View.VISIBLE
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

        val duration = when (mode) {
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
            bubbleIcon.setImageResource(R.drawable.ic_pause_white)
            // En modo manual: mostrar arco fijo al 100%
            arcOverlay.progress = 1f
            arcOverlay.visibility = View.VISIBLE
            arcOverlay.invalidate()
        }
    }

    private fun executeRootCommand(command: String) {
        if (suProcess == null) {
            try {
                suProcess = Runtime.getRuntime().exec("su")
                suOutputStream = java.io.DataOutputStream(suProcess!!.outputStream)
            } catch (e: Exception) {
                e.printStackTrace()
                handler.post { Toast.makeText(this, NativeBridge.getNativeString(NativeBridge.STRING_ROOT_ERROR), Toast.LENGTH_SHORT).show() }
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
        Logger.log(this, NativeBridge.getNativeString(NativeBridge.STRING_FAKE_LAG_ACTIVE) + " (Root: $useRoot)")
        
        if (useRoot) {
            LagController.toggleFakeLag(true, true)
        } else {
            try {
                // Iniciar la VPN dinámicamente
                val vpnIntent = Intent(this, AntigravityFirewall::class.java).apply {
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
        
        if (::arcOverlay.isInitialized) {
            fillAnimator?.cancel()
            arcOverlay.visibility = View.GONE
            arcOverlay.progress = 0f
        }
        if (::bubbleIcon.isInitialized) {
            bubbleIcon.visibility = View.VISIBLE
            bubbleIcon.alpha = 1f
            bubbleIcon.setImageResource(R.drawable.ic_play_white)
        }
        if (useRoot) {
            LagController.toggleFakeLag(false, true)
        } else {
            try {
                LagController.toggleFakeLag(false, false)
                
                // Detener la VPN de inmediato
                val vpnIntent = Intent(this, AntigravityFirewall::class.java).apply {
                    action = "STOP_VPN"
                }
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
            mediaPlayer?.setOnCompletionListener { mp ->
                mp.release()
            }
            mediaPlayer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }



    private fun startArcAnimation(duration: Long) {
        arcOverlay.progress = 0f
        arcOverlay.visibility = View.VISIBLE
        bubbleIcon.visibility = View.GONE // Ocultar icono para evitar clics y que no tape la barra

        fillAnimator?.cancel()
        fillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener {
                arcOverlay.progress = it.animatedValue as Float
                arcOverlay.invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) {
                    arcOverlay.visibility = View.GONE
                    bubbleIcon.visibility = View.VISIBLE // Volver a mostrar
                }
                override fun onAnimationCancel(a: android.animation.Animator) {
                    arcOverlay.visibility = View.GONE
                    bubbleIcon.visibility = View.VISIBLE // Volver a mostrar
                }
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
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
        
        if (isFreezing) stopFreeze(getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).getBoolean("use_root", false))
        fillAnimator?.cancel()
        
        // Detener No-Recoil y Monitoreo de Entrada
        try {
            val recoilIntent = Intent(this, com.freezy.network.RecoilService::class.java).apply {
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

        getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_bubble_running", false).apply()
    }



    private fun isAppOrGameInForeground(): Boolean {
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val targetPkg = prefs.getString("TARGET_PACKAGE", "com.dts.freefiremax") ?: "com.dts.freefiremax"
        
        // 1. Usar UsageStatsManager (si está permitido)
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            if (usm != null) {
                val time = System.currentTimeMillis()
                // Consultamos las estadísticas de los últimos 20 segundos
                val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 1000 * 20, time)
                if (!stats.isNullOrEmpty()) {
                    var recentActiveUsage: android.app.usage.UsageStats? = null
                    for (usage in stats) {
                        if (recentActiveUsage == null || usage.lastTimeUsed > recentActiveUsage.lastTimeUsed) {
                            recentActiveUsage = usage
                        }
                    }
                    if (recentActiveUsage != null) {
                        val pkgName = recentActiveUsage.packageName
                        if (pkgName == packageName || pkgName == targetPkg || pkgName == "com.dts.freefiremax" || pkgName == "com.dts.freefireth") {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback: Revisar procesos en primer plano a través de ActivityManager (solo detectará nuestra app)
        try {
            val appProcesses = (getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.runningAppProcesses
            if (appProcesses != null) {
                for (appProcess in appProcesses) {
                    if (appProcess.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
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
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val nw = cm.activeNetwork ?: return false
                val actNw = cm.getNetworkCapabilities(nw) ?: return false
                return actNw.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                       actNw.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                       actNw.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ||
                       actNw.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            } else {
                @Suppress("DEPRECATION")
                val nwInfo = cm.activeNetworkInfo ?: return false
                @Suppress("DEPRECATION")
                return nwInfo.isConnected
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return true // Asumimos conectado para evitar crasheos catastróficos por políticas del OS
        }
    }

    override fun onBind(intent: Intent): IBinder? = null
}