package com.calleridapp.numberlookup.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.calleridapp.admesh.presentation.InAppUpdateRegistry
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostFragment
import com.calleridapp.numberlookup.databinding.FragmentHomeShellBinding
import com.calleridapp.numberlookup.databinding.ItemNavBinding
import com.calleridapp.numberlookup.ui.common.HomeMotion
import com.calleridapp.numberlookup.ui.contacts.PeopleFragment
import com.calleridapp.numberlookup.ui.lookup.IdentifyFragment
import com.calleridapp.numberlookup.ui.recents.TimelineFragment
import com.calleridapp.numberlookup.ui.terms.FloatKit
import com.calleridapp.numberlookup.util.rateApp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/**
 * The app's home UI: four tabs behind a custom bottom bar, kept alive with show/hide so each
 * tab's state and scroll position survive switching.
 *
 * Hosted by [com.calleridapp.numberlookup.ui.ShellActivity] and by the launcher's
 * swipe-right side panel. Everything that needs an Activity — permission round-trips, the
 * FSI flow, the Play update check — is in [HomeShellController]; this class only draws.
 *
 * Tabs are committed to the **child** fragment manager, so a tab reaches its siblings
 * through `parentFragment` (see the [homeShell] accessor) rather than through the Activity.
 */
class HomeShellFragment : HostFragment<FragmentHomeShellBinding>() {

    private data class Tab(
        val nav: ItemNavBinding,
        val fragment: Fragment,
        @param:DrawableRes val selectedIcon: Int,
        @param:DrawableRes val unselectedIcon: Int,
        @param:StringRes val label: Int
    )

    private lateinit var tabs: List<Tab>
    private var currentIndex = -1

    /** Status-bar height read from window insets; applied per-tab. */
    private var statusBarTop = 0

    /** Visited-tab history for back navigation (most recent last). */
    private val backStack = ArrayDeque<Int>()

    /** Blocking "update required" dialog shown when a *force* update check fails. */
    private var forceUpdateDialog: AlertDialog? = null

    private val controller: HomeShellController? get() = homeShellController

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHomeShellBinding.inflate(inflater, container, false)

