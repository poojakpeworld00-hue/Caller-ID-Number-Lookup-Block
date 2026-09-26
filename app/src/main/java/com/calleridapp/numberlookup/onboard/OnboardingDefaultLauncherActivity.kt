package com.calleridapp.numberlookup.onboard

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.OnBackPressedCallback
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.admesh.presentation.OverlayGuideActivity
import com.calleridapp.numberlookup.databinding.ActivityOnboardingDefaultLauncherBinding
import io.launcher.home.extensions.isDefaultLauncher
import io.launcher.home.extensions.roleManager
import com.calleridapp.numberlookup.permission.AccessEngine
import com.calleridapp.numberlookup.util.followAdContainer
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.isQPlus

/**
 * The "Set as default launcher?" decision point.
 *
 * A fresh arrival opens the first stage by itself; the CTA re-runs it. Two chances, in order:
 *
 *  1. the system's home-app settings page. Come back having chosen us and we move on once
 *     `auto_next_delay_ms` has passed (or on the CTA, with `auto_next` off);
 *  2. otherwise the Q+ role dialog, which is the one-tap version of the same choice. Grant
 *     it and we move on; cancel it and the user stays here (with a one-time
 *     `permission_engine` ask), free to try again or Skip to whatever
 *     `launcher_ads.onboarding.order` has next. With Skip hidden, a cancel moves on instead.
 *
 * Whether granting really does end onboarding is `default_home_screen.skip_rest_on_grant`;
 * whether this screen appears at all is `default_home_screen.enabled` / `skip_if_default`.
 *
 * Skip moves on without asking for anything. Either way the request stays reachable
 * later from the home-screen long-press menu and the "Setup Required" banner, so cancelling
 * here costs the user nothing permanent.
 *
 * This screen deliberately does NOT use [io.launcher.home.extensions
 * .requestSetAsDefaultLauncher]: that helper fires whichever intent resolves first and never
 * reaches the role dialog on a device that has a home-app settings page, which is every device
 * that matters here. The two stages have to be driven separately, hence the two request codes.
 */
class OnboardingDefaultLauncherActivity : OnboardBaseActivity() {

    private companion object {
        const val REQ_HOME_SETTINGS = 7011
        const val REQ_ROLE_HOME = 7012
    }

    private val binding by viewBinding(ActivityOnboardingDefaultLauncherBinding::inflate)

    /** One-way latch: once a destination is committed, nothing else may pick another. */
    private var leaving = false

    /** True between launching a request and its result, so a fast double-Back or a Back
     *  landing on the CTA cannot stack two of them. */
    private var requestInFlight = false

    /** Open the home-app page on the first onResume — see onCreate. */
    private var autoOpenPending = false

    /** The decline-time permission ask runs once per visit, not on every decline. */
    private var askedOnDecline = false

    /** Whether Skip is on screen — without it, a cancelled role dialog has to move on. */
    private var skipEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        // also keeps the settings page and role dialog we launch out of recents — they run
        // in this task, so they inherit its recents state
        excludeAppFromRecents()

        // Once the role is held (auto_next off), the CTA is how the user moves on.
        binding.onboardingSetDefault.setOnClickListener {
            if (isDefaultLauncher()) goHome() else openHomeSettings()
        }
        binding.onboardingSkip.setOnClickListener { goToNextStep() }

        // `onboarding.set_default.skip_enabled: false` takes the opt-out away: the CTA is the
        // only button left (and, for paid users, Back's role dialog below).
        val ui = LauncherAdsConfig.onboardingUi(this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT)
        skipEnabled = ui.skipEnabled
        binding.onboardingSkip.beVisibleIf(ui.skipEnabled)

