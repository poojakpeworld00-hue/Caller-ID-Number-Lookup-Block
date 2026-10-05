package io.launcher.home.activities

import timber.log.Timber
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.app.WallpaperManager.OnColorsChangedListener
import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.util.Log
import android.util.Property
import android.appwidget.AppWidgetProviderInfo
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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.GestureDetector
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.viewbinding.ViewBinding
import io.launcher.home.helpers.FIRST_APPS_PAGE
import kotlinx.collections.immutable.toImmutableList
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.insetsController
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.extensions.realScreenSize
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.DARK_GREY
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_READ_SMS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isOreoMr1Plus
import io.launcher.home.R
import io.launcher.home.databinding.LnchActivityMainBinding
import io.launcher.home.databinding.LnchAllAppsFragmentBinding
import io.launcher.home.databinding.LnchWidgetsFragmentBinding
import io.launcher.home.api.LauncherRegistry
import io.launcher.home.motion.LauncherBannerMotion
import io.launcher.home.dialogs.RenameItemTray
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.extensions.getLabel
import io.launcher.home.extensions.handleGridItemPopupMenu
import io.launcher.home.dialogs.ItemActionsTray
import io.launcher.home.extensions.hiddenIconsDB
import io.launcher.home.extensions.homeScreenGridItemsDB
import io.launcher.home.extensions.isDefaultLauncher
import io.launcher.home.extensions.launchApp
import io.launcher.home.extensions.launchAppInfo
import io.launcher.home.extensions.launchersDB
import io.launcher.home.extensions.supportsDarkText
import io.launcher.home.extensions.uninstallApp
import io.launcher.home.fragments.LauncherSurface
import io.launcher.home.helpers.DefaultLauncherRequester
import io.launcher.home.helpers.ITEM_TYPE_FOLDER
import io.launcher.home.helpers.ITEM_TYPE_ICON
import io.launcher.home.helpers.ITEM_TYPE_SHORTCUT
import io.launcher.home.helpers.ITEM_TYPE_WIDGET
import io.launcher.home.helpers.IconCache
import io.launcher.home.helpers.LauncherScan
import io.launcher.home.helpers.PSEUDO_WIDGET_SEARCH
import io.launcher.home.helpers.REQUEST_ALLOW_BINDING_WIDGET
import io.launcher.home.helpers.REQUEST_CONFIGURE_WIDGET
import io.launcher.home.helpers.REQUEST_CREATE_SHORTCUT
import io.launcher.home.helpers.REQUEST_DEFAULT_SMS
import io.launcher.home.helpers.PACKAGE_REFRESH_DELAY_MS
import io.launcher.home.helpers.UNINSTALL_WATCH_MS
import io.launcher.home.helpers.UNINSTALL_WATCH_STEP_MS
import io.launcher.home.helpers.UNINSTALL_APP_REQUEST_CODE
import io.launcher.home.helpers.SelfRemoval
import io.launcher.home.promo.LauncherAdsConfig
import io.launcher.home.promo.LauncherGuideStep
import io.launcher.home.promo.LauncherPromoController
import io.launcher.home.interfaces.FlingListener
import io.launcher.home.profile.HomeAppsFiller
import io.launcher.home.profile.HomeSeeder
import io.launcher.home.profile.HomeWidgetSeeder
import io.launcher.home.profile.HomeProfileApplier
import io.launcher.home.profile.LauncherFingerprint
import io.launcher.home.interfaces.ItemMenuListener
import io.launcher.home.models.AppLauncher
import io.launcher.home.models.HiddenIcon
import io.launcher.home.models.HomeScreenGridItem
import io.launcher.home.receivers.LockDeviceAdminReceiver
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class LauncherPanel : LauncherBasePanel(), FlingListener {

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
    // Where this gesture started and the leftmost / rightmost x it reached, to tell a pull-back
    // from a lift wobble and a quick swipe from a slow drag.
    private var mGestureDownX = 0f
    private var mGestureMinX = 0f
    private var mGestureMaxX = 0f
    private val mTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    // A page swipe faster than this on average (px per ms, ~400dp/s) turns the page on release.
    private val mQuickSwipeSpeed by lazy { 0.4f * resources.displayMetrics.density }
    // Config.isDrawerEnabled, read once per resume rather than on every touch move.
    private var mDrawerEnabled = true
    private var mLongPressedIcon: HomeScreenGridItem? = null
    private var mOpenPopupMenu: ItemActionsTray? = null
    private var mLastTouchCoords = Pair(-1f, -1f)
    private var mActionOnCanBindWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnWidgetConfiguredWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnAddShortcut:
            ((shortcutId: String, label: String, icon: Drawable) -> Unit)? = null
    private var mActionOnDefaultSmsResult: (() -> Unit)? = null
    private var wasJustPaused: Boolean = false

    /** Set by a Home press in onNewIntent, read (and cleared) by the onResume that follows it. */
    private var mUnwindingFromHomePress = false

    private var mSwipeHintAnimator: ObjectAnimator? = null

    /** Cached answer to "is the Home role still unclaimed" — see [refreshDefaultLauncherBanner]. */
    private var needsDefaultLauncherBanner = false

    /** Cached prompt gate — see [refreshDefaultLauncherBanner] for why it is not read per call. */
    private var mDefaultLauncherPromptEnabled = true

    /**
     * The side panel on screen, or null when neither is — set the moment one starts opening and
     * cleared once it has finished closing.
     *
     * Tracked rather than derived from the panels' `x`, which is what [isLeftPanelExpanded] and
     * [isHostPanelExpanded] read, because a panel opens on a **fling** with no preceding drag:
     * nothing has moved `x` when [showSidePanel] runs — `ObjectAnimator.start()` applies its first
     * value on the next frame — so a position check there still reports "closed", and the alert would
     * stay up for a panel it is legible straight through. The drawer has no such problem: the drag
     * that precedes its fling has already moved `y`.
     */
    private var mOpenSidePanel: View? = null

    /**
     * A console change activated mid-session — see `ConfigManager.startRealtimeUpdates`.
     *
     * The launcher reads `launcher.*` through `AppConfig` at the moment it needs an answer, so most
     * of it is already current by the time this runs. The two exceptions are what this handles: the
     * prompt gate, which is deliberately cached off the touch path, and the drawer's list, which
     * fixes the ad row's position as it is built and so keeps the old one until it is built again.
     *
     * The drawer is left alone while it is on screen. Resubmitting a list under a scrolling finger
     * to move an ad row is a worse experience than the row moving on the next open, which is at
     * most one gesture away.
     */
    /**
     * A panel that `launcher_config.panels` has just switched off must not stay on screen: the
     * gesture gates only stop it opening, so one already open would otherwise be stuck there.
     */
    private fun closeDisabledPanels() {
        if (isLeftPanelExpanded() && !LauncherAdsConfig.panelEnabled(LauncherAdsConfig.LEFT_SWIPE)) {
            hideLeftPanel()
        }
        if (isHostPanelExpanded() && !LauncherAdsConfig.panelEnabled(LauncherAdsConfig.RIGHT_SWIPE)) {
            hideHostPanel()
        }
    }

    /**
     * Re-applies what the launcherConfig drives. The host calls it when a new config has been
     * ingested while this screen is in front (realtime update, rc_sync push).
     */
    fun onConfigUpdated() {
        if (isFinishing || isDestroyed) {
            return
        }

        refreshDefaultLauncherBanner()
        closeDisabledPanels()

        if (!isAllAppsFragmentExpanded() && IconCache.launchers.isNotEmpty()) {
            // Same apps, but the config may have moved rows (the ad row): resubmit regardless.
            binding.allAppsFragmentUi.root.gotLaunchers(IconCache.launchers, force = true)
        }

        // The update gate is asked on resume, and on a cold start that resume can easily beat the
        // first activated fetch — the launcher is up in well under a second, and until Firebase has
        // something the answer is an honest "nothing is configured". This is the other half of the
        // question: a value that lands while the user is already on the workspace takes effect now
        // rather than on the next time they happen to leave and come back.
    }

    private val defaultLauncherRequester by lazy {
        // Granting the role is what retires the card, and the requester is the only thing that
        // notices it the moment it happens rather than on the next resume.
        DefaultLauncherRequester(this) { refreshDefaultLauncherBanner() }
    }

    private var wallpaperColorChangeListener: OnColorsChangedListener? = null
    private var wallpaperSupportsDarkText: Boolean? = null

    private lateinit var mDetector: GestureDetectorCompat
    private val binding by viewBinding(LnchActivityMainBinding::inflate)

    companion object {
        /** Boolean intent extra: open with the inbox panel slid in (the funnel's HOME, see OnboardingRoute). */
        const val EXTRA_OPEN_HOST_PANEL = "open_host_panel"

        /** Dock column of the messaging app (Phone · Messages · Browser · Camera). */
        private const val DOCK_HOST_SLOT = 1
        private const val PLAY_STORE_PACKAGE = "com.android.vending"

        private var mLastUpEvent = 0L
        private const val ANIMATION_DURATION = 150L
        private const val APP_DRAWER_CLOSE_DELAY = 300L
        private const val APP_DRAWER_STATE = "app_drawer_state"
        private const val SWIPE_HINT_ANIMATION_DURATION = 900L

        /** Long enough for the panel slide and any promo ad to have settled. */
        private const val PERMISSION_SHEET_DELAY = 500L

        /** Past the guide's first post and the entrance frames; well under any realistic first swipe. */
        private const val HOST_PANEL_WARM_UP_DELAY = 250L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        useDynamicTheme = false

        super.onCreate(savedInstanceState)

        // The host gets first refusal on this launch. A host with its own first-run flow takes the
        // foreground back here and the workspace is never built — taking the Home role mid-funnel
        // makes the app the launcher, so the system fires HOME and starts *this*, and the host's
        // own Activity is never resumed to notice. Asked before any setup work, because everything
        // below would otherwise be building a screen nobody sees.
        if (!LauncherRegistry.bridge.onLauncherStart(this)) {
            return
        }

        // The layout launcher_config.os_style asks for - the previous launcher's or our own - is
        // applied before the workspace inflates and reads the row/column counts. No-op while the
        // style is unchanged; a switch on a built home is rebuilt by refreshLaunchers.
        HomeProfileApplier.applyStyle(this)
        HomeProfileApplier.refreshIconSizes(this)
        mDrawerEnabled = launcherConfig.isDrawerEnabled

        setContentView(binding.root)
        // An app installed, removed or updated anywhere - Play, Settings, our own menu - reaches
        // the drawer and the home screen at once, not on the next Home press.
        getSystemService(LauncherApps::class.java)?.registerCallback(packageCallback, Handler(Looper.getMainLooper()))
        appLaunched(packageName)
        setupEdgeToEdge(
            padTopSystem = listOf(
                binding.allAppsFragmentUi.root,
                binding.widgetsFragmentUi.root,
                binding.leftPanelUi.root,
                // Not the host panel: the host's screens pad their own headers for the status
                // bar (edge-to-edge, as in the app itself), so padding the panel too doubled the
                // gap above them.
                binding.defaultLauncherBannerUi.root
            ),
            padBottomImeAndSystem = listOf(
                binding.allAppsFragmentUi.allAppsGridUi,
                binding.allAppsFragmentUi.allAppsPageDotsUi,
                binding.widgetsFragmentUi.widgetsListUi,
                binding.leftPanelUi.panelScrollUi,
                // HomeShellFragment (the inbox) is committed into this
                binding.hostPanelUi.hostPanelContainerUi
            ),
            // The apps panel's bottom native slot is a sibling of its scroll view, pinned to the
            // panel's bottom edge — so the scroll view's inset above does not cover it and the ad
            // draws underneath the navigation bar. It takes the inset itself instead.
            padBottomSystem = listOf(
                binding.homeScreenGridUi.root,
                binding.leftPanelUi.panelNativeFrameUi
            )
        )

        mDetector = GestureDetectorCompat(this, MyGestureListener(this))

        mScreenHeight = realScreenSize.y
        mScreenWidth = realScreenSize.x
        mAllAppsFragmentY = mScreenHeight
        mWidgetsFragmentY = mScreenHeight
        mMoveGestureThreshold = resources.getDimensionPixelSize(R.dimen.launcher_move_gesture_threshold)

        arrayOf(
            binding.allAppsFragmentUi.root as LauncherSurface<*>,
            binding.widgetsFragmentUi.root as LauncherSurface<*>
        ).forEach { fragment ->
            fragment.setupFragment(this)
            fragment.y = mScreenHeight.toFloat()
            fragment.beVisible()
        }

        // the side panels are parked off screen horizontally instead, each just past the edge
        // it slides in from: app search on the right, messages on the left
        binding.leftPanelUi.root.apply {
            setupFragment(this@LauncherPanel)
            x = mScreenWidth.toFloat()
            beVisible()
        }

        binding.hostPanelUi.root.apply {
            setupFragment(this@LauncherPanel)
            x = -mScreenWidth.toFloat()
            beVisible()
        }

        handleIntentAction(intent)

        binding.homeScreenGridUi.root.itemClickListener = {
            performItemClick(it)
        }

        binding.homeScreenGridUi.root.itemLongClickListener = {
            performItemLongClick(
                x = binding.homeScreenGridUi.root.getClickableRect(it).left.toFloat(),
                clickedGridItem = it
            )
        }

        setupDefaultLauncherBanner(savedInstanceState)
        setupWallpaperColorListener()

        // A host that wants the user to land *inside* its panel rather than on the grid - the end
        // of a first-run flow, say - starts this Activity with EXTRA_OPEN_HOST_PANEL. Only on a
        // fresh create: a recreate hands the same intent back, and the panel state it had is
        // restored on its own.
        if (savedInstanceState == null) openHostPanelIfAsked(intent)

        // Last, and only on the path where the workspace was actually built — the early return
        // above hands the foreground back to onboarding, and a coach mark over a screen the user is
        // about to be taken off would be pointing at nothing.
        //
        // Posted so the first step is measured against a laid-out workspace.
        binding.mainHolderUi.post { maybeShowGuide() }

        // After the workspace has had its first frames, not inside them: the inbox commit is the
        // single most expensive thing this Activity does, and a Home press should paint the grid
        // first. See HostPanelSurface.warmUp for why it is here at all. Past the delay it still
        // waits for the main thread to go idle - on a cold start that first second is already
        // full of layout passes and the SDKs' own posts, and the commit on top of them was a
        // 1.3 s frame with the grid frozen under the user's finger.
        binding.mainHolderUi.postDelayed({
            Looper.myQueue().addIdleHandler {
                // A host panel switched off by launcher_config is never committed at all; if the
                // config turns it on later, showHostPanel's refresh() builds it on first open.
                if (!isFinishing && !isDestroyed &&
                    LauncherAdsConfig.panelEnabled(LauncherAdsConfig.RIGHT_SWIPE)
                ) binding.hostPanelUi.root.warmUp()
                false
            }
        }, HOST_PANEL_WARM_UP_DELAY)
    }

    /**
     * onboarding already asks for the Home role explicitly on first run, with a Skip option. If the
     * user skipped it or later unset us, this card nags passively instead of the system dialog the
     * launcher used to fire straight from [onCreate] on every single launch. It stays reachable from
     * the long-press menu either way.
     */
    private fun setupDefaultLauncherBanner(savedInstanceState: Bundle?) {
        // Before the first tap can happen: a low-memory kill while the user was in Settings must
        // resume knowing it still owes them the escalation, not start the sequence over.
        defaultLauncherRequester.restoreState(savedInstanceState)
        binding.defaultLauncherBannerUi.root.setOnClickListener { defaultLauncherRequester.start() }
        // The card sits at the top of every page and the grid makes room for it (syncGridTopReserve),
        // so it is not moved with the time widget or the first page.
        LauncherBannerMotion.bind(binding.defaultLauncherBannerUi)
        // Its height is only known once laid out (text wraps per width and language), so the grid's
        // reserve follows every layout pass rather than a guessed constant.
        binding.defaultLauncherBannerUi.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncGridTopReserve()
        }
        refreshDefaultLauncherBanner()
    }

    /**
     * Keeps the first row of the home grid clear of the "Setup Not Complete" card.
     *
     * Driven by whether the card belongs on the workspace at all, not by its momentary visibility:
     * the card hides while the drawer or a side panel is open, and re-flowing the grid on every such
     * frame would redraw the whole workspace during a drag for a screen nobody can see.
     */
    private fun syncGridTopReserve() {
        val banner = binding.defaultLauncherBannerUi.root
        val wanted = needsDefaultLauncherBanner && mDefaultLauncherPromptEnabled
        val grid = binding.homeScreenGridUi.root
        grid.topReserve = when {
            !wanted -> 0
            // The banner is padded by the status-bar inset, which the grid already accounts for.
            banner.height > 0 -> (banner.height - banner.paddingTop).coerceAtLeast(0)
            else -> grid.topReserve
        }
    }

    /**
     * Holding the role hides the card for good — granting it from anywhere (this card, the long-press
     * menu, system settings) retires the prompt.
     *
     * Answering "does the role still need asking for" is a PackageManager round trip, so the answer
     * is cached here and the per-frame question — "is anything covering the workspace right now" — is
     * left to [updateDefaultLauncherBannerVisibility].
     */
    private fun refreshDefaultLauncherBanner() {
        needsDefaultLauncherBanner = !isDefaultLauncher()
        // Cached alongside the role check and for the same reason: the visibility update runs on the
        // touch path during a drawer drag, and resolving the gate there would put a launcherConfig read on
        // every move event for an answer that changes at most once per fetch.
        mDefaultLauncherPromptEnabled = LauncherAdsConfig.defaultLauncherPromptEnabled()
        updateDefaultLauncherBannerVisibility()
        syncGridTopReserve()
    }

    /**
     * The card belongs to the workspace, so it is only ever shown when the workspace is what the user
     * is looking at — and it is shown *whenever* that is true and the Home role is not held. There is
     * no dismiss: it is not modal, it sits at the top of the grid and waits to be tapped.
     *
     * Three things take it away, and each is "something else is on screen":
     *
     *  - the app drawer or the widgets sheet, which slide *over* the workspace rather than replacing
     *    it,
     *  - either side panel ([mOpenSidePanel]), which likewise covers the workspace without replacing
     *    it. The reference app needs no such term because its panel controller translates the card
     *    off screen along with the grid; ours parks the panels over a stationary grid and fades only
     *    the grid, and the card is the grid's *sibling*, so it does not fade with it,
     *  - the guide's scrim, which is declared after the card and therefore covers it.
     *
     * Being covered is not the same as being hidden here. The drawer and the apps panel both paint
     * [R.color.launcher_all_app_bg] and the guide paints its own semi-transparent black, so the card was
     * legible straight *through* all three, printed over their contents — which is what made this
     * visible rather than merely wrong. The widgets sheet and the messages panel are opaque and hid
     * it by accident; they belong in the predicate anyway, because the rule is "the workspace is
     * covered", not "the card happens to show".
     *
     * Deliberately free of PackageManager and launcherConfig work — it is called from the touch path while
     * the drawer is being dragged. The expansion checks are float comparisons and `beVisibleIf`
     * no-ops when the visibility is unchanged, which it is on all but one frame of a drag.
     */
    private fun updateDefaultLauncherBannerVisibility() {
        binding.defaultLauncherBannerUi.root.beVisibleIf(
            needsDefaultLauncherBanner &&
                mDefaultLauncherPromptEnabled &&
                !isAllAppsFragmentExpanded() &&
                !isWidgetsFragmentExpanded() &&
                mOpenSidePanel == null &&
                !binding.swipeHintUi.isVisible
        )
    }

    /**
     * The step the guide is currently teaching, or null when it has nothing to say.
     *
     * Held rather than recomputed at the point of use, because a tap has to perform the step the
     * user was *looking at* — recomputing could hand them a different one if a flag changed between
     * the draw and the tap.
     */
    private var mGuideStep: LauncherGuideStep? = null

    /**
     * The first step that is still owed: enabled in Remote Config and not yet performed.
     *
     * A disabled step is skipped rather than blocking the ones behind it, so turning off step one
     * promotes step two instead of ending the guide.
     */
    private fun nextGuideStep(): LauncherGuideStep? = LauncherGuideStep.entries.firstOrNull {
        LauncherAdsConfig.guideStepEnabled(it) && !launcherConfig.isGuideStepDone(it) &&
                // No drawer, no gesture to teach.
                (it != LauncherGuideStep.SWIPE_UP_DRAWER || mDrawerEnabled)
    }

    /**
     * Shows the next owed step, if the workspace is what the user is actually looking at.
     *
     * Called on first layout and every time the user comes back to the workspace — which is what
     * makes the guide sequential: one step, the gesture, then the next step on the way back.
     */
    private fun maybeShowGuide() {
        // Never over a drawer, a sheet or an open panel: the guide describes the workspace, and
        // drawn over anything else it would be pointing at anything but.
        if (isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()) return
        if (isLeftPanelExpanded() || isHostPanelExpanded()) return

        val step = nextGuideStep()
        if (step == null) {
            hideSwipeHint()
        } else if (mGuideStep != step || !binding.swipeHintUi.isVisible) {
            showGuideStep(step)
        }

        // Re-decided once the guide has settled, and always after it: the scrim is one of the card's
        // visibility inputs, so a guide that just went up has to hide it and a guide that just
        // finished has to give it back.
        updateDefaultLauncherBannerVisibility()
    }

    /**
     * Draws [step]: the chevron run's direction, the label, and the animation axis.
     *
     * The alpha ramp always leads in the direction of travel, so the three chevrons read as one
     * moving finger. `alphas` is applied against the run's visual order, which is why it is reversed
     * for the two steps that travel back toward the origin.
     */
    private fun showGuideStep(step: LauncherGuideStep) {
        cancelGuideAnimation()
        mGuideStep = step

        val chevrons = listOf(
            binding.swipeHintChevron1Ui,
            binding.swipeHintChevron2Ui,
            binding.swipeHintChevron3Ui,
        )
        val travel = resources.getDimension(R.dimen.launcher_swipe_hint_travel)

        val icon: Int
        val label: Int
        val property: Property<View, Float>
        val distance: Float
        val vertical: Boolean
        when (step) {
            LauncherGuideStep.SWIPE_RIGHT_HOST -> {
                icon = org.fossify.commons.R.drawable.ic_chevron_right_vector
                label = R.string.launcher_swipe_right_hint
                property = View.TRANSLATION_X
                distance = travel
                vertical = false
            }

            LauncherGuideStep.SWIPE_LEFT_APPS -> {
                icon = org.fossify.commons.R.drawable.ic_chevron_left_vector
                label = R.string.launcher_swipe_left_hint
                property = View.TRANSLATION_X
                distance = -travel
                vertical = false
            }

            LauncherGuideStep.SWIPE_UP_DRAWER -> {
                icon = org.fossify.commons.R.drawable.ic_chevron_up_vector
                label = R.string.launcher_swipe_up_hint
                property = View.TRANSLATION_Y
                distance = -travel
                vertical = true
            }
        }

        binding.swipeHintChevronsUi.orientation =
            if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        // Leading chevron brightest. The run's first child is the leading one when travel is
        // negative (left, up) and the last one when it is positive (right).
        val alphas = if (distance < 0f) listOf(1f, 0.6f, 0.3f) else listOf(0.3f, 0.6f, 1f)
        chevrons.forEachIndexed { index, view ->
            view.setImageResource(icon)
            view.alpha = alphas[index]
            // Left over from a previous step on the other axis, which would otherwise leave the run
            // visibly offset before its own animation takes over.
            view.translationX = 0f
            view.translationY = 0f
        }
        binding.swipeHintChevronsUi.translationX = 0f
        binding.swipeHintChevronsUi.translationY = 0f
        binding.swipeHintLabelUi.setText(label)
        binding.swipeHintUi.beVisible()

        mSwipeHintAnimator = ObjectAnimator.ofFloat(
            binding.swipeHintChevronsUi,
            property,
            0f,
            distance,
        ).apply {
            duration = SWIPE_HINT_ANIMATION_DURATION
            repeatCount = ValueAnimator.INFINITE
            // REVERSE, so the run eases back and repeats as one continuous gesture. The default
            // RESTART would snap it to the start each cycle, which reads as a glitch rather than as
            // a demonstration of a swipe.
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /**
     * Marks [step] done and takes the guide down.
     *
     * Called from the gesture's own completion — the panel being raised, the drawer being raised —
     * and therefore from exactly one place per step whether the user swiped or tapped, since the tap
     * runs the same open call a swipe settles into.
     */
    private fun completeGuideStep(step: LauncherGuideStep) {
        if (launcherConfig.isGuideStepDone(step)) return
        launcherConfig.markGuideStepDone(step)
        if (mGuideStep == step) {
            hideSwipeHint()
        }
    }

    /**
     * Performs the current step's gesture. The guide advertises "or tap anywhere to try it", for
     * users who cannot or will not make the gesture — a coach mark that can only be satisfied by the
     * thing it is teaching is a dead end for anyone who cannot do it.
     *
     * Returns true when a tap was consumed, so the normal home-screen click handling is skipped.
     */
    private fun performGuideStep(): Boolean {
        val step = mGuideStep ?: return false
        if (!binding.swipeHintUi.isVisible) return false

        // Down *before* the surface starts moving. The scrim is declared above the panels and the
        // drawer, so without this they would slide in dimmed and only brighten once finished.
        // Completion still comes from the open call, so a step whose action does not actually run
        // is left owed and offered again rather than silently retired.
        hideSwipeHint()

        when (step) {
            LauncherGuideStep.SWIPE_RIGHT_HOST -> showHostPanel()
            LauncherGuideStep.SWIPE_LEFT_APPS -> showLeftPanel()
            LauncherGuideStep.SWIPE_UP_DRAWER -> showFragment(binding.allAppsFragmentUi)
        }
        return true
    }

    private fun cancelGuideAnimation() {
        mSwipeHintAnimator?.cancel()
        mSwipeHintAnimator = null
    }

    /**
     * Takes the guide down and stops its animation. Idempotent — every surface that covers the
     * workspace calls it, and most calls arrive when it is already gone.
     *
     * Deliberately does *not* mark the step done: hiding happens whenever the workspace stops being
     * what the user is looking at, and only the gesture itself retires a step.
     */
    private fun hideSwipeHint() {
        if (!binding.swipeHintUi.isVisible) {
            return
        }

        cancelGuideAnimation()
        binding.swipeHintUi.beGone()
    }

    private fun setupWallpaperColorListener() {
        if (isOreoMr1Plus()) {
            val wallpaperManager = WallpaperManager.getInstance(this)
            wallpaperColorChangeListener = OnColorsChangedListener { colors, which ->
                if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                    wallpaperSupportsDarkText = colors?.wantsDarkText() ?: run {
                        refreshWallpaperSupportsDarkText()
                        wallpaperSupportsDarkText
                    }
                    // Only when the wallpaper is what the bar is actually reading. A panel or the
                    // drawer paints over it, and repainting for a wallpaper the user cannot see
                    // would undo the colour that surface just set.
                    if (currentSurfaceColor() == null) {
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
            ?.wantsDarkText()
    }

    /** The theme in force - the app's own night setting through AppCompatDelegate, or the system's. */
    private fun isNightTheme(): Boolean =
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Kept for the wallpaper listener, which still records it; the bar no longer reads it. */
    private fun android.app.WallpaperColors.wantsDarkText(): Boolean = supportsDarkText()

    /**
     * Every new intent is treated as a Home press and unwinds the workspace — except the one the
     * permission sheet's watcher sends.
     *
     * [PermissionSheetWatcher.bringToFront] pulls this Activity back over the system Settings page
     * the instant the user flips the toggle, specifically so the messages panel and the sheet
     * standing on it are still there to carry the chain on to its next row. It arrives here exactly
     * like a Home press does, so without this guard the unwind below would close the panel the
     * watcher just came back to finish the flow on — and, now that [hideHostPanel] takes the
     * sheet down with it, dismiss the sheet mid-chain too.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed: the long-press card goes with the rest. It is a window of its own, so
        // closing the drawer under it left it floating over the workspace.
        dismissItemActions()

        // Only a real Home press unwinds the workspace. Any other intent - the host bringing the
        // task back after a link or an ad, or asking for the inbox panel - used to be treated the
        // same, which closed the panel the user was on (or was about to be shown) and dropped
        // them on the home grid.
        val isHomePress = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME) &&
            !intent.getBooleanExtra(EXTRA_OPEN_HOST_PANEL, false)
        if (!isHomePress) {
            handleIntentAction(intent)
            openHostPanelIfAsked(intent)
            return
        }
        // Everything below starts closing; onResume must not judge the slides still on their way out.
        mUnwindingFromHomePress = true

        val wasAnyFragmentOpen = isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()
        if (wasJustPaused) {
            if (isAllAppsFragmentExpanded()) {
                hideFragment(binding.allAppsFragmentUi)
            }
            if (isWidgetsFragmentExpanded()) {
                hideFragment(binding.widgetsFragmentUi)
            }
        } else {
            closeAppDrawer()
            closeWidgetsFragment()
        }

        if (isLeftPanelExpanded()) {
            hideLeftPanel()
        }

        if (isHostPanelExpanded()) {
            hideHostPanel()
        }

        binding.allAppsFragmentUi.searchBarUi.closeSearch()

        // scroll to first page when home button is pressed
        val alreadyOnHome = intent.flags and FLAG_ACTIVITY_BROUGHT_TO_FRONT == 0
        if (alreadyOnHome && !wasAnyFragmentOpen) {
            binding.homeScreenGridUi.root.skipToPage(0)
        }

        handleIntentAction(intent)
        LauncherRegistry.bridge.onHomePressed(this, alreadyOnHome)
    }

    /**
     * [EXTRA_OPEN_HOST_PANEL]: slide the inbox panel in as soon as the workspace has a frame. Straight
     * to the panel - no gesture promo in front of it, the user did not swipe, and the onboarding
     * exit interstitial may just have run. Ignored when `launcher_config.panels.messages` is off;
     * OnboardingRoute then targets the standalone inbox instead and this is never set.
     */
    private fun openHostPanelIfAsked(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_HOST_PANEL, false)) return
        intent.removeExtra(EXTRA_OPEN_HOST_PANEL)
        if (!LauncherAdsConfig.panelEnabled(LauncherAdsConfig.RIGHT_SWIPE)) return
        binding.mainHolderUi.post {
            if (isFinishing || isDestroyed || isHostPanelExpanded()) return@post
            revealHostPanel()
        }
    }

    override fun onStart() {
        super.onStart()
        binding.homeScreenGridUi.root.appWidgetHost.startListening()
    }

    override fun onResume() {
        super.onResume()
        // A surface left open across a recreate or a trip away must still hide the workspace
        // behind it; the grid's alpha is otherwise only set by the open and close animations.
        //
        // Not after a Home press: onNewIntent runs just before this and has already started taking
        // every surface down. The "expanded" checks read positions, so a panel still sliding out
        // counted as covering - and cancelling the grid's fade-in here left the whole workspace
        // (icons, widgets, dock) invisible behind an empty wallpaper.
        val unwinding = mUnwindingFromHomePress
        mUnwindingFromHomePress = false
        if (!unwinding && isWorkspaceCovered()) {
            binding.homeScreenGridUi.root.animate().cancel()
            binding.homeScreenGridUi.root.alpha = 0f
        }
        // Whatever path got here, a workspace nothing covers once the slides have settled is shown.
        binding.homeScreenGridUi.root.postDelayed({
            val grid = binding.homeScreenGridUi.root
            if (!isFinishing && !isDestroyed && !isWorkspaceCovered() && grid.alpha < 1f) {
                Timber.w("LauncherPanel: workspace left hidden with nothing over it - shown again")
                grid.animate().alpha(1f).setDuration(ANIMATION_DURATION).start()
            }
        }, ANIMATION_DURATION * 3)
        // Remote Config switched the layout while this screen was alive: rebuild it on the new grid.
        if (reapplyLauncherStyle()) return
        wasJustPaused = false
        mDrawerEnabled = launcherConfig.isDrawerEnabled
        // Once a day at most: the home app's process can outlive WorkManager's slot for weeks.
        LauncherRegistry.bridge.onLauncherResume(this)
        // Before the banner refresh below, which reads the role this may have just been granted.
        // Idempotent, so duplicate resumes cannot escalate twice.
        defaultLauncherRequester.onResume()
        refreshWallpaperSupportsDarkText()
        // The Play update sheet is not raised here: the inbox panel (HomeShellFragment) owns it and
        // asks every time the panel opens, so a forced update blocks the inbox, not the workspace.
        Handler(Looper.getMainLooper()).postDelayed({
            updateStatusBarIcons(currentSurfaceColor())
            // Whatever the user came back to, the guide's next step is owed again if the workspace
            // is what they are looking at. maybeShowGuide decides that for itself.
            maybeShowGuide()
        }, ANIMATION_DURATION)

        with(binding.mainHolderUi) {
            onGlobalLayout {
                binding.allAppsFragmentUi.root.setupViews()
                binding.widgetsFragmentUi.root.setupViews()
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

                // AppLauncher.drawable is @Ignore, so what comes back from Room has no icon — it
                // is a fast first paint, not a usable cache. Clearing the signature makes
                // getAllAppLaunchers rebuild below instead of handing this list straight back.
                // Only reachable on a cold start, where the signature is already null; stated here
                // so the invariant "signature set ⇒ launchers carry drawables" holds by
                // construction rather than by luck.
                IconCache.installedSignature = null
            }

            binding.allAppsFragmentUi.root.gotLaunchers(IconCache.launchers)
            binding.leftPanelUi.root.gotLaunchers(IconCache.launchers)
            refreshLaunchers()
        }

        refreshDefaultLauncherBanner()
        syncHostDockItem()

        binding.homeScreenGridUi.root.resizeGrid(
            newRowCount = launcherConfig.homeRowCount,
            newColumnCount = launcherConfig.homeColumnCount
        )
        binding.homeScreenGridUi.root.updateColors()
        binding.allAppsFragmentUi.root.onResume()
    }

    override fun onStop() {
        super.onStop()
        try {
            binding.homeScreenGridUi.root.appWidgetHost.stopListening()
        } catch (_: Exception) {
        }

        wasJustPaused = false
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(LauncherApps::class.java)?.unregisterCallback(packageCallback)
        packageRefreshHandler.removeCallbacksAndMessages(null)
        if (isOreoMr1Plus() && wallpaperColorChangeListener != null) {
            WallpaperManager.getInstance(this)
                .removeOnColorsChangedListener(wallpaperColorChangeListener!!)
        }
    }

    override fun onPause() {
        super.onPause()
        wasJustPaused = true
        // Leaving the launcher (an app, Recents, the screen going off) must not leave the card up
        // for whatever comes back to it.
        dismissItemActions()
    }

    private fun dismissItemActions() {
        mOpenPopupMenu?.dismiss()
        mOpenPopupMenu = null
    }

    /**
     * Back on the workspace's own surfaces means "put me back on the workspace", in one press.
     *
     * The apps panel and the app drawer used to spend a press on closing their search first, which
     * left the user two presses from home with no visible progress on the first one. Both close
     * their search on the way out instead — [hideLeftPanel] resets the query once it is off screen,
     * and [DrawerSurface.resetSearch] does the same for the drawer — so a query is never carried
     * into the next open.
     *
     * The messages panel is handled by the inbox itself while it is on screen: its callback is
     * registered later on this Activity's dispatcher and therefore runs first, unwinding search,
     * selection and its own drawer before closing the panel. This branch is what catches the press
     * when that callback is not listening.
     */
    override fun onBackPressedCompat(): Boolean {
        return if (isLeftPanelExpanded()) {
            hideLeftPanel()
            true
        } else if (isHostPanelExpanded()) {
            // the inbox gets back first (closes search / clears a selection), then the panel goes
            if (!binding.hostPanelUi.root.handleBack()) hideHostPanel()
            true
        } else if (isAllAppsFragmentExpanded()) {
            binding.allAppsFragmentUi.root.resetSearch()
            hideFragment(binding.allAppsFragmentUi)
            true
        } else if (isWidgetsFragmentExpanded()) {
            if (binding.widgetsFragmentUi.searchBarUi.isSearchOpen) {
                clearWidgetsSearch()
            } else {
                hideFragment(binding.widgetsFragmentUi)
            }
            true
        } else if (binding.homeScreenGridUi.resizeFrameUi.isVisible) {
            binding.homeScreenGridUi.root.hideResizeLines()
            true
        } else {
            // this is a home launcher app, prevent back press from doing anything - the host may
            // still treat it as its moment (an ad on Back, say)
            LauncherRegistry.bridge.onWorkspaceBack(this)
            true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        when (requestCode) {
            // Nothing here: the dialog comes back while the package is still being removed, and a
            // refresh then read a half-removed app - its name gone, re-sorted, still listed.
            // watchUninstall refreshes once the package is actually gone.
            UNINSTALL_APP_REQUEST_CODE -> Unit

            REQUEST_DEFAULT_SMS -> {
                mActionOnDefaultSmsResult?.invoke()
                mActionOnDefaultSmsResult = null
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

    /**
     * A flick that slows down as it lifts often reports no fling at all (its release velocity falls
     * under the fling threshold), and then settled by distance - a quarter-page flick snapped back.
     * Judged on the whole gesture instead: fast on average and not pulled back. True = next page.
     */
    private fun quickSwipeDirection(up: MotionEvent): Boolean? {
        val travel = up.x - mGestureDownX
        val duration = (up.eventTime - up.downTime).coerceAtLeast(1L)
        if (abs(travel) <= 2 * mTouchSlop || abs(travel) / duration < mQuickSwipeSpeed) return null
        val retreat = if (travel < 0) up.x - mGestureMinX else mGestureMaxX - up.x
        if (retreat > mTouchSlop) return null
        return travel < 0
    }

    private fun resetGestureRange(x: Float) {
        mGestureDownX = x
        mGestureMinX = x
        mGestureMaxX = x
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.allAppsFragmentUi.root.onConfigurationChanged()
        binding.widgetsFragmentUi.root.onConfigurationChanged()
        // a theme flip lands here without a recreate; the bar follows it
        updateStatusBarIcons(currentSurfaceColor())
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) {
            return false
        }

        if (mLongPressedIcon != null && event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            mLastUpEvent = System.currentTimeMillis()
        }

        // Before the detector, so onFling on this UP sees the gesture's full x range.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetGestureRange(event.x)
        } else {
            for (i in 0 until event.historySize) {
                mGestureMinX = min(mGestureMinX, event.getHistoricalX(i))
                mGestureMaxX = max(mGestureMaxX, event.getHistoricalX(i))
            }
            mGestureMinX = min(mGestureMinX, event.x)
            mGestureMaxX = max(mGestureMaxX, event.x)
        }

        try {
            mDetector.onTouchEvent(event)
        } catch (e: Exception) {
            // The gesture path opens panels and commits the inbox fragment; a swallowed failure
            // here used to look like "panel opens empty". Keep the guard, but say what broke.
            Timber.e(e, "LauncherPanel: gesture handler failed")
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mTouchDownX = event.x.toInt()
                mTouchDownY = event.y.toInt()
                mAllAppsFragmentY = binding.allAppsFragmentUi.root.y.toInt()
                mWidgetsFragmentY = binding.widgetsFragmentUi.root.y.toInt()
                mIgnoreUpEvent = false
            }

            MotionEvent.ACTION_MOVE -> {
                // if the initial gesture was handled by some other view, fix the Down values
                val hasFingerMoved = if (mTouchDownX == -1 || mTouchDownY == -1) {
                    mTouchDownX = event.x.toInt()
                    mTouchDownY = event.y.toInt()
                    resetGestureRange(event.x)
                    false
                } else {
                    hasFingerMoved(event)
                }

                if (mLongPressedIcon != null && (mOpenPopupMenu != null) && hasFingerMoved) {
                    mOpenPopupMenu?.dismiss()
                    mOpenPopupMenu = null
                    binding.homeScreenGridUi.root.itemDraggingStarted(mLongPressedIcon!!)
                    hideFragment(binding.allAppsFragmentUi)
                }

                if (mLongPressedIcon != null && hasFingerMoved) {
                    binding.homeScreenGridUi.root.draggedItemMoved(event.x.toInt(), event.y.toInt())
                }

                if (hasFingerMoved && !mIgnoreMoveEvents) {
                    // The moment anything under the scrim moves, the lesson has landed — and the
                    // scrim is declared above the drawer, so leaving it up would dim the sheet the
                    // user is dragging in. Checked on engagement rather than on fully-open so an
                    // abandoned half-drag also takes it down; the step stays owed either way and is
                    // re-offered from onResume or the next return to the workspace.
                    hideSwipeHint()

                    val diffY = mTouchDownY - event.y
                    val diffX = mTouchDownX - event.x

                    if (abs(diffY) > abs(diffX) && !mIgnoreYMoveEvents) {
                        mIgnoreXMoveEvents = true
                        if (isWidgetsFragmentExpanded()) {
                            val newY = mWidgetsFragmentY - diffY
                            binding.widgetsFragmentUi.root.y = min(
                                a = max(0f, newY), b = mScreenHeight.toFloat()
                            )
                        } else if (mLongPressedIcon == null && mDrawerEnabled) {
                            val newY = mAllAppsFragmentY - diffY
                            binding.allAppsFragmentUi.root.y = min(
                                a = max(0f, newY), b = mScreenHeight.toFloat()
                            )
                        }
                        // A drag is an open too — the sheet starts covering the workspace from the
                        // first pixel, well before it settles and showFragment runs.
                        updateDefaultLauncherBannerVisibility()
                    } else if (abs(diffX) > abs(diffY) && !mIgnoreXMoveEvents) {
                        mIgnoreYMoveEvents = true
                        binding.homeScreenGridUi.root.setSwipeMovement(diffX)
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
                // An empty card is never put on screen, so it never reports a dismiss: let go of it
                // here, or the next long press would think a card is still open.
                if (mOpenPopupMenu?.isEmpty == true) mOpenPopupMenu = null
                resetFragmentTouches()
                binding.homeScreenGridUi.root.itemDraggingStopped()

                if (!mIgnoreUpEvent) {
                    if (!mIgnoreYMoveEvents) {
                        if (binding.allAppsFragmentUi.root.y < mScreenHeight * 0.5) {
                            // A drag that began with the drawer already up is a settle-back, not an
                            // open: the slot must not swap its creative out from under the finger
                            // that just pulled the sheet down and let go. mAllAppsFragmentY is the
                            // sheet's y as of ACTION_DOWN, which is the only thing left that still
                            // knows — root.y has been following the finger since.
                            showFragment(
                                fragment = binding.allAppsFragmentUi,
                                refreshAds = mAllAppsFragmentY == mScreenHeight
                            )
                        } else if (isAllAppsFragmentExpanded()) {
                            hideFragment(binding.allAppsFragmentUi)
                        }

                        if (binding.widgetsFragmentUi.root.y < mScreenHeight * 0.5) {
                            showFragment(binding.widgetsFragmentUi)
                        } else if (isWidgetsFragmentExpanded()) {
                            hideFragment(binding.widgetsFragmentUi)
                        }
                    }

                }

                // Outside the mIgnoreUpEvent guard: a fling that claimed this UP (a side panel, the
                // drawer) must still settle a page drag in progress, or the workspace stays drawn
                // between two pages - icons cut at both edges, the indicator dot frozen mid-way -
                // until the next horizontal drag.
                if (!mIgnoreXMoveEvents) {
                    val grid = binding.homeScreenGridUi.root
                    // No fling claimed this release, yet the swipe was quick: commit the page.
                    if (!mIgnoreUpEvent && mIgnoreYMoveEvents && event.actionMasked == MotionEvent.ACTION_UP) {
                        quickSwipeDirection(event)?.let { grid.flingSwipe(towardsNext = it) }
                    }
                    grid.finalizeSwipe()
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
        defaultLauncherRequester.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.getBoolean(APP_DRAWER_STATE)) {
            showFragment(binding.allAppsFragmentUi, 0L)
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
                    binding.homeScreenGridUi.root.skipToPage(page)
                }
                // delay showing the shortcut both to let the user see adding it in realtime and hackily avoid concurrent modification exception at HomeScreenGrid
                Thread.sleep(2000)

                try {
                    item.accept()
                    binding.homeScreenGridUi.root.storeAndShowGridItem(gridItem)
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    private fun findFirstEmptyCell(): Pair<Int, Rect> {
        val gridItems = homeScreenGridItemsDB.getAllItems()
        // Pinned shortcuts go where apps go: never on the first page, which belongs to the time and
        // search widgets, and never below the last workspace row, which is the dock.
        val maxPage = (gridItems.maxOfOrNull { it.page } ?: FIRST_APPS_PAGE).coerceAtLeast(FIRST_APPS_PAGE)
        val occupiedCells = HashSet<Triple<Int, Int, Int>>()
        gridItems.filter { it.parentId == null && !it.docked }.forEach { item ->
            for (xCell in item.left..item.right) {
                for (yCell in item.top..item.bottom) {
                    occupiedCells.add(Triple(item.page, xCell, yCell))
                }
            }
        }

        // Row by row, left to right, through every page including the last one.
        for (page in FIRST_APPS_PAGE..maxPage) {
            for (checkedYCell in 0 until launcherConfig.homeRowCount - 1) {
                for (checkedXCell in 0 until launcherConfig.homeColumnCount) {
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

    private val packageRefreshHandler = Handler(Looper.getMainLooper())

    /**
     * Watches [packageName] after its uninstall dialog opens: every [UNINSTALL_WATCH_STEP_MS] for
     * up to [UNINSTALL_WATCH_MS], and the moment the package is gone the drawer and the home screen
     * are refreshed. A cancelled dialog simply runs out the clock. Not every OEM delivers the
     * LauncherApps removal to a launcher promptly, so this does not rely on it.
     */
    /**
     * The in-place fake uninstall (LauncherBridge.removesHostIconInPlace): our rows leave the grid, the
     * dock spot is handed back to the host, and the grid is redrawn - the user never leaves Home.
     */
    fun removeSelfFromHome() {
        LauncherRegistry.bridge.onEvent("uninstall_flow_self_hidden", mapOf("trigger" to "shortcut_in_place"))
        // Flag first, on this thread: the grid drops our rows on any load from here on.
        launcherConfig.selfIconHidden = true
        ensureBackgroundThread {
            SelfRemoval.hide(this)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) binding.homeScreenGridUi.root.fetchGridItems()
            }
        }
    }

    fun watchUninstall(packageName: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + UNINSTALL_WATCH_MS
        val check = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val gone = runCatching { packageManager.getPackageInfo(packageName, 0) }.isFailure
                when {
                    gone -> {
                        Timber.d("LauncherPanel: $packageName uninstalled - refreshing")
                        packageRefreshHandler.removeCallbacks(packageRefresh)
                        packageRefresh.run()
                    }
                    android.os.SystemClock.elapsedRealtime() < deadline ->
                        packageRefreshHandler.postDelayed(this, UNINSTALL_WATCH_STEP_MS)
                }
            }
        }
        packageRefreshHandler.postDelayed(check, UNINSTALL_WATCH_STEP_MS)
    }

    // A burst (an update is a remove and an add; a split install several changes) collapses into
    // one refresh.
    private val packageRefresh = Runnable {
        if (!isFinishing && !isDestroyed) ensureBackgroundThread { refreshLaunchers() }
    }

    private val packageCallback = object : LauncherApps.Callback() {
        private fun changed(packageName: String?) {
            Timber.d("LauncherPanel: package changed - $packageName")
            packageRefreshHandler.removeCallbacks(packageRefresh)
            packageRefreshHandler.postDelayed(packageRefresh, PACKAGE_REFRESH_DELAY_MS)
        }

        override fun onPackageRemoved(packageName: String?, user: android.os.UserHandle?) = changed(packageName)
        override fun onPackageAdded(packageName: String?, user: android.os.UserHandle?) = changed(packageName)
        override fun onPackageChanged(packageName: String?, user: android.os.UserHandle?) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: android.os.UserHandle?, replacing: Boolean) =
            changed(packageNames?.joinToString())
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: android.os.UserHandle?, replacing: Boolean) =
            changed(packageNames?.joinToString())
    }

    // One refresh at a time: two at once (a package change landing on a resume) would each find
    // the same new app missing and put it on the workspace twice.
    private val refreshLock = Any()

    /** True while [packageName] is installed and enabled - so also for the length of its update. */
    private fun isInstalledAndEnabled(packageName: String): Boolean =
        runCatching { packageManager.getApplicationInfo(packageName, 0).enabled }.getOrDefault(false)

    private fun refreshLaunchers(): Unit = synchronized(refreshLock) {
        // First run: the dock is seeded from PackageManager alone, ahead of the icon decode
        // below, so the workspace shows its four apps at once instead of staying blank for as
        // long as every installed app's icon takes to render.
        // Seeded on the first build, and again whenever the dock is found empty: a database that
        // was cleared or restored from a backup would otherwise leave the phone with no dialer,
        // messages, browser or camera for good, because the flag above says it was already done.
        val dockEmpty = homeScreenGridItemsDB.getAllItems().none { it.docked }
        if (!launcherConfig.wasHomeScreenInit || dockEmpty) {
            getDefaultAppPackages()
            launcherConfig.wasHomeScreenInit = true
            launcherConfig.wasSearchBarPurged = true
            binding.homeScreenGridUi.root.fetchGridItems()
        }

        // First run: everything that only places apps needs their names, not their icons. Seeding
        // the first page and filling the workspace from the label-only list puts the time, the
        // search bar and the apps on screen at once, instead of behind the full icon decode below
        // (which the host may already have run - see LauncherScan.prewarm). The steps after this
        // are idempotent, so they find this work done and only fill in what is missing.
        if (!launcherConfig.homeSeeded && !launcherConfig.homeRebuildPending) {
            val names = LauncherScan.quick(this)
            HomeSeeder.seedFirstPage(this, names)
            if (!launcherConfig.isDrawerEnabled || launcherConfig.homeAndDrawer) {
                HomeAppsFiller.placeMissing(this, names)
            }
            binding.homeScreenGridUi.root.fetchGridItems()
        }

        val knownSignature = IconCache.installedSignature
        val launchers = getAllAppLaunchers()
        // Widget providers come from the installed packages, which the scan's signature covers
        // (installs, removals, updates). Same signature, same providers: the list is not rebuilt -
        // that decoded every installed widget's preview again on every return to the home screen.
        val appsChanged = knownSignature == null || knownSignature != IconCache.installedSignature
        binding.allAppsFragmentUi.root.gotLaunchers(launchers)
        binding.leftPanelUi.root.gotLaunchers(launchers)
        if (appsChanged || !binding.widgetsFragmentUi.root.hasWidgets()) {
            binding.widgetsFragmentUi.root.getAppWidgets()
        }

        // An app being updated drops out of the launcher list for a moment (onPackagesUnavailable
        // with replacing = true); deleting its rows then threw away its icons and widgets - the
        // Google search bar on every Google app update. Only an app that is really gone, or
        // disabled, loses them.
        IconCache.launchers.map { it.packageName }.forEach { packageName ->
            if (!launchers.map { it.packageName }.contains(packageName) && !isInstalledAndEnabled(packageName)) {
                launchersDB.deleteApp(packageName)
                homeScreenGridItemsDB.deleteByPackageName(packageName)
            }
        }

        IconCache.launchers = launchers

        // The first page is built once and holds two widgets only - the time and Google search. The
        // pages behind it take every app where the home holds them all: our own setup, no drawer, or
        // Home + Drawer read from its own build (ColorOS mode 2). A drawer-only home (One UI,
        // Pixel) keeps its apps in the drawer - but every app installed from now on still lands
        // on the workspace, on every phone.
        if (launcherConfig.homeRebuildPending) {
            HomeSeeder.resetForRebuild(this)
            launcherConfig.homeRebuildPending = false
        }
        HomeSeeder.seedFirstPage(this, launchers)
        // launcher_config switched a first-page widget off (or swapped the search bar): the old
        // view is a child of the grid, which a refetch does not take away, so the screen is rebuilt.
        if (HomeWidgetSeeder.syncFirstPage(this)) {
            runOnUiThread { if (!isFinishing && !isDestroyed) recreate() }
            return
        }
        if (!launcherConfig.isDrawerEnabled || launcherConfig.homeAndDrawer) {
            HomeAppsFiller.placeMissing(this, launchers)
        }
        HomeSeeder.placeNewInstalls(this, launchers)
        purgeStrayHostIconsIfNeeded()

        if (!launcherConfig.wasSearchBarPurged) {
            ensureBackgroundThread {
                purgeSeededSearchBarIfNeeded()
                // the row is gone; this drops the view if the grid has already placed one
                binding.homeScreenGridUi.root.removePlacedSearchBars()
                binding.homeScreenGridUi.root.fetchGridItems()
            }
        } else {
            binding.homeScreenGridUi.root.fetchGridItems()
        }
    }

    /**
     * Re-reads launcher_config.os_style and, when it changed, recreates this screen on the new
     * layout. Also called by the host when a config is ingested while this screen is in front.
     * Returns true when it recreated.
     */
    fun reapplyLauncherStyle(): Boolean {
        if (isFinishing || isDestroyed) return false
        if (!HomeProfileApplier.applyStyle(this)) return false
        recreate()
        return true
    }

    fun isAllAppsFragmentExpanded() = binding.allAppsFragmentUi.root.y != mScreenHeight.toFloat()

    private fun isWidgetsFragmentExpanded() =
        binding.widgetsFragmentUi.root.y != mScreenHeight.toFloat()

    fun isLeftPanelExpanded() = binding.leftPanelUi.root.x != mScreenWidth.toFloat()

    fun isHostPanelExpanded() = binding.hostPanelUi.root.x != -mScreenWidth.toFloat()

    /**
     * Keys name the *gesture direction*, not the panel. The apps panel is parked off the right edge
     * and slides in on a leftward fling, so it is `left_swipe`; the messages panel is the mirror.
     * The launcherConfig's `guide.swipe_left_apps` / `swipe_right_contacts` use the same convention.
     */
    private fun showLeftPanel() {
        // `launcher_config.panels.apps` - off means the gesture is inert, not a hidden panel.
        if (!LauncherAdsConfig.panelEnabled(LauncherAdsConfig.LEFT_SWIPE)) {
            Timber.d("LauncherPanel: apps panel disabled by launcher_config")
            return
        }
        if (isLeftPanelExpanded()) return
        LauncherPromoController.run(this, LauncherAdsConfig.LEFT_SWIPE) {
            if (isFinishing || isDestroyed || isLeftPanelExpanded()) return@run
            // Before the slide, so the request is already in flight while the panel travels. After
            // the promo interstitial rather than before it, so the two are never in the air at once.
            binding.leftPanelUi.root.refreshAds()
            binding.leftPanelUi.root.onOpened()
            showSidePanel(binding.leftPanelUi.root)
            // The gesture the guide's second step teaches has now been made — by swipe or by tap,
            // both of which land here.
            completeGuideStep(LauncherGuideStep.SWIPE_LEFT_APPS)
        }
    }

    fun hideLeftPanel() {
        hideSidePanel(binding.leftPanelUi.root, mScreenWidth.toFloat())
        // clear the query only once it is off screen, else the sections visibly swap mid slide
        Handler(Looper.getMainLooper()).postDelayed({
            binding.leftPanelUi.root.resetSearch()
        }, ANIMATION_DURATION)
    }

    /**
     * Our own icon tapped on the grid, in the drawer or in the apps panel: the same as the dock
     * icon - the host panel slides in, or, with that panel switched off, the host's launch
     * component opens. Never our LAUNCHER entry, which would only route back here.
     */
    fun openHostApp(fallbackActivity: String = "") {
        if (isAllAppsFragmentExpanded()) closeAppDrawer()
        if (isLeftPanelExpanded()) hideLeftPanel()
        if (LauncherAdsConfig.panelEnabled(LauncherAdsConfig.RIGHT_SWIPE)) {
            showHostPanel()
        } else {
            launchApp(packageName, hostActivityName().ifEmpty { fallbackActivity })
        }
    }

    private fun showHostPanel() {
        // `launcher_config.panels.messages` - off means the gesture is inert, not a hidden panel.
        if (!LauncherAdsConfig.panelEnabled(LauncherAdsConfig.RIGHT_SWIPE)) {
            Timber.d("LauncherPanel: messages panel disabled by launcher_config")
            return
        }
        // Already in (or on its way): a second right swipe is not a second request.
        if (isHostPanelExpanded()) return
        LauncherPromoController.run(this, LauncherAdsConfig.RIGHT_SWIPE) {
            if (isFinishing || isDestroyed || isHostPanelExpanded()) return@run
            // After the ad, not before: committing the inbox starts its own permission chain, and
            // two full-screen things arriving at once would stack a system dialog behind the ad.
            revealHostPanel()
            completeGuideStep(LauncherGuideStep.SWIPE_RIGHT_HOST)
        }
    }

    /** The slide itself: commit the inbox, bring the panel in, hand it Back. */
    private fun revealHostPanel() {
        binding.hostPanelUi.root.refresh()
        showSidePanel(binding.hostPanelUi.root)
        // After the commit above, so the fragment exists to be told. Back belongs to the inbox
        // from here until the panel closes.
        binding.hostPanelUi.root.setPanelVisible(true)
    }

    fun hideHostPanel() {
        // Before the slide, not after: a Back press landing during the animation has to reach the
        // launcher's own handler, not the inbox that is on its way off screen.
        binding.hostPanelUi.root.setPanelVisible(false)
        hideSidePanel(binding.hostPanelUi.root, -mScreenWidth.toFloat())
    }

    /** The inbox asking to dismiss itself. In a panel that means closing the panel, not finishing. */
    fun closeHostPanel() {
        if (isHostPanelExpanded()) {
            hideHostPanel()
        }
    }

    private fun showSidePanel(panel: View) {
        hideSwipeHint()
        mOpenSidePanel = panel
        animateSidePanelTo(panel, 0f)
        // The card belongs to the workspace the panel is about to cover, and it does not travel with
        // it — see updateDefaultLauncherBannerVisibility. Before the slide, not after: the apps panel
        // is semi-transparent, so a card still up would be legible through it for the whole 150 ms.
        // After hideSwipeHint above, because the guide's visibility is one of the inputs.
        updateDefaultLauncherBannerVisibility()
        window.navigationBarColor = resources.getColor(R.color.launcher_semitransparent_navigation)
        binding.homeScreenGridUi.root.fragmentExpanded()
        binding.homeScreenGridUi.root.hideResizeLines()
        binding.homeScreenGridUi.root.animate()
            .alpha(0f)
            .setDuration(ANIMATION_DURATION)
            .start()

        @SuppressLint("AccessibilityFocus")
        panel.performAccessibilityAction(
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        )

        // Immediately, not posted past the slide. The navigation bar above is repainted at once, so
        // deferring this one left the two bars disagreeing for the length of the animation.
        updateStatusBarIcons(sidePanelBackgroundColor(panel))
    }

    private fun hideSidePanel(panel: View, parkedX: Float) {
        // Back on the workspace — the guide's next step, if it is owed one. Hung off the slide's
        // own completion rather than a delay of the same length: maybeShowGuide decides whether the
        // workspace is what the user is looking at by reading the panel's current x, and a matching
        // postDelayed is a coin flip against the animator's own last frame. Losing it read as
        // "panel still open" and skipped the step in silence — which is why swipe-up, the only step
        // whose predecessor never bounces through onResume, was the one that went missing.
        animateSidePanelTo(panel, parkedX) {
            mOpenSidePanel = null
            maybeShowGuide()
            // Explicitly, not left to maybeShowGuide's own trailing call: that one is skipped
            // whenever the guide bails early, and the alert is owed the workspace back regardless.
            updateDefaultLauncherBannerVisibility()
        }
        window.navigationBarColor = Color.TRANSPARENT
        binding.homeScreenGridUi.root.fragmentCollapsed()
        updateStatusBarIcons()
        hideKeyboard()
    }

    private fun animateSidePanelTo(panel: View, x: Float, onEnd: (() -> Unit)? = null) {
        ObjectAnimator.ofFloat(panel, "x", x).apply {
            duration = ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            if (onEnd != null) {
                addListener(endListener(onEnd))
            }
            start()
        }
    }

    /** Runs [action] once the animation has settled — on a cancel too, which is the safe direction:
     *  everything hung off this re-decides from the surfaces' current positions anyway. */
    private fun endListener(action: () -> Unit) = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) = action()
    }

    fun startHandlingTouches(touchDownY: Int) {
        mLongPressedIcon = null
        mTouchDownY = touchDownY
        mAllAppsFragmentY = binding.allAppsFragmentUi.root.y.toInt()
        mWidgetsFragmentY = binding.widgetsFragmentUi.root.y.toInt()
        mIgnoreUpEvent = false
    }

    /**
     * [refreshAds] is the drawer's native slot, and defaults on: every caller but one is a genuine
     * open. The exception is the drag handler, which lands here again when a half-dragged sheet
     * settles back up — see its call.
     */
    private fun showFragment(
        fragment: ViewBinding,
        animationDuration: Long = ANIMATION_DURATION,
        refreshAds: Boolean = true,
    ) {
        ObjectAnimator.ofFloat(fragment.root, "y", 0f).apply {
            duration = animationDuration
            interpolator = DecelerateInterpolator()
            // Belt to the braces of the immediate call below. That one is right for every gesture
            // path, because the drag preceding a fling has already moved `y`. It is *not* right for
            // onRestoreInstanceState, which shows the sheet with duration 0 from a parked position —
            // the animator applies its first value on the next frame, so an immediate check there
            // reads "collapsed" and would leave the alert sitting on top of a restored drawer.
            addListener(endListener { updateDefaultLauncherBannerVisibility() })
            start()
        }

        window.navigationBarColor = resources.getColor(R.color.launcher_semitransparent_navigation)
        binding.homeScreenGridUi.root.fragmentExpanded()
        binding.homeScreenGridUi.root.hideResizeLines()
        // So does the guide. The user found the drawer instead — they are clearly not stuck.
        hideSwipeHint()
        // The alert belongs to the workspace the sheet is about to cover. After the hint, because
        // its visibility is one of the inputs.
        updateDefaultLauncherBannerVisibility()
        if (fragment is LnchAllAppsFragmentBinding) {
            // The drawer is up, which is the guide's third and last lesson.
            completeGuideStep(LauncherGuideStep.SWIPE_UP_DRAWER)
            // Requested per open, so the slot is never showing the creative from an hour ago — see
            // DrawerSurface.refreshAds. As the sheet starts moving rather than after it settles,
            // so the fill has the whole slide to arrive.
            if (refreshAds) {
                fragment.root.refreshAds()
            }
        }

        @SuppressLint("AccessibilityFocus")
        fragment.root.performAccessibilityAction(
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        )

        if (
            fragment is LnchAllAppsFragmentBinding
            && launcherConfig.showSearchBar
            && launcherConfig.autoShowKeyboardInAppDrawer
        ) {
            fragment.root.post {
                showKeyboard(fragment.searchBarUi.binding.topToolbarSearch)
            }
        }

        // fade the grid out behind the fragment, fragmentCollapsed() cancels this and restores it
        if (animationDuration == 0L) {
            // onRestoreInstanceState after a theme flip: the sheet's animator above lands this
            // frame, but a view animator only runs on the next one, so the restored drawer sat over
            // a fully drawn workspace until then - or for good, when that frame never ran it.
            binding.homeScreenGridUi.root.animate().cancel()
            binding.homeScreenGridUi.root.alpha = 0f
        } else {
            binding.homeScreenGridUi.root.animate()
                .alpha(0f)
                .setDuration(animationDuration)
                .start()
        }

        // see showSidePanel: repainted with the navigation bar rather than after the slide
        updateStatusBarIcons(sheetBackgroundColor(fragment))
    }

    private fun hideFragment(fragment: ViewBinding, animationDuration: Long = ANIMATION_DURATION) {
        ObjectAnimator.ofFloat(fragment.root, "y", mScreenHeight.toFloat()).apply {
            duration = animationDuration
            interpolator = DecelerateInterpolator()
            // The guide's next step, if one is owed — see hideSidePanel for why this hangs off the
            // animator rather than a delay of the same length. The alert comes back at the same
            // moment and for the same reason: a sheet only counts as collapsed once its animation
            // has actually parked it, so asking any earlier reads as still-expanded.
            addListener(
                endListener {
                    maybeShowGuide()
                    updateDefaultLauncherBannerVisibility()
                }
            )
            start()
        }

        window.navigationBarColor = Color.TRANSPARENT
        binding.homeScreenGridUi.root.fragmentCollapsed()
        updateStatusBarIcons()
        // As hideSidePanel does: the sheet may be going away with its search field focused — the
        // drawer even opens the keyboard for it when autoShowKeyboardInAppDrawer is on — and an IME
        // left standing over the workspace would then need a Back press of its own to dismiss.
        hideKeyboard()
        if (fragment is LnchWidgetsFragmentBinding) {
            clearWidgetsSearch()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (fragment is LnchAllAppsFragmentBinding) {
                fragment.allAppsGridUi.scrollToPosition(0)
                fragment.root.resetToFirstPage()
                fragment.root.touchDownY = -1
            } else if (fragment is LnchWidgetsFragmentBinding) {
                fragment.widgetsListUi.scrollToPosition(0)
                fragment.root.touchDownY = -1
            }
        }, animationDuration)
    }

    fun homeScreenLongPressed(eventX: Float, eventY: Float) {
        if (isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()) {
            return
        }

        // A long press means the user is editing the workspace, not reading about it — and the menu
        // it opens is its own window, so it would otherwise land on top of the dimmed scrim.
        hideSwipeHint()

        val (x, y) = binding.homeScreenGridUi.root.intoViewSpaceCoords(eventX, eventY)
        mIgnoreMoveEvents = true
        val clickedGridItem = binding.homeScreenGridUi.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            performItemLongClick(x, clickedGridItem)
        }
        // Empty space does nothing: the home offers no launcher customisation (widgets,
        // wallpapers, launcher settings) - it is part of the host app, not a launcher to tune.
    }

    /**
     * True while a surface lies over the workspace. A tap on a part of that surface which takes no
     * touches itself - the empty space of the drawer's search screen, say - still reaches this
     * Activity's detector, which used to read it as a tap on the home page underneath and launch
     * whatever icon sat at that spot (on a page with icons there, so only "sometimes").
     */
    private fun isWorkspaceCovered() =
        isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded() || isLeftPanelExpanded() || isHostPanelExpanded()

    fun homeScreenClicked(eventX: Float, eventY: Float) {
        if (isWorkspaceCovered()) {
            return
        }

        // The guide takes the tap before the workspace does. Its scrim covers the screen, so a tap
        // "on an icon" underneath is one the user could not have aimed — they were looking at the
        // coach mark. Consumed, so nothing behind it is launched by accident.
        if (performGuideStep()) {
            return
        }

        binding.homeScreenGridUi.root.hideResizeLines()
        val (x, y) = binding.homeScreenGridUi.root.intoViewSpaceCoords(eventX, eventY)
        val clickedGridItem = binding.homeScreenGridUi.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            performItemClick(clickedGridItem)
        }
        if (clickedGridItem?.type != ITEM_TYPE_FOLDER) {
            binding.homeScreenGridUi.root.closeFolder(redraw = true)
        }
    }

    fun homeScreenDoubleTapped(eventX: Float, eventY: Float) {
        // Same reason as homeScreenClicked: a double tap through the drawer would lock the phone.
        if (isWorkspaceCovered()) {
            return
        }

        val (x, y) = binding.homeScreenGridUi.root.intoViewSpaceCoords(eventX, eventY)
        val clickedGridItem = binding.homeScreenGridUi.root.isClickingGridItem(x.toInt(), y.toInt())
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
                binding.allAppsFragmentUi.root.y = mScreenHeight.toFloat()
                binding.allAppsFragmentUi.allAppsGridUi.scrollToPosition(0)
                binding.allAppsFragmentUi.root.resetToFirstPage()
                binding.allAppsFragmentUi.root.touchDownY = -1
                binding.homeScreenGridUi.root.fragmentCollapsed()
                updateStatusBarIcons()
                // Snapped rather than animated, so the state is already settled here — no listener
                // to hang these off, and none needed.
                maybeShowGuide()
                updateDefaultLauncherBannerVisibility()
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
                binding.widgetsFragmentUi.root.y = mScreenHeight.toFloat()
                binding.widgetsFragmentUi.widgetsListUi.scrollToPosition(0)
                clearWidgetsSearch()
                binding.widgetsFragmentUi.root.touchDownY = -1
                binding.homeScreenGridUi.root.fragmentCollapsed()
                updateStatusBarIcons()
                // see closeAppDrawer
                maybeShowGuide()
                updateDefaultLauncherBannerVisibility()
            }
            if (delayed) {
                Handler(Looper.getMainLooper()).postDelayed(close, APP_DRAWER_CLOSE_DELAY)
            } else {
                close()
            }
        }
    }

    fun clearWidgetsSearch() {
        binding.widgetsFragmentUi.searchBarUi.closeSearch()
    }

    private fun performItemClick(clickedGridItem: HomeScreenGridItem) {
        when (clickedGridItem.type) {
            ITEM_TYPE_ICON -> if (clickedGridItem.packageName == packageName) {
                // Our own icon (dock or grid) slides the host panel in - same promo, motion and Back
                // as the swipe - or, with the panel off, opens the host's launch component.
                openHostApp(clickedGridItem.activityName)
            } else {
                launchApp(clickedGridItem.packageName, clickedGridItem.activityName)
            }
            ITEM_TYPE_FOLDER -> openFolder(clickedGridItem)
            ITEM_TYPE_SHORTCUT -> {
                val id = clickedGridItem.shortcutId
                val packageName = clickedGridItem.packageName
                val userHandle = android.os.Process.myUserHandle()
                val shortcutBounds = binding.homeScreenGridUi.root.getClickableRect(clickedGridItem)
                val launcherApps =
                    applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
                launcherApps.startShortcut(packageName, id, shortcutBounds, null, userHandle)
            }
        }
    }

    private fun openFolder(folder: HomeScreenGridItem) {
        binding.homeScreenGridUi.root.openFolder(folder)
    }

    private fun performItemLongClick(x: Float, clickedGridItem: HomeScreenGridItem) {
        if (clickedGridItem.type == ITEM_TYPE_ICON || clickedGridItem.type == ITEM_TYPE_SHORTCUT || clickedGridItem.type == ITEM_TYPE_FOLDER) {
            binding.mainHolderUi.performHapticFeedback()
        }

        val anchorY = binding.homeScreenGridUi.root.sideMargins.top +
                (clickedGridItem.top * binding.homeScreenGridUi.root.cellHeight.toFloat())
        showHomeIconMenu(x, anchorY, clickedGridItem, false)
    }

    fun showHomeIconMenu(
        x: Float,
        y: Float,
        gridItem: HomeScreenGridItem,
        isOnAllAppsFragment: Boolean,
    ) {
        binding.homeScreenGridUi.root.hideResizeLines()
        mLongPressedIcon = gridItem
        // The card is placed against the item's bounds on screen, so it can sit clear of it. A
        // drawer tile reports screen coordinates already; grid rows and widgets are in the grid's.
        val grid = binding.homeScreenGridUi.root
        val gridOnScreen = IntArray(2).also { grid.getLocationOnScreen(it) }
        val (centerX, itemTop, itemBottom) = when {
            isOnAllAppsFragment -> {
                val tile = realScreenSize.x / launcherConfig.drawerColumnCount.toFloat()
                Triple(x, y, y + tile)
            }
            gridItem.type == ITEM_TYPE_WIDGET -> {
                val top = gridOnScreen[1] + y
                Triple(gridOnScreen[0] + x, top, top + gridItem.getHeightInCells() * grid.cellHeight)
            }
            else -> {
                val rect = grid.getClickableRect(gridItem)
                Triple(gridOnScreen[0] + rect.exactCenterX(), gridOnScreen[1] + rect.top.toFloat(), gridOnScreen[1] + rect.bottom.toFloat())
            }
        }

        // A card that is not on screen (an empty one is never shown, and touches in the drawer do
        // not reach the finger-up that would drop it) does not block the next one.
        if (mOpenPopupMenu?.isShowing != true) {
            mOpenPopupMenu = handleGridItemPopupMenu(
                anchorView = binding.root,
                gridItem = gridItem,
                isOnAllAppsFragment = isOnAllAppsFragment,
                listener = menuListener,
                centerX = centerX,
                itemTop = itemTop,
                itemBottom = itemBottom,
            )
        }
    }

    fun widgetLongPressedOnList(gridItem: HomeScreenGridItem) {
        mLongPressedIcon = gridItem
        hideFragment(binding.widgetsFragmentUi)
        binding.homeScreenGridUi.root.itemDraggingStarted(mLongPressedIcon!!)
    }

    private fun resetFragmentTouches() {
        binding.widgetsFragmentUi.root.apply {
            touchDownY = -1
            ignoreTouches = false
        }

        binding.allAppsFragmentUi.root.apply {
            touchDownY = -1
            ignoreTouches = false
        }
    }

    private fun hideIconUi(item: HomeScreenGridItem) {
        ensureBackgroundThread {
            val hiddenIconUi = HiddenIcon(null, item.packageName, item.activityName, item.title, null)
            hiddenIconsDB.insert(hiddenIconUi)

            runOnUiThread {
                binding.allAppsFragmentUi.root.onIconHidden(item)
            }
        }
    }

    private fun renameItem(homeScreenGridItem: HomeScreenGridItem) {
        RenameItemTray(this, homeScreenGridItem) {
            binding.homeScreenGridUi.root.fetchGridItems()
        }
    }

    val menuListener: ItemMenuListener = object : ItemMenuListener {
        override fun onAnyClick() {
            resetFragmentTouches()
        }

        override fun hide(gridItem: HomeScreenGridItem) {
            hideIconUi(gridItem)
        }

        override fun rename(gridItem: HomeScreenGridItem) {
            renameItem(gridItem)
        }

        override fun resize(gridItem: HomeScreenGridItem) {
            binding.homeScreenGridUi.root.widgetLongPressed(gridItem)
        }

        override fun appInfoUi(gridItem: HomeScreenGridItem) {
            launchAppInfo(gridItem.packageName)
        }

        override fun remove(gridItem: HomeScreenGridItem) {
            binding.homeScreenGridUi.root.removeAppIcon(gridItem)
        }

        override fun uninstall(gridItem: HomeScreenGridItem) {
            uninstallApp(gridItem.packageName)
        }

        override fun onDismiss() {
            mOpenPopupMenu = null
            resetFragmentTouches()
        }
    }

    private class MyGestureListener(
        private val flingListener: FlingListener,
    ) : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            (flingListener as LauncherPanel).homeScreenClicked(event.x, event.y)
            return super.onSingleTapUp(event)
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            (flingListener as LauncherPanel).homeScreenDoubleTapped(event.x, event.y)
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
                // The finger's whole travel decides the direction, not its last instant: a flick
                // that slows down or wobbles as it lifts often reports a velocity against its own
                // drag, which read as a fling the other way and opened the opposite panel. Only a
                // real pull-back - the finger retreating past the touch slop from the farthest
                // point it reached - is a change of mind; that release is left to ACTION_UP,
                // which settles the page by distance.
                val travelX = if (event1 != null) event2.x - event1.x else velocityX
                val direction = if (travelX != 0f) travelX else velocityX
                if (sign(direction) != sign(velocityX)) {
                    val panel = flingListener as LauncherPanel
                    val retreat = if (direction < 0) event2.x - panel.mGestureMinX else panel.mGestureMaxX - event2.x
                    if (retreat > panel.mTouchSlop) {
                        return true
                    }
                }
                if (direction > 0) {
                    flingListener.onFlingRight()
                } else {
                    flingListener.onFlingLeft()
                }
            }

            return true
        }

        override fun onLongPress(event: MotionEvent) {
            (flingListener as LauncherPanel).homeScreenLongPressed(event.x, event.y)
        }
    }

    override fun onFlingUp() {
        if (mIgnoreYMoveEvents || !mDrawerEnabled) {
            return
        }

        if (!isWidgetsFragmentExpanded()) {
            mIgnoreUpEvent = true
            showFragment(binding.allAppsFragmentUi)
        }
    }

    @SuppressLint("WrongConstant")
    override fun onFlingDown() {
        if (mIgnoreYMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        if (isAllAppsFragmentExpanded()) {
            hideFragment(binding.allAppsFragmentUi)
        } else if (isWidgetsFragmentExpanded()) {
            hideFragment(binding.widgetsFragmentUi)
        } else {
            try {
                Class.forName("android.app.StatusBarManager")
                    .getMethod("expandNotificationsPanel")
                    .invoke(getSystemService("statusbar"))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * A side panel is open or sliding in. It covers the workspace (the grid is faded to nothing
     * behind it), so a horizontal fling is not the workspace's to act on: it used to page the
     * invisible grid, and a second right swipe over the open panel left the user on the first
     * ("home") page when the panel closed.
     */
    private fun sidePanelOwnsFlings() =
        mOpenSidePanel != null || isHostPanelExpanded() || isLeftPanelExpanded()

    override fun onFlingRight() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        if (sidePanelOwnsFlings()) {
            return
        }
        val grid = binding.homeScreenGridUi.root
        // Paging owns the fling while a page is dragged or still settling (see flingSwipe). The
        // host panel sits left of the home page, so it opens only from there, at rest; anywhere
        // else a rightward fling is just the previous page.
        if (grid.flingSwipe(towardsNext = false)) {
            return
        }
        if (!isAllAppsFragmentExpanded() && !isWidgetsFragmentExpanded() && grid.isOnFirstPage()) {
            showHostPanel()
        } else {
            grid.prevPage(redraw = true)
        }
    }

    override fun onFlingLeft() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        if (sidePanelOwnsFlings()) {
            return
        }
        val grid = binding.homeScreenGridUi.root
        // see onFlingRight: the apps panel sits right of the last page and opens only from it
        if (grid.flingSwipe(towardsNext = true)) {
            return
        }
        if (!isAllAppsFragmentExpanded() && !isWidgetsFragmentExpanded() && grid.isOnLastPage()) {
            showLeftPanel()
        } else {
            grid.nextPage(redraw = true)
        }
    }

    /** The launcher list, built by [LauncherScan]; the drawer and apps panel get it in stages on a cold start. */
    fun getAllAppLaunchers(): ArrayList<AppLauncher> = LauncherScan.scan(
        this,
        onQuick = { quick ->
            binding.allAppsFragmentUi.root.gotLaunchers(quick)
            binding.leftPanelUi.root.gotLaunchers(quick)
        },
        onProgress = { partial ->
            binding.allAppsFragmentUi.root.gotLaunchers(partial)
            binding.leftPanelUi.root.gotLaunchers(partial)
        },
    )

    /**
     * Takes the search pill off the home screen.
     *
     * It used to be seeded there on first run, so removing the seeding alone would have left it in
     * place for everyone who had already started the launcher once — the row lives in the grid's
     * database, not in the layout. This clears those rows.
     *
     * Once only, hence the flag: the pill is still offered by the widgets picker, and a user who
     * deliberately adds it back must not have it swept away on the next resume.
     */
    /**
     * Takes our own icon off the home pages, once.
     *
     * An older build's HomeAppsFiller did not skip the host, so our LAUNCHER entry was placed on a
     * workspace page next to the dock's own row - a stray Caller ID icon in a random cell. The
     * filler skips us now; this clears what it already placed. The dock slot (docked) and anything
     * inside a folder are left alone, and it runs once, so an icon the user drags onto the home
     * screen later stays where they put it. Off the main thread, before the grid's next fetch.
     */
    private fun purgeStrayHostIconsIfNeeded() {
        if (launcherConfig.wereStrayHostIconsPurged) return
        launcherConfig.wereStrayHostIconsPurged = true
        runCatching {
            val own = packageName
            val stray = homeScreenGridItemsDB.getAllItems().filter {
                it.type == ITEM_TYPE_ICON && !it.docked && it.parentId == null && it.packageName == own
            }
            stray.forEach { item -> item.id?.let { homeScreenGridItemsDB.deleteById(it) } }
            if (stray.isNotEmpty()) Timber.i("LauncherPanel: removed ${stray.size} stray host icon(s) from the home pages")
        }.onFailure { Timber.w(it, "LauncherPanel: stray host icon purge failed") }
    }

    private fun purgeSeededSearchBarIfNeeded() {
        if (launcherConfig.wasSearchBarPurged) {
            return
        }

        launcherConfig.wasSearchBarPurged = true
        try {
            homeScreenGridItemsDB.deleteItemsByClassName(PSEUDO_WIDGET_SEARCH)
        } catch (e: Exception) {
            Log.e("LauncherPanel", "Failed to remove the seeded search bar", e)
        }
    }

    /**
     * The app the host wants in the dock's reserved slot - itself by default. A host that follows a
     * role rather than a package (the SMS role, for a messaging host) answers that in
     * LauncherBridge.dockSlotPackage, and the sync below swaps the row when the answer changes.
     */
    private fun hostDockPackage(): String? = LauncherRegistry.bridge.dockSlotPackage(this)

    /**
     * What the dock's reserved slot should hold: whatever the host names, except that after the fake
     * uninstall (launcherConfig.selfIconHidden) nothing of ours is ever placed there again. A host
     * that wants a replacement in our spot - a messaging host handing it to the phone's stock
     * messaging app, say - answers with that package from dockSlotPackage itself;
     * SelfRemoval.systemMessagingPackage is there for exactly that.
     */
    private fun dockSlotPackage(): String? {
        val wanted = hostDockPackage()
        return if (wanted == packageName && launcherConfig.selfIconHidden) null else wanted
    }

    /** The app's launcher label, or null when it has no launcher entry to dock. */
    private fun dockableTitle(packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return null
        return try {
            packageManager.getLaunchIntentForPackage(packageName) ?: return null
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The dock row for the SMS app [packageName], or null when there is none to show. Our own
     * row is built by hand rather than from the launcher list (where our LAUNCHER entry sits) and points at the standalone inbox rather than the LAUNCHER entry, which would only
     * splash back into this home screen; performItemClick turns it into the panel when there is one.
     * After the fake uninstall (launcherConfig.selfIconHidden) our row is never built again, so
     * neither the first-run seed nor the SMS-role sync can bring the icon back.
     */
    private fun hostDockItem(packageName: String?): HomeScreenGridItem? {
        if (packageName.isNullOrEmpty()) return null
        val ours = packageName == this.packageName
        if (ours && launcherConfig.selfIconHidden) return null
        val title = if (ours) hostLabel() else dockableTitle(packageName) ?: return null
        return HomeScreenGridItem(
            id = null,
            left = DOCK_HOST_SLOT,
            top = launcherConfig.homeRowCount - 1,
            right = DOCK_HOST_SLOT,
            bottom = launcherConfig.homeRowCount - 1,
            page = 0,
            packageName = packageName,
            activityName = if (ours) hostActivityName() else "",
            title = title,
            type = ITEM_TYPE_ICON,
            className = "",
            widgetId = -1,
            shortcutId = "",
            icon = null,
            docked = true,
            parentId = null
        )
    }

    /**
     * Whether this app sits in the dock is decided by the SMS role, not the Home role: the dock's
     * messaging slot shows whichever app is the default SMS app right now. Taking the role inside
     * the inbox, or losing it to another app, swaps the row on the next resume. The row the slot
     * was last given (launcherConfig.dockHostPackage) is removed wherever it still sits docked; a
     * slot the user has since filled with something else is left alone.
     */
    private fun syncHostDockItem() {
        if (!launcherConfig.wasHomeScreenInit) return
        val wanted = dockSlotPackage() ?: return
        // Installs seeded before this existed always got our own row and no record of it.
        val placed = launcherConfig.dockHostPackage.ifEmpty { packageName }
        if (wanted == placed && launcherConfig.dockHostPackage.isNotEmpty()) return
        ensureBackgroundThread {
            val items = homeScreenGridItemsDB.getAllItems()
            if (wanted == placed) {
                // No record of the slot: either an install seeded before the record existed (our
                // row is there) or a seed that ran while the SMS role was changing hands, when
                // getDefaultSmsPackage was null and no row was written at all. Fill it only in
                // the second case.
                val slotFilled = items.any { it.docked && it.left == DOCK_HOST_SLOT && it.parentId == null }
                if (!slotFilled) {
                    hostDockItem(wanted)?.let { homeScreenGridItemsDB.insert(it) }
                    binding.homeScreenGridUi.root.fetchGridItems()
                }
                launcherConfig.dockHostPackage = placed
                return@ensureBackgroundThread
            }
            items.filter { it.docked && it.type == ITEM_TYPE_ICON && it.packageName == placed }
                .forEach { homeScreenGridItemsDB.deleteById(it.id!!) }
            val slotTaken = items.any {
                it.docked && it.left == DOCK_HOST_SLOT && it.packageName != placed && it.parentId == null
            }
            if (!slotTaken) {
                hostDockItem(wanted)?.let { homeScreenGridItemsDB.insert(it) }
            }
            launcherConfig.dockHostPackage = wanted
            Timber.d("LauncherPanel: dock host slot $placed -> $wanted (slotTaken=$slotTaken)")
            binding.homeScreenGridUi.root.fetchGridItems()
        }
    }

    private fun getDefaultAppPackages() {
        val homeScreenGridItems = ArrayList<HomeScreenGridItem>()

        try {
            val defaultDialerPackage =
                (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
            dockableTitle(defaultDialerPackage)?.let { title ->
                val dialerIcon =
                    HomeScreenGridItem(
                        id = null,
                        left = 0,
                        top = launcherConfig.homeRowCount - 1,
                        right = 0,
                        bottom = launcherConfig.homeRowCount - 1,
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

        // The messaging slot follows the SMS role: this app while it holds it, the system's
        // default SMS app until then - see syncHostDockItem, which keeps it that way.
        hostDockItem(dockSlotPackage())?.let { item ->
            homeScreenGridItems.add(item)
            launcherConfig.dockHostPackage = item.packageName
        }

        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, "http://".toUri())
            val resolveInfo =
                packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultBrowserPackage = resolveInfo!!.activityInfo.packageName
            dockableTitle(defaultBrowserPackage)?.let { title ->
                val browserIcon =
                    HomeScreenGridItem(
                        id = null,
                        left = 2,
                        top = launcherConfig.homeRowCount - 1,
                        right = 2,
                        bottom = launcherConfig.homeRowCount - 1,
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
            val cameraIntent = Intent("android.media.action.IMAGE_CAPTURE")
            val resolveInfo =
                packageManager.resolveActivity(cameraIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultCameraPackage = resolveInfo!!.activityInfo.packageName
            dockableTitle(defaultCameraPackage)?.let { title ->
                val cameraIcon =
                    HomeScreenGridItem(
                        id = null,
                        left = 3,
                        top = launcherConfig.homeRowCount - 1,
                        right = 3,
                        bottom = launcherConfig.homeRowCount - 1,
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

        // A fifth slot, where the home profile gives the dock one (Pixel, tablets): the app store,
        // which is what every OEM parks there. Skipped without a launchable Play Store.
        if (launcherConfig.dockColumnCount >= 5) {
            dockableTitle(PLAY_STORE_PACKAGE)?.let { title ->
                homeScreenGridItems.add(
                    HomeScreenGridItem(
                        id = null,
                        left = 4,
                        top = launcherConfig.homeRowCount - 1,
                        right = 4,
                        bottom = launcherConfig.homeRowCount - 1,
                        page = 0,
                        packageName = PLAY_STORE_PACKAGE,
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
                )
            }
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

    /**
     * The colour actually behind the status bar for an expanded sheet.
     *
     * Not `getProperBackgroundColor()` for the app drawer. That returns the *theme* background,
     * which on a light-mode device is light — but the drawer paints [R.color.launcher_all_app_bg] over the
     * wallpaper, so it is a dark surface whatever theme the rest of the app is in, and the bar was
     * being told the opposite. Its own labels already carry a fixed light-on-dark colour for exactly
     * this reason; the status bar simply never got the same treatment.
     *
     * The widgets sheet does not scrim itself, so the theme colour is the right answer there.
     */
    private fun sheetBackgroundColor(fragment: ViewBinding): Int =
        if (fragment is LnchAllAppsFragmentBinding) {
            getColor(R.color.launcher_all_app_bg)
        } else {
            getProperBackgroundColor()
        }

    /**
     * The colour actually behind the status bar for an open side panel.
     *
     * The two panels are not the same surface and never were. The apps panel paints
     * [R.color.launcher_all_app_bg] — the drawer's scrim, dark in both themes. The messages panel is inflated
     * against the messaging module's own DayNight `AppTheme` and paints its `windowBackground`,
     * which is light on a light-mode device and dark on a dark one. Reading the panel's own
     * background is what keeps both right without this having to know which is which; the theme
     * colour, which is what both used to get, describes neither.
     */
    private fun sidePanelBackgroundColor(panel: View): Int =
        (panel.background as? ColorDrawable)?.color ?: getProperBackgroundColor()

    /**
     * The colour behind the status bar right now, or null when the wallpaper is what is showing.
     *
     * Only safe to ask once the surfaces have settled — the expanded checks read a view's current
     * position, so mid-animation they still report the surface that is sliding away. Every caller
     * either runs from a resumed, idle screen or is posted past the animation.
     */
    private fun currentSurfaceColor(): Int? = when {
        isAllAppsFragmentExpanded() -> sheetBackgroundColor(binding.allAppsFragmentUi)
        isWidgetsFragmentExpanded() -> sheetBackgroundColor(binding.widgetsFragmentUi)
        isLeftPanelExpanded() -> sidePanelBackgroundColor(binding.leftPanelUi.root)
        isHostPanelExpanded() -> sidePanelBackgroundColor(binding.hostPanelUi.root)
        else -> null
    }

    /**
     * A surface the launcher paints - the drawer, a side panel - passes its own colour, so the
     * bar follows the app's light / dark theme with it. The bare workspace is the wallpaper, and
     * nothing can say what colour that is: the system's dark-text hint is missing on OEM builds
     * and their live-wallpaper services report invented colours, while the theme says nothing
     * about the picture behind the bar. So the workspace keeps white icons over the scrim
     * declared at the top of lnch_activity_main.xml, which is legible on any wallpaper.
     */
    private fun updateStatusBarIcons(backgroundColor: Int? = null) {
        val isLightBackground = when {
            backgroundColor != null -> backgroundColor.getContrastColor() == DARK_GREY
            else -> false
        }
        window.insetsController().apply {
            isAppearanceLightStatusBars = isLightBackground
            isAppearanceLightNavigationBars = isLightBackground
        }
    }

    /**
     * The host app's own name, for its row in the dock.
     *
     * Read off the package manager rather than a module string: the label the user knows is the one
     * on their home screen everywhere else, and the module has no business naming the app it is
     * embedded in.
     */
    private fun hostLabel(): String = runCatching {
        applicationInfo.loadLabel(packageManager).toString()
    }.getOrDefault(packageName)

    /**
     * Which Activity the host's own dock icon opens.
     *
     * An empty string means "whatever the launch intent resolves to at click time", which is the
     * right answer for most hosts and the fallback when the bridge names nothing.
     */
    private fun hostActivityName(): String =
        LauncherRegistry.bridge.hostLaunchComponent(this)?.className.orEmpty()
}
