package io.launcher.home.fragments

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import io.launcher.home.R
import io.launcher.home.activities.LauncherPanel
import io.launcher.home.api.LauncherPanelContent
import io.launcher.home.api.LauncherRegistry
import io.launcher.home.databinding.LnchHostPanelBinding
import kotlin.math.abs

/**
 * Right-hand panel: the container for whatever UI the host app supplies through
 * [io.launcher.home.api.LauncherBridge.panelFragment]. This class owns only the panel's own
 * gesture and the fragment's commit and visibility hand-off — what is inside it is the host's
 * business entirely.
 *
 * With no host fragment the panel is inert: [refresh] commits nothing, and the swipe that would
 * open it is gated off by `panels.host` in `launcher_config`.
 */
class HostPanelSurface(
    context: Context,
    attributeSet: AttributeSet,
) : LauncherSurface<LnchHostPanelBinding>(context, attributeSet) {

    // the panel covers the whole screen while open, so LauncherPanel never sees these events
    private val gestureDetector = GestureDetectorCompat(context, object : SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (velocityX < 0 && abs(velocityX) > abs(velocityY)) {
                activity?.hideHostPanel()
                return true
            }
            return false
        }
    })

    /** The host's fragment once committed, or null while the panel has never been opened. */
    val panelContent: Fragment?
        get() = activity?.supportFragmentManager?.findFragmentById(R.id.host_panel_containerUi)

    /** The same fragment, when it opted into being told about the panel. */
    private val panelCallbacks: LauncherPanelContent?
        get() = panelContent as? LauncherPanelContent

    override fun setupFragment(activity: LauncherPanel) {
        this.activity = activity
        this.binding = LnchHostPanelBinding.bind(this)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // do not swallow the event, the host UI still has to scroll
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    /** Called by [LauncherPanel] just before the panel slides in; commits the host UI on first open. */
    fun refresh() {
        val activity = activity ?: return
        val fm = activity.supportFragmentManager
        // A commit after onSaveInstanceState throws; the next open lands here again anyway.
        if (fm.isStateSaved) return
        if (fm.findFragmentById(R.id.host_panel_containerUi) == null) {
            // Asked for per commit rather than held: the launcher outlives any one instance, and a
            // host that returns null simply has no right panel.
            val fragment = LauncherRegistry.bridge.panelFragment() ?: return
            // commitNow, so setPanelVisible below can reach the fragment on this same open
            fm.beginTransaction()
                .replace(R.id.host_panel_containerUi, fragment)
                .commitNow()
        }
    }

    /**
     * Commits the host UI while the panel is still parked off screen.
     *
     * Left to [refresh], the first swipe paid for the whole inflate, injection and first query
     * inside the gesture — a 2 s frame that read as "panel stuck mid-slide". Everything the host
     * must not do while nobody is looking already waits on [setPanelVisible], so the only thing
     * that moves is *when* the cost is paid.
     */
    fun warmUp() = refresh()

    /**
     * Tells the host whether it is on screen. The fragment is not removed on close — keeping it
     * holds list state, scroll position and selection for the next open.
     */
    fun setPanelVisible(visible: Boolean) {
        panelCallbacks?.setPanelVisible(visible)
    }

    /** Back while the panel is open goes to the host first, then closes the panel. */
    fun handleBack(): Boolean = panelCallbacks?.handleBack() == true
}
