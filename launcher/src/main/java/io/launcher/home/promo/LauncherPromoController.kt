package io.launcher.home.promo

import io.launcher.home.api.LauncherRegistry
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Gates a launcher gesture behind an interstitial or a promo link, then runs the gesture's action.
 *
 * Covers the two side panels and launching an app from the drawer, configured per gesture from
 * `launcher.gestures` — see [LauncherAdsConfig].
 *
 * ### The gate, in order
 *
 * 1. `inter_enabled` off → run the action, no promo. An unfetched launcherConfig lands here, which is why
 *    that flag defaults false: the launcher must behave exactly as shipped until someone turns a
 *    placement on deliberately.
 * 2. Counter below `inter_counter` → increment and run the action. "Show on every Nth gesture",
 *    `0` meaning every time.
 * 3. Otherwise `url_enabled` → open the promo link; else show the interstitial.
 *
 * A promo link and an ad are alternatives for the same slot, never both — a single gesture must not
 * cost the user an ad *and* a browser trip.
 *
 * ### The action always runs
 *
 * Every path ends in [action], including a no-fill, a closed gate, a missing browser and an SDK
 * exception, and the callback is latched so it runs exactly once. Tapping an app in the drawer has
 * to launch that app; an ad that fails must never turn the tap into nothing.
 *
 * ### Frequency is counted here, not twice
 *
 * Each placement owns its counter, so the ad goes out with the AAR's own `interAdSkipCounter`
 * already satisfied by this gate. Running both would multiply into a spacing neither value
 * describes — the same rule the onboarding funnel follows.
 *
 * Counters are process-lifetime and per gesture: they describe "how many of *these* gestures since
 * the last ad", so carrying them across a cold start would describe nothing the user experienced,
 * and sharing one across gestures would make each one's spacing depend on the others' traffic.
 */
object LauncherPromoController {

    private const val TAG = "LAUNCHER_PROMO"

    private val counters = mutableMapOf<String, Int>()

    /**
     * Runs [action] for [gesture], possibly behind an ad or a promo link.
     *
     * [activity] may be null (no host to show anything over) — the action still runs.
     */
    fun run(activity: Activity?, gesture: String, action: () -> Unit) {
        var done = false
        val once = {
            if (!done) {
                done = true
                action()
            }
        }

        if (activity == null || !LauncherAdsConfig.interEnabled(gesture)) {
            once()
            return
        }

        // Ads that cannot be served must not consume a gesture's turn in the counter — see the
        // same reasoning as the onboarding interstitials.
        if (!LauncherRegistry.ads.isReady()) {
            Log.d(TAG, "$gesture: ads not initialised — running the action")
            once()
            return
        }

        val target = LauncherAdsConfig.interCounter(gesture).coerceAtLeast(0)
        val count = counters[gesture] ?: 0
        if (count < target) {
            counters[gesture] = count + 1
            Log.d(TAG, "$gesture: counter ${count + 1}/$target — skipping")
            once()
            return
        }
        counters[gesture] = 0

        // A promo link opens a separate task, and launching an app alongside it would race it for
        // the foreground, so the app-launch placement is ad-only.
        val url = if (gesture == LauncherAdsConfig.APP_LAUNCH) "" else LauncherAdsConfig.promoUrl(gesture)
        if (url.isNotEmpty()) {
            Log.d(TAG, "$gesture: opening promo link")
            openLink(activity, url)
            once()
            return
        }

        Log.d(TAG, "$gesture: showing interstitial")
        runCatching {
            // once() is the continuation of whatever the user asked for, so the contract is that
            // the host runs it exactly once whatever happened - no ad, a failure, a skip.
            // Leaving for another app gets a full-screen native (QRScanner's choice); the host
            // falls back to an interstitial when none is ready.
            val show = if (gesture == LauncherAdsConfig.APP_LAUNCH) LauncherRegistry.ads::showFullNative
            else LauncherRegistry.ads::showInterstitial
            show(activity, gesture) {
                Log.d(TAG, "$gesture: interstitial closed")
                once()
            }
        }.onFailure {
            Log.w(TAG, "$gesture: interstitial failed - running the action", it)
            once()
        }
    }

    private fun openLink(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            // No browser, or a malformed url. The gesture's own action still runs.
            if (it is ActivityNotFoundException) {
                Log.w(TAG, "no handler for the promo link")
            } else {
                Log.w(TAG, "could not open the promo link", it)
            }
        }
    }

    /** Forget how far each gesture had got. */
    fun reset() = counters.clear()
}
