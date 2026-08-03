package com.calleridapp.admesh.presentation

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.calleridapp.admesh.data.AdKind
import com.calleridapp.admesh.domain.AdCounterRegistry.nativeBannerCounter
import com.calleridapp.admesh.domain.AdRevenueMeter
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.TAG_EVENT
import com.calleridapp.admesh.domain.logKeyEvent
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.databinding.FacebookNativeBannerBinding
import com.calleridapp.numberlookup.databinding.GooglesmallnativeBinding
import com.facebook.ads.Ad
import com.facebook.ads.AdError
import com.facebook.ads.AdOptionsView
import com.facebook.ads.NativeAdListener
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions

class NativePromoBanner {
    companion object {
        private var nativeAdBanner: NativeAd? = null
    }

    fun loadNativeBannerAds(activity: Activity) {
        val adsPref = AdsVault.getInstance(activity)
        if (!adsPref.getBoolean("IsAdsON")) return
        // Firebase "NativeBanner" master switch — disable native banner loading
        if (!adsPref.getBoolean("NativeBanner")) return


        when (AdKind.fromString(adsPref.getString("IsAdType"))) {
            AdKind.GOOGLE -> {
                val adUnitId = adsPref.getString("googleNative") ?: return

                val adLoader = AdLoader.Builder(activity, adUnitId).forNativeAd { ad ->
                    nativeAdBanner?.destroy()
                    nativeAdBanner = ad
                    try {
                        activity.logKeyEvent("NativeBanner_Load")
                    } catch (e: Exception) {
                    }

                    Log.d("NativePromoBanner", "Ad loaded successfully")
                }.withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.e("NativePromoBanner", "Ad failed to load: ${error.message}")
                        nativeAdBanner = null
                        // No retry logic

                        try {
                            activity.logKeyEvent("NativeBanner_fail")
                        } catch (e: Exception) {
                        }
                    }
                }).withNativeAdOptions(NativeAdOptions.Builder().build()).build()

