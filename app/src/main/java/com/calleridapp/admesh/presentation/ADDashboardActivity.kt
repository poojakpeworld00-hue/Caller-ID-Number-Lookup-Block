package com.calleridapp.admesh.presentation

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.facebook.ads.Ad
import com.facebook.ads.InterstitialAdListener
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.FormError
import io.lighthouse.push.Attribution
import io.lighthouse.push.LightHouse
import com.calleridapp.admesh.data.AdKind
import com.calleridapp.admesh.data.OnDataReady
import com.calleridapp.admesh.data.getLocationFromIP
import com.calleridapp.admesh.domain.AdRevenueMeter
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.ConfigSync
import com.calleridapp.admesh.domain.GoogleMobileAdsConsentRegistry
import com.calleridapp.admesh.domain.logKeyEvent
import com.calleridapp.admesh.presentation.oninterAds.InterstitialBack
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.VaultRegistry
import com.calleridapp.numberlookup.permission.AccessEngine
import com.calleridapp.numberlookup.permission.AccessSource
import com.calleridapp.numberlookup.util.AppVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

open class ADDashboardActivity : AppCompatActivity() {
    private var customTabsSession: CustomTabsSession? = null
    private var customTabsClient: CustomTabsClient? = null
    var isCustomTabOpened = false
    var isCloseHandled = false
    var onCustomTabClosed: (() -> Unit)? = null
    private var activity: Activity? = null
    private var onGetData: OnDataReady? = null
    private var isSplash: Boolean? = false
    private val isMobileAdsInitialized = AtomicBoolean(false)
    private val isMobileAdsInitializeCalled = AtomicBoolean(false)
    private var isGoogleAdsEnabled = true
    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor()

    companion object {
        /** One grep-able tag for the whole splash AppOpen/interstitial load+show path. */
        const val APPOPEN_TAG = "AppOpenAd"

        /** One grep-able tag for the getData Remote Config → prefs ingestion path. */
        const val CONFIG_TAG = "GetDataConfig"

        /** Master switch for the IP-location "do not show" check. */
        const val COUNTRY_ENABLE_KEY = "Iscountry_Counter"

        /**
         * Comma-separated "do not show" locations. Each entry is matched against the
         * IP country, region and city (so `"Indore"`, `"Karnataka"` and `"India"` are
         * all valid entries), plus the [NSHOW_ALL] wildcard.
         */
        const val COUNTRY_LIST_KEY = "CountryList_Counter_NShow"

        /** Entry in [COUNTRY_LIST_KEY] that matches every location, worldwide. */
        const val NSHOW_ALL = "all"

        /**
         * Which audience a DEBUG build runs as — flip this one line to test the other side.
         * `true` = marketing, `false` = organic.
         *
         * A build installed from Studio or adb has no install referrer and no LightHouse
         * attribution, so it always resolves to organic on its own and the `marketing` half
         * of the config could never be exercised on a test device. Release builds ignore
         * this entirely and keep using the real attribution.
         *
         * [LookupShellApp] feeds the same value to `LightHouse.debugForceInstallSource` on a
         * debug build, so the SDK's own audience reads (disclosure variant, collection gating)
         * match the config half this picks. Without that they disagree: the app would run the
         * marketing config while the SDK still classified the sideload organic — which is
         * exactly the mismatch that reads as "organic install got the marketing config".
         */
        const val DEBUG_AUDIENCE_MARKETING = false
    }

    open fun getData(
        act: Activity, isSplsh: Boolean? = false, onData: OnDataReady
    ) {
        activity = act
        onGetData = onData
        isSplash = isSplsh

        // Audience FIRST, before consent / Remote Config / ingest / ads. Everything below this
        // point may read OnMaketing, and several of them exit early on failure (no internet,
        // consent error, a Remote Config fetch that times out) — resolving here is what stops
        // those paths from running the funnel on an audience nobody ever settled. It also means
        // the config is ingested once, on the right half, instead of being ingested provisionally
        // and re-ingested after the referrer lands.
        //
        // resolveAttribution waits for the Play referrer (LightHouseConfig.attributionWaitMs),
        // never answers UNKNOWN, and fires on the main thread. Launch 2+ is a cached read.
        LightHouse.resolveAttribution { attribution ->
            val isMarketingOn = if (BuildConfig.DEBUG) {
                DEBUG_AUDIENCE_MARKETING
            } else {
                attribution == Attribution.PAID
            }
            AdsVault.getInstance(act).putBoolean("OnMaketing", isMarketingOn)
            if (BuildConfig.DEBUG) {
                Log.d(CONFIG_TAG, "audience → ${if (isMarketingOn) "MARKETING" else "ORGANIC"}")
            }
            loadDataAfterAudience()
        }
    }

