package com.calleridapp.numberlookup.ui.home

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.AppOpenAdRegistry
import com.calleridapp.admesh.presentation.InAppUpdateListener
import com.calleridapp.admesh.presentation.InAppUpdateRegistry
import com.calleridapp.numberlookup.permission.AccessSheetDialog
import com.calleridapp.numberlookup.permission.fsi.FullScreenAccess
import com.calleridapp.numberlookup.permission.fsi.FullScreenConfig
import com.calleridapp.numberlookup.permission.fsi.FullScreenPrimingDialog
import com.calleridapp.numberlookup.permission.fsi.FullScreenReturnWatcher
import com.calleridapp.numberlookup.services.PersonUploader
import com.calleridapp.numberlookup.ui.terms.FloatKit

/**
 * Everything in the home shell that needs an **Activity** rather than a View.
 *
 * The seam: [HomeShellFragment] draws, this drives. Result launchers must be registered
 * before the Activity is STARTED, and a fragment hosted in the launcher's side panel is
 * committed long after that — so permission round-trips, the FSI flow and the Play update
 * check cannot live in the fragment. They live here, owned by the host Activity as a field
 * initializer, and the fragment reaches them through [HomeShellHost].
 *
 * Two-phase on purpose: [onHostCreated] registers (pre-STARTED, cheap, invisible) and
 * [startFirstRunPriming] prompts (visible, deferred until the shell is actually on screen —
 * in the launcher that is when the panel opens, not when it is committed off-screen).
 */
class HomeShellController(private val host: HomeShellHost) {

    private val activity get() = host.hostActivity

    /** Set by [HomeShellFragment] while its view is alive; null when the shell isn't drawn. */
    var shell: HomeShellFragment? = null

    /** Brings the host back when the FSI toggle flips on (dialog grant round-trip). */
    private val fsiReturnWatcher by lazy { FullScreenReturnWatcher(activity) }

    /** True after the FSI dialog's "Enable" sends us to Settings; drives the deferred sheet. */
    private var awaitFsiReturnForSheet = false

    /**
     * True once the first-run permission sheet has been dismissed ("Not now" or swipe).
     * Home uses it (via [shouldShowPermissionHint]) to surface a "Manage" hint only
     * *after* the user has closed the sheet at least once.
     */
    private var permissionSheetDismissed = false

    /** Guards [startFirstRunPriming] so a re-opened panel doesn't prime twice per session. */
    private var primingStarted = false

    private val handler = Handler(Looper.getMainLooper())

    // ─────────────────────────── Result launchers ───────────────────────────
    // Registered at construction: the host Activity builds this controller as a field
    // initializer, which is the last moment that is still pre-STARTED.

