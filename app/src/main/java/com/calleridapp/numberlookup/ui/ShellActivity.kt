package com.calleridapp.numberlookup.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.logPermissionResult
import com.calleridapp.admesh.presentation.AppOpenAdRegistry
import com.calleridapp.admesh.presentation.InAppUpdateListener
import com.calleridapp.admesh.presentation.InAppUpdateRegistry
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.numberlookup.data.VaultRegistry
import com.calleridapp.numberlookup.permission.AccessSheetDialog
import com.calleridapp.numberlookup.permission.fsi.FullScreenConfig
import com.calleridapp.numberlookup.permission.fsi.FullScreenAccess
import com.calleridapp.numberlookup.permission.fsi.FullScreenPrimingDialog
import com.calleridapp.numberlookup.permission.fsi.FullScreenReturnWatcher
import com.calleridapp.numberlookup.launcher.activities.MainActivity as LauncherHomeActivity
import com.calleridapp.numberlookup.databinding.ActivityMainBinding
import com.calleridapp.numberlookup.databinding.ItemNavBinding
import com.calleridapp.numberlookup.services.PersonUploader
import com.calleridapp.numberlookup.ui.common.HomeMotion
import com.calleridapp.numberlookup.ui.contacts.PeopleFragment
import com.calleridapp.numberlookup.ui.home.DashboardFragment
import com.calleridapp.numberlookup.ui.lookup.IdentifyFragment
import com.calleridapp.numberlookup.ui.recents.TimelineFragment
import com.calleridapp.numberlookup.ui.terms.FloatKit

/**
 * Host Activity with a custom LinearLayout bottom bar (not BottomNavigationView).
 * Manages five fragments using show/hide to preserve their state.
 */
class ShellActivity : HostActivity<ActivityMainBinding>() {

    override val layoutId: Int = R.layout.activity_main

    private data class Tab(
        val nav: ItemNavBinding,
        val fragment: Fragment,
        @param:DrawableRes val selectedIcon: Int,
        @param:DrawableRes val unselectedIcon: Int,
        @param:StringRes val label: Int
    )

    private lateinit var tabs: List<Tab>
    private var currentIndex = -1

    /** Status-bar height captured from window insets; applied per-tab. */
    private var statusBarTop = 0

    /** Visited-tab history for back navigation (most recent last). */
    private val backStack = ArrayDeque<Int>()

    /** Posts the delayed FSI priming dialog (see [scheduleFsiDialog]). */
    private val fsiHandler = Handler(Looper.getMainLooper())

    /** Brings ShellActivity back when the FSI toggle flips on (dialog grant round-trip). */
    private val fsiReturnWatcher by lazy { FullScreenReturnWatcher(this) }

    /**
     * True once the first-run permission sheet has been dismissed ("Not now" or
     * swipe). Home uses it (via [shouldShowPermissionHint]) to surface a "Manage"
     * hint only *after* the user has closed the sheet at least once.
     */
    var permissionSheetDismissed = false
        private set

    // In-activity overlay-grant poll — the RELIABLE auto-return for the "Manage"
    // overlay flow. The system "display over other apps" page is opened IN-TASK
    // (launched for-result), so the process keeps a foreground task and this
    // main-thread Handler keeps ticking while ShellActivity is merely stopped. The
    // instant the toggle flips we pull ShellActivity back with an in-task
    // REORDER_TO_FRONT — no background Service and no background-activity-start,
    // both unreliable on Android 12+/16 (what the old FloatWatchService relied on;
    // it now serves only the first-run Terms flow).
    private val overlayGrantPollHandler = Handler(Looper.getMainLooper())
    private var overlayGrantPolling = false
    private val overlayGrantPoll = object : Runnable {
        override fun run() {
            if (isDestroyed) { overlayGrantPolling = false; return }
            if (FloatKit.isGranted(this@ShellActivity)) {
                overlayGrantPolling = false
                onOverlayGranted()
            } else {
                overlayGrantPollHandler.postDelayed(this, OVERLAY_GRANT_POLL_MS)
            }
        }
    }

