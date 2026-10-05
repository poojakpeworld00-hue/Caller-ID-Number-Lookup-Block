package com.calleridapp.numberlookup.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.onboard.LauncherFlow
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * The one place a Firebase Analytics event is logged from.
 *
 * - Names are lowercase `snake_case`, `{screen}` / `{moment}` first, at most 34 characters (so the
 *   `first_` prefix still fits in Firebase's 40). A name, once shipped, is never renamed.
 * - **First session:** until the first run is over ([endFirstSession], called where onboarding
 *   completes), every event name carries a `first_` prefix, applied here and never at a call site.
 *   An install that already finished onboarding before this logger existed never gets it.
 * - Sent from debug builds as well (and echoed to logcat under `Analytics`), so DebugView works:
 *   `adb shell setprop debug.firebase.analytics.app com.calleridapp.numberlookup`.
 *
 * The events are a closed list — see `docs/analytics-events.md`. Adding one means adding it there first.
 */
object Analytics {

    private const val TAG = "Analytics"
    private const val KEY_FIRST_DONE = "analytics_first_session_done"
    private const val MAX_NAME = 40

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    @Volatile
    private var isFirstSession = false

    /** Call once from `Application.onCreate`. */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences("analytics_prefs", Context.MODE_PRIVATE)
        prefs = p
        isFirstSession = !p.getBoolean(KEY_FIRST_DONE, false) &&
            // An install that was already past onboarding when this logger shipped is not "first".
            !runCatching { LauncherFlow.wasOnboardingCompleted(app) }.getOrDefault(false)
        if (!isFirstSession) p.edit().putBoolean(KEY_FIRST_DONE, true).apply()
    }

    /** Ends the `first_` prefix. Call when the first run actually completes (see [LauncherFlow.markOnboardingCompleted]). */
    fun endFirstSession() {
        if (!isFirstSession) return
        prefs?.edit()?.putBoolean(KEY_FIRST_DONE, true)?.apply()
        isFirstSession = false
    }

    /** Logs [event] with optional string [params]. Never throws, never blocks. */
    fun log(event: String, vararg params: Pair<String, String>) {
        val ctx = appContext ?: return
        val name = (if (isFirstSession) "first_$event" else event).take(MAX_NAME)
        runCatching {
            val bundle = if (params.isEmpty()) null else Bundle().apply {
                params.forEach { (k, v) -> putString(k.take(40), v.take(100)) }
            }
            FirebaseAnalytics.getInstance(ctx).logEvent(name, bundle)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "$name ${params.joinToString { "${it.first}=${it.second}" }}")
    }

    /** `{token}_open` — a screen became visible. */
    fun screen(token: String) = log("${token}_open")

    /**
     * A full-screen ad run by the launcher's ad chains: `ad_{type}_{phase}` with the placement in
     * `screen` — `show` (about to show), `close` (dismissed) or `failed` (could not show).
     */
    fun adEvent(type: String, phase: String, place: String) =
        log("ad_${type}_$phase", "screen" to place)
}
