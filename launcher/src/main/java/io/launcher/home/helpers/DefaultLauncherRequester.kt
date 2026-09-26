package io.launcher.home.helpers

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import io.launcher.home.extensions.bringTaskToFront
import io.launcher.home.activities.DefaultHomeHintPanel
import io.launcher.home.profile.LauncherFingerprint
import org.fossify.commons.helpers.isQPlus
import timber.log.Timber
import io.launcher.home.extensions.isDefaultLauncher
import io.launcher.home.extensions.roleManager

/**
 * The "make this app the Home app" escalation, in one place.
 *
 * ### The sequence
 *
 * ```
 * tap → Settings
 *   ↓ return, role held? → onGranted(), done
 *   ↓ no
 * system chooser, automatically (once)
 *   ↓ return, role held? → onGranted(), done
 *   ↓ no
 * stop — the dialog budget for this visit is spent
 * ```
 *
 * Settings first on every tap, never the chooser: the Settings list is where most users actually
 * complete it, and going straight to the chooser skips that page. This replaces
 * `requestSetAsDefaultLauncher()` for the launcher's own surfaces — that helper fires whichever of
 * four intents resolves first and then forgets about it, so a user who backed out of the Settings
 * list was never offered the one-tap dialog that would have finished the job.
 *
 * ### Why it terminates
 *
 * [pending] is what makes one pass finite: Settings hands off to the chooser at most once, and the
 * chooser hands off to nothing. No return path can reopen Settings by itself, so a user who
 * dismisses the chooser is left where they are rather than in a loop of system dialogs.
 *
 * [roleDialogShown] is what stops the *nagging*: the chooser is offered at most once per visit. The
 * control itself is not rationed — every tap reopens the Settings list, which is what keeps it from
 * ever appearing dead.
 *
 * The reference app spends that same allowance from a second place, a Back press on its onboarding
 * Set-as-Default step, so the two share one budget. Here the two are separate:
 * [io.launcher.home.activities.DefaultLauncherPanel] offers the dialog on Back with a
 * budget of its own, driving it from [rolePickerIntent] rather than from an instance of this class —
 * that step wants the dialog straight away, not the Settings-first escalation, and it leaves for the
 * next step as soon as the dialog is answered, so there is no visit for a shared budget to span. It
 * does suppress the App Open impression itself, per the note below.
 *
 * ### Why resume, not an activity result
 *
 * A Settings page reports no meaningful result code and on some builds delivers no result at all,
 * and the role dialog reports CANCELED on some OEMs even after granting. The role state itself is
 * the only reliable signal, so this checks it on every resume and ignores results entirely.
 *
 * ### Lifecycle
 *
 * [onResume] is idempotent — it acts only on [pending], which it clears as it goes, so duplicate
 * resumes (a configuration change, a dialog dismissing over the Activity) cannot fire twice.
 * [inFlight] blocks a second Settings screen or chooser while one is already up.
 * [saveState]/[restoreState] carry the escalation across process death, so a low-memory kill while
 * the user is in Settings still escalates correctly on return.
 *
 * ### No App Open suppression here
 *
 * The reference app silences the next App Open impression before each hand-off, because the return
 * from a system page the app itself opened is not a launch the user chose. This build does not need
 * that hook for the launcher: `LauncherApp.onResumeApp` already refuses the resume placement
 * outright whenever the resumed Activity is [io.launcher.home.activities.LauncherPanel], which is
 * every return path this class currently has. Wiring it to a surface that is *not* LauncherPanel —
 * the onboarding Set-as-Default step, say — would need that suppression added first.
 */
