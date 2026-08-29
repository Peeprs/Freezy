package com.freezy.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import android.animation.ValueAnimator
import kotlin.math.max
import kotlin.math.sin

/** Línea temporal de licencia con cabezal de progreso y pulso ondulado sutil. */
class LicenseTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ProgressBar(context, attrs, android.R.attr.progressBarStyleHorizontal) {

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26313C")
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * density
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * density
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.5f * density
    }
    private val endpointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#111820")
    }
    private val endpointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#52606D")
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val wavePath = Path()
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        progressDrawable = null
        isIndeterminate = false
        max = 100
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val startX = 8f * density
        val endX = width - 8f * density
        val centerY = height / 2f
        val fraction = (progress.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)
        val headX = startX + (endX - startX) * fraction
        val accent = progressTintList?.defaultColor ?: Color.parseColor("#00E5FF")

        canvas.drawLine(startX, centerY, endX, centerY, trackPaint)

        progressPaint.color = accent
        canvas.drawLine(startX, centerY, headX, centerY, progressPaint)

        // Pulso ondulado pequeño: parece desplazarse por el tramo completado.
        if (headX - startX > 6f * density) {
            wavePaint.color = withAlpha(accent, 180)
            wavePath.reset()
            val amplitude = 2.2f * density
            val wavelength = 22f * density
            var x = startX
            while (x <= headX) {
                val y = centerY + sin(((x - startX) / wavelength + phase * 2f) * Math.PI * 2.0).toFloat() * amplitude
                if (x == startX) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                x += max(2f, density * 1.5f)
            }
            canvas.drawPath(wavePath, wavePaint)
        }

        // Destino: círculo hueco y sobrio.
        canvas.drawCircle(endX, centerY, 6f * density, endpointPaint)
        canvas.drawCircle(endX, centerY, 5f * density, endpointStrokePaint)

        // Avance actual: halo luminoso, cuerpo sólido y núcleo blanco.
        headPaint.shader = RadialGradient(
            headX,
            centerY,
            9f * density,
            intArrayOf(withAlpha(accent, 235), withAlpha(accent, 80), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(headX, centerY, 9f * density, headPaint)
        headPaint.shader = null
        headPaint.color = accent
        canvas.drawCircle(headX, centerY, 5.5f * density, headPaint)
        canvas.drawCircle(headX, centerY, 2f * density, headCorePaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
