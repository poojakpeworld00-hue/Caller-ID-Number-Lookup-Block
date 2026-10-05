package com.calleridapp.numberlookup.launcher

import com.calleridapp.numberlookup.util.Analytics

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.admesh.domain.LauncherPlacementAds
import com.calleridapp.admesh.presentation.AppOpenAdRegistry
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.onboard.LauncherFlow

/**
 * An ad on the system Home and Back buttons, on the launcher home. Each button has its own switch:
 *
 * ```
 * "launcher_ads": {
 *   "system_buttons": {
 *     "home":    { "enabled": false, "ad_type": "", "ads_counter": 0, "min_gap_sec": 30 },
 *     "back":    { "enabled": false, "ad_type": "", "ads_counter": 0, "min_gap_sec": 30 },
 *     "recents": { "enabled": false }
 *   }
 * }
 * ```
 *
 * `ad_type` is one format — `inter` | `app_open` | `reward` | `link` | `full_native` | `custom`.
 * Blank runs the button's placement (`placements.home` / `placements.back`): its `ad_flow`, its
 * `DirectLink` + `link_first_then`, else `googleInter` with `inter_fallback`. `recents` is run by
 * [com.calleridapp.numberlookup.ui.recent.RecentAdWatcher] (the `recent_ad` page); its `enabled`
 * here, when present, overrides `recent_ad.enabled`.
 *
 * **Home** counts only when the user was already in this app: a second Home press on the launcher,
 * or Home from one of our own screens. Coming home from another app is the app-exit moment
 * ([AppExitAd]), not this one, so the two never show back to back.
 *
 * **Back** is a Back on the bare workspace — nothing open to close.
 */
object SystemButtonAds {

    private const val TAG = "SystemButtonAds"

    /** A Home press counts for this long after the `homekey` broadcast that announced it. */
    private const val HOME_PRESS_WINDOW_MS = 3_000L

    private const val LAST_SHOWN_PREFIX = "__system_button_last_"
    private const val COUNTER_PREFIX = "__system_button_counter_"

    /** Uptime of a `homekey` broadcast received while this app was in the foreground, or 0. */
    private var homeFromAppAt = 0L
    private var registered = false

    fun register(app: Application) {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
                    if (intent.getStringExtra("reason") != "homekey") return
                    // Process state at the press: started means the user was in one of our screens.
                    val foreground = ProcessLifecycleOwner.get().lifecycle.currentState
                        .isAtLeast(Lifecycle.State.STARTED)
                    homeFromAppAt = if (foreground) SystemClock.uptimeMillis() else 0L
                }
            },
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** From the launcher's onNewIntent, once it has unwound its drawer and panels. */
    fun onHome(activity: Activity, alreadyOnHome: Boolean) {
        val fromApp = homeFromAppAt != 0L && SystemClock.uptimeMillis() - homeFromAppAt <= HOME_PRESS_WINDOW_MS
        homeFromAppAt = 0L
        if (!alreadyOnHome && !fromApp) return
        run(activity, "home", "home")
    }

    fun onBack(activity: Activity) = run(activity, "back", "back")

    private fun run(activity: Activity, button: String, placement: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        Analytics.log("nav_${button}_press")
        val vault = AdsVault.getInstance(activity)
        if (!vault.getBoolean("IsAdsON")) return
        if (!LauncherFlow.wasOnboardingCompleted(activity)) return
        if (!LauncherPlacementAds.placementEnabled(activity, placement)) return

        val settings = LauncherAdsConfig.systemButtonSettings(activity, button)
        if (!settings.enabled) return
        if (AppOpenAdRegistry.isShowingAd || InterstitialNormal.isInterShow || UnlockAdWatcher.claimsForeground() ||
            com.calleridapp.admesh.domain.AdsGate.isFullScreenShowing
        ) {
            log("$button: another ad is up — skipped")
            return
        }

        val last = vault.getLong(LAST_SHOWN_PREFIX + button, 0L)
        val now = System.currentTimeMillis()
        if (settings.minGapMs > 0L && last > 0L && now - last < settings.minGapMs) {
            log("$button: inside min_gap_sec — skipped")
            return
        }
        if (!LauncherAdsConfig.counterDue(activity, COUNTER_PREFIX + button, settings.counter, "system_buttons.$button")) return

        vault.putLong(LAST_SHOWN_PREFIX + button, now)
        log("$button: showing ${settings.adType.ifEmpty { "placement chain" }}")
        // After the launcher has drawn the workspace it just unwound to.
        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            // `ad` { mode, sequence }: the press's ad as an ordered array, one / all / sequence.
            // The press counter above (`ads_counter`) already paced it.
            settings.flow?.let { block ->
                val flow = LauncherAdsConfig.flowFrom(activity, block, "system_buttons.$button.ad")
                LauncherAdsConfig.runFlow(
                    activity, flow, COUNTER_PREFIX + button + "_flow", "__system_button_${button}_seq_ptr",
                    "system_buttons.$button.ad",
                ) {}
                return@post
            }
            when (settings.adType) {
                "", "inter", "interstitial" -> LauncherPlacementAds.showInterstitial(activity, placement) {}
                "full_native", "fullnative", "full" -> LauncherPlacementAds.showFullNative(activity, placement) {}
                else -> LauncherPlacementAds.showStep(activity, placement, settings.adType) {}
            }
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
