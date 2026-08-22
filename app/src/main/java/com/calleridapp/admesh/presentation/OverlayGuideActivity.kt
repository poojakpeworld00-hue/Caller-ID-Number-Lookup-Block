package com.calleridapp.admesh.presentation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.launcher.extensions.isDefaultLauncher
import com.calleridapp.numberlookup.util.GuardRail
import java.lang.ref.WeakReference


import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Transparent hint screen shown alongside the system "Appear on top" Settings
 * page. Sits in the caller's task and presents a bottom card pointing at the
 * toggle. Auto-finishes the moment overlay permission is granted, so the user
 * lands cleanly back on the caller without an extra tap.
 *
 * Tapping anywhere outside the card also dismisses the hint.
 */
class OverlayGuideActivity : AppCompatActivity() {

    /** Which permission this instance is coaching. */
    enum class Mode { OVERLAY, HOME }

    companion object {
        private const val AUTO_DISMISS_MS = 3_000L
        private const val EXTRA_MODE = "extra_guide_mode"

        /**
         * The live instance, so [dismiss] can close a card the caller has outlived.
         *
         * Weak: this is a static field and the guide is an Activity — a strong one would pin
         * a destroyed instance for the life of the process.
         */
        private var showing: WeakReference<OverlayGuideActivity>? = null

        /**
         * Intent for the coach-mark over whichever system page was just launched.
         *
         * [FLAG_ACTIVITY_NEW_TASK] only when the caller is not an Activity: the guide belongs
         * in the caller's task, on top of the Settings page, but a delayed start runs off the
         * application context and the framework refuses that without a task of its own.
         */
        fun intent(context: Context, mode: Mode): Intent =
            Intent(context, OverlayGuideActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)
                .apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

        /**
         * Fire-and-forget, exactly like the overlay flow it started as: the guide goes on top
         * of the Settings page, times itself out, and closes itself the moment the permission
         * lands.
         */
        fun start(context: Context, mode: Mode) {
            runCatching { context.startActivity(intent(context, mode)) }
                .onFailure { GuardRail.error(TAG, "guide could not be started", it) }
        }

        /**
         * Closes the card early — the caller is back in front, so the list it annotates is
         * gone and there is nothing left for it to point at. A no-op when nothing is showing.
         */
        fun dismiss() {
            val live = showing?.get() ?: return
            showing = null
            if (!live.isFinishing && !live.isDestroyed) live.finish()
        }

        private const val TAG = "OverlayGuide"
    }

    private val mode: Mode
        get() = runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }
            .getOrDefault(Mode.OVERLAY)

    /** Whether the thing this guide is asking for has been granted. */
    private fun isGranted(): Boolean = when (mode) {
        Mode.OVERLAY -> Settings.canDrawOverlays(this)
        Mode.HOME -> isDefaultLauncher()
    }

    private var pollJob: Job? = null
    private var autoDismissJob: Job? = null
    private var radioPulse: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_guide)

        showing = WeakReference(this)

        val root = findViewById<View>(R.id.llMain)

        // The card mirrors the row the user is hunting for, so it has to mirror the CONTROL
        // on that row too. "Appear on top" is a switch list; "Default home app" is a radio
        // list — showing the toggle Lottie there pointed at something the page does not have.
        if (mode == Mode.HOME) {
            findViewById<TextView>(R.id.guideTitle)?.setText(R.string.home_guide_title)
            findViewById<TextView>(R.id.guideDesc)?.setText(R.string.home_guide_desc)
            findViewById<TextView>(R.id.guideRowHint)?.setText(R.string.home_guide_row_hint)
            findViewById<View>(R.id.animation_view)?.visibility = View.GONE
            findViewById<CompoundButton>(R.id.guideRadio)?.let {
                it.visibility = View.VISIBLE
                pulseRadio(it)
            }
        }

        // The label the system list actually prints, rather than a second copy of it that can
        // drift: the row is only useful if it matches the real one character for character.
        findViewById<TextView>(R.id.guideRowTitle)?.text =
            runCatching { applicationInfo.loadLabel(packageManager).toString().trim() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: getString(R.string.app_name_overlay)

        // Edge-to-edge is forced on Android 15+/16 (targetSdk 37), so the bottom
        // hint card would otherwise draw behind the navigation bar. Pad the root
        // by the system-bar insets so the card floats above the nav bar (and
        // clears side/gesture insets in landscape).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // Translucent windows can miss the initial inset pass — force one.
        ViewCompat.requestApplyInsets(root)

        root?.setOnClickListener {
            finish()
        }

        // Slide the card up on entry. The window itself is translucent and the dim
        // fades in on its own, so animating the card is what makes it read as a
        // sheet rising over the Settings page rather than a frame-one pop-in.
        findViewById<View>(R.id.overlayCardVw)?.apply {
            alpha = 0f
            post {
                translationY = height.toFloat()
                animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(280L)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
        }

        // Auto-dismiss after 3s. Lives on lifecycleScope so it cancels on
        // destroy, and runs once per activity instance — pausing (e.g. user
        // pulled down the notification shade) does not reset the timer.
        autoDismissJob = lifecycleScope.launch {
            delay(AUTO_DISMISS_MS)
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    /**
     * Ticks the radio on and off, the way the toggle Lottie flips for the overlay ask — a
     * statically-checked radio reads as "already done" and the user scrolls straight past it.
     */
    private fun pulseRadio(radio: CompoundButton) {
        radioPulse = lifecycleScope.launch {
            while (isActive) {
                radio.isChecked = true
                delay(700L)
                radio.isChecked = false
                delay(350L)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the permission landed while we were paused (because Settings was on
        // top), close ourselves so the caller's UI is fully visible.
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                if (isGranted()) {
                    finish()
                    return@launch
                }
                delay(500L)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    override fun onDestroy() {
        radioPulse?.cancel()
        radioPulse = null
        // Only clear the slot if it is still ours — a newer card may already have claimed it.
        if (showing?.get() === this) showing = null
        super.onDestroy()
    }
}
