package com.calleridapp.admesh.presentation

import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed
import com.google.android.play.core.ktx.isImmediateUpdateAllowed
import java.lang.ref.WeakReference

/**
 * In-App Update Manager — supports IMMEDIATE (force) and FLEXIBLE update flows.
 * Uses ActivityResultLauncher (no deprecated onActivityResult).
 */
object InAppUpdateRegistry {

    private const val TAG = "InAppUpdateRegistry"

    /** Install states that mean Play is already working on it — never prompt over these. */
    private val IN_FLIGHT_STATUSES = setOf(
        InstallStatus.PENDING, InstallStatus.DOWNLOADING, InstallStatus.INSTALLING
    )

    private var activityRef: WeakReference<Activity>? = null
    private var updateManager: AppUpdateManager? = null
    private var callback: InAppUpdateListener? = null
    private var updateType: Int = AppUpdateType.IMMEDIATE
    private var updateLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    /** True when the active flow is the mandatory (IMMEDIATE) one — hosts branch on this. */
    val isForceUpdate: Boolean get() = updateType == AppUpdateType.IMMEDIATE

    /**
     * Call this in onCreate() BEFORE the activity is STARTED.
     * Registers the ActivityResultLauncher.
     */
    fun registerLauncher(activity: ComponentActivity) {
        // Held weakly: this launcher lives in a static field, so capturing the
        // activity in the result lambda would pin it for the process lifetime.
        activityRef = WeakReference(activity)
        updateLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    Log.d(TAG, "User accepted the update.")
                    if (updateType == AppUpdateType.IMMEDIATE) {
                        callback?.onUpdateSuccess()
                    }
                    // For FLEXIBLE, success comes via InstallStateUpdatedListener
                }
                Activity.RESULT_CANCELED -> {
                    Log.d(TAG, "User canceled the update.")
                    callback?.onUpdateCanceled()
                    if (updateType == AppUpdateType.IMMEDIATE) {
                        activityRef?.get()?.finish()
                    }
                }
                else -> {
                    Log.e(TAG, "Update flow failed, resultCode=${result.resultCode}")
                    callback?.onUpdateFailed()
                }
            }
        }
    }

    /**
     * Initializes and starts checking for updates.
     *
     * @param activity The host activity.
     * @param isForceUpdate true = IMMEDIATE (mandatory), false = FLEXIBLE (optional).
     * @param callback Receives update events.
     */
    fun init(
        activity: Activity,
        isForceUpdate: Boolean = false,
        callback: InAppUpdateListener
    ) {
        this.activityRef = WeakReference(activity)
        this.callback = callback
        this.updateType = if (isForceUpdate) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        // Application context: the manager outlives a single activity instance.
        this.updateManager = AppUpdateManagerFactory.create(activity.applicationContext)

        if (updateType == AppUpdateType.FLEXIBLE) {
            updateManager?.registerListener(installStateUpdatedListener)
        }

        checkForUpdates()
    }

    /**
     * Listener for FLEXIBLE update. A finished download is handed to the host via
     * [InAppUpdateListener.onUpdateDownloaded] instead of being installed on the
     * spot: [completeUpdate] restarts the app, and doing that unannounced would
     * yank the user out of whatever they were doing.
     */
    private val installStateUpdatedListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> {
                Log.d(TAG, "Flexible update downloaded — asking the host to install.")
                notifyDownloaded()
            }
            InstallStatus.INSTALLED -> {
                Log.d(TAG, "Update installed successfully.")
                callback?.onUpdateSuccess()
                cleanup()
            }
            InstallStatus.FAILED, InstallStatus.CANCELED -> {
                Log.e(TAG, "Flexible update ended without installing: ${state.installStatus()}")
                callback?.onUpdateFailed()
            }
            else -> {
                Log.d(TAG, "Install status: ${state.installStatus()}")
            }
        }
    }

    /** Checks for updates and starts the flow if available. */
    private fun checkForUpdates() {
        val manager = updateManager ?: return
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                // An already-finished download must be installed, not offered again —
                // this is the state we come back to after an activity recreate.
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    Log.d(TAG, "Update already downloaded — asking the host to install.")
                    notifyDownloaded()
                    return@addOnSuccessListener
                }
                // Download/install already running (it survives our activity) — no prompt.
                if (info.installStatus() in IN_FLIGHT_STATUSES) {
                    Log.d(TAG, "Update already in progress (${info.installStatus()}) — no prompt.")
                    return@addOnSuccessListener
                }

                val isAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                val isAllowed = when (updateType) {
                    AppUpdateType.FLEXIBLE -> info.isFlexibleUpdateAllowed
                    AppUpdateType.IMMEDIATE -> info.isImmediateUpdateAllowed
                    else -> false
                }

                if (isAvailable && isAllowed) {
                    val launcher = updateLauncher
                    if (launcher != null) {
                        // Modern API — ActivityResultLauncher
                        manager.startUpdateFlowForResult(
                            info,
                            launcher,
                            AppUpdateOptions.newBuilder(updateType).build()
                        )
                    } else {
                        // Fallback — deprecated but works if registerLauncher wasn't called
                        activityRef?.get()?.let { activity ->
                            @Suppress("DEPRECATION")
                            manager.startUpdateFlowForResult(
                                info, updateType, activity, 123
                            )
                        }
                    }
                } else {
                    Log.d(TAG, "No update available or not allowed.")
                    // No update needed — not an error, not a success. Just continue.
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Update check failed: ${e.message}")
                callback?.onUpdateFailed()
            }
    }

    /**
     * Call from onResume() to resume interrupted updates.
     * - IMMEDIATE: resumes the mandatory update screen.
     * - FLEXIBLE: re-offers the restart when a download finished in the background.
     */
    fun resumeUpdate() {
        val manager = updateManager ?: return
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (updateType == AppUpdateType.IMMEDIATE &&
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            ) {
                val launcher = updateLauncher
                if (launcher != null) {
                    manager.startUpdateFlowForResult(
                        info, launcher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                }
            } else if (updateType == AppUpdateType.FLEXIBLE &&
                info.installStatus() == InstallStatus.DOWNLOADED
            ) {
                // Downloaded while we were in the background — let the host offer the restart.
                notifyDownloaded()
            }
        }
    }

    /**
     * Installs a downloaded FLEXIBLE update. **This restarts the app**, so only call
     * it from a user action (the host's "Restart" affordance), never automatically.
     */
    fun completeUpdate() {
        Log.d(TAG, "completeUpdate() — restarting to install.")
        updateManager?.completeUpdate()
    }

    /** Re-runs the Play check. Used by the force-update retry when the check failed. */
    fun retryCheck() {
        Log.d(TAG, "retryCheck()")
        checkForUpdates()
    }

    /** Hands a ready-to-install download to the host; without a listener it waits for next launch. */
    private fun notifyDownloaded() {
        val listener = callback
        if (listener == null) {
            Log.w(TAG, "Update downloaded but no listener attached — deferring to the next launch.")
            return
        }
        listener.onUpdateDownloaded()
    }

    /**
     * Cleans up references. Call in onDestroy().
     *
     * [owner] is the Activity tearing down. Two hosts register here — the app's own
     * ShellActivity and the launcher home that shows the same shell in its side panel — and
     * Android can deliver a backgrounded Activity's `onDestroy` *after* another one's
     * `onCreate`. Without this check that late teardown would silently wipe the live host's
     * registration, and its update flow would go quiet with nothing in the log. Pass null
     * only from a caller that knows it is the sole host.
     */
    fun destroy(owner: Activity? = null) {
        val current = activityRef?.get()
        if (owner != null && current != null && current !== owner) {
            Log.d(TAG, "destroy() from a stale host — keeping the live registration.")
            return
        }
        cleanup()
        updateLauncher = null
    }

    private fun cleanup() {
        try { updateManager?.unregisterListener(installStateUpdatedListener) } catch (_: Exception) {}
        updateManager = null
        activityRef = null
        callback = null
    }
}

/** Callback interface for update events. */
interface InAppUpdateListener {
    fun onUpdateSuccess()
    fun onUpdateCanceled()

    /**
     * The Play check or the update flow failed. For a FLEXIBLE update this is
     * informational; for a force (IMMEDIATE) one the host must react, or the
     * mandatory update is silently skipped.
     */
    fun onUpdateFailed()

    /**
     * FLEXIBLE only: the new APK is on disk and waiting. Show a restart affordance
     * and call [InAppUpdateRegistry.completeUpdate] when the user taps it. Doing
     * nothing here simply leaves the update pending for the next launch.
     */
    fun onUpdateDownloaded() {}
}
