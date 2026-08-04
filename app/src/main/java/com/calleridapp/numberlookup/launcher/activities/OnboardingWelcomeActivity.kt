package com.calleridapp.numberlookup.launcher.activities

import android.content.Intent
import android.os.Bundle
import com.calleridapp.numberlookup.databinding.ActivityOnboardingWelcomeBinding
import com.calleridapp.numberlookup.launcher.helpers.setOnboardingStep
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import org.fossify.commons.helpers.PERMISSION_READ_PHONE_STATE
import org.fossify.commons.helpers.isTiramisuPlus

/**
 * First launcher onboarding screen, shown once.
 *
 * Notifications and phone state are each their own toggle so the user can opt out of either
 * before continuing — both default to on, and only the ones still on are actually requested.
 * Whatever the user picks, Continue always moves on to the "set as default launcher" step.
 */
class OnboardingWelcomeActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityOnboardingWelcomeBinding::inflate)

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
            activeStep = 1
        )

        // Notifications are only a runtime permission from Android 13 on; below that the row
        // would be a dead control.
        if (!isTiramisuPlus()) {
            binding.onboardingNotificationsHolder.beGone()
        }

        binding.onboardingContinue.setOnClickListener { requestOnboardingPermissions() }
    }

    private fun requestOnboardingPermissions() {
        val proceed = {
            startActivity(Intent(this, OnboardingDefaultLauncherActivity::class.java))
            finish()
        }

        val requestPhoneState = {
            if (binding.onboardingPhoneStateSwitch.isChecked) {
                handlePermission(PERMISSION_READ_PHONE_STATE) { proceed() }
            } else {
                proceed()
            }
        }

        if (isTiramisuPlus() && binding.onboardingNotificationsSwitch.isChecked) {
            handlePermission(PERMISSION_POST_NOTIFICATIONS) { requestPhoneState() }
        } else {
            requestPhoneState()
        }
    }
}
