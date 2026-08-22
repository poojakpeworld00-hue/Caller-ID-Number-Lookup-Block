package com.calleridapp.admesh.domain

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.calleridapp.admesh.presentation.CustomAdsRegistry
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.permission.AccessSource
import com.calleridapp.numberlookup.util.AppVault
import com.calleridapp.numberlookup.util.AppVault.THEME_DARK
import com.calleridapp.numberlookup.util.AppVault.THEME_LIGHT
import com.calleridapp.numberlookup.util.AppVault.THEME_SYSTEM
import com.facebook.FacebookSdk
import com.facebook.LoggingBehavior
import com.facebook.appevents.AppEventsLogger
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.ConfigUpdateListenerRegistration
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import org.json.JSONObject

/**
 * The one place that turns Remote Config into app state.
 *
 * It owns three things that used to live inside the splash Activity, and that the
 * launcher had no way to reach:
 *
 *  - **the fetch** — one shared [FirebaseRemoteConfigSettings], one single-flight
 *    [fetch]. Concurrent callers join the request in progress instead of starting a
 *    second one, so a cold start costs exactly one network round-trip no matter how
 *    many entry points ask.
 *  - **the ingest** — reading `GET_DATA_LIST` / `DEBUG_GET_DATA_LIST`, picking the
 *    audience half, and writing every key into [AdsVault] ([ingestActivated]).
 *  - **staying current** — [refreshIfStale] for a cheap periodic top-up, and
 *    [startRealtime] for Firebase's push channel, which needs no fetch at all.
 *
 * ### Why the launcher needs this
 * When the app holds `ROLE_HOME`, pressing HOME opens the launcher Activity directly.
 * The splash never runs, and the home process is kept warm for days, so
 * `Application.onCreate` doesn't run either. Without an entry point of its own, a
 * launcher user would sit on a config snapshot from whenever they last opened the
 * app the long way round.
 */
object ConfigSync {

    private const val TAG = "ConfigSync"

    /** Prefs key holding the wall-clock time of the last completed ingest. */
    private const val LAST_SYNC_KEY = "last_config_sync_at"

    /**
     * Remote Config key (integer, **hours**) for how long an ingest stays fresh before
     * [refreshIfStale] fetches again. Absent or `<= 0` falls back to
     * [DEFAULT_STALE_HOURS] — so the window is tunable from the console without a
     * release, and a typo can never turn it into a fetch-every-resume loop.
     *
     * Chicken-and-egg is harmless: the value arrives with the ingest it governs, so the
     * very first sync of an install uses the default and every later one uses yours.
     */
    const val SYNC_HOURS_KEY = "Config_Sync_Hrs"

    /** Window used when [SYNC_HOURS_KEY] is unset. */
    private const val DEFAULT_STALE_HOURS = 6

    /** Remote Config's own client-side throttle. */
    private const val MIN_FETCH_INTERVAL_SEC = 1L

    /**
     * Cap the fetch so a slow network can't park the splash on this call (it defaults
     * to 60s; 37s stalls were observed). On timeout the fetch fails fast and callers
     * fall through to the activated/cached values.
     */
    private const val FETCH_TIMEOUT_SEC = 10L

    /**
     * The `setConfigSettingsAsync` task, kept so [fetch] can wait for it.
     *
     * Not a bare boolean: the call is asynchronous, and firing `fetchAndActivate` before it
     * lands leaves the SDK on its DEFAULT 12-hour minimum fetch interval — which answers from
     * cache and still reports success, so a freshly published template silently never arrives.
     */
    @Volatile private var settingsTask: com.google.android.gms.tasks.Task<Void>? = null
    @Volatile private var fetchInFlight = false
    private val waiting = mutableListOf<(Boolean) -> Unit>()
    @Volatile private var realtime: ConfigUpdateListenerRegistration? = null

    /** The blob parameter this build reads. Debug and release are separate on purpose. */
    val blobKey: String get() = if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST" else "GET_DATA_LIST"

    // ─────────────────────────────── Fetch ───────────────────────────────

    private fun remoteConfig(): FirebaseRemoteConfig {
        val rc = FirebaseRemoteConfig.getInstance()
        // Applied once per process. Previously three call sites each pushed their own
        // settings onto this singleton and raced over the fetch interval.
        if (settingsTask == null) {
            synchronized(this) {
                if (settingsTask == null) {
                    settingsTask = rc.setConfigSettingsAsync(
                        FirebaseRemoteConfigSettings.Builder()
                            .setMinimumFetchIntervalInSeconds(MIN_FETCH_INTERVAL_SEC)
                            .setFetchTimeoutInSeconds(FETCH_TIMEOUT_SEC)
                            .build()
                    )
                }
            }
        }
        return rc
    }

