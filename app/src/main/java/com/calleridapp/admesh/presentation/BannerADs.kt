package com.calleridapp.admesh.presentation
import android.app.Activity
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.facebook.ads.Ad
import com.facebook.ads.AdError
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.calleridapp.admesh.data.AdKind
import com.calleridapp.admesh.domain.AdRevenueMeter
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.logKeyEvent
import com.calleridapp.numberlookup.BuildConfig
import com.facebook.ads.AdView as FbAdView

// --------------------------------------------------------------
// ENUMS
// --------------------------------------------------------------
enum class BannerScale { ADAPTIVE, INLINE, NORMAL }
enum class BannerKind { AUTO, GOOGLE, FACEBOOK, CUSTOM }

// --------------------------------------------------------------
// OBSERVER
// --------------------------------------------------------------
interface BannerAdWatcher {
    fun onAdLoaded() {}
    fun onAdFailed() {}
}

// --------------------------------------------------------------
// BANNER ADS MANAGER
// --------------------------------------------------------------
class BannerPromo {

    private var googleBanner: AdView? = null
    private var facebookBanner: FbAdView? = null

    companion object {
        var bannerCounter = 0

        /**
         * Destroys the AdViews a slot still holds before it is refilled. Every refresh builds a
         * new BannerPromo, and the old AdView was only removed from the layout - never destroyed,
         * so it kept running (and refreshing) off screen until the process died.
         */
        fun destroyBannersIn(container: FrameLayout) {
            for (i in 0 until container.childCount) {
                (container.getChildAt(i) as? AdView)?.let { runCatching { it.destroy() } }
            }
        }
    }

