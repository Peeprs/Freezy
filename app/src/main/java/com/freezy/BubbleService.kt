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
    private lateinit var btnFantasma: ImageView
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
    private lateinit var bubbleFantasmaView: View
    private lateinit var paramsFantasma: WindowManager.LayoutParams
    private lateinit var bubbleIconFantasma: ImageView
    private lateinit var caraFantasma: View
    private var initialXFantasma = 0
    private var initialYFantasma = 0

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

    // Variables para el mapeo del botón de disparo de Auto-Lag
    private var shootAreaLeft = 0
    private var shootAreaTop = 0
    private var shootAreaRight = 0
    private var shootAreaBottom = 0

    private var mappingHudPanel: View? = null
    private var mappingSeekBarView: View? = null
    private var circleTargetContainer: View? = null

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

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isRootMode) Color.parseColor("#D500F9") else Color.WHITE
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
            
            paint.color = if (isRootMode) Color.parseColor("#D500F9") else Color.WHITE
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
            try {
                if (::bubbleFantasmaView.isInitialized && bubbleFantasmaView.parent != null) {
                    windowManager.removeView(bubbleFantasmaView)
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
                bubbleIcon.setColorFilter(Color.parseColor("#D500F9"), android.graphics.PorterDuff.Mode.SRC_IN)
            } else {
                bubbleIcon.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
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
        
        // Variables eliminadas por duplicado
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

        if (!isPremiumLicense) {
            prefs.edit()
                .putBoolean("auto_lag_enabled", false)
                .putBoolean("modo_mapeo_activo", false)
                .commit()
        }

        val isAutoLagEnabled = prefs.getBoolean("auto_lag_enabled", false)
        if (!isAutoLagEnabled) {
            setupBubble() // Solo mostramos la burbuja normal en Fake Lag clásico!
        }
        
        startLicenseCheck()
        
        getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_bubble_running", true).apply()

        // Si el modo mapeo está activo, iniciamos la interfaz de arrastre
        if (prefs.getBoolean("modo_mapeo_activo", false)) {
            iniciarInterfazDeMapeo()
        } else if (isAutoLagEnabled && prefs.contains("shoot_left")) {
            mostrarCirculoDeMapeoBloqueado()
        }
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
                val hwid = NativeBridge.getNativeHWID()
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
        val rootModeType = prefs.getInt("root_mode_type", 0)

        val showFakeLag = !useRoot || rootModeType == 0
        val showFantasma = useRoot && rootModeType == 1

        if (showFakeLag) {
            bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_layout, null)
            bubbleIcon = bubbleView.findViewById(R.id.bubble_icon)
            btnFakeLag = bubbleView.findViewById(R.id.btn_fake_lag)
            caraFakeLag = bubbleView.findViewById(R.id.cara_fake_lag)
            recoilMenu = bubbleView.findViewById(R.id.recoil_menu)

            btnFakeLag.isClickable = false
            btnFakeLag.isFocusable = false

            // Aplicar tinte distintivo al ícono de Play según el modo
            if (useRoot) {
                bubbleIcon.setColorFilter(Color.parseColor("#D500F9"), android.graphics.PorterDuff.Mode.SRC_IN)
            } else {
                bubbleIcon.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
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
                        NativeBridge.setFovRadius(progress)
                        NativeBridge.setFovEnabled(true)
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

            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("bubble_x", 100)
                y = prefs.getInt("bubble_y", 200)
            }
            windowManager.addView(bubbleView, params)
        }

        if (showFantasma) {
            bubbleFantasmaView = LayoutInflater.from(this).inflate(R.layout.bubble_fantasma_layout, null)
            bubbleIconFantasma = bubbleFantasmaView.findViewById(R.id.bubble_icon_fantasma)
            btnFantasma = bubbleFantasmaView.findViewById(R.id.btn_fantasma)
            caraFantasma = bubbleFantasmaView.findViewById(R.id.cara_fantasma)

            btnFantasma.isClickable = false
            btnFantasma.isFocusable = false

            paramsFantasma = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("bubble_fantasma_x", 100)
                y = prefs.getInt("bubble_fantasma_y", 300)
            }
            windowManager.addView(bubbleFantasmaView, paramsFantasma)
        }

        setupTouchListener()
        actualizarUI()
    }

    private fun actualizarUI() {
        val useRoot = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).getBoolean("use_root", false)
        if (::btnFakeLag.isInitialized) {
            btnFakeLag.alpha = if (LagController.fakeLagActivo) 1.0f else 0.5f
            val colorStr = if (useRoot) "#D500F9" else "#FFFFFF"
            btnFakeLag.setColorFilter(Color.parseColor(colorStr), android.graphics.PorterDuff.Mode.SRC_IN)
        }

        if (::btnFantasma.isInitialized) {
            btnFantasma.alpha = if (LagController.fantasmaActivo) 1.0f else 0.5f
            val colorStr = if (useRoot) "#D500F9" else "#FFFFFF"
            btnFantasma.setColorFilter(Color.parseColor(colorStr), android.graphics.PorterDuff.Mode.SRC_IN)
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
                            val nuevoEstado = !LagController.fakeLagActivo
                            LagController.toggleFakeLag(nuevoEstado)
                            actualizarUI()
                            playSoundFromRes(if (nuevoEstado) R.raw.coin_on else R.raw.coin_off)
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

        if (::caraFantasma.isInitialized) {
            var isDraggingFantasma = false
            caraFantasma.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isDraggingFantasma = false
                        initialXFantasma = paramsFantasma.x; initialYFantasma = paramsFantasma.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 8 || abs(dy) > 8) {
                            isDraggingFantasma = true
                        }
                        if (isDraggingFantasma) {
                            paramsFantasma.x = initialXFantasma + dx.toInt()
                            paramsFantasma.y = initialYFantasma + dy.toInt()
                            windowManager.updateViewLayout(bubbleFantasmaView, paramsFantasma)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDraggingFantasma) {
                            val nuevoEstado = !LagController.fantasmaActivo
                            LagController.toggleFantasma(nuevoEstado)
                            actualizarUI()
                            playGhostSound()
                        } else {
                            getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).edit()
                                .putInt("bubble_fantasma_x", paramsFantasma.x).putInt("bubble_fantasma_y", paramsFantasma.y).apply()
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

        // Si el modo Auto-Lag está activo, disparamos el lag switch
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_lag_enabled", false)) {
            val useRoot = prefs.getBoolean("use_root", false)
            if (isFiring) {
                if (!isFreezing) {
                    isFreezing = true
                    startFreeze(useRoot)
                    if (::arcOverlay.isInitialized) {
                        startArcAnimation(1500L) // Límite de 1.5 segundos para evitar desconexiones
                    }
                    handler.postDelayed({
                        if (isFreezing) stopFreeze(useRoot)
                    }, 1500L)
                }
            } else {
                if (isFreezing) {
                    stopFreeze(useRoot)
                }
            }
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
        
        // Brillo visual de disparo: cambiar círculo a rojo translúcido si está visible
        if (circleTargetContainer != null) {
            circleTargetContainer?.post {
                circleTargetContainer?.findViewById<View>(R.id.circle_target)?.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF3B30"))
                )
            }
        }
        
        if (useRoot) {
            val rootModeType = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).getInt("root_mode_type", 0)
            Thread {
                if (rootModeType == 1) {
                    executeRootCommand("iptables -I OUTPUT -p udp --dport 7000:25000 -j DROP")
                    executeRootCommand("iptables -I OUTPUT -p udp --dport 7000:25000 -m length --length 80:1500 -j ACCEPT")
                    executeRootCommand("iptables -I OUTPUT -p udp --dport 7000:25000 -m length --length 0:80 -m limit --limit 3/sec --limit-burst 1 -j ACCEPT")
                } else {
                    executeRootCommand("iptables -I INPUT -p udp --sport 7000:25000 -j DROP")
                    executeRootCommand("iptables -I INPUT -p udp --sport 7000:25000 -m length --length 0:80 -m limit --limit 3/sec --limit-burst 1 -j ACCEPT")
                }
            }.start()
        } else {
            try {
                // Iniciar la VPN dinámicamente
                val vpnIntent = Intent(this, AntigravityFirewall::class.java).apply {
                    putExtra("TARGET_PACKAGE", targetPackage ?: "com.dts.freefiremax")
                }
                startService(vpnIntent)
                AntigravityFirewall.setLagActive(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopFreeze(useRoot: Boolean) {
        playSoundFromRes(R.raw.coin_off)
        Logger.log(this, NativeBridge.getNativeString(NativeBridge.STRING_FAKE_LAG_DEACTIVATED))
        isFreezing = false
        
        // Restaurar círculo a su diseño normal (quitar filtro rojo)
        if (circleTargetContainer != null) {
            circleTargetContainer?.post {
                circleTargetContainer?.findViewById<View>(R.id.circle_target)?.setBackgroundTintList(null)
            }
        }
        
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
            val rootModeType = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).getInt("root_mode_type", 0)
            Thread {
                if (rootModeType == 1) {
                    executeRootCommand("iptables -D OUTPUT -p udp --dport 7000:25000 -m length --length 0:80 -m limit --limit 3/sec --limit-burst 1 -j ACCEPT")
                    executeRootCommand("iptables -D OUTPUT -p udp --dport 7000:25000 -m length --length 80:1500 -j ACCEPT")
                    executeRootCommand("iptables -D OUTPUT -p udp --dport 7000:25000 -j DROP")
                } else {
                    executeRootCommand("iptables -D INPUT -p udp --sport 7000:25000 -m length --length 0:80 -m limit --limit 3/sec --limit-burst 1 -j ACCEPT")
                    executeRootCommand("iptables -D INPUT -p udp --sport 7000:25000 -j DROP")
                }
            }.start()
        } else {
            try {
                AntigravityFirewall.setLagActive(false)
                
                // Detener la VPN de inmediato
                val vpnIntent = Intent(this, AntigravityFirewall::class.java).apply {
                    action = "STOP_VPN"
                }
                startService(vpnIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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

    private fun playGhostSound() {
        Thread {
            try {
                val sampleRate = 44100
                val durationS = 0.32f
                val numSamples = (durationS * sampleRate).toInt()
                val sample = ShortArray(numSamples)
                
                // Generar un barrido de frecuencia spooky corto y táctico: onda senoidal con vibrato rápido
                for (i in 0 until numSamples) {
                    val t = i.toFloat() / sampleRate
                    // Envolvente de amplitud ultra-compacta para evitar chasquidos (fade-in de 40ms, fade-out de 60ms)
                    val env = if (t < 0.04f) {
                        t / 0.04f
                    } else if (t > durationS - 0.06f) {
                        (durationS - t) / 0.06f
                    } else {
                        1.0f
                    }
                    
                    // Frecuencia instantánea
                    val progress = t / durationS
                    val baseFreq = 350f + 350f * Math.sin(progress * Math.PI).toFloat()
                    val vibrato = 30f * Math.sin(2.0 * Math.PI * 12.0 * t).toFloat()
                    val freq = baseFreq + vibrato
                    
                    // Fase acumulada
                    val angle = 2.0 * Math.PI * freq * t
                    sample[i] = (Math.sin(angle) * Short.MAX_VALUE * env * 0.5f).toInt().toShort()
                }
                
                val audioTrack = android.media.AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    numSamples * 2,
                    android.media.AudioTrack.MODE_STATIC
                )
                audioTrack.write(sample, 0, numSamples)
                audioTrack.play()
                // Liberar después de reproducir
                Thread.sleep((durationS * 1000).toLong() + 100)
                try {
                    audioTrack.stop()
                } catch (e: Exception) {}
                audioTrack.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
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
        if (LagController.fantasmaActivo) {
            LagController.desactivarFantasmaRoot()
            LagController.fantasmaActivo = false
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
        if (::bubbleFantasmaView.isInitialized && bubbleFantasmaView.parent != null) {
            windowManager.removeView(bubbleFantasmaView)
        }
        if (::fovOverlay.isInitialized && fovOverlay.parent != null) {
            windowManager.removeView(fovOverlay)
        }
        
        try {
            if (mappingHudPanel != null && mappingHudPanel?.parent != null) {
                windowManager.removeView(mappingHudPanel)
            }
            if (mappingSeekBarView != null && mappingSeekBarView?.parent != null) {
                windowManager.removeView(mappingSeekBarView)
            }
            if (circleTargetContainer != null && circleTargetContainer?.parent != null) {
                windowManager.removeView(circleTargetContainer)
            }
        } catch (e: Exception) {}
        
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

    private fun iniciarInterfazDeMapeo() {
        if (!::windowManager.isInitialized) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        // Inflamos el XML completo una sola vez para extraer las tres vistas individuales
        val parentView = LayoutInflater.from(this).inflate(R.layout.layout_mapping_bubble, null) as ViewGroup
        
        val rawHudPanel = parentView.findViewById<View>(R.id.mapping_hud_panel)
        val rawSeekBarView = parentView.findViewById<View>(R.id.layout_seekbar_container)
        val rawCircleContainer = parentView.findViewById<View>(R.id.circle_target_container)

        // Desconectamos las vistas de su FrameLayout padre para poder añadirlas por separado a WindowManager
        parentView.removeView(rawHudPanel)
        parentView.removeView(rawSeekBarView)
        parentView.removeView(rawCircleContainer)

        mappingHudPanel = rawHudPanel
        mappingSeekBarView = rawSeekBarView
        circleTargetContainer = rawCircleContainer

        // 1. Configurar LayoutParams del HUD panel (Superior Central)
        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (36 * resources.displayMetrics.density).toInt() // Margen de arriba
        }

        // 2. Configurar LayoutParams de la barra de progreso lateral (Izquierda Centro)
        val seekParams = WindowManager.LayoutParams(
            (60 * resources.displayMetrics.density).toInt(),
            (240 * resources.displayMetrics.density).toInt(),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            x = (16 * resources.displayMetrics.density).toInt() // Margen de izquierda
        }

        // 3. Configurar LayoutParams del círculo de disparo (Centro arrastrable)
        val circleParams = WindowManager.LayoutParams(
            (80 * resources.displayMetrics.density).toInt(), // Ancho inicial
            (80 * resources.displayMetrics.density).toInt(), // Alto inicial
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val btnToggle = mappingHudPanel!!.findViewById<Button>(R.id.btn_toggle_mapeo)
        val btnAceptar = mappingHudPanel!!.findViewById<Button>(R.id.btn_aceptar_mapeo)
        val seekbarSize = mappingSeekBarView!!.findViewById<SeekBar>(R.id.seekbar_circle_size)

        var isShowing = false

        btnToggle.setOnClickListener {
            isShowing = !isShowing
            if (isShowing) {
                btnToggle.text = "Cerrar"
                btnAceptar.visibility = View.VISIBLE
                
                // Forzar visibilidad para contrarrestar el 'gone' original del XML
                mappingSeekBarView?.visibility = View.VISIBLE
                circleTargetContainer?.visibility = View.VISIBLE
                
                // Agregamos el SeekBar y el Círculo dinámicamente al WindowManager
                if (mappingSeekBarView?.parent == null) {
                    windowManager.addView(mappingSeekBarView, seekParams)
                }
                if (circleTargetContainer?.parent == null) {
                    windowManager.addView(circleTargetContainer, circleParams)
                }
            } else {
                btnToggle.text = "Mostrar"
                btnAceptar.visibility = View.GONE
                
                // Removemos el SeekBar y el Círculo del WindowManager
                if (mappingSeekBarView?.parent != null) {
                    windowManager.removeView(mappingSeekBarView)
                }
                if (circleTargetContainer?.parent != null) {
                    windowManager.removeView(circleTargetContainer)
                }
            }
        }

        seekbarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val density = resources.displayMetrics.density
                val progressPx = (progress * density).toInt()
                val newSize = Math.max((40 * density).toInt(), progressPx) // Mínimo 40dp en píxeles
                
                circleParams.width = newSize
                circleParams.height = newSize
                
                if (circleTargetContainer?.parent != null) {
                    windowManager.updateViewLayout(circleTargetContainer, circleParams)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f

        circleTargetContainer!!.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = circleParams.x
                    initY = circleParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    circleParams.x = initX + (event.rawX - touchX).toInt()
                    circleParams.y = initY + (event.rawY - touchY).toInt()
                    if (circleTargetContainer?.parent != null) {
                        windowManager.updateViewLayout(circleTargetContainer, circleParams)
                    }
                    true
                }
                else -> false
            }
        }

        btnAceptar.setOnClickListener {
            val location = IntArray(2)
            circleTargetContainer!!.getLocationOnScreen(location)
            
            shootAreaLeft = location[0]
            shootAreaTop = location[1]
            shootAreaRight = shootAreaLeft + circleParams.width
            shootAreaBottom = shootAreaTop + circleParams.height

            // Guardar en preferencias para recordarlo en futuros lanzamientos
            getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE).edit()
                .putInt("shoot_left", shootAreaLeft)
                .putInt("shoot_top", shootAreaTop)
                .putInt("shoot_right", shootAreaRight)
                .putInt("shoot_bottom", shootAreaBottom)
                .putBoolean("modo_mapeo_activo", false)
                .apply()

            // Si el InputMonitor no se ha creado, lo instanciamos y activamos
            if (inputMonitor == null) {
                inputMonitor = com.freezy.network.InputMonitor(this)
            }
            inputMonitor?.startMonitoring()
            inputMonitor?.updateFireZone(shootAreaLeft, shootAreaTop, shootAreaRight, shootAreaBottom)

            // Hacer el círculo completamente intangible al tacto y reposicionarlo de forma fija
            circleParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            circleParams.gravity = Gravity.TOP or Gravity.START
            circleParams.x = shootAreaLeft
            circleParams.y = shootAreaTop
            
            if (circleTargetContainer?.parent != null) {
                windowManager.updateViewLayout(circleTargetContainer, circleParams)
            }

            // Ocultar de inmediato las opciones de aumento, sliders y HUD de mostrar
            try {
                if (mappingHudPanel?.parent != null) windowManager.removeView(mappingHudPanel)
                if (mappingSeekBarView?.parent != null) windowManager.removeView(mappingSeekBarView)
            } catch (e: Exception) {}
            
            Toast.makeText(this, "Botón de disparo mapeado y guardado.", Toast.LENGTH_SHORT).show()
        }

        // Al inicio, solo agregamos el panel de control (que tiene el botón "Mostrar")
        windowManager.addView(mappingHudPanel, hudParams)
    }

    private fun mostrarCirculoDeMapeoBloqueado() {
        if (!::windowManager.isInitialized) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        val left = prefs.getInt("shoot_left", -1)
        val top = prefs.getInt("shoot_top", -1)
        val right = prefs.getInt("shoot_right", -1)
        val bottom = prefs.getInt("shoot_bottom", -1)
        
        val width = right - left
        val height = bottom - top
        if (left == -1 || top == -1 || width <= 0 || height <= 0) return

        val parentView = LayoutInflater.from(this).inflate(R.layout.layout_mapping_bubble, null) as ViewGroup
        val rawCircleContainer = parentView.findViewById<View>(R.id.circle_target_container)
        parentView.removeView(rawCircleContainer)
        
        circleTargetContainer = rawCircleContainer
        circleTargetContainer?.visibility = View.VISIBLE

        val circleParams = WindowManager.LayoutParams(
            width,
            height,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = left
            y = top
        }

        windowManager.addView(circleTargetContainer, circleParams)
        
        // Iniciamos y alimentamos el lector nativo InputMonitor
        if (inputMonitor == null) {
            inputMonitor = com.freezy.network.InputMonitor(this)
        }
        inputMonitor?.startMonitoring()
        inputMonitor?.updateFireZone(left, top, right, bottom)
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