        // Paid users: Back gets one last ask — the role dialog, the cheapest version of the
        // request (on 26-28, where there is no dialog, promptForRole moves on instead).
        // Organic users: Back is Back.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (LauncherFlow.backMovesForward(this@OnboardingDefaultLauncherActivity)) {
                    promptForRole()
                } else {
                    LauncherFlow.passBackThrough(this@OnboardingDefaultLauncherActivity, this)
                }
            }
        })

        // Ad frame pinned above the CTA, `launcher_ads.onboarding.set_default.slot` — a mid
        // native unless Remote Config says otherwise. showSlot hides the frame outright when
        // the slot is off, and followAdContainer drops the hairline with it.
        LauncherAdsConfig.showSlot(
            activity = this,
            slot = LauncherAdsConfig.onboardingSlot(this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT),
            container = binding.adNativeFrame,
            shimmer = binding.adShimmer,
        )
        binding.adNativeDivider.followAdContainer(binding.adNativeFrame)

        LauncherFlow.bindStepHeader(
            this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT, binding.root
        )

        // A fresh arrival opens the system default-app page straight away rather than waiting
        // for the CTA — the user's first decision is made in the place it can actually be made.
        // Spent by the first onResume, and skipped on a restore so the page is not reopened
        // underneath the user.
        if (savedInstanceState == null) autoOpenPending = true

        playEntrance()
    }

    // ===== the two-stage request =====



    /** Stage 1 — the settings page listing the installed home apps. */
    private fun openHomeSettings() {
        if (leaving || requestInFlight) return

        launchHomeSettings()

        // The card follows the list rather than racing it — see LauncherHintPrompt. It is a
        // translucent activity in its own task, so it needs no "display over other apps"
        // permission, which this app does not hold this early in the flow anyway.
        LauncherHintPrompt.showAfterSettings(this)
    }

    private fun launchHomeSettings() {
        if (leaving) return

        val opened = launchForResult(Intent(Settings.ACTION_HOME_SETTINGS), REQ_HOME_SETTINGS) ||
                launchForResult(
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                    REQ_HOME_SETTINGS
                )

        // A ROM with neither page would otherwise dead-end the CTA, so skip to stage 2.
        if (!opened) {
            promptForRole()
        }
    }

    /** Stage 2 — the one-tap role dialog, for when stage 1 came back with nothing changed. */
    private fun promptForRole() {
        if (leaving || requestInFlight) return

        // RoleManager landed in Q. On 26-28 there is no dialog to show, so this IS the
        // cancelled branch and onboarding simply carries on.
        if (!isQPlus()) {
            goToNextStep()
            return
        }

        if (!launchForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                Companion.REQ_ROLE_HOME
            )) {
            goToNextStep()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchForResult(intent: Intent, requestCode: Int): Boolean = try {
        startActivityForResult(intent, requestCode)
        requestInFlight = true
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        requestInFlight = false
        if (leaving) return

        // resultCode is not worth reading: both the settings page and the role dialog report
        // RESULT_CANCELED when dismissed with Back, whether or not the role actually changed.
        // Whether we hold the role is the only honest signal.
        if (isDefaultLauncher()) {
            // onResume runs straight after this and drops onto the home screen.
            return
        }

        when (requestCode) {
            REQ_HOME_SETTINGS -> promptForRole()
            // Cancelling the role dialog leaves us on this screen, with Skip as the explicit exit.
            // With Skip hidden (`skip_enabled: false`) there is no such exit — Back only reopens
            // this dialog — so a cancel moves on instead of trapping the user here.
            REQ_ROLE_HOME -> when {
                resultCode == RESULT_OK || !skipEnabled -> goToNextStep()
                else -> askOnDecline()
            }
        }
    }

    /**
     * The user came back from the home-app choice without picking us, so both system surfaces
     * are closed and nothing races a permission dialog. Whether anything is asked is
     * `permission_engine` — a rule listing `OnboardingDefaultLauncherActivity`.
     */
    private fun askOnDecline() {
        if (askedOnDecline) return
        askedOnDecline = true
        AccessEngine.check(this)
    }

    override fun onResume() {
        super.onResume()
        // We are in front again, so the home-app list is gone and the hint has nothing left
        // to annotate. Also drops a card that has not been started yet, for the user who
        // comes straight back out of Settings. Must run before the auto-open below, which
        // re-arms it.
        LauncherHintPrompt.dismiss()
        // Covers every way the role can arrive: the settings page, the role dialog, or the
        // user wandering off and setting it somewhere else entirely.
        if (isDefaultLauncher()) {
            advanceAfterGrant()
            return
        }

        // First arrival only: open the system home-app page (with the hint) without a tap.
        // On the way back, onActivityResult moves on to the role dialog.
        if (autoOpenPending) {
            autoOpenPending = false
            openHomeSettings()
        }
    }

    /**
     * The role is held. `onboarding.set_default.auto_next` (default on) moves on after
     * `auto_next_delay_ms`; off waits for the CTA.
     */
    private fun advanceAfterGrant() {
        val (auto, delayMs) = LauncherAdsConfig.onboardingAutoNext(
            this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT
        )
        if (!auto) return
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed) goHome()
        }, delayMs)
    }

    // ===== destinations =====

    /**
     * The role arrived. `skip_rest_on_grant` (on by default) treats that as the end of
     * onboarding and drops straight onto the home screen; switch it off and the rest of
     * `onboarding.order` still runs, so the user sees the intro and the language picker too.
     */
    private fun goHome() {
        if (leaving) return
        leaving = true
        LauncherAdsConfig.runOnboardingInter(this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT) {
            LauncherFlow.advance(
                activity = this,
                skipRest = LauncherAdsConfig.defaultHomeStep(this).skipRestOnGrant,
            )
        }
    }

    /** Declined or skipped — carry on with whatever `onboarding.order` has next. */
    private fun goToNextStep() {
        if (leaving) return
        leaving = true
        LauncherAdsConfig.runOnboardingInter(this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT) {
            LauncherFlow.advance(this)
        }
    }

    // ===== motion =====

    private fun playEntrance() = with(binding) {
        riseIn(
            listOf(
                imageHero,
                onboardingTitle,
                onboardingLead,
                onboardingSetDefault,
            )
        )
    }

    override fun onDestroy() {
        // A queued hint must not outlive the screen that asked for it.
        LauncherHintPrompt.dismiss()
        super.onDestroy()
    }
}