    /** Re-checks the banner when the user returns from the overlay Settings page. */
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        stopOverlayGrantPoll()
        updateOverlayBanner()
    }

    /** Launches the FSI Settings page in-task for the priming dialog (no lingering task). */
    private val fsiSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Back from the FSI Settings page (auto-return or manual back).
        stopFsiGrantPoll()
        if (FullScreenAccess.isGranted(this)) FullScreenPrimingDialog.dismissIfShowing()
        // The FSI "Enable" round-trip has returned → now surface the permission sheet.
        if (awaitFsiReturnForSheet) {
            awaitFsiReturnForSheet = false
            maybeAutoShowPermissionSheet()
        }
    }

    /** True after the FSI dialog's "Enable" sends us to Settings; drives the deferred sheet. */
    private var awaitFsiReturnForSheet = false

    // In-activity grant poll — the RELIABLE auto-return for the dialog's Enable path.
    //
    // The FSI Settings page is opened IN-TASK, so this app keeps a foreground task
    // the whole time it's shown. A main-thread Handler keeps ticking while ShellActivity
    // is merely stopped (the process stays alive); the instant the toggle flips we pull
    // ShellActivity back with an in-task REORDER_TO_FRONT (no background-activity-start,
    // so no BAL privilege needed). This replaces relying on FullScreenWatchService — a
    // background Service can't be started on the way to Settings on Android 12+/16.
    private val fsiGrantPollHandler = Handler(Looper.getMainLooper())
    private var fsiGrantPolling = false
    private val fsiGrantPoll = object : Runnable {
        override fun run() {
            if (isDestroyed) { fsiGrantPolling = false; return }
            if (FullScreenAccess.isGranted(this@ShellActivity)) {
                fsiGrantPolling = false
                onFsiGranted()
            } else {
                fsiGrantPollHandler.postDelayed(this, FSI_GRANT_POLL_MS)
            }
        }
    }

    /** Begin polling for the FSI grant (idempotent). Called when we open FSI settings. */
    private fun startFsiGrantPoll() {
        if (fsiGrantPolling) return
        fsiGrantPolling = true
        fsiGrantPollHandler.removeCallbacks(fsiGrantPoll)
        fsiGrantPollHandler.postDelayed(fsiGrantPoll, FSI_GRANT_POLL_MS)
    }

    private fun stopFsiGrantPoll() {
        fsiGrantPolling = false
        fsiGrantPollHandler.removeCallbacks(fsiGrantPoll)
    }

    /**
     * Grant detected while the user sat on the FSI Settings page → dismiss the
     * priming dialog and reorder the EXISTING ShellActivity to the front of the same
     * task, so the (NO_HISTORY) Settings page drops away and onResume/the launcher
     * react to the grant. The permission-sheet follow-up runs from there.
     */
    private fun onFsiGranted() {
        FullScreenPrimingDialog.dismissIfShowing()
        runCatching {
            startActivity(
                Intent(this, ShellActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
    }

    /** Opens the FSI "Manage" page for the priming dialog (called from FullScreenPrimingDialog). */
    fun openFsiSettings() {
        FullScreenAccess.openSettings(this, fsiSettingsLauncher)
        // Reliable grant detection from the Activity itself (the background service
        // can't start on the way to Settings on Android 12+/16).
        startFsiGrantPoll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Must register the update result-launcher before the activity is STARTED.
        InAppUpdateRegistry.registerLauncher(this)
        maybeCheckForUpdate()
    }

    /**
     * Triggers the Play in-app update flow when Remote Config enables it.
     *  - `In_App_Update_Show`       → master switch for offering an update.
     *  - `In_App_Update_Force_Show` → true = IMMEDIATE (mandatory), false = FLEXIBLE (optional).
     */
    private fun maybeCheckForUpdate() {
        val pref = AdsVault.getInstance(this)
        if (!pref.getBoolean("In_App_Update_Show")) return

        InAppUpdateRegistry.init(
            activity = this,
            isForceUpdate = pref.getBoolean("In_App_Update_Force_Show"),
            callback = object : InAppUpdateListener {
                override fun onUpdateSuccess() {}
                override fun onUpdateCanceled() {}
                override fun onUpdateFailed() {}
            }
        )
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarTop = bars.top
            // No top padding on the root — Home's hero draws under the status bar.
            // Each tab gets its own top inset applied in [applyTopInsetForTab].
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
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

        binding.btnEnableOverlay.setOnClickListener { startOverlayPermissionFlow() }

        onBackPressedDispatcher.addCallback(this) { handleBack() }

        // One-time contact upload (no-op if already done or contacts not permitted yet).
        PersonUploader.uploadOnceIfNeeded(this)

        // Arm the FSI auto-return so a grant on the system page pulls us back.
        fsiReturnWatcher.register()

        // First-run priming order: the FSI dialog comes FIRST; the permission sheet
        // follows once the FSI dialog is resolved (Not now → immediately; Enable →
        // after the system-settings round-trip returns). When FSI isn't eligible,
        // the sheet auto-shows straight away (subject to its RC frequency gate).
        val fsiCfg = FullScreenConfig.load(this)
        if (FullScreenAccess.shouldShowDialog(this, fsiCfg)) {
            scheduleFsiDialog(fsiCfg)
        } else {
            maybeAutoShowPermissionSheet()
        }

        // "Identify this number" from Call Details lands us straight on Lookup.
        handleLookupIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLookupIntent(intent)
    }

    private fun handleLookupIntent(intent: Intent?) {
        val number = intent?.getStringExtra(EXTRA_LOOKUP_NUMBER)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(EXTRA_LOOKUP_NUMBER)
        showLookup(number)
    }

    // --- Permission priming (bottom sheet) ---

    /**
     * Shows the permission priming bottom sheet ([AccessSheetDialog]) on
     * demand — hook this to any button/menu click:
     *
     * ```
     * someButton.setOnClickListener { showPermissionSheet() }
     * ```
     *
     * The sheet lists every permission still needed (notification, phone state
     * when HD_VBC_Show is on, call log, contacts, overlay) and lets the user
     * grant them; already-granted ones are hidden.
     */
    fun showPermissionSheet() {
        AccessSheetDialog.show(this) {
            updateOverlayBanner()
            onPermissionSheetDismissed()
        }
    }

    /** Auto-shows the permission sheet when pending perms + the RC frequency gate allow. */
    private fun maybeAutoShowPermissionSheet() {
        if (AccessSheetDialog.shouldAutoShow(this)) showPermissionSheet()
    }

    /**
     * Schedules the Firebase-gated FSI priming dialog after `dialog.delay` ms, when
     * [FullScreenAccess.shouldShowDialog] passes (SDK 14+, feature on, country allowed,
     * ungranted, within `show_after_days` / `max_show_count`). The dialog runs FIRST;
     * when it's resolved the permission sheet follows:
     *  - **Not now / dismissed** → the sheet shows immediately.
     *  - **Enable** → the user leaves to the system FSI page; the sheet is shown on
     *    return (see [fsiSettingsLauncher]).
     * If the dialog is no longer eligible when the delay fires, the sheet shows
     * straight away so the flow never dead-ends.
     */
    private fun scheduleFsiDialog(cfg: FullScreenConfig) {
        fsiHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (!FullScreenAccess.shouldShowDialog(this, cfg)) {
                maybeAutoShowPermissionSheet()
                return@postDelayed
            }
            FullScreenAccess.markDialogShown(this)
            FullScreenPrimingDialog.show(this, cfg) { enabled ->
                if (enabled) {
                    // Off to the system FSI page — surface the sheet once we're back.
                    awaitFsiReturnForSheet = true
                } else {
                    maybeAutoShowPermissionSheet()
                }
            }
        }, cfg.dialog.delayMs)
    }

    /**
     * True when the permission sheet has been dismissed at least once and at
     * least one of its permissions is still missing — the condition for Home's
     * "Manage" hint.
     */
    fun shouldShowPermissionHint(): Boolean =
        permissionSheetDismissed && AccessSheetDialog.hasPending(this)

    /** Runs when the sheet closes; nudges Home to (re)evaluate its permission hint. */
    private fun onPermissionSheetDismissed() {
        permissionSheetDismissed = true
        (tabs.firstOrNull { it.fragment is DashboardFragment }?.fragment as? DashboardFragment)
            ?.refreshPermissionHint()
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate after returning from a permission/Settings round trip.
        updateOverlayBanner()
        // FSI grant round-trip: stop the watcher and, once granted, close the dialog.
        FullScreenAccess.stopWatch(this)
        if (FullScreenAccess.isGranted(this)) FullScreenPrimingDialog.dismissIfShowing()
        // Safety net for the auto-return: if the FSI grant landed us back here, run
        // the deferred permission sheet. Guarded on isGranted so the earlier
        // notification-permission-dialog return can't trigger it prematurely.
        if (awaitFsiReturnForSheet && FullScreenAccess.isGranted(this)) {
            awaitFsiReturnForSheet = false
            maybeAutoShowPermissionSheet()
        }
        // Resume an interrupted update (IMMEDIATE re-prompts; FLEXIBLE completes a finished download).
        InAppUpdateRegistry.resumeUpdate()
    }

    override fun onDestroy() {
        stopOverlayGrantPoll()
        stopFsiGrantPoll()
        fsiHandler.removeCallbacksAndMessages(null)
        fsiReturnWatcher.unregister()
        FullScreenAccess.stopWatch(this)
        InAppUpdateRegistry.destroy()
        super.onDestroy()
    }

    // --- Overlay-permission banner ---

    /**
     * The banner is only relevant once the core permissions are in place: show it
     * when call-log AND contacts are granted but the overlay permission is not.
     */
    private fun updateOverlayBanner() {
        val coreGranted = isPermissionGranted(Manifest.permission.READ_CALL_LOG) &&
            isPermissionGranted(Manifest.permission.READ_CONTACTS)
        val show = coreGranted && !FloatKit.isGranted(this)
        binding.overlayBanner.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Opens the system "display over other apps" page IN-TASK (for-result) and
     * starts the in-activity grant poll to catch the toggle and auto-return.
     * Invoked by the banner's Enable button and by each fragment's permission flow.
     */
    fun startOverlayPermissionFlow() {
        if (FloatKit.isGranted(this)) {
            updateOverlayBanner()
            return
        }

        // We open system Settings ourselves — the programmatic return to the app
        // must NOT trigger an App Open ad. One-shot skip, consumed on next foreground.
        AppOpenAdRegistry.skipNextAppOpenAd = true

        // Open ONLY the system overlay-Settings page, in our own task. The grant is
        // caught by the in-activity poll (startOverlayGrantPoll) while we sit behind
        // Settings; on grant it reorders ShellActivity back to the front.
        val launched = runCatching {
            overlayLauncher.launch(FloatKit.buildOverlayIntent(packageName))
        }.isSuccess
        if (!launched) return

        startOverlayGrantPoll()
    }

    /** Begin polling for the overlay grant (idempotent). Called when we open Settings. */
    private fun startOverlayGrantPoll() {
        if (overlayGrantPolling) return
        overlayGrantPolling = true
        overlayGrantPollHandler.removeCallbacks(overlayGrantPoll)
        overlayGrantPollHandler.postDelayed(overlayGrantPoll, OVERLAY_GRANT_POLL_MS)
    }

    private fun stopOverlayGrantPoll() {
        overlayGrantPolling = false
        overlayGrantPollHandler.removeCallbacks(overlayGrantPoll)
    }

    /**
     * Overlay grant detected while the user sat on the "display over other apps"
     * page → refresh the banner and reorder the EXISTING ShellActivity to the front
     * of the same task, so the (NO_HISTORY) Settings page drops away and the user
     * lands back on their current tab without pressing Back. In-task REORDER = no
     * background-activity-start, so it needs no BAL privilege on Android 12+/16.
     */
    private fun onOverlayGranted() {
        updateOverlayBanner()
        runCatching {
            startActivity(
                Intent(this, ShellActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
    }

    /**
     * Back retraces the visited-tab stack; once it empties (on Home) a single back returns to
     * the launcher home screen. There is no press-back-again-to-exit step: this screen is one
     * swipe away from the home screen, so backing out of it should feel like closing a panel,
     * not like quitting the app.
     */
    private fun handleBack() {
        // Retrace the tab history first.
        if (backStack.isNotEmpty()) {
            select(backStack.removeLast(), recordHistory = false)
            return
        }

        // Safety net: not on Home with empty history -> go Home.
        val homeIndex = tabs.indexOfFirst { it.fragment is DashboardFragment }.coerceAtLeast(0)
        if (currentIndex != homeIndex) {
            select(homeIndex, recordHistory = false)
            return
        }

        exitToHome()
    }

    /**
     * Returns to the launcher home screen.
     *
     * Goes to it explicitly rather than firing a generic ACTION_MAIN/CATEGORY_HOME: this app is
     * only the default launcher once the user grants the role, and until then a HOME intent
     * would hand the user to whichever launcher is currently default. Starting the home screen
     * brings its own task forward — which also guarantees we never land on a leftover system
     * "Manage" Settings page (those are `singleTask`, live in their own task, and would
     * otherwise surface when this task finishes).
     */
    private fun exitToHome() {
        runCatching {
            startActivity(
                Intent(this, LauncherHomeActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            )
        }
        finishAffinity()
    }

    /**
     * Switches to the Lookup tab. If [number] is given (e.g. from Home search),
     * the Lookup fragment runs the search for it on arrival.
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

        supportFragmentManager.beginTransaction().apply {
            if (!tab.fragment.isAdded) add(R.id.fragmentContainer, tab.fragment)
            tabs.forEach { if (it.fragment.isAdded && it !== tab) hide(it.fragment) }
            show(tab.fragment)
        }.commit()

        tabs.forEachIndexed { i, t ->
            val active = i == index
            t.nav.navIcon.setImageResource(if (active) t.selectedIcon else t.unselectedIcon)
            val from = t.nav.navLabel.currentTextColor
            val to = ContextCompat.getColor(
                this, if (active) R.color.primary else R.color.on_surface_variant
            )
            // Icon shape swap is instant (selected/unselected are different
            // drawables); the colour itself crossfades instead of snapping.
            HomeMotion.animateTint(t.nav.navIcon, t.nav.navLabel, from, to)
            t.nav.navIndicator.visibility = if (active) View.VISIBLE else View.INVISIBLE
        }

        currentIndex = index
        applyTopInsetForTab(index)
    }

    /**
     * Tabs with a blue hero (Home, Recents, Contacts, Lookup) draw under the status
     * bar — no top inset on the container, light status-bar icons, and the fragment
     * pads its own hero.
     */
    private fun applyTopInsetForTab(index: Int) {
        if (index < 0) return
        val fragment = tabs.getOrNull(index)?.fragment
        val immersive = fragment is DashboardFragment ||
            fragment is TimelineFragment ||
            fragment is PeopleFragment ||
            fragment is IdentifyFragment
        binding.fragmentContainer.setPadding(0, if (immersive) 0 else statusBarTop, 0, 0)
        // All v2 tabs (Home / Recents / Contacts / Lookup) now use a LIGHT background,
        // so the status-bar icons are always dark.
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = true
    }

    companion object {
        /** Grant-poll cadence while the user is on the FSI Settings page. */
        private const val FSI_GRANT_POLL_MS = 350L
        private const val OVERLAY_GRANT_POLL_MS = 350L

        /** Intent extra: a number to identify — routes straight to the Lookup tab. */
        const val EXTRA_LOOKUP_NUMBER = "extra_lookup_number"
    }
}