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
 * Animated background rendering flowing pink silk curves and fine drifting rose-quartz dust
 * particles over an alabaster-white canvas. Automatically pauses when detached or hidden.
 */
class PinkDustBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private data class DustParticle(
        val curveIndex: Int,
        var progress: Float,
        val speed: Float,
        val spread: Float,
        val spreadFreq: Float,
        val phase: Float,
        val radiusDp: Float,
        val baseAlpha: Int,
        val color: Int,
    )

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val curvePath = Path()

    private val palette = intArrayOf(
        0xFFEC4899.toInt(), // Rose 500
        0xFFF472B6.toInt(), // Pink 400
        0xFFDB2777.toInt(), // Pink 600
        0xFFF9A8D4.toInt(), // Pink 300
        0xFFE879F9.toInt(), // Soft Orchid
    )

    private val particles: List<DustParticle>
    private var running = false
    private var lastFrameNanos = 0L
    private var elapsedSec = 0f

    init {
        val rng = Random(2026)
        particles = List(96) { i ->
            DustParticle(
                curveIndex = i % 3,
                progress = rng.nextFloat(),
                speed = 0.018f + rng.nextFloat() * 0.032f,
                spread = (rng.nextFloat() - 0.5f) * 72f * density,
                spreadFreq = 1.2f + rng.nextFloat() * 2.4f,
                phase = rng.nextFloat() * (2f * PI.toFloat()),
                radiusDp = 1.1f + rng.nextFloat() * 2.5f,
                baseAlpha = 85 + rng.nextInt(145),
                color = palette[i % palette.size],
            )
        }
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
            for (p in particles) {
                p.progress += p.speed * dt
                if (p.progress > 1.08f) {
                    p.progress = -0.08f
                }
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
                    0xFFFFF7FA.toInt(),
                    0xFFFFF0F6.toInt(),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    private fun curveY(curveIndex: Int, t: Float, h: Float, time: Float): Float {
        return when (curveIndex) {
            0 -> {
                val base = h * (0.14f + 0.14f * t)
                val wave1 = sin((t * 3.8f + time * 0.45f).toDouble()).toFloat() * (h * 0.055f)
                val wave2 = cos((t * 2.1f - time * 0.30f).toDouble()).toFloat() * (h * 0.025f)
                base + wave1 + wave2
            }
            1 -> {
                val base = h * (0.50f - 0.12f * t)
                val wave1 = cos((t * 3.2f - time * 0.38f + 1.2f).toDouble()).toFloat() * (h * 0.065f)
                val wave2 = sin((t * 5.0f + time * 0.25f).toDouble()).toFloat() * (h * 0.02f)
                base + wave1 + wave2
            }
            else -> {
                val base = h * (0.82f - 0.08f * t)
                val wave1 = sin((t * 4.2f + time * 0.52f + 2.4f).toDouble()).toFloat() * (h * 0.05f)
                base + wave1
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Crisp porcelain-white silk backdrop
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Three flowing translucent rose silk ribbons
        val curveColors = intArrayOf(
            0x29EC4899,
            0x22F472B6,
            0x1FDB2777,
        )
        val strokeWidths = floatArrayOf(2.2f * density, 1.6f * density, 2.0f * density)

        for (c in 0..2) {
            curvePath.reset()
            val steps = 36
            for (s in 0..steps) {
                val t = s / steps.toFloat()
                val x = -w * 0.06f + t * w * 1.12f
                val y = curveY(c, t, h, elapsedSec)
                if (s == 0) {
                    curvePath.moveTo(x, y)
                } else {
                    curvePath.lineTo(x, y)
                }
            }
            // Soft outer glow stroke
            curvePaint.color = (curveColors[c] and 0x00FFFFFF) or 0x12000000
            curvePaint.strokeWidth = strokeWidths[c] * 6.5f
            canvas.drawPath(curvePath, curvePaint)

            // Crisp silk core curve
            curvePaint.color = curveColors[c]
            curvePaint.strokeWidth = strokeWidths[c]
            canvas.drawPath(curvePath, curvePaint)
        }

        // 3. Drifting luminous pink dust particles along the flowing curves
        for (p in particles) {
            val t = p.progress
            val x = -w * 0.06f + t * w * 1.12f
            val baseY = curveY(p.curveIndex, t, h, elapsedSec)
            val drift = sin((t * p.spreadFreq * 3.5f + elapsedSec * 0.9f + p.phase).toDouble()).toFloat()
            val y = baseY + p.spread * (0.55f + 0.45f * drift)

            val twinkle = 0.55f + 0.45f * sin((elapsedSec * 1.8f + p.phase).toDouble()).toFloat()
            val edgeFade = when {
                t < 0.08f -> ((t + 0.08f) / 0.16f).coerceIn(0f, 1f)
                t > 0.92f -> ((1.08f - t) / 0.16f).coerceIn(0f, 1f)
                else -> 1f
            }
            val alpha = (p.baseAlpha * twinkle * edgeFade).toInt().coerceIn(0, 255)
            val r = p.radiusDp * density

            // Soft rose dust halo
            haloPaint.color = (p.color and 0x00FFFFFF) or ((alpha / 4) shl 24)
            canvas.drawCircle(x, y, r * 2.8f, haloPaint)

            // Crisp particle core
            particlePaint.color = (p.color and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawCircle(x, y, r, particlePaint)
        }
    }
}
