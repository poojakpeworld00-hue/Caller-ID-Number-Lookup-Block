package com.calleridapp.numberlookup.launcher.activities

import android.os.Bundle
import com.calleridapp.numberlookup.databinding.ActivityOnboardingDefaultLauncherBinding
import com.calleridapp.numberlookup.launcher.extensions.isDefaultLauncher
import com.calleridapp.numberlookup.launcher.extensions.requestSetAsDefaultLauncher
import com.calleridapp.numberlookup.launcher.helpers.LauncherFlow
import com.calleridapp.numberlookup.launcher.helpers.setOnboardingStep
import com.calleridapp.numberlookup.ui.onboarding.IntroActivity
import org.fossify.commons.extensions.viewBinding

/**
 * The "Set as default launcher?" decision point.
 *
 * Granting it here — or coming back from Settings having granted it — is the whole point of
 * onboarding, so that path drops straight onto the home screen. Skipping (or backing out of the
 * system dialog) continues through the intro carousel and the language picker instead. Either
 * way the request stays reachable later from the home-screen long-press menu and the
 * "Setup Required" banner.
 */
class OnboardingDefaultLauncherActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityOnboardingDefaultLauncherBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setOnboardingStep(
            listOf(
                binding.onboardingProgress.progressStep1,
                binding.onboardingProgress.progressStep2,
                binding.onboardingProgress.progressStep3,
                binding.onboardingProgress.progressStep4,
            ),
            activeStep = 2
        )

        binding.onboardingSetDefault.setOnClickListener { requestSetAsDefaultLauncher() }
        binding.onboardingSkip.setOnClickListener { goToIntro() }
    }

    override fun onResume() {
        super.onResume()
        // covers returning from the system role dialog or Settings, whichever handled the request
        if (isDefaultLauncher()) {
            LauncherFlow.goHome(this)
        }
    }

    private fun goToIntro() {
        startActivity(LauncherFlow.onboardingIntent(this, IntroActivity::class.java))
        finish()
    }
}
