package com.calleridapp.numberlookup.ui.terms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.calleridapp.admesh.presentation.OverlayGuideActivity
import com.calleridapp.numberlookup.util.GuardRail

/**
 * Helpers for the "display over other apps" (overlay) permission used by the
 * caller-ID overlay. Keeps the permission check and the Settings intent in one
 * place so the Terms flow and the hint screen agree. The grant polling itself
 * now lives in [FloatWatchService].
 */
object FloatKit {

    /** True when we already have the overlay permission (or don't need it). */
    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * Intent to the system "display over other apps" screen for this app.
     *
     * `NO_HISTORY` + `EXCLUDE_FROM_RECENTS` (mirroring the FSI "Manage" page) so
     * that once we pull the app back to the front on grant (the "auto back"), the
     * system Settings page disposes of itself and never lingers in the background
     * task list / recents.
     *
     * Intentionally **no** `FLAG_ACTIVITY_NEW_TASK` / `CLEAR_TASK`: the page is
     * launched *for-result*, so it must stay in the caller's task. With NEW_TASK it
     * would land in a separate task where NO_HISTORY doesn't fire on the in-task
     * REORDER auto-back, and the Settings page would linger as a hidden background
     * task and resurface when the user backs out of the app. (The previous
     * CLEAR_TASK|CLEAR_TOP flags were inert here — CLEAR_TASK needs NEW_TASK — and
     * left the page without NO_HISTORY, so it never self-disposed.)
     */
    fun buildOverlayIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).addFlags(
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )

    /**
     * Stacks the [OverlayGuideActivity] coach-mark on top of the system page that
     * [buildOverlayIntent] just opened, so the user sees which row to find and which
     * switch to flip while they are actually looking at the list.
     *
     * Call it **immediately after** launching the Settings intent, from the same task:
     * both starts are queued in order, so the guide lands on top of the page rather
     * than under it. Its own window is translucent, so the list stays readable behind.
     *
     * Note this is why [buildOverlayIntent] no longer carries `FLAG_ACTIVITY_NO_HISTORY` —
     * that flag finishes the Settings page the moment anything else comes on top of it,
     * which is exactly what this does. The page is instead disposed of by the caller's
     * grant poll bringing the host back to the front.
     *
     * Best effort: a guide that fails to start must never take the Settings page with it.
     */
    fun showGuide(context: Context) {
        runCatching {
            context.startActivity(Intent(context, OverlayGuideActivity::class.java))
        }.onFailure { GuardRail.error("FloatKit", "overlay guide failed to start", it) }
    }
}
