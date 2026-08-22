package com.calleridapp.numberlookup.ui.terms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.OverlayGuideActivity
import com.calleridapp.numberlookup.launcher.extensions.isDefaultLauncher

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
     * Master switch for asking the user for "display over other apps".
     *
     * **Off.** The app does not request the overlay permission any more: the
     * post-call screen reaches the user through the default-role background-start
     * exemption (see CallStateReceiver.handlePostCall) or its full-screen-intent
     * notification, and the ringing-time card still comes up over the keyguard as
     * an activity — so the prompt was buying too little to be worth asking for.
     *
     * Flip this to `true` to bring every overlay prompt back; the per-user gates in
     * [isOfferable] below are still wired and take over from there.
     */
    private val ASK_FOR_OVERLAY = false

    /**
     * True when the overlay permission may still be *offered* to this user.
     *
     * [ASK_FOR_OVERLAY] switches the whole thing off. When it is on, two further
     * gates apply, either one closing it:
     *
     *  1. **We are the default launcher.** Holding `ROLE_HOME` is itself a
     *     background-activity-start exemption, so the post-call screen already
     *     starts directly through it (see CallStateReceiver.holdsSystemDefaultRole)
     *     and asking for "display over other apps" on top of that buys nothing —
     *     so we don't ask. Checked live, because the role can be granted mid-session
     *     by the set-as-default onboarding step. The trade-off is deliberate: the
     *     ringing-time caller-ID card genuinely needs a WindowManager overlay
     *     (IdentFloatService bails out when canDrawOverlays is false), so on an
     *     unlocked phone a launcher user sees the post-call screen but not the
     *     incoming-call card.
     *  2. **The IP-location "do not show" gate** — `Iscountry_Counter` +
     *     `CountryList_Counter_NShow` (see ADDashboardActivity.funOnAdsLoad, which
     *     resolves the match once at splash into [AdsVault.isNShowLocation]). Put a
     *     country / region / city in that list and the permission disappears there;
     *     put the literal `all` in it and it disappears worldwide.
     *
     * Every surface that *asks* for the overlay honours this — the permission
     * sheet row, Home's Enable banner, the Terms step. It deliberately says
     * nothing about a permission the user has already granted: the caller-ID card
     * keeps working for them, because this gates the prompt, not the feature.
     */
    fun isOfferable(context: Context): Boolean {
        if (!ASK_FOR_OVERLAY) return false
        if (runCatching { context.isDefaultLauncher() }.getOrDefault(false)) return false
        return !AdsVault.getInstance(context).isNShowLocation
    }

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
        OverlayGuideActivity.start(context, OverlayGuideActivity.Mode.OVERLAY)
    }
}
