package com.calleridapp.numberlookup.ui.recent

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.numberlookup.BuildConfig
import java.lang.ref.WeakReference

/**
 * Shows [RecentAdActivity] when the user reopens the app from the **Recents / overview** list.
 *
 * ## Catching the "opened the overview" moment
 *
 * The platform has no lifecycle callback for it. What it does give is the `CLOSE_SYSTEM_DIALOGS`
 * broadcast, whose `reason` extra is `"recentapps"` when the Recents key is pressed and `"homekey"`
 * for Home. So:
 *
 *  1. On `recentapps`, if every gate passes, the screen on top is *armed* (remembered).
 *  2. When that armed screen **pauses** — the app going to the overview — the ad page is launched
 *     into its task with `SINGLE_TOP | REORDER_TO_FRONT`, so tapping the app's card in Recents
 *     lands on the ad rather than where the user left off.
 *  3. On `homekey` the arming is cleared. Home is a deliberate exit, not a return, and must not fire.
 *
 * ## Off unless Remote Config says otherwise
 *
 * `IsAdsON`, `recent_ad.enabled` and the throttle are all checked at arm time, so a disabled config
 * arms nothing and the launch path never runs at all. Registering the callbacks and the receiver
 * costs a dormant install almost nothing.
 *
 * ## Two things to know before turning it on
 *
 * This is an **out-of-context ad**: it appears on a resume the user did not tap for, which Google
 * Play's Disruptive Ads policy treats as a suspension risk. And `CLOSE_SYSTEM_DIALOGS` is a signal
 * the platform has been steadily narrowing, so it may simply stop arriving. Both are why it ships
 * disabled.
 */
object RecentAdWatcher {

    private const val TAG = "RecentAd"

    /** Throttle key, so a burst of Recents presses cannot show the page repeatedly. */
    private const val GATE_KEY = "__recent_ad_last_shown"

    /** `CLOSE_SYSTEM_DIALOGS` can arrive in bursts; ignore anything inside this window. */
    private const val ARM_DEBOUNCE_MS = 700L

    /** The current, shown Activity — the one that would be armed. */
    private var top = WeakReference<Activity>(null)

    /** The screen armed by a Recents press, launched from when it pauses. */
    private var armed = WeakReference<Activity>(null)

    private var lastArmUptime = 0L
    private var registered = false

    /** Registers the lifecycle callbacks and the Recents receiver. Idempotent. */
    fun register(app: Application) {
        if (registered) return
        registered = true

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                top = WeakReference(activity)
                topResumed = true
            }

            override fun onActivityPaused(activity: Activity) {
                if (top.get() === activity) topResumed = false
                // The armed screen pausing is the app going to the overview — the moment to launch.
                if (armed.get() === activity) {
                    armed.clear()
                    if (armedForPlayStore) {
                        armedForPlayStore = false
                        launchPlayStoreFrom(activity)
                    } else {
                        launchAdPage(activity)
                    }
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (top.get() === activity) top.clear()
                if (armed.get() === activity) armed.clear()
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
                when (intent.getStringExtra("reason")) {
                    "recentapps", "recent_apps" -> {
                        val app = context.applicationContext
                        if (!armPlayStoreIfFirstSession(app)) armIfAllowed(app)
                    }
                    // Home is an exit, not a return. Never fire on the way out.
                    "homekey" -> { armed.clear(); armedForPlayStore = false }
                }
            }
        }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** Arms the screen on top, if every gate passes. Everything is checked here, not at launch. */
    private fun armIfAllowed(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - lastArmUptime < ARM_DEBOUNCE_MS) return
        lastArmUptime = now

        val activity = top.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        // The ad page must never arm the next one, and the event screens close themselves on
        // Recents (SystemDialogHelper) — an ad page launched from them would outlive them.
        if (activity is RecentAdActivity) return
        if (activity is com.calleridapp.numberlookup.ui.pkgresult.PackageResultActivity ||
            activity is com.calleridapp.numberlookup.ui.charging.ChargingStatusActivity
        ) return

        if (!AdsVault.getInstance(context).getBoolean("IsAdsON")) return

        val settings = LauncherAdsConfig.recentAdSettings(context)
        if (!settings.enabled) return

        if (settings.minGapMs > 0L) {
            val vault = AdsVault.getInstance(context)
            val last = vault.getLong(GATE_KEY, 0L)
            val elapsed = System.currentTimeMillis() - last
            if (last > 0L && elapsed < settings.minGapMs) {
                log("throttled — ${elapsed}ms since the last one, need ${settings.minGapMs}ms")
                return
            }
        }

        armed = WeakReference(activity)
        log("armed ${activity::class.java.simpleName}")
    }

    /** Whether [top] is actually resumed — a paused screen will never deliver the pause we wait on. */
    private var topResumed = false

    private var armedForPlayStore = false

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private const val PLAY_STORE_FIRED_KEY = "__recent_playstore_fired"

    /**
     * `recent_playstore` (QRScanner): a Recents press within the first `recent_playstore_window_sec`
     * (default 180) after install opens the Play Store home instead of the overview — once per
     * install. Returns true when it armed (or fired), so the recent-page ad is not armed on top.
     *
     * The window counts from the install time Android records, so an existing user updating the
     * app never gets it. Launched from the screen's `onPause` — the last moment its window still
     * counts as visible for the background-activity-launch check — or at once if the screen has
     * already paused, since no second pause is coming.
     */
    private fun armPlayStoreIfFirstSession(context: Context): Boolean {
        return runCatching {
        val vault = AdsVault.getInstance(context)
        if (!vault.getBoolean("recent_playstore")) return false
        if (vault.getBoolean(PLAY_STORE_FIRED_KEY)) return false
        val windowMs = vault.getInt("recent_playstore_window_sec", 180).coerceAtLeast(0) * 1000L
        val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        if (System.currentTimeMillis() - installedAt > windowMs) return false
        val activity = top.get() ?: return false
        if (activity.isFinishing || activity.isDestroyed || armedForPlayStore) return false

        if (!topResumed) {
            log("play store: top already paused — starting now")
            launchPlayStoreFrom(activity)
            return true
        }
        armedForPlayStore = true
        armed = WeakReference(activity)
        log("play store: armed on ${activity::class.java.simpleName}")
        // A Recents press that never pauses this screen must not leave it armed.
        main.postDelayed({
            if (armedForPlayStore) {
                armedForPlayStore = false
                armed.clear()
                log("play store: disarmed — no pause within 2s (one-shot kept)")
            }
        }, 2_000L)
        true
        }.getOrElse { Log.w(TAG, "recent_playstore failed", it); false }
    }

    /** The Play Store home. CLEAR_TASK so it opens at its home tab, not the page it was left on. */
    private fun launchPlayStoreFrom(activity: Activity) {
        runCatching {
            val intent = activity.packageManager.getLaunchIntentForPackage("com.android.vending")
                ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps"))
                    .setPackage("com.android.vending")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.startActivity(intent)
            // Spent only once a start did not throw.
            AdsVault.getInstance(activity).putBoolean(PLAY_STORE_FIRED_KEY, true)
            log("play store: fired (one-shot spent)")
        }.onFailure { Log.w(TAG, "launchPlayStoreFrom failed", it) }
    }

    private fun launchAdPage(from: Activity) {
        runCatching {
            AdsVault.getInstance(from).putLong(GATE_KEY, System.currentTimeMillis())
            from.startActivity(
                Intent(from, RecentAdActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            )
            log("launched the recent page")
        }.onFailure { Log.e(TAG, "could not launch the recent page", it) }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
