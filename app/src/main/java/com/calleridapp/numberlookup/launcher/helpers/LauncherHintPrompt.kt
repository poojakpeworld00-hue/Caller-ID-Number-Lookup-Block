package com.calleridapp.numberlookup.launcher.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.calleridapp.admesh.presentation.OverlayGuideActivity
import com.calleridapp.numberlookup.util.GuardRail

/**
 * Times the "Default home app" hint against the system list it annotates.
 *
 * The hint has to arrive *after* Settings is actually in front — started in the same breath
 * as the list it either races it or is buried by it — and it has to arrive while this app is
 * still inside the background-start grace it keeps after being in the foreground. A short
 * post-delay sits in both windows at once.
 *
 * The card itself is [OverlayGuideActivity] in its HOME mode: the same sheet the overlay
 * permission uses, so both asks read identically to the user.
 */
object LauncherHintPrompt {

    /** Long enough for the list to take the screen, far short of the grace running out. */
    private const val SHOW_DELAY_MS = 600L

    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /** Call immediately after starting the home-app list. */
    fun showAfterSettings(context: Context) {
        cancelPending()
        val app = context.applicationContext
        val task = Runnable {
            pending = null
            runCatching {
                app.startActivity(OverlayGuideActivity.intent(app, OverlayGuideActivity.Mode.HOME))
            }.onFailure { GuardRail.error("DefaultHomeHint", "hint could not be started", it) }
        }
        pending = task
        main.postDelayed(task, SHOW_DELAY_MS)
    }

    /**
     * Call when the caller is back in the foreground: the list is gone, so the card has
     * nothing left to point at. Also drops a start that has not fired yet, for the user who
     * comes straight back.
     */
    fun dismiss() {
        cancelPending()
        OverlayGuideActivity.dismiss()
    }

    private fun cancelPending() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
    }
}
