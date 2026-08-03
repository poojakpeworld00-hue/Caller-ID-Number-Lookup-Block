package com.calleridapp.numberlookup.ui.terms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

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
            Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
}
