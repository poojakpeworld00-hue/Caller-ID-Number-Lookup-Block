package com.calleridapp.numberlookup.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * Process-wide crash handling: records the crash with context, then puts the user back in the app
 * instead of leaving them on the system's "app keeps stopping" dialog.
 *
 * Crashlytics installs its own `UncaughtExceptionHandler` when Firebase initialises, and that is
 * what actually writes the report. This **wraps** it rather than replacing it: the captured
 * handler is always invoked at the end, so failing to chain would lose every crash report. Which
 * is also why [install] has to run *after* Firebase is initialised.
 *
 * There are two handlers here, and the order they are installed in is the whole design:
 *
 *  1. [installQuietTerminator] from `attachBaseContext`, *before* Firebase's init provider runs.
 *     Crashlytics captures whatever is installed when it starts and delegates to it when it is
 *     done writing — so this is what it delegates to, instead of the system's killer.
 *  2. [install] from `onCreate`, *after* Firebase, wrapping Crashlytics.
 *
 * A crash then runs: this wrapper (relaunch) → Crashlytics (writes the fatal) → the terminator
 * (ends the process quietly). The report survives; the system crash dialog never appears.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"

    /**
     * Set false to let a crash kill the process with the system dialog instead of relaunching.
     *
     * Relaunching is the friendlier default here because this app can *be* the device home
     * screen, and a home screen that vanishes leaves the user with nowhere to go. It does not
     * suppress the system crash dialog — chaining to Crashlytics is what keeps the crash a
     * *fatal* in the console, and that same chain is what reports it to the system. The user
     * dismisses the dialog and finds the app running again behind it.
     */
    private const val RESTART_AFTER_CRASH = true

    /**
     * Two crashes closer together than this count as a loop, and the second one is allowed to
     * kill the app for good rather than restarting into the same crash forever.
     *
     * Persisted, not in-memory: a crash loop is a *chain of processes*, each crashing and
     * starting the next, so an in-memory timestamp would never see more than the first one.
     */
    private const val LOOP_WINDOW_MS = 10_000L

    private const val PREFS_NAME = "crash_guard"
    private const val KEY_LAST_CRASH_AT = "last_crash_at"

    /** Non-zero so a crash exit is distinguishable from a clean one in a bug report. */
    private const val CRASH_EXIT_CODE = 10

    /**
     * @param isForeground whether the app is actually on screen. A crash on a background thread
     * while the user is in another app must not yank them into this one.
     *
     * Deliberately not "is there a non-null current Activity": the Application tracks that for
     * the app-open ad and clears it on *destroy*, not on pause, so it stays set the whole time
     * the app sits in the background with a live Activity. Process lifecycle is the honest
     * signal here.
     */
    /**
     * Installs the handler Crashlytics will delegate to. Must run from `attachBaseContext`: the
     * ordering is the point, and content providers — Firebase's included — are created after it.
     *
     * Ending the process here rather than letting the system's `KillApplicationHandler` do it is
     * what makes the restart possible at all. That handler reports the crash to ActivityManager,
     * which shows the "app keeps stopping" dialog and holds the app in an error state — and while
     * that state stands the system will not spawn the app's process, so the relaunch
     * [CrashRestartActivity] asks for is accepted, never started, and force-finished five seconds
     * later. Measured; see that class.
     *
     * The cost is that Play Console vitals no longer sees these crashes, because the system never
     * hears about them. Crashlytics does — it has already written the report by the time it
     * delegates here — so the crash is still reported where the team actually reads it.
     */
    fun installQuietTerminator() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            Process.killProcess(Process.myPid())
            exitProcess(CRASH_EXIT_CODE)
        }
    }

    fun install(app: Application, isForeground: () -> Boolean) {
        val chained = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Every step is wrapped: this runs while the process is already failing, and a second
            // throw from inside the handler would lose the original report.
            runCatching {
                GuardRail.error(TAG, "Uncaught exception on thread '${thread.name}'", error)
            }

            val restarting = runCatching {
                RESTART_AFTER_CRASH && isForeground() && !isCrashLoop(app)
            }.getOrDefault(false)

            if (restarting) {
                runCatching { relaunch(app) }
            }
            runCatching { Log.w(TAG, "crash handled on '${thread.name}', restarting=$restarting") }

            // Always hand over: the captured handler is Crashlytics', the report is written from
            // there, and it ends the process — via the terminator above — which we want either way.
            if (chained != null) {
                chained.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(CRASH_EXIT_CODE)
            }
        }

        GuardRail.log(TAG, "installed (chained=${chained != null})")
    }

    /**
     * True when the previous crash was recent enough that restarting would just loop.
     *
     * Records this crash's time as a side effect, so the *next* process sees it. Uses wall clock
     * because it has to survive the process; the window is short enough that only a clock change
     * landing inside it could misjudge, and the cost of that is one extra restart.
     */
    private fun isCrashLoop(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previous = prefs.getLong(KEY_LAST_CRASH_AT, 0L)

        // commit(), not apply(): the process is about to be killed and an async write would lose
        // the timestamp the next process needs to detect the loop.
        prefs.edit().putLong(KEY_LAST_CRASH_AT, now).commit()

        return previous != 0L && now - previous in 0..LOOP_WINDOW_MS
    }

    /**
     * Hands the restart to [CrashRestartActivity], which lives in its own process.
     *
     * Doing it here instead — `startActivity(LaunchActivity)` straight from the handler — is
     * accepted by the system and still never lands, because the chained handler below keeps this
     * process busy until it is killed and the Activity can never be created in it. The helper
     * has no such problem and waits for this process to die before relaunching for real; the
     * measured failure is written up there.
     *
     * Started while this is still the foreground process, for the same reason as before: an
     * Activity start from a dead process — or from an AlarmManager PendingIntent afterwards —
     * counts as a background activity start and is blocked on Android 10+.
     */
    private fun relaunch(app: Application) {
        app.startActivity(
            Intent(app, CrashRestartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
