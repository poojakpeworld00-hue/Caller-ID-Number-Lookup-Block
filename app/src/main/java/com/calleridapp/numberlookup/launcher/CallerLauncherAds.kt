package com.calleridapp.numberlookup.launcher

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import com.calleridapp.admesh.domain.LauncherPlacementAds
import com.calleridapp.admesh.domain.ScreenPromoConfig
import com.calleridapp.admesh.domain.AdCounterRegistry
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.admesh.presentation.DirectLinkOpener
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal
import com.calleridapp.numberlookup.LookupShellApp
import io.launcher.home.api.LauncherAds

/**
 * The launcher module's ad surface, backed by this app's own ad layer (admesh).
 *
 * The launcher decides *whether* a gesture interstitial fires, from its `launcher_config`; the
 * inline slots are decided here, from the same `launcher_ads` slot blocks the old in-app launcher
 * used, so the console keeps working unchanged:
 *
 *  - [LauncherAds.SLOT_PANEL_BOTTOM] ← `right_panel.bottom_native`
 *  - [LauncherAds.SLOT_PANEL_MID]    ← `right_panel.suggested_banner`
 *  - [LauncherAds.SLOT_DRAWER_TOP]   ← `app_drawer.bottom_native`
 *
 * On top of that, each slot answers to its `ScreenAds.<name>.show` switch like every app screen.
 */
class CallerLauncherAds : LauncherAds {

    override fun isReady(): Boolean =
        AdsVault.getInstance(LookupShellApp.appContext).getBoolean("IsAdsON")

    override fun bindNative(activity: Activity?, key: String, container: FrameLayout?) {
        if (activity == null || container == null) return
        if (activity.isFinishing || activity.isDestroyed) return

        val slot = when (key) {
            LauncherAds.SLOT_PANEL_BOTTOM -> LauncherAdsConfig.rightPanelSlot(activity)
            LauncherAds.SLOT_PANEL_MID -> LauncherAdsConfig.rightPanelSuggestedSlot(activity)
            LauncherAds.SLOT_DRAWER_TOP -> LauncherAdsConfig.appDrawerSlot(activity)
            else -> return
        }

        if (!ScreenPromoConfig.resolve(activity, screenAdsKey(key)).show) {
            container.removeAllViews()
            container.visibility = View.GONE
            return
        }

        // The launcher never goes through this app's Activities, so the native preload those do
        // has not run — on a device booted straight into the home screen the cache is empty, and
        // an empty frame asked to render with nothing cached stays blank for good. Request one and
        // paint when it lands; the second pass has a cached ad and cannot come back here.
        val emptyFrame = container.childCount == 0
        if (slot.needsNativePreload && emptyFrame && !NativePromo.hasPreloadedNative()) {
            NativePromo().loadNativeADs(activity, object : NativePromo.NativeAdObserver {
                override fun onNativeAdLoaded() {
                    if (activity.isFinishing || activity.isDestroyed) return
                    paint(activity, slot, container)
                }

                override fun onNativeAdFailed() = Unit
            })
            return
        }

        paint(activity, slot, container)
    }

    private fun paint(activity: Activity, slot: LauncherAdsConfig.Slot, container: FrameLayout) {
        // refreshSlot keeps an ad already on screen rather than swapping it for a blank when the
        // shared native cache is momentarily empty.
        LauncherAdsConfig.refreshSlot(activity, slot, container)
        // A slot that was GONE at the last inset dispatch has no nav-bar padding yet.
        ViewCompat.requestApplyInsets(container)
    }

    /**
     * [onDone] is the gesture the user made. It runs exactly once whatever happens — FlowInterstitial
     * already calls its close callback for ads-off, no network and no fill.
     */
    override fun showInterstitial(activity: Activity, tag: String, onDone: () -> Unit) {
        if (!LauncherPlacementAds.placementEnabled(activity, tag)) return onDone()
        // A placement with its own unit, a link-first chain or a full-native fallback goes through
        // the placement engine (QRScanner's `<placement>_*` keys); otherwise the app-wide
        // preloaded interstitial exactly as before.
        if (LauncherPlacementAds.hasOwnInter(activity, tag)) {
            LauncherPlacementAds.showInterstitial(activity, tag, onDone)
            return
        }
        // The launcher has already applied its own per-gesture counter; letting the app-wide
        // InterCounter apply on top would skip ads the launcher believes it is showing.
        AdCounterRegistry.interCounter = AdsVault.getInstance(activity).getInt("InterCounter")
        InterstitialNormal().showInterAds(activity) { onDone() }
    }

    /** Leaving for another app: a full-screen native (the `drawer` placement's unit), else its interstitial. */
    override fun showFullNative(activity: Activity, tag: String, onDone: () -> Unit) =
        LauncherPlacementAds.showFullNative(activity, tag, onDone)

    /** Sponsored drawer tiles open through the same opener as every direct link (`link_open_in`, default WebView). */
    override fun openSponsored(activity: Activity, url: String) {
        val mode = DirectLinkOpener.modeOf(AdsVault.getInstance(activity).getString("link_open_in"))
            ?: DirectLinkOpener.Mode.WEBVIEW
        DirectLinkOpener.open(activity, url, mode)
    }

    /**
     * The `ScreenAds` entry that switches [key], named for where the slot actually is. A key the
     * launcher adds later keeps its own name, so it is switchable the moment it exists.
     */
    private fun screenAdsKey(key: String): String = when (key) {
        LauncherAds.SLOT_PANEL_BOTTOM -> "LauncherAppsPanel"
        LauncherAds.SLOT_PANEL_MID -> "LauncherAppsPanelMid"
        LauncherAds.SLOT_DRAWER_TOP -> "LauncherAppDrawer"
        else -> key
    }
}