    override fun initView() {
        controller?.shell = this

        // Read-only inset listener: the top inset is ours (per-tab, the hero tabs draw under
        // the status bar) but bottom/side padding belongs to the host container, which pads
        // its ad banner too. Insets are returned unchanged so the host still sees them.
        ViewCompat.setOnApplyWindowInsetsListener(binding.shellRoot) { _, insets ->
            statusBarTop = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            applyTopInsetForTab(currentIndex)
            insets
        }

        tabs = listOf(
            Tab(
                binding.navHome, DashboardFragment(),
                R.drawable.ob_home_selected, R.drawable.ob_home_unselected, R.string.nav_home
            ),
            Tab(
                binding.navRecents, TimelineFragment(),
                R.drawable.ob_recent_selected, R.drawable.ob_recent_unselected, R.string.nav_recents
            ),
            Tab(
                binding.navContacts, PeopleFragment(),
                R.drawable.ob_contact_selected, R.drawable.ob_contact_unselected, R.string.nav_contacts
            ),
            Tab(
                binding.navLookup, IdentifyFragment(),
                R.drawable.ob_lookup_selected, R.drawable.ob_lookup_unselected, R.string.nav_lookup
            )
        )

        tabs.forEachIndexed { index, tab ->
            tab.nav.navLabel.setText(tab.label)
            tab.nav.root.setOnClickListener {
                animateIcon(tab.nav.navIcon)
                select(index)
            }
        }

        select(0)

        binding.btnEnableOverlay.setOnClickListener {
            controller?.startOverlayPermissionFlow()
        }

        // A number handed in by the host (Call Details → "Identify this number").
        arguments?.getString(ARG_LOOKUP_NUMBER)?.takeIf { it.isNotBlank() }?.let { number ->
            arguments?.remove(ARG_LOOKUP_NUMBER)
            showLookup(number)
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayBanner()
    }

    override fun onDestroyView() {
        dismissForceUpdateDialog()
        if (controller?.shell === this) controller?.shell = null
        super.onDestroyView()
    }

    // ─────────────────────────── Host signals ───────────────────────────

    /**
     * Called by the host as the shell comes on screen and goes off it again.
     *
     * ShellActivity is always visible so it reports `true` once. The launcher fires it after
     * the panel's slide animation and `false` when the panel closes — the panel is committed
     * at the launcher's `onCreate` and then parked off-screen, so "attached" and "on screen"
     * are two different moments and anything the user should actually see waits for this.
     */
    fun setPanelVisible(visible: Boolean) {
        if (visible) updateOverlayBanner()
    }

    /** Routes a number into the Lookup tab from outside the shell (deep link / panel host). */
    fun requestLookup(number: String?) {
        if (view == null) {
            arguments = (arguments ?: Bundle()).apply { putString(ARG_LOOKUP_NUMBER, number) }
            return
        }
        showLookup(number)
    }

    // ─────────────────────────── Tab navigation ───────────────────────────

    /**
     * Switches to the Lookup tab. If [number] is given (e.g. from Home search), the Lookup
     * tab runs the search for it on arrival.
     */
    fun showLookup(number: String? = null) {
        val index = tabs.indexOfFirst { it.fragment is IdentifyFragment }
        if (index < 0) return
        select(index)
        if (!number.isNullOrBlank()) {
            (tabs[index].fragment as? IdentifyFragment)?.requestSearch(number)
        }
    }

    /** Switches to the Recents tab (Home's "See all" recent activity). */
    fun showRecents() {
        val index = tabs.indexOfFirst { it.fragment is TimelineFragment }
        if (index >= 0) select(index)
    }

    /**
     * Advances one tab to the right: Home → Recents → Contacts → Lookup.
     *
     * @return false when already on the last tab, so the host can decide what "past the end"
     * means — in the launcher panel that is closing the panel back to the home screen.
     */
    fun pageForward(): Boolean {
        if (currentIndex < 0 || currentIndex >= tabs.lastIndex) return false
        select(currentIndex + 1)
        return true
    }

    /**
     * Goes one tab back to the left, the inverse of [pageForward].
     *
     * @return false when already on the first tab (Home).
     */
    fun pageBack(): Boolean {
        if (currentIndex <= 0) return false
        select(currentIndex - 1)
        return true
    }

    /**
     * Retraces the visited-tab stack by one step.
     *
     * @return true when the press was consumed, false once the history is exhausted on Home —
     * at which point "back out of the shell" is the host's call.
     *
     * Deliberately **not** an `OnBackPressedCallback` registered from here. Both hosts already
     * own back handling — HostActivity has its back-ad callback and the launcher's Fossify base
     * routes everything through `onBackPressedCompat()` — and which one the dispatcher runs
     * first depends on the order lifecycle owners reach STARTED, which is not something to bet
     * tab navigation on. Each host asks, exactly as the launcher already does for its drawer.
     */
    fun onBackPressed(): Boolean {
        // Retrace the tab history first.
        if (backStack.isNotEmpty()) {
            select(backStack.removeLast(), recordHistory = false)
            return true
        }

        // Safety net: not on Home with empty history -> go Home.
        val homeIndex = tabs.indexOfFirst { it.fragment is DashboardFragment }.coerceAtLeast(0)
        if (currentIndex != homeIndex) {
            select(homeIndex, recordHistory = false)
            return true
        }

        return false
    }

    /** One-shot pop when a bottom-bar icon is tapped. */
    private fun animateIcon(icon: View) {
        icon.animate().cancel()
        icon.scaleX = 0.7f
        icon.scaleY = 0.7f
        icon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    private fun select(index: Int, recordHistory: Boolean = true) {
        if (index == currentIndex) return

        // Record the tab we're leaving so Back can retrace to it (each tab kept once).
        if (recordHistory && currentIndex >= 0) {
            backStack.remove(index)
            backStack.remove(currentIndex)
            backStack.addLast(currentIndex)
        }

        val tab = tabs[index]

        childFragmentManager.beginTransaction().apply {
            if (!tab.fragment.isAdded) add(R.id.fragmentContainer, tab.fragment)
            tabs.forEach { if (it.fragment.isAdded && it !== tab) hide(it.fragment) }
            show(tab.fragment)
        }.commit()

        tabs.forEachIndexed { i, t ->
            val active = i == index
            t.nav.navIcon.setImageResource(if (active) t.selectedIcon else t.unselectedIcon)
            val from = t.nav.navLabel.currentTextColor
            val to = ContextCompat.getColor(
                requireContext(), if (active) R.color.primary else R.color.on_surface_variant
            )
            // Icon shape swap is instant (selected/unselected are different drawables); the
            // colour itself crossfades instead of snapping.
            HomeMotion.animateTint(t.nav.navIcon, t.nav.navLabel, from, to)
            t.nav.navIndicator.visibility = if (active) View.VISIBLE else View.INVISIBLE
        }

        currentIndex = index
        applyTopInsetForTab(index)
    }

    /**
     * Tabs with a blue hero (Home, Recents, Contacts, Lookup) draw under the status bar — no
     * top inset on the container, and the fragment pads its own hero.
     */
    private fun applyTopInsetForTab(index: Int) {
        if (index < 0 || view == null) return
        val fragment = tabs.getOrNull(index)?.fragment
        val immersive = fragment is DashboardFragment ||
            fragment is TimelineFragment ||
            fragment is PeopleFragment ||
            fragment is IdentifyFragment
        binding.fragmentContainer.setPadding(0, if (immersive) 0 else statusBarTop, 0, 0)
        // All v2 tabs (Home / Recents / Contacts / Lookup) use a LIGHT background, so the
        // status-bar icons are always dark.
        val window = activity?.window ?: return
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = true
    }

    // ─────────────────────────── Overlay banner ───────────────────────────

    /**
     * The banner is only relevant once the core permissions are in place: show it when
     * call-log AND contacts are granted but the overlay permission is not.
     */
    fun updateOverlayBanner() {
        val ctx = context ?: return
        if (view == null) return
        val coreGranted = isPermissionGranted(Manifest.permission.READ_CALL_LOG) &&
            isPermissionGranted(Manifest.permission.READ_CONTACTS)
        val show = coreGranted && !FloatKit.isGranted(ctx)
        binding.overlayBanner.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED

    /** Nudges Home to (re)evaluate its "Manage permissions" hint. */
    fun refreshHomePermissionHint() {
        (tabs.firstOrNull { it.fragment is DashboardFragment }?.fragment as? DashboardFragment)
            ?.refreshPermissionHint()
    }

    // ─────────────────────────── In-app update surfaces ───────────────────────────

    /**
     * A FLEXIBLE update finished downloading. Installing it restarts the app, so the user
     * picks the moment — the snackbar sits above the bottom bar until they act.
     */
    fun showUpdateReadyPrompt() {
        if (view == null || isRemoving) return
        Snackbar.make(binding.shellRoot, R.string.update_ready_msg, Snackbar.LENGTH_INDEFINITE)
            .setAnchorView(binding.bottomBar)
            .setAction(R.string.update_restart) { InAppUpdateRegistry.completeUpdate() }
            .show()
    }

    /**
     * The Play check failed. An optional update just carries on silently, but a *force*
     * update must not be skipped because the check errored (offline, Play not ready, app not
     * Play-installed) — offer a retry or the Play listing, and back out of the shell if the
     * user takes neither.
     */
    fun showForceUpdateRequiredDialog() {
        if (!InAppUpdateRegistry.isForceUpdate) return
        val activity = activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (forceUpdateDialog?.isShowing == true) return

        forceUpdateDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_required_title)
            .setMessage(R.string.update_required_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.update_retry) { dialog, _ ->
                dialog.dismiss()
                InAppUpdateRegistry.retryCheck()
            }
            .setNegativeButton(R.string.update_open_store) { _, _ ->
                activity.rateApp()              // opens this package's Play listing
                homeShellHost?.onShellBackExhausted()   // don't leave them on the stale build
            }
            .show()
    }

    fun dismissForceUpdateDialog() {
        forceUpdateDialog?.dismiss()
        forceUpdateDialog = null
    }

    companion object {
        private const val ARG_LOOKUP_NUMBER = "arg_lookup_number"

        /** @param number optional number to identify — routes straight to the Lookup tab. */
        fun newInstance(number: String? = null) = HomeShellFragment().apply {
            if (!number.isNullOrBlank()) {
                arguments = Bundle().apply { putString(ARG_LOOKUP_NUMBER, number) }
            }
        }
    }
}