class DefaultLauncherRequester(
    private val activity: Activity,
    /** Fired once, on the first resume where the role is actually held. */
    private val onGranted: () -> Unit = {},
) {

    enum class Pending { NONE, SETTINGS, ROLE_DIALOG }

    private var pending: Pending = Pending.NONE

    /** True between launching a system surface and coming back from it. */
    private var inFlight = false

    private var granted = false

    /**
     * The role dialog's one-per-visit budget.
     *
     * Carried across process death by [saveState]: a low-memory kill while the user is on the system
     * screen must not silently refill the allowance and ask again on return.
     */
    private var roleDialogShown = false

    /**
     * Opens the Settings page where the Home app is chosen, escalating to the system chooser on
     * return if the role still isn't held.
     *
     * Devices with no default-apps page go straight to the chooser rather than leaving the control
     * dead. Returns false when neither surface exists, so the caller can fall back.
     */
    fun start(): Boolean {
        if (inFlight || activity.isDefaultLauncher()) return false
        // Which launcher is being replaced can only be read while it still holds the role.
        LauncherFingerprint.ensureCaptured(activity)
        // Always the Settings list first, on every tap, never consulting a flag. That is what makes
        // the control impossible to wedge: a routing decision based on remembered state is what
        // leaves repeat taps going to a chooser the user already declined, so it appears to do
        // nothing.
        if (settingsIntents().any { launch(it, Pending.SETTINGS) }) return true
        // Nothing on this device opens a Home-app page. Straight to the chooser rather than leaving
        // the control dead.
        return rolePickerIntent(activity)?.let { launch(it, Pending.ROLE_DIALOG) } ?: false
    }

    /**
     * The auto-return from the Settings list. The system sends the user to the new Home app by
     * itself on some builds (AOSP, MIUI) and leaves them on the list on others (One UI); this
     * poller makes it the same everywhere, the way OverlayReturnKeeper does for the overlay page.
     * Bounded, and dropped on the next resume either way.
     */
    private var returnWatch: Runnable? = null

    private fun armReturnWatch() {
        cancelReturnWatch()
        val startedAt = SystemClock.uptimeMillis()
        val watch = object : Runnable {
            override fun run() {
                if (returnWatch !== this || activity.isFinishing || activity.isDestroyed) return
                if (activity.isDefaultLauncher()) {
                    returnWatch = null
                    Timber.d("DefaultLauncherRequester: role granted on the Settings list, raising the launcher")
                    activity.bringTaskToFront()
                    return
                }
                if (SystemClock.uptimeMillis() - startedAt < RETURN_WATCH_TIMEOUT_MS) {
                    handler.postDelayed(this, RETURN_WATCH_POLL_MS)
                } else {
                    returnWatch = null
                }
            }
        }
        returnWatch = watch
        handler.postDelayed(watch, RETURN_WATCH_POLL_MS)
    }

    private fun cancelReturnWatch() {
        returnWatch?.let { handler.removeCallbacks(it) }
        returnWatch = null
    }

    /** Call from the host's `onResume`. Safe to call on every resume. */
    fun onResume() {
        inFlight = false
        cancelReturnWatch()
        if (activity.isDefaultLauncher()) {
            pending = Pending.NONE
            if (!granted) {
                granted = true
                onGranted()
            }
            return
        }

        when (pending) {
            // Back from the Settings list without the role. The list is easy to leave without
            // choosing anything, so it earns one direct one-tap dialog — but only if this visit has
            // not already spent it. Below Q there is no such dialog, and staying put is correct
            // there: reopening Settings on its own is what this must never do.
            Pending.SETTINGS -> {
                pending = Pending.NONE
                if (!roleDialogShown) {
                    roleDialogShown = true
                    rolePickerIntent(activity)?.let { launch(it, Pending.ROLE_DIALOG) }
                }
            }

            // Dismissed the chooser, backed out, or picked someone else. End of the pass — nothing
            // reopens on its own. The dialog allowance stays spent; the control still reopens the
            // Settings list on the next tap.
            Pending.ROLE_DIALOG -> pending = Pending.NONE

            Pending.NONE -> Unit
        }
    }

    private fun launch(intent: Intent, step: Pending): Boolean {
        pending = step
        inFlight = true
        return runCatching {
            // startActivityForResult, not startActivity, even though the result is ignored — see
            // "Why resume, not an activity result" above for the ignoring. The system's role
            // screen identifies the requester through the *calling* package, which exists only for
            // a call started for result; started plainly it finishes without ever drawing, so the
            // escalation looks like it did nothing at all.
            activity.startActivityForResult(intent, REQUEST_SET_DEFAULT)
            // A hint over the Settings list, where the user has to find this app among every other
            // launcher. Not over the role dialog, which asks for one tap on a named button and needs
            // no help — and where a card could only get in the way.
            if (step == Pending.SETTINGS) {
                DefaultHomeHintPanel.show(activity)
                armReturnWatch()
            }
            true
        }.getOrElse {
            pending = Pending.NONE
            inFlight = false
            false
        }
    }

    fun saveState(outState: Bundle) {
        outState.putString(KEY_PENDING, pending.name)
        outState.putBoolean(KEY_ROLE_DIALOG_SHOWN, roleDialogShown)
    }

    fun restoreState(savedState: Bundle?) {
        val state = savedState ?: return
        val name = state.getString(KEY_PENDING)
        if (name != null) {
            pending = runCatching { Pending.valueOf(name) }.getOrDefault(Pending.NONE)
        }
        roleDialogShown = state.getBoolean(KEY_ROLE_DIALOG_SHOWN, false)
    }

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val RETURN_WATCH_POLL_MS = 300L
        private const val RETURN_WATCH_TIMEOUT_MS = 5 * 60 * 1000L
        private const val KEY_PENDING = "default_launcher_pending"
        private const val KEY_ROLE_DIALOG_SHOWN = "default_launcher_role_dialog_shown"

        /**
         * The Settings pages where the Home app can be chosen, best first: the specific page, then
         * the default-apps list as the fallback for OEM builds that do not expose it.
         *
         * Returned as candidates to *try* rather than filtered with `resolveActivity`, which is
         * subject to package-visibility filtering on API 30+ and can answer "nothing handles this"
         * for a page that opens perfectly well. `requestSetAsDefaultLauncher`, the helper this
         * replaces, has always probed by launching and catching — [launch] does the same.
         *
         * Deliberately unflagged. The page is started into this app's own task and inherits its
         * properties; pushing it into a task of its own with `NEW_TASK` puts it in Recents as a
         * separate entry.
         */
        fun settingsIntents(): List<Intent> = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        )

        /**
         * The transient system role-request *dialog*, not a Settings page. Null below Q, when the
         * role is unavailable, or when it is already held.
         */
        fun rolePickerIntent(ctx: Context): Intent? {
            if (!isQPlus()) {
                return null
            }

            return runCatching {
                with(ctx.roleManager) {
                    when {
                        !isRoleAvailable(RoleManager.ROLE_HOME) -> null
                        isRoleHeld(RoleManager.ROLE_HOME) -> null
                        else -> createRequestRoleIntent(RoleManager.ROLE_HOME)
                    }
                }
            }.getOrNull()
        }
    }
}
