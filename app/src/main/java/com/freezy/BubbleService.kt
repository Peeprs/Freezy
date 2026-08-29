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
import android.view.ViewGroup
import android.view.WindowManager
import android.util.Log
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
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
    private var espOverlayView: EspOverlayView? = null
    private var ghostFloatingView: View? = null
    private var ghostFloatingParams: WindowManager.LayoutParams? = null
    private var teleportDropFloatingView: View? = null
    private var teleportDropFloatingParams: WindowManager.LayoutParams? = null
    @Volatile private var teleportDropOperationGeneration = 0
    @Volatile private var teleportDropUsesRoot = false
    private var enemyPullFloatingView: View? = null
    private var enemyPullFloatingParams: WindowManager.LayoutParams? = null
    private var enemyPullDirection = 0
    private var enemyPullRequestGeneration = 0
    private var isGhostActive = false
    private var ghostUsesRoot = false
    private var ghostTargetPackage: String? = null
    @Volatile private var ghostOperationGeneration = 0
    private var isAimbotPacketDelayActive = false
    private var aimVisibleSwitchBusy = false
    private var suppressEspMasterListener = false
    private var appliedRootMode: Boolean? = null
    private val longClickRunnable = Runnable {
        if (LicenseEntitlements.hasPaidFeatures(this)) {
            val compatibility = currentCompatibility()
            if (compatibility.supportsAdvancedFeatures) {
                isLongClickTriggered = true
                expandBubbleMenu()
            } else {
                Toast.makeText(this, compatibility.message, Toast.LENGTH_LONG).show()
            }
        }
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
            val incomingPackage = intent.getStringExtra(NativeBridge.getNativeString(NativeBridge.S92))
            val targetChanged = !incomingPackage.isNullOrEmpty() && incomingPackage != targetPackage
            if (!incomingPackage.isNullOrEmpty()) targetPackage = incomingPackage
            if (intent.action == NativeBridge.getNativeString(NativeBridge.S211)) {
                recreateBubbles()
                return START_STICKY
            }
            if (intent.action == NativeBridge.getNativeString(NativeBridge.S212)) {
                updateBubbleSize()
                return START_STICKY
            }
            if (targetChanged && ::bubbleView.isInitialized) {
                recreateBubbles()
                return START_STICKY
            }
        }

        // Actualizar el color y modo de la burbuja
        val isRootMode =
                getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
                        .getBoolean(NativeBridge.getNativeString(NativeBridge.S205), false)
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
        val useRoot = prefs.getBoolean(NativeBridge.getNativeString(NativeBridge.S205), false)

        // Otorgar permisos SU para lectura de /dev/input/event* SOLO si el usuario activó el modo
        // root
        if (useRoot) {
            Thread {
                        val startupPackage = if (prefs.getBoolean("use_ff_max", false)) {
                            NativeBridge.getNativeString(NativeBridge.S98)
                        } else {
                            NativeBridge.getNativeString(NativeBridge.S99)
                        }
                        // Limpiar reglas que pudieran sobrevivir a un cierre
                        // forzado anterior antes de aceptar nuevas activaciones.
                        RootTeleportDropController.disable()
                        GhostController.disableRoot(this@BubbleService, startupPackage)
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
                .putBoolean(NativeBridge.getNativeString(NativeBridge.S207), true)
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
                                val wasPaid = LicenseEntitlements.hasPaidFeatures(this@BubbleService)
                                val isPaid = LicenseEntitlements.updateFromServer(
                                    this@BubbleService,
                                    jsonObject
                                )
                                if (wasPaid != isPaid || !isPaid) {
                                    handler.post {
                                        val policyPrefs = getSharedPreferences(
                                            NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
                                            Context.MODE_PRIVATE
                                        )
                                        enforceModeAndLicensePolicy(
                                            policyPrefs.getBoolean("use_root", false),
                                            isPaid && currentCompatibility().supportsAdvancedFeatures
                                        )
                                        recreateBubbles()
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
                        .setSmallIcon(R.drawable.ic_notification_freezy)
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
        val hasAdvancedAccess = LicenseEntitlements.hasPaidFeatures(this) &&
            currentCompatibility().supportsAdvancedFeatures

        enforceModeAndLicensePolicy(useRoot, hasAdvancedAccess)

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
                            x = prefs.getInt(NativeBridge.getNativeString(NativeBridge.S203), 100)
                            y = prefs.getInt(NativeBridge.getNativeString(NativeBridge.S204), 200)
                        }
        windowManager.addView(bubbleView, params)

        // El lector de memoria y sus binarios auxiliares pertenecen únicamente
        // al modo Root pagado. NoRoot y TRIAL nunca los preparan ni ejecutan.
        if (useRoot && hasAdvancedAccess) setupMemoryHelper()
        setupMenu()
        setupTouchListener()
        actualizarUI()
    }

    private fun setupMemoryHelper() {
        try {
            // Guardar helper en almacenamiento privado interno camuflado
            val dest = File(filesDir, NativeBridge.getNativeString(NativeBridge.S214))
            assets.open(NativeBridge.getNativeString(NativeBridge.S215)).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setExecutable(true, false)
            dest.setReadable(true, false)

            val chosenPath = dest.absolutePath

            // Limpieza proactiva: eliminar cualquier rastro antiguo en /data/local/tmp
            try {
                Runtime.getRuntime().exec(arrayOf(
                    NativeBridge.getNativeString(NativeBridge.STRING_SU),
                    "-c",
                    NativeBridge.getNativeString(NativeBridge.S213)
                ))
            } catch (e: Exception) {}

            NativeBridge.setMemoryHelperPath(chosenPath)
            val dm = resources.displayMetrics
            val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
            val defaultPtrW = if (android.os.Process.is64Bit() || android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) 8 else 4
            NativeBridge.setPointerWidth(prefs.getInt(NativeBridge.getNativeString(NativeBridge.S206), defaultPtrW))
            val screenW = maxOf(dm.widthPixels, dm.heightPixels)
            val screenH = minOf(dm.widthPixels, dm.heightPixels)
            NativeBridge.setScreenSize(screenW, screenH)
        } catch (e: Exception) {
        }
    }

    /**
     * Elimina estados restaurados que no pertenecen al modo/licencia actuales.
     * Esto evita que una preferencia antigua reactive una función aunque su
     * switch ya no sea visible.
     */
    private fun enforceModeAndLicensePolicy(useRoot: Boolean, hasAdvancedAccess: Boolean) {
        val prefs = getSharedPreferences(
            NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
            Context.MODE_PRIVATE
        )
        val previousRootMode = appliedRootMode

        // Un cambio de selector desmonta primero la tecnología anterior. Así no
        // quedan simultáneamente una cadena Root y un túnel NoRoot activos.
        if (previousRootMode != null && previousRootMode != useRoot) {
            hideTeleportDropFloatingControl(replayCaptured = false)
            if (previousRootMode) {
                try { LagController.toggleFakeLag(false, true) } catch (_: Throwable) {}
            } else {
                try { LagController.toggleFakeLag(false, false) } catch (_: Throwable) {}
            }
            isFreezing = false
            fillAnimator?.cancel()
        }

        if (!useRoot || !hasAdvancedAccess) {
            prefs.edit()
                .putBoolean("pref_silent_aim", false)
                .putBoolean("pref_no_reload", false)
                .putBoolean("pref_aimbot_switch", false)
                .putBoolean("pref_aim_visible", false)
                .putBoolean("pref_fly_hack", false)
                .putBoolean("pref_enemy_pull", false)
                .apply()
            try { NativeBridge.setAimVisible(false) } catch (_: Throwable) {}
            try { NativeBridge.setSilentAim(false) } catch (_: Throwable) {}
            try { NativeBridge.setEnemyPull(false) } catch (_: Throwable) {}
            try { NativeBridge.setEnemyPullDirection(0) } catch (_: Throwable) {}
            try { NativeBridge.setFlyHack(false) } catch (_: Throwable) {}
            try { NativeBridge.setNoReload(false) } catch (_: Throwable) {}
            try { NativeBridge.setCameraAimbot(false) } catch (_: Throwable) {}
            stopEspOverlay()
            hideEnemyPullFloatingControl()
            try { NativeBridge.shutdownMemoryAccess() } catch (_: Throwable) {}
        }

        if (!hasAdvancedAccess) {
            prefs.edit().putBoolean("pref_teleport_drop", false).apply()
            hideTeleportDropFloatingControl(replayCaptured = false)
        }

        if (!hasAdvancedAccess) {
            prefs.edit().putBoolean("pref_ghost_hack", false).apply()
            hideGhostFloatingSwitch()
        } else if (GhostController.active && GhostController.usingRoot != useRoot) {
            // Al cambiar de modo se desmonta primero la implementación anterior;
            // el menú nuevo podrá crear después la burbuja de la ruta correcta.
            hideGhostFloatingSwitch()
        }

        if (previousRootMode == false && useRoot && AntigravityFirewall.isTunnelRunning) {
            try {
                startService(Intent(this, AntigravityFirewall::class.java).apply {
                    action = NativeBridge.getNativeString(NativeBridge.S91)
                })
            } catch (_: Exception) {}
        }
        appliedRootMode = useRoot
    }

    /** Menú pagado NoRoot: únicamente herramientas que usan el túnel de red. */
    private fun setupNoRootPremiumMenu(prefs: android.content.SharedPreferences) {
        val tabEsp = bubbleView.findViewById<ImageButton>(R.id.tab_esp)
        val tabAim = bubbleView.findViewById<ImageButton>(R.id.tab_aim)
        val tabFly = bubbleView.findViewById<ImageButton>(R.id.tab_fly)
        tabEsp?.visibility = View.GONE
        tabAim?.visibility = View.GONE
        tabFly?.visibility = View.VISIBLE
        tabFly?.setBackgroundResource(R.drawable.shape_tab_active)
        tabFly?.setColorFilter(
            Color.parseColor("#00E5FF"),
            android.graphics.PorterDuff.Mode.SRC_IN
        )

        bubbleView.findViewById<View>(R.id.visuals_subtabs_container)?.visibility = View.GONE
        bubbleView.findViewById<View>(R.id.esp_section)?.visibility = View.GONE
        bubbleView.findViewById<View>(R.id.esp_v2_section)?.visibility = View.GONE
        bubbleView.findViewById<View>(R.id.chams_section)?.visibility = View.GONE
        bubbleView.findViewById<View>(R.id.aim_section)?.visibility = View.GONE
        bubbleView.findViewById<View>(R.id.fly_section)?.visibility = View.VISIBLE
        bubbleView.findViewById<TextView>(R.id.tv_hud_title)?.text = "FREEZY - RED NO ROOT"
        bubbleView.findViewById<TextView>(R.id.tv_title_fly_modes)?.text = "RED NO ROOT"
        bubbleView.findViewById<TextView>(R.id.tv_title_teleport_exploits)?.text = "DATOS Y RUTA"

        val rootOnlySwitches = intArrayOf(
            R.id.fly_hack_switch,
            R.id.enemy_pull_switch
        )
        rootOnlySwitches.forEach { id ->
            bubbleView.findViewById<View>(id)?.visibility = View.GONE
        }

        val ghostSwitch = bubbleView.findViewById<Switch>(R.id.ghost_hack_switch)
        val teleportDropSwitch = bubbleView.findViewById<Switch>(R.id.teleport_drop_switch)
        ghostSwitch?.visibility = View.VISIBLE
        teleportDropSwitch?.visibility = View.VISIBLE

        val savedGhost = prefs.getBoolean("pref_ghost_hack", false)
        ghostSwitch?.isChecked = savedGhost
        if (savedGhost) showGhostFloatingSwitch()
        ghostSwitch?.setOnCheckedChangeListener { _, checked ->
            if (!canUseAdvancedFeatures()) {
                ghostSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("pref_ghost_hack", checked).apply()
            if (checked) showGhostFloatingSwitch() else hideGhostFloatingSwitch()
        }

        val savedDrop = prefs.getBoolean("pref_teleport_drop", false)
        teleportDropSwitch?.isChecked = savedDrop
        if (savedDrop) showTeleportDropFloatingControl()
        teleportDropSwitch?.setOnCheckedChangeListener { _, checked ->
            if (!canUseAdvancedFeatures()) {
                teleportDropSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("pref_teleport_drop", checked).apply()
            if (checked) showTeleportDropFloatingControl()
            else hideTeleportDropFloatingControl(replayCaptured = true)
        }
    }

private fun setupMenu() {
    if (!::recoilMenu.isInitialized) return

    val btnBackToLag = bubbleView.findViewById<ImageButton>(R.id.btn_back_to_lag)
    btnBackToLag.setOnClickListener { returnToFakeLag() }

    val accessPrefs = getSharedPreferences(
        NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
        Context.MODE_PRIVATE
    )
    val hasAdvancedAccess = canUseAdvancedFeatures()
    if (!hasAdvancedAccess) return
    if (!accessPrefs.getBoolean("use_root", false)) {
        setupNoRootPremiumMenu(accessPrefs)
        return
    }

    // ==========================================
    // NAVEGACIÓN DE PESTAÑAS LATERALES (TABS)
    // ==========================================
    val tabEsp = bubbleView.findViewById<ImageButton>(R.id.tab_esp)
    val tabAim = bubbleView.findViewById<ImageButton>(R.id.tab_aim)
    val tabFly = bubbleView.findViewById<ImageButton>(R.id.tab_fly)

    val visualsSubtabs = bubbleView.findViewById<View>(R.id.visuals_subtabs_container)
    val espSection = bubbleView.findViewById<View>(R.id.esp_section)
    val espV2Section = bubbleView.findViewById<View>(R.id.esp_v2_section)
    val chamsSection = bubbleView.findViewById<View>(R.id.chams_section)
    val aimSection = bubbleView.findViewById<View>(R.id.aim_section)
    val flySection = bubbleView.findViewById<View>(R.id.fly_section)
    val btnSubtabEsp = bubbleView.findViewById<TextView>(R.id.btn_subtab_esp)
    val btnSubtabEspV2 = bubbleView.findViewById<TextView>(R.id.btn_subtab_esp_v2)
    val btnSubtabChams = bubbleView.findViewById<TextView>(R.id.btn_subtab_chams)
    var selectedVisualSubtab = 0

    val tvHudTitle = bubbleView.findViewById<TextView>(R.id.tv_hud_title)

    fun selectTab(tabIndex: Int) {
        tabEsp?.setBackgroundResource(if (tabIndex == 0) R.drawable.shape_tab_active else R.drawable.shape_tab_inactive)
        tabEsp?.setColorFilter(if (tabIndex == 0) Color.parseColor("#00E5FF") else Color.parseColor("#8A9BA8"), android.graphics.PorterDuff.Mode.SRC_IN)

        tabAim?.setBackgroundResource(if (tabIndex == 1) R.drawable.shape_tab_active else R.drawable.shape_tab_inactive)
        tabAim?.setColorFilter(if (tabIndex == 1) Color.parseColor("#00E5FF") else Color.parseColor("#8A9BA8"), android.graphics.PorterDuff.Mode.SRC_IN)

        tabFly?.setBackgroundResource(if (tabIndex == 2) R.drawable.shape_tab_active else R.drawable.shape_tab_inactive)
        tabFly?.setColorFilter(if (tabIndex == 2) Color.parseColor("#00E5FF") else Color.parseColor("#8A9BA8"), android.graphics.PorterDuff.Mode.SRC_IN)

        when (tabIndex) {
            0 -> {
                tvHudTitle?.text = NativeBridge.getNativeString(NativeBridge.S223)
                visualsSubtabs?.visibility = View.VISIBLE
                espSection?.visibility = if (selectedVisualSubtab == 0) View.VISIBLE else View.GONE
                espV2Section?.visibility = if (selectedVisualSubtab == 1) View.VISIBLE else View.GONE
                chamsSection?.visibility = if (selectedVisualSubtab == 2) View.VISIBLE else View.GONE
                aimSection?.visibility = View.GONE
                flySection?.visibility = View.GONE
            }
            1 -> {
                tvHudTitle?.text = "FREEZY MODS - COMBATE"
                visualsSubtabs?.visibility = View.GONE
                espSection?.visibility = View.GONE
                espV2Section?.visibility = View.GONE
                chamsSection?.visibility = View.GONE
                aimSection?.visibility = View.VISIBLE
                flySection?.visibility = View.GONE
            }
            2 -> {
                tvHudTitle?.text = "FREEZY MODS - MOVIMIENTO"
                visualsSubtabs?.visibility = View.GONE
                espSection?.visibility = View.GONE
                espV2Section?.visibility = View.GONE
                chamsSection?.visibility = View.GONE
                aimSection?.visibility = View.GONE
                flySection?.visibility = View.VISIBLE
            }
        }
    }

    tabEsp?.setOnClickListener { selectTab(0) }
    tabAim?.setOnClickListener { selectTab(1) }
    tabFly?.setOnClickListener { selectTab(2) }

    // Inject dynamic strings from NativeBridge to HUD headers and ESP switches
    bubbleView.findViewById<TextView>(R.id.tv_hud_title)?.text = NativeBridge.getNativeString(NativeBridge.S223)
    bubbleView.findViewById<TextView>(R.id.tv_hud_online)?.text = NativeBridge.getNativeString(NativeBridge.S224)

    bubbleView.findViewById<TextView>(R.id.tv_title_esp_draw)?.text = NativeBridge.getNativeString(NativeBridge.S231)
    bubbleView.findViewById<Switch>(R.id.esp_switch)?.text = NativeBridge.getNativeString(NativeBridge.S232)
    bubbleView.findViewById<Switch>(R.id.esp_box_switch)?.text = NativeBridge.getNativeString(NativeBridge.S233)
    bubbleView.findViewById<Switch>(R.id.esp_skeleton_switch)?.text = NativeBridge.getNativeString(NativeBridge.S234)
    bubbleView.findViewById<Switch>(R.id.esp_line_switch)?.text = NativeBridge.getNativeString(NativeBridge.S235)
    bubbleView.findViewById<Switch>(R.id.esp_count_switch)?.text = NativeBridge.getNativeString(NativeBridge.S236)

    bubbleView.findViewById<TextView>(R.id.tv_title_esp_filters)?.text = NativeBridge.getNativeString(NativeBridge.S237)
    bubbleView.findViewById<Switch>(R.id.esp_ignore_knocked_switch)?.text = NativeBridge.getNativeString(NativeBridge.S238)
    bubbleView.findViewById<Switch>(R.id.esp_team_switch)?.text = NativeBridge.getNativeString(NativeBridge.S239)

    bubbleView.findViewById<TextView>(R.id.tv_title_esp_info)?.text = NativeBridge.getNativeString(NativeBridge.S240)
    bubbleView.findViewById<Switch>(R.id.esp_name_switch)?.text = NativeBridge.getNativeString(NativeBridge.S242)
    bubbleView.findViewById<Switch>(R.id.esp_distance_switch)?.text = NativeBridge.getNativeString(NativeBridge.S243)

    bubbleView.findViewById<TextView>(R.id.tv_title_esp_custom)?.text = NativeBridge.getNativeString(NativeBridge.S245)
    bubbleView.findViewById<Switch>(R.id.esp_rgb_switch)?.text = NativeBridge.getNativeString(NativeBridge.S246)

    // ESP (master: busca PID) + ESP Box / ESP Skeleton / ESP Línea
    val espSwitch = bubbleView.findViewById<Switch>(R.id.esp_switch)
    val espBoxSwitch = bubbleView.findViewById<Switch>(R.id.esp_box_switch)
    val espSkeletonSwitch = bubbleView.findViewById<Switch>(R.id.esp_skeleton_switch)
    val espLineSwitch = bubbleView.findViewById<Switch>(R.id.esp_line_switch)
    val espStatus = bubbleView.findViewById<TextView>(R.id.esp_status)
    val espColorSeekbar = bubbleView.findViewById<SeekBar>(R.id.esp_color_seekbar)
    val espBoxColorStatus = bubbleView.findViewById<TextView>(R.id.esp_box_color_status)
    val espBoxColorSeekbar = bubbleView.findViewById<SeekBar>(R.id.esp_box_color_seekbar)
    val espRgbSwitch = bubbleView.findViewById<Switch>(R.id.esp_rgb_switch)
    val espOriginStatus = bubbleView.findViewById<TextView>(R.id.esp_origin_status)
    val espOriginSeekbar = bubbleView.findViewById<SeekBar>(R.id.esp_origin_seekbar)
    val espWidthStatus = bubbleView.findViewById<TextView>(R.id.esp_width_status)
    val espWidthSeekbar = bubbleView.findViewById<SeekBar>(R.id.esp_width_seekbar)
    val espGlowSwitch = bubbleView.findViewById<Switch>(R.id.esp_glow_switch)
    val espCornerSwitch = bubbleView.findViewById<Switch>(R.id.esp_corner_switch)
    val espFullBoxSwitch = bubbleView.findViewById<Switch>(R.id.esp_fullbox_switch)
    val esp3dBoxSwitch = bubbleView.findViewById<Switch>(R.id.esp_3d_box_switch)
    val espClosestGlowSwitch = bubbleView.findViewById<Switch>(R.id.esp_closest_glow_switch)
    val espMinimapSwitch = bubbleView.findViewById<Switch>(R.id.esp_minimap_switch)
    val espHealthSwitch = bubbleView.findViewById<Switch>(R.id.esp_health_switch)
    val espWeaponSwitch = bubbleView.findViewById<Switch>(R.id.esp_weapon_switch)
    val espTeamSwitch = bubbleView.findViewById<Switch>(R.id.esp_team_switch)
    val espNameSwitch = bubbleView.findViewById<Switch>(R.id.esp_name_switch)
    val espDistanceSwitch = bubbleView.findViewById<Switch>(R.id.esp_distance_switch)
    val espIgnoreKnockedSwitch = bubbleView.findViewById<Switch>(R.id.esp_ignore_knocked_switch)
    val espCountSwitch = bubbleView.findViewById<Switch>(R.id.esp_count_switch)
    val espGlowColorStatus = bubbleView.findViewById<TextView>(R.id.esp_glow_color_status)
    val espGlowColorSeekbar = bubbleView.findViewById<SeekBar>(R.id.esp_glow_color_seekbar)
    val espCornerColorStatus = bubbleView.findViewById<TextView>(R.id.esp_corner_color_status)
    val espCornerColorSeekbar = bubbleView.findViewById<SeekBar>(R.id.esp_corner_color_seekbar)
    val espFullBoxColorStatus = bubbleView.findViewById<TextView>(R.id.esp_fullbox_color_status)
    val espFullBoxColorSeekbar = bubbleView.findViewById<SeekBar>(R.id.esp_fullbox_color_seekbar)

    val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)

    fun bindEspColorControl(
        seekBar: SeekBar?,
        status: TextView?,
        preferenceKey: String,
        defaultColor: Int,
        label: String,
        applyColor: (Int) -> Unit
    ) {
        val saved = prefs.getInt(preferenceKey, defaultColor).coerceIn(0, 7)
        seekBar?.progress = saved
        status?.text = "$label: ${espColorNames[saved]}"
        status?.setTextColor(espColorValues[saved])
        applyColor(espColorValues[saved])
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val idx = progress.coerceIn(0, 7)
                if (fromUser) prefs.edit().putInt(preferenceKey, idx).apply()
                status?.text = "$label: ${espColorNames[idx]}"
                status?.setTextColor(espColorValues[idx])
                applyColor(espColorValues[idx])
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    bindEspColorControl(
        espColorSeekbar,
        espStatus,
        NativeBridge.getNativeString(NativeBridge.S197),
        1,
        "Línea"
    ) { espOverlayView?.lineColor = it }
    bindEspColorControl(
        espBoxColorSeekbar,
        espBoxColorStatus,
        "pref_esp_box_color",
        2,
        "Box"
    ) { espOverlayView?.boxColor = it }
    bindEspColorControl(
        espGlowColorSeekbar,
        espGlowColorStatus,
        "pref_esp_glow_color",
        3,
        "Glow"
    ) { espOverlayView?.glowColor = it }
    bindEspColorControl(
        espCornerColorSeekbar,
        espCornerColorStatus,
        "pref_esp_corner_color",
        7,
        "Corner"
    ) { espOverlayView?.cornerColor = it }
    bindEspColorControl(
        espFullBoxColorSeekbar,
        espFullBoxColorStatus,
        "pref_esp_fullbox_color",
        4,
        "FullBox"
    ) { espOverlayView?.fullBoxColor = it }

    val savedRgb = prefs.getBoolean(NativeBridge.getNativeString(NativeBridge.S198), false)
    espRgbSwitch?.isChecked = savedRgb

    val savedOrigin = prefs.getInt(NativeBridge.getNativeString(NativeBridge.S199), 0).coerceIn(0, 2)
    espOriginSeekbar?.progress = savedOrigin
    espOriginStatus?.text = "${NativeBridge.getNativeString(NativeBridge.S166)}${espOriginNames[savedOrigin]}"

    val savedWidth = (prefs.getInt(NativeBridge.getNativeString(NativeBridge.S200), 3).coerceIn(1, 10) - 1)
    espWidthSeekbar?.progress = savedWidth
    espWidthStatus?.text = "${NativeBridge.getNativeString(NativeBridge.S167)}${savedWidth + 1}${NativeBridge.getNativeString(NativeBridge.S168)}"

    fun setEspMode(skeleton: Boolean, line: Boolean) {
        espOverlayView?.drawSkeleton = skeleton
        espOverlayView?.drawLines = line
    }

    espRgbSwitch?.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean(NativeBridge.getNativeString(NativeBridge.S198), checked).apply()
        espOverlayView?.rgbMode = checked
    }

    espOriginSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            val idx = progress.coerceIn(0, 2)
            if (fromUser) {
                prefs.edit().putInt(NativeBridge.getNativeString(NativeBridge.S199), idx).apply()
                espOriginStatus?.text = "${NativeBridge.getNativeString(NativeBridge.S166)}${espOriginNames[idx]}"
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
                prefs.edit().putInt(NativeBridge.getNativeString(NativeBridge.S200), px).apply()
                espWidthStatus?.text = "${NativeBridge.getNativeString(NativeBridge.S167)}${px}${NativeBridge.getNativeString(NativeBridge.S168)}"
            }
            espOverlayView?.lineWidth = px.toFloat()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })

    espBoxSwitch?.isChecked = false
    espSkeletonSwitch?.isChecked = false
    espLineSwitch?.isChecked = false
    espTeamSwitch?.isChecked = false
    espNameSwitch?.isChecked = false
    espDistanceSwitch?.isChecked = false
    espIgnoreKnockedSwitch?.isChecked = false

    val savedCount = prefs.getBoolean(NativeBridge.getNativeString(NativeBridge.S201), false)
    espCountSwitch?.isChecked = savedCount

    // Los estados individuales son configuración. Sólo ESP Master autoriza que
    // lleguen al lector y al dibujado, incluso si el overlay existe por Chams/Aim Visible.
    fun applyEspMasterState(masterEnabled: Boolean) {
        espOverlayView?.apply {
            espMasterEnabled = masterEnabled
            drawBox = masterEnabled && espBoxSwitch?.isChecked == true
            drawSkeleton = masterEnabled && espSkeletonSwitch?.isChecked == true
            drawLines = masterEnabled && espLineSwitch?.isChecked == true
            drawGlow = masterEnabled && espGlowSwitch?.isChecked == true
            drawCornerBox = masterEnabled && espCornerSwitch?.isChecked == true
            drawFullBox = masterEnabled && espFullBoxSwitch?.isChecked == true
            draw3dBox = masterEnabled && esp3dBoxSwitch?.isChecked == true
            drawClosestGlowLine = masterEnabled && espClosestGlowSwitch?.isChecked == true
            showMinimap = masterEnabled && espMinimapSwitch?.isChecked == true
            drawHealth = masterEnabled && espHealthSwitch?.isChecked == true
            drawWeapon = masterEnabled && espWeaponSwitch?.isChecked == true
            drawTeam = masterEnabled && espTeamSwitch?.isChecked == true
            drawName = masterEnabled && espNameSwitch?.isChecked == true
            drawDistance = masterEnabled && espDistanceSwitch?.isChecked == true
            ignoreKnocked = masterEnabled && espIgnoreKnockedSwitch?.isChecked == true
            showCount = masterEnabled && espCountSwitch?.isChecked == true
        }
    }

    val espMasterControlledSwitches = listOf(
        espBoxSwitch,
        espSkeletonSwitch,
        espLineSwitch,
        espCountSwitch,
        espIgnoreKnockedSwitch,
        espTeamSwitch,
        espNameSwitch,
        espDistanceSwitch,
        espHealthSwitch,
        espWeaponSwitch,
        espGlowSwitch,
        espCornerSwitch,
        espFullBoxSwitch,
        esp3dBoxSwitch,
        espClosestGlowSwitch,
        espMinimapSwitch
    )

    fun updateEspMasterControls(masterEnabled: Boolean) {
        espMasterControlledSwitches.forEach { featureSwitch ->
            featureSwitch?.isEnabled = masterEnabled
            featureSwitch?.alpha = if (masterEnabled) 1f else 0.45f
        }
    }

    fun applyCurrentEspSelection() {
        applyEspMasterState(espSwitch?.isChecked == true && espOverlayView != null)
    }

    listOf(espBoxSwitch, espSkeletonSwitch, espLineSwitch, espTeamSwitch,
        espNameSwitch, espDistanceSwitch, espIgnoreKnockedSwitch).forEach { featureSwitch ->
        featureSwitch?.setOnCheckedChangeListener { _, _ -> applyCurrentEspSelection() }
    }

    fun bindEspV2Switch(
        featureSwitch: Switch?,
        preferenceKey: String
    ) {
        val saved = prefs.getBoolean(preferenceKey, false)
        featureSwitch?.isChecked = saved
        featureSwitch?.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(preferenceKey, checked).apply()
            applyCurrentEspSelection()
        }
    }

    bindEspV2Switch(espGlowSwitch, "pref_esp_glow")
    bindEspV2Switch(espCornerSwitch, "pref_esp_corner")
    bindEspV2Switch(espFullBoxSwitch, "pref_esp_fullbox")
    bindEspV2Switch(esp3dBoxSwitch, "pref_esp_3d_box")
    bindEspV2Switch(espClosestGlowSwitch, "pref_esp_closest_glow")
    bindEspV2Switch(espMinimapSwitch, "pref_esp_minimap")
    bindEspV2Switch(espHealthSwitch, "pref_esp_health")
    bindEspV2Switch(espWeaponSwitch, "pref_esp_weapon")

    espCountSwitch?.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean(NativeBridge.getNativeString(NativeBridge.S201), checked).apply()
        applyCurrentEspSelection()
    }

    // ESP Master es la única puerta de entrada al conjunto ESP. Apagarlo no
    // borra las selecciones: sólo las deshabilita y detiene su lectura/dibujado.
    espSwitch?.apply {
        isChecked = false
        setOnCheckedChangeListener { _, checked ->
            if (suppressEspMasterListener) return@setOnCheckedChangeListener
            if (checked) startEspOverlay()
            val active = isChecked && espOverlayView != null
            updateEspMasterControls(active)
            applyEspMasterState(active)
            if (!active) {
                val neededByAnotherFeature =
                    bubbleView.findViewById<Switch>(R.id.aim_visible_switch)?.isChecked == true ||
                    bubbleView.findViewById<Switch>(R.id.chams_player_switch)?.isChecked == true ||
                    bubbleView.findViewById<Switch>(R.id.chams_weapon_switch)?.isChecked == true
                if (!neededByAnotherFeature) stopEspOverlay()
            }
        }
    }
    updateEspMasterControls(false)
    applyEspMasterState(false)

    // ==========================================
    // SUB-TABS VISUALES: [ ESP | ESP V2 | CHAMS ]
    // ==========================================
    fun selectVisualSubtab(index: Int) {
        selectedVisualSubtab = index.coerceIn(0, 2)
        val buttons = arrayOf(btnSubtabEsp, btnSubtabEspV2, btnSubtabChams)
        buttons.forEachIndexed { buttonIndex, button ->
            button?.setBackgroundResource(
                if (buttonIndex == selectedVisualSubtab) R.drawable.shape_tab_active
                else android.R.color.transparent
            )
            button?.setTextColor(
                Color.parseColor(if (buttonIndex == selectedVisualSubtab) "#00E5FF" else "#8A9BA8")
            )
        }
        espSection?.visibility = if (selectedVisualSubtab == 0) View.VISIBLE else View.GONE
        espV2Section?.visibility = if (selectedVisualSubtab == 1) View.VISIBLE else View.GONE
        chamsSection?.visibility = if (selectedVisualSubtab == 2) View.VISIBLE else View.GONE
    }

    btnSubtabEsp?.setOnClickListener { selectVisualSubtab(0) }
    btnSubtabEspV2?.setOnClickListener { selectVisualSubtab(1) }
    btnSubtabChams?.setOnClickListener { selectVisualSubtab(2) }

    // ==========================================
    // CONFIGURACIÓN DE CHAMS (PERSONAJE & ARMAS)
    // ==========================================
    val chamsPlayerSwitch = bubbleView.findViewById<Switch>(R.id.chams_player_switch)
    val chamsPlayerGlowSwitch = bubbleView.findViewById<Switch>(R.id.chams_player_glow_switch)
    val chamsPlayerWireframeSwitch = bubbleView.findViewById<Switch>(R.id.chams_player_wireframe_switch)
    val chamsWeaponSwitch = bubbleView.findViewById<Switch>(R.id.chams_weapon_switch)
    val chamsThroughWallSwitch = bubbleView.findViewById<Switch>(R.id.chams_through_wall_switch)
    val chamsRgbSwitch = bubbleView.findViewById<Switch>(R.id.chams_rgb_switch)
    val chamsStatus = bubbleView.findViewById<TextView>(R.id.chams_status)
    val chamsColorSeekbar = bubbleView.findViewById<SeekBar>(R.id.chams_color_seekbar)
    val chamsModeStatus = bubbleView.findViewById<TextView>(R.id.chams_mode_status)
    val chamsModeSeekbar = bubbleView.findViewById<SeekBar>(R.id.chams_mode_seekbar)

    val chamsModeNames = arrayOf("Modo: Sólido Intenso", "Modo: Resplandor / Glow", "Modo: Malla / Wireframe", "Modo: Transparente")

    val savedChamsColor = prefs.getInt("pref_chams_color", 1).coerceIn(0, 7)
    chamsColorSeekbar?.progress = savedChamsColor
    chamsStatus?.text = "${NativeBridge.getNativeString(NativeBridge.S165)}${espColorNames[savedChamsColor]}"
    chamsStatus?.setTextColor(espColorValues[savedChamsColor])
    espOverlayView?.chamsColor = espColorValues[savedChamsColor]

    val savedChamsMode = prefs.getInt("pref_chams_mode", 0).coerceIn(0, 3)
    chamsModeSeekbar?.progress = savedChamsMode
    chamsModeStatus?.text = chamsModeNames[savedChamsMode]
    espOverlayView?.chamsMode = savedChamsMode

    val savedChamsRgb = prefs.getBoolean("pref_chams_rgb", false)
    chamsRgbSwitch?.isChecked = savedChamsRgb
    espOverlayView?.chamsRgb = savedChamsRgb

    fun ensureOverlayRunning() {
        if (espOverlayView == null) {
            startEspOverlay()
        } else {
            espOverlayView?.chamsPlayer = chamsPlayerSwitch?.isChecked ?: false
            espOverlayView?.chamsWeapon = chamsWeaponSwitch?.isChecked ?: false
            espOverlayView?.chamsPlayerGlow = chamsPlayerGlowSwitch?.isChecked ?: false
            espOverlayView?.chamsPlayerWireframe = chamsPlayerWireframeSwitch?.isChecked ?: false
            espOverlayView?.chamsThroughWalls = chamsThroughWallSwitch?.isChecked ?: true
            espOverlayView?.chamsRgb = chamsRgbSwitch?.isChecked ?: false
            val cIdx = prefs.getInt("pref_chams_color", 1).coerceIn(0, 7)
            espOverlayView?.chamsColor = espColorValues[cIdx]
            espOverlayView?.chamsMode = prefs.getInt("pref_chams_mode", 0).coerceIn(0, 3)
        }
    }

    chamsPlayerSwitch?.setOnCheckedChangeListener { _, checked ->
        if (checked) {
            ensureOverlayRunning()
        }
        espOverlayView?.chamsPlayer = checked
    }

    chamsPlayerGlowSwitch?.setOnCheckedChangeListener { _, checked ->
        espOverlayView?.chamsPlayerGlow = checked
    }

    chamsPlayerWireframeSwitch?.setOnCheckedChangeListener { _, checked ->
        espOverlayView?.chamsPlayerWireframe = checked
    }

    chamsWeaponSwitch?.setOnCheckedChangeListener { _, checked ->
        if (checked) {
            ensureOverlayRunning()
        }
        espOverlayView?.chamsWeapon = checked
    }

    chamsThroughWallSwitch?.setOnCheckedChangeListener { _, checked ->
        espOverlayView?.chamsThroughWalls = checked
    }

    chamsRgbSwitch?.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean("pref_chams_rgb", checked).apply()
        espOverlayView?.chamsRgb = checked
    }

    chamsColorSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            val idx = progress.coerceIn(0, 7)
            if (fromUser) {
                prefs.edit().putInt("pref_chams_color", idx).apply()
            }
            chamsStatus?.text = "${NativeBridge.getNativeString(NativeBridge.S165)}${espColorNames[idx]}"
            chamsStatus?.setTextColor(espColorValues[idx])
            espOverlayView?.chamsColor = espColorValues[idx]
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })

    chamsModeSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            val idx = progress.coerceIn(0, 3)
            if (fromUser) {
                prefs.edit().putInt("pref_chams_mode", idx).apply()
            }
            chamsModeStatus?.text = chamsModeNames[idx]
            espOverlayView?.chamsMode = idx
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })

    // Enlaza un switch persistente con un controlador nativo. Las búsquedas de PID y
    // libil2cpp se ejecutan fuera del hilo de UI y el switch se revierte si no hay acceso.
    fun bindNativeFeatureSwitch(
        featureSwitch: Switch?,
        preferenceKey: String,
        label: String,
        setter: (Boolean) -> Boolean,
        beforeChange: (Boolean) -> Unit = {}
    ) {
        if (featureSwitch == null) return
        var suppressListener = false
        var requestId = 0

        fun applyFeature(enabled: Boolean, announce: Boolean) {
            val currentRequest = ++requestId
            Thread {
                val applied = try {
                    setter(enabled)
                } catch (_: Throwable) {
                    false
                }
                runOnUiThread {
                    if (currentRequest != requestId) return@runOnUiThread
                    if (enabled && !applied) {
                        suppressListener = true
                        featureSwitch.isChecked = false
                        suppressListener = false
                        prefs.edit().putBoolean(preferenceKey, false).apply()
                        Toast.makeText(
                            this@BubbleService,
                            "$label: no se encontró la memoria del juego",
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (announce) {
                        Toast.makeText(
                            this@BubbleService,
                            if (enabled) "$label: Activado" else "$label: Desactivado",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.start()
        }

        val saved = prefs.getBoolean(preferenceKey, false)
        featureSwitch.isChecked = saved
        featureSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressListener) return@setOnCheckedChangeListener
            beforeChange(checked)
            prefs.edit().putBoolean(preferenceKey, checked).apply()
            applyFeature(checked, true)
        }
        if (saved) applyFeature(true, false)
    }

    // ==========================================
    // MENÚ DE COMBATE / AIM (CASCO TÁCTICO)
    // ==========================================
    val silentAimSwitch = bubbleView.findViewById<Switch>(R.id.silent_aim_switch)
    val aimbotSwitch = bubbleView.findViewById<Switch>(R.id.aimbot_switch)
    val noReloadSwitch = bubbleView.findViewById<Switch>(R.id.no_reload_switch)
    val aimVisibleSwitch = bubbleView.findViewById<Switch>(R.id.aim_visible_switch)
    val aimbotTargetSpinner = bubbleView.findViewById<Spinner>(R.id.aimbot_target_spinner)
    val aimVisibleFovSeekbar = bubbleView.findViewById<SeekBar>(R.id.aim_visible_fov_seekbar)
    val aimVisibleFovStatus = bubbleView.findViewById<TextView>(R.id.aim_visible_fov_status)

    val aimbotTargets = arrayOf("Head", "Neck", "Root", "Hip", "Foot")
    val targetAdapter = object : ArrayAdapter<String>(
        this@BubbleService,
        android.R.layout.simple_spinner_item,
        aimbotTargets
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return super.getView(position, convertView, parent).also { view ->
                (view as? TextView)?.apply {
                    setTextColor(Color.WHITE)
                    textSize = 10f
                }
            }
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            return super.getDropDownView(position, convertView, parent).also { view ->
                (view as? TextView)?.apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#16242D"))
                    textSize = 11f
                    setPadding(18, 12, 18, 12)
                }
            }
        }
    }.apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    val savedAimbotTarget = prefs.getInt("pref_aimbot_target", 0).coerceIn(0, 4)
    aimbotTargetSpinner?.adapter = targetAdapter
    aimbotTargetSpinner?.setSelection(savedAimbotTarget, false)
    NativeBridge.setAimbotTarget(savedAimbotTarget)
    aimbotTargetSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val selected = position.coerceIn(0, 4)
            prefs.edit().putInt("pref_aimbot_target", selected).apply()
            NativeBridge.setAimbotTarget(selected)
            (view as? TextView)?.setTextColor(Color.WHITE)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    val savedAimVisibleFov = prefs.getInt("pref_aim_visible_fov", 200).coerceIn(50, 500)
    aimVisibleFovSeekbar?.progress = savedAimVisibleFov - 50
    aimVisibleFovStatus?.text = "Aim Visible FOV: $savedAimVisibleFov px"
    NativeBridge.setAimVisibleFov(savedAimVisibleFov)
    espOverlayView?.aimVisibleFovRadius = savedAimVisibleFov.toFloat()
    aimVisibleFovSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            val fov = (progress + 50).coerceIn(50, 500)
            aimVisibleFovStatus?.text = "Aim Visible FOV: $fov px"
            NativeBridge.setAimVisibleFov(fov)
            espOverlayView?.aimVisibleFovRadius = fov.toFloat()
            if (fromUser) prefs.edit().putInt("pref_aim_visible_fov", fov).apply()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })

    bindNativeFeatureSwitch(
        silentAimSwitch,
        "pref_silent_aim",
        "Silent Aim",
        NativeBridge::setSilentAim
    )

    bindNativeFeatureSwitch(
        noReloadSwitch,
        "pref_no_reload",
        "NoReload",
        NativeBridge::setNoReload
    )

    // Aimbot ya no toca la VPN ni agrega retardo UDP. La versión anterior elevaba
    // el ping porque este switch estaba conectado por error al filtro de paquetes.
    NativeBridge.setSelectiveUdpDelay(false, 1)
    isAimbotPacketDelayActive = false
    if (prefs.getBoolean("pref_aimbot_switch", false) &&
        prefs.getBoolean("pref_aim_visible", false)) {
        prefs.edit().putBoolean("pref_aimbot_switch", false).apply()
    }
    bindNativeFeatureSwitch(
        aimbotSwitch,
        "pref_aimbot_switch",
        "Aimbot",
        NativeBridge::setCameraAimbot
    ) { enabled ->
        if (enabled && aimVisibleSwitch?.isChecked == true) {
            aimVisibleSwitch.isChecked = false
        }
    }

    val savedAimVisible = prefs.getBoolean("pref_aim_visible", false)
    aimVisibleSwitch?.isChecked = savedAimVisible
    aimVisibleSwitch?.setOnCheckedChangeListener { _, checked ->
        if (aimVisibleSwitchBusy) return@setOnCheckedChangeListener
        prefs.edit().putBoolean("pref_aim_visible", checked).apply()
        if (checked) {
            if (aimbotSwitch?.isChecked == true) aimbotSwitch.isChecked = false
            ensureOverlayRunning()
            espOverlayView?.drawAimVisibleFov = true
            Thread {
                val started = NativeBridge.setAimVisible(true)
                runOnUiThread {
                    if (started) {
                        Toast.makeText(this@BubbleService, "Aim Visible: Activado", Toast.LENGTH_SHORT).show()
                    } else {
                        aimVisibleSwitchBusy = true
                        aimVisibleSwitch?.isChecked = false
                        aimVisibleSwitchBusy = false
                        prefs.edit().putBoolean("pref_aim_visible", false).apply()
                        espOverlayView?.drawAimVisibleFov = false
                        Toast.makeText(this@BubbleService, "Aim Visible: no se encontró la memoria del juego", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        } else {
            NativeBridge.setAimVisible(false)
            espOverlayView?.drawAimVisibleFov = false
            Toast.makeText(this@BubbleService, "Aim Visible: Desactivado", Toast.LENGTH_SHORT).show()
        }
    }
    if (savedAimVisible) {
        ensureOverlayRunning()
        espOverlayView?.drawAimVisibleFov = true
        Thread {
            if (!NativeBridge.setAimVisible(true)) {
                runOnUiThread {
                    aimVisibleSwitchBusy = true
                    aimVisibleSwitch?.isChecked = false
                    aimVisibleSwitchBusy = false
                    prefs.edit().putBoolean("pref_aim_visible", false).apply()
                    espOverlayView?.drawAimVisibleFov = false
                }
            }
        }.start()
    }

    // ==========================================
    // MENÚ DE MOVIMIENTO / VUELO (AVIÓN)
    // ==========================================
    val flyHackSwitch = bubbleView.findViewById<Switch>(R.id.fly_hack_switch)
    val ghostHackSwitch = bubbleView.findViewById<Switch>(R.id.ghost_hack_switch)
    val enemyPullSwitch = bubbleView.findViewById<Switch>(R.id.enemy_pull_switch)
    val teleportDropSwitch = bubbleView.findViewById<Switch>(R.id.teleport_drop_switch)

    bindNativeFeatureSwitch(
        flyHackSwitch,
        "pref_fly_hack",
        "Fly Hack",
        NativeBridge::setFlyHack
    )

    val savedGhostHack = prefs.getBoolean("pref_ghost_hack", false)
    ghostHackSwitch?.isChecked = savedGhostHack
    if (savedGhostHack) {
        showGhostFloatingSwitch()
    }
    ghostHackSwitch?.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean("pref_ghost_hack", checked).apply()
        if (checked) {
            showGhostFloatingSwitch()
            Toast.makeText(this@BubbleService, "Ghost Hack activado (Switch flotante en pantalla)", Toast.LENGTH_SHORT).show()
        } else {
            hideGhostFloatingSwitch()
            Toast.makeText(this@BubbleService, "Ghost Hack desactivado", Toast.LENGTH_SHORT).show()
        }
    }

    val savedTeleportDropBubble = prefs.getBoolean("pref_teleport_drop", false)
    teleportDropSwitch?.visibility = View.VISIBLE
    teleportDropSwitch?.isChecked = savedTeleportDropBubble
    if (savedTeleportDropBubble) showTeleportDropFloatingControl()
    teleportDropSwitch?.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean("pref_teleport_drop", checked).apply()
        if (checked) {
            showTeleportDropFloatingControl()
            Toast.makeText(
                this@BubbleService,
                "Teleport Drop Root: control flotante listo",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            hideTeleportDropFloatingControl(replayCaptured = true)
            Toast.makeText(
                this@BubbleService,
                "Teleport Drop Root: desactivado",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Este switch únicamente muestra el selector flotante. Enemy Pull permanece
    // nativamente apagado hasta que el usuario elige una de sus cuatro direcciones.
    var suppressEnemyPullSwitch = false
    val savedEnemyPullBubble = prefs.getBoolean("pref_enemy_pull", false)
    enemyPullSwitch?.isChecked = savedEnemyPullBubble
    if (savedEnemyPullBubble && enemyPullFloatingView == null) {
        enemyPullDirection = 0
        NativeBridge.setEnemyPullDirection(0)
        NativeBridge.setEnemyPull(false)
        if (!showEnemyPullFloatingControl()) {
            prefs.edit().putBoolean("pref_enemy_pull", false).apply()
            enemyPullSwitch?.isChecked = false
        }
    }
    enemyPullSwitch?.setOnCheckedChangeListener { _, checked ->
        if (suppressEnemyPullSwitch) return@setOnCheckedChangeListener
        prefs.edit().putBoolean("pref_enemy_pull", checked).apply()
        if (checked) {
            enemyPullDirection = 0
            NativeBridge.setEnemyPullDirection(0)
            NativeBridge.setEnemyPull(false)
            if (showEnemyPullFloatingControl()) {
                Toast.makeText(
                    this@BubbleService,
                    "Enemy Pull: selecciona una dirección",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                suppressEnemyPullSwitch = true
                enemyPullSwitch.isChecked = false
                suppressEnemyPullSwitch = false
                prefs.edit().putBoolean("pref_enemy_pull", false).apply()
            }
        } else {
            hideEnemyPullFloatingControl()
            Toast.makeText(this@BubbleService, "Enemy Pull: Desactivado", Toast.LENGTH_SHORT).show()
        }
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
                        .putInt(NativeBridge.getNativeString(NativeBridge.S203), params.x)
                        .putInt(NativeBridge.getNativeString(NativeBridge.S204), params.y)
                        .apply()
                true
            }
            else -> false
        }
    }
}

// Adaptación Android del filtro WinDivert recibido: retrasa únicamente UDP saliente
// con payload de 50..150 bytes. El túnel VPN ya está limitado al paquete del juego.
private fun setAimbotPacketDelay(active: Boolean) {
    if (isAimbotPacketDelayActive == active) return

    NativeBridge.setSelectiveUdpDelay(active, 1)
    isAimbotPacketDelayActive = active

    if (active) {
        // No reiniciar un túnel que ya está siendo usado por Freeze.
        if (!AntigravityFirewall.isTunnelRunning && !LagController.fakeLagActivo) {
            val prefs = getSharedPreferences(
                NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
                Context.MODE_PRIVATE
            )
            val selectedPackage = targetPackage ?: if (prefs.getBoolean("use_ff_max", false)) {
                NativeBridge.getNativeString(NativeBridge.S98)
            } else {
                NativeBridge.getNativeString(NativeBridge.S99)
            }
            val vpnIntent = Intent(this, AntigravityFirewall::class.java).apply {
                putExtra(NativeBridge.getNativeString(NativeBridge.S92), selectedPackage)
            }
            startService(vpnIntent)
        }
    } else {
        maybeStopSharedVpn()
    }
}

private fun runOnUiThread(action: () -> Unit) {
    android.os.Handler(mainLooper).post(action)
}

private val espColorNames by lazy {
    arrayOf(
        NativeBridge.getNativeString(NativeBridge.S186),
        NativeBridge.getNativeString(NativeBridge.S187),
        NativeBridge.getNativeString(NativeBridge.S188),
        NativeBridge.getNativeString(NativeBridge.S189),
        NativeBridge.getNativeString(NativeBridge.S190),
        NativeBridge.getNativeString(NativeBridge.S191),
        NativeBridge.getNativeString(NativeBridge.S192),
        NativeBridge.getNativeString(NativeBridge.S193)
    )
}

private val espOriginNames by lazy {
    arrayOf(
        NativeBridge.getNativeString(NativeBridge.S194),
        NativeBridge.getNativeString(NativeBridge.S195),
        NativeBridge.getNativeString(NativeBridge.S196)
    )
}

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
        Toast.makeText(this@BubbleService, NativeBridge.getNativeString(NativeBridge.S184), Toast.LENGTH_SHORT).show()
        setEspSwitchSilently(false)
        return
    }
    if (espOverlayView != null) return
    val overlay = EspOverlayView(this)
    val prefs = getSharedPreferences(NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME), Context.MODE_PRIVATE)
    val espMasterEnabled = bubbleView.findViewById<Switch>(R.id.esp_switch)?.isChecked == true
    overlay.espMasterEnabled = espMasterEnabled
    val colorIdx = prefs.getInt(NativeBridge.getNativeString(NativeBridge.S197), 1).coerceIn(0, 7)
    overlay.lineColor = espColorValues[colorIdx]
    overlay.boxColor = espColorValues[prefs.getInt("pref_esp_box_color", 2).coerceIn(0, 7)]
    overlay.glowColor = espColorValues[prefs.getInt("pref_esp_glow_color", 3).coerceIn(0, 7)]
    overlay.cornerColor = espColorValues[prefs.getInt("pref_esp_corner_color", 7).coerceIn(0, 7)]
    overlay.fullBoxColor = espColorValues[prefs.getInt("pref_esp_fullbox_color", 4).coerceIn(0, 7)]
    overlay.rgbMode = prefs.getBoolean(NativeBridge.getNativeString(NativeBridge.S198), false)
    overlay.lineOrigin = prefs.getInt(NativeBridge.getNativeString(NativeBridge.S199), 0).coerceIn(0, 2)
    overlay.lineWidth = prefs.getInt(NativeBridge.getNativeString(NativeBridge.S200), 3).coerceIn(1, 10).toFloat()
    overlay.showCount = espMasterEnabled && prefs.getBoolean(NativeBridge.getNativeString(NativeBridge.S201), false)
    overlay.drawBox = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_box_switch)?.isChecked == true
    overlay.drawSkeleton = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_skeleton_switch)?.isChecked == true
    overlay.drawLines = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_line_switch)?.isChecked == true
    overlay.drawGlow = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_glow_switch)?.isChecked == true
    overlay.drawCornerBox = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_corner_switch)?.isChecked == true
    overlay.drawFullBox = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_fullbox_switch)?.isChecked == true
    overlay.draw3dBox = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_3d_box_switch)?.isChecked == true
    overlay.drawClosestGlowLine = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_closest_glow_switch)?.isChecked == true
    overlay.showMinimap = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_minimap_switch)?.isChecked == true
    overlay.drawHealth = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_health_switch)?.isChecked == true
    overlay.drawWeapon = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_weapon_switch)?.isChecked == true
    overlay.drawTeam = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_team_switch)?.isChecked == true
    overlay.drawName = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_name_switch)?.isChecked == true
    overlay.drawDistance = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_distance_switch)?.isChecked == true
    overlay.ignoreKnocked = espMasterEnabled && bubbleView.findViewById<Switch>(R.id.esp_ignore_knocked_switch)?.isChecked == true

    // Inicializar configuración de Chams
    overlay.chamsPlayer = bubbleView.findViewById<Switch>(R.id.chams_player_switch)?.isChecked ?: false
    overlay.chamsPlayerGlow = bubbleView.findViewById<Switch>(R.id.chams_player_glow_switch)?.isChecked ?: false
    overlay.chamsPlayerWireframe = bubbleView.findViewById<Switch>(R.id.chams_player_wireframe_switch)?.isChecked ?: false
    overlay.chamsWeapon = bubbleView.findViewById<Switch>(R.id.chams_weapon_switch)?.isChecked ?: false
    overlay.chamsThroughWalls = bubbleView.findViewById<Switch>(R.id.chams_through_wall_switch)?.isChecked ?: true
    val chamsColorIdx = prefs.getInt("pref_chams_color", 1).coerceIn(0, 7)
    overlay.chamsColor = espColorValues[chamsColorIdx]
    overlay.chamsMode = prefs.getInt("pref_chams_mode", 0).coerceIn(0, 3)
    overlay.chamsRgb = prefs.getBoolean("pref_chams_rgb", false)
    overlay.drawAimVisibleFov = bubbleView.findViewById<Switch>(R.id.aim_visible_switch)?.isChecked ?: false
    overlay.aimVisibleFovRadius = prefs.getInt("pref_aim_visible_fov", 200)
        .coerceIn(50, 500).toFloat()
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
        Toast.makeText(this@BubbleService, NativeBridge.getNativeString(NativeBridge.S185), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
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
    suppressEspMasterListener = true
    switch.isChecked = checked
    suppressEspMasterListener = false
}

    private fun expandBubbleMenu() {
        if (isMenuExpanded || !canUseAdvancedFeatures()) return
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
                        if (LicenseEntitlements.hasPaidFeatures(this)) {
                            handler.postDelayed(longClickRunnable, 3000L)
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

                // Reutilizar el túnel si Ghost no-root ya lo mantiene abierto.
                if (!AntigravityFirewall.isTunnelRunning) {
                    val vpnIntent =
                            Intent(this, AntigravityFirewall::class.java).apply {
                                putExtra(NativeBridge.getNativeString(NativeBridge.S92), targetPackage ?: NativeBridge.getNativeString(NativeBridge.S98))
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

                // Ghost no-root conserva su propia ruta aunque Fake Lag se apague.
                maybeStopSharedVpn()
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
        NativeBridge.setSelectiveUdpDelay(false, 1)
        NativeBridge.setAimVisible(false)
        NativeBridge.setSilentAim(false)
        NativeBridge.setEnemyPull(false)
        NativeBridge.setFlyHack(false)
        NativeBridge.setNoReload(false)
        NativeBridge.setCameraAimbot(false)
        isAimbotPacketDelayActive = false
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
        hideGhostFloatingSwitch()
        hideTeleportDropFloatingControl(replayCaptured = false)
        hideEnemyPullFloatingControl()
        NativeBridge.shutdownMemoryAccess()
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

    private fun showGhostFloatingSwitch() {
        if (ghostFloatingView != null || !canUseAdvancedFeatures()) return
        try {
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.floating_ghost_layout, null)
            val ghostParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 300
            }

            var gInitialX = 0
            var gInitialY = 0
            var gTouchX = 0f
            var gTouchY = 0f
            var isClick = false
            var operationBusy = false

            val tvStatus = view.findViewById<TextView>(R.id.tv_ghost_floating_status)
            val ivIcon = view.findViewById<ImageView>(R.id.iv_ghost_floating_icon)

            fun updateGhostUi(active: Boolean) {
                isGhostActive = active
                if (active) {
                    tvStatus?.text = "ON"
                    tvStatus?.setTextColor(Color.parseColor("#39FF14"))
                    ivIcon?.setColorFilter(Color.parseColor("#00E5FF"), android.graphics.PorterDuff.Mode.SRC_IN)
                } else {
                    tvStatus?.text = "OFF"
                    tvStatus?.setTextColor(Color.parseColor("#FF3B30"))
                    ivIcon?.setColorFilter(Color.parseColor("#8A9BA8"), android.graphics.PorterDuff.Mode.SRC_IN)
                }
            }

            updateGhostUi(isGhostActive)

            view.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        gInitialX = ghostParams.x
                        gInitialY = ghostParams.y
                        gTouchX = event.rawX
                        gTouchY = event.rawY
                        isClick = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - gTouchX).toInt()
                        val dy = (event.rawY - gTouchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isClick = false
                        }
                        ghostParams.x = gInitialX + dx
                        ghostParams.y = gInitialY + dy
                        try {
                            windowManager.updateViewLayout(view, ghostParams)
                        } catch (e: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick && !operationBusy) {
                            if (isGhostActive) {
                                updateGhostUi(false)
                                deactivateGhostMode()
                                Toast.makeText(
                                    this@BubbleService,
                                    "Ghost Mode: DESACTIVADO",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                operationBusy = true
                                tvStatus?.text = "..."
                                tvStatus?.setTextColor(Color.parseColor("#FFD740"))
                                activateGhostMode { enabled, rootMode ->
                                    operationBusy = false
                                    updateGhostUi(enabled)
                                    Toast.makeText(
                                        this@BubbleService,
                                        if (enabled) {
                                            "Ghost Mode: ACTIVADO (${if (rootMode) "ROOT" else "NO-ROOT"})"
                                        } else {
                                            "Ghost Mode: no se pudo iniciar"
                                        },
                                        if (enabled) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(view, ghostParams)
            ghostFloatingView = view
            ghostFloatingParams = ghostParams
        } catch (e: Exception) {
            Log.e("BubbleService", "Error mostrando switch flotante de Ghost", e)
        }
    }

    private fun hideGhostFloatingSwitch() {
        deactivateGhostMode()
        ghostFloatingView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {}
        }
        ghostFloatingView = null
        ghostFloatingParams = null
    }

    /** Activa Ghost por una ruta propia según el modo seleccionado en ajustes. */
    private fun activateGhostMode(onComplete: (Boolean, Boolean) -> Unit) {
        if (!canUseAdvancedFeatures()) {
            onComplete(false, false)
            return
        }
        val prefs = getSharedPreferences(
            NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
            Context.MODE_PRIVATE
        )
        val useRoot = prefs.getBoolean("use_root", false)
        val selectedPackage = targetPackage ?: if (prefs.getBoolean("use_ff_max", false)) {
            NativeBridge.getNativeString(NativeBridge.S98)
        } else {
            NativeBridge.getNativeString(NativeBridge.S99)
        }
        val generation = ++ghostOperationGeneration

        Thread {
            val enabled = try {
                if (useRoot) {
                    GhostController.enableRoot(this@BubbleService, selectedPackage)
                } else {
                    if (!GhostController.enableNoRoot(this@BubbleService, selectedPackage)) {
                        return@Thread
                    }
                    if (!AntigravityFirewall.isTunnelRunning) {
                        startService(Intent(this@BubbleService, AntigravityFirewall::class.java).apply {
                            putExtra(NativeBridge.getNativeString(NativeBridge.S92), selectedPackage)
                        })
                        // No mostrar ON hasta que Android entregue realmente el TUN.
                        var attempts = 0
                        while (!AntigravityFirewall.isTunnelRunning && attempts < 30) {
                            Thread.sleep(50)
                            attempts++
                        }
                    }
                    AntigravityFirewall.isTunnelRunning.also { ready ->
                        if (!ready) GhostController.disableNoRoot()
                    }
                }
            } catch (e: Exception) {
                Log.e("BubbleService", "No se pudo activar Ghost", e)
                false
            }

            runOnUiThread {
                if (generation != ghostOperationGeneration || ghostFloatingView == null) {
                    // La burbuja se cerró durante la operación: no dejar reglas huérfanas.
                    Thread {
                        if (useRoot) {
                            GhostController.disableRoot(this@BubbleService, selectedPackage)
                        } else {
                            GhostController.disableNoRoot()
                        }
                    }.start()
                    return@runOnUiThread
                }
                isGhostActive = enabled
                ghostUsesRoot = enabled && useRoot
                ghostTargetPackage = if (enabled) selectedPackage else null
                onComplete(enabled, useRoot)
            }
        }.start()
    }

    /** Apaga solo Ghost. Nunca modifica gLagActive ni la cadena FREEZY_FAKELAG. */
    private fun deactivateGhostMode() {
        ++ghostOperationGeneration
        val wasActive = isGhostActive || GhostController.active
        val usedRoot = ghostUsesRoot || GhostController.usingRoot
        val packageToClean = ghostTargetPackage ?: targetPackage ?: run {
            val prefs = getSharedPreferences(
                NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
                Context.MODE_PRIVATE
            )
            if (prefs.getBoolean("use_ff_max", false)) {
                NativeBridge.getNativeString(NativeBridge.S98)
            } else {
                NativeBridge.getNativeString(NativeBridge.S99)
            }
        }

        isGhostActive = false
        ghostUsesRoot = false
        ghostTargetPackage = null
        if (!wasActive) {
            val currentRootMode = getSharedPreferences(
                NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
                Context.MODE_PRIVATE
            ).getBoolean("use_root", false)
            if (!currentRootMode) {
                try { AntigravityFirewall.setGhostActive(false) } catch (_: Throwable) {}
            }
            return
        }

        if (usedRoot) {
            Thread {
                GhostController.disableRoot(this@BubbleService, packageToClean)
            }.start()
        } else {
            try {
                GhostController.disableNoRoot()
                maybeStopSharedVpn()
            } catch (e: Exception) {
                Log.e("BubbleService", "No se pudo apagar Ghost no-root", e)
            }
        }
    }

    /** Estado NoRoot de Teleport Drop sin propagar fallos de JNI a la UI. */
    private fun nativeTeleportDropState(): Int = try {
        AntigravityFirewall.getTeleportDropState().coerceIn(0, 2)
    } catch (_: Throwable) {
        0
    }

    private fun teleportDropState(): Int {
        return if (teleportDropUsesRoot || RootTeleportDropController.active) {
            if (RootTeleportDropController.active) 1 else 0
        } else {
            nativeTeleportDropState()
        }
    }

    /** Cierra el VPN únicamente cuando ninguna función no-root lo necesita. */
    private fun maybeStopSharedVpn() {
        val ghostNeedsVpn = isGhostActive && !ghostUsesRoot
        if (!isFreezing && !LagController.fakeLagActivo && !ghostNeedsVpn &&
            !isAimbotPacketDelayActive && nativeTeleportDropState() == 0 &&
            AntigravityFirewall.isTunnelRunning) {
            try {
                startService(Intent(this, AntigravityFirewall::class.java).apply {
                    action = NativeBridge.getNativeString(NativeBridge.S91)
                })
            } catch (e: Exception) {
                Log.e("BubbleService", "No se pudo cerrar el VPN compartido", e)
            }
        }
    }

    private fun selectedGamePackage(): String {
        val prefs = getSharedPreferences(
            NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
            Context.MODE_PRIVATE
        )
        return targetPackage ?: if (prefs.getBoolean("use_ff_max", false)) {
            NativeBridge.getNativeString(NativeBridge.S98)
        } else {
            NativeBridge.getNativeString(NativeBridge.S99)
        }
    }

    private fun currentCompatibility(): GameCompatibility.Report {
        return GameCompatibility.inspect(this, selectedGamePackage())
    }

    private fun canUseAdvancedFeatures(): Boolean {
        return LicenseEntitlements.hasPaidFeatures(this) &&
            currentCompatibility().supportsAdvancedFeatures
    }

    private fun startTeleportDropCapture(onComplete: (Boolean) -> Unit) {
        val accessPrefs = getSharedPreferences(
            NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
            Context.MODE_PRIVATE
        )
        if (!canUseAdvancedFeatures()) {
            onComplete(false)
            return
        }
        val generation = ++teleportDropOperationGeneration
        val selectedPackage = selectedGamePackage()
        val useRoot = accessPrefs.getBoolean("use_root", false)
        Thread {
            val ready = try {
                if (useRoot) {
                    RootTeleportDropController.enable(this@BubbleService, selectedPackage)
                } else {
                    if (!AntigravityFirewall.isTunnelRunning) {
                    startService(Intent(this@BubbleService, AntigravityFirewall::class.java).apply {
                        putExtra(NativeBridge.getNativeString(NativeBridge.S92), selectedPackage)
                    })
                    var attempts = 0
                    while (!AntigravityFirewall.isTunnelRunning && attempts < 30) {
                        Thread.sleep(50)
                        attempts++
                    }
                    }
                    AntigravityFirewall.isTunnelRunning.also { tunnelReady ->
                        if (tunnelReady) AntigravityFirewall.setTeleportDropActive(true)
                    }
                }
            } catch (e: Exception) {
                Log.e("BubbleService", "No se pudo preparar Teleport Drop", e)
                false
            }

            if (generation != teleportDropOperationGeneration) {
                if (ready) {
                    if (useRoot) RootTeleportDropController.disable()
                    else try { AntigravityFirewall.cancelTeleportDrop() } catch (_: Throwable) {}
                }
                return@Thread
            }
            runOnUiThread {
                if (generation == teleportDropOperationGeneration) {
                    teleportDropUsesRoot = ready && useRoot
                    onComplete(ready)
                }
            }
        }.start()
    }

    private fun beginTeleportDropReplay(updateUi: ((Int) -> Unit)? = null) {
        val generation = ++teleportDropOperationGeneration
        if (teleportDropUsesRoot || RootTeleportDropController.active) {
            Thread {
                RootTeleportDropController.disable()
                runOnUiThread {
                    if (generation != teleportDropOperationGeneration) return@runOnUiThread
                    teleportDropUsesRoot = false
                    updateUi?.invoke(0)
                }
            }.start()
            return
        }
        try {
            AntigravityFirewall.setTeleportDropActive(false)
        } catch (_: Throwable) {}
        updateUi?.invoke(teleportDropState())

        val monitor = object : Runnable {
            override fun run() {
                if (generation != teleportDropOperationGeneration) return
                val state = teleportDropState()
                updateUi?.invoke(state)
                if (state == 2) {
                    handler.postDelayed(this, 80)
                } else {
                    maybeStopSharedVpn()
                }
            }
        }
        handler.post(monitor)
    }

    private fun showTeleportDropFloatingControl() {
        val accessPrefs = getSharedPreferences(
            NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
            Context.MODE_PRIVATE
        )
        if (teleportDropFloatingView != null ||
            !canUseAdvancedFeatures()) return
        try {
            val view = LayoutInflater.from(this).inflate(
                R.layout.floating_teleport_drop_layout,
                null
            )
            val density = resources.displayMetrics.density
            val prefs = getSharedPreferences(
                NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
                Context.MODE_PRIVATE
            )
            val dropParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("teleport_drop_bubble_x", (90 * density).toInt())
                y = prefs.getInt("teleport_drop_bubble_y", (390 * density).toInt())
            }

            val status = view.findViewById<TextView>(R.id.tv_teleport_drop_status)
            val icon = view.findViewById<ImageView>(R.id.iv_teleport_drop_icon)
            var operationBusy = false

            fun updateUi(state: Int) {
                when (state) {
                    1 -> {
                        status.text = "REC"
                        status.setTextColor(Color.parseColor("#39FF14"))
                        icon.setColorFilter(
                            Color.parseColor("#00E5FF"),
                            android.graphics.PorterDuff.Mode.SRC_IN
                        )
                    }
                    2 -> {
                        status.text = "SEND"
                        status.setTextColor(Color.parseColor("#FFD740"))
                        icon.setColorFilter(
                            Color.parseColor("#B026FF"),
                            android.graphics.PorterDuff.Mode.SRC_IN
                        )
                    }
                    else -> {
                        status.text = "OFF"
                        status.setTextColor(Color.parseColor("#FF3B30"))
                        icon.setColorFilter(
                            Color.parseColor("#8A9BA8"),
                            android.graphics.PorterDuff.Mode.SRC_IN
                        )
                    }
                }
            }

            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f
            var dragging = false
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = dropParams.x
                        initialY = dropParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) dragging = true
                        if (dragging) {
                            dropParams.x = initialX + dx
                            dropParams.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(view, dropParams)
                            } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            prefs.edit()
                                .putInt("teleport_drop_bubble_x", dropParams.x)
                                .putInt("teleport_drop_bubble_y", dropParams.y)
                                .apply()
                        } else if (!operationBusy) {
                            when (teleportDropState()) {
                                0 -> {
                                    operationBusy = true
                                    status.text = "..."
                                    status.setTextColor(Color.parseColor("#FFD740"))
                                    startTeleportDropCapture { started ->
                                        operationBusy = false
                                        updateUi(if (started) 1 else 0)
                                        Toast.makeText(
                                            this@BubbleService,
                                            if (started) {
                                                if (accessPrefs.getBoolean("use_root", false))
                                                    "Teleport Drop: reteniendo posición (ROOT)"
                                                else "Teleport Drop: reteniendo última posición"
                                            } else "Teleport Drop: no se pudo iniciar",
                                            if (started) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                1 -> {
                                    beginTeleportDropReplay(::updateUi)
                                    Toast.makeText(
                                        this@BubbleService,
                                        if (teleportDropUsesRoot)
                                            "Teleport Drop Root: posición liberada"
                                        else "Teleport Drop: liberando última posición",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                2 -> Toast.makeText(
                                    this@BubbleService,
                                    "Teleport Drop: enviando datos retenidos",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            view.performClick()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }

            updateUi(teleportDropState())
            windowManager.addView(view, dropParams)
            teleportDropFloatingView = view
            teleportDropFloatingParams = dropParams
            if (teleportDropState() == 2) {
                beginTeleportDropReplay(::updateUi)
            }
        } catch (e: Exception) {
            Log.e("BubbleService", "Error mostrando Teleport Drop flotante", e)
        }
    }

    private fun hideTeleportDropFloatingControl(replayCaptured: Boolean) {
        val state = teleportDropState()
        if (replayCaptured && state in 1..2) {
            beginTeleportDropReplay()
        } else {
            ++teleportDropOperationGeneration
            if (!replayCaptured) {
                if (teleportDropUsesRoot || RootTeleportDropController.active) {
                    Thread { RootTeleportDropController.disable() }.start()
                    teleportDropUsesRoot = false
                } else {
                    try { AntigravityFirewall.cancelTeleportDrop() } catch (_: Throwable) {}
                }
            }
        }
        teleportDropFloatingView?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
        }
        teleportDropFloatingView = null
        teleportDropFloatingParams = null
    }

    /**
     * Burbuja vertical de dirección para Enemy Pull. Cada segmento funciona como
     * selección exclusiva y también como asa de arrastre cuando el dedo se mueve.
     */
    private fun showEnemyPullFloatingControl(): Boolean {
        if (enemyPullFloatingView != null) return true
        return try {
            val view = LayoutInflater.from(this).inflate(R.layout.floating_enemy_pull_layout, null)
            val density = resources.displayMetrics.density
            val pullParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val prefs = getSharedPreferences(
                    NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
                    Context.MODE_PRIVATE
                )
                x = prefs.getInt(
                    "enemy_pull_bubble_x",
                    (resources.displayMetrics.widthPixels - 76 * density).toInt().coerceAtLeast(0)
                )
                y = prefs.getInt("enemy_pull_bubble_y", (260 * density).toInt())
            }

            val directionViews = arrayOf(
                view.findViewById<TextView>(R.id.pull_direction_up),
                view.findViewById<TextView>(R.id.pull_direction_down),
                view.findViewById<TextView>(R.id.pull_direction_left),
                view.findViewById<TextView>(R.id.pull_direction_right)
            )
            val directionNames = arrayOf("Arriba", "Abajo", "Izquierda", "Derecha")

            fun updateDirectionUi() {
                directionViews.forEachIndexed { index, directionView ->
                    val selected = enemyPullDirection == index + 1
                    directionView.setBackgroundResource(
                        if (selected) R.drawable.bg_pull_direction_selected
                        else android.R.color.transparent
                    )
                    directionView.setTextColor(
                        Color.parseColor(if (selected) "#39FF14" else "#8A9BA8")
                    )
                }
            }

            fun selectDirection(direction: Int) {
                // La dirección ya seleccionada funciona como toggle: un segundo
                // toque la desmarca y apaga Enemy Pull, dejando la burbuja abierta
                // para escoger otra dirección después.
                if (direction == enemyPullDirection) {
                    ++enemyPullRequestGeneration
                    enemyPullDirection = 0
                    NativeBridge.setEnemyPullDirection(0)
                    NativeBridge.setEnemyPull(false)
                    updateDirectionUi()
                    Toast.makeText(
                        this@BubbleService,
                        "Enemy Pull: Desactivado",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                enemyPullDirection = direction
                NativeBridge.setEnemyPullDirection(direction)
                updateDirectionUi()
                val requestGeneration = ++enemyPullRequestGeneration
                Thread {
                    val started = try {
                        NativeBridge.setEnemyPull(true)
                    } catch (_: Throwable) {
                        false
                    }
                    runOnUiThread {
                        if (requestGeneration != enemyPullRequestGeneration ||
                            enemyPullFloatingView !== view) {
                            if (enemyPullDirection == 0) {
                                NativeBridge.setEnemyPull(false)
                            }
                            return@runOnUiThread
                        }
                        if (started) {
                            Toast.makeText(
                                this@BubbleService,
                                "Enemy Pull: ${directionNames[direction - 1]}",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            enemyPullDirection = 0
                            NativeBridge.setEnemyPullDirection(0)
                            updateDirectionUi()
                            Toast.makeText(
                                this@BubbleService,
                                "Enemy Pull: no se encontró la memoria del juego",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }

            directionViews.forEachIndexed { index, directionView ->
                var startWindowX = 0
                var startWindowY = 0
                var startTouchX = 0f
                var startTouchY = 0f
                var dragging = false
                directionView.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startWindowX = pullParams.x
                            startWindowY = pullParams.y
                            startTouchX = event.rawX
                            startTouchY = event.rawY
                            dragging = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - startTouchX).toInt()
                            val dy = (event.rawY - startTouchY).toInt()
                            if (abs(dx) > 10 || abs(dy) > 10) dragging = true
                            if (dragging) {
                                pullParams.x = startWindowX + dx
                                pullParams.y = startWindowY + dy
                                try {
                                    windowManager.updateViewLayout(view, pullParams)
                                } catch (_: Exception) {}
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (dragging) {
                                getSharedPreferences(
                                    NativeBridge.getNativeString(NativeBridge.STRING_PREFS_NAME),
                                    Context.MODE_PRIVATE
                                ).edit()
                                    .putInt("enemy_pull_bubble_x", pullParams.x)
                                    .putInt("enemy_pull_bubble_y", pullParams.y)
                                    .apply()
                            } else {
                                selectDirection(index + 1)
                                directionView.performClick()
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> true
                        else -> false
                    }
                }
            }

            updateDirectionUi()
            windowManager.addView(view, pullParams)
            enemyPullFloatingView = view
            enemyPullFloatingParams = pullParams
            true
        } catch (e: Exception) {
            Log.e("BubbleService", "Error mostrando selector flotante de Enemy Pull", e)
            false
        }
    }

    private fun hideEnemyPullFloatingControl() {
        ++enemyPullRequestGeneration
        enemyPullDirection = 0
        NativeBridge.setEnemyPullDirection(0)
        NativeBridge.setEnemyPull(false)
        enemyPullFloatingView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
        }
        enemyPullFloatingView = null
        enemyPullFloatingParams = null
    }

    override fun onBind(intent: Intent): IBinder? = null
}