                adLoader.loadAd(AdRequest.Builder().build())
            }

            AdKind.FACEBOOK -> {
                // FB ad type → directly try FB
                Log.e(TAG_EVENT, "AdType FaceBook NOt Pre load Google Native")
                return
            }

            AdKind.UNKNOWN, AdKind.CUSTOM -> {
                // Custom ad type
                Log.e(TAG_EVENT, "AdType Custom NOt Pre load Google Native")
                return
            }
        }


    }

    fun showNativeBannerNative(
        context: Activity, layout: FrameLayout, shimmer: ShimmerFrameLayout? = null
    ) {
        Log.e("NativeAds", "Google Show: nativeAd")
        val adsPref = AdsVault.getInstance(context)

        // 🔥 CRASH FIX 1: Activity lifecycle safety
        if (context.isFinishing || context.isDestroyed) return


        if (!isNetworkConnected(context)
            || !adsPref.getBoolean("IsAdsON")
            || !adsPref.getBoolean("NativeBanner")
        ) {
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            return
        }

        if (nativeBannerCounter < adsPref.getInt("MidNativeCounter")) {
            nativeBannerCounter += 1
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            return
        }

        nativeBannerCounter = 0

        layout.visible()
        shimmer?.startShimmer()
        shimmer?.isVisible = true

        when (AdKind.fromString(adsPref.getString("IsAdType"))) {
            AdKind.GOOGLE -> {

                layout.post {
                    try {
                        if (context.isFinishing || context.isDestroyed) return@post
                        if (nativeAdBanner != null) {
                            val binding = GooglesmallnativeBinding.inflate(context.layoutInflater)
                            bindGoogleNativeAd(nativeAdBanner!!, binding, context)

                            layout.removeAllViews()
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false

                            layout.addView(binding.root)
                            nativeAdBanner = null
                            loadNativeBannerAds(context)
                            return@post
                        } else {
                            // Google failed → FB fallback or Custom
                            if (adsPref.getBoolean("IsFail_FB")) {
                                showFBNativeBannerFallback(context, layout)
                            } else {
                                layout.removeAllViews()
                                shimmer?.stopShimmer()
                                shimmer?.isVisible = false
                                CustomAdsRegistry().loadCustomAd(
                                    context, layout, CustomAdsRegistry.CustomAdType.BANNER
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("NativePromoBanner", "Google NativeBanner failed: ${e.message}")
                    }
                }
            }

            AdKind.FACEBOOK -> {
                showFBNativeBannerFallback(context, layout)
            }

            AdKind.UNKNOWN, AdKind.CUSTOM -> {
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                CustomAdsRegistry().loadCustomAd(
                    context, layout, CustomAdsRegistry.CustomAdType.BANNER
                )
            }
        }
    }

    private fun bindGoogleNativeAd(
        nativeAd: NativeAd, binding: GooglesmallnativeBinding, context: Activity
    ) {
        // Log load
        context.logKeyEvent("NativeBanner_Show_Google")

        if (BuildConfig.DEBUG) AdRevenueMeter.simulateDebugRevenue(context)

        nativeAd.setOnPaidEventListener {
            AdRevenueMeter.logPaidEvent(context, it)
        }

        binding.apply {
            mainNativeadView.headlineView = adHeadline
            mainNativeadView.bodyView = adBody
            mainNativeadView.callToActionView = adCallToAction
            mainNativeadView.iconView = adAppIcon

            (adHeadline as TextView).text = nativeAd.headline

            val bgColor = AdsVault.getInstance(context).getString("NativeBgColor")
            val btnColor = AdsVault.getInstance(context).getString("NativebtnColor")


            val txtColor =
                AdsVault.getInstance(context).getString("NativetxtColor") ?: "#000000"

            (binding.mainNativeadView.headlineView as TextView).apply {
                setTextColor(Color.parseColor(txtColor))
            }

            (binding.mainNativeadView.bodyView as TextView).apply {
                setTextColor(Color.parseColor(txtColor))
            }

            val btntxtColor =
                AdsVault.getInstance(context).getString("NativebtntxtColor") ?: "#000000"

            (adCallToAction as TextView).apply {
                setTextColor(Color.parseColor(btntxtColor))
            }

            mainNativeadView.backgroundTintList =
                ColorStateList.valueOf(safeParseColor(bgColor, "#FFFFFF"))

            adCallToAction.backgroundTintList =
                ColorStateList.valueOf(safeParseColor(btnColor, "#000000"))


            if (nativeAd.body != null) {
                adBody.visibility = View.VISIBLE
                (adBody as TextView).text = nativeAd.body
            } else {
                adBody.visibility = View.GONE
            }

            if (nativeAd.icon != null) {
                adAppIcon.visibility = View.VISIBLE
                (adAppIcon as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            } else {
                adAppIcon.visibility = View.GONE
            }

            if (nativeAd.callToAction != null) {
                adCallToAction.visibility = View.VISIBLE
                (adCallToAction as TextView).text = nativeAd.callToAction

            } else {
                adCallToAction.visibility = View.GONE
            }

            mainNativeadView.setNativeAd(nativeAd)
        }
    }

    fun safeParseColor(colorString: String?, defaultColor: String): Int {
        return try {
            if (!colorString.isNullOrBlank()) {
                Color.parseColor(colorString)
            } else {
                Color.parseColor(defaultColor)
            }
        } catch (e: IllegalArgumentException) {
            Color.parseColor(defaultColor)
        }
    }


    private fun showFBNativeBannerFallback(
        context: Activity, layout: FrameLayout, shimmer: ShimmerFrameLayout? = null
    ) {
        val adsPref = AdsVault.getInstance(context)
        val fbId = adsPref.getString("faceB_NativeBannerAds")

        if (fbId.isNullOrEmpty()) {
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            CustomAdsRegistry().loadCustomAd(
                context, layout, CustomAdsRegistry.CustomAdType.BANNER
            )
            return
        }

        val fbNative = com.facebook.ads.NativeAd(context, fbId)
        fbNative.loadAd(
            fbNative.buildLoadAdConfig().withAdListener(object : NativeAdListener {
                override fun onMediaDownloaded(ad: Ad?) {
                    shimmer?.stopShimmer()
                    shimmer?.isVisible = false
                    layout.removeAllViews()
                    inflateFbNativeBAnnerAd(fbNative, layout, context)
                    context.logKeyEvent("NativeBAnner_FB")
                }

                override fun onError(ad: Ad?, adError: AdError?) {
                    shimmer?.stopShimmer()
                    shimmer?.isVisible = false
                    Log.e("NativeAds", "FB MidNative failed: ${adError?.errorMessage}")
                    CustomAdsRegistry().loadCustomAd(
                        context, layout, CustomAdsRegistry.CustomAdType.BANNER
                    )
                }

                override fun onAdLoaded(ad: Ad?) {
                    if (fbNative !== ad) return
                    fbNative.downloadMedia()
                }

                override fun onAdClicked(ad: Ad?) {}
                override fun onLoggingImpression(ad: Ad?) {}
            }).build()
        )
    }

    fun inflateFbNativeBAnnerAd(
        nativeAd: com.facebook.ads.NativeAd,
        viewGroup: ViewGroup,
        activity: Activity,
        adSize: String? = null
    ) {
        // ✅ Make sure container is visible
        viewGroup.isVisible = true

        // Unregister any old ad view
        nativeAd.unregisterView()

        // ✅ Inflate layout with ViewBinding
        val binding =
            FacebookNativeBannerBinding.inflate(LayoutInflater.from(activity), viewGroup, false)

        // Clear old views and add new ad view
        viewGroup.removeAllViews()
        viewGroup.addView(binding.root)

        // ✅ Add AdChoicesView
        val adOptionsView = AdOptionsView(activity, nativeAd, binding.nativview)
        binding.adChoicesContainer.removeAllViews()
        binding.adChoicesContainer.addView(adOptionsView, 0)

        // ✅ Bind ad data to views
        binding.nativeAdTitle.text = nativeAd.advertiserName
        binding.nativeAdSocialContext.text = nativeAd.adSocialContext
        binding.nativeAdSponsoredLabel.text = nativeAd.sponsoredTranslation

        val bgColor = AdsVault.getInstance(activity).getString("NativeBgColor")
        val btnColor = AdsVault.getInstance(activity).getString("NativebtnColor")
        val txtColor = AdsVault.getInstance(activity).getString("NativetxtColor") ?: "#000000"
        val btntxtColor =
            AdsVault.getInstance(activity).getString("NativebtntxtColor") ?: "#000000"

        binding.nativeAdTitle.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSocialContext.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSponsoredLabel.setTextColor(Color.parseColor(txtColor))

        binding.nativview.backgroundTintList =
            ColorStateList.valueOf(safeParseColor(bgColor, "#FFFFFF"))

        binding.nativeAdCallToAction.backgroundTintList =
            ColorStateList.valueOf(safeParseColor(btnColor, "#000000"))
        (binding.nativeAdCallToAction as TextView).apply {
            setTextColor(Color.parseColor(btntxtColor))
        }

        if (nativeAd.hasCallToAction()) {
            binding.nativeAdCallToAction.text = nativeAd.adCallToAction
            binding.nativeAdCallToAction.isVisible = true
        } else {
            binding.nativeAdCallToAction.isVisible = false
        }

        // ✅ Register clickable views
        val clickableViews = listOf(binding.nativeAdTitle, binding.nativeAdCallToAction)

        nativeAd.registerViewForInteraction(
            binding.root, binding.nativeIconView, clickableViews
        )
    }

}