    private fun loadDataAfterAudience() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // We defer MobileAds.initialize() until after consent is obtained.

                if (!isInternetConnected(this@ADDashboardActivity)) {
                    withContext(Dispatchers.Main) {
                        onGetData?.onError()
                    }
                    return@launch
                }

                // Consent and SDK initialization
                withContext(Dispatchers.Main) {
                    try {
                        val googleMobileAdsConsentManager =
                            GoogleMobileAdsConsentRegistry.getInstance(
                                applicationContext
                            )

                        googleMobileAdsConsentManager.gatherConsent(this@ADDashboardActivity) { consentError: FormError? ->
                            // Always drive the flow forward once consent gathering
                            // completes. initializeMobileAdsSdk() is what kicks off
                            // remote config → prefs → permissions → navigation, and
                            // it's idempotent. If we only called it when canRequestAds
                            // is true, a consent failure (e.g. "Error making request"
                            // on a slow network) would silently dead-end the splash —
                            // no permission prompt, no navigation, hangs forever.
                            if (consentError != null) {
                                Log.w("ADDashboardActivity", "Consent error: ${consentError.message} — proceeding without ads consent")
                            }
                            initializeMobileAdsSdk()
                            if (googleMobileAdsConsentManager.isPrivacyOptionsRequired) {
                                invalidateOptionsMenu()
                            }
                        }

                        // Fast path: if consent is already available, start the SDK +
                        // downstream flow immediately instead of waiting on the callback.
                        if (googleMobileAdsConsentManager.canRequestAds()) {
                            initializeMobileAdsSdk()
                        }
                    } catch (e: Exception) {
                        Log.e("ADDashboardActivity", "Consent manager error", e)
                        initializeMobileAdsSdk() // Fallback
                        withContext(Dispatchers.Main) {
                            onGetData?.onError()
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onGetData?.onError()
                }
            }
        }
    }

    fun isInternetConnected(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        // For Android 10 (API level 29) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            // For older Android versions
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }
    }

    private fun initializeMobileAdsSdk() {
        if (isMobileAdsInitializeCalled.getAndSet(true)) {
            return
        }

        // Initialize MobileAds on a background thread after consent
        if (!isMobileAdsInitialized.getAndSet(true)) {
            backgroundExecutor.execute {
                try {
                    activity?.let {
                        isGoogleAdsEnabled = true
                        MobileAds.initialize(it) {}
                        if (BuildConfig.DEBUG) Log.d("ADDashboardActivity", "MobileAds initialized")
                    }
                } catch (e: Exception) {
                    isGoogleAdsEnabled = false
                    Log.e("ADDashboardActivity", "Failed to initialize MobileAds", e)
                    isMobileAdsInitialized.set(false)
                }
            }
        }

        // Fetch + settings are owned by ConfigSync — the launcher and the realtime
        // listener go through the same single-flight call, so however many entry points
        // ask, a cold start still costs one request.
        ConfigSync.fetch { ok ->
            if (ok) {
                lifecycleScope.launch(Dispatchers.IO) { setResponceInPref() }
            } else {
                onGetData?.onError()
            }
        }
    }

    private fun setResponceInPref() {
        // Run everything in a background thread to prevent cold-start stutters and ANR
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // The splash resolves a fresh IP location of its own, so it ingests here
                // and applies the location gate afterwards in funOnAdsLoad — hence
                // ingestActivated() rather than ingestAndApply().
                val isSplit = ConfigSync.ingestActivated(this@ADDashboardActivity) ?: return@launch

                // Must run AFTER the ingest so funOnAdsLoad reads the freshly-persisted
                // ad gates, not stale or half-written values.
                funOnAdsLoad(isSplit)

            } catch (e: Exception) {
                Log.e("ADDashboardActivity", "Failed to update ad preferences: ${e.message}")
            }
        }
    }

    /**
     * [isSplitConfig] is true when the getData response carried a top-level
     * `marketing` / `organic` split, i.e. the ingested block already holds the final
     * counters and the *Market* keys must not be copied over them.
     */
    private fun funOnAdsLoad(isSplitConfig: Boolean) {
        activity?.let { activity ->
            val adsPreference = AdsVault.getInstance(activity)
            lifecycleScope.launch(Dispatchers.IO) {

                // Fetch IP geo once. Use the ISO country code to set the app's
                // country (Home search chip + Lookup country picker) unless the
                // user already picked one, then feed the ads country-counter logic.
                val location = getLocationFromIP()
                location?.countryCode?.takeIf { it.isNotBlank() }?.let { iso ->
                    val prefs = VaultRegistry(activity)
                    if (prefs.homeCountryIso.isBlank()) {
                        prefs.homeCountryIso = iso
                        if (BuildConfig.DEBUG) Log.d("LocationCheck", "Country code set from IP: $iso")
                    }
                }

                // Settled in getData, before consent / Remote Config / this call.
                val isMarketingOn = adsPreference.getBoolean("OnMaketing")

                // The location gate itself lives in ConfigSync so the launcher and the
                // realtime listener can re-run it against the cached location without a
                // second IP lookup. Here we have a fresh one, so we pass that.
                location?.let { loc ->
                    if (BuildConfig.DEBUG) {
                        Log.d("LocationCheck", "=== Location Info ===")
                        Log.d("LocationCheck", "Country: ${loc.country}")
                        Log.d("LocationCheck", "Region: ${loc.regionName}")
                        Log.d("LocationCheck", "City: ${loc.city}")
                    }
                    adsPreference.userCountry = loc.country.orEmpty()
                    adsPreference.userRegion = loc.regionName.orEmpty()
                    adsPreference.userCity = loc.city.orEmpty()
                } ?: run {
                    if (BuildConfig.DEBUG) Log.w("LocationCheck", "⚠️ Location not available")
                }
                ConfigSync.applyLocationGate(
                    activity,
                    location?.country ?: adsPreference.userCountry,
                    location?.regionName ?: adsPreference.userRegion,
                    location?.city ?: adsPreference.userCity,
                )

                // (marketing state already decided above as `isMarketingOn`)

                // --------------------
                // 1️⃣ Apply marketing counters (ONLY if marketing ON).
                //     Skipped for a top-level split config — its `marketing` block
                //     already carries the final counters/links, so copying the
                //     *Market* keys (absent there) would zero them out.
                // --------------------
                if (isMarketingOn && !isSplitConfig) {
                    with(adsPreference) {
                        putInt("InterCounter", getInt("MarketInterCounter"))
                        putInt("InterBackCounter", getInt("MarketBackCounter"))
                        putInt("MidNativeCounter", getInt("MarketNativeCounter"))
                        putInt("NativeCounter", getInt("MarketNativeCounter"))
                        putInt("BannerCounter", getInt("MarketBannerCounter"))
                        putInt("AppopenCounter", getInt("MarketAppopenCounter"))
                        putBoolean("IsBack", true)
                        putString("DirectLink", getString("MarketLink"))
                        putBoolean("is_intro", true)
                    }
                }
                // --------------------
                // 2️⃣ Apply NativeTheme (Marketing or Default)
                // --------------------
                val savedMarketingStr = adsPreference.getString("NativeTheme_marketing", "{}")
                val savedDefaultStr = adsPreference.getString("NativeTheme_default", "{}")

                val marketingObj = JSONObject(savedMarketingStr)
                val defaultObj = JSONObject(savedDefaultStr)

                // Decide theme source
                val themeSource = if (isMarketingOn) marketingObj else defaultObj
                val modeKey = ConfigSync.nativeThemeKey(activity)
                // Get the correct modeKey (e.g., "NativeDark" or "NativeLight")
                val themeJson = themeSource.optJSONObject(modeKey)

                themeJson?.let { theme ->
                    adsPreference.putString("NativebtnColor", theme.optString("btnColor"))
                    adsPreference.putString("NativebtntxtColor", theme.optString("btnText"))
                    adsPreference.putString("NativeBgColor", theme.optString("bgColor"))
                    adsPreference.putString("NativetxtColor", theme.optString("textColor"))

                    if (BuildConfig.DEBUG) Log.d(
                        "NativeTheme",
                        "Applied ${if (isMarketingOn) "MARKETING" else "DEFAULT"} $modeKey theme"
                    )
                }

                launch {

                    val isAdsOn = adsPreference.getBoolean("IsAdsON")


                    withContext(Dispatchers.Main) {

                        // 🔹 Native ads (never block navigation)
                        if (isAdsOn && isSplash == false) {
                            Log.d(APPOPEN_TAG, "isSplash=false → BS Native path (splash AppOpen is NOT attempted here)")
                            launch(Dispatchers.Main) {
                                SheetNativeAds().BS_loadNativeADs(activity)
                                onGetData?.onSuccess()
                            }
                            return@withContext
                        }

                        // Splash owns its permission priming explicitly (notification
                        // → phone_state) via AccessEngine.request() — the targeted,
                        // activity-independent path — rather than check()'s Activity-name
                        // matching. This guarantees the OS Allow/Deny dialogs fire HERE,
                        // on the splash, BEFORE the splash ad, even when the Remote Config
                        // permission_engine rules don't list LaunchActivity. Resolving
                        // notification now also stops LightHouse's later
                        // subscribeAsync()/data-disclosure from re-prompting for it on the
                        // next screen. Order is: permission(s) → ad → dismiss/fail → next.
                        primeSplashPermissions(activity) {
                            if (!isAdsOn) {
                                // Ads OFF → continue
                                Log.w(APPOPEN_TAG, "IsAdsON=false → ads disabled, no AppOpen, continuing to app")
                                onGetData?.onSuccess()
                            } else {
                                if (isGoogleAdsEnabled) {
                                    NativePromo().loadNativeADs(activity)
                                    NativePromoBanner().loadNativeBannerAds(activity)
                                    InterstitialNormal().loadInterAds(activity)
                                    InterstitialBack().loadBackInterAds(activity)
                                }

                                // Splash ads gated by Firebase "is_splash_ads" flag
                                // AND days-since-install check
                                val isSplashAdsEnabled = adsPreference.getBoolean("is_splash_ads")
                                Log.d(
                                    APPOPEN_TAG,
                                    "gate → IsAdsON=$isAdsOn, isSplash=$isSplash, is_splash_ads=$isSplashAdsEnabled, " +
                                        "IsAdType=${adsPreference.getString("IsAdType")}, appopenId=${adsPreference.getString("googleAppopen")}"
                                )
                                if (!isSplashAdsEnabled) {
                                    // Splash ads disabled from Firebase → skip entirely
                                    Log.w(APPOPEN_TAG, "is_splash_ads=false → skipping splash ad entirely, continuing to app")
                                    onGetData?.onSuccess()
                                } else {
                                    // Ads ON + splash enabled + gate passed → preload → show → continue
                                    preloadAds(adsPreference, activity) {
                                        showPreloadedAd(activity, adsPreference) {
                                            onGetData?.onSuccess()
                                        }
                                    }
                                }
                            }
                        }

                    }
                }

            }
        }
    }

    /**
     * Splash no longer requests any runtime permission directly. Notification
     * and READ_PHONE_STATE are now owned entirely by the global
     * [com.calleridapp.numberlookup.permission.AccessEngine]
     * (Remote Config-driven, per-Activity, with the HD_VBC_Show gate preserved
     * for phone state). Kept as a thin pass-through so the splash navigation
     * flow is unchanged. [hdVbcShow] is intentionally unused now.
     */
    private fun requestUserPermissions(
        @Suppress("UNUSED_PARAMETER") hdVbcShow: Boolean, onContinue: () -> Unit
    ) {
        onContinue()
    }

    /**
     * Sequentially primes the splash's runtime permissions — notification, then
     * phone_state — through [AccessEngine.request], then runs [onDone].
     *
     * Uses request() (targeted by permission key) so the prompts fire HERE, in
     * this exact order, before the splash ad — but only for keys whose Remote
     * Config rule actually lists this screen (see [targetsScreen]). So dropping
     * `LaunchActivity` from a permission's `activities` list keeps it off the
     * splash, and it is then asked wherever it *is* listed (e.g. ShellActivity).
     * Each request still honours SDK applicability (notification only on API
     * 33+), the `HD_VBC_Show` gate (phone_state), already-granted, `show_once`,
     * and the remote enable off-switch. Each callback always fires once on the
     * main thread, so [onDone] runs exactly once after both resolve (or are
     * skipped).
     */
    private fun primeSplashPermissions(activity: Activity, onDone: () -> Unit) {
        // OnMaketing is only final a few lines above (LightHouse attribution), and
        // the engine may have cached its rules earlier — at Application startup,
        // when the audience was still unknown. Re-parse so the marketing/organic
        // split below is read from the right side of the config.
        runCatching { AccessSource.reload() }
        val screen = activity::class.java.simpleName
        val prime: (String, () -> Unit) -> Unit = { key, next ->
            if (targetsScreen(key, screen)) AccessEngine.request(activity, key) { next() }
            else next()
        }
        prime("notification") {
            prime("phone_state") {
                onDone()
            }
        }
    }

    /**
     * True when the Remote Config rule for [key] targets [screen]. A key with no
     * configured rule returns true — there is nothing to opt out of, so the
     * splash keeps its previous behaviour of asking.
     */
    private fun targetsScreen(key: String, screen: String): Boolean {
        val rule = AccessSource.rules().firstOrNull { it.key == key } ?: return true
        val targets = rule.activities.any { it.equals(screen, ignoreCase = true) }
        if (!targets) Log.d("AccessEngine", "'$key' not configured for $screen — skipped on splash")
        return targets
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ------------------------
    // AppOpenAd
    // ------------------------
    // Preload all ads in sequence or parallel
    fun preloadAds(adsPreference: AdsVault, activity: Activity, onComplete: () -> Unit) {
        bindCustomTabs(activity)
        val adType = AdKind.fromString(adsPreference.getString("IsAdType"))
        Log.d(APPOPEN_TAG, "preload() → IsAdType=$adType, googleAdsEnabled=$isGoogleAdsEnabled")
        when (adType) {
            AdKind.GOOGLE -> {
                if (isGoogleAdsEnabled) {
                    val showInterstitialOnSplash = adsPreference.getBoolean("is_splash_inter_show")
                    if (showInterstitialOnSplash) {
                        Log.d(APPOPEN_TAG, "preload → is_splash_inter_show=true → loading splash INTERSTITIAL instead of AppOpen")
                        loadGoogleInterstitialWithFallback(activity, adsPreference, onComplete)
                    } else {
                        Log.d(APPOPEN_TAG, "preload → is_splash_inter_show=false → loading APPOPEN")
                        loadAppOpenAdWithFallback(activity, adsPreference, onComplete)
                    }
                } else {
                    Log.w(APPOPEN_TAG, "preload → Google ads disabled (init failed) → Facebook fallback")
                    loadFacebookFallback(activity, adsPreference, onComplete)
                }
            }

            AdKind.FACEBOOK -> {
                if (adsPreference.getBoolean("IsFail_FB")) {
                    val fbId = adsPreference.getString("faceB_InterAds")!!
                    loadFacebookInterstitial(
                        activity,
                        fbId,
                        onLoaded = { onComplete() },
                        onFailed = { onComplete() },
                        onDismissed = {
                            onComplete()
                        }// fallback
                    )
                } else {
                    onComplete()
                }
            }

            AdKind.CUSTOM, AdKind.UNKNOWN -> onComplete() // nothing to preload
        }
    }

    fun showPreloadedAd(activity: Activity, adsPreference: AdsVault, onDismissed: () -> Unit) {
        when (AdKind.fromString(adsPreference.getString("IsAdType"))) {
            AdKind.GOOGLE -> {
                Log.d(APPOPEN_TAG, "showPreloaded() → appOpenReady=${appOpenAd != null}, interstitialReady=${interstitialAd != null}")
                if (isGoogleAdsEnabled && (appOpenAd != null || interstitialAd != null)) {
                    if (appOpenAd != null) {
                        showAppOpenAd(activity) { onDismissed() }
                    } else if (interstitialAd != null) {
                        showGoogleInterstitial(activity) {
                            onDismissed()
                        }
                    }
                } else {
                    if (adsPreference.getBoolean("IsFail_FB")) {
                        val fbId = adsPreference.getString("faceB_InterAds")
                        if (!fbId.isNullOrEmpty() && fbInterstitial != null) {
                            showFacebookInterstitial { onDismissed() }
                        } else if (adsPreference.getBoolean("IsCustomADS")) {
                            // All failed + custom ads ON → fallback to custom link
                            launchCustomAdLink(
                                activity, adsPreference.getString("DirectLink")!!
                            ) {
                                onDismissed()
                            }
                        } else {
                            onDismissed()
                        }
                    } else if (adsPreference.getBoolean("IsCustomADS")) {
                        // All failed + custom ads ON → fallback to custom link
                        launchCustomAdLink(
                            activity, adsPreference.getString("DirectLink")!!
                        ) {
                            onDismissed()
                        }
                    } else {
                        onDismissed()
                    }
                }
            }

            AdKind.FACEBOOK -> {
                if (fbInterstitial != null) showFacebookInterstitial { onDismissed() }
                else onDismissed()
            }

            AdKind.CUSTOM, AdKind.UNKNOWN -> {
                if (adsPreference.getBoolean("IsCustomADS")) {
                    launchCustomAdLink(
                        activity, adsPreference.getString("DirectLink")!!
                    ) {
                        onDismissed()
                    }
                } else {
                    onDismissed()
                }
            }
        }
    }

    private var appOpenAd: AppOpenAd? = null
    private fun loadGoogleInterstitialWithFallback(
        activity: Activity, adsPreference: AdsVault, onComplete: () -> Unit
    ) {
        val googleId = adsPreference.getString("googleS_Inter") ?: run { onComplete(); return }
        loadGoogleInterstitial(activity, googleId, onLoaded = { onComplete() }, onFailed = {
            loadFacebookFallback(activity, adsPreference, onComplete)
        })
    }

    private fun loadAppOpenAdWithFallback(
        activity: Activity, adsPreference: AdsVault, onComplete: () -> Unit
    ) {
        val appOpenId = adsPreference.getString("googleAppopen")
        if (appOpenId.isNullOrBlank()) {
            Log.w(APPOPEN_TAG, "no/blank 'googleAppopen' unit id in Remote Config → skipping AppOpen, continuing")
            onComplete(); return
        }
        loadAppOpenAd(activity, appOpenId, onLoaded = { onComplete() }, onFailed = {
            Log.w(APPOPEN_TAG, "AppOpen load failed → falling back to Google interstitial")
            loadGoogleInterstitialWithFallback(activity, adsPreference, onComplete)
        })
    }

    private fun loadFacebookFallback(
        activity: Activity, adsPreference: AdsVault, onComplete: () -> Unit
    ) {
        if (adsPreference.getBoolean("IsFail_FB")) {
            val fbId = adsPreference.getString("faceB_InterAds")
            if (!fbId.isNullOrEmpty()) {
                loadFacebookInterstitial(
                    activity,
                    fbId,
                    onLoaded = { onComplete() },
                    onFailed = { onComplete() },
                    onDismissed = { onComplete() })
            } else onComplete()
        } else onComplete()
    }

    fun loadAppOpenAd(
        activity: Activity,
        adUnitId: String,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null
    ) {
        Log.d(APPOPEN_TAG, "load() → requesting AppOpen, id=$adUnitId")
        AppOpenAd.load(
            activity,
            adUnitId,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    // The single most useful line for "why didn't it load".
                    Log.e(
                        APPOPEN_TAG,
                        "load FAILED → code=${loadAdError.code}, domain=${loadAdError.domain}, " +
                            "message=${loadAdError.message}"
                    )
                    appOpenAd = null
                    AppOpenAdRegistry.isShowingAd = false
                    onFailed?.invoke()
                }

                override fun onAdLoaded(ad: AppOpenAd) {
                    Log.d(APPOPEN_TAG, "load SUCCESS → AppOpen ad is ready to show")
                    appOpenAd = ad
                    onLoaded?.invoke()
                }
            })
    }

    fun showAppOpenAd(activity: Activity, onDismissed: (() -> Unit)? = null) {
        appOpenAd?.let { ad ->
            Log.d(APPOPEN_TAG, "show() → displaying AppOpen ad")
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    onDismissed?.invoke()
                    AppOpenAdRegistry.isShowingAd = false
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    onDismissed?.invoke()
                }

                override fun onAdShowedFullScreenContent() {
                    AppOpenAdRegistry.isShowingAd = true
                }
            }
            // Log load
            activity.logKeyEvent("AppOpen_Loaded")

            if (BuildConfig.DEBUG) AdRevenueMeter.simulateDebugRevenue(activity)

            appOpenAd?.setOnPaidEventListener {
                AdRevenueMeter.logPaidEvent(activity, it)
            }
            ad.show(activity)
        } ?: run {
            // Ad not loaded yet
            Log.w(APPOPEN_TAG, "show() → AppOpen ad is null (not loaded in time) → skipping show")
            onDismissed?.invoke()
        }
    }

    // ------------------------
