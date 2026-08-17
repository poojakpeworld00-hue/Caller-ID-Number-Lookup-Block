package com.calleridapp.numberlookup.launcher.activities

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import com.calleridapp.admesh.domain.LauncherAdsConfig
import android.app.WallpaperManager
import android.app.WallpaperManager.OnColorsChangedListener
import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.util.Log
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.viewbinding.ViewBinding
import kotlinx.collections.immutable.toImmutableList
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.insetsController
import org.fossify.commons.extensions.isPackageInstalled
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.extensions.realScreenSize
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.DARK_GREY
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isOreoMr1Plus
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.databinding.ActivityLauncherHomeBinding
import com.calleridapp.numberlookup.databinding.AllAppsFragmentBinding
import com.calleridapp.numberlookup.databinding.WidgetsFragmentBinding
import com.calleridapp.numberlookup.launcher.dialogs.RenameItemDialog
import com.calleridapp.numberlookup.launcher.extensions.config
import com.calleridapp.numberlookup.launcher.extensions.getDrawableForPackageName
import com.calleridapp.numberlookup.launcher.extensions.getLabel
import com.calleridapp.numberlookup.launcher.extensions.handleGridItemPopupMenu
import com.calleridapp.numberlookup.launcher.extensions.hiddenIconsDB
import com.calleridapp.numberlookup.launcher.extensions.homeScreenGridItemsDB
import com.calleridapp.numberlookup.launcher.extensions.isDefaultLauncher
import com.calleridapp.numberlookup.launcher.extensions.launchApp
import com.calleridapp.numberlookup.launcher.extensions.launchAppInfo
import com.calleridapp.numberlookup.launcher.extensions.launchersDB
import com.calleridapp.numberlookup.launcher.extensions.requestSetAsDefaultLauncher
import com.calleridapp.numberlookup.launcher.extensions.supportsDarkText
import com.calleridapp.numberlookup.launcher.extensions.uninstallApp
import com.calleridapp.numberlookup.launcher.fragments.MyFragment
import com.calleridapp.numberlookup.launcher.helpers.CLOCK_ROW_SPAN
import com.calleridapp.numberlookup.launcher.helpers.ITEM_TYPE_FOLDER
import com.calleridapp.numberlookup.launcher.helpers.ITEM_TYPE_ICON
import com.calleridapp.numberlookup.launcher.helpers.ITEM_TYPE_SHORTCUT
import com.calleridapp.numberlookup.launcher.helpers.LauncherFlow
import com.calleridapp.numberlookup.launcher.helpers.ITEM_TYPE_WIDGET
import com.calleridapp.numberlookup.launcher.helpers.IconCache
import com.calleridapp.numberlookup.launcher.helpers.PSEUDO_WIDGET_CLOCK
import com.calleridapp.numberlookup.launcher.helpers.PSEUDO_WIDGET_SEARCH
import com.calleridapp.numberlookup.launcher.helpers.REQUEST_ALLOW_BINDING_WIDGET
import com.calleridapp.numberlookup.launcher.helpers.SEARCH_BAR_ROW
import com.calleridapp.numberlookup.launcher.helpers.REQUEST_CONFIGURE_WIDGET
import com.calleridapp.numberlookup.launcher.helpers.REQUEST_CREATE_SHORTCUT
import com.calleridapp.numberlookup.launcher.helpers.UNINSTALL_APP_REQUEST_CODE
import com.calleridapp.numberlookup.launcher.interfaces.FlingListener
import com.calleridapp.numberlookup.launcher.interfaces.ItemMenuListener
import com.calleridapp.numberlookup.launcher.models.AppLauncher
import com.calleridapp.numberlookup.launcher.models.HiddenIcon
import com.calleridapp.numberlookup.launcher.models.HomeScreenGridItem
import com.calleridapp.numberlookup.launcher.receivers.LockDeviceAdminReceiver
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.appcompat.app.AppCompatActivity
import com.calleridapp.numberlookup.data.LocaleRegistry
import com.calleridapp.numberlookup.ui.home.HomeShellController
import com.calleridapp.numberlookup.ui.home.HomeShellHost
import com.calleridapp.numberlookup.util.applyNativeAdTheme

class MainActivity : SimpleActivity(), FlingListener, HomeShellHost {

    override val hostActivity: AppCompatActivity get() = this

    // Not `by lazy`: constructing this registers result launchers, which must happen before
    // the Activity is STARTED. The caller panel's shell is committed much later than that,
    // which is exactly why the Activity-bound half lives out here.
    override val homeShellController = HomeShellController(this)

    /** Back inside the caller panel with its own tab history exhausted just closes it. */
    override fun onShellBackExhausted() {
        hideCallerPanel()
    }

