package com.pingpong.tracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * High-performance animated background rendering ~1,800 ultra-fine rose-quartz dust particles
 * drifting in fluid curved currents across a pure porcelain-white canvas at 60fps.
 * Uses primitive FloatArrays + Canvas.drawPoints for zero per-frame allocations.
 */
class PinkDustBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    companion object {
        private const val PARTICLE_COUNT = 1800
        private const val LAYER_COUNT = 4
    }

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ribbonPath = Path()

    // 4 depth/size/opacity layers for ultra-fine stardust mist effect
    private val layerPaints = arrayOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55F9A8D4.toInt() // Ultra-fine blush mist
            strokeWidth = 1.1f * density
            strokeCap = Paint.Cap.ROUND
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x70F472B6.toInt() // Fine pink dust
            strokeWidth = 1.6f * density
            strokeCap = Paint.Cap.ROUND
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88EC4899.toInt() // Vibrant rose micro-grains
            strokeWidth = 2.1f * density
            strokeCap = Paint.Cap.ROUND
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x52DB2777.toInt() // Deep rose accents
            strokeWidth = 2.7f * density
            strokeCap = Paint.Cap.ROUND
        },
    )

    // Primitive SoA (Structure of Arrays) for zero-GC 60fps updates
    private val pCurve = IntArray(PARTICLE_COUNT)
    private val pLayer = IntArray(PARTICLE_COUNT)
    private val pProgress = FloatArray(PARTICLE_COUNT)
    private val pSpeed = FloatArray(PARTICLE_COUNT)
    private val pSpread = FloatArray(PARTICLE_COUNT)
    private val pFreq = FloatArray(PARTICLE_COUNT)
    private val pPhase = FloatArray(PARTICLE_COUNT)

    // Pre-allocated point buffers per layer
    private val layerPoints: Array<FloatArray>
    private val layerCounts = IntArray(LAYER_COUNT)

    private var running = false
    private var lastFrameNanos = 0L
    private var elapsedSec = 0f

    init {
        val rng = Random(202611)
        val perLayerMax = IntArray(LAYER_COUNT)
        for (i in 0 until PARTICLE_COUNT) {
            val layer = when {
                i % 10 < 4 -> 0 // 40% ultra-fine mist
                i % 10 < 7 -> 1 // 30% fine dust
                i % 10 < 9 -> 2 // 20% rose grains
                else -> 3       // 10% brighter accents
            }
            pCurve[i] = i % 3
            pLayer[i] = layer
            perLayerMax[layer]++

            pProgress[i] = rng.nextFloat() * 1.12f - 0.06f
            pSpeed[i] = (0.012f + rng.nextFloat() * 0.028f) * (0.75f + layer * 0.15f)
            // Gaussian-like concentrated stream around each curve
            val gauss = (rng.nextFloat() + rng.nextFloat() + rng.nextFloat() - 1.5f) / 1.5f
            pSpread[i] = gauss * (58f + layer * 14f) * density
            pFreq[i] = 1.5f + rng.nextFloat() * 3.0f
            pPhase[i] = rng.nextFloat() * (2f * PI.toFloat())
        }
        layerPoints = Array(LAYER_COUNT) { l -> FloatArray(perLayerMax[l] * 2) }
    }

    fun startAnimation() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopAnimation() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos != 0L) {
            val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            elapsedSec += dt
            for (i in 0 until PARTICLE_COUNT) {
                var prog = pProgress[i] + pSpeed[i] * dt
                if (prog > 1.06f) prog = -0.06f
                pProgress[i] = prog
            }
        }
        lastFrameNanos = frameTimeNanos
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            bgPaint.shader = LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                intArrayOf(
                    0xFFFFFFFF.toInt(),
                    0xFFFFF8FB.toInt(),
                    0xFFFFF1F6.toInt(),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    private fun curveY(curveIndex: Int, t: Float, h: Float, time: Float): Float {
        return when (curveIndex) {
            0 -> {
                val base = h * (0.12f + 0.16f * t)
                val w1 = sin((t * 3.8f + time * 0.42f).toDouble()).toFloat() * (h * 0.055f)
                val w2 = cos((t * 2.2f - time * 0.28f).toDouble()).toFloat() * (h * 0.022f)
                base + w1 + w2
            }
            1 -> {
                val base = h * (0.50f - 0.14f * t)
                val w1 = cos((t * 3.4f - time * 0.36f + 1.1f).toDouble()).toFloat() * (h * 0.065f)
                val w2 = sin((t * 5.2f + time * 0.24f).toDouble()).toFloat() * (h * 0.018f)
                base + w1 + w2
            }
            else -> {
                val base = h * (0.84f - 0.10f * t)
                val w1 = sin((t * 4.1f + time * 0.48f + 2.3f).toDouble()).toFloat() * (h * 0.05f)
                base + w1
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Pure porcelain-white silk canvas
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Ultra-delicate silk current ribbons
        val ribbonColors = intArrayOf(0x1CEC4899, 0x16F472B6, 0x18DB2777)
        for (c in 0..2) {
            ribbonPath.reset()
            val steps = 32
            for (s in 0..steps) {
                val t = s / steps.toFloat()
                val x = -w * 0.05f + t * w * 1.10f
                val y = curveY(c, t, h, elapsedSec)
                if (s == 0) ribbonPath.moveTo(x, y) else ribbonPath.lineTo(x, y)
            }
            ribbonPaint.color = ribbonColors[c]
            ribbonPaint.strokeWidth = 10f * density
            canvas.drawPath(ribbonPath, ribbonPaint)
        }

        // 3. Batch-draw all 1,800 fine pink dust micro-particles
        layerCounts.fill(0)
        val xStart = -w * 0.06f
        val xSpan = w * 1.12f

        for (i in 0 until PARTICLE_COUNT) {
            val t = pProgress[i]
            val x = xStart + t * xSpan
            val baseY = curveY(pCurve[i], t, h, elapsedSec)
            val flutter = sin((t * pFreq[i] * 4.0f + elapsedSec * 0.85f + pPhase[i]).toDouble()).toFloat()
            val y = baseY + pSpread[i] * (0.65f + 0.35f * flutter)

            val layer = pLayer[i]
            val idx = layerCounts[layer]
            val buf = layerPoints[layer]
            if (idx + 1 < buf.size) {
                buf[idx] = x
                buf[idx + 1] = y
                layerCounts[layer] = idx + 2
            }
        }

        for (l in 0 until LAYER_COUNT) {
            val count = layerCounts[l]
            if (count > 0) {
                canvas.drawPoints(layerPoints[l], 0, count, layerPaints[l])
            }
        }
    }
}
