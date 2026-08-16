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
import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.system.network.ui.R
import kotlin.math.abs
import org.json.JSONObject
import java.io.File

class BubbleService : Service() {

    private var licenseCheckFailCount = 0
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubbleIcon: ImageView
    private lateinit var bubbleMainIcon: ImageView
    private lateinit var cyberBubble: com.freezy.ui.CyberBubbleView
    private lateinit var arcOverlay: ArcProgressView
    private lateinit var bubbleFaceOverlay: View

    private var suProcess: Process? = null
    private var suOutputStream: java.io.DataOutputStream? = null

    private val handler = Handler(Looper.getMainLooper())

    private var isFreezing = false
    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var fillAnimator: ValueAnimator? = null
    private var targetPackage: String? = null
    private lateinit var recoilMenu: LinearLayout
    private var isMenuExpanded = false
    private var isLongClickTriggered = false
    private var aimbotSwitchBusy = false
    private var sniperScopeSwitchBusy = false
    private var sniperSwitchBusy = false
    private var espOverlayView: EspOverlayView? = null
    private val longClickRunnable = Runnable {
        isLongClickTriggered = true
        expandBubbleMenu()
    }

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

    private fun updateBubbleSize() {
        if (::windowManager.isInitialized && ::bubbleView.isInitialized && bubbleView.parent != null) {
            val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
            val density = resources.displayMetrics.density
            val sizePercent = prefs.getInt("bubble_size", 20).coerceIn(0, 100)
            // 0% = 50dp, 100% = 150dp (lineal)
            val sizePx = ((50 + sizePercent) * density).toInt()
            if (isMenuExpanded) {
                returnToFakeLag()
            }
            if (::bubbleFaceOverlay.isInitialized) {
                bubbleFaceOverlay.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            }
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
        isMenuExpanded = false
        handler.removeCallbacks(longClickRunnable)
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
            targetPackage = intent.getStringExtra(NativeBridge.getNativeString(NativeBridge.S92))
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
                getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
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

        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)

        // Otorgar permisos SU para lectura de /dev/input/event* SOLO si el usuario activó el modo
        // root
        if (useRoot) {
            Thread {
                        executeRootCommand(NativeBridge.getNativeString(NativeBridge.S101))
                        executeRootCommand(NativeBridge.getNativeString(NativeBridge.S102))
                        executeRootCommand(NativeBridge.getNativeString(NativeBridge.S103))
                    }
                    .start()
        }

        setupBubble()

        startLicenseCheck()

        getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
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

            SessionGuard.clearSession(this)

            stopSelf()

            if (isAppOrGameInForeground()) {
                val intent = Intent(this, LoginActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            }
        }
    }

    private fun startForegroundNotification() {
        val channelId = NativeBridge.getNativeString(NativeBridge.S108)
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

    private fun setupBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)

        bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_layout, null)
        bubbleMainIcon = bubbleView.findViewById(R.id.bubble_main_icon)
        bubbleIcon = bubbleMainIcon
        cyberBubble = bubbleView.findViewById(R.id.cyber_bubble_view)
        bubbleFaceOverlay = bubbleView.findViewById(R.id.bubble_face_overlay)
        recoilMenu = bubbleView.findViewById(R.id.recoil_menu)

        cyberBubble.setMode(useRoot)
        cyberBubble.setActiveState(isFreezing)

        bubbleMainIcon.isClickable = false
        bubbleMainIcon.isFocusable = false

        arcOverlay = ArcProgressView(this, useRoot)
        arcOverlay.visibility = View.GONE

        val density = resources.displayMetrics.density
        val sizePercent = prefs.getInt("bubble_size", 20).coerceIn(0, 100)
        // 0% = 50dp, 100% = 150dp (lineal)
        val sizePx = ((50 + sizePercent) * density).toInt()

        // La burbuja tiene tamaño dinámico, se fija explícitamente sobre el overlay
        // para que el contenedor wrap_content mida correctamente la cara de la burbuja
        bubbleFaceOverlay.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)

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

        setupMemoryHelper()
        setupMenu()
        setupTouchListener()
        actualizarUI()
    }

    private fun setupMemoryHelper() {
        try {
            val dest = File(filesDir, "ffmem")
            assets.open("ffmem").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setExecutable(true, false)
            dest.setReadable(true, false)

            var chosenPath = dest.absolutePath
            val tmpDest = File("/data/local/tmp/ffmem")
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '${dest.absolutePath}' '${tmpDest.absolutePath}' && chmod 777 '${tmpDest.absolutePath}'"))
                p.waitFor()
                if (tmpDest.exists()) {
                    chosenPath = tmpDest.absolutePath
                }
            } catch (e: Exception) {}

            NativeBridge.setMemoryHelperPath(chosenPath)
            val dm = resources.displayMetrics
            val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
            NativeBridge.setPointerWidth(prefs.getInt("ptr_width", 4))
            val screenW = maxOf(dm.widthPixels, dm.heightPixels)
            val screenH = minOf(dm.widthPixels, dm.heightPixels)
            NativeBridge.setScreenSize(screenW, screenH)
            Log.d("FreezyMenu", "ffmem listo en $chosenPath (32-bit: 4 bytes)")
        } catch (e: Exception) {
            Log.e("FreezyMenu", "No se pudo preparar ffmem: ${e.message}")
        }
    }

