package com.freezy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import org.json.JSONObject

class EspOverlayView(context: Context) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 30f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val textStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        textSize = 30f
        textAlign = Paint.Align.CENTER
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

    private data class Bone(val x: Float, val y: Float)

    // Una entidad dibujada: esqueleto + si está derribada (rojo) o viva (color normal)
    private data class Skeleton(val bones: List<Bone>, val knocked: Boolean)

    private var skeletons: MutableList<Skeleton> = mutableListOf()

    private val pollThread = object : Thread("esp-poll") {
        override fun run() {
            while (running) {
                poll()
                try {
                    Thread.sleep(8)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private val paintRunnable = object : Runnable {
        override fun run() {
            if (running) postInvalidate()
        }
    }

    // Loop propio de animación RGB a ~60fps, independiente de la tasa de snapshots.
    private val rgbRunnable = object : Runnable {
        override fun run() {
            if (!running || !rgbMode) return
            postInvalidate()
            handler.postDelayed(this, 16)
        }
    }

    private var skeletonsLock = Any()

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
        handler.post(paintRunnable)
        if (rgbMode) {
            handler.removeCallbacks(rgbRunnable)
            handler.post(rgbRunnable)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(paintRunnable)
        handler.removeCallbacks(rgbRunnable)
        synchronized(skeletonsLock) { skeletons.clear() }
        postInvalidate()
    }

    private fun poll() {
        val jsonStr = try { NativeBridge.getEspSnapshot(pid) } catch (e: Exception) { null }
        if (jsonStr.isNullOrEmpty()) return
        try {
            val root = JSONObject(jsonStr)
            if (!root.optBoolean("ok", false)) return
            val ents = root.getJSONArray("entities")
            val count = ents.length()
            val newSkeletons = ArrayList<Skeleton>(count)
            for (i in 0 until count) {
                val ent = ents.getJSONObject(i)
                val skel = ent.optJSONArray("skel") ?: continue
                if (skel.length() < 28) continue
                val bones = ArrayList<Bone>(14)
                for (b in 0 until 14) {
                    bones.add(Bone(
                        skel.getDouble(b * 2).toFloat(),
                        skel.getDouble(b * 2 + 1).toFloat()
                    ))
                }
                newSkeletons.add(Skeleton(bones, ent.optBoolean("knocked", false)))
            }
            synchronized(skeletonsLock) {
                skeletons = newSkeletons
            }
            postInvalidate()
        } catch (e: Exception) {
            // JSON inválido: ignorar y reintentar en el siguiente ciclo
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bonesToDraw: List<Skeleton>
        synchronized(skeletonsLock) { bonesToDraw = skeletons }

        val w = width.toFloat()
        val h = height.toFloat()

        // Color RGB animado (si está activo) -> afecta el paint de línea
        var baseColor = lineColor
        if (rgbMode) {
            val t = System.currentTimeMillis() / 1000.0
            val hue = ((t * 180.0) % 360.0).toFloat()
            baseColor = Color.HSVToColor(255, floatArrayOf(hue, 1f, 1f))
        }
        linePaint.color = baseColor

        // ESP Count: enemigos vivos, arriba al centro a ~30px del borde
        if (showCount && bonesToDraw.isNotEmpty()) {
            val countText = "Enemigos: ${bonesToDraw.size}"
            textStrokePaint.color = Color.BLACK
            textPaint.color = linePaint.color
            canvas.drawText(countText, w / 2f, 30f + textPaint.textSize, textStrokePaint)
            canvas.drawText(countText, w / 2f, 30f + textPaint.textSize, textPaint)
        }

        if (bonesToDraw.isEmpty()) return

        val originY = when (lineOrigin) {
            0 -> h            // abajo
            1 -> h / 2f       // medio
            else -> 60f       // arriba
        }

        // Derribado (knocked) -> rojo
        val knockedColor = Color.RED

        for (s in bonesToDraw) {
            val b = s.bones
            if (b.size < 14) continue

            val paint = if (s.knocked) {
                linePaint.color = knockedColor
                linePaint
            } else {
                linePaint.color = baseColor
                linePaint
            }

            if (drawLines) {
                val head = b[0]
                if (head.x > 0 && head.y > 0) {
                    canvas.drawLine(w / 2f, originY, head.x, head.y, paint)
                }
            }

            if (drawBox) {
                val head = b[0]
                if (head.x > 0 && head.y > 0) {
                    // Alto: Referencia hueso de Head (b[0]) con margen superior
                    val neck = b[1]
                    val headOffset = if (neck.y > head.y) (neck.y - head.y) * 0.9f else 18f
                    val top = head.y - headOffset

                    // Base: Pies (b[12] pie izq, b[13] pie der)
                    val footYList = mutableListOf<Float>()
                    if (b[12].y > 0) footYList.add(b[12].y)
                    if (b[13].y > 0) footYList.add(b[13].y)

                    val bottom = if (footYList.isNotEmpty()) {
                        footYList.maxOrNull()!! + 6f
                    } else if (b[3].y > 0) { // Ingle
                        b[3].y + (b[3].y - head.y)
                    } else if (b[2].y > 0) { // Cadera
                        b[2].y + (b[2].y - head.y) * 1.2f
                    } else {
                        head.y + 100f
                    }

                    // Ancho: Brazos (hombros b[4], b[5] y muñecas b[8], b[9])
                    val armXList = mutableListOf<Float>()
                    if (b[4].x > 0) armXList.add(b[4].x)
                    if (b[5].x > 0) armXList.add(b[5].x)
                    if (b[8].x > 0) armXList.add(b[8].x)
                    if (b[9].x > 0) armXList.add(b[9].x)

                    var left: Float
                    var right: Float

                    if (armXList.isNotEmpty()) {
                        val minArmX = armXList.minOrNull()!!
                        val maxArmX = armXList.maxOrNull()!!
                        val armWidth = maxArmX - minArmX
                        val armPadding = if (armWidth > 10f) armWidth * 0.15f else 8f
                        left = minArmX - armPadding
                        right = maxArmX + armPadding

                        // Asegurar proporción mínima si el enemigo está de perfil
                        val boxHeight = bottom - top
                        if (boxHeight > 0 && (right - left) < boxHeight * 0.35f) {
                            val centerX = (minArmX + maxArmX) / 2f
                            val halfW = (boxHeight * 0.40f) / 2f
                            left = centerX - halfW
                            right = centerX + halfW
                        }
                    } else {
                        // Fallback si no hay huesos de brazos detectados
                        val boxHeight = bottom - top
                        val halfW = if (boxHeight > 0) (boxHeight * 0.40f) / 2f else 30f
                        left = head.x - halfW
                        right = head.x + halfW
                    }

                    if (bottom > top && right > left) {
                        canvas.drawRect(left, top, right, bottom, paint)
                    }
                }
            }

            if (!drawSkeleton) continue

            drawLine(canvas, b[0], b[1], paint)    // cabeza - cuello
            drawLine(canvas, b[1], b[2], paint)    // cuello - cadera
            drawLine(canvas, b[1], b[4], paint)    // cuello - hombro izq
            drawLine(canvas, b[1], b[5], paint)    // cuello - hombro der
            drawLine(canvas, b[4], b[8], paint)    // hombro izq - muñeca izq (codo omitido)
            drawLine(canvas, b[5], b[9], paint)    // hombro der - muñeca der (codo omitido)
            drawLine(canvas, b[2], b[3], paint)    // cadera - ingle
            drawLine(canvas, b[3], b[12], paint)   // ingle - pie izq (tobillo omitido)
            drawLine(canvas, b[3], b[13], paint)   // ingle - pie der (tobillo omitido)

            // articulaciones (codos y tobillos quedan en -1 y no se dibujan)
            for (i in 0 until 14) {
                if (i == 6 || i == 7 || i == 10 || i == 11) continue
                val bone = b[i]
                if (bone.x > 0 && bone.y > 0) {
                    canvas.drawCircle(bone.x, bone.y, 3f, paint)
                }
            }
        }
    }

    private fun drawLine(canvas: Canvas, a: Bone, b: Bone, paint: Paint) {
        if (a.x <= 0 || a.y <= 0 || b.x <= 0 || b.y <= 0) return
        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
    }
}