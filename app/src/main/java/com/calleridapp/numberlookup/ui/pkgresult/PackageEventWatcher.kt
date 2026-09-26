package com.calleridapp.numberlookup.ui.pkgresult

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.calleridapp.admesh.domain.LauncherAdsConfig
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

/**
 * Shows [PackageResultActivity] when an app is installed or removed, the way the reference app does
 * it: from a foreground Activity with a plain `startActivity` — never from a background service and
 * never through a notification.
 *
 * ## Why it works this way
 *
 * An app cannot start an Activity from a true background, and a full-screen-intent notification
 * needs a special grant on Android 14. The reference sidesteps both by only ever showing the screen
 * while one of its own Activities is resumed, and by **queuing** an event that arrives otherwise and
 * draining it the next time a screen resumes. So this:
 *
 *  - registers the package receiver on the **app context at runtime** (the only way to hear
 *    `ACTION_PACKAGE_*` on Android 8+ is a context-registered receiver in a living process — a
 *    manifest receiver is not delivered these implicit broadcasts),
 *  - tracks the currently-resumed Activity through lifecycle callbacks, and
 *  - shows the screen immediately if one is foreground, or queues it for the next resume.
 *
 * Registering is idempotent and cheap; with the screen disabled in Remote Config it hears the events
 * and does nothing but keep the metadata cache warm.
 */
object PackageEventWatcher {

    private const val TAG = "PackageEvent"

    private const val PENDING_FILE = "package_event_pending"
    private const val KEY_INSTALLED = "pending_installed"
    private const val KEY_PACKAGE = "pending_package"
    private const val KEY_TIME = "pending_time"
    private const val KEY_LAST_SHOWN = "last_shown_at"

    /** A queued event older than this is stale and dropped — an install card an hour later is noise. */
    private const val PENDING_TTL_MS = 10 * 60 * 1000L

    private var registered = false

    /** The currently-resumed screen of this app — where the result may be shown from. */
    private var resumed = WeakReference<Activity>(null)

    /** Registers the lifecycle callbacks and the package receiver. Idempotent. */
    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(Callbacks)

        // NOT_EXPORTED: these are protected system broadcasts, so only the system delivers them.
        // The "package" data scheme is what makes the filter match package events.
        ContextCompat.registerReceiver(
            app,
            Receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Cache metadata for every installed app up front, so removing any of them — not only ones
        // installed while we were running — can still show its icon and version.
        thread { PackageMetadataCache.seedAll(app) }
    }

    private object Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            val app = context.applicationContext

            // Remember a newly installed (or updated) app first, so its metadata is on hand if it is
            // removed later. Independent of whether the screen is shown.
            if (intent.action == Intent.ACTION_PACKAGE_ADDED && !pkg.isNullOrBlank()) {
                thread { PackageMetadataCache.remember(app, pkg) }
            }

            // A replace (update) fires REMOVED then ADDED with EXTRA_REPLACING on both — an app
            // updating is not an install. This app's own package is never an event.
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            if (pkg.isNullOrBlank() || pkg == app.packageName) return

            val installed = when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> true
                Intent.ACTION_PACKAGE_REMOVED -> false
                else -> return
            }
            onEvent(app, installed, pkg)
        }
    }

    /** Shows the result now if a screen is foreground, or queues it for the next resume. */
    private fun onEvent(context: Context, installed: Boolean, pkg: String) {
        runCatching {
            if (!gatesPass(context)) return
            val activity = resumed.get()
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                fire(activity, installed, pkg)
            } else {
                enqueue(context, installed, pkg)
                Log.d(TAG, "queued $pkg (installed=$installed) — no foreground screen")
            }
        }.onFailure { Log.w(TAG, "onEvent($pkg) failed", it) }
    }

    /** Records the throttle and launches the result screen from [activity]. */
    private fun fire(activity: Activity, installed: Boolean, pkg: String) {
        settings(activity).edit { putLong(KEY_LAST_SHOWN, System.currentTimeMillis()) }
        activity.startActivity(
            Intent(activity, PackageResultActivity::class.java)
                .putExtra(PackageResultActivity.EXTRA_INSTALLED, installed)
                .putExtra(PackageResultActivity.EXTRA_PACKAGE, pkg)
        )
        Log.d(TAG, "showed $pkg (installed=$installed) from ${activity.javaClass.simpleName}")

        // The app is gone, so its cache entry is spent — drop it once the screen has read it.
        if (!installed) {
            val app = activity.applicationContext
            activity.window.decorView.postDelayed({
                thread { PackageMetadataCache.forget(app, pkg) }
            }, FORGET_DELAY_MS)
        }
    }

    /** The config's `enabled` plus its throttle, decided in one place before anything is shown. */
    private fun gatesPass(context: Context): Boolean {
        val settings = LauncherAdsConfig.packageResultSettings(context)
        if (!settings.enabled) return false
        if (settings.minGapMs > 0L) {
            val last = settings(context).getLong(KEY_LAST_SHOWN, 0L)
            if (System.currentTimeMillis() - last < settings.minGapMs) {
                Log.d(TAG, "throttled (min_gap ${settings.minGapMs}ms)")
                return false
            }
        }
        return true
    }

    private fun enqueue(context: Context, installed: Boolean, pkg: String) {
        settings(context).edit {
            putBoolean(KEY_INSTALLED, installed)
            putString(KEY_PACKAGE, pkg)
            putLong(KEY_TIME, System.currentTimeMillis())
        }
    }

    /** The queued event if one is set and still fresh, clearing the slot either way. */
    private fun takePending(context: Context): Pair<Boolean, String>? {
        val data = settings(context)
        val pkg = data.getString(KEY_PACKAGE, null) ?: return null
        val installed = data.getBoolean(KEY_INSTALLED, true)
        val at = data.getLong(KEY_TIME, 0L)
        data.edit { remove(KEY_INSTALLED); remove(KEY_PACKAGE); remove(KEY_TIME) }
        if (System.currentTimeMillis() - at > PENDING_TTL_MS) return null
        return installed to pkg
    }

    private fun settings(context: Context) =
        context.applicationContext.getSharedPreferences(PENDING_FILE, Context.MODE_PRIVATE)

    private object Callbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            // Never arm off, or drain onto, the result screen itself.
            if (activity is PackageResultActivity) return
            resumed = WeakReference(activity)

            // An install that arrived while backgrounded waits here to be shown on the next screen
            // the user lands on.
            val (installed, pkg) = takePending(activity.applicationContext) ?: return
            runCatching {
                if (gatesPass(activity.applicationContext)) fire(activity, installed, pkg)
            }.onFailure { Log.w(TAG, "drain($pkg) failed", it) }
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

    /** Long enough for the screen to have read the cache before it is pruned. */
    private const val FORGET_DELAY_MS = 5_000L
}
