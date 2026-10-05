package com.calleridapp.admesh.presentation.oninterAds

import android.app.Activity
import android.content.Context
import android.util.Log
import com.facebook.ads.Ad
import com.facebook.ads.InterstitialAdListener
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.calleridapp.admesh.data.AdKind
import com.calleridapp.admesh.domain.AdCounterRegistry.interBackCounter
import com.calleridapp.admesh.domain.AdRevenueMeter
import com.calleridapp.admesh.domain.AdsGate
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.LauncherPlacementAds
import com.calleridapp.admesh.domain.logKeyEvent
import com.calleridapp.admesh.presentation.isNetworkConnected

class InterstitialBack {

    companion object {
        private var _isInterBAckShow: Boolean = false
        var isInterBAckShow: Boolean
            get() = _isInterBAckShow
            set(value) {
                _isInterBAckShow = value
            }
        private var googleInterBack: InterstitialAd? = null
        private var googleInterBackLoadedAt = 0L
        private var isLoadingBack = false
        private const val MAX_AGE_MS = 60 * 60_000L

        /** The loaded back interstitial, or null when there is none or it is older than an hour. */
        private fun freshBackInter(): InterstitialAd? {
            if (googleInterBack != null && android.os.SystemClock.elapsedRealtime() - googleInterBackLoadedAt > MAX_AGE_MS) {
                googleInterBack = null
            }
            return googleInterBack
        }
    }

    // ----------------------------------------------------------------------
    // LOAD GOOGLE INTERSTITIAL (Back Ads)
    // ----------------------------------------------------------------------
    fun loadBackInterAds(activity: Activity) {
        val pref = AdsVault.getInstance(activity)

        if (!pref.getBoolean("IsAdsON")) {
            activity.safeLog("BackLoad:AdsOFF")
            return
        }

        // Firebase "InterAds" master switch — back ads are interstitials too
        if (!pref.getBoolean("InterAds")) {
            activity.safeLog("BackLoad:InterAdsDisabled")
            return
        }

        if (!pref.getBoolean("IsBack")) {
            activity.safeLog("BackLoad:BackAdsOFF")
            return
        }

        if (!AdsGate.canRequestAds(activity)) return
        // Called on every foreground now: never replace a fresh ad or stack a second request.
        if (freshBackInter() != null || isLoadingBack) return

        val id = pref.getString("googleBackInter").orEmpty()
        if (id.isBlank()) return
        val req = AdRequest.Builder().build()

        if (AdKind.fromString(pref.getString("IsAdType")) == AdKind.GOOGLE) {
            isLoadingBack = true
            val app = activity.applicationContext
            InterstitialAd.load(
                app, id, req,
                object : InterstitialAdLoadCallback() {

                    override fun onAdLoaded(ad: InterstitialAd) {
                        isLoadingBack = false
                        googleInterBack = ad
                        googleInterBackLoadedAt = android.os.SystemClock.elapsedRealtime()
                        Log.d("InterstitialBack", "Back Inter Loaded")
                        app.safeLog("Back_Inter_Loaded")
                    }

                    override fun onAdFailedToLoad(err: LoadAdError) {
                        isLoadingBack = false
                        googleInterBack = null
                        Log.e("InterstitialBack", "Back Inter Load Fail: ${err.message}")
                        // Analytics event names cannot carry free text; the code is enough.
                        app.safeLog("Back_Inter_Load_FAILED_${err.code}")
                    }
                })
        }
    }

    // ----------------------------------------------------------------------
    // PUBLIC: SHOW BACK INTER AD
    // ----------------------------------------------------------------------
    fun showBackAds(activity: Activity?, adsClose: () -> Unit) {
        showBackInternal(activity, adsClose)
    }

