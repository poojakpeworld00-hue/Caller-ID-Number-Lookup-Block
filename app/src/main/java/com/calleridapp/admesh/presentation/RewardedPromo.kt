package com.calleridapp.admesh.presentation

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.calleridapp.admesh.domain.AdRevenueMeter
import com.calleridapp.admesh.domain.AdsGate
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.oninterAds.FullScreenSpinner
import com.calleridapp.admesh.presentation.oninterAds.InterLoader
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal
import com.calleridapp.numberlookup.BuildConfig

/**
 * Preload-and-show pattern for Google AdMob Rewarded ads.
 *
 * Usage:
 *   RewardedPromo.preload(activity)                      // early (setupViews / onResume)
 *   RewardedPromo().show(activity, onNotEarned = { … }) { unlock() }
 *
 * The reward is the user's for watching: [show]'s `onRewarded` runs only when the ad reports the
 * reward as earned. Closing the ad early runs `onNotEarned` instead. When there is no ad to watch
 * at all (ads off, no consent, no fill), the feature is not held hostage - `onRewarded` runs, after
 * the DirectLink fallback when `IsCustomADS` is on.
 */
class RewardedPromo {

    companion object {
        private const val TAG = "RewardedPromo"

        /** A rewarded ad held longer than this is dropped rather than shown stale. */
        private const val MAX_AGE_MS = 60 * 60_000L

        /** How long an on-demand load may hold the user before the fallback runs instead. */
        private const val ON_DEMAND_TIMEOUT_MS = 8_000L

        private var loadedAd: RewardedAd? = null
        private var loadedAt = 0L
        private var isLoading = false
        private val waiting = mutableListOf<(RewardedAd?) -> Unit>()
        private val main = Handler(Looper.getMainLooper())

        private fun enabled(context: Context): Boolean {
            val pref = AdsVault.getInstance(context)
            return pref.getBoolean("IsAdsON") &&
                pref.getString("RewardedAds")?.trim()?.lowercase() != "false" &&
                AdsGate.canRequestAds(context)
        }

        private fun freshAd(): RewardedAd? {
            if (loadedAd != null && SystemClock.elapsedRealtime() - loadedAt > MAX_AGE_MS) {
                Log.d(TAG, "preloaded rewarded expired — dropped")
                loadedAd = null
            }
            return loadedAd
        }

        /** Call this early (e.g. setupViews / onResume) to warm up the ad. */
        fun preload(context: Context) = load(context, null)

        private fun load(context: Context, onResult: ((RewardedAd?) -> Unit)?) {
            if (!enabled(context)) return onResult?.invoke(null) ?: Unit
            freshAd()?.let { ad -> return onResult?.invoke(ad) ?: Unit }
            onResult?.let { waiting += it }
            if (isLoading) return

            val unitId = AdsVault.getInstance(context).getString("googleRewarded")
            if (unitId.isNullOrEmpty()) {
                flush(null)
                return
            }

            isLoading = true
            Log.d(TAG, "Loading…")
            RewardedAd.load(
                context.applicationContext,
                unitId,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        loadedAd = ad
                        loadedAt = SystemClock.elapsedRealtime()
                        isLoading = false
                        Log.d(TAG, "Loaded OK")
                        flush(ad)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loadedAd = null
                        isLoading = false
                        Log.e(TAG, "Load failed: ${error.message}")
                        flush(null)
                    }
                }
            )
        }

        private fun flush(ad: RewardedAd?) {
            val callbacks = waiting.toList()
            waiting.clear()
            callbacks.forEach { runCatching { it(ad) } }
        }

        /** Show DirectLink fallback, then invoke [onClosed] when it's done. */
        private fun showDirectLinkFallback(activity: Activity, onClosed: () -> Unit) {
            val pref = AdsVault.getInstance(activity)
            if (pref.getBoolean("IsCustomADS")) {
                Log.d(TAG, "Falling back to DirectLink")
                InterstitialNormal.openDirectLink(activity) { onClosed() }
            } else {
                onClosed()
            }
        }
    }

    /**
     * Shows a rewarded ad for the feature behind [onRewarded].
     * - Ads off / no consent / rewarded off → [onRewarded] at once.
     * - No ad ready → loaded on demand (spinner, [ON_DEMAND_TIMEOUT_MS]); still none → DirectLink
     *   fallback when `IsCustomADS`, then [onRewarded].
     * - Ad shown → [onRewarded] only once the reward is earned; closed early → [onNotEarned].
     * Exactly one of the two runs, once.
     */
    fun show(activity: Activity, onNotEarned: () -> Unit = {}, onRewarded: () -> Unit) {
        if (!enabled(activity)) {
            onRewarded()
            return
        }

        val ready = freshAd()
        if (ready != null) {
            present(activity, ready, onNotEarned, onRewarded)
            return
        }

        // Nothing preloaded: wait a moment for one rather than giving the feature away at once.
        var settled = false
        FullScreenSpinner.show(activity, InterLoader.enabled(AdsVault.getInstance(activity)))
        val timeout = Runnable {
            if (settled) return@Runnable
            settled = true
            FullScreenSpinner.hide()
            Log.d(TAG, "on-demand load timed out — fallback")
            showDirectLinkFallback(activity, onRewarded)
        }
        main.postDelayed(timeout, ON_DEMAND_TIMEOUT_MS)
        load(activity) { ad ->
            if (settled) return@load
            settled = true
            main.removeCallbacks(timeout)
            FullScreenSpinner.hide()
            if (ad != null && !activity.isFinishing && !activity.isDestroyed) {
                present(activity, ad, onNotEarned, onRewarded)
            } else {
                showDirectLinkFallback(activity, onRewarded)
            }
        }
    }

    private fun present(activity: Activity, ad: RewardedAd, onNotEarned: () -> Unit, onRewarded: () -> Unit) {
        // Consume the held reference so we don't show it twice
        loadedAd = null
        var earned = false
        var finished = false

        ad.setOnPaidEventListener { AdRevenueMeter.logPaidEvent(activity, it) }
        if (BuildConfig.DEBUG) AdRevenueMeter.simulateDebugRevenue(activity)

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                AdsGate.fullScreenDismissed()
                preload(activity)
                if (finished) return
                finished = true
                // The reward is for watching: an ad closed before it was earned unlocks nothing.
                if (earned) onRewarded() else {
                    Log.d(TAG, "Closed before the reward was earned")
                    onNotEarned()
                }
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                AdsGate.fullScreenDismissed()
                Log.e(TAG, "Show failed: ${error.message} — showing DirectLink fallback")
                preload(activity)
                if (finished) return
                finished = true
                showDirectLinkFallback(activity, onRewarded)
            }
        }

        AdsGate.fullScreenShown()
        ad.show(activity) {
            Log.d(TAG, "Reward earned: ${it.type} x${it.amount}")
            earned = true
        }
    }
}
