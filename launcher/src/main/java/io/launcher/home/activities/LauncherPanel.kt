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
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
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
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.insetsController
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.extensions.realScreenSize
import org.fossify.commons.extensions.showErrorToast
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
import io.launcher.home.extensions.getDrawableForPackageName
import io.launcher.home.extensions.getLabel
import io.launcher.home.extensions.handleGridItemPopupMenu
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
import io.launcher.home.helpers.IconShaper
import io.launcher.home.helpers.PSEUDO_WIDGET_SEARCH
import io.launcher.home.helpers.REQUEST_ALLOW_BINDING_WIDGET
import io.launcher.home.helpers.REQUEST_CONFIGURE_WIDGET
import io.launcher.home.helpers.REQUEST_CREATE_SHORTCUT
import io.launcher.home.helpers.REQUEST_DEFAULT_SMS
import io.launcher.home.helpers.PACKAGE_REFRESH_DELAY_MS
import io.launcher.home.helpers.UNINSTALL_APP_REQUEST_CODE
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
    // Config.isDrawerEnabled, read once per resume rather than on every touch move.
    private var mDrawerEnabled = true
    private var mLongPressedIcon: HomeScreenGridItem? = null
    private var mOpenPopupMenu: PopupMenu? = null
    private var mLastTouchCoords = Pair(-1f, -1f)
    private var mActionOnCanBindWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnWidgetConfiguredWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnAddShortcut:
            ((shortcutId: String, label: String, icon: Drawable) -> Unit)? = null
    private var mActionOnDefaultSmsResult: (() -> Unit)? = null
    private var wasJustPaused: Boolean = false

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
    /** Re-applies what the launcherConfig drives; the reference ran this on every RC activation. */
    private fun onConfigUpdated() {
        if (isFinishing || isDestroyed) {
            return
        }

        refreshDefaultLauncherBanner()

        if (!isAllAppsFragmentExpanded() && IconCache.launchers.isNotEmpty()) {
            binding.allAppsFragmentUi.root.gotLaunchers(IconCache.launchers)
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
        private const val HOST_PANEL_WARM_UP_DELAY = 600L

        /** Samples per axis taken by [calculateAverageColor]; 16² points is plenty for a tint. */
        private const val COLOR_SAMPLE_EDGE = 16
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

        // Arrived from onboarding (OnboardingRoute HOME), or the Home-role grant landed here and
        // the gate called it the funnel's end: the user was promised the app, and here the app is
        // the inbox panel. Only on a fresh create - a recreate hands the same intent back, and the
        // panel state it had is restored on its own.
        // A host that wants the user to land *inside* its panel rather than on the grid — the end
        // of a first-run flow, say — starts this Activity with EXTRA_OPEN_HOST_PANEL. Only on a
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
                if (!isFinishing && !isDestroyed) binding.hostPanelUi.root.warmUp()
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
        openHostPanelIfAsked(intent)
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
        // Immediately, not inside the delay below. On a *forced* update this is the re-raise: Home
        // lands back on this Activity, and every millisecond before Play's screen returns is a
        // millisecond of workspace the block was supposed to be covering. It needs no layout and
        // nothing here waits on it — Play answers asynchronously either way.
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
        packageRefreshHandler.removeCallbacks(packageRefresh)
        if (isOreoMr1Plus() && wallpaperColorChangeListener != null) {
            WallpaperManager.getInstance(this)
                .removeOnColorsChangedListener(wallpaperColorChangeListener!!)
        }
    }

    override fun onPause() {
        super.onPause()
        wasJustPaused = true
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
                    binding.homeScreenGridUi.root.finalizeSwipe()
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
            for (checkedYCell in 0 until launcherConfig.homeColumnCount) {
                for (checkedXCell in 0 until launcherConfig.homeRowCount - 1) {
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

        val launchers = getAllAppLaunchers()
        binding.allAppsFragmentUi.root.gotLaunchers(launchers)
        binding.leftPanelUi.root.gotLaunchers(launchers)
        binding.widgetsFragmentUi.root.getAppWidgets()

        IconCache.launchers.map { it.packageName }.forEach { packageName ->
            if (!launchers.map { it.packageName }.contains(packageName)) {
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
        // launcher_config.search_widget changed: the old bar's view is a child of the grid, which a
        // refetch does not take away, so the screen is rebuilt around the new one.
        if (HomeWidgetSeeder.syncSearchWidget(this)) {
            runOnUiThread { if (!isFinishing && !isDestroyed) recreate() }
            return
        }
        if (!launcherConfig.isDrawerEnabled || launcherConfig.homeAndDrawer) {
            HomeAppsFiller.placeMissing(this, launchers)
        }
        HomeSeeder.placeNewInstalls(this, launchers)

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
     * layout. Also called from TextlyApplication on an rc_sync push. Returns true when it recreated.
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
        LauncherPromoController.run(this, LauncherAdsConfig.LEFT_SWIPE) {
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
     * Our own icon tapped in the drawer or the apps panel: the same as the dock icon — the host
     * panel slides in, or, with that panel switched off, the host's launch component opens.
     */
    fun openHostApp() {
        if (isAllAppsFragmentExpanded()) closeAppDrawer()
        if (isLeftPanelExpanded()) hideLeftPanel()
        if (LauncherAdsConfig.panelEnabled(LauncherAdsConfig.RIGHT_SWIPE)) {
            showHostPanel()
        } else {
            launchApp(packageName, hostActivityName())
        }
    }

    private fun showHostPanel() {
        // `launcher_config.panels.messages` - off means the gesture is inert, not a hidden panel.
        if (!LauncherAdsConfig.panelEnabled(LauncherAdsConfig.RIGHT_SWIPE)) {
            Timber.d("LauncherPanel: messages panel disabled by launcher_config")
            return
        }
        LauncherPromoController.run(this, LauncherAdsConfig.RIGHT_SWIPE) {
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
        binding.homeScreenGridUi.root.animate()
            .alpha(0f)
            .setDuration(animationDuration)
            .start()

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
            return
        }

        binding.mainHolderUi.performHapticFeedback()
        showMainLongPressMenu(x, y)
    }

    fun homeScreenClicked(eventX: Float, eventY: Float) {
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
                openHostApp()
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
        val anchorY = if (isOnAllAppsFragment || gridItem.type == ITEM_TYPE_WIDGET) {
            val iconSize = realScreenSize.x / launcherConfig.drawerColumnCount
            y - iconSize / 2f
        } else {
            val clickableRect = binding.homeScreenGridUi.root.getClickableRect(gridItem)
            clickableRect.top.toFloat() - binding.homeScreenGridUi.root.getCurrentIconSize() / 2f
        }

        binding.homeScreenPopupMenuAnchorUi.x = x
        binding.homeScreenPopupMenuAnchorUi.y = anchorY

        if (mOpenPopupMenu == null) {
            mOpenPopupMenu = handleGridItemPopupMenu(
                anchorView = binding.homeScreenPopupMenuAnchorUi,
                gridItem = gridItem,
                isOnAllAppsFragment = isOnAllAppsFragment,
                listener = menuListener
            )
        }
    }

    fun widgetLongPressedOnList(gridItem: HomeScreenGridItem) {
        mLongPressedIcon = gridItem
        hideFragment(binding.widgetsFragmentUi)
        binding.homeScreenGridUi.root.itemDraggingStarted(mLongPressedIcon!!)
    }

    private fun showMainLongPressMenu(x: Float, y: Float) {
        binding.homeScreenGridUi.root.hideResizeLines()
        binding.homeScreenPopupMenuAnchorUi.x = x
        binding.homeScreenPopupMenuAnchorUi.y =
            y - resources.getDimension(R.dimen.launcher_long_press_anchor_button_offset_y) * 2
        val contextTheme = ContextThemeWrapper(this, getPopupMenuTheme())
        PopupMenu(
            contextTheme,
            binding.homeScreenPopupMenuAnchorUi,
            Gravity.TOP or Gravity.END
        ).apply {
            inflate(R.menu.lnch_menu_home_screen)
            menu.findItem(R.id.set_as_defaultUi).isVisible = !isDefaultLauncher()
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.widgetsUi -> showWidgetsFragment()
                    R.id.wallpapersUi -> launchWallpapersIntent()
                    R.id.launcher_settingsUi -> launchSettings()
                    // Same escalation the banner runs, so the two routes to the role cannot drift.
                    R.id.set_as_defaultUi -> defaultLauncherRequester.start()
                }
                true
            }
            show()
        }
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

    private fun showWidgetsFragment() {
        showFragment(binding.widgetsFragmentUi)
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
            Intent(this@LauncherPanel, LauncherPrefsPanel::class.java)
        )
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

        override fun beforeShow(menu: Menu) {
            var visibleMenuItems = 0
            for (item in menu.iterator()) {
                if (item.isVisible) {
                    visibleMenuItems++
                }
            }
            val yOffset =
                resources.getDimension(R.dimen.launcher_long_press_anchor_button_offset_y) * (visibleMenuItems - 1)
            binding.homeScreenPopupMenuAnchorUi.y -= yOffset
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
                if (velocityX > 0) {
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

    override fun onFlingRight() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        // A page already being dragged this way is committed by the fling; the side panel takes a
        // fling only where no page could be pulled in (the first page, or the drawer up).
        if (binding.homeScreenGridUi.root.flingSwipe(towardsNext = false)) {
            return
        }
        if (!isAllAppsFragmentExpanded() && !isWidgetsFragmentExpanded()) {
            showHostPanel()
        } else {
            binding.homeScreenGridUi.root.prevPage(redraw = true)
        }
    }

    override fun onFlingLeft() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        // see onFlingRight: a dragged page is committed, the panel takes the rest
        if (binding.homeScreenGridUi.root.flingSwipe(towardsNext = true)) {
            return
        }
        if (!isAllAppsFragmentExpanded() && !isWidgetsFragmentExpanded()) {
            showLeftPanel()
        } else {
            binding.homeScreenGridUi.root.nextPage(redraw = true)
        }
    }

    /**
     * The installed set, in a form cheap enough to compare on every resume.
     *
     * `sourceDir` is what makes this catch app *updates* as well as installs and removals: the
     * installer writes an updated APK to a fresh directory, so the path moves whenever the icon
     * behind it could have. It rides along on the ResolveInfo already fetched, so this costs no
     * extra binder call.
     *
     * The hidden set is folded in because it is the other input to the result — without it, hiding
     * or unhiding an icon would leave the drawer showing the old list until something else
     * invalidated the cache.
     */
    private fun installedSignature(
        list: List<ResolveInfo>,
        hiddenIcons: List<String>,
    ): String {
        val apps = list
            .map { "${it.activityInfo.packageName}/${it.activityInfo.name}/${it.activityInfo.applicationInfo.sourceDir}" }
            .sorted()
        return apps.joinToString("\n") + "\u0000" + hiddenIcons.sorted().joinToString("\n")
    }

    // Two resumes in quick succession (a Home press landing on a fresh process, a new intent)
    // each start a background rebuild before either has filled the cache; the second waits here
    // and then finds the first one's answer by signature instead of decoding every icon again.
    private val launchersLock = Any()

    @SuppressLint("WrongConstant")
    fun getAllAppLaunchers(): ArrayList<AppLauncher> = synchronized(launchersLock) {
        val hiddenIcons = hiddenIconsDB.getHiddenIcons().map {
            it.getIconIdentifier()
        }

        val allApps = ArrayList<AppLauncher>()
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

        val simpleLauncher = applicationContext.packageName
        val microG = "com.google.android.gms"
        val list = packageManager.queryIntentActivities(intent, PackageManager.PERMISSION_GRANTED)

        // Everything below decodes an icon per installed app. Skipped outright when the installed
        // set is byte-for-byte what it was when the cache was built — which is every resume that is
        // just the user pressing Home. The signature is derived from `list`, which we needed
        // anyway, so the check itself is one already-paid binder call plus a string compare.
        val signature = installedSignature(list, hiddenIcons)
        val cached = IconCache.launchers
        if (cached.isNotEmpty() && signature == IconCache.installedSignature) {
            return@synchronized ArrayList(cached)
        }

        val started = android.os.SystemClock.elapsedRealtime()
        val wanted = list.filter { info ->
            val packageName = info.activityInfo.applicationInfo.packageName
            // Our own app is listed too (unless the fake uninstall hid it); tapping it goes
            // through openHostApp, not its LAUNCHER entry, which would only route back here.
            (packageName != simpleLauncher || !launcherConfig.selfIconHidden) && packageName != microG &&
                !hiddenIcons.contains("$packageName/${info.activityInfo.name}")
        }

        // Nothing to show yet (first run, or the process came back with an empty table): hand
        // the drawer and the apps panel the labels straight away, on their placeholder tiles,
        // so the lists are usable while the icons below are still rendering.
        if (cached.isEmpty()) {
            val quick = ArrayList(wanted.map { info ->
                AppLauncher(
                    id = null,
                    title = info.loadLabel(packageManager).toString(),
                    packageName = info.activityInfo.applicationInfo.packageName,
                    activityName = info.activityInfo.name,
                    order = 0,
                    thumbnailColor = 0,
                    drawable = null
                )
            })
            binding.allAppsFragmentUi.root.gotLaunchers(quick)
            binding.leftPanelUi.root.gotLaunchers(quick)
        }

        // A launcher already cached from the same APK keeps its icon: one app installed or removed
        // decodes one icon, not every installed app's again.
        fun keyOf(info: ResolveInfo) = "${info.activityInfo.applicationInfo.packageName}/${info.activityInfo.name}"
        val sourceDirs = wanted.associate { keyOf(it) to it.activityInfo.applicationInfo.sourceDir.orEmpty() }
        val knownDirs = IconCache.sourceDirs
        val reusable = cached
            .filter { it.drawable != null }
            .associateBy { "${it.packageName}/${it.activityName}" }
            .filterKeys { key -> knownDirs[key] != null && knownDirs[key] == sourceDirs[key] }

        // One icon per installed app: an XML inflate, a render and a colour average each, all
        // independent, so they are spread over the cores rather than queued on one thread. The
        // render is capped at the size any list here draws it - an adaptive icon's intrinsic
        // size is 108 dp, which at this density is a 400 px bitmap nobody ever sees.
        val iconPx = resources.getDimensionPixelSize(R.dimen.launcher_icon_cache_size)
        val legacyTray = launcherConfig.legacyIconTray
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        )
        try {
            val decoded = wanted.map { info ->
                reusable[keyOf(info)]?.let { kept ->
                    return@map java.util.concurrent.CompletableFuture.completedFuture<AppLauncher?>(
                        kept.copy(title = info.loadLabel(packageManager).toString())
                    )
                }
                pool.submit<AppLauncher?> {
                    val packageName = info.activityInfo.applicationInfo.packageName
                    val drawable = info.loadIcon(packageManager)?.let { IconShaper.shape(it, legacyTray) }
                        ?: getDrawableForPackageName(packageName)
                        ?: return@submit null
                    val bitmap = drawable.toBitmap(
                        width = drawable.intrinsicWidth.coerceIn(1, iconPx),
                        height = drawable.intrinsicHeight.coerceIn(1, iconPx),
                        config = Bitmap.Config.ARGB_8888
                    )
                    AppLauncher(
                        id = null,
                        title = info.loadLabel(packageManager).toString(),
                        packageName = packageName,
                        activityName = info.activityInfo.name,
                        order = 0,
                        thumbnailColor = calculateAverageColor(bitmap),
                        drawable = bitmap.toDrawable(resources)
                    )
                }
            }
            // One app whose icon will not load (a broken resource, an uninstall mid-decode) is
            // skipped, the way the loop it replaced skipped it - not the whole list dropped.
            decoded.forEach { future -> runCatching { future.get() }.getOrNull()?.let(allApps::add) }
        } finally {
            pool.shutdown()
        }

        Timber.d("LauncherPanel: ${allApps.size} launchers, ${allApps.size - reusable.size} icons decoded in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        launchersDB.insertAll(allApps)
        // Last, so a throw anywhere above leaves the signature stale and the next resume retries
        // rather than caching a half-built list.
        IconCache.installedSignature = signature
        IconCache.sourceDirs = sourceDirs
        allApps
    }

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
     * The app the dock's reserved slot should carry - the host by default, or whatever the host
     * names instead. See [io.launcher.home.api.LauncherBridge.dockSlotPackage]: a host that follows
     * a role rather than a package answers there, and the sync below swaps the row when the answer
     * changes.
     */
    private fun dockSlotPackage(): String? = LauncherRegistry.bridge.dockSlotPackage(this)

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
     * package is not in the launcher list (getAllAppLaunchers skips it), so its row is built by
     * hand and points at the standalone inbox rather than the LAUNCHER entry, which would only
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

    // taken from https://gist.github.com/maxjvh/a6ab15cbba9c82a5065d
    /**
     * The mean colour of [bitmap], sampled on a grid rather than read whole.
     *
     * The result is a *placeholder* tint — LaunchersLineup shows it behind the icon only until
     * Glide has the real drawable — so a grid sample is as good as the exact mean. Reading every
     * pixel was not: it allocated an `IntArray(width * height)`, roughly 147 KB for a 192 px
     * adaptive icon, once per installed app, every time the launcher set was rebuilt. On a device
     * with a few hundred apps that is tens of MB of short-lived garbage in a tight loop, and the GC
     * pressure it creates is paid back as longer pauses on the main thread.
     *
     * One reused row buffer and at most [COLOR_SAMPLE_EDGE]² samples instead.
     */
    private fun calculateAverageColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return Color.TRANSPARENT
        }

        val stepX = max(1, width / COLOR_SAMPLE_EDGE)
        val stepY = max(1, height / COLOR_SAMPLE_EDGE)
        val row = IntArray(width)

        var red = 0L
        var green = 0L
        var blue = 0L
        var n = 0
        var y = 0
        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                val color = row[x]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                n++
                x += stepX
            }
            y += stepY
        }

        // stepX/stepY are at least 1 and the loops run at least once, so n > 0 here; the guard is
        // for the degenerate bitmap the check above already excludes, not for a reachable path.
        if (n == 0) {
            return Color.TRANSPARENT
        }

        return Color.rgb((red / n).toInt(), (green / n).toInt(), (blue / n).toInt())
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
