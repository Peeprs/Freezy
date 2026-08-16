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

    private val weaponTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 19f
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#FFD54F") // Dorado / Amarillo suave
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    @Volatile var lineColor: Int = Color.parseColor("#00FF41")
        set(value) {
            field = value
            linePaint.color = value
        }

    @Volatile var drawSkeleton: Boolean = false
    @Volatile var drawLines: Boolean = false
    @Volatile var drawBox: Boolean = false
    @Volatile var drawHealth: Boolean = false
    @Volatile var drawTeam: Boolean = false
    @Volatile var drawName: Boolean = false
    @Volatile var drawDistance: Boolean = false
    @Volatile var drawWeapon: Boolean = false
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

    // Buffers pre-asignados de alta velocidad (Zero Garbage Collection) - 40 floats por entidad
    private val espBuffer = FloatArray(1600)
    private val drawBuffer = FloatArray(1600)
    private val localBuffer = FloatArray(1600)
    @Volatile private var entityCount = 0
    private val bufferLock = Any()

    private val nameBuilder = StringBuilder(16)
    private val labelBuilder = StringBuilder(32)
    private val boneEdges = intArrayOf(0, 1, 1, 2, 1, 4, 1, 5, 4, 8, 5, 9, 2, 3, 3, 12, 3, 13)
    private val skeletonLines = FloatArray(boneEdges.size * 2)

    private val pollThread = object : Thread(NativeBridge.getNativeString(NativeBridge.S125)) {
        override fun run() {
            while (running) {
                poll()
                try {
                    // Sondeo adaptativo: 8ms (~120Hz) con enemigos, 25ms (~40Hz) en reposo
                    val sleepMs = if (entityCount > 0) 8L else 25L
                    Thread.sleep(sleepMs)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private val rgbRunnable = object : Runnable {
        override fun run() {
            if (!running || !rgbMode) return
            postInvalidate()
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
        pollThread.start()
        if (rgbMode) {
            handler.removeCallbacks(rgbRunnable)
            handler.post(rgbRunnable)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(rgbRunnable)
        synchronized(bufferLock) {
            entityCount = 0
        }
        postInvalidate()
    }

    private fun poll() {
        if (pid <= 0) return
        val flags = (if (drawBox) 1 else 0) or
                (if (drawSkeleton) 2 else 0) or
                (if (drawLines) 4 else 0) or
                (if (drawHealth) 8 else 0) or
                (if (drawName) 16 else 0) or
                (if (drawDistance) 32 else 0) or
                (if (drawWeapon) 64 else 0) or
                (if (drawTeam) 128 else 0) or
                (if (ignoreKnocked) 256 else 0)

        val count = try {
            NativeBridge.getEspSnapshotDirect(pid, espBuffer, flags)
        } catch (e: Exception) {
            0
        }

        synchronized(bufferLock) {
            if (count > 0) {
                System.arraycopy(espBuffer, 0, drawBuffer, 0, count * 40)
            }
            entityCount = count
        }
        postInvalidate()
    }

    private fun unpackName(buffer: FloatArray, off: Int, isBot: Boolean): String {
        if (isBot) return NativeBridge.getNativeString(NativeBridge.S119)
        nameBuilder.setLength(0)
        for (i in 0 until 6) {
            val packed = buffer[off + i].toInt()
            if (packed <= 0) break
            val c1 = (packed shr 8) and 0xFF
            val c2 = packed and 0xFF
            if (c1 in 32..126) nameBuilder.append(c1.toChar())
            if (c2 in 32..126) nameBuilder.append(c2.toChar())
        }
        return if (nameBuilder.isNotEmpty()) nameBuilder.toString() else NativeBridge.getNativeString(NativeBridge.S120)
    }

    private fun getWeaponName(id: Int): String {
        return when (id) {
            1 -> NativeBridge.getNativeString(NativeBridge.S126)
            2 -> NativeBridge.getNativeString(NativeBridge.S127)
            3 -> NativeBridge.getNativeString(NativeBridge.S128)
            4 -> NativeBridge.getNativeString(NativeBridge.S129)
            5 -> NativeBridge.getNativeString(NativeBridge.S130)
            6 -> NativeBridge.getNativeString(NativeBridge.S131)
            7 -> NativeBridge.getNativeString(NativeBridge.S132)
            8 -> NativeBridge.getNativeString(NativeBridge.S133)
            9 -> NativeBridge.getNativeString(NativeBridge.S134)
            10 -> NativeBridge.getNativeString(NativeBridge.S135)
            11 -> NativeBridge.getNativeString(NativeBridge.S136)
            12 -> NativeBridge.getNativeString(NativeBridge.S137)
            13 -> NativeBridge.getNativeString(NativeBridge.S138)
            14 -> NativeBridge.getNativeString(NativeBridge.S139)
            15 -> NativeBridge.getNativeString(NativeBridge.S140)
            16 -> NativeBridge.getNativeString(NativeBridge.S141)
            17 -> NativeBridge.getNativeString(NativeBridge.S142)
            21 -> NativeBridge.getNativeString(NativeBridge.S143)
            64 -> NativeBridge.getNativeString(NativeBridge.S144)
            65 -> NativeBridge.getNativeString(NativeBridge.S145)
            75 -> NativeBridge.getNativeString(NativeBridge.S146)
            78 -> NativeBridge.getNativeString(NativeBridge.S147)
            128 -> NativeBridge.getNativeString(NativeBridge.S148)
            129 -> NativeBridge.getNativeString(NativeBridge.S149)
            197 -> NativeBridge.getNativeString(NativeBridge.S150)
            201 -> NativeBridge.getNativeString(NativeBridge.S151)
            202 -> NativeBridge.getNativeString(NativeBridge.S152)
            203 -> NativeBridge.getNativeString(NativeBridge.S153)
            204 -> NativeBridge.getNativeString(NativeBridge.S154)
            0 -> ""
            else -> "${NativeBridge.getNativeString(NativeBridge.S124)}$id"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return

        val count: Int
        synchronized(bufferLock) {
            count = entityCount
            if (count > 0) {
                System.arraycopy(drawBuffer, 0, localBuffer, 0, count * 40)
            }
        }

        val w = width.toFloat()
        val h = height.toFloat()

        var baseColor = lineColor
        if (rgbMode) {
            val t = System.currentTimeMillis() / 1000.0
            val hue = ((t * 180.0) % 360.0).toFloat()
            baseColor = Color.HSVToColor(255, floatArrayOf(hue, 1f, 1f))
        }
        linePaint.color = baseColor

        val originY = when (lineOrigin) {
            0 -> h            // abajo
            1 -> h / 2f       // medio
            else -> 60f       // arriba
        }

        val knockedColor = Color.RED
        val teamColor = Color.parseColor("#00E5FF") // Cyan para aliados

        var visibleCount = 0

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

            // Coordenadas base de cabeza: Hueso 0 -> offset 12 y 13
            val headX = localBuffer[off + 12 + 0]
            val headY = localBuffer[off + 12 + 1]
            if (headX <= 0 || headY <= 0) continue

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

            // 1. ESP Línea
            if (drawLines) {
                canvas.drawLine(w / 2f, originY, headX, headY, linePaint)
            }

            // 2. ESP Box
            if (drawBox) {
                if (boxBottom > boxTop && boxRight > boxLeft) {
                    canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, linePaint)
                }
            }

            // 3. ESP Health (Barra de vida visual)
            var currentTop = boxTop - 12f
            if (drawHealth) {
                val barW = if (boxRight > boxLeft) (boxRight - boxLeft).coerceIn(40f, 90f) else 50f
                val barH = 5f
                val barLeft = headX - (barW / 2f)
                val barRight = barLeft + barW
                val barTop = currentTop
                val barBottom = barTop + barH

                canvas.drawRect(barLeft - 1f, barTop - 1f, barRight + 1f, barBottom + 1f, healthBgPaint)
                canvas.drawRect(barLeft - 1f, barTop - 1f, barRight + 1f, barBottom + 1f, healthBorderPaint)

                val hpPercent = (hp / 200f).coerceIn(0f, 1f)
                healthBarPaint.color = when {
                    hpPercent > 0.5f -> Color.parseColor("#00E676")
                    hpPercent > 0.25f -> Color.parseColor("#FFD600")
                    else -> Color.parseColor("#FF1744")
                }
                canvas.drawRect(barLeft, barTop, barLeft + (barW * hpPercent), barBottom, healthBarPaint)

                val hpText = "${hp.toInt()}"
                canvas.drawText(hpText, headX, barTop - 3f, smallTextStrokePaint)
                canvas.drawText(hpText, headX, barTop - 3f, smallTextPaint)
                currentTop -= 16f
            }

            // 4. ESP Name & ESP Distance
            if (drawName || drawDistance) {
                labelBuilder.setLength(0)
                if (drawName) {
                    val name = unpackName(localBuffer, off + 6, isBot)
                    labelBuilder.append(name)
                }
                if (drawDistance && dist > 0) {
                    if (labelBuilder.isNotEmpty()) labelBuilder.append(' ')
                    labelBuilder.append('[').append(dist.toInt()).append('m').append(']')
                }

                if (labelBuilder.isNotEmpty()) {
                    val str = labelBuilder.toString()
                    smallTextStrokePaint.color = Color.BLACK
                    smallTextPaint.color = if (isBot) Color.parseColor("#B0BEC5") else Color.WHITE
                    canvas.drawText(str, headX, currentTop - 2f, smallTextStrokePaint)
                    canvas.drawText(str, headX, currentTop - 2f, smallTextPaint)
                    currentTop -= 16f
                }
            }

            // 5. ESP Team
            if (drawTeam) {
                val teamText = if (isAlly) NativeBridge.getNativeString(NativeBridge.S121) else NativeBridge.getNativeString(NativeBridge.S122)
                smallTextStrokePaint.color = Color.BLACK
                smallTextPaint.color = if (isAlly) teamColor else Color.WHITE
                canvas.drawText(teamText, headX, currentTop - 2f, smallTextStrokePaint)
                canvas.drawText(teamText, headX, currentTop - 2f, smallTextPaint)
                currentTop -= 16f
            }

            // 6. ESP Weapon (debajo de los pies)
            if (drawWeapon) {
                val wepName = getWeaponName(wepId)
                if (wepName.isNotEmpty()) {
                    val wepY = (if (boxBottom > 0) boxBottom else headY + 120f) + 16f
                    canvas.drawText(wepName, headX, wepY, smallTextStrokePaint)
                    canvas.drawText(wepName, headX, wepY, weaponTextPaint)
                }
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
        }

        // ESP Count: enemigos vivos
        if (showCount) {
            val countText = "${NativeBridge.getNativeString(NativeBridge.S123)}$visibleCount"
            textStrokePaint.color = Color.BLACK
            textPaint.color = baseColor
            canvas.drawText(countText, w / 2f, 30f + textPaint.textSize, textStrokePaint)
            canvas.drawText(countText, w / 2f, 30f + textPaint.textSize, textPaint)
        }
    }
}