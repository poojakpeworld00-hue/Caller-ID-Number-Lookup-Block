package io.launcher.home.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.content.pm.LauncherApps
import android.os.Process
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import io.launcher.home.databinding.LnchItemSearchShortcutBinding
import io.launcher.home.extensions.appUsageDB
import io.launcher.home.helpers.AppSuggestions
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.views.MyGridLayoutManager
import io.launcher.home.R
import io.launcher.home.activities.LauncherPanel
import io.launcher.home.adapters.DrawerPagesLineup
import io.launcher.home.adapters.LaunchersLineup
import io.launcher.home.profile.DrawerMode
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import io.launcher.home.databinding.LnchAllAppsFragmentBinding
import io.launcher.home.extensions.applyDrawerSkin
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.extensions.launchApp
import io.launcher.home.promo.LauncherAdsConfig
import io.launcher.home.api.LauncherRegistry
import io.launcher.home.promo.SponsoredTiles
import io.launcher.home.extensions.setupDrawerBackground
import io.launcher.home.helpers.ITEM_TYPE_ICON
import io.launcher.home.interfaces.AllAppsListener
import io.launcher.home.models.AppLauncher
import io.launcher.home.models.HomeScreenGridItem
import io.launcher.home.models.appLauncherComparator

class DrawerSurface(
    context: Context,
    attributeSet: AttributeSet
) : LauncherSurface<LnchAllAppsFragmentBinding>(context, attributeSet), AllAppsListener {

    companion object {
        /** The search cards are four across whatever the drawer's own column count (One UI's Finder is too). */
        private const val SEARCH_COLUMNS = 4
        private const val SEARCH_MAX_RESULTS = 8
        private const val SEARCH_MAX_SHORTCUTS = 4
    }

    private var lastTouchCoords = Pair(0f, 0f)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var touchDownY = -1
    var ignoreTouches = false

    private var launchers = emptyList<AppLauncher>()

    /**
     * [setupViews] runs on every resume — LauncherPanel.onResume re-runs it once the layout settles —
     * and everything in it is an assignment except the scroll listener, which appends. Left
     * unguarded the grid ends up with one listener per resume, each doing the `hasFocus` check on
     * every scrolled frame.
     */
    private var scrollListenerAdded = false

    /**
     * The drawer's native slot. Built here rather than in the layout because it is not a sibling of
     * the grid: [LaunchersLineup] hosts it in a full-span row of the grid — at the row
     * `launcher.screens.app_drawer.ad_row_position` names — so it scrolls away with the apps.
     * Starts GONE — the host's ad wiring reveals it only once a fill arrives, and until then the row
     * it lives in wraps to nothing.
     */
    private val adSlot by lazy {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
    }

    private var launchersAdapter: LaunchersLineup? = null

    // The paged (Samsung-style) drawer: one grid per page in the ViewPager2, dots underneath.
    // Which of the two surfaces is live follows Config.drawerMode - see setupAdapter.
    private var pagesAdapter: DrawerPagesLineup? = null
    private var shownPaged: Boolean? = null
    private var touchDownX = -1

    private val isPaged: Boolean get() = context.launcherConfig.drawerMode == DrawerMode.PAGED

    @SuppressLint("ClickableViewAccessibility")
    override fun setupFragment(activity: LauncherPanel) {
        this.activity = activity
        this.binding = LnchAllAppsFragmentBinding.bind(this)

        // the slot's row spans the whole width; every other position is a single app icon. Which
        // position that is comes from the launcherConfig, so it is asked of the adapter rather than fixed
        // here — see submitList.
        val layoutManager = binding.allAppsGridUi.layoutManager as MyGridLayoutManager
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (getAdapter()?.isAdSlotPosition(position) == true) layoutManager.spanCount else 1
        }

        binding.allAppsGridUi.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchDownY = -1
            }

            return@setOnTouchListener false
        }

        binding.allAppsPagerUi.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = renderDots(selected = position)
        })
    }

    /**
     * Requests the drawer's slot afresh.
     *
     * Driven by `LauncherPanel.showFragment` on every open rather than once from [setupFragment],
     * for the same reason as `AppsPanelSurface.refreshAds`: the drawer is built at boot and then
     * only parked below the screen, so a single request there would pin one creative for the life
     * of the process.
     *
     * [adSlot] is a stable view that the adapter re-parents into whichever holder owns the ad row,
     * so it can be refreshed while it is detached — a fill that lands then is already in place by
     * the time the row scrolls back in.
     *
     * Whether the refresh happens at all is the console's call — see
     * [LauncherAdsConfig.reloadAdOnOpen]. The drawer is opened far more often than either panel, so
     * it is the placement most likely to want the flag off.
     */
    fun refreshAds() {
        LauncherRegistry.ads.bindNativeOnOpen(activity, LauncherAdsConfig.KEY_APP_DRAWER, adSlot)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupDrawerBackground(context.getColor(R.color.launcher_all_app_bg))
    }

    @SuppressLint("NotifyDataSetChanged")
    fun onResume() {
        if (binding.allAppsGridUi.layoutManager == null || (binding.allAppsGridUi.adapter == null && pagesAdapter == null)) {
            return
        }

        val layoutManager = binding.allAppsGridUi.layoutManager as MyGridLayoutManager
        if (layoutManager.spanCount != context.launcherConfig.drawerColumnCount || shownPaged != isPaged) {
            onConfigurationChanged()
            // Force redraw due to changed item size
            getAdapter()?.notifyDataSetChanged()
        }
    }

    fun onConfigurationChanged() {
        binding.allAppsGridUi.scrollToPosition(0)
        binding.allAppsPagerUi.setCurrentItem(0, false)
        setupViews()

        val layoutManager = binding.allAppsGridUi.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = context.launcherConfig.drawerColumnCount
        setupAdapter(launchers)
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) {
            return super.onInterceptTouchEvent(event)
        }

        var shouldIntercept = false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y.toInt()
                touchDownX = event.x.toInt()
            }

            MotionEvent.ACTION_MOVE -> {
                if (ignoreTouches) {
                    // some devices ACTION_MOVE keeps triggering for the whole long press duration, but we are interested in real moves only, when coords change
                    if (lastTouchCoords.first != event.x || lastTouchCoords.second != event.y) {
                        touchDownY = -1
                        return true
                    }
                }

                // pull the whole fragment down if it is scrolled way to the top and the user pulls it even further.
                // a held finger always drifts a pixel or two, so this has to clear the touch slop: intercepting on
                // any downward movement cancels the icon's pending long press and the popup menu never appears
                if (touchDownY != -1) {
                    val distance = event.y.toInt() - touchDownY
                    // On the pager a page swipe drifts a little vertically; only a pull that is
                    // mostly downward may take the sheet away from it.
                    val mostlyVertical = !isPaged || abs(distance) > abs(event.x.toInt() - touchDownX)
                    shouldIntercept = distance > touchSlop && mostlyVertical && isGridAtTop()
                    if (shouldIntercept) {
                        // Hiding is expensive, only do it if focused
                        if (binding.searchBarUi.hasFocus()) {
                            activity?.hideKeyboard()
                        }
                        activity?.startHandlingTouches(touchDownY)
                        touchDownY = -1
                    }
                }
            }
        }

        lastTouchCoords = Pair(event.x, event.y)
        return shouldIntercept
    }

    /**
     * Whether the grid is scrolled all the way to its top, read off the first laid-out child.
     *
     * Not `computeVerticalScrollOffset() == 0`: that is an estimate, and GridLayoutManager skips
     * zero-height children when it looks for the first visible row. The ad row is exactly that
     * while its slot is GONE - which is every open until a fill arrives, and always when the
     * placement is off - so with the row at position 0 the estimate reads one row of "scrolled
     * away" for a grid that has never moved, and the pull-down never intercepted. The reference
     * app asks its scroll view for `scrollY == 0`; this is the same question put to the grid.
     */
    private fun isGridAtTop(): Boolean {
        // Pages never scroll vertically: the sheet is always at its top.
        if (isPaged) return true
        val grid = binding.allAppsGridUi
        val first = grid.getChildAt(0) ?: return true
        val layoutManager = grid.layoutManager ?: return true
        return grid.getChildAdapterPosition(first) <= 0 &&
            layoutManager.getDecoratedTop(first) >= grid.paddingTop
    }

    fun gotLaunchers(appLaunchers: List<AppLauncher>) {
        launchers = appLaunchers.sortedWith(appLauncherComparator)

        setupAdapter(launchers)
    }

    /** The current launcher entry (with its decoded icon) for each of [apps], by package and activity; dropped if gone. */
    private fun live(apps: List<AppLauncher>, current: List<AppLauncher>): List<AppLauncher> {
        val byKey = current.associateBy { it.packageName to it.activityName }
        return apps.mapNotNull { byKey[it.packageName to it.activityName] ?: current.firstOrNull { c -> c.packageName == it.packageName } }
    }

    // held here rather than read back off the RecyclerView, which hands it back as a plain
    // RecyclerView.Adapter
    private fun getAdapter() = launchersAdapter

    // Gated here rather than inside launchApp: the left panel launches apps through the same
    // extension, and this placement is the *drawer's*. Shared by the scrolling grid and every page.
    private val onItemClick: (Any) -> Unit = {
        val launcher = it as AppLauncher
        // The app-launch promo is applied inside launchApp, for every surface that opens an app.
        if (SponsoredTiles.isSponsored(launcher)) {
            activity?.let { a -> SponsoredTiles.open(a, launcher) }
        } else if (launcher.packageName == context.applicationContext.packageName) {
            activity?.openHostApp()
        } else {
            activity?.launchApp(launcher.packageName, launcher.activityName)
        }
        if (activity?.launcherConfig?.closeAppDrawer == true) {
            activity?.closeAppDrawer(delayed = true)
        }
        ignoreTouches = false
        touchDownY = -1
    }

    private fun setupAdapter(launchers: List<AppLauncher>) {
        activity?.runOnUiThread {
            val paged = isPaged
            val layoutManager = binding.allAppsGridUi.layoutManager as MyGridLayoutManager
            layoutManager.spanCount = context.launcherConfig.drawerColumnCount

            // One surface at a time. The slot is a single view, so the surface that goes away
            // must let go of it: its adapter is dropped and rebuilt on the way back.
            if (shownPaged != paged) {
                shownPaged = paged
                binding.allAppsGridUi.beVisibleIf(!paged)
                binding.allAppsPagerUi.beVisibleIf(paged)
                binding.allAppsPageDotsUi.beVisibleIf(paged)
                (adSlot.parent as? ViewGroup)?.removeView(adSlot)
                if (paged) {
                    launchersAdapter = null
                    binding.allAppsGridUi.adapter = null
                } else {
                    pagesAdapter = null
                    binding.allAppsPagerUi.adapter = null
                }
            }

            if (paged) {
                if (pagesAdapter == null) {
                    pagesAdapter = DrawerPagesLineup(activity!!, this, adSlot, onItemClick)
                    binding.allAppsPagerUi.adapter = pagesAdapter
                }
            } else if (getAdapter() == null) {
                LaunchersLineup(activity!!, this, adSlot, onItemClick).apply {
                    launchersAdapter = this
                    binding.allAppsGridUi.itemAnimator = null
                    binding.allAppsGridUi.adapter = this
                }
            }

            submitList(launchers.toMutableList())
        }
    }

    /**
     * Cuts the list into pages of `drawerColumnCount` x `drawerRowCount`. The native slot takes a
     * full row of the first page, at the row the console names, so that page holds one row fewer.
     */
    private fun chunkPages(apps: List<AppLauncher>): List<List<AppLauncher>> {
        if (apps.isEmpty()) return emptyList()
        val columns = context.launcherConfig.drawerColumnCount.coerceAtLeast(1)
        val rows = context.launcherConfig.drawerRowCount.coerceAtLeast(2)
        val perPage = columns * rows
        val firstPageApps = (perPage - columns).coerceAtLeast(columns)
        val pages = ArrayList<List<AppLauncher>>()
        val first = apps.take(firstPageApps).toMutableList()
        val adIndex = (LauncherAdsConfig.drawerAdRowPosition() * columns).coerceIn(0, first.size)
        first.add(adIndex, LaunchersLineup.AD_SLOT)
        pages.add(first)
        apps.drop(firstPageApps).chunked(perPage).forEach(pages::add)
        return pages
    }

    private fun renderDots(count: Int = pagesAdapter?.itemCount ?: 0, selected: Int = binding.allAppsPagerUi.currentItem) {
        val dots = binding.allAppsPageDotsUi
        val size = resources.getDimensionPixelSize(R.dimen.launcher_drawer_page_dot)
        val gap = resources.getDimensionPixelSize(R.dimen.launcher_drawer_page_dot_gap)
        while (dots.childCount > count) dots.removeViewAt(dots.childCount - 1)
        while (dots.childCount < count) {
            dots.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginStart = gap; it.marginEnd = gap }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(context.getColor(android.R.color.white))
                }
            })
        }
        for (i in 0 until dots.childCount) dots.getChildAt(i).alpha = if (i == selected) 1f else 0.35f
        dots.beVisibleIf(isPaged && count > 1)
    }

    /** Back to the first page, the way the scrolling drawer goes back to its top on close. */
    fun resetToFirstPage() {
        if (binding.allAppsPagerUi.adapter != null) binding.allAppsPagerUi.setCurrentItem(0, false)
    }

    fun onIconHidden(item: HomeScreenGridItem) {
        val itemToRemove = launchers.firstOrNull {
            it.getLauncherIdentifier() == item.getItemIdentifier()
        }

        if (itemToRemove != null) {
            val position = launchers.indexOfFirst {
                it.getLauncherIdentifier() == item.getItemIdentifier()
            }

            launchers = launchers.toMutableList().apply {
                removeAt(position)
            }

            submitList(launchers.toMutableList())
        }
    }

    fun setupViews() {
        if (activity == null) {
            return
        }

        if (!scrollListenerAdded) {
            scrollListenerAdded = true
            binding.allAppsGridUi.addOnScrollListener(object : OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // Hiding is expensive, only do it if focused
                    if (binding.searchBarUi.hasFocus() && dy > 0 && binding.allAppsGridUi.computeVerticalScrollOffset() > 0) {
                        activity?.hideKeyboard()
                    }
                }
            })
        }

        setupDrawerBackground(context.getColor(R.color.launcher_all_app_bg))

        applySearchPosition()
        applyBottomSearchClearance()
        binding.searchBarUi.beVisibleIf(context.launcherConfig.showSearchBar)
        binding.searchBarUi.requireToolbar().beGone()
        binding.searchBarUi.updateColors()
        binding.searchBarUi.applyDrawerSkin()
        binding.searchBarUi.setupMenu()

        binding.searchBarUi.onSearchTextChangedListener = {
            submitList(launchers)
        }
        binding.searchBarUi.onSearchOpenListener = { showSearchPanel(true) }
        binding.searchBarUi.onSearchClosedListener = { showSearchPanel(false) }

        binding.searchBarUi.binding.topToolbarSearch.setOnEditorActionListener { _, actionId, _ ->
            if (binding.searchBarUi.getCurrentQuery().isEmpty()) return@setOnEditorActionListener false
            when (actionId) {
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_GO -> (if (isPaged) pagesAdapter?.firstPageAdapter else getAdapter())?.launchFirstApp() == true
                else -> false
            }
        }
    }

    /**
     * Puts the search bar at the top (the default) or the bottom of the sheet, the way One UI 6.1
     * moved its Finder bar, and re-anchors the grid, the pager, its dots and the placeholder around
     * it. Pure RelativeLayout rules, so the same layout serves both.
     */
    private var appliedSearchAtBottom: Boolean? = null

    private fun applySearchPosition() {
        val bottom = context.launcherConfig.drawerSearchAtBottom
        // setupViews runs from a global-layout hook; re-anchoring on every pass would request a
        // layout from inside a layout and starve the UI thread. Only a real change touches the rules.
        if (appliedSearchAtBottom == bottom) return
        appliedSearchAtBottom = bottom
        val search = binding.searchBarUi.id
        val dots = binding.allAppsPageDotsUi.id

        fun RelativeLayout.LayoutParams.anchor(vararg rules: Pair<Int, Int>) {
            for (verb in listOf(RelativeLayout.ABOVE, RelativeLayout.BELOW, RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_BOTTOM)) {
                removeRule(verb)
            }
            rules.forEach { (verb, subject) -> addRule(verb, subject) }
        }

        (binding.searchBarUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ALIGN_PARENT_BOTTOM to RelativeLayout.TRUE else RelativeLayout.ALIGN_PARENT_TOP to RelativeLayout.TRUE
        )
        (binding.allAppsGridUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ABOVE to search else RelativeLayout.BELOW to search
        )
        (binding.allAppsPageDotsUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ABOVE to search else RelativeLayout.ALIGN_PARENT_BOTTOM to RelativeLayout.TRUE
        )
        (binding.allAppsPagerUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ALIGN_PARENT_TOP to RelativeLayout.TRUE else RelativeLayout.BELOW to search,
            RelativeLayout.ABOVE to dots,
        )
        (binding.noResultsPlaceholderUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ALIGN_PARENT_TOP to RelativeLayout.TRUE else RelativeLayout.BELOW to search
        )
        // The search cards always stack from the top of the sheet (One UI's Finder keeps them
        // there with its bar at the bottom); the bar's edge only decides where the panel ends.
        (binding.searchPanelUi.layoutParams as RelativeLayout.LayoutParams).anchor(
            if (bottom) RelativeLayout.ALIGN_PARENT_TOP to RelativeLayout.TRUE else RelativeLayout.BELOW to search,
            if (bottom) RelativeLayout.ABOVE to search else RelativeLayout.ALIGN_PARENT_BOTTOM to RelativeLayout.TRUE,
        )
        binding.searchPanelUi.gravity = Gravity.TOP
        binding.searchBarUi.requestLayout()
    }

    /**
     * A bottom search bar sits on the navigation bar (and the keyboard, once it opens). The grid
     * already carries exactly that clearance as bottom padding from setupEdgeToEdge - the window
     * root consumes the insets, so they cannot be read here - and padding a search menu itself
     * re-dispatches insets without settling. So the bar mirrors the grid's padding as a margin,
     * re-checked on every layout and written only when it differs (as padding: the menu's own
     * layout does not honour a bottom margin).
     */
    private fun applyBottomSearchClearance() {
        if (!clearanceWatchAdded) {
            clearanceWatchAdded = true
            viewTreeObserver.addOnGlobalLayoutListener { syncBottomSearchClearance() }
        }
        syncBottomSearchClearance()
    }

    private var clearanceWatchAdded = false

    private fun syncBottomSearchClearance() {
        val bottom = context.launcherConfig.drawerSearchAtBottom
        val wanted = if (bottom) binding.allAppsGridUi.paddingBottom else 0
        val bar = binding.searchBarUi
        if (bar.paddingBottom != wanted) {
            bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, wanted)
        }
        // With the bar at the bottom nothing sits above the list, so its first row gets a gap of its
        // own below the status bar. Kept here, on every layout, because inset passes rewrite padding.
        val grid = binding.allAppsGridUi
        val topGap = if (bottom) resources.getDimensionPixelSize(R.dimen.launcher_drawer_list_top_gap) else 0
        if (grid.paddingTop != topGap) {
            grid.setPadding(grid.paddingLeft, topGap, grid.paddingRight, grid.paddingBottom)
        }
        syncDotsToSearchPill(bottom)
    }

    /**
     * The page dots sit a fixed gap above the search *pill*, not above the search bar: the bar is an
     * app-bar layout with toolbar chrome above the pill, so anchoring to its edge left the dots
     * floating, and the pages above them short by as much. A negative bottom margin lets the dots
     * (and the pager anchored above them) reclaim that chrome. Applied only on a real change.
     */
    private fun syncDotsToSearchPill(bottom: Boolean) {
        val dots = binding.allAppsPageDotsUi
        val params = dots.layoutParams as? RelativeLayout.LayoutParams ?: return
        // setupEdgeToEdge pads the dots by the navigation-bar inset for the top-bar layout, where
        // they sit on the nav bar. Under a bottom bar that clearance is already the bar's own
        // (syncBottomSearchClearance), so here the dots keep only the layout's 8 dp.
        if (bottom) {
            val own = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.medium_margin)
            if (dots.paddingBottom != own) dots.setPadding(dots.paddingLeft, dots.paddingTop, dots.paddingRight, own)
        }
        val margin = if (bottom) {
            val pill = binding.searchBarUi.binding.toolbarContainer
            var pillTop = 0
            var v: View? = pill
            while (v != null && v !== binding.searchBarUi) { pillTop += v.top; v = v.parent as? View }
            if (v == null || pill.height == 0) return
            val gap = resources.getDimensionPixelSize(R.dimen.launcher_page_dots_search_gap)
            -(pillTop - gap).coerceAtLeast(0)
        } else {
            0
        }
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin
            dots.layoutParams = params
        }
    }

    private fun showNoResultsPlaceholderIfNeeded() {
        val itemCount = if (isPaged) pagesAdapter?.itemCount else getAdapter()?.itemCount
        binding.noResultsPlaceholderUi.beVisibleIf(itemCount != null && itemCount == 0)
    }

    override fun onAppLauncherLongPressed(x: Float, y: Float, appLauncher: AppLauncher) {
        val gridItem = HomeScreenGridItem(
            id = null,
            left = -1,
            top = -1,
            right = -1,
            bottom = -1,
            page = 0,
            packageName = appLauncher.packageName,
            activityName = appLauncher.activityName,
            title = appLauncher.title,
            type = ITEM_TYPE_ICON,
            className = "",
            widgetId = -1,
            shortcutId = "",
            icon = null,
            docked = false,
            parentId = null,
            drawable = appLauncher.drawable
        )

        activity?.showHomeIconMenu(x, y, gridItem, true)
        ignoreTouches = true

        binding.searchBarUi.closeSearch()
    }

    /**
     * Closes the drawer's search if it is open.
     *
     * Called as the drawer is dismissed rather than instead of dismissing it: Back closes the
     * drawer in a single press (see LauncherPanel.onBackPressedCompat), and this only makes sure the
     * next open does not come back with the last query still in the bar.
     */
    fun resetSearch() {
        if (binding.searchBarUi.isSearchOpen) {
            binding.searchBarUi.closeSearch()
        }
    }

    private fun submitList(items: List<AppLauncher>) {
        val searchQuery = binding.searchBarUi.getCurrentQuery()
        if (binding.searchBarUi.isSearchOpen) {
            renderSearchPanel(items, searchQuery)
            return
        }
        val filtered = if (searchQuery.isNotEmpty()) {
            items.filter {
                it.title.normalizeString()
                    .contains(searchQuery.normalizeString(), ignoreCase = true)
            }
        } else {
            SponsoredTiles.interleave(items)
        }

        if (isPaged) {
            pagesAdapter?.submitPages(chunkPages(filtered), context.launcherConfig.drawerColumnCount, context.launcherConfig.drawerRowCount.coerceAtLeast(2))
            binding.allAppsPagerUi.setCurrentItem(0, false)
            renderDots(selected = 0)
            showNoResultsPlaceholderIfNeeded()
            return
        }
        getAdapter()?.submitList(withAdRow(filtered)) {
            showNoResultsPlaceholderIfNeeded()
        }
    }

    // ---- Search panel -------------------------------------------------------------------------
    //
    // While the search bar is open the grid / pages step aside for two cards, the way One UI's
    // Finder does it: "Suggested apps" (AppSuggestions over the launcher's own launch log) until
    // something is typed, then "Searched apps" with the matched prefix tinted and, under it, the
    // top match's app shortcuts.

    private var searchAdapter: LaunchersLineup? = null
    private var suggestions: List<AppLauncher>? = null
    private var suggestionsGeneration = 0

    private fun showSearchPanel(open: Boolean) {
        binding.searchPanelUi.beVisibleIf(open)
        if (open) {
            binding.allAppsGridUi.beGone()
            binding.allAppsPagerUi.beGone()
            binding.allAppsPageDotsUi.beGone()
            binding.noResultsPlaceholderUi.beGone()
            suggestions = null
            loadSuggestions()
            renderSearchPanel(launchers, binding.searchBarUi.getCurrentQuery())
        } else {
            val paged = isPaged
            binding.allAppsGridUi.beVisibleIf(!paged)
            binding.allAppsPagerUi.beVisibleIf(paged)
            binding.allAppsPageDotsUi.beVisibleIf(paged)
            searchAdapter?.highlight = ""
            submitList(launchers)
        }
    }

    private fun searchGridAdapter(): LaunchersLineup {
        searchAdapter?.let { return it }
        val grid = binding.searchAppsGridUi
        grid.layoutManager = object : GridLayoutManager(context, SEARCH_COLUMNS) {
            override fun canScrollVertically() = false
        }
        grid.itemAnimator = null
        grid.isNestedScrollingEnabled = false
        // Rows breathe the way the Finder's do; the item layout alone packs them tighter.
        val rowGap = resources.getDimensionPixelSize(R.dimen.launcher_search_card_row_gap)
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                outRect.top = if (position >= SEARCH_COLUMNS) rowGap else 0
            }
        })
        return LaunchersLineup(activity!!, this, null, onItemClick).also {
            searchAdapter = it
            grid.adapter = it
        }
    }

    /** The launcher's own launch log ranked, off the main thread; the card fills in when it lands. */
    private fun loadSuggestions() {
        val generation = ++suggestionsGeneration
        val installed = launchers
        val pm = context.packageManager
        ensureBackgroundThread {
            val usage = runCatching { context.appUsageDB.getAll() }.getOrDefault(emptyList())
            val facts = HashMap<String, AppSuggestions.Facts?>()
            val ranked = AppSuggestions.rank(installed, usage, { pkg ->
                facts.getOrPut(pkg) {
                    runCatching {
                        val info = pm.getPackageInfo(pkg, 0)
                        AppSuggestions.Facts(info.firstInstallTime, info.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED)
                    }.getOrNull()
                }
            }, System.currentTimeMillis())
            activity?.runOnUiThread {
                if (generation != suggestionsGeneration) return@runOnUiThread
                suggestions = ranked
                if (binding.searchBarUi.isSearchOpen && binding.searchBarUi.getCurrentQuery().isEmpty()) {
                    renderSearchPanel(launchers, "")
                }
            }
        }
    }

    private fun renderSearchPanel(items: List<AppLauncher>, query: String) {
        val adapter = searchGridAdapter()
        if (query.isEmpty()) {
            // Resolved against the list the drawer holds now: the icon cache may have refreshed
            // (new AppLauncher objects, decoded icons) since the ranking was taken.
            val apps = live(suggestions ?: emptyList(), items)
            binding.searchAppsTitleUi.setText(R.string.launcher_suggested_apps)
            adapter.highlight = ""
            adapter.submitList(apps.toMutableList())
            binding.searchAppsCardUi.beVisibleIf(apps.size >= AppSuggestions.MIN_TO_SHOW)
            binding.searchShortcutsCardUi.beGone()
            binding.noResultsPlaceholderUi.beGone()
            return
        }
        val needle = query.normalizeString()
        val matches = items.filter { it.title.normalizeString().contains(needle, ignoreCase = true) }
            .sortedWith(compareBy<AppLauncher> { !it.title.normalizeString().startsWith(needle, ignoreCase = true) }.thenBy { it.title.lowercase() })
        binding.searchAppsTitleUi.setText(R.string.launcher_searched_apps)
        adapter.highlight = query
        adapter.submitList(matches.take(SEARCH_MAX_RESULTS).toMutableList())
        binding.searchAppsCardUi.beVisibleIf(matches.isNotEmpty())
        binding.noResultsPlaceholderUi.beVisibleIf(matches.isEmpty())
        renderShortcuts(matches.firstOrNull())
    }

    /** The top match's app shortcuts (dynamic + manifest), as the Finder lists them under the app's name. */
    private fun renderShortcuts(app: AppLauncher?) {
        val card = binding.searchShortcutsCardUi
        val list = binding.searchShortcutsListUi
        list.removeAllViews()
        if (app == null) { card.beGone(); return }
        val launcherApps = context.applicationContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val shortcuts = runCatching {
            if (!launcherApps.hasShortcutHostPermission()) return@runCatching null
            val flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
            launcherApps.getShortcuts(LauncherApps.ShortcutQuery().setQueryFlags(flags).setPackage(app.packageName), Process.myUserHandle())
        }.getOrNull()?.filter { it.isEnabled }?.take(SEARCH_MAX_SHORTCUTS).orEmpty()
        if (shortcuts.isEmpty()) { card.beGone(); return }
        binding.searchShortcutsTitleUi.text = app.title
        val inflater = LayoutInflater.from(context)
        val density = resources.displayMetrics.densityDpi
        shortcuts.forEach { shortcut ->
            val row = LnchItemSearchShortcutBinding.inflate(inflater, list, false)
            row.searchShortcutLabelUi.text = shortcut.shortLabel ?: shortcut.longLabel ?: ""
            row.searchShortcutIconUi.setImageDrawable(runCatching { launcherApps.getShortcutIconDrawable(shortcut, density) }.getOrNull())
            row.root.setOnClickListener {
                runCatching {
                    launcherApps.startShortcut(shortcut.`package`, shortcut.id, null, null, Process.myUserHandle())
                }.onFailure { activity?.showErrorToast(it as? Exception ?: Exception(it)) }
                if (activity?.launcherConfig?.closeAppDrawer == true) activity?.closeAppDrawer(delayed = true)
            }
            list.addView(row.root)
        }
        card.beVisible()
    }

    /**
     * Puts the ad row where `launcher.screens.app_drawer.ad_row_position` asks for it — counted in
     * rows of apps above it, so the same console value reads the same at any column count.
     *
     * Inserted into the list rather than concatenated ahead of it, because the position is not
     * fixed; [LaunchersLineup.AD_SLOT] is the marker the adapter renders as the slot's row.
     *
     * A position past the end lands the row after the last app instead of being dropped: an
     * over-large console value should still show the ad, at the bottom, rather than silently
     * turning the placement off. The list is left alone entirely while it is empty — a lone ad row
     * under "No items found" is not a drawer.
     */
    private fun withAdRow(apps: List<AppLauncher>): MutableList<AppLauncher> {
        if (apps.isEmpty()) {
            return apps.toMutableList()
        }

        val spanCount = (binding.allAppsGridUi.layoutManager as MyGridLayoutManager).spanCount
        val row = LauncherAdsConfig.drawerAdRowPosition()
        val index = (row * spanCount).coerceIn(0, apps.size)
        return apps.toMutableList().apply { add(index, LaunchersLineup.AD_SLOT) }
    }
}
