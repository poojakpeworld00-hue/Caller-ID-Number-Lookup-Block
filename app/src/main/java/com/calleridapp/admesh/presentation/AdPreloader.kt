package com.calleridapp.admesh.presentation

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import com.calleridapp.admesh.domain.AdsGate
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.oninterAds.InterstitialBack
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal
import com.calleridapp.numberlookup.BuildConfig

/**
 * Keeps every pooled format filled.
 *
 * The pools (interstitial, back interstitial, native, native banner, App Open, rewarded) hold one
 * ad each and used to be filled by the splash alone. A failed preload, a launch with no network,
 * or a session that never went through the splash (the launcher home after a reboot, our icon in
 * our own launcher, the post-call screen) then ran with empty pools for its whole life, and the
 * App Open ad was never reloaded at all.
 *
 * [topUp] asks each pool to fill itself. Each load already skips when its pool holds a fresh ad or
 * a request is in flight, so this costs nothing when everything is full; it is also rate limited.
 * Called when the SDK comes up outside the splash and on every return to the foreground.
 */
object AdPreloader {

    private const val TAG = "AdPreloader"
    private const val MIN_GAP_MS = 20_000L

    private var lastTopUp = 0L

    fun topUp(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val vault = AdsVault.getInstance(activity)
        if (!vault.getBoolean("IsAdsON")) return
        if (!AdsGate.canRequestAds(activity)) return
        val now = SystemClock.uptimeMillis()
        if (lastTopUp != 0L && now - lastTopUp < MIN_GAP_MS) return
        lastTopUp = now
        if (BuildConfig.DEBUG) Log.d(TAG, "topping up the ad pools from ${activity::class.java.simpleName}")

        runCatching { InterstitialNormal().loadInterAds(activity) }
        runCatching { InterstitialBack().loadBackInterAds(activity) }
        // The native pool replaces (and destroys) its ad on every load, so only an empty pool asks.
        runCatching { if (!NativePromo.hasPreloadedNative()) NativePromo().loadNativeADs(activity) }
        runCatching { NativePromoBanner().loadNativeBannerAds(activity) }
        runCatching { AppOpenAdRegistry.loadAd(activity) }
        runCatching { RewardedPromo.preload(activity) }
    }
}
