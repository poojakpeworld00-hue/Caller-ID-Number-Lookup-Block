package com.calleridapp.numberlookup.ui.terms

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.VaultRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One-time tutorial coach-mark shown on top of the system "display over other
 * apps" Settings page. A hand taps the toggle to show the user what to flip.
 *
 * Three knobs (see code):
 *  - shown ONCE  -> [VaultRegistry.isOverlayTutorialShown]
 *  - lasts 3 sec -> [AUTO_DISMISS_MS]
 *  - the HAND    -> [startHandHint] animating R.id.ivHand
 *
 * It never navigates; [ConsentActivity] owns the flow. This screen just finishes
 * itself: on grant detected, after the timeout, or on tap.
 */
class FloatAccessActivity : AppCompatActivity() {

    companion object {
        private const val AUTO_DISMISS_MS = 3_000L
        private const val POLL_MS = 500L
        private const val TAP_PERIOD_MS = 600L
    }

    private val prefs by lazy { VaultRegistry(this) }

    private var pollJob: Job? = null
    private var autoDismissJob: Job? = null
    private var handAnim: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_permission)

        // Tutorial is shown only once, ever.
        if (prefs.isOverlayTutorialShown) {
            finish()
            return
        }
        prefs.isOverlayTutorialShown = true

        // Tapping anywhere outside the card dismisses the hint.
        findViewById<View>(R.id.llMain)?.setOnClickListener { finish() }

        startHandHint()

        // Auto-dismiss after the tutorial duration.
        autoDismissJob = lifecycleScope.launch {
            delay(AUTO_DISMISS_MS)
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Close the moment the toggle is flipped on so the caller's UI shows.
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                if (FloatKit.isGranted(this@FloatAccessActivity)) {
                    finish()
                    return@launch
                }
                delay(POLL_MS)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    override fun onDestroy() {
        handAnim?.cancel()
        handAnim = null
        super.onDestroy()
    }

    /** Looping "tap" gesture: the hand nudges up into the toggle and shrinks. */
    private fun startHandHint() {
        val hand = findViewById<ImageView>(R.id.ivHand) ?: return
        val up = dp(2f)
        val down = dp(12f)

        val translate = ObjectAnimator.ofFloat(hand, View.TRANSLATION_Y, down, up)
        val scaleX = ObjectAnimator.ofFloat(hand, View.SCALE_X, 1f, 0.82f)
        val scaleY = ObjectAnimator.ofFloat(hand, View.SCALE_Y, 1f, 0.82f)

        handAnim = AnimatorSet().apply {
            duration = TAP_PERIOD_MS
            interpolator = AccelerateDecelerateInterpolator()
            playTogether(translate, scaleX, scaleY)
            childAnimations.forEach {
                (it as ObjectAnimator).repeatCount = ValueAnimator.INFINITE
                it.repeatMode = ValueAnimator.REVERSE
            }
            start()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
