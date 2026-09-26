package io.launcher.home.motion

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible
import io.launcher.home.databinding.LnchDefaultLauncherBannerBinding

/**
 * Motion for the launcher's default-home card (`lnch_default_launcher_banner.xml`): the card pops
 * in each time [LauncherPanel][io.launcher.home.activities.LauncherPanel]
 * shows it, and while it is on screen the hero floats over a breathing glow and a turning dashed
 * ring, two sparkles twinkle, the chevron chip nudges towards the edge and a highlight sweeps the
 * card every few seconds.
 *
 * The panel toggles the card's root between GONE and VISIBLE from its touch path, so nothing here
 * hangs off that call: [bind] watches the root through a global-layout listener and starts the
 * loops on the GONE -> VISIBLE edge and cancels them on the way back, which keeps six animators
 * from ticking under a hidden card for the life of the home screen. Everything no-ops when the
 * system animator scale is 0.
 */
object LauncherBannerMotion {

    private const val ENTER_MS = 420L
    private const val ENTER_FROM_SCALE = 0.9f
    private const val ENTER_FROM_Y_DP = -28f
    private const val FLOAT_DP = 4f
    private const val FLOAT_MS = 2800L
    private const val SWAY_DEG = 4f
    private const val SWAY_MS = 4200L
    private const val GLOW_ALPHA_MIN = 0.45f
    private const val GLOW_TO_SCALE = 1.18f
    private const val GLOW_MS = 2600L
    private const val RING_MS = 16_000L
    private const val TWINKLE_MS = 1800L
    private const val NUDGE_DP = 4f
    private const val NUDGE_MS = 1100L
    private const val SHEEN_MS = 1200L
    private const val SHEEN_EVERY_MS = 4200L

    fun bind(binding: LnchDefaultLauncherBannerBinding) {
        val root = binding.root
        // the card's rounded shape is its outline: clip the corner discs and the sheen to it
        binding.bannerCardUi.clipToOutline = true
        if (!shouldAnimate(root.context)) return

        var loops = mutableListOf<Animator>()
        var wasVisible = root.isVisible

        fun start() {
            if (loops.isNotEmpty()) return
            enter(binding.bannerCardUi)
            loops = idle(binding)
        }

        fun stop() {
            loops.forEach { it.cancel() }
            loops.clear()
        }

        val onLayout = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = root.isVisible
            if (visible == wasVisible) return@OnGlobalLayoutListener
            wasVisible = visible
            if (visible) start() else stop()
        }
        if (root.isAttachedToWindow) root.viewTreeObserver.addOnGlobalLayoutListener(onLayout)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnGlobalLayoutListener(onLayout)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(onLayout)
                stop()
            }
        })
        if (wasVisible) start()
    }

    /** Drops the card in from just above its slot with a soft overshoot. */
    private fun enter(card: View) {
        card.animate().cancel()
        card.alpha = 0f
        card.scaleX = ENTER_FROM_SCALE
        card.scaleY = ENTER_FROM_SCALE
        card.translationY = ENTER_FROM_Y_DP * card.resources.displayMetrics.density
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(ENTER_MS)
            .setInterpolator(OvershootInterpolator(1.1f))
            .withLayer()
            .start()
    }

    private fun idle(b: LnchDefaultLauncherBannerBinding): MutableList<Animator> {
        val density = b.root.resources.displayMetrics.density
        val loops = mutableListOf<Animator>()

        loops += loop(ObjectAnimator.ofFloat(b.bannerArtUi, View.TRANSLATION_Y, 0f, -FLOAT_DP * density), FLOAT_MS, 0L)
        loops += loop(ObjectAnimator.ofFloat(b.bannerArtUi, View.ROTATION, -SWAY_DEG, SWAY_DEG), SWAY_MS, 300L)

        loops += loop(
            ObjectAnimator.ofPropertyValuesHolder(
                b.bannerGlowUi,
                PropertyValuesHolder.ofFloat(View.ALPHA, GLOW_ALPHA_MIN, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, GLOW_TO_SCALE),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, GLOW_TO_SCALE),
            ), GLOW_MS, 0L,
        )

        loops += ObjectAnimator.ofFloat(b.bannerRingUi, View.ROTATION, 0f, 360f).apply {
            duration = RING_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        listOf(b.bannerSparkle1Ui, b.bannerSparkle2Ui).forEachIndexed { index, sparkle ->
            loops += loop(
                ObjectAnimator.ofPropertyValuesHolder(
                    sparkle,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.15f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.6f, 1.3f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.6f, 1.3f),
                    PropertyValuesHolder.ofFloat(View.ROTATION, 0f, 45f),
                ), TWINKLE_MS + index * 400L, index * 500L,
            )
        }

        loops += loop(ObjectAnimator.ofFloat(b.bannerChevronUi, View.TRANSLATION_X, 0f, NUDGE_DP * density), NUDGE_MS, 600L)

        loops += sheen(b.bannerSheenUi, b.bannerCardUi)
        return loops
    }

    /**
     * Sweeps the highlight from past the card's left edge to past its right edge, then rests off
     * screen until the next pass; the repeat is a fixed-length animator that spends its tail out
     * of sight, so there is no handler to clear.
     */
    private fun sheen(sheen: View, card: View): Animator {
        val fraction = SHEEN_MS.toFloat() / SHEEN_EVERY_MS
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHEEN_EVERY_MS
            startDelay = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                if (t > fraction) {
                    sheen.visibility = View.INVISIBLE
                    return@addUpdateListener
                }
                val eased = AccelerateDecelerateInterpolator().getInterpolation(t / fraction)
                sheen.visibility = View.VISIBLE
                sheen.translationX = -sheen.width * 1.5f + (card.width + sheen.width * 3f) * eased
            }
            start()
        }
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

    private fun shouldAnimate(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f) > 0f
}
