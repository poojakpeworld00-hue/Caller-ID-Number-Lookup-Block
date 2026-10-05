package com.calleridapp.admesh.presentation.oninterAds

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.ContextCompat
import com.facebook.ads.Ad
import com.facebook.ads.InterstitialAdListener
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.calleridapp.admesh.data.AdKind
import com.calleridapp.admesh.domain.AdCounterRegistry.interCounter
import com.calleridapp.admesh.domain.AdRevenueMeter
import com.calleridapp.admesh.domain.AdsGate
import com.calleridapp.admesh.presentation.DrawerAdRunner
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.LauncherPlacementAds
import com.calleridapp.admesh.presentation.DirectLinkOpener
import com.calleridapp.admesh.domain.logKeyEvent
import com.calleridapp.admesh.presentation.isNetworkConnected
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.R
class InterstitialNormal {

    companion object {

        /**
         * The app-wide interstitial's own placement name, so its link-first / `ad_flow` /
         * `inter_fallback` keys live under `app_…` like every other placement's.
         */
        private const val APP_PLACEMENT = "app"

        private var _isInterShow: Boolean = false
        var isInterShow: Boolean
            get() = _isInterShow
            set(value) {
                _isInterShow = value
            }
        private var googleInterAd: InterstitialAd? = null
        private var googleInterLoadedAt = 0L
        private var isGoogleInterLoading = false

        /** An interstitial kept longer than this is no longer served by AdMob; it is dropped. */
        private const val INTER_MAX_AGE_MS = 60 * 60_000L

        /** The loaded Google interstitial, or null when there is none or it has gone stale. */
        private fun freshGoogleInter(): InterstitialAd? {
            if (googleInterAd != null && android.os.SystemClock.elapsedRealtime() - googleInterLoadedAt > INTER_MAX_AGE_MS) {
                Log.d("InterstitialNormal", "preloaded inter expired (older than 1h) — dropped")
                googleInterAd = null
            }
            return googleInterAd
        }

        /** Uptime at which an interstitial request started showing; 0 when none is in progress. */
        private var showInFlightSince = 0L
        private const val SHOW_IN_FLIGHT_MAX_MS = 60_000L

        /** A show that has not closed (or put an ad on screen) after this long is given up on. */
        private const val WATCHDOG_MS = 20_000L
        private const val WATCHDOG_MAX_CHECKS = 6

        private var preloadedFbAd: com.facebook.ads.InterstitialAd? = null
        private var isFbPreloading = false
        // Forwarding callbacks — set just before ad.show(), fired by the preload listener
        private var fbOnDismissed: (() -> Unit)? = null
        private var fbOnFail: (() -> Unit)? = null
        var isOpened = false
        var onTabClosed: (() -> Unit)? = null
        fun handleTabReturn(context: Context) {
            if (!isOpened) return

            isOpened = false
            onTabClosed?.invoke()
            onTabClosed = null

            releaseSession(context) // 🔥 ADD THIS
        }
        private var customTabsClient: CustomTabsClient? = null
        private var customTabsSession: CustomTabsSession? = null
        private var serviceConnection: CustomTabsServiceConnection? = null

        fun releaseSession(context: Context) {
            serviceConnection?.let {
                try {
                    context.unbindService(it)
                } catch (_: Exception) {}
            }
            serviceConnection = null
            customTabsClient = null
            customTabsSession = null
        }

        fun preloadFbAd(context: Context) {
            val pref = AdsVault.getInstance(context)
            if (!pref.getBoolean("IsAdsON")) return
            if (!AdsGate.canRequestAds(context)) return
            if (isFbPreloading || preloadedFbAd != null) return
            val fbId = pref.getString("faceB_InterAds") ?: return
            isFbPreloading = true
            val inter = com.facebook.ads.InterstitialAd(context, fbId)
            inter.loadAd(
                inter.buildLoadAdConfig()
                    .withAdListener(object : com.facebook.ads.InterstitialAdListener {
                        override fun onAdLoaded(ad: com.facebook.ads.Ad?) {
                            preloadedFbAd = inter
                            isFbPreloading = false
                        }
                        override fun onError(ad: com.facebook.ads.Ad?, e: com.facebook.ads.AdError?) {
                            isFbPreloading = false
                            fbOnFail?.invoke()
                            fbOnFail = null
                            fbOnDismissed = null
                        }
                        override fun onInterstitialDismissed(ad: com.facebook.ads.Ad?) {
                            AdsGate.fullScreenDismissed()
                            preloadFbAd(context)   // replenish
                            fbOnDismissed?.invoke()
                            fbOnDismissed = null
                            fbOnFail = null
                        }
                        override fun onInterstitialDisplayed(ad: com.facebook.ads.Ad?) {
                            AdsGate.fullScreenShown()
                        }
                        override fun onAdClicked(ad: com.facebook.ads.Ad?) {}
                        override fun onLoggingImpression(ad: com.facebook.ads.Ad?) {}
                    }).build()
            )
        }

        fun openDirectLink(context: Activity, onClosed: () -> Unit) {
            val url = AdsVault.getInstance(context).getString("DirectLink")

            if (url.isNullOrEmpty()) {
                onClosed()
                return
            }

            // `DirectLinkType` webview / browser: DirectLinkOpener owns those. Only the custom tab
            // path below needs the close tracking, so it alone stays here.
            if (DirectLinkOpener.mode(context) != DirectLinkOpener.Mode.CUSTOM_TAB) {
                // Carry on once the user is back from the page. Continuing at once started the
                // next screen on top of the landing page, so the ad was never seen.
                if (DirectLinkOpener.open(context, url)) DrawerAdRunner.onReturnTo(context, onClosed)
                else onClosed()
                return
            }

            val uri = Uri.parse(url)
            isOpened = true
            onTabClosed = onClosed
            // The tab's return is caught by HostActivity.onResume; screens that are not HostActivities
            // (onboarding, the launcher, the charging screen) never called handleTabReturn, which left
            // the flow stuck. Their own resume does it now.
            DrawerAdRunner.onReturnTo(context) { handleTabReturn(context) }
            com.calleridapp.admesh.domain.AdsGate.skipNextAppOpen()

            getSession(context) { session ->

                val customTab = CustomTabsIntent.Builder(session)
                    .setShowTitle(true)
                    .setToolbarColor(ContextCompat.getColor(context, R.color.black))
                    .build()

                try {
                    customTab.launchUrl(context, uri)
                } catch (e: Exception) {
                    openInBrowser(context, uri, onClosed)
                }
            }
        }

        // Use CustomTabsSession to track tab close
        private fun getSession(
            context: Context,
            onReady: (CustomTabsSession?) -> Unit
        ) {
            if (customTabsSession != null) {
                onReady(customTabsSession)
                return
            }

            serviceConnection = object : CustomTabsServiceConnection() {

                override fun onCustomTabsServiceConnected(
                    name: ComponentName,
                    client: CustomTabsClient
                ) {
                    customTabsClient = client
                    customTabsSession = client.newSession(null)
                    onReady(customTabsSession)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    customTabsClient = null
                    customTabsSession = null
                    onReady(null)
                }
            }

            // Without Chrome (or with it disabled) the bind fails and onReady was never called, so
            // the tab never opened and the caller's flow never continued. A null session still
            // opens a Custom Tab in whatever browser supports one, or falls back to the browser.
            val bound = runCatching {
                CustomTabsClient.bindCustomTabsService(
                    context,
                    "com.android.chrome",
                    serviceConnection as CustomTabsServiceConnection
                )
            }.getOrDefault(false)
            if (!bound) {
                serviceConnection = null
                onReady(null)
            }
        }

        private fun openInBrowser(
            context: Activity,
            uri: Uri,
            onClosed: () -> Unit
        ) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                context.startActivity(intent)
//                safeLog("browser_opened")
            } catch (e: Exception) {
//                context.safeLog("browser_open_failed")
            } finally {
                // Ensure callback is always called
                isOpened = false
                onTabClosed?.invoke()
                onTabClosed = null
                onClosed()
            }
        }


    }


    // ----------------------------------------------------------------------
    // LOAD INTER AD (Google)
    // ----------------------------------------------------------------------
    fun loadInterAds(activity: Activity) {
        val pref = AdsVault.getInstance(activity)
        if (!pref.getBoolean("IsAdsON")) return
        // Firebase "InterAds" master switch — disable interstitial loading entirely
        if (!pref.getBoolean("InterAds")) return
        // Only preload when is_preload_ads = true; on-demand path loads at show time
        if (!pref.getBoolean("is_preload_ads")) return
        if (!AdsGate.canRequestAds(activity)) return

        val adType = AdKind.fromString(pref.getString("IsAdType"))

        // Already holding a fresh one, or one on its way: called on every foreground now, so a
        // second request would only replace a good ad.
        if (adType == AdKind.GOOGLE && freshGoogleInter() == null && !isGoogleInterLoading) {
            val id = pref.getString("googleInter").orEmpty()
            if (id.isNotBlank()) {
                activity.safeLog("google_inter_load_start")
                isGoogleInterLoading = true
                val app = activity.applicationContext
                InterstitialAd.load(
                    app, id, AdRequest.Builder().build(),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            isGoogleInterLoading = false
                            googleInterAd = null
                            app.safeLog("google_inter_load_failed_${error.code}")
                            Log.e("InterstitialNormal", "Load Failed: ${error.message}")
                        }
                        override fun onAdLoaded(ad: InterstitialAd) {
                            isGoogleInterLoading = false
                            googleInterAd = ad
                            googleInterLoadedAt = android.os.SystemClock.elapsedRealtime()
                            app.safeLog("google_inter_loaded")
                            Log.d("InterstitialNormal", "Google inter loaded")
                        }
                    })
            }
        }

        if (adType == AdKind.FACEBOOK || pref.getBoolean("IsFail_FB")) {
            preloadFbAd(activity)
        }
    }

    // ----------------------------------------------------------------------
    // PUBLIC SHOW METHOD
    // ----------------------------------------------------------------------
    fun showInterAds(activity: Activity?, adsClose: () -> Unit) {
        showAdInternal(activity, adsClose)
    }

    // ----------------------------------------------------------------------
    // MAIN INTER AD SHOW
    // ----------------------------------------------------------------------
    private fun showAdInternal(activity: Activity?, adsClose: () -> Unit) {
        val act = activity ?: return adsClose()
        act.safeLog("inter_request_start")

        val pref = AdsVault.getInstance(act)
        if (act.isFinishing || act.isDestroyed) return adsClose()

        // A second tap while the first one's ad is still loading or on screen is the same tap
        // again: ignored, instead of starting a parallel load, a second navigation, and an ad
        // over the next screen. Stale after SHOW_IN_FLIGHT_MAX_MS so a lost callback can't lock it.
        val now = android.os.SystemClock.uptimeMillis()
        if (showInFlightSince != 0L && now - showInFlightSince < SHOW_IN_FLIGHT_MAX_MS) {
            act.safeLog("inter_request_ignored_in_flight")
            return
        }
        showInFlightSince = now
        var hasClosed = false

        fun safeClose(reason: String) {
            if (hasClosed) return
            hasClosed = true
            showInFlightSince = 0L
            act.safeLog("inter_closed_$reason")
            adsClose()   // OPEN NEXT ACTIVITY INSTANTLY
        }

        // A load or a callback that never comes back left the caller waiting for ever (a blank
        // screen on onboarding, which is already latched on "leaving"), and every later interstitial
        // dropped by the in-flight check above. If nothing is on screen after WATCHDOG_MS, move on.
        val watchdog = android.os.Handler(android.os.Looper.getMainLooper())
        var checks = 0
        lateinit var check: Runnable
        check = Runnable {
            if (hasClosed) return@Runnable
            if (isInterShow || AdsGate.isFullScreenShowing) {
                // An ad is up: the user is looking at it. Look again later, but not for ever.
                if (++checks < WATCHDOG_MAX_CHECKS) watchdog.postDelayed(check, WATCHDOG_MS)
                return@Runnable
            }
            act.safeLog("inter_watchdog_close")
            safeClose("watchdog")
        }
        watchdog.postDelayed(check, WATCHDOG_MS)

        // No consent, no ad request of any network.
        if (!AdsGate.canRequestAds(act)) return safeClose("no_consent")

        // Network check
        if (!isNetworkConnected(act)) return safeClose("no_network")
        if (!pref.getBoolean("IsAdsON")) return safeClose("ads_off")
        // Firebase "InterAds" master switch — skip showing interstitials entirely
        if (!pref.getBoolean("InterAds")) return safeClose("inter_ads_disabled")

        // Counter logic
        // Skip `InterCounter` requests, then show. `<`, not `!=`: a missing key reads -1 and a
        // counter lowered remotely can sit below the in-memory count - with `!=` either one never
        // matched again and interstitials stopped for good.
        val target = pref.getInt("InterCounter")
        if (interCounter < target) {
            interCounter++
            act.safeLog("inter_counter_skip")
            return safeClose("counter_skip")
        }
        interCounter = 0
        act.safeLog("inter_counter_triggered")

        // `app_link_first_then` + `app_DirectLink`: the links first, then the listed follow-ups.
        if (LauncherPlacementAds.showLinkFirst(act, APP_PLACEMENT) { safeClose("link_first") }) {
            act.safeLog("inter_link_first")
            return
        }
        // `app_ad_flow`: the whole chain, in the configured order, instead of the interstitial.
        if (LauncherPlacementAds.showAdFlow(act, APP_PLACEMENT) { safeClose("ad_flow") }) {
            act.safeLog("inter_ad_flow")
            return
        }

        val isPreload = pref.getBoolean("is_preload_ads")

        when (AdKind.fromString(pref.getString("IsAdType"))) {

            AdKind.GOOGLE -> {
                act.safeLog("inter_type_google")
                if (isPreload) {
                    showGoogleInterstitial(act, pref, ::safeClose)
                } else {
                    loadAndShowGoogleOnDemand(act, pref, ::safeClose)
                }
            }

            AdKind.FACEBOOK -> {
                act.safeLog("inter_type_facebook")
                if (isPreload && preloadedFbAd != null) {
                    showPreloadedFbAd(
                        act,
                        onDismissed = { safeClose("fb_dismiss") },
                        onFail = {
                            act.safeLog("fb_preload_fail_fallback")
                            loadAndShowFacebookInter(
                                act,
                                onDismissed = { safeClose("fb_dismiss") },
                                onFail = { showCustomAfterFacebookFail(act, pref) { safeClose("fb_fail_custom") } }
                            )
                        }
                    )
                } else {
                    loadAndShowFacebookInter(
                        act,
                        onDismissed = { safeClose("fb_dismiss") },
                        onFail = {
                            act.safeLog("fb_load_fail")
                            showCustomAfterFacebookFail(act, pref) { safeClose("fb_fail_custom") }
                        }
                    )
                }
            }

            AdKind.CUSTOM, AdKind.UNKNOWN -> {
                act.safeLog("inter_type_custom")
                if (pref.getBoolean("IsCustomADS"))
                    openDirectLink(act) { safeClose("custom_opened") }
                else safeClose("custom_disabled")
            }
        }
    }

    // ----------------------------------------------------------------------
    // GOOGLE INTERSTITIAL
    // ----------------------------------------------------------------------
    private fun showGoogleInterstitial(
        activity: Activity,
        pref: AdsVault,
        safeClose: (String) -> Unit
    ) {

        val inter = freshGoogleInter()
        if (inter == null) {
            activity.safeLog("google_inter_null")
            // The slot is refilled for the next request whatever this one ends up showing.
            loadInterAds(activity)
            return handleGoogleFail(activity, pref, safeClose)
        }

        // Log load
        activity.logKeyEvent("google_inter_show_attempt")

        if (BuildConfig.DEBUG) AdRevenueMeter.simulateDebugRevenue(activity)

        inter.setOnPaidEventListener {
            AdRevenueMeter.logPaidEvent(activity, it)
        }

        // The failure path runs once. A show() that throws and then also reports
        // onAdFailedToShow used to start the fallback chain twice, and its rewarded step then came up
        // over the other chain's ad.
        var failHandled = false
        fun failOnce() {
            if (failHandled) return
            failHandled = true
            handleGoogleFail(activity, pref, safeClose)
        }

        inter.fullScreenContentCallback = object : FullScreenContentCallback() {

            override fun onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent()
                isInterShow = true
                // The shared gate: every other chain checks it before putting an ad on screen.
                AdsGate.fullScreenShown()
            }

            override fun onAdDismissedFullScreenContent() {
                isInterShow = false
                AdsGate.fullScreenDismissed()
                googleInterAd = null
                activity.safeLog("google_inter_dismiss")
                safeClose("google_dismiss")
                if (pref.getBoolean("is_preload_ads")) loadInterAds(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                isInterShow = false
                AdsGate.fullScreenDismissed()
                googleInterAd = null
                activity.safeLog("google_inter_failed_show_${error.code}")
                failOnce()
                if (pref.getBoolean("is_preload_ads")) loadInterAds(activity)
            }
        }

        try {
            inter.show(activity)
        } catch (e: Exception) {
            isInterShow = false
            AdsGate.fullScreenDismissed()
            googleInterAd = null
            activity.safeLog("google_inter_exception")
            failOnce()
            loadInterAds(activity)
        }
    }

    // ----------------------------------------------------------------------
    // GOOGLE ON-DEMAND (is_preload_ads = false)
    // ----------------------------------------------------------------------
    private fun loadAndShowGoogleOnDemand(
        activity: Activity,
        pref: AdsVault,
        safeClose: (String) -> Unit
    ) {
        val id = pref.getString("googleInter") ?: return handleGoogleFail(activity, pref, safeClose)
        val isLoader = InterLoader.enabled(pref)

        activity.safeLog("google_inter_ondemand_load_start")
        FullScreenSpinner.show(activity, isLoader)

        InterstitialAd.load(
            activity, id, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    FullScreenSpinner.hide()
                    googleInterAd = ad
                    googleInterLoadedAt = android.os.SystemClock.elapsedRealtime()
                    activity.safeLog("google_inter_ondemand_loaded")
                    showGoogleInterstitial(activity, pref, safeClose)
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    FullScreenSpinner.hide()
                    googleInterAd = null
                    activity.safeLog("google_inter_ondemand_fail_${error.code}")
                    handleGoogleFail(activity, pref, safeClose)
                }
            }
        )
    }

    // ----------------------------------------------------------------------
    // FACEBOOK PRELOADED SHOW (is_preload_ads = true)
    // ----------------------------------------------------------------------
    private fun showPreloadedFbAd(
        activity: Activity,
        onDismissed: () -> Unit,
        onFail: () -> Unit
    ) {
        val ad = preloadedFbAd
        if (ad == null || !ad.isAdLoaded) {
            preloadedFbAd = null
            onFail()
            return
        }
        preloadedFbAd = null
        // Wire forwarding callbacks (listener was set at load time in preloadFbAd)
        fbOnDismissed = onDismissed
        fbOnFail = onFail
        try {
            ad.show()
            activity.safeLog("fb_preloaded_show")
        } catch (e: Exception) {
            activity.safeLog("fb_preloaded_show_exception")
            fbOnDismissed = null
            fbOnFail = null
            onFail()
        }
    }

    private fun handleGoogleFail(
        activity: Activity,
        pref: AdsVault,
        safeClose: (String) -> Unit
    ) {
        activity.safeLog("google_fail_start")

        val fbEnabled = pref.getBoolean("IsFail_FB")
        val customEnabled = pref.getBoolean("IsCustomADS")

        if (fbEnabled) {

            activity.safeLog("google_fail_try_facebook")

            loadAndShowFacebookInter(
                activity,
                onDismissed = { safeClose("fb_dismiss") },
                onFail = {
                    activity.safeLog("fb_fail_after_google_fail")
                    showCustomAfterFacebookFail(activity, pref, safeClose)
                }
            )
        } else if (LauncherPlacementAds.hasFallback(activity, APP_PLACEMENT)) {
            // `inter_fallback`: rewarded / full-screen native / … before giving up on the slot.
            activity.safeLog("google_fail_fallback_chain")
            LauncherPlacementAds.runFallback(activity, APP_PLACEMENT) { safeClose("google_fail_fallback") }
        } else {
            if (customEnabled) {
                activity.safeLog("google_fail_open_custom")
                openDirectLink(activity) { safeClose("google_fail_custom") }
            } else safeClose("google_fail_no_fb_no_custom")
        }
    }

    // ----------------------------------------------------------------------
    // FACEBOOK INTERSTITIAL
    // ----------------------------------------------------------------------
    fun loadAndShowFacebookInter(
        context: Context,
        onDismissed: () -> Unit,
        onFail: () -> Unit
    ) {
        val pref = AdsVault.getInstance(context)
        val isLoader = InterLoader.enabled(pref)
        val fbId = pref.getString("faceB_InterAds") ?: return onFail()

        context.safeLog("facebook_inter_load_start")

        val inter = com.facebook.ads.InterstitialAd(context, fbId)

        // ⬅ FULLSCREEN LOADER (only if Activity)
        // Show loader only if isLoader == true
        if (context is Activity) FullScreenSpinner.show(context, isLoader)

        inter.loadAd(
            inter.buildLoadAdConfig()
                .withAdListener(object : InterstitialAdListener {

                    override fun onAdLoaded(ad: Ad?) {
                        context.safeLog("facebook_inter_loaded")
                        if (context is Activity) FullScreenSpinner.hide()
                        try {
                            inter.show()
                            context.safeLog("facebook_inter_show")
                        } catch (e: Exception) {
                            context.safeLog("facebook_inter_show_exception")
                            onFail()
                        }
                    }

                    override fun onError(ad: Ad?, error: com.facebook.ads.AdError?) {
                        context.safeLog("facebook_inter_error_${error?.errorCode}")
                        if (context is Activity) FullScreenSpinner.hide()
                        onFail()
                    }

                    override fun onInterstitialDismissed(ad: Ad?) {
                        AdsGate.fullScreenDismissed()
                        context.safeLog("facebook_inter_dismiss")
                        if (context is Activity) FullScreenSpinner.hide()
                        onDismissed()
                    }

                    override fun onInterstitialDisplayed(ad: Ad?) {
                        AdsGate.fullScreenShown()
                        context.safeLog("facebook_inter_displayed")

                        if (context is Activity) FullScreenSpinner.hide()
                    }

                    override fun onAdClicked(ad: Ad?) {
                        context.safeLog("facebook_inter_clicked")
                    }

                    override fun onLoggingImpression(ad: Ad?) {
                        context.safeLog("facebook_inter_impression")
                    }

                }).build()
        )
    }

    private fun showCustomAfterFacebookFail(
        activity: Activity,
        pref: AdsVault,
        safeClose: (String) -> Unit
    ) {
        activity.safeLog("custom_after_fb_fail")

        if (pref.getBoolean("IsCustomADS"))
            openDirectLink(activity) { safeClose("fb_fail_custom") }
        else safeClose("fb_fail_no_custom")
    }

    // ----------------------------------------------------------------------
    // SAFE LOG WRAPPER
    // ----------------------------------------------------------------------
    private fun Context.safeLog(event: String) {
        try {
            logKeyEvent(event)
            Log.d("InterADsLog", event)
        } catch (_: Exception) {
        }
    }
}
