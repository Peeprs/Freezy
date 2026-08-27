package com.freezy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View

class EspOverlayView(context: Context) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val glowBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.SQUARE
    }

    private val fullBoxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val fullBoxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val closestGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val radarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radarGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(65, 255, 255, 255)
        strokeWidth = 1f
    }
    private val radarTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 16f
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val textStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val healthBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 20, 20, 20)
    }

    private val healthBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val healthBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(220, 0, 0, 0)
    }

    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 20f
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val smallTextStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        textSize = 20f
        textAlign = Paint.Align.CENTER
        color = Color.BLACK
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val chamsFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val chamsStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val chamsGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val chamsWeaponPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    private val aimVisibleFovPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(204, 255, 0, 0)
    }

    private val teamEspColor = Color.rgb(0, 229, 255)
    private val healthyColor = Color.rgb(86, 255, 43)
    private val botTextColor = Color.rgb(176, 190, 197)
    private val rgbHsv = floatArrayOf(0f, 1f, 1f)
    private val botLabel = NativeBridge.getNativeString(NativeBridge.S119)
    private val unknownPlayerLabel = NativeBridge.getNativeString(NativeBridge.S120)
    private val teamLabel = NativeBridge.getNativeString(NativeBridge.S121)
    private val countLabel = NativeBridge.getNativeString(NativeBridge.S123)
    private val weaponLabels = arrayOfNulls<String>(512)

    @Volatile var chamsPlayer: Boolean = false
    @Volatile var chamsPlayerGlow: Boolean = false
    @Volatile var chamsPlayerWireframe: Boolean = false
    @Volatile var chamsWeapon: Boolean = false
    @Volatile var chamsThroughWalls: Boolean = true
    @Volatile var chamsColor: Int = Color.parseColor("#00FF41")
    @Volatile var chamsMode: Int = 0 // 0: Sólido, 1: Glow, 2: Wireframe, 3: Transparente
    @Volatile var chamsRgb: Boolean = false
    @Volatile var drawAimVisibleFov: Boolean = false
    @Volatile var aimVisibleFovRadius: Float = 200f
    @Volatile var espMasterEnabled: Boolean = false

    @Volatile var lineColor: Int = Color.parseColor("#00FF41")
        set(value) {
            field = value
            linePaint.color = value
        }

    @Volatile var boxColor: Int = Color.parseColor("#2196F3")
    @Volatile var glowColor: Int = Color.parseColor("#00BCD4")
    @Volatile var cornerColor: Int = Color.parseColor("#FFEB3B")
    @Volatile var fullBoxColor: Int = Color.parseColor("#E91E63")

    @Volatile var drawSkeleton: Boolean = false
    @Volatile var drawLines: Boolean = false
    @Volatile var drawBox: Boolean = false
    @Volatile var drawGlow: Boolean = false
    @Volatile var drawCornerBox: Boolean = false
    @Volatile var drawFullBox: Boolean = false
    @Volatile var draw3dBox: Boolean = false
    @Volatile var drawHealth: Boolean = false
    @Volatile var drawWeapon: Boolean = false
    @Volatile var drawClosestGlowLine: Boolean = false
    @Volatile var showMinimap: Boolean = false
    @Volatile var drawTeam: Boolean = false
    @Volatile var drawName: Boolean = false
    @Volatile var drawDistance: Boolean = false
    @Volatile var ignoreKnocked: Boolean = false
    @Volatile var showCount: Boolean = false
    @Volatile var rgbMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                handler.removeCallbacks(rgbRunnable)
                handler.post(rgbRunnable)
            }
        }
    @Volatile var lineOrigin: Int = 0
    @Volatile var lineWidth: Float = 3f
        set(value) {
            field = value
            linePaint.strokeWidth = value
        }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var pid = 0

    // Ring de snapshots: JNI escribe en un slot libre y Canvas consume otro slot
    // inmutable. No se copian los 40 floats de cada entidad en poll() ni en onDraw().
    private val espFrames = Array(3) { FloatArray(1600) }
    private val radarFrames = Array(3) { FloatArray(256) }
    private var localBuffer = espFrames[0]
    private var radarLocalBuffer = radarFrames[0]
    private val frameEntityCounts = IntArray(3)
    private val frameRadarCounts = IntArray(3)
    private val frameTotalCounts = IntArray(3)
    private val frameClosestDistances = FloatArray(3) { 999f }
    private val frameLock = Any()
    private var publishedFrameIndex = 0
    private var drawingFrameIndex = -1
    private var nextWriteFrameIndex = 1
    @Volatile private var entityCount = 0
    @Volatile private var radarEntityCount = 0
    @Volatile private var totalEnemiesCount = 0
    @Volatile private var closestDistance = 999f

    private val labelBuilder = StringBuilder(32)
    private val countBuilder = StringBuilder(24)
    private val box3dLines = FloatArray(16)
    private val cornerLines = FloatArray(32)
    private val boneEdges = intArrayOf(
        0, 1,   // Cabeza -> Cuello
        1, 2,   // Cuello -> Cadera
        1, 4,   // Cuello -> Hombro Izq
        4, 6,   // Hombro Izq -> Codo Izq
        6, 8,   // Codo Izq -> Muñeca Izq
        1, 5,   // Cuello -> Hombro Der
        5, 7,   // Hombro Der -> Codo Der
        7, 9,   // Codo Der -> Muñeca Der
        2, 3,   // Cadera -> Ingle
        3, 10,  // Ingle -> Tobillo Izq
        10, 12, // Tobillo Izq -> Pie Izq
        3, 11,  // Ingle -> Tobillo Der
        11, 13  // Tobillo Der -> Pie Der
    )
    private val skeletonLines = FloatArray(boneEdges.size * 2)

    private val pollThreadName = NativeBridge.getNativeString(NativeBridge.S125)
    private var pollThread: Thread? = null

    private fun createPollThread(): Thread = object : Thread(pollThreadName) {
        override fun run() {
            while (running) {
                poll()
                try {
                    // Sondeo adaptativo escalonado por proximidad:
                    // 100ms (~10Hz) en reposo (0 enemigos)
                    // 20ms (~50Hz) en combate cercano (<40m)
                    // 33ms (~30Hz) a media distancia (40-90m)
                    // 60ms (~16Hz) a larga distancia (>90m)
                    val sleepMs = when {
                        totalEnemiesCount == 0 && entityCount == 0 -> 100L
                        closestDistance < 40f -> 20L
                        closestDistance < 90f -> 33L
                        else -> 60L
                    }
                    Thread.sleep(sleepMs)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private val rgbRunnable = object : Runnable {
        override fun run() {
            if (!running || (!rgbMode && !chamsRgb)) return
            postInvalidateOnAnimation()
            handler.postDelayed(this, 16)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val screenW = maxOf(w, h)
            val screenH = minOf(w, h)
            NativeBridge.setScreenSize(screenW, screenH)
        }
    }

    fun start(targetPid: Int) {
        if (running) return
        running = true
        pid = targetPid
        if (width > 0 && height > 0) {
            val screenW = maxOf(width, height)
            val screenH = minOf(width, height)
            NativeBridge.setScreenSize(screenW, screenH)
        }
        pollThread = createPollThread().also { it.start() }
        if (rgbMode || chamsRgb) {
            handler.removeCallbacks(rgbRunnable)
            handler.post(rgbRunnable)
        }
    }

    fun stop() {
        running = false
        pollThread?.interrupt()
        pollThread = null
        handler.removeCallbacks(rgbRunnable)
        synchronized(frameLock) {
            entityCount = 0
            radarEntityCount = 0
            totalEnemiesCount = 0
            closestDistance = 999f
            frameEntityCounts[publishedFrameIndex] = 0
            frameRadarCounts[publishedFrameIndex] = 0
            frameTotalCounts[publishedFrameIndex] = 0
        }
        postInvalidateOnAnimation()
    }

    private fun acquireWriteFrame(): Int = synchronized(frameLock) {
        repeat(espFrames.size) { offset ->
            val candidate = (nextWriteFrameIndex + offset) % espFrames.size
            if (candidate != publishedFrameIndex && candidate != drawingFrameIndex) {
                nextWriteFrameIndex = (candidate + 1) % espFrames.size
                return@synchronized candidate
            }
        }
        -1
    }

    private fun poll() {
        if (pid <= 0) return
        val masterEnabled = espMasterEnabled
        val requiresEntities = (masterEnabled && (
                showMinimap || drawBox || drawGlow || drawCornerBox || drawFullBox || draw3dBox ||
                drawSkeleton || drawLines || drawClosestGlowLine || drawTeam ||
                drawName || drawDistance || drawHealth || drawWeapon || ignoreKnocked || showCount
            )) || chamsPlayer || chamsWeapon

        if (!requiresEntities) {
            synchronized(frameLock) {
                entityCount = 0
                radarEntityCount = 0
                totalEnemiesCount = 0
                closestDistance = 999f
                frameEntityCounts[publishedFrameIndex] = 0
                frameRadarCounts[publishedFrameIndex] = 0
                frameTotalCounts[publishedFrameIndex] = 0
            }
            postInvalidateOnAnimation()
            return
        }
        val writeFrameIndex = acquireWriteFrame()
        if (writeFrameIndex < 0) return
        val espBuffer = espFrames[writeFrameIndex]
        val radarBuffer = radarFrames[writeFrameIndex]
        val flags = (if (masterEnabled && (drawBox || drawGlow || drawCornerBox || drawFullBox || draw3dBox)) 1 else 0) or
                (if ((masterEnabled && drawSkeleton) || chamsPlayer || chamsWeapon) 2 else 0) or
                (if (masterEnabled && (drawLines || drawClosestGlowLine)) 4 else 0) or
                (if (masterEnabled && drawHealth) 8 else 0) or
                (if (masterEnabled && drawName) 16 else 0) or
                (if (masterEnabled && drawDistance) 32 else 0) or
                (if (chamsWeapon || (masterEnabled && drawWeapon)) 64 else 0) or
                (if (masterEnabled && drawTeam) 128 else 0) or
                (if (masterEnabled && ignoreKnocked) 256 else 0) or
                (if (masterEnabled && showMinimap) 512 else 0)

        val packed = try {
            NativeBridge.getEspSnapshotDirect(pid, espBuffer, flags)
        } catch (e: Exception) {
            0
        }

        val count = (packed and 0xFFFF).coerceIn(0, espBuffer.size / 40)
        val totalInRange = (packed ushr 16) and 0xFFFF
        val radarCount = if (showMinimap) {
            try {
                // Se sirve desde el snapshot recién generado; no vuelve a leer el juego.
                NativeBridge.getRadarSnapshot(pid, radarBuffer)
            } catch (_: Exception) {
                0
            }.coerceIn(0, radarBuffer.size / 4)
        } else {
            0
        }

        var minDist = 999f
        for (i in 0 until count) {
            val d = espBuffer[i * 40 + 1]
            if (d in 0.1f..<minDist) minDist = d
        }

        synchronized(frameLock) {
            frameEntityCounts[writeFrameIndex] = count
            frameRadarCounts[writeFrameIndex] = radarCount
            frameTotalCounts[writeFrameIndex] = if (totalInRange > 0) totalInRange else count
            frameClosestDistances[writeFrameIndex] = minDist
            publishedFrameIndex = writeFrameIndex
            entityCount = count
            radarEntityCount = radarCount
            totalEnemiesCount = if (totalInRange > 0) totalInRange else count
            closestDistance = minDist
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return

        val count: Int
        val totalEnemies: Int
        val radarCount: Int
        val frameIndex: Int
        synchronized(frameLock) {
            frameIndex = publishedFrameIndex
            drawingFrameIndex = frameIndex
            count = frameEntityCounts[frameIndex]
            totalEnemies = frameTotalCounts[frameIndex]
            radarCount = frameRadarCounts[frameIndex]
            localBuffer = espFrames[frameIndex]
            radarLocalBuffer = radarFrames[frameIndex]
        }

        val w = width.toFloat()
        val h = height.toFloat()

        if (showMinimap) {
            drawMinimap(canvas, radarCount, w, h)
        }

        if (drawAimVisibleFov) {
            canvas.drawCircle(w / 2f, h / 2f, aimVisibleFovRadius, aimVisibleFovPaint)
        }

        var animatedColor = lineColor
        if (rgbMode || chamsRgb) {
            val t = System.currentTimeMillis() / 1000.0
            rgbHsv[0] = ((t * 180.0) % 360.0).toFloat()
            animatedColor = Color.HSVToColor(255, rgbHsv)
        }
        val baseColor = if (rgbMode) animatedColor else lineColor
        linePaint.color = baseColor

        val effectiveChamsColor = if (chamsRgb || rgbMode) animatedColor else chamsColor

        val originY = when (lineOrigin) {
            0 -> h            // abajo
            1 -> h / 2f       // medio
            else -> 60f       // arriba
        }

        val knockedColor = Color.RED
        val teamColor = teamEspColor

        var visibleCount = 0
        var closestLineDistance = Float.MAX_VALUE
        var closestLineX = -1f
        var closestLineY = -1f

        for (i in 0 until count) {
            val off = i * 40
            val isKnocked = localBuffer[off] > 0.5f
            val dist = localBuffer[off + 1]
            val team = localBuffer[off + 2].toInt()
            val hp = localBuffer[off + 3]
            val wepId = localBuffer[off + 4].toInt()
            val isBot = localBuffer[off + 5] > 0.5f

            // Filtro Ignore Knocked
            if (ignoreKnocked && isKnocked) continue

            // Filtro Team: si no está activo ESP Team, solo dibuja enemigos (team == 2)
            if (!drawTeam && team == 1) continue

            visibleCount++

            val isAlly = (team == 1)
            val currentPaintColor = when {
                isKnocked -> knockedColor
                isAlly -> teamColor
                else -> baseColor
            }
            linePaint.color = currentPaintColor
            val currentBoxColor = when {
                isKnocked -> knockedColor
                isAlly -> teamColor
                else -> boxColor
            }
            val currentGlowColor = when {
                isKnocked -> knockedColor
                isAlly -> teamColor
                else -> glowColor
            }
            val currentCornerColor = when {
                isKnocked -> knockedColor
                isAlly -> teamColor
                else -> cornerColor
            }
            val currentFullBoxColor = when {
                isKnocked -> knockedColor
                isAlly -> teamColor
                else -> fullBoxColor
            }

            // Coordenadas base de cabeza: Hueso 0 -> offset 12 y 13
            val headX = localBuffer[off + 12 + 0]
            val headY = localBuffer[off + 12 + 1]
            if (headX <= 0 || headY <= 0) continue
            if (drawClosestGlowLine && !isKnocked && !isAlly && dist < closestLineDistance) {
                closestLineDistance = dist
                closestLineX = headX
                closestLineY = headY
            }

            val neckY = localBuffer[off + 12 + 3]
            val headOffset = if (neckY > headY) (neckY - headY) * 0.9f else 18f
            val boxTop = headY - headOffset

            // Base: Pies (b[12]=25, b[13]=27)
            val footL_Y = localBuffer[off + 12 + 25]
            val footR_Y = localBuffer[off + 12 + 27]

            var boxBottom = -1f
            if (footL_Y > 0 && footR_Y > 0) {
                boxBottom = maxOf(footL_Y, footR_Y) + 6f
            } else if (footL_Y > 0) {
                boxBottom = footL_Y + 6f
            } else if (footR_Y > 0) {
                boxBottom = footR_Y + 6f
            } else {
                val groinY = localBuffer[off + 12 + 7]
                val hipY = localBuffer[off + 12 + 5]
                if (groinY > 0) {
                    boxBottom = groinY + (groinY - headY)
                } else if (hipY > 0) {
                    boxBottom = hipY + (hipY - headY) * 1.2f
                } else {
                    boxBottom = headY + 100f
                }
            }

            // Ancho: Brazos (hombros b[4]=8, b[5]=10 y muñecas b[8]=16, b[9]=18)
            val sL_X = localBuffer[off + 12 + 8]
            val sR_X = localBuffer[off + 12 + 10]
            val wL_X = localBuffer[off + 12 + 16]
            val wR_X = localBuffer[off + 12 + 18]

            var minArmX = Float.MAX_VALUE
            var maxArmX = Float.MIN_VALUE

            if (sL_X > 0) { if (sL_X < minArmX) minArmX = sL_X; if (sL_X > maxArmX) maxArmX = sL_X }
            if (sR_X > 0) { if (sR_X < minArmX) minArmX = sR_X; if (sR_X > maxArmX) maxArmX = sR_X }
            if (wL_X > 0) { if (wL_X < minArmX) minArmX = wL_X; if (wL_X > maxArmX) maxArmX = wL_X }
            if (wR_X > 0) { if (wR_X < minArmX) minArmX = wR_X; if (wR_X > maxArmX) maxArmX = wR_X }

            var boxLeft: Float
            var boxRight: Float

            val boxHeight = boxBottom - boxTop
            if (maxArmX > minArmX && (maxArmX - minArmX) > 10f) {
                val armWidth = maxArmX - minArmX
                val armPadding = if (armWidth > 10f) armWidth * 0.15f else 8f
                boxLeft = minArmX - armPadding
                boxRight = maxArmX + armPadding

                if (boxHeight > 0 && (boxRight - boxLeft) < boxHeight * 0.35f) {
                    val centerX = (minArmX + maxArmX) / 2f
                    val halfW = (boxHeight * 0.40f) / 2f
                    boxLeft = centerX - halfW
                    boxRight = centerX + halfW
                }
            } else {
                val halfW = if (boxHeight > 0) (boxHeight * 0.40f) / 2f else 30f
                boxLeft = headX - halfW
                boxRight = headX + halfW
            }

            // 1. ESP Línea (color y grosor independientes)
            if (drawLines) {
                canvas.drawLine(w / 2f, originY, headX, headY, linePaint)
            }

            if (boxBottom > boxTop && boxRight > boxLeft) {
                // ESP Glow: tres contornos superpuestos para un resplandor estable
                // incluso cuando el overlay se renderiza por hardware.
                if (drawGlow) {
                    glowBoxPaint.color = Color.argb(
                        45,
                        Color.red(currentGlowColor),
                        Color.green(currentGlowColor),
                        Color.blue(currentGlowColor)
                    )
                    glowBoxPaint.strokeWidth = 14f
                    canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, glowBoxPaint)
                    glowBoxPaint.color = Color.argb(
                        90,
                        Color.red(currentGlowColor),
                        Color.green(currentGlowColor),
                        Color.blue(currentGlowColor)
                    )
                    glowBoxPaint.strokeWidth = 8f
                    canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, glowBoxPaint)
                    glowBoxPaint.color = currentGlowColor
                    glowBoxPaint.strokeWidth = 2.5f
                    canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, glowBoxPaint)
                }

                // FullBox: cuerpo completo con relleno transparente y borde propio.
                if (drawFullBox) {
                    fullBoxFillPaint.color = Color.argb(
                        42,
                        Color.red(currentFullBoxColor),
                        Color.green(currentFullBoxColor),
                        Color.blue(currentFullBoxColor)
                    )
                    fullBoxStrokePaint.color = currentFullBoxColor
                    canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, fullBoxFillPaint)
                    canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, fullBoxStrokePaint)
                }

                // ESP Box clásico: solo contorno.
                if (drawBox) {
                    boxPaint.color = currentBoxColor
                    canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, boxPaint)
                }

                // Caja pseudo-3D: cara posterior desplazada y cuatro conectores.
                if (draw3dBox) {
                    boxPaint.color = currentBoxColor
                    val depth = ((boxRight - boxLeft) * 0.16f).coerceIn(5f, 14f)
                    canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, boxPaint)
                    canvas.drawRect(
                        boxLeft + depth,
                        boxTop - depth,
                        boxRight + depth,
                        boxBottom - depth,
                        boxPaint
                    )
                    box3dLines[0] = boxLeft; box3dLines[1] = boxTop
                    box3dLines[2] = boxLeft + depth; box3dLines[3] = boxTop - depth
                    box3dLines[4] = boxRight; box3dLines[5] = boxTop
                    box3dLines[6] = boxRight + depth; box3dLines[7] = boxTop - depth
                    box3dLines[8] = boxLeft; box3dLines[9] = boxBottom
                    box3dLines[10] = boxLeft + depth; box3dLines[11] = boxBottom - depth
                    box3dLines[12] = boxRight; box3dLines[13] = boxBottom
                    box3dLines[14] = boxRight + depth; box3dLines[15] = boxBottom - depth
                    canvas.drawLines(box3dLines, boxPaint)
                }

                // Corner Box: cuatro esquinas, cada una con segmentos horizontal/vertical.
                if (drawCornerBox) {
                    cornerPaint.color = currentCornerColor
                    val cornerLength = (minOf(boxRight - boxLeft, boxBottom - boxTop) * 0.28f)
                        .coerceIn(8f, 32f)
                    cornerLines[0] = boxLeft; cornerLines[1] = boxTop
                    cornerLines[2] = boxLeft + cornerLength; cornerLines[3] = boxTop
                    cornerLines[4] = boxLeft; cornerLines[5] = boxTop
                    cornerLines[6] = boxLeft; cornerLines[7] = boxTop + cornerLength
                    cornerLines[8] = boxRight; cornerLines[9] = boxTop
                    cornerLines[10] = boxRight - cornerLength; cornerLines[11] = boxTop
                    cornerLines[12] = boxRight; cornerLines[13] = boxTop
                    cornerLines[14] = boxRight; cornerLines[15] = boxTop + cornerLength
                    cornerLines[16] = boxLeft; cornerLines[17] = boxBottom
                    cornerLines[18] = boxLeft + cornerLength; cornerLines[19] = boxBottom
                    cornerLines[20] = boxLeft; cornerLines[21] = boxBottom
                    cornerLines[22] = boxLeft; cornerLines[23] = boxBottom - cornerLength
                    cornerLines[24] = boxRight; cornerLines[25] = boxBottom
                    cornerLines[26] = boxRight - cornerLength; cornerLines[27] = boxBottom
                    cornerLines[28] = boxRight; cornerLines[29] = boxBottom
                    cornerLines[30] = boxRight; cornerLines[31] = boxBottom - cornerLength
                    canvas.drawLines(cornerLines, cornerPaint)
                }
            }

            if (drawHealth && boxBottom > boxTop) {
                val maxHealth = 200f
                val healthRatio = (hp / maxHealth).coerceIn(0f, 1f)
                val barLeft = boxLeft - 7f
                val barRight = boxLeft - 3f
                healthBgPaint.color = Color.argb(210, 30, 30, 30)
                canvas.drawRect(barLeft, boxTop, barRight, boxBottom, healthBgPaint)
                healthBarPaint.color = when {
                    healthRatio < 0.30f -> Color.RED
                    healthRatio < 0.80f -> Color.YELLOW
                    else -> healthyColor
                }
                val healthTop = boxBottom - (boxBottom - boxTop) * healthRatio
                canvas.drawRect(barLeft, healthTop, barRight, boxBottom, healthBarPaint)
                canvas.drawRect(barLeft, boxTop, barRight, boxBottom, healthBorderPaint)
            }

            var currentTop = boxTop - 12f

            // 3. ESP Name & ESP Distance (Zero-Alloc)
            if (drawName || drawDistance) {
                labelBuilder.setLength(0)
                if (drawName) {
                    if (isBot) {
                        labelBuilder.append(botLabel)
                    } else {
                        val startLen = labelBuilder.length
                        for (j in 0 until 6) {
                            val packed = localBuffer[off + 6 + j].toInt()
                            if (packed <= 0) break
                            val c1 = (packed shr 8) and 0xFF
                            val c2 = packed and 0xFF
                            if (c1 in 32..126) labelBuilder.append(c1.toChar())
                            if (c2 in 32..126) labelBuilder.append(c2.toChar())
                        }
                        if (labelBuilder.length == startLen) {
                            labelBuilder.append(unknownPlayerLabel)
                        }
                    }
                }
                if (drawDistance && dist > 0) {
                    if (labelBuilder.isNotEmpty()) labelBuilder.append(' ')
                    labelBuilder.append('[').append(dist.toInt()).append('m').append(']')
                }

                if (labelBuilder.isNotEmpty()) {
                    smallTextStrokePaint.color = Color.BLACK
                    smallTextPaint.color = if (isBot) botTextColor else Color.WHITE
                    canvas.drawText(labelBuilder, 0, labelBuilder.length, headX, currentTop - 2f, smallTextStrokePaint)
                    canvas.drawText(labelBuilder, 0, labelBuilder.length, headX, currentTop - 2f, smallTextPaint)
                    currentTop -= 16f
                }
            }

            // 4. ESP Team (Solo muestra etiqueta para compañeros de equipo)
            if (drawTeam && isAlly) {
                smallTextStrokePaint.color = Color.BLACK
                smallTextPaint.color = teamColor
                canvas.drawText(teamLabel, headX, currentTop - 2f, smallTextStrokePaint)
                canvas.drawText(teamLabel, headX, currentTop - 2f, smallTextPaint)
                currentTop -= 16f
            }

            if (drawWeapon && wepId > 0) {
                val weaponText = weaponLabel(wepId)
                smallTextStrokePaint.color = Color.BLACK
                smallTextPaint.color = currentBoxColor
                val weaponY = boxBottom + smallTextPaint.textSize + 4f
                canvas.drawText(weaponText, headX, weaponY, smallTextStrokePaint)
                canvas.drawText(weaponText, headX, weaponY, smallTextPaint)
            }

            // 7. ESP Skeleton
            if (drawSkeleton) {
                var lineIdx = 0
                var i = 0
                while (i < boneEdges.size) {
                    val b1 = boneEdges[i]
                    val b2 = boneEdges[i + 1]
                    val x1 = localBuffer[off + 12 + b1 * 2]
                    val y1 = localBuffer[off + 12 + b1 * 2 + 1]
                    val x2 = localBuffer[off + 12 + b2 * 2]
                    val y2 = localBuffer[off + 12 + b2 * 2 + 1]
                    if (x1 > 0 && y1 > 0 && x2 > 0 && y2 > 0) {
                        skeletonLines[lineIdx++] = x1
                        skeletonLines[lineIdx++] = y1
                        skeletonLines[lineIdx++] = x2
                        skeletonLines[lineIdx++] = y2
                    }
                    i += 2
                }
                if (lineIdx > 0) {
                    canvas.drawLines(skeletonLines, 0, lineIdx, linePaint)
                }

                for (b in 0 until 14) {
                    if (b == 6 || b == 7 || b == 10 || b == 11) continue
                    val bx = localBuffer[off + 12 + b * 2]
                    val by = localBuffer[off + 12 + b * 2 + 1]
                    if (bx > 0 && by > 0) {
                        canvas.drawCircle(bx, by, 3f, linePaint)
                    }
                }
            }

            // 8. Chams Personaje (Silueta / Volumetría / Glow)
            if (chamsPlayer) {
                val alpha = when (chamsMode) {
                    0 -> 220 // Sólido Intenso
                    1 -> 160 // Glow
                    2 -> 120 // Wireframe
                    else -> 80 // Transparente
                }
                val finalChamsColor = Color.argb(
                    alpha,
                    Color.red(effectiveChamsColor),
                    Color.green(effectiveChamsColor),
                    Color.blue(effectiveChamsColor)
                )
                chamsStrokePaint.color = finalChamsColor
                chamsFillPaint.color = finalChamsColor

                // Cabeza rellena o Glow
                val headRadius = if (neckY > headY) (neckY - headY) * 0.75f else 16f
                if (chamsPlayerGlow || chamsMode == 1) {
                    chamsGlowPaint.color = Color.argb(
                        90,
                        Color.red(effectiveChamsColor),
                        Color.green(effectiveChamsColor),
                        Color.blue(effectiveChamsColor)
                    )
                    chamsGlowPaint.strokeWidth = 16f
                    canvas.drawCircle(headX, headY, headRadius + 4f, chamsGlowPaint)
                }

                if (chamsPlayerWireframe || chamsMode == 2) {
                    chamsStrokePaint.strokeWidth = 2.5f
                    canvas.drawCircle(headX, headY, headRadius, chamsStrokePaint)
                } else {
                    canvas.drawCircle(headX, headY, headRadius, chamsFillPaint)
                }

                // Extremidades / Torso
                val limbWidth = if (boxHeight > 0) (boxHeight * 0.08f).coerceIn(8f, 22f) else 12f
                chamsStrokePaint.strokeWidth = limbWidth

                var i = 0
                while (i < boneEdges.size) {
                    val b1 = boneEdges[i]
                    val b2 = boneEdges[i + 1]
                    val x1 = localBuffer[off + 12 + b1 * 2]
                    val y1 = localBuffer[off + 12 + b1 * 2 + 1]
                    val x2 = localBuffer[off + 12 + b2 * 2]
                    val y2 = localBuffer[off + 12 + b2 * 2 + 1]
                    if (x1 > 0 && y1 > 0 && x2 > 0 && y2 > 0) {
                        if (chamsPlayerGlow || chamsMode == 1) {
                            chamsGlowPaint.strokeWidth = limbWidth + 8f
                            canvas.drawLine(x1, y1, x2, y2, chamsGlowPaint)
                        }
                        canvas.drawLine(x1, y1, x2, y2, chamsStrokePaint)

                        if (chamsPlayerWireframe || chamsMode == 2) {
                            val midX = (x1 + x2) / 2f
                            val midY = (y1 + y2) / 2f
                            chamsStrokePaint.strokeWidth = 1.5f
                            canvas.drawCircle(midX, midY, limbWidth / 2f, chamsStrokePaint)
                            chamsStrokePaint.strokeWidth = limbWidth
                        }
                    }
                    i += 2
                }
            }

            // 9. Chams Arma (Resplandor / Trazo en mano armada)
            if (chamsWeapon) {
                val wL_Y = localBuffer[off + 12 + 17]
                val wR_Y = localBuffer[off + 12 + 19]
                val sL_Y = localBuffer[off + 12 + 9]
                val sR_Y = localBuffer[off + 12 + 11]

                val handX = if (wR_X > 0f) wR_X else wL_X
                val handY = if (wR_Y > 0f) wR_Y else wL_Y

                if (handX > 0f && handY > 0f) {
                    val wepColor = Color.argb(
                        230,
                        Color.red(effectiveChamsColor),
                        Color.green(effectiveChamsColor),
                        Color.blue(effectiveChamsColor)
                    )
                    chamsWeaponPaint.color = wepColor

                    val shoulderX = if (wR_X > 0f) sR_X else sL_X
                    val shoulderY = if (wR_Y > 0f) sR_Y else sL_Y
                    val dirX = if (shoulderX > 0f) (handX - shoulderX) else 15f
                    val dirY = if (shoulderY > 0f) (handY - shoulderY) else 5f
                    val len = Math.hypot(dirX.toDouble(), dirY.toDouble()).toFloat().coerceAtLeast(1f)
                    val normX = dirX / len
                    val normY = dirY / len
                    val barrelLen = 36f

                    val barrelEndX = handX + normX * barrelLen
                    val barrelEndY = handY + normY * barrelLen

                    chamsGlowPaint.color = Color.argb(
                        100,
                        Color.red(effectiveChamsColor),
                        Color.green(effectiveChamsColor),
                        Color.blue(effectiveChamsColor)
                    )
                    chamsGlowPaint.strokeWidth = 10f
                    canvas.drawLine(handX, handY, barrelEndX, barrelEndY, chamsGlowPaint)

                    chamsWeaponPaint.strokeWidth = 4f
                    canvas.drawLine(handX, handY, barrelEndX, barrelEndY, chamsWeaponPaint)
                    canvas.drawCircle(handX, handY, 5f, chamsWeaponPaint)
                    canvas.drawCircle(barrelEndX, barrelEndY, 3f, chamsWeaponPaint)
                }
            }
        }

        if (drawClosestGlowLine && closestLineX > 0f && closestLineY > 0f) {
            val startX = w / 2f
            val startY = h / 2f
            closestGlowPaint.color = Color.argb(
                38,
                Color.red(baseColor),
                Color.green(baseColor),
                Color.blue(baseColor)
            )
            closestGlowPaint.strokeWidth = 18f
            canvas.drawLine(startX, startY, closestLineX, closestLineY, closestGlowPaint)
            closestGlowPaint.color = Color.argb(
                90,
                Color.red(baseColor),
                Color.green(baseColor),
                Color.blue(baseColor)
            )
            closestGlowPaint.strokeWidth = 9f
            canvas.drawLine(startX, startY, closestLineX, closestLineY, closestGlowPaint)
            closestGlowPaint.color = baseColor
            closestGlowPaint.strokeWidth = lineWidth.coerceAtLeast(2f)
            canvas.drawLine(startX, startY, closestLineX, closestLineY, closestGlowPaint)
        }

        // ESP Count: total de enemigos vivos en rango
        if (showCount) {
            val displayCount = if (totalEnemies > 0) totalEnemies else visibleCount
            countBuilder.setLength(0)
            countBuilder.append(countLabel).append(displayCount)
            textStrokePaint.color = Color.BLACK
            textPaint.color = baseColor
            canvas.drawText(countBuilder, 0, countBuilder.length, w / 2f, 30f + textPaint.textSize, textStrokePaint)
            canvas.drawText(countBuilder, 0, countBuilder.length, w / 2f, 30f + textPaint.textSize, textPaint)
        }
        synchronized(frameLock) {
            if (drawingFrameIndex == frameIndex) drawingFrameIndex = -1
        }
    }

    private fun weaponLabel(id: Int): String {
        if (id !in weaponLabels.indices) return "ARMA #$id"
        return weaponLabels[id] ?: "ARMA #$id".also { weaponLabels[id] = it }
    }

    private fun drawMinimap(canvas: Canvas, count: Int, screenWidth: Float, screenHeight: Float) {
        val radarWidth = 200f
        val radarHeight = 150f
        val left = 24f
        val top = (screenHeight - radarHeight - 24f).coerceAtLeast(24f)
        val right = (left + radarWidth).coerceAtMost(screenWidth - 12f)
        val bottom = top + radarHeight
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f

        radarPaint.style = Paint.Style.FILL
        radarPaint.color = Color.argb(185, 10, 10, 10)
        canvas.drawRoundRect(left, top, right, bottom, 6f, 6f, radarPaint)
        radarGridPaint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(left, top, right, bottom, 6f, 6f, radarGridPaint)
        canvas.drawLine(centerX, top, centerX, bottom, radarGridPaint)
        canvas.drawLine(left, centerY, right, centerY, radarGridPaint)
        canvas.drawCircle(centerX, centerY, minOf(right - left, radarHeight) * 0.25f, radarGridPaint)

        canvas.drawText("N", centerX, top + 17f, radarTextPaint)

        radarPaint.color = Color.CYAN
        canvas.drawCircle(centerX, centerY, 5.5f, radarPaint)

        val scale = minOf((right - left) / 2f, radarHeight / 2f) / 250f
        for (i in 0 until count.coerceAtMost(radarLocalBuffer.size / 4)) {
            val offset = i * 4
            val relativeX = radarLocalBuffer[offset]
            val relativeZ = radarLocalBuffer[offset + 1]
            val knocked = radarLocalBuffer[offset + 2] > 0.5f
            val team = radarLocalBuffer[offset + 3].toInt()
            val pointX = centerX + relativeX * scale
            val pointY = centerY - relativeZ * scale
            if (pointX !in left..right || pointY !in top..bottom) continue
            radarPaint.color = when {
                knocked -> Color.YELLOW
                team == 1 -> Color.CYAN
                else -> Color.RED
            }
            canvas.drawCircle(pointX, pointY, 4f, radarPaint)
        }
    }
}