// Google Interstitial
// ------------------------
    private var interstitialAd: InterstitialAd? = null

    fun loadGoogleInterstitial(
        activity: Activity,
        adUnitId: String,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null
    ) {
        InterstitialAd.load(
            activity, adUnitId, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    onLoaded?.invoke()
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    interstitialAd = null
                    onFailed?.invoke()
                }
            })
    }

    fun showGoogleInterstitial(activity: Activity, onDismissed: (() -> Unit)? = null) {
        interstitialAd?.let { ad ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    onDismissed?.invoke()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    onDismissed?.invoke()
                }

                override fun onAdShowedFullScreenContent() {
                }
            }

            // Log load
            activity.logKeyEvent("Interstitial_Splash_Loaded")

            if (BuildConfig.DEBUG) AdRevenueMeter.simulateDebugRevenue(activity)

            interstitialAd?.setOnPaidEventListener {
                AdRevenueMeter.logPaidEvent(activity, it)
            }
            ad.show(activity)
        } ?: run { onDismissed?.invoke() }
    }

    // ------------------------
// Facebook Interstitial
// ------------------------
    private var fbInterstitial: com.facebook.ads.InterstitialAd? = null

    fun loadFacebookInterstitial(
        activity: Activity,
        adUnitId: String,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
        onDismissed: (() -> Unit)? = null // Added parameter
    ) {
        fbInterstitial = com.facebook.ads.InterstitialAd(activity, adUnitId)
        fbInterstitial?.loadAd(
            fbInterstitial!!.buildLoadAdConfig().withAdListener(object : InterstitialAdListener {
                override fun onInterstitialDisplayed(ad: Ad?) {}
                override fun onInterstitialDismissed(ad: Ad?) {
                    onDismissed?.invoke()
                }

                override fun onError(ad: Ad?, adError: com.facebook.ads.AdError?) {
                    onFailed?.invoke()
                }

                override fun onAdLoaded(ad: Ad?) {
                    onLoaded?.invoke()
                }

                override fun onAdClicked(ad: Ad?) {}
                override fun onLoggingImpression(ad: Ad?) {}
            }).build()
        )
    }

    fun showFacebookInterstitial(onDismissed: (() -> Unit)? = null) {
        val ad = fbInterstitial

        if (ad != null && ad.isAdLoaded) {
            ad.show()
        } else {
            onDismissed?.invoke()
        }
    }

    // 1️⃣ Bind Custom Tabs
    private fun bindCustomTabs(activity: Activity) {
        if (customTabsClient != null) return

        CustomTabsClient.bindCustomTabsService(
            activity, "com.android.chrome", object : CustomTabsServiceConnection() {
                override fun onCustomTabsServiceConnected(
                    name: ComponentName, client: CustomTabsClient
                ) {
                    customTabsClient = client
                    customTabsSession = client.newSession(object : CustomTabsCallback() {
                        override fun onNavigationEvent(event: Int, extras: Bundle?) {
                            if ((event == CustomTabsCallback.TAB_HIDDEN || event == CustomTabsCallback.NAVIGATION_ABORTED) && isCustomTabOpened) {
                                handleCustomTabClose()
                            }
                        }
                    })
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    customTabsClient = null
                    customTabsSession = null
                }
            })
    }

    // 2️⃣ Handle close in one function
    private fun handleCustomTabClose() {
        if (isFinishing || isDestroyed) return
        if (!isCloseHandled) {
            isCloseHandled = true
            isCustomTabOpened = false
            onCustomTabClosed?.invoke()
        }
    }

    override fun onDestroy() {
        try {
            customTabsClient = null
            customTabsSession = null
            onCustomTabClosed = null
            activity = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    // 3️⃣ Launch Custom Tab
    private fun launchCustomAdLink(activity: Activity, url: String, onClosed: () -> Unit) {
        bindCustomTabs(activity)

        isCustomTabOpened = true
        isCloseHandled = false
        onCustomTabClosed = { onClosed() }

        val customTabsIntent = CustomTabsIntent.Builder(customTabsSession).setShowTitle(true)
            .setToolbarColor(ContextCompat.getColor(activity, R.color.black)).build()

        try {
            customTabsIntent.launchUrl(activity, Uri.parse(url))
        } catch (e: Exception) {
            // fallback to browser
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            handleCustomTabClose()
        }
    }

    override fun onResume() {
        super.onResume()
        handleCustomTabClose()
    }

}