    // ----------------------------------------------------------------------
    // INTERNAL SHOW LOGIC (BACK ADS ONLY)
    // ----------------------------------------------------------------------
    private fun showBackInternal(activity: Activity?, adsClose: () -> Unit) {
        val act = activity ?: return adsClose()
        val pref = AdsVault.getInstance(act)
        var closedOnce = false

        fun safeClose(reason: String) {
            if (closedOnce) return
            closedOnce = true
            act.safeLog("Closed_$reason")
            Log.e("InterstitialBack", "Closed: $reason")
            try {
                adsClose()
            } catch (_: Exception) {
            }
        }
        // Basic checks
        if (!isNetworkConnected(act)) return safeClose("no_network")
        if (!pref.getBoolean("IsAdsON")) return safeClose("ads_off")
        // Firebase "InterAds" master switch — back ads are interstitials too
        if (!pref.getBoolean("InterAds")) return safeClose("inter_ads_disabled")
        if (!pref.getBoolean("IsBack")) return safeClose("back_ads_disabled")
        if (!AdsGate.canRequestAds(act)) return safeClose("no_consent")

        // ------------------------
        // COUNTER CHECK
        // ------------------------
        // `<`, not `!=`: a missing key reads -1, and a lowered remote value can sit below the
        // in-memory count - `!=` then never matched again and back ads stopped for good.
        val target = pref.getInt("InterBackCounter")

        if (interBackCounter < target) {
            interBackCounter++
            return safeClose("counter_skip")
        }
        interBackCounter = 0

        // `back_ad_flow` / a `back_` link chain: the dynamic flow instead of the back interstitial.
        // Only a flow configured *for back* switches it over — a global setting never does.
        if (LauncherPlacementAds.hasOwnFlow(act, "back")) {
            if (!LauncherPlacementAds.placementEnabled(act, "back")) return safeClose("back_ads_on_false")
            LauncherPlacementAds.showInterstitial(act, "back") { safeClose("back_flow") }
            return
        }

        // ------------------------
        // SELECT AD TYPE
        // ------------------------
        when (AdKind.fromString(pref.getString("IsAdType"))) {

            AdKind.GOOGLE -> {
                showGoogleBackInter(act, pref, ::safeClose)
            }

            AdKind.FACEBOOK -> {
                showFacebookBackInter(
                    act,
                    onDismiss = { safeClose("fb_back_dismiss") },
                    onFail = {
                        showCustomAfterFBFail(act, pref) {
                            safeClose("fb_back_fail")
                        }
                    }
                )
            }

            AdKind.CUSTOM, AdKind.UNKNOWN -> {
                if (pref.getBoolean("IsCustomADS"))
                    InterstitialNormal.openDirectLink(act) { safeClose("custom_open") }
                else safeClose("custom_disabled")
            }

            else -> safeClose("invalid_type")
        }
    }

    // ----------------------------------------------------------------------
    // GOOGLE BACK INTERSTITIAL
    // ----------------------------------------------------------------------

    private fun showGoogleBackInter(
        activity: Activity,
        pref: AdsVault,
        safeClose: (String) -> Unit
    ) {
        val ad = freshBackInter()
        if (ad == null) {
            // Refilled for the next Back whatever this one ends up showing.
            loadBackInterAds(activity)
            return handleGoogleFail(activity, pref, safeClose)
        }
        activity.safeLog("google_back_inter_show_attempt")

        if (com.calleridapp.numberlookup.BuildConfig.DEBUG) AdRevenueMeter.simulateDebugRevenue(activity)
        ad.setOnPaidEventListener { AdRevenueMeter.logPaidEvent(activity, it) }

        // The failure path runs once: a show() that throws and then also reports onAdFailedToShow
        // opened the custom link / Facebook ad twice.
        var failHandled = false
        fun failOnce() {
            if (failHandled) return
            failHandled = true
            handleGoogleFail(activity, pref, safeClose)
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent()
                isInterBAckShow = true
                AdsGate.fullScreenShown()
            }

            override fun onAdDismissedFullScreenContent() {
                googleInterBack = null
                isInterBAckShow = false
                AdsGate.fullScreenDismissed()
                safeClose("Google_Dismiss")
                loadBackInterAds(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                googleInterBack = null
                isInterBAckShow = false
                AdsGate.fullScreenDismissed()
                failOnce()
                loadBackInterAds(activity)
            }
        }

        try {
            ad.show(activity)
        } catch (e: Exception) {
            googleInterBack = null
            isInterBAckShow = false
            AdsGate.fullScreenDismissed()
            failOnce()
            loadBackInterAds(activity)
        }
    }

    // ----------------------------------------------------------------------
    // GOOGLE FAIL → FB or CUSTOM
    // ----------------------------------------------------------------------
    private fun handleGoogleFail(
        activity: Activity,
        pref: AdsVault,
        safeClose: (String) -> Unit
    ) {
        if (pref.getBoolean("IsFail_FB")) {
            showFacebookBackInter(
                activity,
                onDismiss = { safeClose("fb_dismiss") },
                onFail = { showCustomAfterFBFail(activity, pref, safeClose) }
            )

        } else {
            if (pref.getBoolean("IsCustomADS")) {
                InterstitialNormal.openDirectLink(activity) { safeClose("google_fail_custom") }
            } else safeClose("google_fail_no_fb_no_custom")
        }
    }

    // ----------------------------------------------------------------------
    // FACEBOOK — BACK ADS
    // ----------------------------------------------------------------------
    private fun showFacebookBackInter(
        context: Context,
        onDismiss: () -> Unit,
        onFail: () -> Unit
    ) {
        val pref = AdsVault.getInstance(context)
        val isLoader = InterLoader.enabled(pref)
        val fbId = pref.getString("faceB_InterAds") ?: return onFail()

        val fb = com.facebook.ads.InterstitialAd(context, fbId)

        // ⬅ FULLSCREEN LOADER (only if Activity)
        if (context is Activity) FullScreenSpinner.show(context, isLoader)

        fb.loadAd(
            fb.buildLoadAdConfig()
                .withAdListener(object : InterstitialAdListener {

                    override fun onAdLoaded(ad: Ad?) {
                        if (context is Activity) FullScreenSpinner.hide()
                        try {
                            fb.show()
                        } catch (e: Exception) {
                            onFail()
                        }
                    }

                    override fun onError(ad: Ad?, err: com.facebook.ads.AdError?) {
                        if (context is Activity) FullScreenSpinner.hide()
                        onFail()
                    }

                    override fun onInterstitialDismissed(ad: Ad?) {
                        AdsGate.fullScreenDismissed()
                        if (context is Activity) FullScreenSpinner.hide()
                        onDismiss()
                    }

                    override fun onLoggingImpression(ad: Ad?) {}
                    override fun onInterstitialDisplayed(ad: Ad?) {
                        AdsGate.fullScreenShown()
                        if (context is Activity) FullScreenSpinner.hide()
                    }

                    override fun onAdClicked(ad: Ad?) {}

                }).build()
        )
    }

