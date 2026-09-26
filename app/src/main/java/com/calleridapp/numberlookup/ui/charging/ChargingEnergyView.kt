package com.calleridapp.numberlookup.ui.charging

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.calleridapp.numberlookup.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The charging screen's energy ring.
 *
 * A faithful reconstruction of the reference app's `ChargingEnergyView`: its own `onDraw` did not
 * survive decompilation, so this rebuilds the same picture from the screenshot and the parts that
 * did survive — a rounded-cap stroke, sweep gradients for the arc, radial gradients for the orb, a
 * ~2.8s infinite rotation, and a 760ms ease on the level.
 *
 * What it draws, outermost first: a dark radial orb in the middle; a faint full track ring; a
 * gradient progress arc from the top clockwise for [progress]; a bright glow that sweeps the ring on
 * the rotation animator; and a bright dot at the head of the arc. Connected it is cyan→blue and
 * spins in 2.8s; disconnected it is muted grey and slows to 4.4s.
 */
class ChargingEnergyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()

    private var connected = true
    private var progress = if (isInEditMode) 68f else 0f

    private var sweep = 0f
    private var rotationAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null

    private var arcShader: SweepGradient? = null
    private var orbShader: RadialGradient? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private fun primary() = color(R.color.charging_ring_primary)
    private fun secondary() = color(R.color.charging_ring_secondary)
    private fun activeColor() =
        if (connected) primary() else color(R.color.charging_ring_disconnected)

    /** Sets the level (0–100), animating there with a 760ms ease when attached. */
    fun setProgress(value: Int, animate: Boolean) {
        val target = value.coerceIn(0, 100).toFloat()
        progressAnimator?.cancel()
        if (!animate || !isAttachedToWindow) {
            progress = target
            invalidate()
            return
        }
        progressAnimator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 760L
            interpolator = AnimationUtils.loadInterpolator(
                context, android.R.interpolator.fast_out_slow_in
            )
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Charging vs not: swaps the palette and the rotation speed. */
    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        arcShader = null
        if (isAttachedToWindow) {
            rotationAnimator?.cancel()
            rotationAnimator = null
            startRotation()
        }
        invalidate()
    }

    private fun startRotation() {
        if (isInEditMode || rotationAnimator != null) return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = if (connected) 2800L else 4400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweep = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startRotation()
    }

    override fun onDetachedFromWindow() {
        rotationAnimator?.cancel()
        progressAnimator?.cancel()
        rotationAnimator = null
        progressAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        arcShader = null
        orbShader = null
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    override fun onDraw(views: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val stroke = dp(9f)
        val radius = min(cx, cy) - stroke
        if (radius <= 0f) return
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Center orb.
        if (orbShader == null) {
            orbShader = RadialGradient(
                cx, cy, radius,
                intArrayOf(color(R.color.charging_orb_center), color(R.color.charging_orb_edge)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }
        fillPaint.shader = orbShader
        views.drawCircle(cx, cy, radius, fillPaint)
        fillPaint.shader = null

        // Track.
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = stroke
        ringPaint.shader = null
        ringPaint.color = color(R.color.charging_ring_track)
        views.drawCircle(cx, cy, radius, ringPaint)

        val sweepAngle = (progress / 100f) * 360f

        // Rotating glow highlight — a soft bright band that travels the ring.
        if (connected) {
            glowPaint.style = Paint.Style.STROKE
            glowPaint.strokeWidth = stroke * 1.7f
            glowPaint.color = (primary() and 0x00FFFFFF) or 0x55000000
            views.drawArc(arc, sweep - 90f, 46f, false, glowPaint)
        }

        // Progress arc.
        if (sweepAngle > 0f) {
            if (arcShader == null) {
                val a = activeColor()
                val b = if (connected) secondary() else color(R.color.charging_ring_disconnected)
                arcShader = SweepGradient(
                    cx, cy,
                    intArrayOf(a, b, a),
                    floatArrayOf(0f, 0.5f, 1f)
                )
            }
            ringPaint.shader = arcShader
            views.drawArc(arc, -90f, sweepAngle, false, ringPaint)
            ringPaint.shader = null

            // Leading dot at the head of the arc.
            val headAngle = Math.toRadians((-90f + sweepAngle).toDouble())
            val hx = cx + radius * cos(headAngle).toFloat()
            val hy = cy + radius * sin(headAngle).toFloat()
            dotPaint.color = Color.WHITE
            views.drawCircle(hx, hy, stroke * 0.62f, dotPaint)
            dotPaint.color = (activeColor() and 0x00FFFFFF) or 0x66000000
            views.drawCircle(hx, hy, stroke * 1.1f, dotPaint)
        }
    }
}