    /** Re-checks the banner when the user returns from the overlay Settings page. */
    private val overlayLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        stopOverlayGrantPoll()
        shell?.updateOverlayBanner()
    }

    /** Launches the FSI Settings page in-task for the priming dialog (no lingering task). */
    private val fsiSettingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Back from the FSI Settings page (auto-return or manual back).
        stopFsiGrantPoll()
        if (FullScreenAccess.isGranted(activity)) FullScreenPrimingDialog.dismissIfShowing()
        // The FSI "Enable" round-trip has returned → now surface the permission sheet.
        if (awaitFsiReturnForSheet) {
            awaitFsiReturnForSheet = false
            maybeAutoShowPermissionSheet()
        }
    }

    // ─────────────────────────── Host lifecycle ───────────────────────────

    /**
     * Registration only — must run pre-STARTED, and must *not* show anything.
     *
     * In the launcher this runs during the home screen's `onCreate`, while the user is
     * looking at the app grid; anything visible here would appear over it.
     */
    fun onHostCreated() {
        InAppUpdateRegistry.registerLauncher(activity)
        maybeCheckForUpdate()
        // One-time contact upload (no-op if already done or contacts not permitted yet).
        PersonUploader.uploadOnceIfNeeded(activity)
        // Arm the FSI auto-return so a grant on the system page pulls us back.
        fsiReturnWatcher.register()
    }

    /**
     * The visible first-run prompts: FSI priming dialog first, then the permission sheet.
     *
     * Call this when the shell is actually on screen. ShellActivity calls it from
     * `initView`, where the shell *is* the screen; the launcher defers it until the panel
     * has finished sliding in, so the sheet lands over the caller-ID content it is asking
     * about rather than over the home grid.
     *
     * First-run order: the FSI dialog comes FIRST; the permission sheet follows once the
     * dialog is resolved (Not now → immediately; Enable → after the system-settings
     * round-trip returns). When FSI isn't eligible, the sheet auto-shows straight away
     * (subject to its RC frequency gate).
     */
    fun startFirstRunPriming() {
        if (primingStarted) return
        primingStarted = true

        val fsiCfg = FullScreenConfig.load(activity)
        if (FullScreenAccess.shouldShowDialog(activity, fsiCfg)) {
            scheduleFsiDialog(fsiCfg)
        } else {
            maybeAutoShowPermissionSheet()
        }
    }

    /** Re-evaluate after returning from a permission/Settings round trip. */
    fun onHostResume() {
        shell?.updateOverlayBanner()
        // FSI grant round-trip: stop the watcher and, once granted, close the dialog.
        FullScreenAccess.stopWatch(activity)
        if (FullScreenAccess.isGranted(activity)) FullScreenPrimingDialog.dismissIfShowing()
        // Safety net for the auto-return: if the FSI grant landed us back here, run the
        // deferred permission sheet. Guarded on isGranted so the earlier
        // notification-permission-dialog return can't trigger it prematurely.
        if (awaitFsiReturnForSheet && FullScreenAccess.isGranted(activity)) {
            awaitFsiReturnForSheet = false
            maybeAutoShowPermissionSheet()
        }
        // Resume an interrupted update (IMMEDIATE re-prompts; FLEXIBLE completes a finished download).
        InAppUpdateRegistry.resumeUpdate()
    }

    fun onHostDestroy() {
        stopOverlayGrantPoll()
        stopFsiGrantPoll()
        handler.removeCallbacksAndMessages(null)
        fsiReturnWatcher.unregister()
        FullScreenAccess.stopWatch(activity)
        shell?.dismissForceUpdateDialog()
        // Owner-scoped: a late teardown here must not wipe the other host's registration.
        InAppUpdateRegistry.destroy(activity)
    }

    // ─────────────────────────── In-app update ───────────────────────────

    /**
     * Triggers the Play in-app update flow when Remote Config enables it.
     *  - `In_App_Update_Show`       → master switch for offering an update.
     *  - `In_App_Update_Force_Show` → true = IMMEDIATE (mandatory), false = FLEXIBLE (optional).
     */
    private fun maybeCheckForUpdate() {
        val pref = AdsVault.getInstance(activity)
        if (!pref.getBoolean("In_App_Update_Show")) return

        InAppUpdateRegistry.init(
            activity = activity,
            isForceUpdate = pref.getBoolean("In_App_Update_Force_Show"),
            callback = object : InAppUpdateListener {
                override fun onUpdateSuccess() {}
                override fun onUpdateCanceled() {}
                override fun onUpdateFailed() {
                    shell?.showForceUpdateRequiredDialog()
                }

                override fun onUpdateDownloaded() {
                    shell?.showUpdateReadyPrompt()
                }
            }
        )
    }

    // ─────────────────────────── Permission sheet ───────────────────────────

    /**
     * Shows the permission priming bottom sheet ([AccessSheetDialog]).
     *
     * The sheet lists every permission still needed (notification, phone state when
     * HD_VBC_Show is on, call log, contacts, overlay) and lets the user grant them;
     * already-granted ones are hidden.
     */
    fun showPermissionSheet() {
        AccessSheetDialog.show(activity) {
            shell?.updateOverlayBanner()
            permissionSheetDismissed = true
            shell?.refreshHomePermissionHint()
        }
    }

    /** Auto-shows the permission sheet when pending perms + the RC frequency gate allow. */
    private fun maybeAutoShowPermissionSheet() {
        if (AccessSheetDialog.shouldAutoShow(activity)) showPermissionSheet()
    }

    /**
     * True when the permission sheet has been dismissed at least once and at least one of
     * its permissions is still missing — the condition for Home's "Manage" hint.
     */
    fun shouldShowPermissionHint(): Boolean =
        permissionSheetDismissed && AccessSheetDialog.hasPending(activity)

    // ─────────────────────────── FSI priming ───────────────────────────

    /**
     * Schedules the Firebase-gated FSI priming dialog after `dialog.delay` ms, when
     * [FullScreenAccess.shouldShowDialog] passes (SDK 14+, feature on, country allowed,
     * ungranted, within `show_after_days` / `max_show_count`). The dialog runs FIRST; when
     * it's resolved the permission sheet follows:
     *  - **Not now / dismissed** → the sheet shows immediately.
     *  - **Enable** → the user leaves to the system FSI page; the sheet is shown on return.
     * If the dialog is no longer eligible when the delay fires, the sheet shows straight
     * away so the flow never dead-ends.
     */
    private fun scheduleFsiDialog(cfg: FullScreenConfig) {
        handler.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            if (!FullScreenAccess.shouldShowDialog(activity, cfg)) {
                maybeAutoShowPermissionSheet()
                return@postDelayed
            }
            FullScreenAccess.markDialogShown(activity)
            FullScreenPrimingDialog.show(activity, cfg) { enabled ->
                if (enabled) {
                    // Off to the system FSI page — surface the sheet once we're back.
                    awaitFsiReturnForSheet = true
                } else {
                    maybeAutoShowPermissionSheet()
                }
            }
        }, cfg.dialog.delayMs)
    }

    /** Opens the FSI "Manage" page (called from [FullScreenPrimingDialog]). */
    fun openFsiSettings() {
        FullScreenAccess.openSettings(activity, fsiSettingsLauncher)
        // Reliable grant detection from the Activity itself (the background service
        // can't start on the way to Settings on Android 12+/16).
        startFsiGrantPoll()
    }

    // In-activity grant poll — the RELIABLE auto-return for the dialog's Enable path.
    //
    // The FSI Settings page is opened IN-TASK, so this app keeps a foreground task the
    // whole time it's shown. A main-thread Handler keeps ticking while the host is merely
    // stopped (the process stays alive); the instant the toggle flips we pull the host back
    // with an in-task REORDER_TO_FRONT (no background-activity-start, so no BAL privilege
    // needed). A background Service can't be started on the way to Settings on 12+/16.
    private val fsiGrantPollHandler = Handler(Looper.getMainLooper())
    private var fsiGrantPolling = false
    private val fsiGrantPoll = object : Runnable {
        override fun run() {
            if (activity.isDestroyed) { fsiGrantPolling = false; return }
            if (FullScreenAccess.isGranted(activity)) {
                fsiGrantPolling = false
                onFsiGranted()
            } else {
                fsiGrantPollHandler.postDelayed(this, GRANT_POLL_MS)
            }
        }
    }

    private fun startFsiGrantPoll() {
        if (fsiGrantPolling) return
        fsiGrantPolling = true
        fsiGrantPollHandler.removeCallbacks(fsiGrantPoll)
        fsiGrantPollHandler.postDelayed(fsiGrantPoll, GRANT_POLL_MS)
    }

    private fun stopFsiGrantPoll() {
        fsiGrantPolling = false
        fsiGrantPollHandler.removeCallbacks(fsiGrantPoll)
    }

    /**
     * Grant detected while the user sat on the FSI Settings page → dismiss the priming
     * dialog and pull the host forward, so the (NO_HISTORY) Settings page drops away and
     * `onResume` reacts to the grant. The permission-sheet follow-up runs from there.
     */
    private fun onFsiGranted() {
        FullScreenPrimingDialog.dismissIfShowing()
        host.bringHostToFront()
    }

    // ─────────────────────────── Overlay permission ───────────────────────────

    /**
     * Opens the system "display over other apps" page IN-TASK (for-result) and starts the
     * in-activity grant poll to catch the toggle and auto-return. Invoked by the shell's
     * banner Enable button and by each tab's permission flow.
     */
    fun startOverlayPermissionFlow() {
        if (FloatKit.isGranted(activity)) {
            shell?.updateOverlayBanner()
            return
        }

        // We open system Settings ourselves — the programmatic return to the app must NOT
        // trigger an App Open ad. One-shot skip, consumed on next foreground.
        AppOpenAdRegistry.skipNextAppOpenAd = true

        // Open ONLY the system overlay-Settings page, in our own task. The grant is caught
        // by the in-activity poll while we sit behind Settings; on grant it pulls the host
        // back to the front.
        val launched = runCatching {
            overlayLauncher.launch(FloatKit.buildOverlayIntent(activity.packageName))
        }.isSuccess
        if (!launched) return

        startOverlayGrantPoll()
    }

    private val overlayGrantPollHandler = Handler(Looper.getMainLooper())
    private var overlayGrantPolling = false
    private val overlayGrantPoll = object : Runnable {
        override fun run() {
            if (activity.isDestroyed) { overlayGrantPolling = false; return }
            if (FloatKit.isGranted(activity)) {
                overlayGrantPolling = false
                onOverlayGranted()
            } else {
                overlayGrantPollHandler.postDelayed(this, GRANT_POLL_MS)
            }
        }
    }

    private fun startOverlayGrantPoll() {
        if (overlayGrantPolling) return
        overlayGrantPolling = true
        overlayGrantPollHandler.removeCallbacks(overlayGrantPoll)
        overlayGrantPollHandler.postDelayed(overlayGrantPoll, GRANT_POLL_MS)
    }

    private fun stopOverlayGrantPoll() {
        overlayGrantPolling = false
        overlayGrantPollHandler.removeCallbacks(overlayGrantPoll)
    }

    /**
     * Overlay grant detected while the user sat on the "display over other apps" page →
     * refresh the banner and pull the host forward, so the (NO_HISTORY) Settings page drops
     * away and the user lands back on their current tab without pressing Back.
     */
    private fun onOverlayGranted() {
        shell?.updateOverlayBanner()
        host.bringHostToFront()
    }

    /** Convenience for hosts that want a plain Intent flag set for [bringHostToFront]. */
    companion object {
        /** Grant-poll cadence while the user is on a system Settings page. */
        private const val GRANT_POLL_MS = 350L

        /** Flags for the in-task reorder every [HomeShellHost.bringHostToFront] uses. */
        const val REORDER_FLAGS =
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
}
