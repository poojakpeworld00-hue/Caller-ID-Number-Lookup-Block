package com.calleridapp.numberlookup.ui.charging

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
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.LauncherAdsConfig
import java.lang.ref.WeakReference

/**
 * Shows [ChargingStatusActivity] when the charger is plugged in or pulled out (QRScanner's
 * `system_ads.charge` / `discharge`), the way the reference app does it: from one of this app's own
 * screens with a plain `startActivity` — no foreground service, no notification. An event that
 * arrives with none of our screens in front is queued and shown on the next resume, the same as
 * [com.callerid.number.lookup.home.screen.pkgresult.PackageEventWatcher] does for installs.
 *
 * Since the launcher home is one of our screens, a user who has made the app their home screen
 * sees it whenever they are on the home screen or come back to it.
 *
 * Every gate is here — `IsAdsON`, the trigger's `enabled`, and its `min_gap_sec` throttle — and all
 * of them fail closed: an absent `system_ads` block shows nothing.
 */
object ChargeEventWatcher {

    private const val TAG = "ChargeEvent"

    private const val PREFS = "charge_event_watcher"
    private const val KEY_PENDING = "pending_trigger"
    private const val KEY_PENDING_AT = "pending_at"

    /** A queued event older than this is stale and dropped — a "charging" screen an hour later is noise. */
    private const val PENDING_TTL_MS = 10 * 60 * 1000L

    private var registered = false

    private var resumed = WeakReference<Activity>(null)

    /** Registers the lifecycle callbacks and the power receiver. Idempotent. */
    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(Callbacks)
        // NOT_EXPORTED: protected system broadcasts, only the system delivers them. The only way to
        // hear ACTION_POWER_* on Android 8+ is a context-registered receiver in a living process.
        ContextCompat.registerReceiver(
            app,
            Receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private object Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val trigger = when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> ChargingStatusActivity.TRIGGER_CHARGE
                Intent.ACTION_POWER_DISCONNECTED -> ChargingStatusActivity.TRIGGER_DISCHARGE
                else -> return
            }
            onEvent(context.applicationContext, trigger)
        }
    }

    private fun onEvent(context: Context, trigger: String) {
        runCatching {
            if (!gatesPass(context, trigger)) return
            val activity = resumed.get()
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                fire(activity, trigger)
            } else {
                settings(context).edit {
                    putString(KEY_PENDING, trigger)
                    putLong(KEY_PENDING_AT, System.currentTimeMillis())
                }
                Log.d(TAG, "'$trigger' queued — no foreground screen")
            }
        }.onFailure { Log.w(TAG, "onEvent('$trigger') failed", it) }
    }

    private fun fire(activity: Activity, trigger: String) {
        settings(activity).edit { putLong(lastKey(trigger), System.currentTimeMillis()) }
        activity.startActivity(
            Intent(activity, ChargingStatusActivity::class.java)
                .putExtra(ChargingStatusActivity.EXTRA_TRIGGER, trigger)
        )
        Log.d(TAG, "showed '$trigger' from ${activity.javaClass.simpleName}")
    }

    private fun gatesPass(context: Context, trigger: String): Boolean {
        if (!AdsVault.getInstance(context).getBoolean("IsAdsON")) return false
        val settings = LauncherAdsConfig.systemAdSettings(context, trigger)
        if (!settings.enabled) return false
        if (settings.minGapMs > 0L) {
            val last = settings(context).getLong(lastKey(trigger), 0L)
            if (System.currentTimeMillis() - last < settings.minGapMs) {
                Log.d(TAG, "'$trigger' throttled (min_gap ${settings.minGapMs}ms)")
                return false
            }
        }
        return true
    }

    /** The queued trigger if one is set and still fresh, clearing the slot either way. */
    private fun takePending(context: Context): String? {
        val data = settings(context)
        val trigger = data.getString(KEY_PENDING, null) ?: return null
        val at = data.getLong(KEY_PENDING_AT, 0L)
        data.edit { remove(KEY_PENDING); remove(KEY_PENDING_AT) }
        return trigger.takeIf { System.currentTimeMillis() - at <= PENDING_TTL_MS }
    }

    private fun lastKey(trigger: String) = "last_$trigger"

    private fun settings(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private object Callbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            // Never arm off, or drain onto, the charging screen itself.
            if (activity is ChargingStatusActivity) return
            resumed = WeakReference(activity)
            val trigger = takePending(activity.applicationContext) ?: return
            runCatching {
                if (gatesPass(activity.applicationContext, trigger)) fire(activity, trigger)
            }.onFailure { Log.w(TAG, "drain('$trigger') failed", it) }
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
