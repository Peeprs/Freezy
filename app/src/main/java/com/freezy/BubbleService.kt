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
    private lateinit var arcOverlay: ArcProgressView
    private lateinit var caraFakeLag: FrameLayout
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

    // Variables para el mapeo del botón de disparo de Auto-Lag
    private var shootAreaLeft = 0
    private var shootAreaTop = 0
    private var shootAreaRight = 0
    private var shootAreaBottom = 0

    private var mappingHudPanel: View? = null
    private var mappingSeekBarView: View? = null
    private var circleTargetContainer: View? = null

    // Vista personalizada que dibuja el arco circular de progreso
    inner class ArcProgressView(context: Context) : View(context) {
        var progress = 0f // 0.0 a 1.0
        
        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                isForceDarkAllowed = false
            }
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 16f // Mucho más gruesa para que sea súper blanca
            strokeCap = Paint.Cap.ROUND
            alpha = 255 // Opacidad total
        }
        private val rect = RectF()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = (56 * resources.displayMetrics.density).toInt()
            setMeasuredDimension(size, size)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = paint.strokeWidth / 2f + 2f
            rect.set(pad, pad, width - pad, height - pad)
            
            // Dibujar arco de progreso (blanco puro)
            paint.color = Color.WHITE
            paint.alpha = 255
            canvas.drawArc(rect, -90f, 360f * progress, false, paint)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupFov()
        
        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
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
            checkLicense()
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

                val challengeJson = "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"username\": \"$username\", \"device_model\": \"$deviceModel\"}"
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

                val verifyJson = "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"hmac\": \"$hmacHex\"}"
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
            
            val intent = Intent(this, LoginActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
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
        bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_layout, null)
        bubbleIcon = bubbleView.findViewById(R.id.bubble_icon)
        caraFakeLag = bubbleView.findViewById(R.id.cara_fake_lag)
        recoilMenu = bubbleView.findViewById(R.id.recoil_menu)

        // Agregar la vista de arco circular programáticamente encima del ícono (en caraFakeLag)
        arcOverlay = ArcProgressView(this)
        arcOverlay.visibility = View.GONE
        val size = (56 * resources.displayMetrics.density).toInt()
        caraFakeLag.addView(
            arcOverlay,
            FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        )

        // Set obfuscated strings for the bubble menu
        bubbleView.findViewById<android.widget.TextView>(R.id.tv_bubble_title)?.text = NativeBridge.getNativeString(NativeBridge.STRING_BUBBLE_TITLE)

        // Configurar clics del menú
        val btnBackToLag = bubbleView.findViewById<ImageButton>(R.id.btn_back_to_lag)
        btnBackToLag.setOnClickListener { returnToFakeLag() }

        // 2. Hacer el menú arrastrable usando el header
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

        // 3. Configurar SeekBar y Switch
        val recoilSeekbar = bubbleView.findViewById<SeekBar>(R.id.recoil_seekbar)
        val recoilPercentage = bubbleView.findViewById<TextView>(R.id.recoil_percentage)
        val recoilSwitch = bubbleView.findViewById<Switch>(R.id.recoil_switch)

        recoilPercentage.text = "${NativeBridge.getNativeString(NativeBridge.STRING_EFFECTIVENESS)}50%"
        recoilSwitch.text = NativeBridge.getNativeString(NativeBridge.STRING_RECOIL_EXTERNAL)



        recoilSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Los permisos se otorgan dentro de RecoilService al iniciar

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
                // Calculamos los valores del perfil basados en el porcentaje (Aumentado para máxima fuerza)
                val baseStrength = (progress * 0.5).toInt() // 0 a 50
                val maxStrength = (progress * 1.5).toInt()  // 0 a 150
                
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

        // Registrar el callback de UI con C++ para recibir notificaciones de disparo
        NativeBridge.registerUiCallback(this)

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
                    // Si es la primera vez que sube de 0, mostramos el overlay
                    if (fovOverlay.parent == null) {
                        windowManager.addView(fovOverlay, fovParams)
                    }
                    fovOverlay.updateRadius(progress)
                    NativeBridge.setFovRadius(progress)
                    NativeBridge.setFovEnabled(true)
                } else if (progress == 0) {
                    // Si baja a 0, lo quitamos de la vista para limpieza
                    if (fovOverlay.parent != null) {
                        windowManager.removeView(fovOverlay)
                    }
                    NativeBridge.setFovEnabled(false)
                }
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
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
    }

    private fun setupTouchListener() {
        // Solo escuchamos toques en la burbuja (Face 1) para permitir clics en el menú (Face 2)
        caraFakeLag.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    isLongClickTriggered = false
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    handler.postDelayed(longClickRunnable, 1000) // 1 segundo para que no sea tan sensible
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

        // Volver al tamaño de la burbuja (56dp)
        val density = resources.displayMetrics.density
        val size56dp = (56 * density).toInt()
        params.width = size56dp
        params.height = size56dp
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
                    startArcAnimation(1500L) // Límite de 1.5 segundos para evitar desconexiones
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
        playSound(android.media.ToneGenerator.TONE_PROP_BEEP)
        Logger.log(this, NativeBridge.getNativeString(NativeBridge.STRING_FAKE_LAG_ACTIVE) + " (Root: $useRoot)")
        if (useRoot) {
            Thread {
                executeRootCommand("iptables -I INPUT 1 -p udp -j DROP")
            }.start()
        }
    }

    private fun stopFreeze(useRoot: Boolean) {
        playSound(android.media.ToneGenerator.TONE_PROP_BEEP2)
        Logger.log(this, NativeBridge.getNativeString(NativeBridge.STRING_FAKE_LAG_DEACTIVATED))
        isFreezing = false
        fillAnimator?.cancel()
        arcOverlay.visibility = View.GONE
        arcOverlay.progress = 0f
        bubbleIcon.visibility = View.VISIBLE
        bubbleIcon.alpha = 1f
        bubbleIcon.setImageResource(R.drawable.ic_play_white)
        if (useRoot) {
            Thread {
                executeRootCommand("iptables -D INPUT -p udp -j DROP")
            }.start()
        }
    }

    private fun playSound(type: Int) {
        try {
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 65)
            toneGen.startTone(type, 100)
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
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
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

    override fun onBind(intent: Intent): IBinder? = null
}