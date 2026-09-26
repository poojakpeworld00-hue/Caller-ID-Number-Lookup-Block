package com.calleridapp.numberlookup.onboard

import android.animation.ValueAnimator
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.databinding.ActivityOnboardingWelcomeBinding
import com.calleridapp.numberlookup.databinding.CellGestureTileBinding
import com.calleridapp.numberlookup.permission.AccessEngine
import com.calleridapp.numberlookup.util.followAdContainer
import org.fossify.commons.extensions.beVisibleIf
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
 * Declining is not a dead end: the flow always continues to whatever `onboarding.order` puts
 * next (the intro carousel, unless Remote Config reordered it), and the
 * permissions stay reachable later from Settings. Skip goes to the same place without asking
 * for anything.
 */
class OnboardingWelcomeActivity : OnboardBaseActivity() {

    private val binding by viewBinding(ActivityOnboardingWelcomeBinding::inflate)
    private var shieldPulse: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        excludeAppFromRecents()

        binding.onboardingContinue.setOnClickListener { requestOnboardingPermissions() }
        binding.onboardingSkip.setOnClickListener { goToNextStep() }

        // `onboarding.welcome.skip_enabled: false` makes the screen a required step —
        // Continue is then the only way on (and Back, for paid users).
        val ui = LauncherAdsConfig.onboardingUi(this, LauncherAdsConfig.OnboardScreen.WELCOME)
        binding.onboardingSkip.beVisibleIf(ui.skipEnabled)

        // Paid users: Back behaves like Skip and moves the flow on. Organic users: Back is Back.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (LauncherFlow.backMovesForward(this@OnboardingWelcomeActivity)) goToNextStep()
                else LauncherFlow.passBackThrough(this@OnboardingWelcomeActivity, this)
            }
        })

        // Ad frame pinned above the CTA, `launcher_ads.onboarding.welcome.slot` — a mid native
        // unless Remote Config says otherwise. showSlot hides the frame outright when the slot
        // is off (as the renderers do when ads are off or the network is down), and
        // followAdContainer drops the hairline with it.
        LauncherAdsConfig.showSlot(
            activity = this,
            slot = LauncherAdsConfig.onboardingSlot(this, LauncherAdsConfig.OnboardScreen.WELCOME),
            container = binding.adNativeFrame,
            shimmer = binding.adShimmer,
        )
        binding.adNativeDivider.followAdContainer(binding.adNativeFrame)

        LauncherFlow.bindStepHeader(this, LauncherAdsConfig.OnboardScreen.WELCOME, binding.root)

        bindGestureTiles()
        playEntrance()
    }

    /**
     * The four launcher gestures: one chevron glyph rotated per direction (up -90, right 0,
     * down 90, left 180), each with its own label pair.
     */
    private fun bindGestureTiles() = with(binding) {
        bindTile(tileSwipeUp, -90f, R.string.gesture_swipe_up, R.string.gesture_swipe_up_body)
        bindTile(tileSwipeRight, 0f, R.string.gesture_swipe_right, R.string.gesture_swipe_right_body)
        bindTile(tileSwipeDown, 90f, R.string.gesture_swipe_down, R.string.gesture_swipe_down_body)
        bindTile(tileSwipeLeft, 180f, R.string.gesture_swipe_left, R.string.gesture_swipe_left_body)
    }

    private fun bindTile(tile: CellGestureTileBinding, rotation: Float, title: Int, body: Int) {
        tile.tileIcon.rotation = rotation
        tile.tileTitle.setText(title)
        tile.tileBody.setText(body)
    }

    private fun playEntrance() = with(binding) {
        riseIn(
            listOf(
                onboardingHero,
                onboardingTitle,
                onboardingLead,
                onboardingGestures,
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
        AccessEngine.check(this) { goToNextStep() }
    }

    /**
     * Whatever `launcher_ads.onboarding.order` puts after this screen — the intro carousel
     * unless the order was changed — with this screen's exit interstitial in front of it.
     */
    private fun goToNextStep() {
        LauncherAdsConfig.runOnboardingInter(this, LauncherAdsConfig.OnboardScreen.WELCOME) {
            LauncherFlow.advance(this)
        }
    }
}