private fun setupMenu() {
    if (!::recoilMenu.isInitialized) return

    val btnBackToLag = bubbleView.findViewById<ImageButton>(R.id.btn_back_to_lag)
    btnBackToLag.setOnClickListener { returnToFakeLag() }

    // Tabs del menú: Combate (cráneo) / Enemigos (ESP)
    val combatSection = bubbleView.findViewById<View>(R.id.combat_section)
    val espSection = bubbleView.findViewById<View>(R.id.esp_section)
    val tabCombat = bubbleView.findViewById<ImageButton>(R.id.tab_combat)
    val tabEsp = bubbleView.findViewById<ImageButton>(R.id.tab_esp)
    fun selectTab(combat: Boolean) {
        combatSection?.visibility = if (combat) View.VISIBLE else View.GONE
        espSection?.visibility = if (combat) View.GONE else View.VISIBLE
        tabCombat?.background = if (combat) getDrawable(R.drawable.shape_tab_active) else getDrawable(R.drawable.shape_tab_inactive)
        tabCombat?.setColorFilter(if (combat) Color.parseColor("#00E5FF") else Color.parseColor("#7E8B9B"))
        tabEsp?.background = if (combat) getDrawable(R.drawable.shape_tab_inactive) else getDrawable(R.drawable.shape_tab_active)
        tabEsp?.setColorFilter(if (combat) Color.parseColor("#7E8B9B") else Color.parseColor("#00E5FF"))
    }
    tabCombat?.setOnClickListener { selectTab(true) }
    tabEsp?.setOnClickListener { selectTab(false) }
    selectTab(false) // Por defecto abrir en pestaña ESP (segura)

    // ================================================================
    // [FREEZY MENU - AIMBOT]
    // ================================================================

    // 1. El aimbot arranca en OFF. Se activa solo cuando el usuario
    //    enciende el switch y se confirma que la memoria del juego existe.
    val statusText = bubbleView.findViewById<TextView>(R.id.status_text)
    statusText?.text = "Aimbot: OFF | Esperando activación"

    // 2. Conectar el Switch de Aimbot (recoil_switch)
    val aimbotSwitch = bubbleView.findViewById<Switch>(R.id.recoil_switch)

    // RESTRICCIÓN ANTI-BAN: el cráneo (aimbot/sniper/switch) requiere doble confirmación
    setupSkullSwitch(aimbotSwitch) { checked ->
        if (checked) {
            toggleAimbot()
        } else {
            NativeBridge.stopAimbot()
            Log.d("FreezyMenu", "Aimbot desactivado")
            Toast.makeText(this@BubbleService, "⛔ Aimbot desactivado", Toast.LENGTH_SHORT).show()
            statusText?.text = "Aimbot: OFF | Esperando activación"
        }
    }

    // 4. Switch Sniper Scope (aim-assist)
    val sniperScopeSwitch = bubbleView.findViewById<Switch>(R.id.sniper_scope_switch)
    val sniperBodySwitch = bubbleView.findViewById<Switch>(R.id.sniper_body_switch)
    val sniperScopeStatus = bubbleView.findViewById<TextView>(R.id.sniper_scope_status)

    setupSkullSwitch(sniperScopeSwitch) { checked ->
        if (checked) {
            toggleSniperScope()
        } else {
            NativeBridge.setSniperScope(false)
            Log.d("FreezyMenu", "Sniper Scope desactivado")
            Toast.makeText(this@BubbleService, "⛔ Sniper Scope desactivado", Toast.LENGTH_SHORT).show()
            sniperScopeStatus?.text = "Sniper: OFF | Cabeza"
        }
    }

    sniperBodySwitch?.setOnCheckedChangeListener { _, checked ->
        NativeBridge.setSniperMode(if (checked) 1 else 0)
        sniperScopeStatus?.text =
            if (sniperScopeSwitch?.isChecked == true) {
                if (checked) "Sniper: ON ✅ | Cuerpo" else "Sniper: ON ✅ | Cabeza"
            } else {
                if (checked) "Sniper: OFF | Cuerpo" else "Sniper: OFF | Cabeza"
            }
    }

    // 5. Switch Sniper Switch (patch de la mira)
    val sniperSwitch = bubbleView.findViewById<Switch>(R.id.sniper_switch_switch)
    val sniperSwitchStatus = bubbleView.findViewById<TextView>(R.id.sniper_switch_status)

    setupSkullSwitch(sniperSwitch) { checked ->
        if (checked) {
            toggleSniperSwitch()
        } else {
            Thread {
                if (NativeBridge.sniperSwitchRemove()) {
                    runOnUiThread {
                        sniperSwitchStatus?.text = "Patch: Quitado"
                        Toast.makeText(this@BubbleService, "⛔ Patch quitado", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        sniperSwitchStatus?.text = "Patch: no aplicado"
                        setSniperSwitchSilently(false)
                    }
                }
            }.start()
        }
    }

    // 6. ESP (master: busca PID) + ESP Box / ESP Skeleton / ESP Línea
    val espSwitch = bubbleView.findViewById<Switch>(R.id.esp_switch)
    val espBoxSwitch = bubbleView.findViewById<Switch>(R.id.esp_box_switch)
    val espSkeletonSwitch = bubbleView.findViewById<Switch>(R.id.esp_skeleton_switch)
    val espLineSwitch = bubbleView.findViewById<Switch>(R.id.esp_line_switch)
    val espStatus = bubbleView.findViewById<TextView>(R.id.esp_status)
    val espColorSeekbar = bubbleView.findViewById<SeekBar>(R.id.esp_color_seekbar)
    val espRgbSwitch = bubbleView.findViewById<Switch>(R.id.esp_rgb_switch)
    val espOriginStatus = bubbleView.findViewById<TextView>(R.id.esp_origin_status)
    val espOriginSeekbar = bubbleView.findViewById<SeekBar>(R.id.esp_origin_seekbar)
    val espWidthStatus = bubbleView.findViewById<TextView>(R.id.esp_width_status)
    val espWidthSeekbar = bubbleView.findViewById<SeekBar>(R.id.esp_width_seekbar)

    val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)

    val savedColor = prefs.getInt("esp_color", 1).coerceIn(0, 7)
    espColorSeekbar?.progress = savedColor
    espStatus?.text = "Color: ${espColorNames[savedColor]}"
    espStatus?.setTextColor(espColorValues[savedColor])

    val savedRgb = prefs.getBoolean("esp_rgb", false)
    espRgbSwitch?.isChecked = savedRgb

    val savedOrigin = prefs.getInt("esp_origin", 0).coerceIn(0, 2)
    espOriginSeekbar?.progress = savedOrigin
    espOriginStatus?.text = "Origen línea: ${espOriginNames[savedOrigin]}"

    val savedWidth = (prefs.getInt("esp_width", 3).coerceIn(1, 10) - 1)
    espWidthSeekbar?.progress = savedWidth
    espWidthStatus?.text = "Grosor línea: ${savedWidth + 1}px"

    fun setEspMode(skeleton: Boolean, line: Boolean) {
        espOverlayView?.drawSkeleton = skeleton
        espOverlayView?.drawLines = line
    }

    espColorSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            val idx = progress.coerceIn(0, 7)
            if (fromUser) {
                prefs.edit().putInt("esp_color", idx).apply()
            }
            espStatus?.text = "Color: ${espColorNames[idx]}"
            espStatus?.setTextColor(espColorValues[idx])
            espOverlayView?.lineColor = espColorValues[idx]
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })

    espRgbSwitch?.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean("esp_rgb", checked).apply()
        espOverlayView?.rgbMode = checked
    }

    espOriginSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            val idx = progress.coerceIn(0, 2)
            if (fromUser) {
                prefs.edit().putInt("esp_origin", idx).apply()
                espOriginStatus?.text = "Origen línea: ${espOriginNames[idx]}"
            }
            espOverlayView?.lineOrigin = idx
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })

    espWidthSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            val px = progress.coerceIn(0, 9) + 1
            if (fromUser) {
                prefs.edit().putInt("esp_width", px).apply()
                espWidthStatus?.text = "Grosor línea: ${px}px"
            }
            espOverlayView?.lineWidth = px.toFloat()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })

    // ESP (master): busca el PID y arranca/para el overlay.
    espSwitch?.apply {
        isChecked = false
        setOnCheckedChangeListener { _, checked ->
            if (checked) {
                startEspOverlay()
            } else {
                stopEspOverlay()
            }
        }
    }

    // ESP Box, ESP Skeleton y ESP Línea son independientes: cada uno activa su dibujo.
    espBoxSwitch?.apply {
        isChecked = false
        setOnCheckedChangeListener { _, checked -> espOverlayView?.drawBox = checked }
    }
    espSkeletonSwitch?.apply {
        isChecked = false
        setOnCheckedChangeListener { _, checked -> espOverlayView?.drawSkeleton = checked }
    }
    espLineSwitch?.apply {
        isChecked = false
        setOnCheckedChangeListener { _, checked -> espOverlayView?.drawLines = checked }
    }

    // ESP Health, ESP Team, Ignore Knocked
    val espHealthSwitch = bubbleView.findViewById<Switch>(R.id.esp_health_switch)
    espHealthSwitch?.apply {
        isChecked = false
        setOnCheckedChangeListener { _, checked -> espOverlayView?.drawHealth = checked }
    }

    val espTeamSwitch = bubbleView.findViewById<Switch>(R.id.esp_team_switch)
    espTeamSwitch?.apply {
        isChecked = false
        setOnCheckedChangeListener { _, checked -> espOverlayView?.drawTeam = checked }
    }

    val espNameSwitch = bubbleView.findViewById<Switch>(R.id.esp_name_switch)
    espNameSwitch?.apply {
        isChecked = false
        setOnCheckedChangeListener { _, checked -> espOverlayView?.drawName = checked }
    }

    val espDistanceSwitch = bubbleView.findViewById<Switch>(R.id.esp_distance_switch)
    espDistanceSwitch?.apply {
        isChecked = false
        setOnCheckedChangeListener { _, checked -> espOverlayView?.drawDistance = checked }
    }

    val espWeaponSwitch = bubbleView.findViewById<Switch>(R.id.esp_weapon_switch)
    espWeaponSwitch?.apply {
        isChecked = false
        setOnCheckedChangeListener { _, checked -> espOverlayView?.drawWeapon = checked }
    }

    val espIgnoreKnockedSwitch = bubbleView.findViewById<Switch>(R.id.esp_ignore_knocked_switch)
    espIgnoreKnockedSwitch?.apply {
        isChecked = false
        setOnCheckedChangeListener { _, checked -> espOverlayView?.ignoreKnocked = checked }
    }

    // ESP Count: muestra el contador de enemigos arriba al centro.
    val espCountSwitch = bubbleView.findViewById<Switch>(R.id.esp_count_switch)
    val savedCount = prefs.getBoolean("esp_count", false)
    espCountSwitch?.isChecked = savedCount
    espCountSwitch?.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean("esp_count", checked).apply()
        espOverlayView?.showCount = checked
    }

    // 3. MOVIMIENTO DE LA BURBUJA (tu código existente)
    val menuHeader = bubbleView.findViewById<LinearLayout>(R.id.menu_header)
    menuHeader.setOnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
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
                getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
                        .edit()
                        .putInt("bubble_x", params.x)
                        .putInt("bubble_y", params.y)
                        .apply()
                true
            }
            else -> false
        }
    }
}

