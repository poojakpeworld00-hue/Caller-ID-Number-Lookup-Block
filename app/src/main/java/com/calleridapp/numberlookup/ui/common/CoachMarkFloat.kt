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
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * Modal coach-mark. Darkens its host with a scrim, leaves a rounded "spotlight"
 * hole over [target] so it stays visible/highlighted, and shows [bubbleRes] just
 * beneath it. Tapping anywhere dismisses it.
 *
 * It attaches to a host container — the Activity's decor view for a screen that
 * owns the whole window, or the surface the target lives on when that surface is
 * only *part* of the window. In the launcher the app's home UI is a side panel
 * that slides in over the home grid, so a mark parked on the decor view would
 * scrim the launcher's own home screen and spotlight a hole where nothing is;
 * hosted on the panel it moves, hides and dies with it.
 *
 * Strictly one-shot — create via [show] and let the tap (or [dismiss]) tear it
 * down.
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
         * Shows the coach-mark for [target] over the whole window. Only correct
         * for a screen that *is* the window; see the [host] overload otherwise.
         */
        fun show(
            activity: Activity,
            target: View,
            bubbleRes: Int,
            onDismiss: (() -> Unit)? = null
        ): CoachMarkFloat = show(activity.window.decorView as ViewGroup, target, bubbleRes, onDismiss)

        /**
         * Shows the coach-mark for [target] inside [host], which must be an
         * ancestor of [target]. Must be called after [target] is laid out (e.g.
         * inside `target.post { }`).
         */
        fun show(
            host: ViewGroup,
            target: View,
            bubbleRes: Int,
            onDismiss: (() -> Unit)? = null
        ): CoachMarkFloat {
            val overlay = CoachMarkFloat(host.context).apply { this.onDismiss = onDismiss }

            // Both locations are read on screen and subtracted, so the hole lands
            // right whether the host is the decor view or a panel parked at an
            // offset of its own.
            val targetLoc = IntArray(2)
            val hostLoc = IntArray(2)
            target.getLocationOnScreen(targetLoc)
            host.getLocationOnScreen(hostLoc)
            val left = (targetLoc[0] - hostLoc[0]).toFloat()
            val top = (targetLoc[1] - hostLoc[1]).toFloat()

            val pad = overlay.dp(6f)
            overlay.holeRect.set(
                left - pad,
                top - pad,
                left + target.width + pad,
                top + target.height + pad
            )

            // Hint bubble positioned just below the spotlight.
            val bubble = LayoutInflater.from(host.context).inflate(bubbleRes, overlay, false)
            bubble.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (top + target.height + overlay.dp(8f)).toInt()
            }
            bubble.setOnClickListener { overlay.dismiss() }
            overlay.addView(bubble)

            host.addView(overlay, fillParams(host))
            return overlay
        }

        /**
         * Layout params that fill [host]. A ConstraintLayout needs the four parent
         * constraints spelled out — an unconstrained child there is placed at (0,0)
         * by accident rather than by instruction, and warns about it.
         */
        private fun fillParams(host: ViewGroup): ViewGroup.LayoutParams =
            if (host is ConstraintLayout) {
                ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    ConstraintLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
            } else {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
    }
}
