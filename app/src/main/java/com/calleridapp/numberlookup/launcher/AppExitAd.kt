package com.calleridapp.numberlookup.launcher

import com.calleridapp.numberlookup.util.Analytics

import android.app.Activity
import android.util.Log
import com.calleridapp.admesh.domain.LauncherPlacementAds
import io.launcher.home.api.LauncherKeys
import io.launcher.home.api.LauncherRegistry
import org.json.JSONObject

/**
 * The ad on the way back to the launcher from an app it opened — the same dynamic flow as the app
 * click (link-first chain → else full-screen native → else interstitial), on its own placement.
 *
 * Gate: `launcher_config.gestures.app_exit` (`inter_enabled`, `inter_counter` = show on every
 * (N+1)th return, `0` every time), exactly like `gestures.app_launch`. Placement keys: `appExit_*`
 * (`appExit_ads_on`, `appExit_DirectLink`, `appExit_link_first_then`, `appExit_googleInter`,
 * `appExit_googleFullNative`, …), each falling back to the global key.
 *
 * With `app_exit` absent or off this answers false and the caller keeps the older
 * `launcher_ads.app_drawer` return sequence, so nothing changes until the console turns it on.
 */
object AppExitAd {

    private const val TAG = "AppExitAd"
    private const val PLACEMENT = "appExit"

    /** Process-lifetime, like the launcher's own gesture counters. */
    private var count = 0

    /** True when `app_exit` owns this return (an ad ran, or its counter skipped this one). */
    fun run(activity: Activity): Boolean {
        Analytics.log("launcher_app_close")
        val gate = runCatching {
            JSONObject(LauncherRegistry.bridge.configString(LauncherKeys.LAUNCHER_CONFIG, ""))
                .optJSONObject("gestures")?.optJSONObject("app_exit")
        }.getOrNull() ?: return false
        if (!gate.optBoolean("inter_enabled", false)) return false

        val target = gate.optInt("inter_counter", 0).coerceAtLeast(0)
        if (count < target) {
            count++
            Log.d(TAG, "counter $count/$target — skipping")
            return true
        }
        count = 0
        Log.d(TAG, "showing the app-exit flow")
        LauncherPlacementAds.showFullNative(activity, PLACEMENT) {}
        return true
    }
}
