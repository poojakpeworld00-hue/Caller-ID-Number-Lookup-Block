package com.calleridapp.numberlookup.launcher

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.calleridapp.admesh.presentation.InAppUpdateRegistry
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.ui.home.HomeShellController
import com.calleridapp.numberlookup.ui.home.HomeShellHost
import com.google.android.material.snackbar.Snackbar
import io.launcher.home.activities.LauncherPanel
import java.util.WeakHashMap

/**
 * [HomeShellHost] for the launcher module's home screen.
 *
 * [LauncherPanel] is the module's Activity, so it cannot implement our interface the way the old
 * in-app launcher did. This object stands in for it, one per LauncherPanel instance, and the shell
 * fragments find it through [of].
 *
 * It must be created from [CallerLauncherBridge.onLauncherStart]: that runs inside the launcher's
 * `onCreate`, and [HomeShellController] registers its result launchers on construction, which only
 * works before the Activity is STARTED.
 */
class LauncherShellHost private constructor(private val panel: LauncherPanel) : HomeShellHost {

    override val hostActivity: AppCompatActivity get() = panel

    override val homeShellController = HomeShellController(this)

    /** Set by [LauncherShellFragment] as the right-hand panel slides in and out. */
    var shellVisible = false

    private var updateReadySnackbar: Snackbar? = null

    override val isShellOnScreen: Boolean get() = shellVisible

    /** Back inside the panel with the shell's own tab history exhausted just closes it. */
    override fun onShellBackExhausted() = panel.closeHostPanel()

    /**
     * With the panel open the shell's own Snackbar is right; with it shut the shell is parked off
     * screen, so the home grid carries the prompt or a downloaded update has nowhere to install from.
     */
    override fun showUpdateReadyPrompt() {
        if (shellVisible) {
            homeShellController.shell?.showUpdateReadyPrompt()
            return
        }
        if (updateReadySnackbar?.isShown == true) return
        updateReadySnackbar = Snackbar
            .make(panel.findViewById(android.R.id.content), R.string.update_ready_msg, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.update_restart) { InAppUpdateRegistry.completeUpdate() }
            .also { it.show() }
    }

    /** Back from a Settings round trip (overlay / FSI grant): land in the panel, not on the grid. */
    override fun bringHostToFront() {
        runCatching {
            panel.startActivity(
                Intent(panel, LauncherPanel::class.java)
                    .addFlags(HomeShellController.REORDER_FLAGS)
                    .putExtra(LauncherPanel.EXTRA_OPEN_HOST_PANEL, true)
            )
        }
    }

    companion object {

        private val hosts = WeakHashMap<Activity, LauncherShellHost>()

        fun attach(panel: LauncherPanel) {
            if (hosts.containsKey(panel)) return
            val host = LauncherShellHost(panel)
            hosts[panel] = host
            host.homeShellController.onHostCreated()
            panel.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    hosts.remove(panel)?.homeShellController?.onHostDestroy()
                }
            })
        }

        fun of(activity: Activity?): LauncherShellHost? = activity?.let { hosts[it] }
    }
}