// RESTRICCIÓN ANTI-BAN para los switches del cráneo (Aimbot / Sniper Scope / Sniper Switch).
// Mecanismo de doble confirmación: un solo tap se revierte y avisa; hay que tocar el switch
// de nuevo dentro de 3s para activar de verdad. Evita activaciones accidentales que dan ban.
private fun setupSkullSwitch(switch: Switch?, onActivate: (Boolean) -> Unit) {
    if (switch == null) return
    val CONFIRM_WINDOW_MS = 3000L
    var armed = false
    var reverting = false
    val disarmRunnable = Runnable { armed = false }

    // Estado de partida arranca en OFF
    switch.isChecked = false
    switch.setOnCheckedChangeListener { _, checked ->
        if (reverting) {
            reverting = false
            return@setOnCheckedChangeListener
        }
        if (!checked) {
            // Apagado: siempre permitido (desactivar no da ban)
            handler.removeCallbacks(disarmRunnable)
            armed = false
            onActivate(false)
        } else {
            // Encendido: requiere confirmación con un segundo tap dentro de la ventana
            if (armed) {
                handler.removeCallbacks(disarmRunnable)
                armed = false
                switch.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                Toast.makeText(this@BubbleService, "☠️ Cráneo activado", Toast.LENGTH_SHORT).show()
                onActivate(true)
            } else {
                armed = true
                handler.postDelayed(disarmRunnable, CONFIRM_WINDOW_MS)
                // Reverto a OFF: el primer tap NUNCA activa, solo arma la confirmación
                reverting = true
                switch.isChecked = false
                Toast.makeText(this@BubbleService, "⚠️ Riesgo de ban. Pulsa de nuevo para activar", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// Activa el aimbot: busca el PID, confirma que la memoria es legible y lo aplica.
private fun toggleAimbot() {
    val statusText = bubbleView.findViewById<TextView>(R.id.status_text)
    statusText?.text = "🔍 Buscando en memoria..."
    Toast.makeText(this@BubbleService, "🔍 Buscando en memoria...", Toast.LENGTH_SHORT).show()

    Thread {
        val pid = NativeBridge.findGamePid()
        if (pid <= 0) {
            Log.e("FreezyMenu", "❌ Juego no encontrado (PID <= 0). ¿Está corriendo?")
            failAimbot("❌ Memoria no encontrada, aimbot no aplicado", "pid<=0")
            return@Thread
        }
        Log.d("FreezyMenu", "PID encontrado: $pid — verificando memoria del juego...")
        if (!NativeBridge.isGameMemoryReady(pid)) {
            val diag = NativeBridge.getGameMemoryDiagnostics(pid)
            Log.e("FreezyMenu", "❌ Memoria no legible → $diag")
            failAimbot("❌ Memoria no encontrada, aimbot no aplicado", diag)
            return@Thread
        }

        NativeBridge.startAimbot()
        Log.d("FreezyMenu", "Aimbot aplicado (PID: $pid)")
        runOnUiThread {
            val statusText = bubbleView.findViewById<TextView>(R.id.status_text)
            statusText?.text = "Aimbot: ON ✅ | PID: $pid"
            Toast.makeText(this@BubbleService, "✅ Aimbot aplicado (PID: $pid)", Toast.LENGTH_SHORT).show()
        }
    }.start()
}

private fun failAimbot(message: String, detail: String = "") {
    runOnUiThread {
        val statusText = bubbleView.findViewById<TextView>(R.id.status_text)
        statusText?.text = message
        if (detail.isNotEmpty()) {
            Toast.makeText(this@BubbleService, "$message\n\n$detail", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this@BubbleService, message, Toast.LENGTH_SHORT).show()
        }
        setSwitchSilently(false)
    }
}

// Cambia el switch sin volver a disparar el listener
private fun setSwitchSilently(checked: Boolean) {
    val switch = bubbleView.findViewById<Switch>(R.id.recoil_switch) ?: return
    aimbotSwitchBusy = true
    switch.isChecked = checked
    aimbotSwitchBusy = false
}

private fun setSniperSwitchSilently(checked: Boolean) {
    val switch = bubbleView.findViewById<Switch>(R.id.sniper_switch_switch) ?: return
    sniperSwitchBusy = true
    switch.isChecked = checked
    sniperSwitchBusy = false
}

// Activa el aim-assist de sniper: busca PID y memoria, configura modo y arranca.
private fun toggleSniperScope() {
    val statusText = bubbleView.findViewById<TextView>(R.id.sniper_scope_status)
    statusText?.text = "🔍 Buscando en memoria..."
    Toast.makeText(this@BubbleService, "🔍 Buscando en memoria...", Toast.LENGTH_SHORT).show()

    Thread {
        val pid = NativeBridge.findGamePid()
        if (pid <= 0) {
            Log.e("FreezyMenu", "❌ Juego no encontrado (PID <= 0). ¿Está corriendo?")
            runOnUiThread {
                statusText?.text = "Sniper: OFF | Juego no encontrado"
                Toast.makeText(this@BubbleService, "❌ Memoria no encontrada, sniper no aplicado", Toast.LENGTH_LONG).show()
                setSniperScopeSilently(false)
            }
            return@Thread
        }
        if (!NativeBridge.isGameMemoryReady(pid)) {
            runOnUiThread {
                statusText?.text = "Sniper: OFF | Memoria no legible"
                Toast.makeText(this@BubbleService, "❌ Memoria no legible, sniper no aplicado", Toast.LENGTH_LONG).show()
                setSniperScopeSilently(false)
            }
            return@Thread
        }

        val mode = if (bubbleView.findViewById<Switch>(R.id.sniper_body_switch).isChecked) 1 else 0
        NativeBridge.setSniperMode(mode)
        NativeBridge.setSniperScope(true)
        Log.d("FreezyMenu", "Sniper Scope aplicado (PID: $pid, modo: $mode)")
        runOnUiThread {
            statusText?.text = if (mode == 1) "Sniper: ON ✅ | Cuerpo" else "Sniper: ON ✅ | Cabeza"
            Toast.makeText(this@BubbleService, "✅ Sniper Scope aplicado (PID: $pid)", Toast.LENGTH_SHORT).show()
        }
    }.start()
}

private fun setSniperScopeSilently(checked: Boolean) {
    val switch = bubbleView.findViewById<Switch>(R.id.sniper_scope_switch) ?: return
    sniperScopeSwitchBusy = true
    switch.isChecked = checked
    sniperScopeSwitchBusy = false
}

// Aplica el patch de la mira (patrones de SniperSwitch.cs)
private fun toggleSniperSwitch() {
    val statusText = bubbleView.findViewById<TextView>(R.id.sniper_switch_status)
    statusText?.text = "🔍 Buscando en memoria..."
    Toast.makeText(this@BubbleService, "🔍 Buscando en memoria...", Toast.LENGTH_SHORT).show()

    Thread {
        val ok = NativeBridge.sniperSwitchApply()
        if (ok) {
            Log.d("FreezyMenu", "Sniper Switch aplicado")
            runOnUiThread {
                statusText?.text = "Patch: Aplicado ✅"
                Toast.makeText(this@BubbleService, "✅ Sniper Switch aplicado", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.e("FreezyMenu", "Sniper Switch: patrón no encontrado")
            runOnUiThread {
                statusText?.text = "Patch: Patrón no encontrado"
                Toast.makeText(this@BubbleService, "❌ Patrón no encontrado", Toast.LENGTH_LONG).show()
                setSniperSwitchSilently(false)
            }
        }
    }.start()
}

private fun runOnUiThread(action: () -> Unit) {
    android.os.Handler(mainLooper).post(action)
}

private val espColorNames = arrayOf("Rojo", "Verde", "Azul", "Cyan", "Rosa", "Morado", "Blanco", "Amarillo")

private val espOriginNames = arrayOf("Abajo", "Medio", "Arriba")

private val espColorValues = intArrayOf(
    0xFFF44336.toInt(), // Rojo
    0xFF4CAF50.toInt(), // Verde
    0xFF2196F3.toInt(), // Azul
    0xFF00BCD4.toInt(), // Cyan
    0xFFE91E63.toInt(), // Rosa
    0xFF9C27B0.toInt(), // Morado
    0xFFFFFFFF.toInt(), // Blanco
    0xFFFFEB3B.toInt()  // Amarillo
)

private fun startEspOverlay() {
    val pid = NativeBridge.findGamePid()
    if (pid <= 0) {
        Toast.makeText(this@BubbleService, "❌ Juego no encontrado, ESP no activado", Toast.LENGTH_SHORT).show()
        setEspSwitchSilently(false)
        return
    }
    if (espOverlayView != null) return
    val overlay = EspOverlayView(this)
    val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
    val colorIdx = prefs.getInt("esp_color", 1).coerceIn(0, 7)
    overlay.lineColor = espColorValues[colorIdx]
    overlay.rgbMode = prefs.getBoolean("esp_rgb", false)
    overlay.lineOrigin = prefs.getInt("esp_origin", 0).coerceIn(0, 2)
    overlay.lineWidth = prefs.getInt("esp_width", 3).coerceIn(1, 10).toFloat()
    overlay.showCount = prefs.getBoolean("esp_count", false)
    overlay.drawBox = bubbleView.findViewById<Switch>(R.id.esp_box_switch)?.isChecked ?: false
    overlay.drawSkeleton = bubbleView.findViewById<Switch>(R.id.esp_skeleton_switch)?.isChecked ?: false
    overlay.drawLines = bubbleView.findViewById<Switch>(R.id.esp_line_switch)?.isChecked ?: false
    overlay.drawHealth = bubbleView.findViewById<Switch>(R.id.esp_health_switch)?.isChecked ?: false
    overlay.drawTeam = bubbleView.findViewById<Switch>(R.id.esp_team_switch)?.isChecked ?: false
    overlay.drawName = bubbleView.findViewById<Switch>(R.id.esp_name_switch)?.isChecked ?: false
    overlay.drawDistance = bubbleView.findViewById<Switch>(R.id.esp_distance_switch)?.isChecked ?: false
    overlay.drawWeapon = bubbleView.findViewById<Switch>(R.id.esp_weapon_switch)?.isChecked ?: false
    overlay.ignoreKnocked = bubbleView.findViewById<Switch>(R.id.esp_ignore_knocked_switch)?.isChecked ?: false
    val overlayParams =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
    try {
        windowManager.addView(overlay, overlayParams)
        espOverlayView = overlay
        overlay.start(pid)
        Toast.makeText(this@BubbleService, "✅ ESP activado", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.e("FreezyMenu", "ESP overlay error: ${e.message}")
        setEspSwitchSilently(false)
    }
}

private fun stopEspOverlay() {
    val overlay = espOverlayView ?: run { return }
    overlay.stop()
    try { windowManager.removeView(overlay) } catch (e: Exception) {}
    espOverlayView = null
}

private fun setEspSwitchSilently(checked: Boolean) {
    val switch = bubbleView.findViewById<Switch>(R.id.esp_switch) ?: return
    switch.setOnCheckedChangeListener(null)
    switch.isChecked = checked
    val espSwitch = switch
    // religar el listener
    espSwitch.setOnCheckedChangeListener { _, isChecked ->
        if (isChecked) startEspOverlay() else stopEspOverlay()
    }
}

    private fun expandBubbleMenu() {
        if (isMenuExpanded) return
        isMenuExpanded = true
        recoilMenu.visibility = View.VISIBLE
        bubbleFaceOverlay.visibility = View.GONE

        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            recreateBubbles()
        }
    }

    private fun returnToFakeLag() {
        recoilMenu.visibility = View.GONE
        bubbleFaceOverlay.visibility = View.VISIBLE
        isMenuExpanded = false

        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        val sizePercent = prefs.getInt("bubble_size", 20).coerceIn(0, 100)
        val sizePx = ((50 + sizePercent) * resources.displayMetrics.density).toInt()
        params.width = sizePx
        params.height = sizePx
        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            recreateBubbles()
        }
    }

    private fun actualizarUI() {
        val useRoot =
                getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
                        .getBoolean("use_root", false)
        val isActive = if (useRoot) LagController.fakeLagActivo else isFreezing

        if (::cyberBubble.isInitialized) {
            cyberBubble.setMode(useRoot)
            cyberBubble.setActiveState(isActive)
        }

        if (::bubbleMainIcon.isInitialized && ::bubbleFaceOverlay.isInitialized) {
            val colorStr = if (useRoot) "#B026FF" else "#00E5FF"
            bubbleMainIcon.setColorFilter(
                    Color.parseColor(colorStr),
                    android.graphics.PorterDuff.Mode.SRC_IN
            )
            bubbleMainIcon.alpha = if (isActive) 1.0f else (if (useRoot) 0.6f else 1.0f)
        }

        if (::arcOverlay.isInitialized) {
            arcOverlay.updateMode(useRoot)
        }
    }

    private fun setupTouchListener() {
        if (::bubbleFaceOverlay.isInitialized) {
            bubbleFaceOverlay.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isDragging = false
                        isLongClickTriggered = false
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        handler.postDelayed(longClickRunnable, 3000L)
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
                            bubbleFaceOverlay.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.KEYBOARD_TAP
                            )
                            onBubbleTapped()
                        } else if (isDragging) {
                            getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
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
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("use_root", false)

        val mode = prefs.getInt("mode", 0)

        // Modo Manual: el tap siempre hace toggle (ON/OFF)
        if (mode == 2) {
            toggleManual(useRoot)
            return
        }

        // Modos Auto y Personalizado: ignorar taps mientras ya está activo
        if (isFreezing) return

        val customSeconds = prefs.getFloat("custom_time_float", 1.0f).coerceAtLeast(0.5f).coerceAtMost(3.0f)
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

    private fun executeRootCommand(command: String) {
        if (suProcess == null) {
            try {
                suProcess = Runtime.getRuntime().exec(NativeBridge.getNativeString(NativeBridge.STRING_SU))
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

    private fun startFreeze(useRoot: Boolean, durationMs: Long = 3000L) {
        playSelectedTone()
        Logger.log(
                this,
                NativeBridge.getNativeString(NativeBridge.STRING_FAKE_LAG_ACTIVE) +
                        " (Root: $useRoot)"
        )

        if (useRoot) {
            LagController.toggleFakeLag(true, true)
        } else {
            try {
                // Forzar límite máximo de desincronización de 800ms en C++ para que Free Fire registre el daño y el ping no suba a 999ms
                NativeBridge.setNativeMaxDesyncMs(800L)

                // Iniciar la VPN dinámicamente si no estuviera corriendo
                val vpnIntent =
                        Intent(this, AntigravityFirewall::class.java).apply {
                            putExtra(NativeBridge.getNativeString(NativeBridge.S92), targetPackage ?: NativeBridge.getNativeString(NativeBridge.S98))
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
        playSelectedTone()
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

                // Detener la VPN de inmediato para volver al ruteo directo y restaurar el ping normal
                val vpnIntent =
                        Intent(this, AntigravityFirewall::class.java).apply { action = NativeBridge.getNativeString(NativeBridge.S91) }
                startService(vpnIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        actualizarUI()
    }

    // Reproduce el tono de activación/desactivación elegido por el usuario en Extras
    private fun playSelectedTone() {
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        ToneManager.play(this, prefs.getInt("tone_type", 0))
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
        handler.removeCallbacks(longClickRunnable)
        // Limpieza de iptables si estaban activos en LagController
        try {
            LagController.desactivarFakeLagRoot()
            LagController.fakeLagActivo = false
        } catch (e: Exception) {}

        if (isFreezing) {
            try {
                stopFreeze(
                    getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
                        .getBoolean("use_root", false)
                )
            } catch (e: Exception) {}
        }
        fillAnimator?.cancel()

        // Limpieza de Overlays para evitar que queden pegados en pantalla
        stopEspOverlay()
        if (::bubbleView.isInitialized && bubbleView.parent != null) {
            try { windowManager.removeView(bubbleView) } catch (e: Exception) {}
        }

        // Detener la VPN si estaba en uso al destruir el servicio
        try {
            val vpnIntent = Intent(this, AntigravityFirewall::class.java).apply { action = NativeBridge.getNativeString(NativeBridge.S91) }
            startService(vpnIntent)
            stopService(Intent(this, AntigravityFirewall::class.java))
        } catch (e: Exception) {}

        // Cerrar el shell root correctamente
        try {
            suOutputStream?.writeBytes(NativeBridge.getNativeString(NativeBridge.STRING_SU_CMD_EXIT))
            suOutputStream?.flush()
            suOutputStream?.close()
            suProcess?.destroy()
        } catch (e: Exception) {}

        getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_bubble_running", false)
            .apply()
    }

    private fun isAppOrGameInForeground(): Boolean {
        val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
        val targetPkg =
                prefs.getString(NativeBridge.getNativeString(NativeBridge.S92), NativeBridge.getNativeString(NativeBridge.S98)) ?: NativeBridge.getNativeString(NativeBridge.S98)

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
                                        pkgName == NativeBridge.getNativeString(NativeBridge.S98) ||
                                        pkgName == NativeBridge.getNativeString(NativeBridge.S99)
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
