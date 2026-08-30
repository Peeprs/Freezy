package com.freezy.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import com.freezy.publicapp.R

/**
 * CyberBubbleView: Burbuja orbital de alta fidelidad.
 * 
 * - En reposo: Aro fino que gira suavemente alrededor del copo + fondo transparente.
 * - Al activar: El aro se acelera e ilumina con resplandor neón intenso + copo brillante.
 * - Colores 100% centralizados en res/values/colors.xml.
 */
class CyberBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var isRootMode: Boolean = false
    private var isActive: Boolean = false

    // Rotación continua del aro orbital
    private var ringRotationAnimator: ValueAnimator? = null
    private var rotationAngle: Float = 0f

    // Pulso suave de respiración
    private var breathAnimator: ValueAnimator? = null
    private var breathValue: Float = 0.5f

    // Paints optimizados
    private val ringOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val ringRect = RectF()
    private val clipPath = Path()
    private var snowflakeDrawable: Drawable? = null

    init {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            isForceDarkAllowed = false
        }

        try {
            snowflakeDrawable = ContextCompat.getDrawable(context, R.drawable.ic_freezy_snowflake)?.mutate()
        } catch (_: Exception) {}

        startBreathAnimation()
        startRingRotation()
    }

    private fun startBreathAnimation() {
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                breathValue = it.animatedValue as Float
                if (!isActive) {
                    invalidate()
                }
            }
            start()
        }
    }

    private fun startRingRotation() {
        ringRotationAnimator?.cancel()
        val durationMs = if (isActive) 750L else 2400L
        ringRotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setMode(root: Boolean) {
        if (this.isRootMode != root) {
            this.isRootMode = root
            invalidate()
        }
    }

    fun setActiveState(active: Boolean) {
        if (this.isActive != active) {
            this.isActive = active
            startRingRotation()
            invalidate()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setProgress(value: Float) {
        // Compatibilidad retroactiva
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val minDim = width.coerceAtMost(height).toFloat()
        if (minDim <= 0) return

        // Colores centralizados desde ThemeColors / colors.xml
        val primaryColor = ThemeColors.getPrimary(context, isRootMode)
        val neonColor = ThemeColors.getNeon(context, isRootMode)
        val glowColor = ThemeColors.getGlow(context, isRootMode)

        val density = resources.displayMetrics.density
        val ringRadius = minDim * 0.42f
        ringRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)

        // Clip circular exacto para evitar cualquier corte o fuga cuadrada
        clipPath.reset()
        clipPath.addCircle(cx, cy, minDim * 0.49f, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        if (isActive) {
            // ================================================================
            // ESTADO ACTIVADO: CÍRCULO NEÓN PURO Y RADIANTE (SIN FONDO CUADRADO)
            // ================================================================

            canvas.save()
            canvas.rotate(rotationAngle, cx, cy)

            // 1. Resplandor exterior suave del aro
            ringOuterPaint.color = glowColor
            ringOuterPaint.strokeWidth = 4.2f * density
            ringOuterPaint.alpha = 160
            canvas.drawArc(ringRect, 0f, 130f, false, ringOuterPaint)
            canvas.drawArc(ringRect, 180f, 130f, false, ringOuterPaint)

            // 2. Núcleo Neón Intenso del aro
            ringCorePaint.color = neonColor
            ringCorePaint.strokeWidth = 2.4f * density
            ringCorePaint.alpha = 255
            canvas.drawArc(ringRect, 0f, 130f, false, ringCorePaint)
            canvas.drawArc(ringRect, 180f, 130f, false, ringCorePaint)

            // 3. Filamento central blanco brillante (Efecto Láser Neón)
            ringHighlightPaint.color = Color.WHITE
            ringHighlightPaint.strokeWidth = 1.0f * density
            ringHighlightPaint.alpha = 220
            canvas.drawArc(ringRect, 10f, 110f, false, ringHighlightPaint)
            canvas.drawArc(ringRect, 190f, 110f, false, ringHighlightPaint)

            canvas.restore()

            // 4. Cabezales orbitales en las puntas
            val rad0 = Math.toRadians(rotationAngle.toDouble())
            val rad180 = Math.toRadians((rotationAngle + 180).toDouble())
            val hx1 = cx + (ringRadius * Math.cos(rad0)).toFloat()
            val hy1 = cy + (ringRadius * Math.sin(rad0)).toFloat()
            val hx2 = cx + (ringRadius * Math.cos(rad180)).toFloat()
            val hy2 = cy + (ringRadius * Math.sin(rad180)).toFloat()

            dotPaint.color = neonColor
            dotPaint.alpha = 255
            canvas.drawCircle(hx1, hy1, 3.5f * density, dotPaint)
            canvas.drawCircle(hx2, hy2, 3.5f * density, dotPaint)
            canvas.drawCircle(hx1, hy1, 1.8f * density, dotCorePaint)
            canvas.drawCircle(hx2, hy2, 1.8f * density, dotCorePaint)

            // 5. Copo de Nieve Neón en el centro
            drawSnowflake(canvas, cx, cy, minDim * 0.68f, neonColor, 255)

        } else {
            // ================================================================
            // ESTADO REPOSO: ARO SUAVE GIRATORIO + COPO ELEGANTE
            // ================================================================

            canvas.save()
            canvas.rotate(rotationAngle, cx, cy)

            ringCorePaint.color = primaryColor
            ringCorePaint.strokeWidth = 1.8f * density
            val breathAlpha = (110 + (breathValue * 45)).toInt().coerceIn(0, 255)
            ringCorePaint.alpha = breathAlpha

            canvas.drawArc(ringRect, 0f, 120f, false, ringCorePaint)
            canvas.drawArc(ringRect, 180f, 120f, false, ringCorePaint)

            canvas.restore()

            val rad0 = Math.toRadians(rotationAngle.toDouble())
            val rad180 = Math.toRadians((rotationAngle + 180).toDouble())
            val hx1 = cx + (ringRadius * Math.cos(rad0)).toFloat()
            val hy1 = cy + (ringRadius * Math.sin(rad0)).toFloat()
            val hx2 = cx + (ringRadius * Math.cos(rad180)).toFloat()
            val hy2 = cy + (ringRadius * Math.sin(rad180)).toFloat()

            dotPaint.color = primaryColor
            dotPaint.alpha = (140 + (breathValue * 40)).toInt().coerceIn(0, 255)
            canvas.drawCircle(hx1, hy1, 2.2f * density, dotPaint)
            canvas.drawCircle(hx2, hy2, 2.2f * density, dotPaint)

            val alphaBreath = (210 + (breathValue * 40)).toInt().coerceIn(0, 255)
            drawSnowflake(canvas, cx, cy, minDim * 0.70f, primaryColor, alphaBreath)
        }

        canvas.restore()
    }

    private fun drawSnowflake(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, alpha: Int) {
        val d = snowflakeDrawable ?: return
        val half = size / 2f
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        DrawableCompat.setTint(d, color)
        d.alpha = alpha
        d.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        ringRotationAnimator?.cancel()
        breathAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
