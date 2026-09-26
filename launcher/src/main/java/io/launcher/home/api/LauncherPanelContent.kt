package io.launcher.home.api

/**
 * Optional contract for the Fragment a host puts in the right-hand panel.
 *
 * Implementing it is not required; it exists because of one fact about how the panel works.
 *
 * **The panel is committed while it is parked off screen.** That happens during the launcher's
 * `onCreate`, deliberately: left until the first swipe, the whole inflate + first query was paid
 * for inside the gesture, and the panel appeared to stick mid-slide. The cost had to move, so it
 * moved to startup — which means a host fragment is created, resumed and laid out long before
 * anyone can see it.
 *
 * So anything the host must not do unseen — request an ad, show a coach-mark, ask for a permission,
 * start an animation, mark something as read — belongs in [setPanelVisible], not in the fragment's
 * own lifecycle callbacks.
 */
interface LauncherPanelContent {

    /**
     * Whether the panel is now on screen.
     *
     * Called with `true` as the panel starts sliding in and `false` once it is closed. The fragment
     * is *not* removed on close: keeping it holds list state, scroll position and selection for the
     * next open.
     */
    fun setPanelVisible(visible: Boolean) {}

    /**
     * A Back press while the panel is open, offered to the host first.
     *
     * Return true if the host consumed it — a search field to collapse, a selection to clear — and
     * the panel stays open. Return false and the panel closes, which is what the user expects when
     * the host has nothing left to undo.
     */
    fun handleBack(): Boolean = false
}
