package com.calleridapp.numberlookup.ui.terms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.calleridapp.numberlookup.ui.ShellActivity
import com.calleridapp.numberlookup.ui.intro.IntroRevealConfig
import com.calleridapp.numberlookup.launcher.helpers.LauncherFlow
import com.calleridapp.numberlookup.ui.intro.IntroRevealPolicy
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.numberlookup.data.VaultRegistry
import com.calleridapp.numberlookup.databinding.ActivityTermsBinding
import com.calleridapp.numberlookup.ui.onboarding.IntroActivity

/**
 * Terms &amp; Conditions gate shown after language selection. The agreement
 * checkbox starts checked; the button is enabled only while it is.
 *
 * On accept it owns the overlay-permission step:
 *  1. Already granted (or not needed) -> go straight to the next screen.
 *  2. Otherwise open the system overlay Settings page and start a grant watcher.
 *  3. Three idempotent paths converge on [proceedToNextScreen]: the watcher
 *     fires (toggle detected ON), the launcher returns (Settings dismissed), or
 *     [onResume] runs after a real return from Settings (gated by settingsShown).
 */
class ConsentActivity : HostActivity<ActivityTermsBinding>() {

    override val layoutId: Int = R.layout.activity_terms

    private val prefs by lazy { VaultRegistry(this) }

    private var awaitingOverlay = false
    private var settingsShown = false
    private var proceeded = false
    private var watchRegistered = false

    /** Fired by [FloatWatchService] once the overlay permission flips ON. */
    private val overlayGrantedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            proceedToNextScreen()
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {

        proceedToNextScreen() }

    override fun initView() {
        // Record this intro show for the once/count frequency gate.
        IntroRevealPolicy.markShown(this, IntroRevealConfig.TERMS)

        ViewCompat.setOnApplyWindowInsetsListener(binding.termsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.cbAgree.isChecked = true
        setAcceptEnabled(true)

        binding.cbAgree.setOnCheckedChangeListener { _, checked -> setAcceptEnabled(checked) }
        // Tapping the label toggles the box too.
        binding.tvAgree.setOnClickListener { binding.cbAgree.toggle() }
        binding.btnAccept.setOnClickListener { onAccept() }

        // Onboarding rule: system back must not exit the app — advance forward to
        // the next screen (marking terms accepted so it isn't shown again). Stays
        // enabled so back never falls through to HostActivity's exit handler;
        // proceedToNextScreen is itself idempotent.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                prefs.isTermsAccepted = true
                proceedToNextScreen()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Proceed only after we've genuinely returned from the system Settings
        // page — settingsShown is set in onPause once Settings actually took the
        // foreground. Without this gate a transient resume (e.g. an activity
        // flashing on top and finishing) navigates away before the user ever
        // sees the overlay-permission screen — the Android 16 "auto back" bug.
        if (awaitingOverlay && settingsShown) {
            awaitingOverlay = false
            settingsShown = false
            proceedToNextScreen()
        }
    }

    override fun onPause() {
        super.onPause()
        // Mark that the foreground actually moved away (to the Settings page),
        // so the next onResume is treated as a real return rather than a spurious
        // resume that would prematurely advance the flow.
        if (awaitingOverlay) settingsShown = true
    }

    override fun onDestroy() {
        stopWatch()
        super.onDestroy()
    }

    private fun setAcceptEnabled(enabled: Boolean) {
        binding.btnAccept.isEnabled = enabled
        binding.btnAccept.alpha = if (enabled) 1f else 0.5f
    }

    private fun onAccept() {
        if (!binding.cbAgree.isChecked) {
            Toast.makeText(this, R.string.terms_please_accept, Toast.LENGTH_SHORT).show()
            return
        }
        prefs.isTermsAccepted = true
        openOverlayPermission()
    }

    /** Requests the overlay permission, or skips ahead if it isn't needed. */
    private fun openOverlayPermission() {
        if (FloatKit.isGranted(this)) {
            proceedToNextScreen()
            return
        }

        // Open ONLY the system overlay-Settings page. We deliberately do NOT
        // drop FloatAccessActivity on top here: launching our own activity
        // back-to-back with the Settings page raced it for the foreground and,
        // combined with the premature onResume proceed, auto-backed out of
        // Settings on Android 16 before the user could grant. Grant detection is
        // handled by FloatWatchService (polls while we're behind Settings) and
        // the genuine return is handled by onResume / the launcher result.
        val launched = runCatching {
            overlayLauncher.launch(FloatKit.buildOverlayIntent(packageName))
        }.isSuccess

        if (!launched) {
            // No overlay-settings screen on this device; don't block the flow.
            proceedToNextScreen()
            return
        }

        awaitingOverlay = true
        settingsShown = false

        // Watch for the grant from a service so it keeps polling while we sit
        // behind the system Settings screen.
        ContextCompat.registerReceiver(
            this,
            overlayGrantedReceiver,
            IntentFilter(FloatWatchService.ACTION_OVERLAY_GRANTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        watchRegistered = true
        FloatWatchService.start(this)
    }

    /**
     * Idempotent. Cancels the watcher and starts the next screen with
     * CLEAR_TASK so this screen and any Settings remnant are wiped from the
     * back stack.
     */
    private fun proceedToNextScreen() {
        if (proceeded) return
        proceeded = true
        stopWatch()

        // Onboarding follows the same IntroRevealPolicy frequency gate as Splash.
        val next = if (IntroRevealPolicy.shouldShowOnboarding(this)) IntroActivity::class.java
        else LauncherFlow.homeActivity()
        if (next == LauncherFlow.homeActivity()) {
            LauncherFlow.markOnboardingCompleted(this)
        }
        startActivity(
            Intent(this, next).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        finish()
    }

    /** Stops the grant watcher service and unregisters its receiver (idempotent). */
    private fun stopWatch() {
        if (watchRegistered) {
            runCatching { unregisterReceiver(overlayGrantedReceiver) }
            watchRegistered = false
        }
        FloatWatchService.stop(this)
    }
}
