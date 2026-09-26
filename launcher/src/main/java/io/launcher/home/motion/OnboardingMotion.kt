package io.launcher.home.motion

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * Motion every onboarding screen shares: [enter] cascades the copy and CTA in, [hero] keeps the
 * illustration, its glow, ring and sparkles alive. Press feedback stays with
 * [CommonAnimations.setOnPressAnimatedClickListener]. Everything no-ops when the system animator
 * scale is 0 so a11y / battery-saver users land on the rest state immediately.
 */
object OnboardingMotion {

    const val STAGGER_MS = 60L
    const val ENTER_MS = 350L
    private const val RISE_DP = 24f
    private const val HERO_FROM_SCALE = 0.92f
    private const val PULSE_MS = 1200L
    private const val PULSE_TO_SCALE = 1.04f
    private const val FLOAT_DP = 9f
    private const val FLOAT_MS = 3400L
    private const val SWAY_DEG = 1.6f
    private const val SWAY_MS = 5200L
    private const val GLOW_ALPHA_MIN = 0.55f
    private const val GLOW_TO_SCALE = 1.1f
    private const val GLOW_MS = 3000L
    private const val RING_MS = 28_000L
    private const val TWINKLE_MS = 2200L
    private const val CHIP_FLOAT_DP = 7f
    private const val CHIP_MS = 2600L
    private const val CHIP_SWAY_DEG = 3f
    private const val SLIDE_MS = 420L
    private const val SLIDE_DP = 48f
    private const val MIN_HERO_DP = 150f

    /** Delay of the [index]-th view in the cascade. Pure, so it is unit-tested. */
    fun staggerDelay(index: Int): Long = index * STAGGER_MS

    /** Motion is skipped when the system animator scale is 0. Pure, so it is unit-tested. */
    fun shouldAnimate(animatorDurationScale: Float): Boolean = animatorDurationScale > 0f

    private fun animatorScale(context: Context): Float = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    /**
     * [hero] scales 0.92 -> 1 with a fade; [views] rise 24 dp with a fade, one after another.
     * Pass views in visual order. Ad slots must not be passed - the ads SDK owns them.
     */
    fun enter(hero: View?, vararg views: View) {
        val context = (hero ?: views.firstOrNull())?.context ?: return
        if (!shouldAnimate(animatorScale(context))) return

        val rise = RISE_DP * context.resources.displayMetrics.density
        val interpolator = FastOutSlowInInterpolator()

        hero?.let { view ->
            view.animate().cancel()
            view.alpha = 0f
            view.scaleX = HERO_FROM_SCALE
            view.scaleY = HERO_FROM_SCALE
            view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(0L)
                .setDuration(ENTER_MS)
                .setInterpolator(interpolator)
                .start()
        }
        views.forEachIndexed { index, view ->
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = rise
            view.animate().alpha(1f).translationY(0f)
                .setStartDelay(staggerDelay(index + 1))
                .setDuration(ENTER_MS)
                .setInterpolator(interpolator)
                .start()
        }
    }

    /**
     * Keeps a hero alive: [art] floats and sways, [glow] breathes, [ring] turns slowly, [sparkles]
     * twinkle out of phase. Every loop is cancelled when the first non-null view leaves the window,
     * so screens do not have to track the animators.
     */
    fun hero(art: View?, glow: View?, ring: View?, sparkles: List<View> = emptyList(), chips: List<View> = emptyList()) {
        val anchor = art ?: glow ?: ring ?: sparkles.firstOrNull() ?: chips.firstOrNull() ?: return
        if (!shouldAnimate(animatorScale(anchor.context))) return
        val density = anchor.resources.displayMetrics.density
        val loops = mutableListOf<Animator>()

        art?.let {
            loops += loop(ObjectAnimator.ofFloat(it, View.TRANSLATION_Y, 0f, -FLOAT_DP * density), FLOAT_MS, 0L)
            loops += loop(ObjectAnimator.ofFloat(it, View.ROTATION, -SWAY_DEG, SWAY_DEG), SWAY_MS, 400L)
        }
        glow?.let {
            loops += loop(
                ObjectAnimator.ofPropertyValuesHolder(
                    it,
                    PropertyValuesHolder.ofFloat(View.ALPHA, GLOW_ALPHA_MIN, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, GLOW_TO_SCALE),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, GLOW_TO_SCALE),
                ), GLOW_MS, 0L,
            )
        }
        ring?.let {
            loops += ObjectAnimator.ofFloat(it, View.ROTATION, 0f, 360f).apply {
                duration = RING_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
        sparkles.forEachIndexed { index, sparkle ->
            loops += loop(
                ObjectAnimator.ofPropertyValuesHolder(
                    sparkle,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.2f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.7f, 1.25f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.7f, 1.25f),
                    PropertyValuesHolder.ofFloat(View.ROTATION, 0f, 40f),
                ), TWINKLE_MS + index * 300L, index * 350L,
            )
        }

        chips.forEachIndexed { index, chip ->
            // each chip drifts on its own phase so the cluster never moves as one block
            loops += loop(ObjectAnimator.ofFloat(chip, View.TRANSLATION_Y, 0f, -CHIP_FLOAT_DP * density), CHIP_MS + index * 500L, index * 300L)
            loops += loop(ObjectAnimator.ofFloat(chip, View.ROTATION, -CHIP_SWAY_DEG, CHIP_SWAY_DEG), CHIP_MS + 1400L + index * 300L, index * 700L)
        }

        anchor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                loops.forEach { it.cancel() }
                loops.clear()
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }

