package com.calleridapp.numberlookup.launcher

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.calleridapp.admesh.domain.LauncherPlacementAds
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal
import com.calleridapp.numberlookup.onboard.LauncherFlow
import com.calleridapp.numberlookup.ui.ShellActivity
import com.calleridapp.numberlookup.data.VaultRegistry
import io.launcher.home.activities.LauncherPanel
import io.launcher.home.api.LauncherRegistry
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * An ad after the user unlocks the phone — **off by default**.
 *
 * Config: `launcher_config.unlock_ads` (the block the launcher module already parses):
 * ```
 * "unlock_ads": {
 *   "enabled": false,           // must be explicitly true
 *   "start_after_hours": 24,    // hours after install before the first one
 *   "gap_minutes": 30,          // minimum time between two
 *   "max_per_day": 5,           // daily cap (local calendar day)
 *   "format": "inter",          // "inter" (with inter_fallback) or "full_native"
 *   "countries": { "IN": { "enabled": false } }   // per-country overrides of any field
 * }
 * ```
 * Units / link-first come from the `unlock_*` placement keys (`unlock_googleInter`,
 * `unlock_DirectLink` + `unlock_link_first_then`, `unlock_ads_on`, …), falling back to the global ones.
 *
 * It is shown only on our own home — the launcher home or the app home — which is what is in front
 * after unlock when the app is the default home app. Android does not let an app start a screen
 * from the background, so it never appears over another app; an unlock that lands elsewhere waits
 * [PENDING_TTL_MS] for one of our homes and is then dropped.
 *
 * Google Play's disruptive-ads policy names ads on unlock explicitly; this is why it fails closed at
 * every gate.
 */
object UnlockAdWatcher {

    private const val TAG = "UnlockAd"
    private const val PLACEMENT = "unlock"

    /** An unlock older than this is no longer "just unlocked". */
    private const val PENDING_TTL_MS = 10_000L

    /** Lets the home settle (and any system surface finish) before the ad goes over it. */
    private const val SHOW_DELAY_MS = 400L

    private const val LAST_SHOWN_KEY = "__unlock_ad_last_shown"
    private const val DAY_KEY = "__unlock_ad_day"
    private const val DAY_COUNT_KEY = "__unlock_ad_day_count"

    private var registered = false
    private var resumed = WeakReference<Activity>(null)

    /** Uptime of the unlock waiting for one of our homes, or 0. */
    private var pendingAt = 0L

    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(Callbacks)
        // USER_PRESENT is only delivered to receivers registered at runtime on Android 8+.
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_USER_PRESENT) onUnlock(context.applicationContext)
                }
            },
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun onUnlock(context: Context) {
        if (settings(context) == null) return
        val home = resumed.get()
        if (home != null && isHome(home) && !home.isFinishing && !home.isDestroyed) {
            show(home)
        } else {
            pendingAt = SystemClock.uptimeMillis()
            Log.d(TAG, "unlocked — waiting for a home screen")
        }
    }

    private fun isHome(activity: Activity) = activity is LauncherPanel || activity is ShellActivity

    private fun show(activity: Activity) {
        pendingAt = 0L
        activity.window.decorView.postDelayed({
            if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) return@postDelayed
            if (InterstitialNormal.isInterShow) return@postDelayed
            // Re-checked at show time: the throttle and cap must count what actually showed.
            val cfg = settings(activity) ?: return@postDelayed
            record(activity)
            Log.d(TAG, "showing (${cfg.optString("format", "inter")})")
            if (cfg.optString("format", "inter").trim().lowercase() in setOf("full_native", "fullnative", "full")) {
                LauncherPlacementAds.showFullNative(activity, PLACEMENT) {}
            } else {
                LauncherPlacementAds.showInterstitial(activity, PLACEMENT) {}
            }
        }, SHOW_DELAY_MS)
    }

    /**
     * The resolved `unlock_ads` block when every gate passes right now, else null: ads on, the
     * placement on, onboarding done, `enabled` (after the country override), past
     * `start_after_hours`, outside `gap_minutes`, under `max_per_day`.
     */
    private fun settings(context: Context): JSONObject? {
        return runCatching {
        val vault = AdsVault.getInstance(context)
        if (!vault.getBoolean("IsAdsON")) return null
        if (!LauncherPlacementAds.placementEnabled(context, PLACEMENT)) return null
        if (!LauncherFlow.wasOnboardingCompleted(context)) return null

        val raw = LauncherRegistry.setup().unlockAdsJson.takeIf { it.isNotBlank() } ?: return null
        val cfg = JSONObject(raw)
        country(context)?.let { cc -> cfg.optJSONObject("countries")?.optJSONObject(cc) }
            ?.let { override -> override.keys().forEach { cfg.put(it, override.get(it)) } }
        if (!cfg.optBoolean("enabled", false)) return null

        val now = System.currentTimeMillis()
        val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        if (now - installedAt < cfg.optLong("start_after_hours", 24L) * 3_600_000L) return null

        val gapMs = cfg.optLong("gap_minutes", 30L).coerceAtLeast(0L) * 60_000L
        if (now - vault.getLong(LAST_SHOWN_KEY, 0L) < gapMs) return null

        val max = cfg.optInt("max_per_day", 5)
        if (max <= 0 || shownToday(vault) >= max) return null
        cfg
        }.getOrElse { Log.w(TAG, "settings failed", it); null }
    }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun shownToday(vault: AdsVault): Int =
        if (vault.getString(DAY_KEY) == today()) vault.getInt(DAY_COUNT_KEY, 0) else 0

    private fun record(context: Context) {
        val vault = AdsVault.getInstance(context)
        val count = shownToday(vault) + 1
        vault.putString(DAY_KEY, today())
        vault.putInt(DAY_COUNT_KEY, count)
        vault.putLong(LAST_SHOWN_KEY, System.currentTimeMillis())
    }

    /** The app's known country (the user's home country, else the IP one), else the SIM's. */
    private fun country(context: Context): String? {
        val data = VaultRegistry(context)
        return listOf(
            data.homeCountryIso,
            data.geoCountryIso,
            (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.simCountryIso,
        ).firstOrNull { !it.isNullOrBlank() && it.length == 2 }?.uppercase()
    }

    private object Callbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
            if (pendingAt == 0L || !isHome(activity)) return
            val fresh = SystemClock.uptimeMillis() - pendingAt <= PENDING_TTL_MS
            pendingAt = 0L
            if (fresh && settings(activity) != null) show(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed.get() === activity) resumed.clear()
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (resumed.get() === activity) resumed.clear()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