    /**
     * Pull the launcher back to the front after a system-Settings round trip started from
     * inside the panel. The panel is a view in this Activity, so it is still open when we
     * land — the user returns to the tab they left.
     */
    override fun bringHostToFront() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(HomeShellController.REORDER_FLAGS)
            )
        }
    }

    private var mTouchDownX = -1
    private var mTouchDownY = -1
    private var mAllAppsFragmentY = 0
    private var mWidgetsFragmentY = 0
    private var mScreenHeight = 0
    private var mScreenWidth = 0
    private var mMoveGestureThreshold = 0
    private var mIgnoreUpEvent = false
    private var mIgnoreMoveEvents = false
    private var mIgnoreXMoveEvents = false
    private var mIgnoreYMoveEvents = false
    private var mLongPressedIcon: HomeScreenGridItem? = null
    private var mOpenPopupMenu: PopupMenu? = null
    private var mLastTouchCoords = Pair(-1f, -1f)
    private var mActionOnCanBindWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnWidgetConfiguredWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnAddShortcut:
            ((shortcutId: String, label: String, icon: Drawable) -> Unit)? = null
    private var wasJustPaused: Boolean = false

    private val mSwipeHintAnimators = mutableListOf<ObjectAnimator>()
    private val mSwipeHintHider = Runnable { hideSwipeHint() }
    private var mHomeHint: LauncherAdsConfig.HomeHint? = null
    private var mHintRunActive = false

    private var wallpaperColorChangeListener: OnColorsChangedListener? = null
    private var wallpaperSupportsDarkText: Boolean? = null

    private lateinit var mDetector: GestureDetectorCompat
    private val binding by viewBinding(ActivityLauncherHomeBinding::inflate)

    companion object {
        private var mLastUpEvent = 0L
        private const val ANIMATION_DURATION = 150L
        private const val APP_DRAWER_CLOSE_DELAY = 300L
        private const val APP_DRAWER_STATE = "app_drawer_state"
        private const val SWIPE_HINT_ANIMATION_DURATION = 900L
        // long enough for the user to read the shade they just pulled down before the next
        // hint appears underneath it
        private const val SHADE_HINT_RESUME_DELAY = 2500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        useDynamicTheme = false

        // Before super.onCreate, so the grid and both side panels inflate with the chosen
        // language's resources. HostActivity does this for every other screen; this one does
        // not extend it, and it hosts the app's own home UI in the caller panel — without this
        // a language picked during onboarding would not reach either.
        LocaleRegistry.applySaved(this)

        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        // This is the device HOME, so it can be the first screen after a reboot — and the
        // native-ad palette keys are global, only ever written by HostActivity, which this is
        // not. Without this the natives in the app-search panel, the app drawer and the caller
        // panel's Home tab keep whatever mode some earlier screen left behind, or none at all on
        // a cold boot straight to home: dark text on a dark card, effectively invisible.
        applyNativeAdTheme()
        setupEdgeToEdge(
            padTopSystem = listOf(
                binding.allAppsFragment.root,
                binding.widgetsFragment.root,
                binding.leftPanel.root,
                binding.defaultLauncherBanner.root
            ),
            padBottomImeAndSystem = listOf(
                binding.allAppsFragment.allAppsGrid,
                binding.widgetsFragment.widgetsList,
                binding.leftPanel.panelScroll
            ),
            // the panel's ad is pinned below its scroll area, so it — not the scroll — is what
            // has to clear the navigation bar. System-only, deliberately: padding it for the IME
            // too would make it leap above the keyboard while the user is typing a search.
            padBottomSystem = listOf(binding.homeScreenGrid.root, binding.leftPanel.adNativeFrame)
        )

        mDetector = GestureDetectorCompat(this, MyGestureListener(this))

        mScreenHeight = realScreenSize.y
        mScreenWidth = realScreenSize.x
        mAllAppsFragmentY = mScreenHeight
        mWidgetsFragmentY = mScreenHeight
        mMoveGestureThreshold = resources.getDimensionPixelSize(R.dimen.move_gesture_threshold)

        arrayOf(
            binding.allAppsFragment.root as MyFragment<*>,
            binding.widgetsFragment.root as MyFragment<*>
        ).forEach { fragment ->
            fragment.setupFragment(this)
            fragment.y = mScreenHeight.toFloat()
            fragment.beVisible()
        }

        // The app-search panel is parked off screen to the right and slides in on a left
        // fling. A right fling has no panel of its own — it opens the caller-ID app instead.
        binding.leftPanel.root.apply {
            setupFragment(this@MainActivity)
            x = mScreenWidth.toFloat()
            beVisible()
        }

        // The caller panel comes in from the opposite edge, so it parks on the other side.
        binding.callerPanel.root.apply {
            setupFragment(this@MainActivity)
            x = -mScreenWidth.toFloat()
            beVisible()
        }

        // Registration only (update launcher, contact upload, FSI watcher) — nothing visible.
        // Gated on onboarding being done, because `onCreate` on a HOME activity is not a
        // "the user opened the app" signal. The visible priming waits for the panel to open.
        if (LauncherFlow.wasOnboardingCompleted(this)) {
            homeShellController.onHostCreated()
        }

        handleIntentAction(intent)

        binding.homeScreenGrid.root.itemClickListener = {
            performItemClick(it)
        }

        binding.homeScreenGrid.root.itemLongClickListener = {
            performItemLongClick(
                x = binding.homeScreenGrid.root.getClickableRect(it).left.toFloat(),
                clickedGridItem = it
            )
        }

        // onboarding already asks about this explicitly on first run (with a Skip option). If the
        // user skipped it or later unset us, the "Setup Required" banner below nags instead of a
        // dialog, same as the reference app. It also stays reachable via the long-press menu.
        binding.defaultLauncherBanner.root.setOnClickListener { requestSetAsDefaultLauncher() }

        setupWallpaperColorListener()

        // Granting the home role makes the system open us immediately, over an onboarding task
        // that is still mid-run — so the home screen can be the first thing a user sees while
        // steps are still pending. Hand the run back its screen (it opens on top of this one)
        // and leave the coach mark for the launch that really is the end of onboarding.
        if (LauncherFlow.resumeIfUnfinished(this)) {
            return
        }

        startSwipeHintRun()
    }

    /**
     * Opens a run of the coach mark, driven by `launcher_ads.home_hint`.
     *
     * The gestures in `swipeHints` are taught ONE AT A TIME, in order: the first hint stays up
     * until the user actually makes that swipe, then the next one appears the next time they
     * are back on the bare home screen, and so on until the list is exhausted.
     *
     * Whether a run starts at all is decided once per launch — `isHintDue` spends a counter
     * tick, so it must not be asked again on every resume. `once` latches on the launcher's own
     * pref, so an install that has already been through the list never sees it again.
     */
    private fun startSwipeHintRun() {
        val hint = LauncherAdsConfig.homeHint(this)
        mHomeHint = hint
        if (!hint.visible) {
            return
        }

        // An interrupted `once` run picks up where it left off rather than starting over; the
        // repeating modes always begin a fresh pass through the list.
        val resuming = hint.mode == LauncherAdsConfig.HintMode.ONCE &&
                config.swipeHintIndex in 1 until hint.directions.size

        mHintRunActive = when {
            resuming -> true
            hint.mode == LauncherAdsConfig.HintMode.ALWAYS -> true
            hint.mode == LauncherAdsConfig.HintMode.APP_LAUNCHES ->
                LauncherAdsConfig.isHintDue(this, hint)

            else -> !config.wasSwipeHintShown
        }

        if (mHintRunActive && !resuming) {
            config.swipeHintIndex = 0
        }
    }

    /**
     * Shows the hint the user has not made yet, or ends the run once they all have. Called
     * whenever the bare home screen comes back into view — a hint over an open drawer or panel
     * would be teaching a gesture that screen does not have.
     */
    private fun showNextSwipeHint() {
        val hint = mHomeHint ?: return
        if (!mHintRunActive) {
            return
        }

        if (config.swipeHintIndex >= hint.directions.size) {
            mHintRunActive = false
            config.wasSwipeHintShown = true
            hideSwipeHint()
            return
        }

        if (isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded() || isLeftPanelExpanded()) {
            return
        }

        showSwipeHint(hint.directions[config.swipeHintIndex], hint.autoHideSec)
    }

    /**
     * The user made the gesture the hint was teaching, so it is learned: move to the next one.
     * Any other gesture leaves the index alone — the hint stays until its own swipe is made.
     */
    private fun completeSwipeHint(direction: LauncherAdsConfig.HintDirection) {
        if (!mHintRunActive || !binding.swipeHint.isVisible) {
            return
        }

        if (mHomeHint?.directions?.getOrNull(config.swipeHintIndex) != direction) {
            return
        }

        config.swipeHintIndex = config.swipeHintIndex + 1
        hideSwipeHint()

        // Swiping down only pulls the shade over us — the home screen is never left, so
        // nothing else will come back to ask for the next hint.
        if (direction == LauncherAdsConfig.HintDirection.DOWN) {
            binding.swipeHint.postDelayed({ showNextSwipeHint() }, SHADE_HINT_RESUME_DELAY)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSwipeHint(direction: LauncherAdsConfig.HintDirection, autoHideSec: Int) {
        val travel = resources.getDimension(R.dimen.swipe_hint_travel)

        // The overlay has to swallow the touches it covers: it is only a background, so without
        // this a tap falls through to the home screen underneath and opens whatever is behind
        // the hint — the search pill, or an app icon. The gesture detector is still fed, so
        // both routes work: the taught swipe as usual, and a tap anywhere doing the same thing
        // (see homeScreenClicked → performHintAction).
        binding.swipeHint.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // the Activity's own ACTION_DOWN bookkeeping never runs for these events
                mIgnoreXMoveEvents = false
                mIgnoreYMoveEvents = false
                mIgnoreUpEvent = false
            }
            runCatching { mDetector.onTouchEvent(event) }
            true
        }

        mSwipeHintAnimators.forEach { it.cancel() }
        mSwipeHintAnimators.clear()
        binding.swipeHint.removeCallbacks(mSwipeHintHider)
        binding.swipeHint.removeAllViews()

        val row = layoutInflater.inflate(R.layout.item_swipe_hint, binding.swipeHint, false)
        val chevrons = row.findViewById<View>(R.id.hint_chevrons)
        row.findViewById<TextView>(R.id.hint_label).setText(captionFor(direction))

        // The chevrons are drawn pointing right, so each direction is that row rotated — and
        // the drift then runs along the matching axis. Rotation happens in the row's own
        // space; translation stays in the parent's, hence the axis switch.
        val (property, distance) = when (direction) {
            LauncherAdsConfig.HintDirection.RIGHT -> View.TRANSLATION_X to travel
            LauncherAdsConfig.HintDirection.LEFT -> View.TRANSLATION_X to -travel
            LauncherAdsConfig.HintDirection.UP -> View.TRANSLATION_Y to -travel
            LauncherAdsConfig.HintDirection.DOWN -> View.TRANSLATION_Y to travel
        }
        chevrons.rotation = when (direction) {
            LauncherAdsConfig.HintDirection.RIGHT -> 0f
            LauncherAdsConfig.HintDirection.LEFT -> 180f
            LauncherAdsConfig.HintDirection.UP -> 270f
            LauncherAdsConfig.HintDirection.DOWN -> 90f
        }

        // A quarter turn leaves the trio standing three chevrons tall in a row that only
        // measured one, so a vertical row needs the extra height reserved — otherwise it draws
        // over its own caption.
        if (property == View.TRANSLATION_Y) {
            val pad = resources.getDimensionPixelSize(R.dimen.swipe_hint_vertical_pad)
            row.setPadding(row.paddingLeft, pad, row.paddingRight, pad)
        }

        binding.swipeHint.addView(row)
        mSwipeHintAnimators += ObjectAnimator.ofFloat(chevrons, property, 0f, distance).apply {
            duration = SWIPE_HINT_ANIMATION_DURATION
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        binding.swipeHint.beVisible()

        // Auto-hide only takes this hint off the screen; the run stays where it is, so the
        // same one is offered again next time the home screen comes back.
        if (autoHideSec > 0) {
            binding.swipeHint.postDelayed(mSwipeHintHider, autoHideSec * 1000L)
        }
    }

    /**
     * Runs the gesture the visible hint is teaching, as if the user had made it — the fling
     * handlers own the ad pacing and mark the hint done, so a tap and a swipe land in exactly
     * the same place.
     */
    private fun performHintAction() {
        when (mHomeHint?.directions?.getOrNull(config.swipeHintIndex)) {
            LauncherAdsConfig.HintDirection.RIGHT -> onFlingRight()
            LauncherAdsConfig.HintDirection.LEFT -> onFlingLeft()
            LauncherAdsConfig.HintDirection.UP -> onFlingUp()
            LauncherAdsConfig.HintDirection.DOWN -> onFlingDown()
            null -> hideSwipeHint()
        }
    }

    private fun captionFor(direction: LauncherAdsConfig.HintDirection): Int = when (direction) {
        LauncherAdsConfig.HintDirection.RIGHT -> R.string.swipe_right_hint
        LauncherAdsConfig.HintDirection.LEFT -> R.string.swipe_left_hint
        LauncherAdsConfig.HintDirection.UP -> R.string.swipe_up_hint
        LauncherAdsConfig.HintDirection.DOWN -> R.string.swipe_down_hint
    }

    private fun hideSwipeHint() {
        if (!binding.swipeHint.isVisible) {
            return
        }

        binding.swipeHint.removeCallbacks(mSwipeHintHider)
        binding.swipeHint.setOnTouchListener(null)
        mSwipeHintAnimators.forEach { it.cancel() }
        mSwipeHintAnimators.clear()
        binding.swipeHint.beGone()
    }

    private fun setupWallpaperColorListener() {
        if (isOreoMr1Plus()) {
            val wallpaperManager = WallpaperManager.getInstance(this)
            wallpaperColorChangeListener = OnColorsChangedListener { colors, which ->
                if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                    wallpaperSupportsDarkText = colors?.supportsDarkText() ?: run {
                        refreshWallpaperSupportsDarkText()
                        wallpaperSupportsDarkText
                    }
                    if (!isAllAppsFragmentExpanded() && !isWidgetsFragmentExpanded()) {
                        runOnUiThread {
                            updateStatusBarIcons()
                        }
                    }
                }
            }
            wallpaperManager.addOnColorsChangedListener(
                wallpaperColorChangeListener!!,
                Handler(Looper.getMainLooper())
            )

            refreshWallpaperSupportsDarkText()
        }
    }

    private fun refreshWallpaperSupportsDarkText() {
        if (!isOreoMr1Plus()) return
        wallpaperSupportsDarkText = WallpaperManager.getInstance(this)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.supportsDarkText()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Same as onCreate: a home intent can land here with the first run still pending, once
        // this activity already exists. It is only reachable that way after the role changed
        // under us, so the run has a screen owing.
        if (LauncherFlow.resumeIfUnfinished(this)) {
            return
        }

        val wasAnyFragmentOpen = isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()
        if (wasJustPaused) {
            if (isAllAppsFragmentExpanded()) {
                hideFragment(binding.allAppsFragment)
            }
            if (isWidgetsFragmentExpanded()) {
                hideFragment(binding.widgetsFragment)
            }
        } else {
            closeAppDrawer()
            closeWidgetsFragment()
        }

        if (isLeftPanelExpanded()) {
            hideLeftPanel()
        }

        binding.allAppsFragment.searchBar.closeSearch()

        // scroll to first page when home button is pressed
        val alreadyOnHome = intent.flags and FLAG_ACTIVITY_BROUGHT_TO_FRONT == 0
        if (alreadyOnHome && !wasAnyFragmentOpen) {
            binding.homeScreenGrid.root.skipToPage(0)
        }

        handleIntentAction(intent)
    }

    override fun onStart() {
        super.onStart()
        binding.homeScreenGrid.root.appWidgetHost.startListening()
    }

    override fun onResume() {
        super.onResume()
        wasJustPaused = false
        // Catches a language picked while we were in the background (the app's Settings and the
        // onboarding picker both live in other Activities). Same call HostActivity makes here.
        LocaleRegistry.applySaved(this)
        // Picks up an overlay / full-screen-intent grant made from inside the caller panel.
        homeShellController.onHostResume()
        refreshWallpaperSupportsDarkText()
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()) {
                updateStatusBarIcons(getProperBackgroundColor())
            } else {
                updateStatusBarIcons()
            }
        }, ANIMATION_DURATION)

        // Back on the home screen — offer the next hint the user has not made yet. Covers the
        // first show too, since onResume always follows onCreate.
        showNextSwipeHint()

        with(binding.mainHolder) {
            onGlobalLayout {
                binding.allAppsFragment.root.setupViews()
                binding.widgetsFragment.root.setupViews()
            }
        }

        ensureBackgroundThread {
            if (IconCache.launchers.isEmpty()) {
                val hiddenIcons = hiddenIconsDB.getHiddenIcons().map {
                    it.getIconIdentifier()
                }

                IconCache.launchers = launchersDB.getAppLaunchers().filter {
                    val showIcon = !hiddenIcons.contains(it.getLauncherIdentifier())
                    if (!showIcon) {
                        try {
                            launchersDB.deleteById(it.id!!)
                        } catch (_: Exception) {
                        }
                    }
                    showIcon
                }.toMutableList() as ArrayList<AppLauncher>
            }

            binding.allAppsFragment.root.gotLaunchers(IconCache.launchers)
            binding.leftPanel.root.gotLaunchers(IconCache.launchers)
            refreshLaunchers()
        }

        binding.defaultLauncherBanner.root.beVisibleIf(!isDefaultLauncher())

        binding.homeScreenGrid.root.resizeGrid(
            newRowCount = config.homeRowCount,
            newColumnCount = config.homeColumnCount
        )
        binding.homeScreenGrid.root.updateColors()
        binding.allAppsFragment.root.onResume()
    }

    override fun onStop() {
        super.onStop()
        try {
            binding.homeScreenGrid.root.appWidgetHost.stopListening()
        } catch (_: Exception) {
        }

        wasJustPaused = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isOreoMr1Plus() && wallpaperColorChangeListener != null) {
            WallpaperManager.getInstance(this)
                .removeOnColorsChangedListener(wallpaperColorChangeListener!!)
        }

        // the infinite chevron animators hold hard references to the rows they drive
        binding.swipeHint.removeCallbacks(mSwipeHintHider)
        mSwipeHintAnimators.forEach { it.cancel() }
        mSwipeHintAnimators.clear()

        homeShellController.onHostDestroy()
    }

    override fun onPause() {
        super.onPause()
        wasJustPaused = true
    }

    override fun onBackPressedCompat(): Boolean {
        return if (isLeftPanelExpanded()) {
            if (binding.leftPanel.root.hasQuery()) {
                binding.leftPanel.root.resetSearch()
            } else {
                hideLeftPanel()
            }
            true
        } else if (isCallerPanelExpanded()) {
            // Let the shell retrace its own tab history first — same contract as the app
            // drawer below. This handler runs *before* the shell's OnBackPressedCallback (the
            // Fossify base registers its callback after ours), so without asking, one back
            // press would close the panel from whichever tab the user was on.
            if (binding.callerPanel.root.shell()?.onBackPressed() != true) {
                hideCallerPanel()
            }
            true
        } else if (isAllAppsFragmentExpanded()) {
            if (!binding.allAppsFragment.root.onBackPressed()) {
                hideFragment(binding.allAppsFragment)
                true
            } else {
                true
            }
        } else if (isWidgetsFragmentExpanded()) {
            if (binding.widgetsFragment.searchBar.isSearchOpen) {
                clearWidgetsSearch()
            } else {
                hideFragment(binding.widgetsFragment)
            }
            true
        } else if (binding.homeScreenGrid.resizeFrame.isVisible) {
            binding.homeScreenGrid.root.hideResizeLines()
            true
        } else {
            // this is a home launcher app, prevent back press from doing anything
            true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        when (requestCode) {
            UNINSTALL_APP_REQUEST_CODE -> {
                ensureBackgroundThread {
                    refreshLaunchers()
                }
            }

            REQUEST_ALLOW_BINDING_WIDGET -> mActionOnCanBindWidget?.invoke(resultCode == RESULT_OK)
            REQUEST_CONFIGURE_WIDGET -> mActionOnWidgetConfiguredWidget?.invoke(resultCode == RESULT_OK)
            REQUEST_CREATE_SHORTCUT -> {
                if (resultCode == RESULT_OK && resultData != null) {
                    val launcherApps =
                        applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
                    if (launcherApps.hasShortcutHostPermission()) {
                        val item = launcherApps.getPinItemRequest(resultData)
                        val shortcutInfo = item?.shortcutInfo ?: return
                        if (item.accept()) {
                            val shortcutId = shortcutInfo.id
                            val label = shortcutInfo.getLabel()
                            val icon = launcherApps.getShortcutBadgedIconDrawable(
                                shortcutInfo,
                                resources.displayMetrics.densityDpi
                            )
                            mActionOnAddShortcut?.invoke(shortcutId, label, icon)
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.allAppsFragment.root.onConfigurationChanged()
        binding.widgetsFragment.root.onConfigurationChanged()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) {
            return false
        }

        if (mLongPressedIcon != null && event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            mLastUpEvent = System.currentTimeMillis()
        }

        try {
            mDetector.onTouchEvent(event)
        } catch (_: Exception) {
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mTouchDownX = event.x.toInt()
                mTouchDownY = event.y.toInt()
                mAllAppsFragmentY = binding.allAppsFragment.root.y.toInt()
                mWidgetsFragmentY = binding.widgetsFragment.root.y.toInt()
                mIgnoreUpEvent = false
            }

            MotionEvent.ACTION_MOVE -> {
                // if the initial gesture was handled by some other view, fix the Down values
                val hasFingerMoved = if (mTouchDownX == -1 || mTouchDownY == -1) {
                    mTouchDownX = event.x.toInt()
                    mTouchDownY = event.y.toInt()
                    false
                } else {
                    hasFingerMoved(event)
                }

                if (mLongPressedIcon != null && (mOpenPopupMenu != null) && hasFingerMoved) {
                    mOpenPopupMenu?.dismiss()
                    mOpenPopupMenu = null
                    binding.homeScreenGrid.root.itemDraggingStarted(mLongPressedIcon!!)
                    hideFragment(binding.allAppsFragment)
                }

                if (mLongPressedIcon != null && hasFingerMoved) {
                    binding.homeScreenGrid.root.draggedItemMoved(event.x.toInt(), event.y.toInt())
                }

                if (hasFingerMoved && !mIgnoreMoveEvents) {
                    val diffY = mTouchDownY - event.y
                    val diffX = mTouchDownX - event.x

                    if (abs(diffY) > abs(diffX) && !mIgnoreYMoveEvents) {
                        mIgnoreXMoveEvents = true
                        if (isWidgetsFragmentExpanded()) {
                            val newY = mWidgetsFragmentY - diffY
                            binding.widgetsFragment.root.y = min(
                                a = max(0f, newY), b = mScreenHeight.toFloat()
                            )
                        } else if (mLongPressedIcon == null) {
                            val newY = mAllAppsFragmentY - diffY
                            binding.allAppsFragment.root.y = min(
                                a = max(0f, newY), b = mScreenHeight.toFloat()
                            )
                        }
                    } else if (abs(diffX) > abs(diffY) && !mIgnoreXMoveEvents) {
                        mIgnoreYMoveEvents = true
                        binding.homeScreenGrid.root.setSwipeMovement(diffX)
                    }
                }

                mLastTouchCoords = Pair(event.x, event.y)
            }

            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                mTouchDownX = -1
                mTouchDownY = -1
                mIgnoreMoveEvents = false
                mLongPressedIcon = null
                mLastTouchCoords = Pair(-1f, -1f)
                resetFragmentTouches()
                binding.homeScreenGrid.root.itemDraggingStopped()

                if (!mIgnoreUpEvent) {
                    if (!mIgnoreYMoveEvents) {
                        if (binding.allAppsFragment.root.y < mScreenHeight * 0.5) {
                            showFragment(binding.allAppsFragment)
                        } else if (isAllAppsFragmentExpanded()) {
                            hideFragment(binding.allAppsFragment)
                        }

                        if (binding.widgetsFragment.root.y < mScreenHeight * 0.5) {
                            showFragment(binding.widgetsFragment)
                        } else if (isWidgetsFragmentExpanded()) {
                            hideFragment(binding.widgetsFragment)
                        }
                    }

                    if (!mIgnoreXMoveEvents) {
                        binding.homeScreenGrid.root.finalizeSwipe()
                    }
                }

                mIgnoreXMoveEvents = false
                mIgnoreYMoveEvents = false
            }
        }

        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(APP_DRAWER_STATE, isAllAppsFragmentExpanded())
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.getBoolean(APP_DRAWER_STATE)) {
            showFragment(binding.allAppsFragment, 0L)
        }
    }

    private fun handleIntentAction(intent: Intent) {
        if (intent.action == LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT) {
            val launcherApps =
                applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
            val item = launcherApps.getPinItemRequest(intent)
            val shortcutInfo = item?.shortcutInfo ?: return

            ensureBackgroundThread {
                val shortcutId = shortcutInfo.id
                val label = shortcutInfo.getLabel()
                val icon = launcherApps.getShortcutBadgedIconDrawable(
                    shortcutInfo,
                    resources.displayMetrics.densityDpi
                )
                val (page, rect) = findFirstEmptyCell()
                val gridItem = HomeScreenGridItem(
                    id = null,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    page = page,
                    packageName = shortcutInfo.`package`,
                    activityName = "",
                    title = label,
                    type = ITEM_TYPE_SHORTCUT,
                    className = "",
                    widgetId = -1,
                    shortcutId = shortcutId,
                    icon = icon.toBitmap(),
                    docked = false,
                    parentId = null,
                    drawable = icon
                )

                runOnUiThread {
                    binding.homeScreenGrid.root.skipToPage(page)
                }
                // delay showing the shortcut both to let the user see adding it in realtime and hackily avoid concurrent modification exception at HomeScreenGrid
                Thread.sleep(2000)

                try {
                    item.accept()
                    binding.homeScreenGrid.root.storeAndShowGridItem(gridItem)
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    private fun findFirstEmptyCell(): Pair<Int, Rect> {
        val gridItems = homeScreenGridItemsDB.getAllItems() as ArrayList<HomeScreenGridItem>
        val maxPage = gridItems.maxOf { it.page }
        val occupiedCells = ArrayList<Triple<Int, Int, Int>>()
        gridItems.toImmutableList().filter { it.parentId == null }.forEach { item ->
            for (xCell in item.left..item.right) {
                for (yCell in item.top..item.bottom) {
                    occupiedCells.add(Triple(item.page, xCell, yCell))
                }
            }
        }

        for (page in 0 until maxPage) {
            for (checkedYCell in 0 until config.homeColumnCount) {
                for (checkedXCell in 0 until config.homeRowCount - 1) {
                    val wantedCell = Triple(page, checkedXCell, checkedYCell)
                    if (!occupiedCells.contains(wantedCell)) {
                        return Pair(
                            first = page,
                            second = Rect(
                                wantedCell.second,
                                wantedCell.third,
                                wantedCell.second,
                                wantedCell.third
                            )
                        )
                    }
                }
            }
        }

        return Pair(maxPage + 1, Rect(0, 0, 0, 0))
    }

    // some devices ACTION_MOVE keeps triggering for the whole long press duration, but we are interested in real moves only, when coords change
    private fun hasFingerMoved(event: MotionEvent): Boolean {
        return mTouchDownX != -1 && mTouchDownY != -1 &&
                (abs(mTouchDownX - event.x) > mMoveGestureThreshold || abs(mTouchDownY - event.y) > mMoveGestureThreshold)
    }

    private fun refreshLaunchers() {
        val launchers = getAllAppLaunchers()
        binding.allAppsFragment.root.gotLaunchers(launchers)
        binding.leftPanel.root.gotLaunchers(launchers)
        binding.widgetsFragment.root.getAppWidgets()

        IconCache.launchers.map { it.packageName }.forEach { packageName ->
            if (!launchers.map { it.packageName }.contains(packageName)) {
                launchersDB.deleteApp(packageName)
                homeScreenGridItemsDB.deleteByPackageName(packageName)
            }
        }

        IconCache.launchers = launchers

        if (!config.wasHomeScreenInit) {
            ensureBackgroundThread {
                getDefaultAppPackages(launchers)
                config.wasHomeScreenInit = true
                seedHomeWidgetsIfNeeded()
                binding.homeScreenGrid.root.fetchGridItems()
            }
        } else if (!config.wasSearchBarSeeded || !config.wasClockSeeded) {
            ensureBackgroundThread {
                seedHomeWidgetsIfNeeded()
                binding.homeScreenGrid.root.fetchGridItems()
            }
        } else {
            binding.homeScreenGrid.root.fetchGridItems()
        }
    }

    fun isAllAppsFragmentExpanded() = binding.allAppsFragment.root.y != mScreenHeight.toFloat()

    private fun isWidgetsFragmentExpanded() =
        binding.widgetsFragment.root.y != mScreenHeight.toFloat()

    fun isLeftPanelExpanded() = binding.leftPanel.root.x != mScreenWidth.toFloat()

    private fun showLeftPanel() {
        // ask for the ad before the slide starts, so it is in place by the time the panel lands
        binding.leftPanel.root.onPanelShown()
        showSidePanel(binding.leftPanel.root)
    }

    /**
     * Opens the app search panel from something other than a fling (the search-bar widget).
     * Unlike the fling this is an explicit "I want to search", so the field takes focus and
     * the keyboard comes up once the panel has finished sliding in.
     */
    fun openAppSearch() {
        showLeftPanel()
        Handler(Looper.getMainLooper()).postDelayed({
            if (isLeftPanelExpanded()) {
                binding.leftPanel.root.focusSearch()
            }
        }, ANIMATION_DURATION)
    }

    /** Opens the clock app behind the home screen clock, falling back to the alarm list. */
    fun openClockApp() {
        val intents = listOf(
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
            Intent(AlarmClock.ACTION_SET_ALARM)
        )

        startFirstResolvable(intents)
    }

    /** Opens the calendar on today, behind the home screen clock's date line. */
    fun openCalendarApp() {
        val todayUri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(System.currentTimeMillis().toString())
            .build()

        val intents = listOf(
            Intent(Intent.ACTION_VIEW, todayUri),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
        )

        startFirstResolvable(intents)
    }

    private fun startFirstResolvable(intents: List<Intent>) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    fun hideLeftPanel() {
        hideSidePanel(binding.leftPanel.root, mScreenWidth.toFloat())
        // clear the query only once it is off screen, else the sections visibly swap mid slide
        Handler(Looper.getMainLooper()).postDelayed({
            binding.leftPanel.root.resetSearch()
            showNextSwipeHint()
        }, ANIMATION_DURATION)
    }

    private fun showSidePanel(panel: View) {
        // Re-apply here, not just in onCreate: on a cold boot straight to this home screen
        // onCreate runs before the Remote Config fetch lands, so there is no palette to copy
        // yet. By the time a panel is opened there is. See [applyNativeAdTheme].
        applyNativeAdTheme()
        hideSwipeHint()
        animateSidePanelTo(panel, 0f)
        window.navigationBarColor = resources.getColor(R.color.semitransparent_navigation)
        binding.homeScreenGrid.root.fragmentExpanded()
        binding.homeScreenGrid.root.hideResizeLines()
        binding.homeScreenGrid.root.animate()
            .alpha(0f)
            .setDuration(ANIMATION_DURATION)
            .start()

        @SuppressLint("AccessibilityFocus")
        panel.performAccessibilityAction(
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        )

        Handler(Looper.getMainLooper()).postDelayed({
            updateStatusBarIcons(getProperBackgroundColor())
        }, ANIMATION_DURATION)
    }

    private fun hideSidePanel(panel: View, parkedX: Float) {
        animateSidePanelTo(panel, parkedX)
        window.navigationBarColor = Color.TRANSPARENT
        binding.homeScreenGrid.root.fragmentCollapsed()
        updateStatusBarIcons()
        hideKeyboard()
    }

    private fun animateSidePanelTo(panel: View, x: Float) {
        ObjectAnimator.ofFloat(panel, "x", x).apply {
            duration = ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    fun startHandlingTouches(touchDownY: Int) {
        mLongPressedIcon = null
        mTouchDownY = touchDownY
        mAllAppsFragmentY = binding.allAppsFragment.root.y.toInt()
        mWidgetsFragmentY = binding.widgetsFragment.root.y.toInt()
        mIgnoreUpEvent = false
    }

    private fun showFragment(fragment: ViewBinding, animationDuration: Long = ANIMATION_DURATION) {
        // The app drawer carries its own native slot — same cold-boot reasoning as
        // [showSidePanel].
        applyNativeAdTheme()
        ObjectAnimator.ofFloat(fragment.root, "y", 0f).apply {
            duration = animationDuration
            interpolator = DecelerateInterpolator()
            start()
        }

        window.navigationBarColor = resources.getColor(R.color.semitransparent_navigation)
        binding.homeScreenGrid.root.fragmentExpanded()
        binding.homeScreenGrid.root.hideResizeLines()

        @SuppressLint("AccessibilityFocus")
        fragment.root.performAccessibilityAction(
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        )

        if (fragment is AllAppsFragmentBinding) {
            // asked on every open so the slot renders whatever has been preloaded since the
            // last one, the same way the side panel refreshes itself
            fragment.root.onDrawerShown()

            if (config.showSearchBar && config.autoShowKeyboardInAppDrawer) {
                fragment.root.post {
                    showKeyboard(fragment.searchBar.binding.topToolbarSearch)
                }
            }
        }

        // fade the grid out behind the fragment, fragmentCollapsed() cancels this and restores it
        binding.homeScreenGrid.root.animate()
            .alpha(0f)
            .setDuration(animationDuration)
            .start()

        Handler(Looper.getMainLooper()).postDelayed({
            updateStatusBarIcons(getProperBackgroundColor())
        }, animationDuration)
    }

    private fun hideFragment(fragment: ViewBinding, animationDuration: Long = ANIMATION_DURATION) {
        ObjectAnimator.ofFloat(fragment.root, "y", mScreenHeight.toFloat()).apply {
            duration = animationDuration
            interpolator = DecelerateInterpolator()
            start()
        }

        window.navigationBarColor = Color.TRANSPARENT
        binding.homeScreenGrid.root.fragmentCollapsed()
        updateStatusBarIcons()
        if (fragment is WidgetsFragmentBinding) {
            clearWidgetsSearch()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (fragment is AllAppsFragmentBinding) {
                fragment.allAppsGrid.scrollToPosition(0)
                fragment.root.touchDownY = -1
            } else if (fragment is WidgetsFragmentBinding) {
                fragment.widgetsList.scrollToPosition(0)
                fragment.root.touchDownY = -1
            }
            // the home screen is bare again, so the next hint can have it
            showNextSwipeHint()
        }, animationDuration)
    }

    fun homeScreenLongPressed(eventX: Float, eventY: Float) {
        if (isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded() || binding.swipeHint.isVisible) {
            return
        }

        val (x, y) = binding.homeScreenGrid.root.intoViewSpaceCoords(eventX, eventY)
        mIgnoreMoveEvents = true
        val clickedGridItem = binding.homeScreenGrid.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            performItemLongClick(x, clickedGridItem)
            return
        }

        binding.mainHolder.performHapticFeedback()
        showMainLongPressMenu(x, y)
    }

    fun homeScreenClicked(eventX: Float, eventY: Float) {
        // While the coach mark is up a tap belongs to it, not to the icon underneath: it does
        // whatever the hint is teaching, so the panel (or the drawer, or the caller-ID app)
        // opens by tap just as it does by swipe.
        if (binding.swipeHint.isVisible) {
            performHintAction()
            return
        }

        binding.homeScreenGrid.root.hideResizeLines()
        val (x, y) = binding.homeScreenGrid.root.intoViewSpaceCoords(eventX, eventY)
        val clickedGridItem = binding.homeScreenGrid.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            performItemClick(clickedGridItem)
        }
        if (clickedGridItem?.type != ITEM_TYPE_FOLDER) {
            binding.homeScreenGrid.root.closeFolder(redraw = true)
        }
    }

    fun homeScreenDoubleTapped(eventX: Float, eventY: Float) {
        if (binding.swipeHint.isVisible) {
            return
        }

        val (x, y) = binding.homeScreenGrid.root.intoViewSpaceCoords(eventX, eventY)
        val clickedGridItem = binding.homeScreenGrid.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            return
        }

        val devicePolicyManager =
            getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isLockDeviceAdminActive = devicePolicyManager.isAdminActive(
            ComponentName(this, LockDeviceAdminReceiver::class.java)
        )
        if (isLockDeviceAdminActive) {
            devicePolicyManager.lockNow()
        }
    }

    fun closeAppDrawer(delayed: Boolean = false) {
        if (isAllAppsFragmentExpanded()) {
            val close = {
                binding.allAppsFragment.root.y = mScreenHeight.toFloat()
                binding.allAppsFragment.allAppsGrid.scrollToPosition(0)
                binding.allAppsFragment.root.touchDownY = -1
                binding.homeScreenGrid.root.fragmentCollapsed()
                updateStatusBarIcons()
            }
            if (delayed) {
                Handler(Looper.getMainLooper()).postDelayed(close, APP_DRAWER_CLOSE_DELAY)
            } else {
                close()
            }
        }
    }

    fun closeWidgetsFragment(delayed: Boolean = false) {
        if (isWidgetsFragmentExpanded()) {
            val close = {
                binding.widgetsFragment.root.y = mScreenHeight.toFloat()
                binding.widgetsFragment.widgetsList.scrollToPosition(0)
                clearWidgetsSearch()
                binding.widgetsFragment.root.touchDownY = -1
                binding.homeScreenGrid.root.fragmentCollapsed()
                updateStatusBarIcons()
            }
            if (delayed) {
                Handler(Looper.getMainLooper()).postDelayed(close, APP_DRAWER_CLOSE_DELAY)
            } else {
                close()
            }
        }
    }

    fun clearWidgetsSearch() {
        binding.widgetsFragment.searchBar.closeSearch()
    }

    private fun performItemClick(clickedGridItem: HomeScreenGridItem) {
        when (clickedGridItem.type) {
            ITEM_TYPE_ICON -> launchApp(clickedGridItem.packageName, clickedGridItem.activityName)
            ITEM_TYPE_FOLDER -> openFolder(clickedGridItem)
            ITEM_TYPE_SHORTCUT -> {
                val id = clickedGridItem.shortcutId
                val packageName = clickedGridItem.packageName
                val userHandle = android.os.Process.myUserHandle()
                val shortcutBounds = binding.homeScreenGrid.root.getClickableRect(clickedGridItem)
                val launcherApps =
                    applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
                launcherApps.startShortcut(packageName, id, shortcutBounds, null, userHandle)
            }
        }
    }

    private fun openFolder(folder: HomeScreenGridItem) {
        binding.homeScreenGrid.root.openFolder(folder)
    }

    private fun performItemLongClick(x: Float, clickedGridItem: HomeScreenGridItem) {
        if (clickedGridItem.type == ITEM_TYPE_ICON || clickedGridItem.type == ITEM_TYPE_SHORTCUT || clickedGridItem.type == ITEM_TYPE_FOLDER) {
            binding.mainHolder.performHapticFeedback()
        }

        val anchorY = binding.homeScreenGrid.root.sideMargins.top +
                (clickedGridItem.top * binding.homeScreenGrid.root.cellHeight.toFloat())
        showHomeIconMenu(x, anchorY, clickedGridItem, false)
    }

    fun showHomeIconMenu(
        x: Float,
        y: Float,
        gridItem: HomeScreenGridItem,
        isOnAllAppsFragment: Boolean,
    ) {
        binding.homeScreenGrid.root.hideResizeLines()
        mLongPressedIcon = gridItem
        val anchorY = if (isOnAllAppsFragment || gridItem.type == ITEM_TYPE_WIDGET) {
            val iconSize = realScreenSize.x / config.drawerColumnCount
            y - iconSize / 2f
        } else {
            val clickableRect = binding.homeScreenGrid.root.getClickableRect(gridItem)
            clickableRect.top.toFloat() - binding.homeScreenGrid.root.getCurrentIconSize() / 2f
        }

        binding.homeScreenPopupMenuAnchor.x = x
        binding.homeScreenPopupMenuAnchor.y = anchorY

        if (mOpenPopupMenu == null) {
            mOpenPopupMenu = handleGridItemPopupMenu(
                anchorView = binding.homeScreenPopupMenuAnchor,
                gridItem = gridItem,
                isOnAllAppsFragment = isOnAllAppsFragment,
                listener = menuListener
            )
        }
    }

    fun widgetLongPressedOnList(gridItem: HomeScreenGridItem) {
        mLongPressedIcon = gridItem
        hideFragment(binding.widgetsFragment)
        binding.homeScreenGrid.root.itemDraggingStarted(mLongPressedIcon!!)
    }

    private fun showMainLongPressMenu(x: Float, y: Float) {
        binding.homeScreenGrid.root.hideResizeLines()
        binding.homeScreenPopupMenuAnchor.x = x
        binding.homeScreenPopupMenuAnchor.y =
            y - resources.getDimension(R.dimen.long_press_anchor_button_offset_y) * 2
        val contextTheme = ContextThemeWrapper(this, getPopupMenuTheme())
        PopupMenu(
            contextTheme,
            binding.homeScreenPopupMenuAnchor,
            Gravity.TOP or Gravity.END
        ).apply {
            inflate(R.menu.menu_home_screen)
            menu.findItem(R.id.set_as_default).isVisible = !isDefaultLauncher()
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.widgets -> showWidgetsFragment()
                    R.id.wallpapers -> launchWallpapersIntent()
                    R.id.launcher_settings -> launchSettings()
                    R.id.set_as_default -> requestSetAsDefaultLauncher()
                }
                true
            }
            show()
        }
    }

    private fun resetFragmentTouches() {
        binding.widgetsFragment.root.apply {
            touchDownY = -1
            ignoreTouches = false
        }

        binding.allAppsFragment.root.apply {
            touchDownY = -1
            ignoreTouches = false
        }
    }

    private fun showWidgetsFragment() {
        showFragment(binding.widgetsFragment)
    }

    private fun hideIcon(item: HomeScreenGridItem) {
        ensureBackgroundThread {
            val hiddenIcon = HiddenIcon(null, item.packageName, item.activityName, item.title, null)
            hiddenIconsDB.insert(hiddenIcon)

            runOnUiThread {
                binding.allAppsFragment.root.onIconHidden(item)
            }
        }
    }

    private fun renameItem(homeScreenGridItem: HomeScreenGridItem) {
        RenameItemDialog(this, homeScreenGridItem) {
            binding.homeScreenGrid.root.fetchGridItems()
        }
    }

    private fun launchWallpapersIntent() {
        try {
            Intent(Intent.ACTION_SET_WALLPAPER).apply {
                startActivity(this)
            }
        } catch (_: ActivityNotFoundException) {
            toast(org.fossify.commons.R.string.no_app_found)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun launchSettings() {
        startActivity(
            Intent(this@MainActivity, SettingsActivity::class.java)
        )
    }

    val menuListener: ItemMenuListener = object : ItemMenuListener {
        override fun onAnyClick() {
            resetFragmentTouches()
        }

        override fun hide(gridItem: HomeScreenGridItem) {
            hideIcon(gridItem)
        }

        override fun rename(gridItem: HomeScreenGridItem) {
            renameItem(gridItem)
        }

        override fun resize(gridItem: HomeScreenGridItem) {
            binding.homeScreenGrid.root.widgetLongPressed(gridItem)
        }

        override fun appInfo(gridItem: HomeScreenGridItem) {
            launchAppInfo(gridItem.packageName)
        }

        override fun remove(gridItem: HomeScreenGridItem) {
            binding.homeScreenGrid.root.removeAppIcon(gridItem)
        }

        override fun uninstall(gridItem: HomeScreenGridItem) {
            uninstallApp(gridItem.packageName)
        }

        override fun onDismiss() {
            mOpenPopupMenu = null
            resetFragmentTouches()
        }

        override fun beforeShow(menu: Menu) {
            var visibleMenuItems = 0
            for (item in menu.iterator()) {
                if (item.isVisible) {
                    visibleMenuItems++
                }
            }
            val yOffset =
                resources.getDimension(R.dimen.long_press_anchor_button_offset_y) * (visibleMenuItems - 1)
            binding.homeScreenPopupMenuAnchor.y -= yOffset
        }
    }

    private class MyGestureListener(
        private val flingListener: FlingListener,
    ) : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            (flingListener as MainActivity).homeScreenClicked(event.x, event.y)
            return super.onSingleTapUp(event)
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            (flingListener as MainActivity).homeScreenDoubleTapped(event.x, event.y)
            return super.onDoubleTap(event)
        }

        override fun onFling(
            event1: MotionEvent?,
            event2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            // ignore fling events just after releasing an icon from dragging
            if (System.currentTimeMillis() - mLastUpEvent < 500L) {
                return true
            }

            if (abs(velocityY) > abs(velocityX)) {
                if (velocityY > 0) {
                    flingListener.onFlingDown()
                } else {
                    flingListener.onFlingUp()
                }
            } else if (abs(velocityX) > abs(velocityY)) {
                if (velocityX > 0) {
                    flingListener.onFlingRight()
                } else {
                    flingListener.onFlingLeft()
                }
            }

            return true
        }

        override fun onLongPress(event: MotionEvent) {
            (flingListener as MainActivity).homeScreenLongPressed(event.x, event.y)
        }
    }

    override fun onFlingUp() {
        if (mIgnoreYMoveEvents) {
            return
        }

        if (!isWidgetsFragmentExpanded()) {
            mIgnoreUpEvent = true
            completeSwipeHint(LauncherAdsConfig.HintDirection.UP)
            showFragment(binding.allAppsFragment)
        }
    }

    @SuppressLint("WrongConstant")
    override fun onFlingDown() {
        if (mIgnoreYMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        if (isAllAppsFragmentExpanded()) {
            hideFragment(binding.allAppsFragment)
        } else if (isWidgetsFragmentExpanded()) {
            hideFragment(binding.widgetsFragment)
        } else {
            completeSwipeHint(LauncherAdsConfig.HintDirection.DOWN)
            try {
                Class.forName("android.app.StatusBarManager")
                    .getMethod("expandNotificationsPanel")
                    .invoke(getSystemService("statusbar"))
            } catch (_: Exception) {
            }
        }
    }

    override fun onFlingRight() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        // A right fling opens the caller-ID panel, whichever page we are on. Paging is still
        // available by dragging horizontally, which never reaches here.
        if (!isAllAppsFragmentExpanded() && !isWidgetsFragmentExpanded()) {
            completeSwipeHint(LauncherAdsConfig.HintDirection.RIGHT)
            // Paced by launcher_ads.swipe_right; the panel opens on every path regardless.
            LauncherAdsConfig.run(this, LauncherAdsConfig.Surface.SWIPE_RIGHT) {
                showCallerPanel()
            }
        } else {
            binding.homeScreenGrid.root.prevPage(redraw = true)
        }
    }

    override fun onFlingLeft() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        // see onFlingRight: the panel wins over paging on a fling
        if (!isAllAppsFragmentExpanded() && !isWidgetsFragmentExpanded()) {
            completeSwipeHint(LauncherAdsConfig.HintDirection.LEFT)
            // Paced by launcher_ads.swipe_left; the panel opens on every path regardless.
            LauncherAdsConfig.run(this, LauncherAdsConfig.Surface.SWIPE_LEFT) {
                showLeftPanel()
            }
        } else {
            binding.homeScreenGrid.root.nextPage(redraw = true)
        }
    }

    fun isCallerPanelExpanded() = binding.callerPanel.root.x != -mScreenWidth.toFloat()

    /** Opens the caller panel from outside the fling gesture (deep links, widgets). */
    fun showCallerPanelExternally() = showCallerPanel()

    /**
     * Swipe right slides the caller-ID app's own home UI in over the grid. It is a panel in
     * this Activity rather than a separate task, so Back closes it and Home never has to
     * unwind another task — and the shell keeps its tab and scroll position between opens.
     */
    private fun showCallerPanel() {
        showSidePanel(binding.callerPanel.root)
        // Everything the panel shows *over itself* waits for the slide to finish — fired at
        // the start it would land over the home grid the panel is still covering.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isCallerPanelExpanded()) return@postDelayed
            binding.callerPanel.root.onPanelOpened()
            binding.callerPanel.root.shell()?.setPanelVisible(true)
            if (LauncherFlow.wasOnboardingCompleted(this)) {
                homeShellController.startFirstRunPriming()
            }
        }, ANIMATION_DURATION)
    }

    fun hideCallerPanel() {
        // Disabling the shell's back callback before the slide keeps it from swallowing the
        // next back press — the drawer and the grid own those again once the panel is gone.
        binding.callerPanel.root.shell()?.setPanelVisible(false)
        hideSidePanel(binding.callerPanel.root, -mScreenWidth.toFloat())
        // Back on the grid: offer the next gesture the user has not been taught yet.
        Handler(Looper.getMainLooper()).postDelayed({ showNextSwipeHint() }, ANIMATION_DURATION)
    }

    @SuppressLint("WrongConstant")
    fun getAllAppLaunchers(): ArrayList<AppLauncher> {
        val hiddenIcons = hiddenIconsDB.getHiddenIcons().map {
            it.getIconIdentifier()
        }

        val allApps = ArrayList<AppLauncher>()
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

        val simpleLauncher = applicationContext.packageName
        val microG = "com.google.android.gms"
        val list = packageManager.queryIntentActivities(intent, PackageManager.PERMISSION_GRANTED)
        for (info in list) {
            val componentInfo = info.activityInfo.applicationInfo
            val packageName = componentInfo.packageName
            if (packageName == simpleLauncher || packageName == microG) {
                continue
            }

            val activityName = info.activityInfo.name
            if (hiddenIcons.contains("$packageName/$activityName")) {
                continue
            }

            val label = info.loadLabel(packageManager).toString()
            val drawable = info.loadIcon(packageManager)
                ?: getDrawableForPackageName(packageName)
                ?: continue

            val bitmap = drawable.toBitmap(
                width = max(drawable.intrinsicWidth, 1),
                height = max(drawable.intrinsicHeight, 1),
                config = Bitmap.Config.ARGB_8888
            )
            val placeholderColor = calculateAverageColor(bitmap)
            allApps.add(
                AppLauncher(
                    id = null,
                    title = label,
                    packageName = packageName,
                    activityName = activityName,
                    order = 0,
                    thumbnailColor = placeholderColor,
                    drawable = bitmap.toDrawable(resources)
                )
            )
        }

        launchersDB.insertAll(allApps)
        return allApps
    }

    // matches the reference app, which has the clock and the search pill sitting on the home
    // screen from the start rather than requiring them to be added from the widgets picker.
    // Kept independent of wasHomeScreenInit / getDefaultAppPackages so it also backfills
    // installs that already ran through that one-time seeding before these existed.
    private fun seedHomeWidgetsIfNeeded() {
        val needsSearchBar = !config.wasSearchBarSeeded
        val needsClock = !config.wasClockSeeded
        if (!needsSearchBar && !needsClock) {
            return
        }

        // one shot either way, a user who removes them is not supposed to get them back
        config.wasSearchBarSeeded = true
        config.wasClockSeeded = true

        try {
            val lastColumn = config.homeColumnCount - 1
            // the bottom row is the dock, the header may not eat everything above it
            val headerFits = config.homeRowCount - 1 > SEARCH_BAR_ROW

            val pageItems = homeScreenGridItemsDB.getAllItems()
                .filter { it.page == 0 && !it.docked && it.parentId == null }
            val searchBar = pageItems.firstOrNull { it.className == PSEUDO_WIDGET_SEARCH }
            val clock = pageItems.firstOrNull { it.className == PSEUDO_WIDGET_CLOCK }
            // whatever the user placed up there wins, we only seed into rows that are still empty
            val headerRowsFree = pageItems
                .filter { it.id != searchBar?.id && it.id != clock?.id }
                .none { item -> (0..SEARCH_BAR_ROW).any { it in item.top..item.bottom } }

            if (!headerFits || !headerRowsFree) {
                // no room for the full header, fall back to the pill alone at the top
                if (searchBar == null) {
                    insertPseudoWidget(
                        className = PSEUDO_WIDGET_SEARCH,
                        titleRes = R.string.pseudo_widget_search_bar,
                        left = 0,
                        top = 0,
                        right = min(3, lastColumn),
                        bottom = 0
                    )
                }

                return
            }

            if (clock == null) {
                insertPseudoWidget(
                    className = PSEUDO_WIDGET_CLOCK,
                    titleRes = R.string.pseudo_widget_clock,
                    left = 0,
                    top = 0,
                    right = lastColumn,
                    bottom = CLOCK_ROW_SPAN - 1
                )
            }

            if (searchBar == null) {
                insertPseudoWidget(
                    className = PSEUDO_WIDGET_SEARCH,
                    titleRes = R.string.pseudo_widget_search_bar,
                    left = 0,
                    top = SEARCH_BAR_ROW,
                    right = lastColumn,
                    bottom = SEARCH_BAR_ROW
                )
            } else {
                // an older install already has the pill in the row the clock now wants
                homeScreenGridItemsDB.updateItemPosition(
                    left = 0,
                    top = SEARCH_BAR_ROW,
                    right = lastColumn,
                    bottom = SEARCH_BAR_ROW,
                    page = 0,
                    docked = false,
                    parentId = null,
                    id = searchBar.id!!
                )
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to seed default home widgets", e)
        }
    }

    private fun insertPseudoWidget(
        className: String,
        titleRes: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val item = HomeScreenGridItem(
            id = null,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            page = 0,
            packageName = packageName,
            activityName = "",
            title = getString(titleRes),
            type = ITEM_TYPE_WIDGET,
            className = className,
            widgetId = binding.homeScreenGrid.root.appWidgetHost.allocateAppWidgetId(),
            shortcutId = "",
            icon = null,
            docked = false,
            parentId = null
        )

        homeScreenGridItemsDB.insert(item)
    }

    private fun getDefaultAppPackages(appLaunchers: ArrayList<AppLauncher>) {
        val homeScreenGridItems = ArrayList<HomeScreenGridItem>()

        try {
            val defaultDialerPackage =
                (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
            appLaunchers.firstOrNull { it.packageName == defaultDialerPackage }?.apply {
                val dialerIcon =
                    HomeScreenGridItem(
                        id = null,
                        left = 0,
                        top = config.homeRowCount - 1,
                        right = 0,
                        bottom = config.homeRowCount - 1,
                        page = 0,
                        packageName = defaultDialerPackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                homeScreenGridItems.add(dialerIcon)
            }
        } catch (_: Exception) {
        }

        try {
            val defaultSMSMessengerPackage = Telephony.Sms.getDefaultSmsPackage(this)
            appLaunchers.firstOrNull { it.packageName == defaultSMSMessengerPackage }?.apply {
                val messengerIcon =
                    HomeScreenGridItem(
                        id = null,
                        left = 1,
                        top = config.homeRowCount - 1,
                        right = 1,
                        bottom = config.homeRowCount - 1,
                        page = 0,
                        packageName = defaultSMSMessengerPackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                homeScreenGridItems.add(messengerIcon)
            }
        } catch (_: Exception) {
        }

        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, "http://".toUri())
            val resolveInfo =
                packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultBrowserPackage = resolveInfo!!.activityInfo.packageName
            appLaunchers.firstOrNull { it.packageName == defaultBrowserPackage }?.apply {
                val browserIcon =
                    HomeScreenGridItem(
                        id = null,
                        left = 2,
                        top = config.homeRowCount - 1,
                        right = 2,
                        bottom = config.homeRowCount - 1,
                        page = 0,
                        packageName = defaultBrowserPackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                homeScreenGridItems.add(browserIcon)
            }
        } catch (_: Exception) {
        }

        try {
            val potentialStores = arrayListOf(
                "com.android.vending", "org.fdroid.fdroid", "com.aurora.store"
            )
            val storePackage = potentialStores.firstOrNull {
                isPackageInstalled(it) && appLaunchers.map { it.packageName }.contains(it)
            }
            if (storePackage != null) {
                appLaunchers.firstOrNull { it.packageName == storePackage }?.apply {
                    val storeIcon = HomeScreenGridItem(
                        id = null,
                        left = 3,
                        top = config.homeRowCount - 1,
                        right = 3,
                        bottom = config.homeRowCount - 1,
                        page = 0,
                        packageName = storePackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                    homeScreenGridItems.add(storeIcon)
                }
            }
        } catch (_: Exception) {
        }

        try {
            val cameraIntent = Intent("android.media.action.IMAGE_CAPTURE")
            val resolveInfo =
                packageManager.resolveActivity(cameraIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultCameraPackage = resolveInfo!!.activityInfo.packageName
            appLaunchers.firstOrNull { it.packageName == defaultCameraPackage }?.apply {
                val cameraIcon =
                    HomeScreenGridItem(
                        id = null,
                        left = 4,
                        top = config.homeRowCount - 1,
                        right = 4,
                        bottom = config.homeRowCount - 1,
                        page = 0,
                        packageName = defaultCameraPackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                homeScreenGridItems.add(cameraIcon)
            }
        } catch (_: Exception) {
        }

        homeScreenGridItemsDB.insertAll(homeScreenGridItems)
    }

    fun handleWidgetBinding(
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        appWidgetInfo: AppWidgetProviderInfo,
        callback: (canBind: Boolean) -> Unit,
    ) {
        mActionOnCanBindWidget = null
        val canCreateWidget =
            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, appWidgetInfo.provider)
        if (canCreateWidget) {
            callback(true)
        } else {
            mActionOnCanBindWidget = callback
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, appWidgetInfo.provider)
                startActivityForResult(this, REQUEST_ALLOW_BINDING_WIDGET)
            }
        }
    }

    fun handleWidgetConfigureScreen(
        appWidgetHost: AppWidgetHost,
        appWidgetId: Int,
        callback: (canBind: Boolean) -> Unit,
    ) {
        mActionOnWidgetConfiguredWidget = callback
        appWidgetHost.startAppWidgetConfigureActivityForResult(
            this,
            appWidgetId,
            0,
            REQUEST_CONFIGURE_WIDGET,
            null
        )
    }

    fun handleShorcutCreation(
        activityInfo: ActivityInfo,
        callback: (shortcutId: String, label: String, icon: Drawable) -> Unit,
    ) {
        mActionOnAddShortcut = callback
        val componentName = ComponentName(activityInfo.packageName, activityInfo.name)
        Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
            component = componentName
            startActivityForResult(this, REQUEST_CREATE_SHORTCUT)
        }
    }

    private fun updateStatusBarIcons(backgroundColor: Int? = null) {
        val isLightBackground = when {
            backgroundColor != null -> backgroundColor.getContrastColor() == DARK_GREY
            wallpaperSupportsDarkText != null -> wallpaperSupportsDarkText!!
            else -> {
                refreshWallpaperSupportsDarkText()
                wallpaperSupportsDarkText ?: false
            }
        }
        window.insetsController().apply {
            isAppearanceLightStatusBars = isLightBackground
            isAppearanceLightNavigationBars = isLightBackground
        }
    }

    // taken from https://gist.github.com/maxjvh/a6ab15cbba9c82a5065d
    private fun calculateAverageColor(bitmap: Bitmap): Int {
        var red = 0
        var green = 0
        var blue = 0
        val height = bitmap.height
        val width = bitmap.width
        var n = 0
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var i = 0
        while (i < pixels.size) {
            val color = pixels[i]
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
            n++
            i += 1
        }

        return Color.rgb(red / n, green / n, blue / n)
    }
}