    /** A there-and-back loop: [period] covers both directions, so one leg is half of it. */
    private fun loop(animator: ObjectAnimator, period: Long, delay: Long): Animator = animator.apply {
        duration = period / 2
        startDelay = delay
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        start()
    }

    /**
     * Carousel page turn: [outgoing] slides out and fades, [incoming] slides in from the side and
     * its [cascade] views replay the entrance. [forward] picks the direction.
     */
    fun turn(outgoing: View, incoming: View, forward: Boolean, vararg cascade: View) {
        val density = incoming.resources.displayMetrics.density
        val shift = SLIDE_DP * density * if (forward) 1f else -1f
        if (!shouldAnimate(animatorScale(incoming.context))) {
            outgoing.alpha = 0f
            incoming.alpha = 1f
            incoming.translationX = 0f
            return
        }
        val interpolator = FastOutSlowInInterpolator()
        outgoing.animate().cancel()
        outgoing.animate().alpha(0f).translationX(-shift)
            .setDuration(SLIDE_MS).setInterpolator(interpolator)
            .withEndAction { outgoing.translationX = 0f }
            .start()
        incoming.animate().cancel()
        incoming.alpha = 0f
        incoming.translationX = shift
        incoming.animate().alpha(1f).translationX(0f)
            .setDuration(SLIDE_MS).setInterpolator(interpolator)
            .start()
        val rise = RISE_DP * density
        cascade.forEachIndexed { index, view ->
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = rise
            view.animate().alpha(1f).translationY(0f)
                .setStartDelay(SLIDE_MS / 2 + staggerDelay(index))
                .setDuration(ENTER_MS)
                .setInterpolator(interpolator)
                .start()
        }
    }

    /**
     * Keeps [hero] sized to whatever height [stage] has left after the other children of [column],
     * so the art fills a tall screen and gives way (down to [minDp]) when the bottom ad slot takes
     * its share; below that the stage scrolls instead of squashing. Re-runs whenever the stage
     * changes height - an ad filling is the usual cause.
     */
    fun keepHeroFitted(stage: View, column: ViewGroup, hero: View, minDp: Float = MIN_HERO_DP) {
        fun fit() {
            val available = stage.height - stage.paddingTop - stage.paddingBottom
            if (available <= 0) return
            var rest = column.paddingTop + column.paddingBottom
            for (i in 0 until column.childCount) {
                val child = column.getChildAt(i)
                if (child === hero || child.visibility == View.GONE) continue
                val lp = child.layoutParams as ViewGroup.MarginLayoutParams
                rest += child.measuredHeight + lp.topMargin + lp.bottomMargin
            }
            val heroLp = hero.layoutParams as ViewGroup.MarginLayoutParams
            val minPx = (minDp * hero.resources.displayMetrics.density).toInt()
            val target = (available - rest - heroLp.topMargin - heroLp.bottomMargin).coerceAtLeast(minPx)
            if (heroLp.height != target) {
                heroLp.height = target
                hero.layoutParams = heroLp
            }
        }
        stage.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) stage.post { fit() }
        }
        stage.post { fit() }
    }

    /** Soft infinite breathe for hero rings. Caller cancels the returned animator in onDestroy. */
    fun pulse(view: View): Animator? {
        if (!shouldAnimate(animatorScale(view.context))) return null
        return ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, PULSE_TO_SCALE),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, PULSE_TO_SCALE),
        ).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
}
