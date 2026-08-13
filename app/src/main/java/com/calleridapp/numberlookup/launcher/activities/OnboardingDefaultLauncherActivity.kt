package com.calleridapp.numberlookup.launcher.activities

import android.animation.ValueAnimator
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.OnBackPressedCallback
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.numberlookup.databinding.ActivityOnboardingDefaultLauncherBinding
import com.calleridapp.numberlookup.launcher.extensions.excludeAppFromRecents
import com.calleridapp.numberlookup.launcher.extensions.isDefaultLauncher
import com.calleridapp.numberlookup.launcher.extensions.roleManager
import com.calleridapp.numberlookup.launcher.helpers.LauncherFlow
import com.calleridapp.numberlookup.launcher.helpers.breathe
import com.calleridapp.numberlookup.launcher.helpers.riseIn
import com.calleridapp.numberlookup.launcher.helpers.stampIn
import com.calleridapp.numberlookup.launcher.helpers.twinkle
import com.calleridapp.numberlookup.util.followAdContainer
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.isQPlus

/**
 * The "Set as default launcher?" decision point.
 *
 * The CTA gives the user two chances, in this order:
 *
 *  1. the system's home-app settings page. Come back having chosen us and we drop straight
 *     onto the home screen;
 *  2. otherwise the Q+ role dialog, which is the one-tap version of the same choice. Grant
 *     it and we drop onto the home screen; cancel it and onboarding continues with whatever
 *     `launcher_ads.onboarding.order` has next — the intro carousel and the language picker,
 *     unless Remote Config reordered them.
 *
 * Whether granting really does end onboarding is `default_home_screen.skip_rest_on_grant`;
 * whether this screen appears at all is `default_home_screen.enabled` / `skip_if_default`.
 *
 * Skip moves on without asking for anything. Either way the request stays reachable
 * later from the home-screen long-press menu and the "Setup Required" banner, so cancelling
 * here costs the user nothing permanent.
 *
 * This screen deliberately does NOT use [com.calleridapp.numberlookup.launcher.extensions
 * .requestSetAsDefaultLauncher]: that helper fires whichever intent resolves first and never
 * reaches the role dialog on a device that has a home-app settings page, which is every device
 * that matters here. The two stages have to be driven separately, hence the two request codes.
 */
class OnboardingDefaultLauncherActivity : SimpleActivity() {

    private companion object {
        const val REQ_HOME_SETTINGS = 7011
        const val REQ_ROLE_HOME = 7012
    }

    private val binding by viewBinding(ActivityOnboardingDefaultLauncherBinding::inflate)
    private var shieldPulse: ValueAnimator? = null
    private var sparklePulses: List<ValueAnimator> = emptyList()

    /** One-way latch: once a destination is committed, nothing else may pick another. */
    private var leaving = false

    /** True between launching a request and its result, so a fast double-Back or a Back
     *  landing on the CTA cannot stack two of them. */
    private var requestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        // also keeps the settings page and role dialog we launch out of recents — they run
        // in this task, so they inherit its recents state
        excludeAppFromRecents()

        binding.onboardingSetDefault.setOnClickListener { openHomeSettings() }
        binding.onboardingSkip.setOnClickListener { goToNextStep() }

        // `onboarding.set_default.skip_enabled: false` takes the opt-out away: the CTA is the
        // only button left, and Back still falls through to the role dialog below.
        val ui = LauncherAdsConfig.onboardingUi(this, LauncherAdsConfig.OnboardScreen.SET_DEFAULT)
        binding.onboardingSkip.beVisibleIf(ui.skipEnabled)

        // Back gets one last ask: the role dialog, the cheapest version of the request. It is
        // the same stage 2 the CTA reaches after the settings page, so cancelling it lands in
        // the intro exactly as it does there. On 26-28, where there is no dialog to show,
        // promptForRole falls through to the intro instead.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = promptForRole()
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

        playEntrance()
    }

    // ===== the two-stage request =====

    /** Stage 1 — the settings page listing the installed home apps. */
    private fun openHomeSettings() {
        if (leaving || requestInFlight) return

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

        if (!launchForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE_HOME)) {
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
            REQ_ROLE_HOME -> goToNextStep()
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers every way the role can arrive: the settings page, the role dialog, or the
        // user wandering off and setting it somewhere else entirely.
        if (isDefaultLauncher()) {
            goHome()
        }
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
                onboardingHero,
                onboardingTitle,
                onboardingLead,
                onboardingFooter,
            )
        )
        stampIn(onboardingBadge)
        shieldPulse = breathe(onboardingShield)
        sparklePulses = twinkle(
            listOf(onboardingSparkle1, onboardingSparkle2, onboardingSparkle3)
        )
    }

    override fun onDestroy() {
        // infinite animators keep hard references to the views they drive
        shieldPulse?.cancel()
        shieldPulse = null
        sparklePulses.forEach { it.cancel() }
        sparklePulses = emptyList()
        super.onDestroy()
    }
}
