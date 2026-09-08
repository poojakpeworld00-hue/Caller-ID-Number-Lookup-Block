package com.calleridapp.numberlookup.util

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.calleridapp.numberlookup.launcher.extensions.isDefaultLauncher
import com.calleridapp.numberlookup.ui.splash.LaunchActivity

/**
 * Brings the app back after a crash. Runs in its own `:restart` process, started by [CrashGuard]
 * from the crashing one.
 *
 * The separate process is the whole point. Starting [LaunchActivity] directly from the crash
 * handler looks like it works — the system accepts the start (`BAL_ALLOW_VISIBLE_WINDOW`, because
 * the crashing process is still the foreground one) — but it never lands: the chained Crashlytics
 * handler owns the crashing thread for as long as it takes to write the report, so the dying
 * process cannot create the Activity the system just scheduled into it. Five seconds later the
 * input dispatcher times out and the system force-finishes it, leaving the user on their home
 * screen. Measured on Android 14; the whole sequence is in logcat as
 * `START ... result code=0` → `Input dispatching timed out` → `Force finishing activity`.
 *
 * This Activity is in a process of its own, so nothing the crashing process does can stall it. It
 * waits for that process to actually die before starting the real entry point — otherwise the
 * relaunch is scheduled into the same stalled process and dies exactly the same way.
 *
 * What it cannot avoid: the system crash dialog still appears, because the chained handler reports
 * the crash to the system (which is also what writes the Crashlytics report — see [CrashGuard]).
 * If the user answers that dialog with "Close app" the whole package is force-stopped, this
 * process included, and the relaunch is lost. Nothing short of dropping the fatal report avoids
 * that.
 */
class CrashRestartActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var giveUpAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No content view: the theme's window background stands in for the split second this is
        // on screen, so the restart reads as the app coming back rather than as a blank flash.
        giveUpAt = SystemClock.elapsedRealtime() + MAX_WAIT_MS
        awaitCrashedProcess()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Polls until the crashed process is gone, then relaunches.
     *
     * The deadline matters: if the process somehow never dies we still want the user back in the
     * app, and a relaunch that gets force-finished is no worse than no relaunch at all.
     */
    private fun awaitCrashedProcess() {
        val expired = SystemClock.elapsedRealtime() >= giveUpAt
        if (expired || !isMainProcessAlive()) {
            GuardRail.log(TAG, "relaunching (waitedOut=$expired)")
            relaunch()
            finish()
            return
        }
        handler.postDelayed(::awaitCrashedProcess, POLL_INTERVAL_MS)
    }

    /**
     * `getRunningAppProcesses` is restricted to the caller's own app, which is exactly the
     * question being asked here: is the main process — the one that crashed — still up.
     */
    private fun isMainProcessAlive(): Boolean = runCatching {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses.orEmpty().any { it.processName == packageName }
    }.getOrDefault(false)

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
     *  - [LaunchActivity] also carries the package's default affinity, so `CLEAR_TASK` on it would
     *    clear the *home* task and leave the splash rooted in it.
     *
     * A generic HOME intent normally risks handing the user to whichever launcher is default —
     * the reason [com.calleridapp.numberlookup.ui.ShellActivity] deliberately avoids it — but in
     * this branch we are that launcher, so it can only land here.
     */
    private fun relaunch() {
        runCatching {
            if (runCatching { isDefaultLauncher() }.getOrDefault(false)) {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return@runCatching
            }

            startActivity(
                Intent(this, LaunchActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            )
        }.onFailure { GuardRail.error(TAG, "relaunch failed", it) }
    }

    companion object {

        private const val TAG = "CrashRestart"

        /** Must match `android:process` on this Activity in the manifest. */
        private const val PROCESS_SUFFIX = ":restart"

        /** Long enough to cover Crashlytics writing its report (~4s) plus the process teardown. */
        private const val MAX_WAIT_MS = 8_000L

        private const val POLL_INTERVAL_MS = 150L

        /**
         * True in the helper process. `Application.onCreate` runs in *every* process, and none of
         * the app's real initialisation — ads, push, Firebase, [CrashGuard] itself — belongs in
         * the one whose only job is to start an Activity and finish.
         */
        fun isRestartProcess(context: Context): Boolean =
            currentProcessName(context).endsWith(PROCESS_SUFFIX)

        private fun currentProcessName(context: Context): String = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return@runCatching Application.getProcessName()
            }
            val pid = Process.myPid()
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses.orEmpty()
                .firstOrNull { it.pid == pid }
                ?.processName
                .orEmpty()
        }.getOrDefault("")
    }
}
