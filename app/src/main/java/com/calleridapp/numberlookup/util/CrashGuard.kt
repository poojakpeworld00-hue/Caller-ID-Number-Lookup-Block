package com.calleridapp.numberlookup.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.calleridapp.numberlookup.launcher.extensions.isDefaultLauncher
import com.calleridapp.numberlookup.ui.splash.LaunchActivity

/**
 * Process-wide crash handling: records the crash with context, then puts the user back in the app
 * instead of leaving them on the system's "app keeps stopping" dialog.
 *
 * Crashlytics installs its own `UncaughtExceptionHandler` when Firebase initialises, and that is
 * what actually writes the report. This **wraps** it rather than replacing it: the captured
 * handler is always invoked at the end, so failing to chain would lose every crash report. Which
 * is also why [install] has to run *after* Firebase is initialised.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"

    /**
     * Set false to let a crash kill the process with the system dialog instead of relaunching.
     *
     * Relaunching is the friendlier default here because this app can *be* the device home
     * screen, and a home screen that vanishes leaves the user with nowhere to go. The cost is
     * that crashes get less visible in the wild, which [LOOP_WINDOW_MS] bounds.
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

    /**
     * @param isForeground whether the app is actually on screen. A crash on a background thread
     * while the user is in another app must not yank them into this one.
     *
     * Deliberately not "is there a non-null current Activity": the Application tracks that for
     * the app-open ad and clears it on *destroy*, not on pause, so it stays set the whole time
     * the app sits in the background with a live Activity. Process lifecycle is the honest
     * signal here.
     */
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
                // Started from here, while this is still the foreground process: an Activity start
                // from a dead process — or from an AlarmManager PendingIntent afterwards — counts
                // as a background activity start and is blocked on Android 10+.
                runCatching { relaunch(app) }
            }
            runCatching { Log.w(TAG, "crash handled on '${thread.name}', restarting=$restarting") }

            // Always hand over: the captured handler is Crashlytics', the report is written from
            // there, and it ends the process — which we want either way.
            if (chained != null) {
                chained.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
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
     * Restarts the app at the right entry point for what it currently is.
     *
     * When this app holds the HOME role it *is* the device home screen, so it comes back through
     * the home screen rather than the splash — and it gets there by firing the real HOME intent
     * rather than starting the Activity directly. Two reasons:
     *
     *  - The system re-establishes the home task itself, with the right activity type. Starting a
     *    default-affinity Activity into that task by hand is the trap that stopped the post-call
     *    screen from ever appearing (see `taskAffinity=""` in the manifest).
     *  - `LaunchActivity` also carries the package's default affinity, so `CLEAR_TASK` on it would
     *    clear the *home* task and leave the splash rooted in it.
     *
     * A generic HOME intent normally risks handing the user to whichever launcher is default —
     * the reason [com.calleridapp.numberlookup.ui.ShellActivity] deliberately avoids it — but in
     * this branch we are that launcher, so it can only land here.
     */
    private fun relaunch(app: Application) {
        if (runCatching { app.isDefaultLauncher() }.getOrDefault(false)) {
            app.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        app.startActivity(
            Intent(app, LaunchActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
    }
}
