package com.calleridapp.numberlookup.launcher

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.ScreenPromoConfig
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.databinding.LauncherShellPanelBinding
import com.calleridapp.numberlookup.onboard.LauncherFlow
import com.calleridapp.numberlookup.ui.home.HomeShellFragment
import com.calleridapp.numberlookup.util.followAdContainer
import io.launcher.home.api.LauncherPanelContent

/**
 * What the launcher module's right-hand panel hosts: the caller-ID app's own home
 * ([HomeShellFragment]), the same fragment [com.calleridapp.numberlookup.ui.ShellActivity] shows.
 *
 * The launcher commits this at `onCreate` and parks it off screen, so everything that must not
 * happen unseen — first-run permission priming — waits for [setPanelVisible].
 */
class LauncherShellFragment : Fragment(), LauncherPanelContent {

    private var _binding: LauncherShellPanelBinding? = null

    private var bannerRequested = false
    private var lastBannerAt = 0L

    private val shell: HomeShellFragment?
        get() = if (isAdded) {
            childFragmentManager.findFragmentById(R.id.callerPanelContainer) as? HomeShellFragment
        } else {
            null
        }

    private val host: LauncherShellHost? get() = LauncherShellHost.of(activity)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        LauncherShellPanelBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Keeps the shell's bottom bar clear of the navigation bar. Insets are returned unchanged
        // so the shell inside still receives them for its own per-tab status-bar padding.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }

        // Committed once and kept — the panel slides in and out rather than being recreated, so
        // the user's tab and scroll position survive closing it.
        if (shell == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.callerPanelContainer, HomeShellFragment.newInstance())
                .commitNow()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun setPanelVisible(visible: Boolean) {
        host?.shellVisible = visible
        shell?.setPanelVisible(visible)
        if (!visible) return
        // Requested only once the panel is on screen: a banner rendered while it is parked off
        // screen is an impression nobody saw.
        renderBanner()
        // The FSI dialog and the permission sheet are drawn over the shell, so they only start
        // once the user can see it — and never during onboarding, which asks for its own.
        if (LauncherFlow.wasOnboardingCompleted(requireContext())) {
            host?.homeShellController?.startFirstRunPriming()
        }
    }

    override fun handleBack(): Boolean = shell?.onBackPressed() == true

    /**
     * The bottom banner, from the standalone home's own `ScreenAds` entry so one switch covers
     * both. The enabled flag is re-read on every open (the launcher can stay alive for days), and a
     * shown banner is refreshed at most every [MIN_REFRESH_MS].
     */
    private fun renderBanner() {
        val activity = activity ?: return
        val binding = _binding ?: return
        val container = binding.bannerSlot.bannerAdFrame
        val shimmer = binding.bannerSlot.bannerShimmer

        if (!ScreenPromoConfig.resolve(activity, BANNER_SCREEN_KEY).show ||
            !AdsVault.getInstance(activity).getBoolean("IsAdsON")
        ) {
            container.removeAllViews()
            container.visibility = View.GONE
            shimmer.stopShimmer()
            shimmer.visibility = View.GONE
            binding.callerAdBannerDivider.followAdContainer(container)
            // Not latched: turn it back on in Remote Config and the next open loads it.
            bannerRequested = false
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (bannerRequested && now - lastBannerAt < MIN_REFRESH_MS) return
        bannerRequested = true
        lastBannerAt = now

        ScreenPromoConfig.showAd(BANNER_SCREEN_KEY, activity, container, shimmer)
        binding.callerAdBannerDivider.followAdContainer(container)
    }

    private companion object {

        /** The same ScreenAds entry the standalone home uses, so one switch covers both. */
        const val BANNER_SCREEN_KEY = "ShellActivity"

        const val MIN_REFRESH_MS = 30_000L
    }
}