    private fun showCustomAfterFBFail(
        context: Activity,
        pref: AdsVault,
        safeClose: (String) -> Unit
    ) {
        if (pref.getBoolean("IsCustomADS"))
            InterstitialNormal.openDirectLink(context) { safeClose("fb_fail_custom") }
        else safeClose("fb_fail_no_custom")
    }

//    private var customTabsClient: CustomTabsClient? = null
//    private var customTabsSession: CustomTabsSession? = null
//    private var serviceConnection: CustomTabsServiceConnection? = null
//    // ----------------------------------------------------------------------
//    // OPEN CUSTOM URL
//    // ----------------------------------------------------------------------
//    // ----------------------------------------------------------------------
//    // OPEN CUSTOM DIRECT LINK
//    // ----------------------------------------------------------------------
//    private fun openDirectLink(context: Activity, onClosed: () -> Unit) {
//        val url = AdsVault.getInstance(context).getString("DirectLink")
//
//        if (url.isNullOrEmpty()) {
//            onClosed()
//            return
//        }
//
//        val uri = Uri.parse(url)
//        isOpened = true
//        onTabClosed = onClosed
//
//        getSession(context) { session ->
//
//            val customTab = CustomTabsIntent.Builder(session)
//                .setShowTitle(true)
//                .setToolbarColor(ContextCompat.getColor(context, R.color.black))
//                .build()
//
//            try {
//                customTab.launchUrl(context, uri)
//            } catch (e: Exception) {
//                openInBrowser(context, uri, onClosed)
//            }
//        }
//    }
//
//    // Use CustomTabsSession to track tab close
//    private fun getSession(
//        context: Context,
//        onReady: (CustomTabsSession?) -> Unit
//    ) {
//        if (customTabsSession != null) {
//            onReady(customTabsSession)
//            return
//        }
//
//        serviceConnection = object : CustomTabsServiceConnection() {
//
//            override fun onCustomTabsServiceConnected(
//                name: ComponentName,
//                client: CustomTabsClient
//            ) {
//                customTabsClient = client
//                customTabsSession = client.newSession(null)
//                onReady(customTabsSession)
//            }
//
//            override fun onServiceDisconnected(name: ComponentName) {
//                customTabsClient = null
//                customTabsSession = null
//                onReady(null)
//            }
//        }
//
//        CustomTabsClient.bindCustomTabsService(
//            context,
//            "com.android.chrome",
//            serviceConnection as CustomTabsServiceConnection
//        )
//    }
//
//    private fun openInBrowser(
//        context: Activity,
//        uri: Uri,
//        onClosed: () -> Unit
//    ) {
//        try {
//            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
//                addCategory(Intent.CATEGORY_BROWSABLE)
//            }
//            context.startActivity(intent)
//            context.safeLog("browser_opened")
//        } catch (e: Exception) {
//            context.safeLog("browser_open_failed")
//        } finally {
//            // Ensure callback is always called
//            isOpened = false
//            onTabClosed?.invoke()
//            onTabClosed = null
//            onClosed()
//        }
//    }

    /*private fun getSession(context: Context): CustomTabsSession? {
        var tabSession: CustomTabsSession? = null
        CustomTabsClient.bindCustomTabsService(
            context, "com.android.chrome",
            object : CustomTabsServiceConnection() {
                override fun onServiceDisconnected(name: android.content.ComponentName?) {}
                override fun onCustomTabsServiceConnected(
                    name: ComponentName,
                    client: CustomTabsClient
                ) {
                    tabSession = client?.newSession(object : CustomTabsCallback() {
                        override fun onNavigationEvent(
                            navigationEvent: Int,
                            extras: android.os.Bundle?
                        ) {
                            if (navigationEvent == CustomTabsCallback.NAVIGATION_ABORTED ||
                                navigationEvent == CustomTabsCallback.TAB_HIDDEN
                            ) {
                                if (isOpened) {
                                    isOpened = false
                                    onTabClosed?.invoke()
                                }
                            }
                        }
                    })
                }
            }
        )
        return tabSession
    }
*/
    private fun Context.safeLog(event: String) {
        try {
            this.logKeyEvent(event)
        } catch (_: Exception) {
        }
    }

}
