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
import com.system.network.ui.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * CyberBubbleView: Vista de alta fidelidad para la burbuja flotante de Freezy.
 * 
 * Modos de renderizado:
 * 1. DESACTIVADO (MODO STANDBY CYBER-TECH VIBRANTE):
 *    - Fondo con degradado tecnológico rico en color (Cian Profundo / Púrpura Eléctrico).
 *    - Anillo exterior de neón con resplandor pulsante, retículas concéntricas y 12 marcadores láser.
 *    - Núcleo de energía ambiental y silueta runner en neón vivo de alto contraste.
 * 
 * 2. ACTIVADO (CRISTAL CONGELADO CON ILUMINACIÓN EN EL CENTRO):
 *    - Cero bordes circulares lineales ni indicadores de tiempo perimetrales.
 *    - Silueta orgánica asimétrica de esquirlas y picos de hielo (Ice Spikes & Frost Needles).
 *    - Núcleo central ultra-iluminado con resplandor radiante, destello de estrella de hielo y refracción 3D.
 *    - Grietas internas (Frost Cracks) y dendritas de nieve sobresalientes.
 */
class CyberBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var isRootMode: Boolean = false
    private var isActive: Boolean = false

    // Animadores de respiración y pulso de escarcha
    private var breathAnimator: ValueAnimator? = null
    private var breathValue: Float = 0.5f // 0f a 1f

    private var plasmaAnimator: ValueAnimator? = null
    private var plasmaPulse: Float = 0f // 0f a 1f

    // Paints optimizados para 60 FPS sin GC overhead
    private val chassisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Paints especializados para la estructura de cristal de hielo e iluminación central
    private val crystalFacetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val crystalEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.ROUND
    }
    private val frostSpikePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.MITER
    }
    private val frostCrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val frostDendritePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val centerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val centerFlarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Geometrías y Paths precalculados
    private val boundsRect = RectF()
    private val innerArcRect = RectF()
    private val spikePath = Path()
    private val facetPath = Path()
    private val crackPath = Path()

    // Datos fijos de los picos de hielo (16 ángulos y multiplicadores de radio para forma orgánica asimétrica)
    private val spikeMultipliers = floatArrayOf(
        1.16f, 0.78f, 1.05f, 0.82f,
        1.18f, 0.75f, 1.08f, 0.80f,
        1.15f, 0.76f, 1.10f, 0.79f,
        1.20f, 0.77f, 1.04f, 0.81f
    )

    private val spikeAngles = FloatArray(16) { i -> i * (360f / 16f) }

    private var runnerDrawable: Drawable? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            isForceDarkAllowed = false
        }

        try {
            val d = ContextCompat.getDrawable(context, R.drawable.ic_cyber_runner)
            runnerDrawable = d?.mutate()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        startBreathAnimation()
    }

    private fun startBreathAnimation() {
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
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

    private fun startPlasmaAnimation() {
        plasmaAnimator?.cancel()
        plasmaAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                plasmaPulse = it.animatedValue as Float
                if (isActive) {
                    invalidate()
                }
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
            if (active) {
                startPlasmaAnimation()
            } else {
                plasmaAnimator?.cancel()
                plasmaAnimator = null
            }
            invalidate()
        }
    }

    /**
     * Mantenido por retrocompatibilidad con servicios externos.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setProgress(value: Float) {
        // No-op: Se eliminó el modo de tiempo circular
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        boundsRect.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (width.coerceAtMost(height) / 2f) * 0.74f

        if (baseRadius <= 0) return

        // Paleta cromática dinámica
        val primaryColor = if (isRootMode) Color.parseColor("#B026FF") else Color.parseColor("#00E5FF")
        val secondaryFrost = if (isRootMode) Color.parseColor("#F5D0FE") else Color.parseColor("#E0F7FA")
        val accentShimmer = if (isRootMode) Color.parseColor("#D946EF") else Color.parseColor("#38BDF8")
        val coreGlowColor = if (isRootMode) Color.parseColor("#7E22CE") else Color.parseColor("#0284C7")
        val darkChassisCore = if (isRootMode) Color.parseColor("#280B44") else Color.parseColor("#082B40")
        val deepVoid = if (isRootMode) Color.parseColor("#11041D") else Color.parseColor("#020E18")

        if (!isActive) {
            // ============================================================
            // ESTADO 1: DESACTIVADO (MODO STANDBY CYBER-TECH VIBRANTE Y CON COLOR)
            // ============================================================
            val radius = baseRadius * 1.08f

            // 1. Chasis Tecnológico con Degradado Radial Vibrante
            val chassisShader = RadialGradient(
                cx, cy, radius * 1.05f,
                intArrayOf(darkChassisCore, deepVoid, Color.parseColor("#03060A")),
                floatArrayOf(0f, 0.70f, 1f),
                Shader.TileMode.CLAMP
            )
            chassisPaint.shader = chassisShader
            canvas.drawCircle(cx, cy, radius, chassisPaint)

            // 2. Núcleo de Energía Ambiental en Reposo (Resplandor central que respira)
            val ambientCoreShader = RadialGradient(
                cx, cy, radius * 0.55f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(primaryColor, (60 + (breathValue * 45)).toInt()),
                    ColorUtils.setAlphaComponent(coreGlowColor, (30 + (breathValue * 25)).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            centerGlowPaint.shader = ambientCoreShader
            canvas.drawCircle(cx, cy, radius * 0.55f, centerGlowPaint)

            // 3. Anillo Perimetral Neón con Sombra y Pulso de Color
            borderPaint.color = primaryColor
            borderPaint.strokeWidth = 2.8f * resources.displayMetrics.density
            borderPaint.alpha = (175 + (breathValue * 80)).toInt()
            borderPaint.setShadowLayer(
                6f * resources.displayMetrics.density,
                0f, 0f,
                ColorUtils.setAlphaComponent(primaryColor, 180)
            )
            canvas.drawCircle(cx, cy, radius - (borderPaint.strokeWidth / 2f), borderPaint)
            borderPaint.clearShadowLayer()

            // 4. Retícula Tecnológica Interna Segmentada
            innerRingPaint.color = ColorUtils.blendARGB(primaryColor, Color.WHITE, 0.35f)
            innerRingPaint.strokeWidth = 1.2f * resources.displayMetrics.density
            innerRingPaint.alpha = (100 + (breathValue * 60)).toInt()
            val innerR = radius * 0.84f
            innerArcRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)

            // 4 segmentos de arco tecnológico
            for (seg in 0 until 4) {
                val startAngle = seg * 90f + 10f
                canvas.drawArc(innerArcRect, startAngle, 70f, false, innerRingPaint)
            }

            // 5. 12 Marcadores Láser con Pips Brillantes en las Puntas
            val tickCount = 12
            val tickInner = radius * 0.68f
            val tickOuter = radius * 0.82f
            tickPaint.color = ColorUtils.blendARGB(primaryColor, Color.WHITE, 0.2f)
            tickPaint.strokeWidth = 1.5f * resources.displayMetrics.density
            tickPaint.alpha = (130 + (breathValue * 80)).toInt()

            pipPaint.color = primaryColor
            pipPaint.alpha = (180 + (breathValue * 75)).toInt()
            val pipRadius = 1.4f * resources.displayMetrics.density

            for (i in 0 until tickCount) {
                val angleDeg = i * (360f / tickCount)
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val cosA = cos(angleRad).toFloat()
                val sinA = sin(angleRad).toFloat()

                val x1 = cx + (tickInner * cosA)
                val y1 = cy + (tickInner * sinA)
                val x2 = cx + (tickOuter * cosA)
                val y2 = cy + (tickOuter * sinA)
                canvas.drawLine(x1, y1, x2, y2, tickPaint)

                // Punto luminoso (Pip) en los ejes cardinales (0, 90, 180, 270)
                if (i % 3 == 0) {
                    val px = cx + (tickInner * 0.92f * cosA)
                    val py = cy + (tickInner * 0.92f * sinA)
                    canvas.drawCircle(px, py, pipRadius, pipPaint)
                }
            }

            // 6. Icono Central Runner en Neón Vibrante con Respiración
            drawRunnerIcon(canvas, cx, cy, radius, primaryColor, false)

        } else {
            // ============================================================
            // ESTADO 2: ACTIVADO (CRISTAL CONGELADO CON ILUMINACIÓN EN EL CENTRO)
            // Cero bordes circulares lineales: Silueta orgánica de picos y destello nuclear
            // ============================================================

            // 1. Base Glacial de Fondo
            val coreShader = RadialGradient(
                cx, cy, baseRadius * 1.25f,
                intArrayOf(
                    ColorUtils.blendARGB(darkChassisCore, primaryColor, 0.6f + (plasmaPulse * 0.3f)),
                    darkChassisCore,
                    deepVoid
                ),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            chassisPaint.shader = coreShader
            drawJaggedCrystalBase(canvas, cx, cy, baseRadius, chassisPaint)

            // 2. Facetas Geométricas de Prisma 3D (Refracción de Cristal de Hielo)
            drawCrystalPrismFacets(canvas, cx, cy, baseRadius, primaryColor, secondaryFrost, accentShimmer)

            // 3. Fracturas y Grietas Internas de Hielo (Frost Cracks)
            drawFrostCracks(canvas, cx, cy, baseRadius, primaryColor)

            // 4. Picos de Nieve y Escarcha que sobresalen (Frost Dendrites & Spicules)
            drawSnowDendrites(canvas, cx, cy, baseRadius, secondaryFrost)

            // 5. Contorno Afilado de Cristal con Picos (Ice Shards Outer Shell)
            drawJaggedCrystalOutline(canvas, cx, cy, baseRadius, primaryColor)

            // 6. ILUMINACIÓN RADIANTE EN EL CENTRO (Reactor Glacial / Central Bloom)
            drawCenterGlacialIllumination(canvas, cx, cy, baseRadius, primaryColor, secondaryFrost)

            // 7. Icono Central Runner en Modo Lanza-Hielo Puro
            drawRunnerIcon(canvas, cx, cy, baseRadius, primaryColor, true)
        }
    }

    /**
     * Dibuja la iluminación central radiante al activarse la congelación.
     */
    private fun drawCenterGlacialIllumination(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        baseRadius: Float,
        primaryColor: Int,
        secondaryFrost: Int
    ) {
        val glowRadius = baseRadius * (0.68f + (plasmaPulse * 0.15f))

        // 1. Halo Radial Ultra-Luminoso (Blanco Glacial en el centro -> Neón Primario -> Transparente)
        val centerGlowShader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.WHITE,
                ColorUtils.blendARGB(Color.WHITE, secondaryFrost, 0.5f),
                ColorUtils.setAlphaComponent(primaryColor, (210 + (plasmaPulse * 45)).toInt()),
                ColorUtils.setAlphaComponent(primaryColor, (70 + (plasmaPulse * 40)).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.22f, 0.52f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        centerGlowPaint.shader = centerGlowShader
        canvas.drawCircle(cx, cy, glowRadius, centerGlowPaint)

        // 2. Destello de Estrella de Hielo de 4 Puntas (Cross Flare)
        val flareLength = baseRadius * (0.60f + (plasmaPulse * 0.20f))
        val flareWidth = 2.8f * resources.displayMetrics.density
        centerFlarePaint.color = Color.WHITE
        centerFlarePaint.strokeWidth = flareWidth
        centerFlarePaint.alpha = (200 + (plasmaPulse * 55)).toInt()
        centerFlarePaint.setShadowLayer(6f * resources.displayMetrics.density, 0f, 0f, primaryColor)

        // Eje Horizontal y Vertical
        canvas.drawLine(cx - flareLength, cy, cx + flareLength, cy, centerFlarePaint)
        canvas.drawLine(cx, cy - flareLength, cx, cy + flareLength, centerFlarePaint)

        // Destellos Diagonales Menores
        val diagLength = flareLength * 0.55f
        centerFlarePaint.strokeWidth = flareWidth * 0.6f
        centerFlarePaint.color = secondaryFrost
        canvas.drawLine(cx - diagLength, cy - diagLength, cx + diagLength, cy + diagLength, centerFlarePaint)
        canvas.drawLine(cx - diagLength, cy + diagLength, cx + diagLength, cy - diagLength, centerFlarePaint)

        centerFlarePaint.clearShadowLayer()
    }

    /**
     * Dibuja la base poligonal irregular del cristal congelado.
     */
    private fun drawJaggedCrystalBase(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        baseRadius: Float,
        paint: Paint
    ) {
        spikePath.reset()
        val count = spikeAngles.size

        for (i in 0 until count) {
            val angleRad = Math.toRadians(spikeAngles[i].toDouble())
            val pulseFactor = 1f + (plasmaPulse * 0.03f * if (i % 2 == 0) 1f else -1f)
            val r = baseRadius * spikeMultipliers[i] * pulseFactor
            val x = cx + (r * cos(angleRad)).toFloat()
            val y = cy + (r * sin(angleRad)).toFloat()

            if (i == 0) {
                spikePath.moveTo(x, y)
            } else {
                spikePath.lineTo(x, y)
            }
        }
        spikePath.close()
        canvas.drawPath(spikePath, paint)
    }

    /**
     * Dibuja las facetas 3D triangulares del cristal de hielo con refracción translúcida.
     */
    private fun drawCrystalPrismFacets(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        baseRadius: Float,
        primaryColor: Int,
        secondaryFrost: Int,
        accentShimmer: Int
    ) {
        val count = spikeAngles.size
        crystalFacetPaint.style = Paint.Style.FILL

        for (i in 0 until count) {
            val nextIdx = (i + 1) % count
            val a1 = Math.toRadians(spikeAngles[i].toDouble())
            val a2 = Math.toRadians(spikeAngles[nextIdx].toDouble())

            val r1 = baseRadius * spikeMultipliers[i]
            val r2 = baseRadius * spikeMultipliers[nextIdx]

            val x1 = cx + (r1 * cos(a1)).toFloat()
            val y1 = cy + (r1 * sin(a1)).toFloat()
            val x2 = cx + (r2 * cos(a2)).toFloat()
            val y2 = cy + (r2 * sin(a2)).toFloat()

            facetPath.reset()
            facetPath.moveTo(cx, cy)
            facetPath.lineTo(x1, y1)
            facetPath.lineTo(x2, y2)
            facetPath.close()

            val facetAlpha: Int
            val facetColor: Int

            when (i % 4) {
                0 -> {
                    facetColor = secondaryFrost
                    facetAlpha = (120 + (plasmaPulse * 65)).toInt()
                }
                1 -> {
                    facetColor = primaryColor
                    facetAlpha = (150 + (plasmaPulse * 75)).toInt()
                }
                2 -> {
                    facetColor = accentShimmer
                    facetAlpha = (100 + (plasmaPulse * 50)).toInt()
                }
                else -> {
                    facetColor = Color.WHITE
                    facetAlpha = (135 + (plasmaPulse * 80)).toInt()
                }
            }

            crystalFacetPaint.color = facetColor
            crystalFacetPaint.alpha = facetAlpha
            canvas.drawPath(facetPath, crystalFacetPaint)

            // Línea de bisel / arista cristalina afilada
            crystalEdgePaint.color = ColorUtils.blendARGB(primaryColor, Color.WHITE, 0.50f)
            crystalEdgePaint.strokeWidth = 1.2f * resources.displayMetrics.density
            crystalEdgePaint.alpha = (150 + (plasmaPulse * 85)).toInt()
            canvas.drawLine(cx, cy, x1, y1, crystalEdgePaint)
        }
    }

    /**
     * Dibuja fracturas/grietas de hielo luminosas en zigzag.
     */
    private fun drawFrostCracks(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        baseRadius: Float,
        primaryColor: Int
    ) {
        frostCrackPaint.color = ColorUtils.blendARGB(Color.WHITE, primaryColor, 0.3f)
        frostCrackPaint.strokeWidth = 1.8f * resources.displayMetrics.density
        frostCrackPaint.alpha = (180 + (plasmaPulse * 75)).toInt()

        val majorPeaks = intArrayOf(0, 4, 8, 12)
        for (idx in majorPeaks) {
            val angleRad = Math.toRadians(spikeAngles[idx].toDouble())
            val midR1 = baseRadius * 0.40f
            val midR2 = baseRadius * 0.75f
            val peakR = baseRadius * spikeMultipliers[idx] * 0.96f

            val jiggle1 = 0.12f * (if (idx % 2 == 0) 1f else -1f)
            val jiggle2 = 0.08f * (if (idx % 2 == 0) -1f else 1f)

            crackPath.reset()
            crackPath.moveTo(cx, cy)
            crackPath.lineTo(
                cx + (midR1 * cos(angleRad + jiggle1)).toFloat(),
                cy + (midR1 * sin(angleRad + jiggle1)).toFloat()
            )
            crackPath.lineTo(
                cx + (midR2 * cos(angleRad + jiggle2)).toFloat(),
                cy + (midR2 * sin(angleRad + jiggle2)).toFloat()
            )
            crackPath.lineTo(
                cx + (peakR * cos(angleRad)).toFloat(),
                cy + (peakR * sin(angleRad)).toFloat()
            )

            canvas.drawPath(crackPath, frostCrackPaint)
        }
    }

    /**
     * Dibuja espículas de nieve y dendritas afiladas que sobresalen de los picos.
     */
    private fun drawSnowDendrites(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        baseRadius: Float,
        secondaryFrost: Int
    ) {
        frostDendritePaint.strokeWidth = 1.5f * resources.displayMetrics.density
        val count = spikeAngles.size

        for (i in 0 until count step 2) {
            val angleRad = Math.toRadians(spikeAngles[i].toDouble())
            val peakR = baseRadius * spikeMultipliers[i]
            val px = cx + (peakR * cos(angleRad)).toFloat()
            val py = cy + (peakR * sin(angleRad)).toFloat()

            val extLength = 8f * resources.displayMetrics.density
            val pExtX = px + (extLength * cos(angleRad)).toFloat()
            val pExtY = py + (extLength * sin(angleRad)).toFloat()

            frostDendritePaint.color = Color.WHITE
            frostDendritePaint.alpha = (200 + (plasmaPulse * 55)).toInt()
            canvas.drawLine(px, py, pExtX, pExtY, frostDendritePaint)

            val barbAngle1 = angleRad + Math.toRadians(40.0)
            val barbAngle2 = angleRad - Math.toRadians(40.0)
            val barbLen = 4.5f * resources.displayMetrics.density

            frostDendritePaint.color = secondaryFrost
            frostDendritePaint.alpha = (160 + (plasmaPulse * 70)).toInt()

            canvas.drawLine(
                px, py,
                px + (barbLen * cos(barbAngle1)).toFloat(),
                py + (barbLen * sin(barbAngle1)).toFloat(),
                frostDendritePaint
            )
            canvas.drawLine(
                px, py,
                px + (barbLen * cos(barbAngle2)).toFloat(),
                py + (barbLen * sin(barbAngle2)).toFloat(),
                frostDendritePaint
            )
        }
    }

    /**
     * Dibuja el contorno cristalino afilado con halo y puntas de hielo brillante.
     */
    private fun drawJaggedCrystalOutline(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        baseRadius: Float,
        primaryColor: Int
    ) {
        spikePath.reset()
        val count = spikeAngles.size

        for (i in 0 until count) {
            val angleRad = Math.toRadians(spikeAngles[i].toDouble())
            val r = baseRadius * spikeMultipliers[i]
            val x = cx + (r * cos(angleRad)).toFloat()
            val y = cy + (r * sin(angleRad)).toFloat()

            if (i == 0) {
                spikePath.moveTo(x, y)
            } else {
                spikePath.lineTo(x, y)
            }
        }
        spikePath.close()

        // 1. Resplandor Neón de los picos
        frostSpikePaint.style = Paint.Style.STROKE
        frostSpikePaint.strokeWidth = 3.5f * resources.displayMetrics.density
        frostSpikePaint.color = primaryColor
        frostSpikePaint.alpha = (190 + (plasmaPulse * 65)).toInt()
        frostSpikePaint.setShadowLayer(8f * resources.displayMetrics.density, 0f, 0f, primaryColor)
        canvas.drawPath(spikePath, frostSpikePaint)
        frostSpikePaint.clearShadowLayer()

        // 2. Línea de borde glacial en blanco escarcha
        frostSpikePaint.strokeWidth = 1.6f * resources.displayMetrics.density
        frostSpikePaint.color = Color.WHITE
        frostSpikePaint.alpha = 245
        canvas.drawPath(spikePath, frostSpikePaint)
    }

    /**
     * Dibuja el icono del corredor central con efectos de iluminación.
     */
    private fun drawRunnerIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        primaryColor: Int,
        isFrozenActive: Boolean
    ) {
        runnerDrawable?.let { drawable ->
            val iconSize = (radius * 1.15f).toInt()
            val left = (cx - iconSize / 2f).toInt()
            val top = (cy - iconSize / 2f).toInt()
            drawable.setBounds(left, top, left + iconSize, top + iconSize)

            if (isFrozenActive) {
                // Iluminación intensa en blanco puro sobre el resplandor central
                DrawableCompat.setTint(drawable, Color.WHITE)
                drawable.alpha = 255
            } else {
                // Standby: Color primario neón vibrante con respiración luminosa
                val tintColor = ColorUtils.blendARGB(
                    primaryColor,
                    Color.WHITE,
                    0.28f + (breathValue * 0.32f)
                )
                DrawableCompat.setTint(drawable, tintColor)
                drawable.alpha = (200 + (breathValue * 55)).toInt()
            }
            drawable.draw(canvas)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        breathAnimator?.cancel()
        plasmaAnimator?.cancel()
    }
}
