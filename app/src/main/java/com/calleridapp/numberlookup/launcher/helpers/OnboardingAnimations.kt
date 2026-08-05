package com.calleridapp.numberlookup.launcher.helpers

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * The onboarding screens' motion, ported from the Claude Design page's keyframes:
 * `cid-rise-in` for the staggered entrance, `cid-stamp` for the verified badge,
 * `cid-breathe` for the shield, and `cid-twinkle` for the sparkles.
 *
 * All of it is decorative. Android turns animators into no-ops when the user has
 * animations switched off in developer options or accessibility settings, so the
 * screens still land in their finished state — hence every helper sets the final
 * values up front and only animates away from them.
 */
private const val RISE_DISTANCE_DP = 18f
private const val RISE_DURATION = 460L
private const val STAGGER = 70L

/** `cid-rise-in`: fade up from below, one view after another. */
fun riseIn(views: List<View>, startDelay: Long = 90L) {
    val density = views.firstOrNull()?.resources?.displayMetrics?.density ?: return
    val offset = RISE_DISTANCE_DP * density

    views.forEachIndexed { index, view ->
        view.alpha = 0f
        view.translationY = offset
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(RISE_DURATION)
            .setStartDelay(startDelay + index * STAGGER)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }
}

/** `cid-stamp`: the badge lands late and overshoots, so the check reads as a stamp. */
fun stampIn(view: View, startDelay: Long = 520L) {
    view.alpha = 0f
    view.scaleX = 1.6f
    view.scaleY = 1.6f
    view.animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(420L)
        .setStartDelay(startDelay)
        .setInterpolator(OvershootInterpolator(2.2f))
        .start()
}

/**
 * `cid-breathe`: a slow 4.5% pulse on the shield. Returns the animator so the caller
 * can cancel it in onDestroy — an infinite animator holding a View keeps the whole
 * activity alive otherwise.
 */
fun breathe(view: View): ValueAnimator =
    ValueAnimator.ofFloat(1f, 1.045f).apply {
        duration = 2600L
        startDelay = 900L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            val scale = it.animatedValue as Float
            view.scaleX = scale
            view.scaleY = scale
        }
        start()
    }

/** `cid-twinkle`: the sparkles breathe out of phase with each other. */
fun twinkle(views: List<View>): List<ValueAnimator> =
    views.mapIndexed { index, view ->
        ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 1800L
            startDelay = 700L + index * 260L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Float
                view.alpha = value
                view.scaleX = 0.82f + value * 0.3f
                view.scaleY = 0.82f + value * 0.3f
            }
            start()
        }
    }
