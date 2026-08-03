package com.calleridapp.numberlookup.ui.common

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Full-screen modal coach-mark. Darkens the entire screen with a scrim, leaves
 * a rounded "spotlight" hole over [target] so it stays visible/highlighted, and
 * shows [bubbleRes] just beneath it. Tapping anywhere dismisses it.
 *
 * It attaches itself to the Activity's decor view, so it floats above all
 * fragment content. Strictly one-shot — create via [show] and let the tap (or
 * [dismiss]) tear it down.
 */
class CoachMarkFloat private constructor(context: Context) : FrameLayout(context) {

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SCRIM_COLOR }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val holeRect = RectF()
    private val holeRadius = dp(14f)
    private var onDismiss: (() -> Unit)? = null

    init {
        setWillNotDraw(false)
        // A software layer is required for PorterDuff.CLEAR to punch a fully
        // transparent hole (instead of painting black).
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        setOnClickListener { dismiss() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRoundRect(holeRect, holeRadius, holeRadius, holePaint)
    }

    fun dismiss() {
        (parent as? ViewGroup)?.removeView(this)
        onDismiss?.invoke()
        onDismiss = null
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val SCRIM_COLOR = 0xB3000000.toInt() // ~70% black

        /**
         * Shows the coach-mark for [target]. Must be called after [target] is
         * laid out (e.g. inside `target.post { }`).
         */
        fun show(
            activity: Activity,
            target: View,
            bubbleRes: Int,
            onDismiss: (() -> Unit)? = null
        ): CoachMarkFloat {
            val root = activity.window.decorView as ViewGroup
            val overlay = CoachMarkFloat(activity).apply { this.onDismiss = onDismiss }

            // Target bounds in window coordinates (decor view is the window root).
            val loc = IntArray(2)
            target.getLocationInWindow(loc)
            val pad = overlay.dp(6f)
            overlay.holeRect.set(
                loc[0] - pad,
                loc[1] - pad,
                loc[0] + target.width + pad,
                loc[1] + target.height + pad
            )

            // Hint bubble positioned just below the spotlight.
            val bubble = LayoutInflater.from(activity).inflate(bubbleRes, overlay, false)
            bubble.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (loc[1] + target.height + overlay.dp(8f)).toInt()
            }
            bubble.setOnClickListener { overlay.dismiss() }
            overlay.addView(bubble)

            root.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            return overlay
        }
    }
}
