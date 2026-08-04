package com.calleridapp.numberlookup.ui.common

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView

/**
 * Entrance/interaction motion shared by Home (and reusable anywhere else that
 * needs the same read). The design declares its entrance as a `cid-rise-in`
 * CSS keyframe -- `opacity 0, translateY(12px)` to `opacity 1, none`, on an
 * ease-out curve -- reproduced here with the same distance and a comparable
 * duration.
 *
 * Everything honours the platform "remove animations" setting: when the user
 * has turned animations off, [prefersReducedMotion] is true and views are
 * simply placed in their final state, matching the design's own
 * `@media (prefers-reduced-motion: reduce)` rule.
 */
object HomeMotion {

    /** Material standard-decelerate, the curve behind the design's ease-out. */
    val DECELERATE = PathInterpolator(0f, 0f, 0f, 1f)

    /** Matches the design's `cubic-bezier(0.2, 0.6, 0.3, 1)`. */
    val EMPHASIZED = PathInterpolator(0.2f, 0.6f, 0.3f, 1f)

    private const val RISE_DISTANCE_DP = 12f
    private const val RISE_DURATION = 420L

    /** Per-item delay for the recent-activity list stagger. */
    const val STAGGER_STEP = 55L

    fun prefersReducedMotion(context: Context): Boolean {
        val scale = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        return scale == 0f
    }

    /**
     * `cid-rise-in`: from { opacity 0; translateY(12px) } to { opacity 1; none }.
     * Hardware-accelerated -- alpha and translationY are both RenderThread
     * properties, so this never touches layout.
     */
    fun riseIn(view: View, delay: Long = 0L, distanceDp: Float = RISE_DISTANCE_DP) {
        if (prefersReducedMotion(view.context)) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = distanceDp * view.resources.displayMetrics.density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(RISE_DURATION)
            .setInterpolator(DECELERATE)
            .start()
    }

    /**
     * Search-bar focus: a small scale lift on the pill surface, the Material
     * affordance for "this input now has focus" (the design's prototype has no
     * interactive states for the input, so this stays subtle and reversible).
     */
    fun attachFocusScale(surface: View, input: View, liftScale: Float = 1.015f) {
        if (prefersReducedMotion(surface.context)) return
        input.setOnFocusChangeListener { _, hasFocus ->
            val target = if (hasFocus) liftScale else 1f
            surface.animate()
                .scaleX(target).scaleY(target)
                .setDuration(150L)
                .setInterpolator(if (hasFocus) EMPHASIZED else DECELERATE)
                .start()
        }
    }

    /** Bottom-nav tab switch: icon + label tween colour instead of an instant swap. */
    fun animateTint(icon: ImageView, label: TextView, from: Int, to: Int, duration: Long = 160L) {
        if (prefersReducedMotion(icon.context)) {
            icon.imageTintList = android.content.res.ColorStateList.valueOf(to)
            label.setTextColor(to)
            return
        }
        ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            this.duration = duration
            interpolator = DECELERATE
            addUpdateListener {
                val c = it.animatedValue as Int
                icon.imageTintList = android.content.res.ColorStateList.valueOf(c)
                label.setTextColor(c)
            }
            start()
        }
    }
}
