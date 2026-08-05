package com.calleridapp.numberlookup.launcher.activities

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.numberlookup.databinding.ActivityOnboardingWelcomeBinding
import com.calleridapp.numberlookup.launcher.extensions.excludeAppFromRecents
import com.calleridapp.numberlookup.launcher.helpers.breathe
import com.calleridapp.numberlookup.launcher.helpers.riseIn
import com.calleridapp.numberlookup.launcher.helpers.stampIn
import com.calleridapp.numberlookup.permission.AccessEngine
import com.calleridapp.numberlookup.util.followAdContainer
import org.fossify.commons.extensions.viewBinding

/**
 * First launcher onboarding screen, shown once.
 *
 * The screen used to carry a toggle per permission; the design it is now built to has none,
 * so Continue hands off to [AccessEngine], which asks for whatever `permission_engine` has
 * configured for this Activity — notifications and phone state — in priority order, honouring
 * each rule's delay and skipping anything already granted or not applicable on this SDK.
 *
 * The engine matches rules by Activity simple name, so `"OnboardingWelcomeActivity"` has to
 * appear in the `activities` list of each rule in Remote Config. With no rule targeting this
 * screen the engine completes immediately and Continue simply moves on — which is also what
 * happens once every permission is already granted.
 *
 * Declining is not a dead end: the flow always continues to the "set as default launcher"
 * step, and the permissions stay reachable later from Settings. Skip goes to the same place
 * without asking for anything.
 */
class OnboardingWelcomeActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityOnboardingWelcomeBinding::inflate)
    private var shieldPulse: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        excludeAppFromRecents()

        binding.onboardingContinue.setOnClickListener { requestOnboardingPermissions() }
        binding.onboardingSkip.setOnClickListener { goToDefaultLauncherStep() }

        // Back moves the flow on rather than out. Onboarding runs once and there is nothing
        // behind this screen worth returning to, so Back behaves like Skip.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goToDefaultLauncherStep()
        })

        // Mid native pinned above the CTA. showMidNative hides the frame outright when ads
        // are off or the network is down, and followAdContainer drops the hairline with it.
        NativePromo().showMidNative(this, binding.adNativeFrame, binding.adShimmer)
        binding.adNativeDivider.followAdContainer(binding.adNativeFrame)

        playEntrance()
    }

    private fun playEntrance() = with(binding) {
        riseIn(
            listOf(
                onboardingHero,
                onboardingTitle,
                onboardingLead,
                onboardingFeatures,
                onboardingFooter,
            )
        )
        stampIn(onboardingBadge)
        shieldPulse = breathe(onboardingShield)
    }

    override fun onDestroy() {
        // an infinite animator keeps a hard reference to the view it drives
        shieldPulse?.cancel()
        shieldPulse = null
        super.onDestroy()
    }

    private fun requestOnboardingPermissions() {
        // onComplete fires once the whole configured queue is done — or straight away when
        // there is nothing to ask. It deliberately does NOT fire if the run is interrupted
        // (another Activity triggers the engine, or this one is torn down mid-flow), so a
        // half-finished prompt chain can never navigate the user onwards behind its back.
        AccessEngine.check(this) { goToDefaultLauncherStep() }
    }

    private fun goToDefaultLauncherStep() {
        startActivity(Intent(this, OnboardingDefaultLauncherActivity::class.java))
        finish()
    }
}