    /**
     * `fetchAndActivate`, at most one at a time. A caller arriving while a fetch is in
     * flight is added to the waiting list and gets the same result — it does not start
     * a second request. [onDone] always runs, on the main thread.
     */
    fun fetch(onDone: (Boolean) -> Unit) {
        synchronized(waiting) {
            if (fetchInFlight) {
                waiting += onDone
                Log.d(TAG, "fetch already in flight — joined (${waiting.size} waiting)")
                return
            }
            fetchInFlight = true
        }
        try {
            val rc = remoteConfig()
            val startFetch = {
                rc.fetchAndActivate().addOnCompleteListener { task ->
                    val ok = task.isSuccessful
                    Log.d(
                        TAG,
                        "fetchAndActivate success=$ok" +
                            (task.exception?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
                    )
                    val joined = synchronized(waiting) {
                        fetchInFlight = false
                        waiting.toList().also { waiting.clear() }
                    }
                    onDone(ok)
                    joined.forEach { runCatching { it(ok) } }
                }
                Unit
            }

            // Wait for the settings, or the fetch runs under the SDK's 12-hour default
            // interval and answers from cache while still reporting success — see
            // [settingsTask]. Already-complete is the steady state, so this costs one
            // branch on every fetch after the first.
            val settings = settingsTask
            if (settings == null || settings.isComplete) {
                startFetch()
            } else {
                settings.addOnCompleteListener { startFetch() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetch failed", e)
            val joined = synchronized(waiting) {
                fetchInFlight = false
                waiting.toList().also { waiting.clear() }
            }
            onDone(false)
            joined.forEach { runCatching { it(false) } }
        }
    }

    // ─────────────────────────────── Ingest ───────────────────────────────

    /**
     * Writes the currently *activated* config into [AdsVault]. No fetch — call it after
     * [fetch], or from the realtime listener once `activate()` has completed.
     *
     * Returns whether the response carried a top-level `marketing` / `organic` split
     * (the splash needs it), or `null` when there was nothing to ingest.
     *
     * Blocking JSON work: call it off the main thread.
     */
    fun ingestActivated(context: Context): Boolean? {
        return try {
            val configString = remoteConfig().getString(blobKey)
            if (configString.isEmpty()) {
                Log.w(TAG, "$blobKey is empty → nothing ingested (using cached prefs)")
                return null
            }
            // Unguarded: the two facts that decide which slice of the template this device
            // actually reads. Nearly every "I published and nothing changed" turns out to be
            // an edit to the OTHER blob or the OTHER audience half.
            Log.d(TAG, "ingest ← $blobKey / ${if (AdsVault.getInstance(context).getBoolean("OnMaketing")) "marketing" else "organic"}")
            val response = JSONObject(configString)
            val adsPref = AdsVault.getInstance(context)
            val isSplit = response.has("marketing") || response.has("organic")
            val onMarketing = adsPref.getBoolean("OnMaketing")
            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "fetched $blobKey (${configString.length} chars) → audienceSplit=$isSplit, OnMaketing=$onMarketing"
            )
            ingestConfig(context, audienceRoot(response, onMarketing))
            // The permission engine parses Remote Config itself and caches the result,
            // so it has to be told the values moved on.
            AccessSource.reload()
            adsPref.putLong(LAST_SYNC_KEY, System.currentTimeMillis())
            isSplit
        } catch (e: Exception) {
            Log.e(TAG, "ingest failed", e)
            null
        }
    }

    /**
     * [ingestActivated] plus a re-run of the location gate against the country/region/city
     * already cached in [AdsVault] — no new IP lookup. This is the entry point for anything
     * that is not the splash: the splash resolves a fresh location itself and calls
     * [applyLocationGate] directly.
     */
    fun ingestAndApply(context: Context): Boolean? {
        val isSplit = ingestActivated(context) ?: return null
        val adsPref = AdsVault.getInstance(context)
        applyLocationGate(context, adsPref.userCountry, adsPref.userRegion, adsPref.userCity)
        return isSplit
    }

    /**
     * Fetch + ingest, but only when the last sync is older than the [SYNC_HOURS_KEY]
     * window (or [force] is set). Safe to call from `onResume` — HOME gets pressed dozens
     * of times a day and all but the first land inside the window and cost nothing.
     *
     * This is only the **backstop** for [startRealtime]: it covers the phone that was
     * offline when the template was published, or a device the push channel never
     * reached. The realtime path is what makes a console change land in seconds.
     */
    fun refreshIfStale(context: Context, force: Boolean = false, onDone: (() -> Unit)? = null) {
        val adsPref = AdsVault.getInstance(context)
        val window = staleAfterMs(context)
        val age = System.currentTimeMillis() - adsPref.getLong(LAST_SYNC_KEY, 0L)
        // `window == 0` → the range is empty and nothing is ever considered fresh.
        if (!force && age in 0 until window) {
            Log.d(TAG, "config is ${age / 60_000}min old (window ${window / 60_000}min) — no fetch")
            onDone?.invoke()
            return
        }
        val app = context.applicationContext
        fetch { ok ->
            if (ok) {
                Thread {
                    // Stamp on a successful *fetch*, not only on a successful ingest.
                    // ingestActivated() bails out before stamping when the blob is empty
                    // (parameter never set, wrong build's key) — without this, that
                    // misconfiguration would leave the window permanently expired and
                    // fetch on every single resume.
                    AdsVault.getInstance(app).putLong(LAST_SYNC_KEY, System.currentTimeMillis())
                    ingestAndApply(app)
                    onDone?.invoke()
                }.start()
            } else {
                onDone?.invoke()
            }
        }
    }

    /**
     * The freshness window in millis.
     *
     * `> 0` is that many hours. **`0` means no window at all** — every [refreshIfStale] call
     * fetches, which on the launcher home is once per HOME press, so it is a testing /
     * force-fresh setting rather than something to ship. Absent reads back as `-1` (see
     * `AdsVault.getInt`) and falls back to [DEFAULT_STALE_HOURS], as does any other negative
     * value, so a typo can never turn into a fetch-every-resume loop by accident.
     */
    private fun staleAfterMs(context: Context): Long {
        val hours = AdsVault.getInstance(context).getInt(SYNC_HOURS_KEY)
        if (hours == 0) return 0L
        return (if (hours > 0) hours else DEFAULT_STALE_HOURS) * 60L * 60L * 1000L
    }

    // ───────────────────────────── Realtime ─────────────────────────────

    /**
     * Subscribes to Firebase's realtime config channel. When a new template is published
     * the SDK pushes it, we `activate()` and re-ingest — **without a fetch**, so this
     * costs no extra requests at all. That is what makes a console change reach a phone
     * that is parked on the home screen.
     *
     * Register from `onStart` and drop it in [stopRealtime] on `onStop`: the channel is a
     * live server connection and should not outlive the screen that wants it. Calling it
     * twice is a no-op.
     */
    fun startRealtime(context: Context) {
        if (realtime != null) return
        val app = context.applicationContext
        realtime = runCatching {
            remoteConfig().addOnConfigUpdateListener(object : ConfigUpdateListener {
                override fun onUpdate(configUpdate: ConfigUpdate) {
                    Log.d(TAG, "realtime update: ${configUpdate.updatedKeys}")
                    // Only re-ingest when something we actually read moved.
                    if (configUpdate.updatedKeys.none { it == blobKey || it == "permission_engine" }) {
                        Log.d(TAG, "realtime: none of those is $blobKey → ignored")
                        return
                    }
                    remoteConfig().activate().addOnCompleteListener {
                        Thread { ingestAndApply(app) }.start()
                    }
                }

                override fun onError(error: FirebaseRemoteConfigException) {
                    Log.w(TAG, "realtime listener error", error)
                }
            })
        }.onFailure { Log.w(TAG, "realtime unavailable", it) }.getOrNull()
    }

    /** Drops the realtime subscription registered by [startRealtime]. */
    fun stopRealtime() {
        runCatching { realtime?.remove() }
        realtime = null
    }

    // ─────────────────────────── Location gate ───────────────────────────

    /**
     * Applies the `Iscountry_Counter` / `CountryList_Counter_NShow` "do not show" gate to
     * the given location and remembers the verdict in [AdsVault.isNShowLocation].
     *
     * One pair of keys for both audiences: the blob is already split into
     * `marketing` / `organic`, so the old `*_Marketing_*` duplicate is gone.
     *
     * Entries match the country, region **or** city (so `"India"`, `"Karnataka"` and
     * `"Indore"` are all valid), and the literal [NSHOW_ALL] matches every device without
     * needing a location at all — so it still applies when the IP lookup failed.
     *
     * @return true when this device is inside the list.
     */
    fun applyLocationGate(
        context: Context,
        country: String?,
        region: String?,
        city: String?,
    ): Boolean {
        val adsPref = AdsVault.getInstance(context)
        if (!adsPref.getBoolean(COUNTRY_ENABLE_KEY)) {
            // Check off = nothing suppressed; clear any earlier match so a config change
            // takes effect immediately.
            adsPref.isNShowLocation = false
            if (BuildConfig.DEBUG) Log.d(TAG, "country check disabled → nothing suppressed")
            return false
        }

        val nShowList = (adsPref.getString(COUNTRY_LIST_KEY, "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val matchesAll = nShowList.any { it.equals(NSHOW_ALL, ignoreCase = true) }
        val matchesLocation = nShowList.any { entry ->
            entry.equals(country, ignoreCase = true) ||
                entry.equals(region, ignoreCase = true) ||
                entry.equals(city, ignoreCase = true)
        }
        val isNShow = matchesAll || matchesLocation
        adsPref.isNShowLocation = isNShow

        if (isNShow) {
            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "✅ location IN list ($COUNTRY_LIST_KEY${if (matchesAll) ", via \"$NSHOW_ALL\"" else ""}) " +
                    "→ HD_VBC_Show=false (real ads)"
            )
            adsPref.putBoolean("HD_VBC_Show", false)
        } else if (BuildConfig.DEBUG) {
            Log.d(TAG, "❌ location NOT in list ($COUNTRY_LIST_KEY) → HD_VBC_Show unchanged")
        }
        return isNShow
    }

    /** Master switch for the IP-location "do not show" check. */
    const val COUNTRY_ENABLE_KEY = "Iscountry_Counter"

    /** Comma-separated "do not show" locations — country, region or city names. */
    const val COUNTRY_LIST_KEY = "CountryList_Counter_NShow"

    /** Entry in [COUNTRY_LIST_KEY] that matches every location, worldwide. */
    const val NSHOW_ALL = "all"

    // ───────────────────────── Config → prefs ─────────────────────────

    /**
     * Reads every getData key from [root] into AdsVault (batched). [root] is either the
     * flat response or one of its `marketing` / `organic` sub-objects (see [audienceRoot]).
     */
    fun ingestConfig(context: Context, root: JSONObject) {
        val adsPref = AdsVault.getInstance(context)
        adsPref.update {
            // --- Booleans ---
            listOf(
                "IsAdsON", "IsFail_FB", "isLoaderForFB", "IsCustomADS", "IsBack",
                "NativeBanner", "BannerAds", "In_App_Update_Show", "In_App_Update_Force_Show",
                "Iscountry_Counter", "HD_VBC_Show",
                "HD_VBC_Native", "is_preload_ads",
                "is_splash_inter_show", "is_splash_ads", "InterAds", "AppopenAds",
                "NativeAd", "is_rateus", "Perm_Sheet_Show",
                "screen_wise_ad", "screen_wise_default"
            ).forEach { key -> if (root.has(key)) putBoolean(key, root.optBoolean(key, false)) }

            // --- Strings ---
            listOf(
                "IsAdType", "In_App_Update_Link", "CountryList_Counter_NShow",
                "PrivacyPolicy", "TermLink",
                "DirectLink", "MarketLink", "HD_VBC_Native_ID", "HD_VBC_Banner_ID",
                "googleS_Inter", "googleBackInter", "googleInter", "googleAppopen",
                "googleNative", "googleBanner", "googleRewarded", "faceB_InterAds",
                "faceB_NativeAds", "faceB_NativeBannerAds", "faceB_BannerAds",
                "NativeTheme", "HD_VBC_Type", "NativeBgColor", "NativebtnColor",
                "NativetxtColor", "NativebtntxtColor", "Perm_Sheet_Mode",
                // API origin — see RetrofitClient, which falls back to its compiled-in default
                // when this is absent or malformed.
                "api_base_url",
                // Nested JSON objects stored as text (read back via JSONObject).
                "intro_display", "ScreenAds", "launcher_ads"
            ).forEach { key -> if (root.has(key)) putString(key, root.optString(key, "")) }

            // --- Integers ---
            listOf(
                "InterCounter", "InterBackCounter", "MarketInterCounter", "MarketBackCounter",
                "NativeCounter", "MarketNativeCounter", "MidNativeCounter", "BannerCounter",
                "MarketBannerCounter", "MarketAppopenCounter", "AppopenCounter",
                "Perm_Sheet_Interval_Days", "HD_VBC_Hrs", SYNC_HOURS_KEY
            ).forEach { key -> if (root.has(key)) putInt(key, root.optInt(key, 0)) }

            applyNativeTheme(context, root) // DEFAULT theme

            // --- Custom Ads ---
            val customAdsArray = root.optJSONArray("custom_ads")
            if (customAdsArray != null) {
                putString("CUSTOM_ADS", customAdsArray.toString())
                CustomAdsRegistry.clearCache()
            }
        }

        // Facebook Ad initialization parameters
        val fbAppId = root.optString("FbAppId", "")
        val fbClientToken = root.optString("FbClientToken", "")
        if (fbAppId.isNotEmpty() && fbClientToken.isNotEmpty()) {
            initFacebook(context, fbAppId, fbClientToken)
        }

        if (BuildConfig.DEBUG) Log.d(
            TAG,
            "ingested → IsAdsON=${adsPref.getBoolean("IsAdsON")}, IsAdType=${adsPref.getString("IsAdType")}, " +
                "InterAds=${adsPref.getBoolean("InterAds")}, AppopenAds=${adsPref.getBoolean("AppopenAds")}, " +
                "NativeAd=${adsPref.getBoolean("NativeAd")}, BannerAds=${adsPref.getBoolean("BannerPromo")}, " +
                "HD_VBC_Show=${adsPref.getBoolean("HD_VBC_Show")}, HD_VBC_Hrs=${adsPref.getInt("HD_VBC_Hrs")}, " +
                "screen_wise_ad=${adsPref.getBoolean("screen_wise_ad")}, " +
                "customAds=${root.optJSONArray("custom_ads")?.length() ?: 0}, " +
                "fbInit=${fbAppId.isNotEmpty() && fbClientToken.isNotEmpty()}, " +
                "appOpenId=${adsPref.getString("googleAppopen")}"
        )
    }

    /**
     * The audience-specific sub-object of a getData response — `marketing` or `organic`
     * per [isMarketing], falling back to the other audience, then to the flat [response]
     * itself (legacy, un-split config → unchanged behaviour).
     */
    fun audienceRoot(response: JSONObject, isMarketing: Boolean): JSONObject {
        val preferred = if (isMarketing) "marketing" else "organic"
        val fallback = if (isMarketing) "organic" else "marketing"
        response.optJSONObject(preferred)?.let {
            if (BuildConfig.DEBUG) Log.d(TAG, "audienceRoot → using '$preferred' segment")
            return it
        }
        response.optJSONObject(fallback)?.let {
            if (BuildConfig.DEBUG) Log.d(TAG, "audienceRoot → '$preferred' missing, fell back to '$fallback' segment")
            return it
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "audienceRoot → no marketing/organic wrapper, using flat config")
        return response
    }

    /** `NativeLight` / `NativeDark`, per the user's theme choice. */
    fun nativeThemeKey(context: Context): String = when (AppVault.selectedTheme(context)) {
        THEME_DARK -> "NativeDark"
        THEME_LIGHT -> "NativeLight"
        THEME_SYSTEM -> {
            val isSystemDark = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (isSystemDark) "NativeDark" else "NativeLight"
        }

        else -> "NativeLight"
    }

    private fun applyNativeTheme(context: Context, response: JSONObject) {
        val adsPreference = AdsVault.getInstance(context)
        val nativeThemeRoot = response.optJSONObject("NativeTheme") ?: return
        val modeKey = nativeThemeKey(context)

        val marketingObj = nativeThemeRoot.optJSONObject("marketing")
        val defaultObj = nativeThemeRoot.optJSONObject("default")

        // Convert JSONObjects to strings before storing in AdsVault
        adsPreference.putString("NativeTheme_marketing", marketingObj?.toString() ?: "{}")
        adsPreference.putString("NativeTheme_default", defaultObj?.toString() ?: "{}")

        val themeJson = defaultObj?.optJSONObject(modeKey)
        if (themeJson != null) {
            adsPreference.putString("NativebtnColor", themeJson.optString("btnColor"))
            adsPreference.putString("NativebtntxtColor", themeJson.optString("btnText"))
            adsPreference.putString("NativeBgColor", themeJson.optString("bgColor"))
            adsPreference.putString("NativetxtColor", themeJson.optString("textColor"))
        }
    }

    private fun initFacebook(context: Context, fbAppId: String, fbClientToken: String) {
        runCatching {
            FacebookSdk.setApplicationId(fbAppId)
            FacebookSdk.setClientToken(fbClientToken)
            FacebookSdk.sdkInitialize(context.applicationContext)
            FacebookSdk.setAutoInitEnabled(true)
            FacebookSdk.fullyInitialize()
            FacebookSdk.setAutoLogAppEventsEnabled(true)
            FacebookSdk.addLoggingBehavior(LoggingBehavior.APP_EVENTS)
            AppEventsLogger.newLogger(context.applicationContext).applicationId
        }.onFailure { Log.w(TAG, "Facebook SDK init failed", it) }
    }
}