    /** Pauses / resumes / destroys [adView] with [activity], as the SDK expects of a banner. */
    private fun bindLifecycle(activity: Activity, adView: AdView) {
        val owner = activity as? androidx.lifecycle.LifecycleOwner ?: return
        owner.lifecycle.addObserver(object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> runCatching { adView.pause() }
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> runCatching { adView.resume() }
                    androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> {
                        source.lifecycle.removeObserver(this)
                        runCatching { adView.destroy() }
                    }
                    else -> Unit
                }
            }
        })
    }

    // -----------------------------
    // SHOW BANNER ENTRY POINT
    // -----------------------------
    fun showBanner(
        activity: Activity,
        container: FrameLayout,
        type: BannerKind = BannerKind.AUTO,
        size: BannerScale = BannerScale.ADAPTIVE,
        isCollapsable: Boolean = false,
        shimmer: ShimmerFrameLayout? = null,
        observer: BannerAdWatcher? = null,
        customAdUnitId: String? = null,
        disableInternalFallback: Boolean = false
    ) {
        val pref = AdsVault.getInstance(activity)

        // Ads OFF, or no ad consent
        if (!isNetworkConnected(activity)|| !pref.getBoolean("IsAdsON") || !pref.getBoolean("BannerAds") ||
            !com.calleridapp.admesh.domain.AdsGate.canRequestAds(activity)
        ) {
            hide(container)
            observer?.onAdFailed()
            return
        }

        // Banner counter logic
        if (bannerCounter < pref.getInt("BannerCounter")) {
            bannerCounter++
            hide(container)
            observer?.onAdFailed()
            return
        }
        bannerCounter = 0

        when (type) {
            BannerKind.AUTO -> {
                when (AdKind.fromString(pref.getString("IsAdType"))) {
                    AdKind.GOOGLE -> loadGoogleBanner(
                        activity,
                        container,
                        size,
                        isCollapsable,
                        shimmer,
                        observer,
                        customAdUnitId,
                        disableInternalFallback
                    )

                    AdKind.FACEBOOK -> loadFacebookBanner(
                        activity, container, shimmer, observer, disableInternalFallback
                    )

                    AdKind.CUSTOM, AdKind.UNKNOWN -> {
                        if (disableInternalFallback) {
                            observer?.onAdFailed()
                        } else {
                            CustomAdsRegistry().loadCustomAd(
                                activity,
                                container,
                                CustomAdsRegistry.CustomAdType.BANNER
                            )
                        }
                    }
                }
            }

            BannerKind.GOOGLE -> loadGoogleBanner(
                activity,
                container,
                size,
                isCollapsable,
                shimmer,
                observer,
                customAdUnitId,
                disableInternalFallback
            )

            BannerKind.FACEBOOK -> loadFacebookBanner(
                activity, container, shimmer, observer, disableInternalFallback
            )

            BannerKind.CUSTOM -> {
                if (disableInternalFallback) {
                    observer?.onAdFailed()
                } else {
                    CustomAdsRegistry().loadCustomAd(
                        activity,
                        container,
                        CustomAdsRegistry.CustomAdType.BANNER
                    )
                }
            }
        }
    }

    // -----------------------------
    // GOOGLE BANNER
    // -----------------------------
    private fun loadGoogleBanner(
        activity: Activity,
        container: FrameLayout,
        size: BannerScale,
        isCollapsable: Boolean,
        shimmer: ShimmerFrameLayout? = null,
        observer: BannerAdWatcher?,
        customAdUnitId: String? = null,
        disableInternalFallback: Boolean = false
    ) {
        val pref = AdsVault.getInstance(activity)
        val adUnitId = customAdUnitId ?: pref.getString("googleBanner")

        if (adUnitId.isNullOrEmpty()) {
            if (disableInternalFallback) observer?.onAdFailed()
            else fallbackToFBOrCustom(activity, container, shimmer, observer)
            return
        }

        hide(container)
        // Show shimmer while loading
        shimmer?.startShimmer()
        shimmer?.visibility = View.VISIBLE
        destroyBannersIn(container)
        container.removeAllViews()
        shimmer?.let { container.addView(it) }
        container.visibility = View.VISIBLE

        // Preload banner
        if (googleBanner == null) googleBanner = AdView(activity).also { bindLifecycle(activity, it) }
        googleBanner?.adUnitId = adUnitId

        if (isCollapsable) {
            googleBanner?.setAdSize(getAdSize(activity, container))
        } else {
            googleBanner?.setAdSize(getGoogleSize(activity, container, size, isCollapsable))

        }

        googleBanner?.adListener = object : AdListener() {
            override fun onAdLoaded() {
                Log.i(
                    "BannerAds",
                    "Ad loaded. adView.isCollapsible() is ${googleBanner?.isCollapsible}.",
                )
                // Log load
                activity.logKeyEvent("Banner_Load")

                if (BuildConfig.DEBUG) AdRevenueMeter.simulateDebugRevenue(activity)

                googleBanner!!.setOnPaidEventListener {
                    AdRevenueMeter.logPaidEvent(activity, it)
                }


                shimmer?.stopShimmer()
                shimmer?.visibility = View.GONE
                container.removeAllViews()

                container.addView(googleBanner)
                container.visibility = View.VISIBLE
                observer?.onAdLoaded()

            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                shimmer?.stopShimmer()
                shimmer?.visibility = View.GONE
                try {
                    activity.logKeyEvent("Banner_fail_Load")
                } catch (_: Exception) {
                }
                Log.e("BannerPromo", "Google Banner Failed: ${error.message}")
                observer?.onAdFailed()
                if (!disableInternalFallback) {
                    fallbackToFBOrCustom(activity, container, shimmer, observer)
                }
            }

            override fun onAdClicked() {
                activity.logKeyEvent("google_banner")
            }
        }

        val request = if (isCollapsable) {
            val extras = Bundle()
            extras.putString("collapsible", "bottom")
            AdRequest.Builder().addNetworkExtrasBundle(AdMobAdapter::class.java, extras).build()
        } else AdRequest.Builder().build()

        googleBanner?.loadAd(request)
    }

    private fun getAdSize(
        activity: Activity,
        adContainer: FrameLayout
    ): com.google.android.gms.ads.AdSize {
        val display = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(display)

        val density = display.density
        val widthPixels =
            if (adContainer.width == 0) display.widthPixels.toFloat()
            else adContainer.width.toFloat()

        val adWidth = (widthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }

    private fun getGoogleSize(
        activity: Activity,
        container: FrameLayout,
        size: BannerScale,
        isCollapsable: Boolean
    ): AdSize {
        return when (size) {
            BannerScale.NORMAL -> AdSize.BANNER
            BannerScale.INLINE -> AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(
                activity,
                getWidthDp(activity)
            )

            BannerScale.ADAPTIVE -> AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                activity,
                getWidthDp(activity)
            )
        }
    }

    private fun getWidthDp(activity: Activity): Int {
        val display = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(display)
        return (display.widthPixels / display.density).toInt()
    }

    // -----------------------------
    // FACEBOOK BANNER
    // -----------------------------
    private fun loadFacebookBanner(
        activity: Activity,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        observer: BannerAdWatcher?,
        disableInternalFallback: Boolean = false
    ) {
        val pref = AdsVault.getInstance(activity)
        val fbId = pref.getString("faceB_BannerAds")

        if (fbId.isNullOrEmpty()) {
            if (disableInternalFallback) {
                observer?.onAdFailed()
            } else {
                CustomAdsRegistry().loadCustomAd(
                    activity,
                    container,
                    CustomAdsRegistry.CustomAdType.BANNER
                )
            }
            return
        }

        hide(container)
        // Show shimmer while loading
        shimmer?.startShimmer()
        shimmer?.visibility = View.VISIBLE
        container.removeAllViews()
        shimmer?.let { container.addView(it) }

        if (facebookBanner == null) facebookBanner =
            FbAdView(activity, fbId, com.facebook.ads.AdSize.BANNER_HEIGHT_50)

        facebookBanner?.loadAd(
            facebookBanner!!.buildLoadAdConfig()
                .withAdListener(object : com.facebook.ads.AdListener {

                    override fun onAdLoaded(ad: Ad?) {
                        shimmer?.stopShimmer()
                        shimmer?.visibility = View.GONE
                        container.removeAllViews()

                        container.addView(facebookBanner)
                        container.visibility = View.VISIBLE
                        observer?.onAdLoaded()
                        activity.logKeyEvent("facebook_banner_load")
                    }

                    override fun onError(
                        ad: Ad?,
                        error: AdError?
                    ) {
                        shimmer?.stopShimmer()
                        shimmer?.visibility = View.GONE
                        observer?.onAdFailed()
                        Log.e("BannerPromo", "FB Banner Failed: ${error?.errorMessage}")
                        if (!disableInternalFallback) {
                            CustomAdsRegistry().loadCustomAd(
                                activity,
                                container,
                                CustomAdsRegistry.CustomAdType.BANNER
                            )
                        }
                    }

                    override fun onAdClicked(ad: Ad?) {
                    }

                    override fun onLoggingImpression(ad: Ad?) {}
                })
                .build()
        )
    }

    // -----------------------------
    // FALLBACK
    // -----------------------------
    private fun fallbackToFBOrCustom(
        activity: Activity,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        observer: BannerAdWatcher?
    ) {
        val pref = AdsVault.getInstance(activity)
        if (pref.getBoolean("IsFail_FB")) {
            // Pass shimmer to Facebook banner loader
            loadFacebookBanner(activity, container, shimmer, observer)
        } else {
            // Optionally, you can show shimmer for custom ads if CustomAdsRegistry supports it
            shimmer?.startShimmer()
            shimmer?.visibility = View.VISIBLE
            CustomAdsRegistry().loadCustomAd(
                activity,
                container,
                CustomAdsRegistry.CustomAdType.BANNER
            )
        }
    }

    // -----------------------------
    // HIDE
    // -----------------------------
    private fun hide(container: FrameLayout) {
        container.removeAllViews()
        container.visibility = View.GONE
    }

}

