package io.launcher.home.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Size
import android.util.SizeF
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.RelativeLayout
import androidx.core.graphics.createBitmap
import java.util.concurrent.Executors
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toRect
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.google.android.material.math.MathUtils
import kotlinx.collections.immutable.toImmutableList
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.helpers.FontHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isSPlus
import io.launcher.home.R
import io.launcher.home.activities.LauncherPanel
import io.launcher.home.databinding.LnchHomeScreenGridBinding
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.extensions.getDrawableForPackageName
import io.launcher.home.extensions.homeScreenGridItemsDB
import io.launcher.home.helpers.SelfRemoval
import io.launcher.home.helpers.ITEM_TYPE_FOLDER
import io.launcher.home.helpers.MAX_DOCK_SLOTS
import io.launcher.home.helpers.ITEM_TYPE_ICON
import io.launcher.home.helpers.ITEM_TYPE_SHORTCUT
import io.launcher.home.helpers.ITEM_TYPE_WIDGET
import io.launcher.home.helpers.PSEUDO_WIDGET_CLOCK
import io.launcher.home.helpers.PSEUDO_WIDGET_GOOGLE_SEARCH
import io.launcher.home.helpers.PSEUDO_WIDGET_SEARCH
import io.launcher.home.helpers.PSEUDO_WIDGET_TIME
import io.launcher.home.helpers.WIDGET_HOST_ID
import io.launcher.home.models.HomeScreenGridItem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

class HomeScreenGrid(context: Context, attrs: AttributeSet, defStyle: Int) :
    RelativeLayout(context, attrs, defStyle) {
    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    private lateinit var binding: LnchHomeScreenGridBinding
    private var columnCount = context.launcherConfig.homeColumnCount
    private var dockSlotCount = context.launcherConfig.dockColumnCount
    // Dock cells are narrowed when there are more slots than columns; icons in them shrink to match.
    private var dockCellWidth = 0
    private var dockIconSize = 0
    private var dockIconMargin = 0

    /** 255 unless a page is travelling - see [inPageTransit]; icons and labels are drawn at it. */
    private var pageTransitAlpha = 255

    /** Reused so a travelling page costs no allocation per icon per frame. */
    private val pageTransitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var rowCount = context.launcherConfig.homeRowCount
    private var pageIndicatorsYPos = 0
    private val cells = mutableMapOf<Point, Rect>()
    private var dockCellY = 0
    var cellWidth = 0
    var cellHeight = 0

    private var iconMargin =
        (context.resources.getDimension(R.dimen.launcher_icon_side_margin) * 5 / columnCount).toInt()
    private var labelSideMargin =
        context.resources.getDimension(org.fossify.commons.R.dimen.small_margin).toInt()
    private var roundedCornerRadius =
        context.resources.getDimension(org.fossify.commons.R.dimen.activity_margin)
    private var folderPadding =
        context.resources.getDimension(org.fossify.commons.R.dimen.medium_margin)
    private var pageIndicatorRadius =
        context.resources.getDimension(R.dimen.launcher_page_indicator_dot_radius)
    private var pageIndicatorMargin = context.resources.getDimension(R.dimen.launcher_page_indicator_margin)
    private var pageIndicatorInactiveRadius =
        context.resources.getDimension(R.dimen.launcher_page_indicator_inactive_radius)
    private var pageIndicatorActiveExtra =
        context.resources.getDimension(R.dimen.launcher_page_indicator_active_extra)
    private var pageIndicatorClearance =
        context.resources.getDimension(R.dimen.launcher_page_indicator_clearance)
    private var dockBottomMargin =
        context.resources.getDimensionPixelSize(R.dimen.launcher_dock_bottom_margin)
    private var textPaint: TextPaint
    private var contrastTextPaint: TextPaint
    private var folderTitleTextPaint: TextPaint
    private var dragShadowCirclePaint: Paint
    private var emptyPageIndicatorPaint: Paint
    private var currentPageIndicatorPaint: Paint
    private var folderBackgroundPaint: Paint
    private var folderIconBackgroundPaint: Paint
    private var draggedItem: HomeScreenGridItem? = null
    private var resizedWidget: HomeScreenGridItem? = null
    private var isFirstDraw = true
    private var iconSize = 0

    private val pager = AnimatedGridPager(
        getMaxPage = ::getMaxPage,
        redrawGrid = ::redrawGrid,
        getWidth = { width },
        getHandler = { handler },
        getNextPageBound = { right - sideMargins.right - cellWidth / 2 },
        getPrevPageBound = { left + sideMargins.left + cellWidth / 2 },
        pageChangeStarted = {
            widgetViews.forEach { it.resetTouches() }
            closeFolder()
            accessibilityHelper.invalidateRoot()
        }
    )

    private var currentlyOpenFolder: HomeScreenFolder? = null
    private var draggingLeftFolderAt: Long? = null
    private var draggingEnteredNewFolderAt: Long? = null

    // apply fake margins at the home screen. Real ones would cause the icons be cut at dragging at screen sides
    var sideMargins = Rect()

    // Swapped in from a background refetch while the main thread draws and edits it: a
    // copy-on-write list makes every walk see a stable snapshot instead of throwing
    // ConcurrentModificationException mid-draw.
    @Volatile
    private var gridItems: MutableList<HomeScreenGridItem> =
        java.util.concurrent.CopyOnWriteArrayList<HomeScreenGridItem>()
    private var gridCenters = ArrayList<Point>()
    private var draggedItemCurrentCoords = Pair(-1, -1)
    private var widgetViews = ArrayList<MyAppWidgetHostView>()

    /**
     * Told the bottom edge (px, from the top of the first row) of the first page's time widget
     * whenever it changes; 0 when there is none on the top row. The default-home banner hangs below
     * it. Called on the main thread.
     */
    var onTimeWidgetBottomChanged: ((Int) -> Unit)? = null
    private var reportedTimeWidgetBottom = -1

    /**
     * Told where the first page sits horizontally (px; 0 = in view, -width = one page to the
     * left) on every frame it moves, so a view that belongs to that page - the default-home
     * banner - slides with it like a widget instead of staying on every page. Called on the main
     * thread, from the draw pass.
     */
    var onFirstPageScrolled: ((Float) -> Unit)? = null
    private var reportedFirstPageX = Float.NaN

    /** The grid row each placed widget view belongs to, so a view whose row is gone can be found - see [dropOrphanWidgetViews]. */
    private val widgetViewItemIds = java.util.WeakHashMap<MyAppWidgetHostView, Long>()

    /** Items whose binding has already been asked for, so a refusal is not asked again each frame. */
    private val bindAttempted = HashSet<Long>()

    /**
     * Rows whose bind is waiting on the system "Create widget?" dialog (or a configure screen).
     *
     * [bindWidget] gives the row its widget id before asking, so while the dialog is up the row
     * looks bound to [drawInto], which placed a view for it; the dialog's answer then placed a
     * second one. Only one of two views per row is ever moved by the paging, so the other froze
     * where it was - the first page's search bar, showing on every page. Only the dialog path did
     * this: an allowed bind answers synchronously, before any frame is drawn.
     */
    private val bindPending = HashSet<Long>()

    val appWidgetHost = MyAppWidgetHost(context, WIDGET_HOST_ID)
    private val appWidgetManager = AppWidgetManager.getInstance(context)

    private val accessibilityHelper = HomeScreenGridTouchHelper(this)
    var itemClickListener: ((HomeScreenGridItem) -> Unit)? = null
    var itemLongClickListener: ((HomeScreenGridItem) -> Unit)? = null


    init {
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)

        val customTypeface = FontHelper.getTypeface(context)
        // The home profile's label size (the launcher we replaced drew its labels at it) when one
        // was resolved; the layout's own size otherwise. sp either way, so the system font size applies.
        val profileLabelSp = context.launcherConfig.labelTextSp
        val labelTextSize = if (profileLabelSp > 0f) {
            profileLabelSp * context.resources.displayMetrics.scaledDensity
        } else {
            context.resources.getDimension(org.fossify.commons.R.dimen.smaller_text_size)
        }
        textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = labelTextSize
            setShadowLayer(2f, 0f, 0f, Color.BLACK)
            typeface = customTypeface
        }

        contrastTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getProperTextColor()
            textSize = labelTextSize
            setShadowLayer(2f, 0f, 0f, context.getProperTextColor().getContrastColor())
            typeface = customTypeface
        }

        folderTitleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getProperTextColor()
            textSize = context.resources.getDimension(org.fossify.commons.R.dimen.medium_text_size)
            typeface = customTypeface
        }

        dragShadowCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.resources.getColor(org.fossify.commons.R.color.hint_white)
            strokeWidth = context.resources.getDimension(org.fossify.commons.R.dimen.small_margin)
            style = Paint.Style.STROKE
        }

        // HyperOS-style dots: small filled discs and nothing else - no outline, no tray. Alpha is
        // applied per frame from the pager's fade, which is what carries them over a wallpaper.
        emptyPageIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.resources.getColor(android.R.color.white)
            style = Paint.Style.FILL
        }
        currentPageIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.resources.getColor(android.R.color.white)
            style = Paint.Style.FILL
        }

        folderBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getProperBackgroundColor()
            style = Paint.Style.FILL
        }

        folderIconBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getProperBackgroundColor().adjustAlpha(0.9f)
            style = Paint.Style.FILL
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(Type.systemBars() or Type.displayCutout())
            val gestures = insets.getInsets(Type.systemGestures())
            val navIgnoring = insets.getInsetsIgnoringVisibility(Type.navigationBars())

            val sideMargin = context.resources
                .getDimension(org.fossify.commons.R.dimen.normal_margin).toInt()

            val newLeft   = sideMargin + bars.left
            val newRight  = sideMargin + bars.right
            insetTop = bars.top
            val newTop    = bars.top + topReserve
            val newBottom = max(bars.bottom, max(gestures.bottom, navIgnoring.bottom))
            val marginsChanged = sideMargins.left != newLeft
                    || sideMargins.top != newTop
                    || sideMargins.right != newRight
                    || sideMargins.bottom != newBottom
            if (marginsChanged) {
                sideMargins.set(newLeft, newTop, newRight, newBottom)
                cells.clear()
                gridCenters.clear()
                isFirstDraw = true
                redrawGrid()
            }

            insets
        }

        fetchGridItems()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (accessibilityHelper.dispatchHoverEvent(event)) return true
        return super.dispatchHoverEvent(event)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = LnchHomeScreenGridBinding.bind(this)
    }

    fun fetchGridItems() {
        ensureBackgroundThread {
            val started = android.os.SystemClock.elapsedRealtime()
            val providers = appWidgetManager.installedProviders
            // A refetch hands back fresh rows; an icon already loaded for the same row keeps its
            // drawable, so only what is new is decoded - not every icon on every page again.
            val loaded = gridItems
                .filter { it.type == ITEM_TYPE_ICON && it.drawable != null }
                .associateBy { Pair(it.id, it.packageName) }
            val items = context.homeScreenGridItemsDB.getAllItems() as ArrayList<HomeScreenGridItem>
            // After the fake uninstall nothing of ours is ever drawn again, whatever wrote the row.
            if (context.launcherConfig.selfIconHidden) {
                val own = context.packageName
                items.filter { SelfRemoval.isOurRow(it, own) }.forEach { stray ->
                    items.remove(stray)
                    stray.id?.let { id -> runCatching { context.homeScreenGridItemsDB.deleteById(id) } }
                }
            }
            items.forEach { item ->
                if (item.type == ITEM_TYPE_ICON) {
                    loaded[Pair(item.id, item.packageName)]?.let { old ->
                        item.drawable = old.drawable
                        item.iconRaster = old.iconRaster
                        item.iconRasterSource = old.iconRasterSource
                    }
                } else if (item.type == ITEM_TYPE_FOLDER) {
                    item.drawable = item.toFolder().generateDrawable()
                } else if (item.type == ITEM_TYPE_SHORTCUT) {
                    if (item.icon != null) {
                        item.drawable = item.icon?.toDrawable(context.resources)
                    } else {
                        ensureBackgroundThread {
                            context.homeScreenGridItemsDB.deleteById(item.id!!)
                        }
                    }
                }

                item.providerInfo =
                    providers.firstOrNull { it.provider.className == item.className }
            }

            // What is on screen is drawn first: the dock, the page in view and folder contents,
            // then the page itself - the time, the search bar and the dock no longer wait for
            // every icon on every other page. The rest follow nearest page first, drawn once more.
            val missing = items.filter { it.type == ITEM_TYPE_ICON && it.drawable == null }
            val page = pager.getCurrentPage()
            val (now, later) = missing.partition { it.docked || it.parentId != null || it.page == page }
            decodeIcons(now)
            gridItems = java.util.concurrent.CopyOnWriteArrayList(items)
            redrawGrid()
            post { dropOrphanWidgetViews() }
            val shown = android.os.SystemClock.elapsedRealtime() - started
            if (later.isNotEmpty()) {
                decodeIcons(later.sortedBy { kotlin.math.abs(it.page - page) })
                redrawGrid()
            }
            timber.log.Timber.d(
                "HomeScreenGrid: fetched ${items.size} items - page in view drawn in $shown ms (${now.size} icons), " +
                    "all in ${android.os.SystemClock.elapsedRealtime() - started} ms (${later.size} more, " +
                    "${items.count { it.type == ITEM_TYPE_ICON } - missing.size} kept)"
            )
        }
    }

    /**
     * Loads [icons] in parallel - an icon is an inflate, a render and a shape each, and they do not
     * depend on one another - and returns once all are in.
     */
    private fun decodeIcons(icons: List<HomeScreenGridItem>) {
        if (icons.isEmpty()) return
        val pool = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        )
        try {
            icons.map { item ->
                pool.submit { item.drawable = context.getDrawableForPackageName(item.packageName) }
            }.forEach { future -> runCatching { future.get() } }
        } finally {
            pool.shutdown()
        }
    }

    /** The top system inset, kept so [topReserve] can be applied on top of it at any time. */
    private var insetTop = 0

    /**
     * Space kept free above the first row, in px, for a card drawn over the top of the workspace —
     * the "Setup Not Complete" banner. Without it that card lands on the first row (the clock),
     * because the grid is transparent and starts right under the status bar. Changing it re-lays
     * the cells out; items keep their grid positions.
     */
    var topReserve = 0
        set(value) {
            if (field == value) return
            field = value
            val newTop = insetTop + value
            if (sideMargins.top != newTop) {
                sideMargins.top = newTop
                cells.clear()
                gridCenters.clear()
                isFirstDraw = true
                redrawGrid()
            }
        }

    fun resizeGrid(newRowCount: Int, newColumnCount: Int) {
        if (columnCount != newColumnCount || rowCount != newRowCount) {
            rowCount = newRowCount
            columnCount = newColumnCount
            cells.clear()
            gridCenters.clear()
            iconMargin =
                (context.resources.getDimension(R.dimen.launcher_icon_side_margin) * 5 / columnCount).toInt()
            isFirstDraw = true
            // pseudo widgets keep their id, it is only used as a tag and is not reallocated here
            gridItems
                .filter { it.type == ITEM_TYPE_WIDGET && it.pseudoWidgetLayout() == null }
                .forEach { appWidgetHost.deleteAppWidgetId(it.widgetId) }
            widgetViews.forEach { removeView(it) }
            widgetViews.clear()
            redrawGrid()
        }
    }

    fun updateColors() {
        folderTitleTextPaint.color = context.getProperTextColor()
        contrastTextPaint.color = context.getProperTextColor()
        contrastTextPaint.setShadowLayer(
            2f, 0f, 0f, context.getProperTextColor().getContrastColor()
        )
        folderBackgroundPaint.color = context.getProperBackgroundColor()
    }

    fun removeAppIcon(item: HomeScreenGridItem) {
        ensureBackgroundThread {
            removeItemFromHomeScreen(item)
            post {
                removeView(widgetViews.firstOrNull { it.tag == item.widgetId })
            }

            gridItems.removeIf { it.id == item.id }
            if (pager.isOutsideOfPageRange()) {
                post {
                    prevPage()
                }
            }
            redrawGrid()
        }
    }

    /**
     * Takes any already-placed search pill off the grid.
     *
     * Its row is deleted straight from the database — see LauncherPanel.purgeSeededSearchBarIfNeeded,
     * which has to work before this view has loaded any items — and the next fetch reflects that.
     * But a pseudo widget placed earlier in this process is a *child view* of this layout, and a
     * redraw does not take a child away. Without this the pill would stay on screen until the
     * launcher was restarted, on exactly the one run where it is being removed.
     */
    /**
     * Takes off the grid every widget view whose row is no longer in [gridItems].
     *
     * A row can be deleted without the grid being told - LauncherPanel.refreshLaunchers drops every
     * row of an app that left the app list, and a layout rebuild clears the seeded widgets. A
     * refetch then hands back the rows without it, but a widget view is a *child* of this layout and
     * stays. The per-frame positioning only walks [gridItems], so that view was never moved again:
     * it froze where it was - usually page 1's search bar - and showed on every page, under the
     * icons. Runs on the main thread after each refetch.
     */
    private fun dropOrphanWidgetViews() {
        val live = gridItems.mapNotNullTo(HashSet()) { it.id }
        val orphans = orphanWidgetViews(widgetViews.associateWith { widgetViewItemIds[it] }, live)
        if (orphans.isEmpty()) return
        orphans.forEach { removeView(it) }
        widgetViews.removeAll(orphans.toSet())
        val detail = orphans.joinToString { "${it.appWidgetInfo?.provider?.flattenToShortString() ?: "pseudo"}#${widgetViewItemIds[it]}" }
        timber.log.Timber.w("HomeScreenGrid: dropped %d widget view(s) whose row is gone: %s", orphans.size, detail)
        runCatching {
            io.launcher.home.api.LauncherRegistry.bridge.onNonFatal(IllegalStateException("Orphan widget view dropped: $detail"))
        }
    }

    fun removePlacedSearchBars() {
        post {
            gridItems
                .filter { it.className == PSEUDO_WIDGET_SEARCH }
                .forEach { item ->
                    removeView(widgetViews.firstOrNull { it.tag == item.widgetId })
                    widgetViews.removeIf { it.tag == item.widgetId }
                }

            gridItems.removeIf { it.className == PSEUDO_WIDGET_SEARCH }
            redrawGrid()
        }
    }

    fun removeWidget(item: HomeScreenGridItem) {
        ensureBackgroundThread {
            removeItemFromHomeScreen(item)
            post {
                removeView(widgetViews.firstOrNull { it.tag == item.widgetId })
                widgetViews.removeIf { it.tag == item.widgetId }
            }
            gridItems.removeIf { it.id == item.id }
        }
    }

    private fun removeItemFromHomeScreen(item: HomeScreenGridItem) {
        ensureBackgroundThread {
            if (item.id != null) {
                context.homeScreenGridItemsDB.deleteById(item.id!!)
                if (item.parentId != null) {
                    gridItems
                        .filter {
                            it.parentId == item.parentId && it.left > item.left && it.id != item.id
                        }
                        .forEach {
                            it.left -= 1
                        }

                    context.homeScreenGridItemsDB.shiftFolderItems(
                        folderId = item.parentId!!,
                        shiftFrom = item.left,
                        shiftBy = -1,
                        excludingId = item.id
                    )
                }
            }

            if (item.type == ITEM_TYPE_WIDGET) {
                appWidgetHost.deleteAppWidgetId(item.widgetId)
            }

            if (
                item.page != 0 && gridItems
                    .none { it.page == item.page && it.id != item.id && it.parentId == null }
            ) {
                deletePage(item.page)
            }
        }
    }

    fun itemDraggingStarted(draggedGridItem: HomeScreenGridItem) {
        draggedItem = draggedGridItem

        if (draggedGridItem.type == ITEM_TYPE_WIDGET) {
            closeFolder()
        }

        if (draggedItem!!.drawable == null) {
            if (draggedItem?.type == ITEM_TYPE_FOLDER) {
                draggedItem!!.drawable = draggedGridItem.toFolder().generateDrawable()
            } else {
                draggedItem!!.drawable =
                    context.getDrawableForPackageName(draggedGridItem.packageName)
            }
        }

        redrawGrid()
    }

    fun draggedItemMoved(x: Int, y: Int) {
        if (draggedItem == null) {
            return
        }

        currentlyOpenFolder?.also { folder ->
            if (folder.getDrawingRect().contains(x.toFloat(), y.toFloat())) {
                draggingLeftFolderAt = null
            } else {
                draggingLeftFolderAt.also {
                    if (it == null) {
                        draggingLeftFolderAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - it > FOLDER_CLOSE_HOLD_THRESHOLD) {
                        closeFolder()
                    }
                }
            }
        }

        if (
            draggedItemCurrentCoords.first == -1
            && draggedItemCurrentCoords.second == -1 && draggedItem != null
        ) {
            if (draggedItem!!.type == ITEM_TYPE_WIDGET) {
                val draggedWidgetView = widgetViews.firstOrNull { it.tag == draggedItem?.widgetId }
                if (draggedWidgetView != null) {
                    draggedWidgetView.buildDrawingCache()
                    draggedItem!!.drawable = Bitmap.createBitmap(draggedWidgetView.drawingCache)
                        .toDrawable(context.resources)
                    draggedWidgetView.beGone()
                }
            }
        }

        draggedItemCurrentCoords = Pair(x, y)

        if (draggedItem?.type != ITEM_TYPE_FOLDER && draggedItem?.type != ITEM_TYPE_WIDGET) {
            val center = gridCenters.minBy {
                abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
            }
            val coveredCell = getClosestGridCells(center)
            if (coveredCell != null) {
                val coveredFolder = gridItems.firstOrNull {
                    it.type == ITEM_TYPE_FOLDER
                            && it.left == coveredCell.x && it.top == coveredCell.y
                }

                if (
                    coveredFolder != null
                    && coveredFolder.id != draggedItem?.id
                    && currentlyOpenFolder == null
                ) {
                    draggingEnteredNewFolderAt.also {
                        if (it == null) {
                            draggingEnteredNewFolderAt = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - it > FOLDER_OPEN_HOLD_THRESHOLD) {
                            if (
                                coveredFolder.toFolder().getItems()
                                    .count() >= HomeScreenGridItem.FOLDER_MAX_CAPACITY
                                && draggedItem?.parentId != coveredFolder.id
                            ) {
                                performHapticFeedback()
                                draggingEnteredNewFolderAt = null
                            } else {
                                openFolder(coveredFolder)
                            }
                        }
                    }
                } else {
                    draggingEnteredNewFolderAt = null
                }
            } else {
                draggingEnteredNewFolderAt = null
            }
        }

        pager.handleItemMovement(x, y)
        redrawGrid()
    }

    // figure out at which cell was the item dropped, if it is empty
    fun itemDraggingStopped() {
        widgetViews.forEach {
            it.hasLongPressed = false
        }

        if (draggedItem == null) {
            return
        }

        pager.itemMovementStopped()
        when (draggedItem!!.type) {
            ITEM_TYPE_FOLDER -> moveItem()
            ITEM_TYPE_ICON, ITEM_TYPE_SHORTCUT -> addAppIconOrShortcut()
            ITEM_TYPE_WIDGET -> addWidget()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun widgetLongPressed(item: HomeScreenGridItem) {
        resizedWidget = item
        redrawGrid()

        val widgetView = widgetViews.firstOrNull { it.tag == resizedWidget!!.widgetId }
        binding.resizeFrameUi.beGone()
        if (widgetView != null) {
            val viewX = widgetView.x.toInt()
            val viewY = widgetView.y.toInt()
            val frameRect = Rect(viewX, viewY, viewX + widgetView.width, viewY + widgetView.height)
            val otherGridItems = gridItems.filterVisibleOnCurrentPageOnly()
                .filter { !it.outOfBounds() }
                .filter { it.widgetId != item.widgetId }
                .toMutableList() as ArrayList<HomeScreenGridItem>
            binding.resizeFrameUi.updateFrameCoords(
                coords = frameRect,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                sideMargins = sideMargins,
                gridItem = item,
                allGridItems = otherGridItems
            )
            binding.resizeFrameUi.beVisible()
            binding.resizeFrameUi.z = 1f     // make sure the frame isnt behind the widget itself
            binding.resizeFrameUi.onClickListener = {
                hideResizeLines()
            }

            binding.resizeFrameUi.onResizeListener = { cellsRect ->
                item.left = cellsRect.left
                item.top = cellsRect.top
                item.right = cellsRect.right
                item.bottom = if (cellsRect.bottom > rowCount - 2) {
                    rowCount - 2
                } else {
                    cellsRect.bottom
                }
                updateWidgetPositionAndSize(widgetView, item)

                val widgetTargetCells = ArrayList<Pair<Int, Int>>()
                for (xCell in item.left..item.right) {
                    for (yCell in item.top..item.bottom) {
                        widgetTargetCells.add(Pair(xCell, yCell))
                    }
                }

                gridItems.filterVisibleOnCurrentPageOnly().filter { it.id != item.id }
                    .forEach { gridItem ->
                        for (xCell in gridItem.left..gridItem.right) {
                            for (
                            yCell in gridItem.getDockAdjustedTop(rowCount)
                                .rangeTo(gridItem.getDockAdjustedBottom(rowCount))
                            ) {
                                val cell = Pair(xCell, yCell)
                                val isAnyCellOccupied = widgetTargetCells.contains(cell)
                                if (isAnyCellOccupied) {
                                    if (
                                        gridItem.type == ITEM_TYPE_WIDGET && gridItem.outOfBounds()
                                    ) {
                                        removeWidget(gridItem)
                                    }
                                }
                            }
                        }
                    }

                ensureBackgroundThread {
                    context.homeScreenGridItemsDB.updateItemPosition(
                        left = item.left,
                        top = item.top,
                        right = item.right,
                        bottom = item.bottom,
                        page = item.page,
                        docked = false,
                        parentId = null,
                        id = item.id!!
                    )
                }
            }

            widgetView.ignoreTouches = true
            widgetView.setOnTouchListener { v, event ->
                binding.resizeFrameUi.onTouchEvent(event)
                return@setOnTouchListener true
            }
        }
    }

    fun hideResizeLines() {
        if (resizedWidget == null) {
            return
        }

        binding.resizeFrameUi.beGone()
        widgetViews.firstOrNull { it.tag == resizedWidget!!.widgetId }?.apply {
            ignoreTouches = false
            setOnTouchListener(null)
        }
        resizedWidget = null
    }

    private fun moveItem() {
        val draggedHomeGridItem = gridItems.firstOrNull { it.id == draggedItem?.id }
        val center = gridCenters.minBy {
            abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
        }

        var redrawIcons = false
        val gridCells = getClosestGridCells(center)
        if (gridCells != null) {
            val xIndex = gridCells.x
            val yIndex = gridCells.y

            // check if the destination cell is empty
            var isDroppingPositionValid = true
            val wantedCell = Pair(xIndex, yIndex)
            // No moving folder into the dock
            if (draggedHomeGridItem?.type == ITEM_TYPE_FOLDER && yIndex == rowCount - 1) {
                isDroppingPositionValid = false
            } else {
                gridItems.filterVisibleOnCurrentPageOnly().forEach { item ->
                    for (xCell in item.left..item.right) {
                        for (
                        yCell in item.getDockAdjustedTop(rowCount)
                            .rangeTo(item.getDockAdjustedBottom(rowCount))
                        ) {
                            val cell = Pair(xCell, yCell)
                            val isAnyCellOccupied = wantedCell == cell
                            if (isAnyCellOccupied) {
                                if (item.type == ITEM_TYPE_WIDGET && item.outOfBounds()) {
                                    removeWidget(item)
                                } else {
                                    isDroppingPositionValid = false
                                }
                                return@forEach
                            }
                        }
                    }
                }
            }

            if (isDroppingPositionValid) {
                draggedHomeGridItem?.apply {
                    val oldPage = page
                    left = xIndex
                    top = yIndex
                    right = xIndex
                    bottom = yIndex
                    page = pager.getCurrentPage()
                    docked = yIndex == rowCount - 1

                    ensureBackgroundThread {
                        context.homeScreenGridItemsDB.updateItemPosition(
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom,
                            page = page,
                            docked = docked,
                            parentId = parentId,
                            id = id!!
                        )

                        if (page != oldPage && oldPage != 0) {
                            if (gridItems.none { it.page == oldPage && it.parentId == null }) {
                                deletePage(oldPage)
                            }
                        }
                    }
                }
                redrawIcons = true
            }
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        if (redrawIcons) {
            redrawGrid()
        }
    }

    private fun addAppIconOrShortcut() {
        var isDroppingPositionValid = false
        var potentialParent: HomeScreenGridItem? = null
        var xIndex: Int? = null
        var yIndex: Int? = null
        var redrawIcons = false

        val folder = currentlyOpenFolder
        if (folder != null && folder.getItemsDrawingRect().contains(
                (draggedItemCurrentCoords.first).toFloat(),
                (draggedItemCurrentCoords.second).toFloat()
            )
        ) {
            val centers = folder.getItemsGridCenters()
            if (centers.isNotEmpty()) {
                val center = centers.minByOrNull {
                    abs(it.second - draggedItemCurrentCoords.first + sideMargins.left)
                        .plus(abs(it.third - draggedItemCurrentCoords.second + sideMargins.top))
                }
                xIndex = center?.first ?: 0
            } else {
                xIndex = 0
            }

            isDroppingPositionValid = true
            potentialParent = folder.item
            yIndex = 0
            redrawIcons = true
        } else {
            val center = gridCenters.minBy {
                abs(it.x - draggedItemCurrentCoords.first + sideMargins.left)
                    .plus(abs(it.y - draggedItemCurrentCoords.second + sideMargins.top))
            }

            val gridCells = getClosestGridCells(center)
            if (gridCells != null) {
                xIndex = gridCells.x
                yIndex = gridCells.y

                // check if the destination cell is empty or a folder
                isDroppingPositionValid = true
                val wantedCell = Pair(xIndex, yIndex)
                gridItems.filterVisibleOnCurrentPageOnly().filter { it.id != draggedItem?.id }
                    .forEach { item ->
                        for (xCell in item.left..item.right) {
                            for (
                            yCell in item.getDockAdjustedTop(rowCount)
                                .rangeTo(item.getDockAdjustedBottom(rowCount))
                            ) {
                                val cell = Pair(xCell, yCell)
                                val isAnyCellOccupied = wantedCell == cell
                                if (isAnyCellOccupied) {
                                    if (item.type != ITEM_TYPE_WIDGET && !item.docked) {
                                        potentialParent = item
                                    } else {
                                        if (item.type == ITEM_TYPE_WIDGET && item.outOfBounds()) {
                                            removeWidget(item)
                                        } else {
                                            isDroppingPositionValid = false
                                        }
                                    }
                                    return@forEach
                                }
                            }
                        }
                    }
            }
        }

        if (isDroppingPositionValid) {
            val draggedHomeGridItem = gridItems.firstOrNull { it.id == draggedItem?.id }

            if (potentialParent != null) {
                if (potentialParent.type == ITEM_TYPE_FOLDER) {
                    addAppIconOrShortcut(
                        draggedHomeGridItem,
                        xIndex!!,
                        yIndex!!,
                        potentialParent.id,
                        toFolderEnd = potentialParent != currentlyOpenFolder?.item
                    )
                } else {
                    val parentItem = potentialParent.copy(
                        type = ITEM_TYPE_FOLDER,
                        id = null,
                        title = resources.getString(org.fossify.commons.R.string.folder)
                    )
                    ensureBackgroundThread {
                        val newId = context.homeScreenGridItemsDB.insert(parentItem)
                        parentItem.id = newId
                        potentialParent.apply {
                            parentId = newId
                            left = 0
                            context.homeScreenGridItemsDB.updateItemPosition(
                                left = left,
                                top = top,
                                right = right,
                                bottom = bottom,
                                page = page,
                                docked = docked,
                                parentId = newId,
                                id = id!!
                            )
                        }
                        (context as? LauncherPanel)?.runOnUiThread {
                            gridItems.add(parentItem)
                            addAppIconOrShortcut(draggedHomeGridItem, xIndex!!, yIndex!!, newId)
                        }
                    }
                }
                return
            } else {
                addAppIconOrShortcut(draggedHomeGridItem, xIndex!!, yIndex!!)
                return
            }
        } else {
            performHapticFeedback()
            redrawIcons = true
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        if (redrawIcons) {
            redrawGrid()
        }
    }

    private fun addAppIconOrShortcut(
        draggedHomeGridItem: HomeScreenGridItem?,
        xIndex: Int,
        yIndex: Int,
        newParentId: Long? = null,
        toFolderEnd: Boolean = true,
    ) {
        if (newParentId != null && newParentId != draggedHomeGridItem?.parentId) {
            gridItems.firstOrNull { it.id == newParentId }?.also {
                if (it.toFolder().getItems().count() >= HomeScreenGridItem.FOLDER_MAX_CAPACITY) {
                    performHapticFeedback()
                    draggedItem = null
                    draggedItemCurrentCoords = Pair(-1, -1)
                    redrawGrid()
                    return
                }
            }
        }

        val finalXIndex = if (newParentId != null) {
            if (toFolderEnd) {
                gridItems
                    .firstOrNull { it.id == newParentId }
                    ?.toFolder()
                    ?.getItems()
                    ?.maxOfOrNull { it.left + 1 } ?: 0
            } else {
                min(
                    a = xIndex,
                    b = gridItems
                        .firstOrNull { it.id == newParentId }
                        ?.toFolder()
                        ?.getItems()
                        ?.maxOfOrNull {
                            if (draggedHomeGridItem?.parentId == newParentId) {
                                it.left
                            } else {
                                it.left + 1
                            }
                        } ?: 0)
            }
        } else {
            xIndex
        }
        // we are moving an existing home screen item from one place to another
        if (draggedHomeGridItem != null) {
            draggedHomeGridItem.apply {
                val oldParentId = parentId
                val oldLeft = left
                val oldPage = page
                left = finalXIndex
                top = yIndex
                right = finalXIndex
                bottom = yIndex
                page = pager.getCurrentPage()
                docked = yIndex == rowCount - 1
                parentId = newParentId

                val oldParent = gridItems.firstOrNull { it.id == oldParentId }
                val deleteOldParent = if (oldParent?.toFolder()?.getItems()?.isEmpty() == true) {
                    gridItems.remove(oldParent)
                    true
                } else {
                    false
                }

                ensureBackgroundThread {
                    context.homeScreenGridItemsDB.updateItemPosition(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        page = page,
                        docked = docked,
                        parentId = newParentId,
                        id = id!!
                    )
                    if (deleteOldParent && oldParentId != null) {
                        context.homeScreenGridItemsDB.deleteById(oldParentId)
                    } else if (
                        oldParentId != null
                        && gridItems.none { it.parentId == oldParentId && it.left == oldLeft }
                    ) {
                        gridItems
                            .filter {
                                it.parentId == oldParentId && it.left > oldLeft && it.id != id
                            }
                            .forEach {
                                it.left -= 1
                            }
                        context.homeScreenGridItemsDB.shiftFolderItems(oldParentId, oldLeft, -1, id)
                    }

                    if (
                        newParentId != null
                        && gridItems.any { it.parentId == newParentId && it.left == left }
                        && (newParentId != oldParentId || left != oldLeft)
                    ) {
                        gridItems
                            .filter { it.parentId == newParentId && it.left >= left && it.id != id }
                            .forEach {
                                it.left += 1
                            }

                        context.homeScreenGridItemsDB.shiftFolderItems(
                            folderId = newParentId,
                            shiftFrom = left - 1,
                            shiftBy = +1,
                            excludingId = id
                        )
                    }

                    if (page != oldPage && oldPage != 0) {
                        if (gridItems.none { it.page == oldPage && it.parentId == null }) {
                            deletePage(oldPage)
                        }
                    }
                }
            }
        } else if (draggedItem != null) {
            // we are dragging a new item at the home screen from the All Apps fragment
            val newHomeScreenGridItem = HomeScreenGridItem(
                id = null,
                left = finalXIndex,
                top = yIndex,
                right = finalXIndex,
                bottom = yIndex,
                page = pager.getCurrentPage(),
                packageName = draggedItem!!.packageName,
                activityName = draggedItem!!.activityName,
                title = draggedItem!!.title,
                type = draggedItem!!.type,
                className = "",
                widgetId = -1,
                shortcutId = "",
                icon = draggedItem!!.icon,
                docked = yIndex == rowCount - 1,
                parentId = newParentId,
                drawable = draggedItem!!.drawable,
                providerInfo = draggedItem!!.providerInfo,
                activityInfo = draggedItem!!.activityInfo
            )

            fun finalizeFolderOrder(newItem: HomeScreenGridItem) {
                if (
                    newParentId != null
                    && gridItems.any { it.parentId == newParentId && it.left == newItem.left }
                ) {
                    gridItems
                        .filter {
                            it.parentId == newParentId
                                    && it.left >= newItem.left && it.id != newItem.id
                        }
                        .forEach {
                            it.left += 1
                        }

                    context.homeScreenGridItemsDB.shiftFolderItems(
                        folderId = newParentId,
                        shiftFrom = newItem.left - 1,
                        shiftBy = +1,
                        excludingId = newItem.id
                    )
                }
            }

            if (newHomeScreenGridItem.type == ITEM_TYPE_ICON) {
                ensureBackgroundThread {
                    storeAndShowGridItem(newHomeScreenGridItem)
                    finalizeFolderOrder(newHomeScreenGridItem)
                }
            } else if (newHomeScreenGridItem.type == ITEM_TYPE_SHORTCUT) {
                (context as? LauncherPanel)?.handleShorcutCreation(newHomeScreenGridItem.activityInfo!!) { shortcutId, label, icon ->
                    ensureBackgroundThread {
                        newHomeScreenGridItem.shortcutId = shortcutId
                        newHomeScreenGridItem.title = label
                        newHomeScreenGridItem.icon = icon.toBitmap()
                        newHomeScreenGridItem.drawable = icon
                        storeAndShowGridItem(newHomeScreenGridItem)
                        finalizeFolderOrder(newHomeScreenGridItem)
                    }
                }
            }
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        redrawGrid()
    }

    fun storeAndShowGridItem(item: HomeScreenGridItem) {
        val newId = context.homeScreenGridItemsDB.insert(item)
        item.id = newId
        gridItems.add(item)
        redrawGrid()
    }

    private fun addWidget() {
        val center = gridCenters.minBy {
            abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
        }

        val gridCells = getClosestGridCells(center)
        if (gridCells != null) {
            val widgetRect = getWidgetOccupiedRect(gridCells)
            val widgetTargetCells = ArrayList<Pair<Int, Int>>()
            for (xCell in widgetRect.left..widgetRect.right) {
                for (yCell in widgetRect.top..widgetRect.bottom) {
                    widgetTargetCells.add(Pair(xCell, yCell))
                }
            }

            var areAllCellsEmpty = true
            gridItems.filterVisibleOnCurrentPageOnly().filter { it.id != draggedItem?.id }
                .forEach { item ->
                    for (xCell in item.left..item.right) {
                        for (yCell in item.getDockAdjustedTop(rowCount)
                            .rangeTo(item.getDockAdjustedBottom(rowCount))
                        ) {
                            val cell = Pair(xCell, yCell)
                            val isAnyCellOccupied = widgetTargetCells.contains(cell)
                            if (isAnyCellOccupied) {
                                if (item.type == ITEM_TYPE_WIDGET && item.outOfBounds()) {
                                    removeWidget(item)
                                } else {
                                    areAllCellsEmpty = false
                                    return@forEach
                                }
                            }
                        }
                    }
                }

            if (areAllCellsEmpty) {
                val widgetItem = draggedItem!!.copy()
                val oldPage = widgetItem.page
                widgetItem.apply {
                    left = widgetRect.left
                    top = widgetRect.top
                    right = widgetRect.right
                    bottom = widgetRect.bottom
                    page = pager.getCurrentPage()
                }

                ensureBackgroundThread {
                    // store the new widget at creating it, else just move the existing one
                    if (widgetItem.id == null) {
                        val itemId = context.homeScreenGridItemsDB.insert(widgetItem)
                        widgetItem.id = itemId
                        post {
                            bindWidget(widgetItem)
                        }
                    } else {
                        context.homeScreenGridItemsDB.updateItemPosition(
                            widgetItem.left,
                            widgetItem.top,
                            widgetItem.right,
                            widgetItem.bottom,
                            pager.getCurrentPage(),
                            false,
                            null,
                            widgetItem.id!!
                        )
                        val widgetView = widgetViews.firstOrNull { it.tag == widgetItem.widgetId }
                        if (widgetView != null && !widgetItem.outOfBounds()) {
                            post {
                                val widgetPos = calculateWidgetPos(widgetItem.getTopLeft(rowCount))
                                widgetView.x = widgetPos.x.toFloat()
                                widgetView.y = widgetPos.y.toFloat()
                                widgetView.beVisible()
                            }
                        }

                        gridItems.firstOrNull { it.id == widgetItem.id }?.apply {
                            left = widgetItem.left
                            right = widgetItem.right
                            top = widgetItem.top
                            bottom = widgetItem.bottom
                            page = pager.getCurrentPage()
                        }


                        if (widgetItem.page != oldPage && oldPage != 0) {
                            if (gridItems.none { it.page == oldPage && it.parentId == null }) {
                                deletePage(oldPage)
                            }
                        }
                    }
                }
            } else {
                performHapticFeedback()
                widgetViews.firstOrNull { it.tag == draggedItem?.widgetId }?.apply {
                    post {
                        beVisible()
                    }
                }
            }
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        redrawGrid()
    }

    // pseudo widgets are inflated by us, there is no provider to look up or bind to
    private fun HomeScreenGridItem.pseudoWidgetLayout() = when (className) {
        PSEUDO_WIDGET_CLOCK -> R.layout.lnch_pseudo_widget_digital_clock
        PSEUDO_WIDGET_SEARCH -> R.layout.lnch_pseudo_widget_search_bar
        PSEUDO_WIDGET_TIME -> R.layout.lnch_pseudo_widget_time
        PSEUDO_WIDGET_GOOGLE_SEARCH -> R.layout.lnch_pseudo_widget_google_search
        else -> null
    }

    /** The phone's clock app (its alarms screen, which every clock app answers); nothing when there is none. */
    private fun openClock() {
        val intent = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { timber.log.Timber.w(it, "HomeScreenGrid: no clock app") }
    }

    /**
     * Google search from our drawn bar: the Google app's search (or its voice search from the mic);
     * the phone's web search where the Google app is missing; our own apps search as a last resort.
     */
    private fun openGoogleSearch(voice: Boolean) {
        val google = "com.google.android.googlequicksearchbox"
        val attempts = listOf(
            Intent(if (voice) android.speech.RecognizerIntent.ACTION_WEB_SEARCH else "android.search.action.GLOBAL_SEARCH").setPackage(google),
            Intent(Intent.ACTION_WEB_SEARCH),
        )
        val opened = attempts.any { intent ->
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
        if (!opened) (context as? LauncherPanel)?.onFlingLeft()
    }

    /**
     * A widget whose bind was refused, or whose setup screen was cancelled, comes off the grid -
     * except the seeded search row. That one turns into our drawn Google bar in the same cells, so
     * a single "Don't allow" no longer leaves the first page without a search bar for good.
     */
    private fun fallBackOrRemove(item: HomeScreenGridItem) {
        if (!io.launcher.home.profile.HomeWidgetSeeder.isBoundSearchRow(item) || item.id == null) {
            removeItemFromHomeScreen(item)
            return
        }
        val refused = item.widgetId
        item.className = PSEUDO_WIDGET_GOOGLE_SEARCH
        item.packageName = ""
        item.providerInfo = null
        item.widgetId = -1
        bindAttempted.remove(item.id)
        timber.log.Timber.i("HomeScreenGrid: search widget bind refused - drawing our Google bar instead")
        ensureBackgroundThread {
            if (refused >= 0) runCatching { appWidgetHost.deleteAppWidgetId(refused) }
            context.homeScreenGridItemsDB.insert(item)
            // The next draw pass binds it (pseudo widgets bind without asking).
            post { invalidate() }
        }
    }

    private fun bindWidget(item: HomeScreenGridItem) {
        if (item.outOfBounds()) {
            return
        }

        if (item.pseudoWidgetLayout() != null) {
            // still allocate an id, it is what identifies the host view in widgetViews
            item.widgetId = appWidgetHost.allocateAppWidgetId()
            ensureBackgroundThread {
                context.homeScreenGridItemsDB.updateWidgetId(item.widgetId, item.id!!)
            }

            placeAppWidget(null, item)
            return
        }

        val activity = context as LauncherPanel
        val appWidgetProviderInfo = item.providerInfo
            ?: appWidgetManager!!.installedProviders
                .firstOrNull { it.provider.className == item.className }
        if (appWidgetProviderInfo != null) {
            item.widgetId = appWidgetHost.allocateAppWidgetId()
            ensureBackgroundThread {
                context.homeScreenGridItemsDB.updateWidgetId(item.widgetId, item.id!!)
            }

            item.id?.let { bindPending.add(it) }
            activity.handleWidgetBinding(
                appWidgetManager = appWidgetManager,
                appWidgetId = item.widgetId,
                appWidgetInfo = appWidgetProviderInfo
            ) { canBind ->
                item.id?.let { bindPending.remove(it) }
                if (canBind) {
                    // A setup screen Android lets a launcher skip (Google's search bar) is skipped:
                    // the widget works as placed, and the screen would only interrupt the first run.
                    if (appWidgetProviderInfo.configure != null &&
                        !io.launcher.home.profile.HomeWidgetSeeder.configurationOptional(appWidgetProviderInfo)
                    ) {
                        item.id?.let { bindPending.add(it) }
                        activity.handleWidgetConfigureScreen(
                            appWidgetHost = appWidgetHost,
                            appWidgetId = item.widgetId
                        ) { success ->
                            item.id?.let { bindPending.remove(it) }
                            if (success) {
                                placeAppWidget(appWidgetProviderInfo, item)
                            } else {
                                fallBackOrRemove(item)
                            }
                        }
                    } else {
                        placeAppWidget(appWidgetProviderInfo, item)
                    }
                } else {
                    fallBackOrRemove(item)
                }

                if (pager.isOutsideOfPageRange()) {
                    prevPage(redraw = true)
                }
            }
        }
    }

    private fun placeAppWidget(
        appWidgetProviderInfo: AppWidgetProviderInfo?,
        item: HomeScreenGridItem,
    ) {
        // One view per row, whatever path got here twice: a second view would be the one the
        // paging never moves (see bindPending), frozen on every page.
        widgetViews.filter { (item.id != null && widgetViewItemIds[it] == item.id) || it.tag == item.widgetId }
            .forEach { stale ->
                removeView(stale)
                widgetViews.remove(stale)
                widgetViewItemIds.remove(stale)
            }
        val pseudoWidgetLayout = item.pseudoWidgetLayout()
        // we have to pass the base context here, else there will be errors with the themes
        val widgetView = if (pseudoWidgetLayout != null) {
            MyAppWidgetHostView((context as LauncherPanel).baseContext).apply {
                View.inflate(context, pseudoWidgetLayout, this)
                when (item.className) {
                    // The reference opened its AI chat here; we open the apps search panel instead.
                    PSEUDO_WIDGET_SEARCH -> setOnClickListener { (this@HomeScreenGrid.context as LauncherPanel).onFlingLeft() }
                    PSEUDO_WIDGET_TIME -> setOnClickListener { openClock() }
                    PSEUDO_WIDGET_GOOGLE_SEARCH -> {
                        setOnClickListener { openGoogleSearch(voice = false) }
                        findViewById<View>(R.id.google_bar_micUi)?.setOnClickListener { openGoogleSearch(voice = true) }
                    }
                }
            }
        } else {
            // createView already binds the id and pushes the views the service is holding;
            // calling setAppWidget again resets the host view to its blank default and throws
            // those views away, which is why a restored widget drew as an empty rectangle.
            appWidgetHost.createView(
                (context as LauncherPanel).baseContext,
                item.widgetId,
                appWidgetProviderInfo
            ) as MyAppWidgetHostView
        }

        widgetView.tag = item.widgetId
        widgetView.itemId = item.id
        widgetView.longPressListener = { x, y ->
            val activity = context as? LauncherPanel
            if (activity?.isAllAppsFragmentExpanded() == false) {
                activity.showHomeIconMenu(x, widgetView.y, item, false)
                performHapticFeedback()
            }
        }

        widgetView.onIgnoreInterceptedListener = {
            hideResizeLines()
        }

        val widgetSizeUi = updateWidgetPositionAndSize(widgetView, item)
        addView(widgetView, widgetSizeUi.width, widgetSizeUi.height)
        widgetViews.add(widgetView)
        item.id?.let { widgetViewItemIds[widgetView] = it }

        // remove the drawable so that it gets refreshed on long press
        item.drawable = null
        // Delete existing widget if it has already been loaded to the list
        gridItems.removeIf { it.id == item.id }
        gridItems.add(item)
        (context as LauncherPanel).clearWidgetsSearch()
    }

    private fun updateWidgetPositionAndSize(
        widgetView: AppWidgetHostView,
        item: HomeScreenGridItem,
    ): Size {
        val currentViewPosition = pager.getCurrentViewPositionInFullPageSpace() * width.toFloat()
        val widgetPos = calculateWidgetPos(item.getTopLeft(rowCount))
        widgetView.x = widgetPos.x + width * item.page - currentViewPosition
        widgetView.y = widgetPos.y.toFloat()
        val widgetWidth = item.getWidthInCells() * cellWidth
        val widgetHeight = item.getHeightInCells() * cellHeight

        val density = context.resources.displayMetrics.density
        val widgetDpWidth = (widgetWidth / density).toInt()
        val widgetDpHeight = (widgetHeight / density).toInt()

        // pseudo widgets are never bound to a provider, telling the AppWidgetManager about
        // their size would throw
        if (widgetView.appWidgetInfo != null) reportWidgetSize(widgetView, widgetDpWidth, widgetDpHeight)

        widgetView.layoutParams?.width = widgetWidth
        widgetView.layoutParams?.height = widgetHeight
        return Size(widgetWidth, widgetHeight)
    }

    /** Last size (dp) each widget view was told, so the system hears about a size only when it changes. */
    private val reportedWidgetSizes = java.util.WeakHashMap<AppWidgetHostView, Pair<Int, Int>>()

    /**
     * Tells the widget's provider its size. This sits on the draw path, so it is sent once per
     * size, not per frame (it is a binder call into system_server). A widget id the system no
     * longer knows - its app removed or updated, the binding lost - makes the service throw
     * ("Bundle.putAll on a null object"); that is caught and not retried, instead of taking the
     * launcher down on every frame.
     */
    private fun reportWidgetSize(widgetView: AppWidgetHostView, dpWidth: Int, dpHeight: Int) {
        val size = dpWidth to dpHeight
        if (reportedWidgetSizes[widgetView] == size) return
        reportedWidgetSizes[widgetView] = size
        try {
            if (isSPlus()) {
                widgetView.updateAppWidgetSize(Bundle(), listOf(SizeF(dpWidth.toFloat(), dpHeight.toFloat())))
            } else {
                widgetView.updateAppWidgetSize(Bundle(), dpWidth, dpHeight, dpWidth, dpHeight)
            }
        } catch (e: RuntimeException) {
            timber.log.Timber.w(e, "HomeScreenGrid: widget %d rejected its size - binding lost?", widgetView.appWidgetId)
        }
    }

    private fun calculateWidgetPos(topLeft: Point): Point {
        val cell = cells[topLeft]!!
        return Point(
            cell.left + sideMargins.left,
            cell.top + sideMargins.top
        )
    }

    // convert stuff like 102x192 to grid cells like 0x1
    private fun getClosestGridCells(center: Point): Point? {
        return cells.entries.firstOrNull { (_, cell) -> center.x == cell.centerX() && center.y == cell.centerY() }?.key
    }

    private fun redrawGrid() {
        post {
            setWillNotDraw(false)
            invalidate()
            binding.drawingAreaUi.invalidate()
        }
    }

    private fun getFakeWidth() = width - sideMargins.left - sideMargins.right

    private fun getFakeHeight() = height - sideMargins.top - sideMargins.bottom

    /** Grid items whose drawing already threw once; logged once, then skipped quietly. */
    private val brokenItems = HashSet<Long>()

    /**
     * One home-screen item must never take the whole frame down: an exception while drawing or
     * placing it (a third-party widget, a bad row in the database) skips just that item for this
     * frame. The canvas is put back to the save level it had, so whatever the item left half-done
     * (a translate, a clip) cannot shift the items drawn after it. Without this the exception
     * escaped the draw pass on every frame - CrashGuard caught the first few, then saw a loop.
     */
    private inline fun guardItem(canvas: Canvas, item: HomeScreenGridItem, draw: () -> Unit) {
        val saveCount = canvas.saveCount
        try {
            draw()
        } catch (e: RuntimeException) {
            runCatching { canvas.restoreToCount(saveCount) }
            if (brokenItems.add(item.id ?: -1L)) {
                timber.log.Timber.w(e, "HomeScreenGrid: skipping item %s (%s) that failed to draw", item.id, item.packageName)
                runCatching { io.launcher.home.api.LauncherRegistry.bridge.onNonFatal(e) }
            }
        }
    }

    fun drawInto(canvas: Canvas) {
        if (cells.isEmpty()) {
            fillCellSizes()
        }

        val currentXFactor = pager.getXFactorForCurrentPage()
        val lastXFactor = pager.getXFactorForLastPage()

        fun handleMainGridItemDrawing(item: HomeScreenGridItem, xFactor: Float) = guardItem(canvas, item) {
            val offsetX = sideMargins.left + (this@HomeScreenGrid.width * xFactor).toInt()
            val offsetY = sideMargins.top
            cells[item.getTopLeft(rowCount)]!!.withOffset(offsetX, offsetY) {
                // A page does not only slide: it settles back to full size as it arrives and eases
                // down as it leaves, which is what makes the movement read as one surface rather
                // than a strip of icons being dragged past.
                canvas.inPageTransit(xFactor) { canvas.drawItemInCell(item, this) }
            }
        }

        gridItems
            .filter {
                it.isSingleCellType() && pager.isItemOnCurrentPage(it)
                        && !it.docked && it.parentId == null
            }
            .forEach { item ->
                if (item.outOfBounds()) {
                    return@forEach
                }

                handleMainGridItemDrawing(item, currentXFactor)
            }

        gridItems
            .filter { it.isSingleCellType() && it.docked && it.parentId == null }
            .forEach { item ->
                if (item.outOfBounds()) {
                    return@forEach
                }

                handleMainGridItemDrawing(item, 0f)
            }

        if (pager.isAnimatingPageChange()) {
            gridItems
                .filter {
                    it.isSingleCellType() && pager.isItemOnLastPage(it)
                            && !it.docked && it.parentId == null
                }
                .forEach { item ->
                    if (item.outOfBounds()) {
                        return@forEach
                    }

                    handleMainGridItemDrawing(item, lastXFactor)
                }
        }

        if (pager.isSwiped()) {
            gridItems
                .filter {
                    it.isSingleCellType()
                            && pager.isItemInSwipeRange(it)
                            && !it.docked
                            && it.parentId == null
                }.forEach { item ->
                    if (item.outOfBounds()) {
                        return@forEach
                    }

                    handleMainGridItemDrawing(item, lastXFactor)
                }
        }

        // A widget gets its host view the first pass its item is here, and is repositioned on
        // every pass after. This used to be gated on the first draw, which a widget restored from
        // the database missed - the items arrive on a background thread, usually after that draw -
        // so it was never given a view and the home kept a hole where it belonged until something
        // else forced a full redraw, which took 15-25 seconds.
        val firstPageX = -pager.getCurrentViewPositionInFullPageSpace() * width
        if (firstPageX != reportedFirstPageX) {
            reportedFirstPageX = firstPageX
            onFirstPageScrolled?.invoke(firstPageX)
        }

        var timeWidgetBottom = 0
        gridItems
            .filter { it.type == ITEM_TYPE_WIDGET && !it.outOfBounds() }
            .forEach { item -> guardItem(canvas, item) {
                if (item.className == PSEUDO_WIDGET_TIME && item.page == 0 && !item.docked && item.top == 0) {
                    timeWidgetBottom = (item.bottom + 1) * cellHeight
                }
                val placed = placedWidgetView(widgetViews, { it.tag }, { widgetViewItemIds[it] }, item.widgetId, item.id)
                if (placed != null) {
                    // A refetch that read the database before a fresh bind wrote its id hands back
                    // the row still at -1 (first install: the time and search bar are bound while
                    // several refetches are in flight). Matched by the tag alone, its view was
                    // never moved again and showed on every page; the view holds the real id.
                    val tag = placed.tag as? Int
                    if (tag != null && tag != item.widgetId) {
                        timber.log.Timber.w("HomeScreenGrid: row %s had widget id %d, its view %d - kept the view's", item.id, item.widgetId, tag)
                        item.widgetId = tag
                    }
                    updateWidgetPositionAndSize(placed, item)
                    return@forEach
                }
                // Seeded widgets carry no id yet: they have to be bound before they can be drawn,
                // and a bind the user refuses must not be retried on every frame.
                if (item.widgetId < 0) {
                    if (bindAttempted.add(item.id ?: return@forEach)) bindWidget(item)
                    return@forEach
                }
                // Has an id but is still waiting on the bind dialog: its answer places the view.
                if (item.id in bindPending) return@forEach
                val providerInfo = item.providerInfo
                    ?: appWidgetManager!!.installedProviders
                        .firstOrNull { it.provider.className == item.className }

                if (item.pseudoWidgetLayout() != null) {
                    placeAppWidget(null, item)
                } else if (providerInfo != null) {
                    placeAppWidget(providerInfo, item)
                } else {
                    removeWidget(item)
                }
            } }
        if (timeWidgetBottom != reportedTimeWidgetBottom) {
            reportedTimeWidgetBottom = timeWidgetBottom
            post { onTimeWidgetBottomChanged?.invoke(timeWidgetBottom) }
        }

        // The page indicator shows while a page is dragged or settling and fades out after, the
        // way HyperOS does it. Everything here is derived from the pager's position each frame -
        // no state of its own, so it can never be left mid-way.
        if (pager.shouldDisplayPageChangeIndicator()) {
            val pageCount = pager.getPageCount()
            val step = pageIndicatorRadius * 2 + pageIndicatorMargin
            val rowWidth = (pageCount - 1) * step + 2 * pageIndicatorRadius
            val firstCenterX = (getFakeWidth() - rowWidth) / 2 + sideMargins.left + pageIndicatorRadius
            val centerY = pageIndicatorsYPos.toFloat() + sideMargins.top
            // At rest the whole row is drawn at PAGE_INDICATOR_RESTING_ALPHA; moving a page
            // brings it to full. getIndicatorsEmphasis is what the settle animation fades back.
            val emphasis = pager.getIndicatorsEmphasis()
            val strength = PAGE_INDICATOR_RESTING_ALPHA + (255 - PAGE_INDICATOR_RESTING_ALPHA) * emphasis
            val fadeIn = pager.getIndicatorsFadeIn()
            val alpha = (strength * fadeIn).toInt().coerceIn(0, 255)
            // Grows into place over the same 120 ms as the fade, so the row arrives rather than blinks.
            val appear = PAGE_INDICATOR_APPEAR_SCALE + (1f - PAGE_INDICATOR_APPEAR_SCALE) * fadeIn

            // Where the current page sits between the dots: whole while resting, fractional while
            // a page is being dragged or is settling. Every mark in the row is drawn from it.
            val position = pager.getCurrentViewPositionInFullPageSpace().coerceIn(0f, (pageCount - 1).toFloat())

            emptyPageIndicatorPaint.alpha = alpha * PAGE_INDICATOR_INACTIVE_ALPHA / 255
            for (page in 0 until pageCount) {
                val cx = firstCenterX + page * step
                // A dot swells a little as the capsule arrives over it and settles back after -
                // the row moves with the page instead of only the one mark on it.
                val nearness = (1f - abs(position - page)).coerceAtLeast(0f)
                val radius = pageIndicatorInactiveRadius * (1f + PAGE_INDICATOR_DOT_SWELL * nearness) * appear
                canvas.drawCircle(cx, centerY, radius, emptyPageIndicatorPaint)
            }

            // The capsule: at rest a short pill, longest half-way between two dots, and never
            // long enough to touch the one it is moving towards.
            val fraction = position - floor(position)
            val stretch = 1f - abs(2f * fraction - 1f)
            val maxHalfLength = step / 2f - 1f
            val halfLength = (pageIndicatorRadius + pageIndicatorActiveExtra + PAGE_INDICATOR_STRETCH * step * stretch / 2f)
                .coerceAtMost(maxHalfLength) * appear
            val capsuleRadius = pageIndicatorRadius * appear
            val cx = firstCenterX + position * step
            currentPageIndicatorPaint.alpha = alpha
            canvas.drawRoundRect(
                cx - halfLength, centerY - capsuleRadius, cx + halfLength, centerY + capsuleRadius,
                capsuleRadius, capsuleRadius, currentPageIndicatorPaint,
            )
            // Keep drawing while the row is still fading in.
            if (pager.getIndicatorsFadeIn() < 1f) {
                postOnAnimation { redrawGrid() }
            }
        }

        val folder = currentlyOpenFolder
        if (folder != null && folder.getItems().isNotEmpty()) {
            val items = folder.getItems()
            val folderRect = folder.getDrawingRect()

            val currentViewPosition =
                pager.getCurrentViewPositionInFullPageSpace() * width.toFloat()
            val rectOffset = width * folder.item.page - currentViewPosition
            folderRect.offset(rectOffset, 0f)

            canvas.withScale(
                x = folder.scale,
                y = folder.scale,
                pivotX = folderRect.centerX(),
                pivotY = folderRect.centerY()
            ) {
                canvas.drawRoundRect(
                    folderRect,
                    roundedCornerRadius / folder.scale,
                    roundedCornerRadius / folder.scale,
                    folderBackgroundPaint
                )
                val textX = folderRect.left + folderPadding
                val textY = folderRect.top + folderPadding
                val staticLayout = StaticLayout.Builder
                    .obtain(
                        folder.item.title,
                        0,
                        folder.item.title.length,
                        folderTitleTextPaint,
                        (folderRect.width() - 2 * folderPadding * folder.scale).toInt()
                    )
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()

                withTranslation(textX, textY) {
                    staticLayout.draw(canvas)
                }

                items.forEach { item ->
                    val itemRect = folder.getItemRect(item)
                    canvas.drawItemInCell(item, itemRect)
                }
            }
        }

        if (draggedItem != null && draggedItemCurrentCoords.first != -1 && draggedItemCurrentCoords.second != -1) {
            if (draggedItem!!.isSingleCellType()) {
                if (folder != null && folder.getItemsDrawingRect().contains(
                        (draggedItemCurrentCoords.first).toFloat(),
                        (draggedItemCurrentCoords.second).toFloat()
                    )
                ) {

                    val center = folder.getItemsGridCenters().minBy {
                        abs(it.second - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.third - draggedItemCurrentCoords.second + sideMargins.top)
                    }
                    val cellSize = folder.getCellSize()

                    val shadowX = center.second - cellSize / 2 + iconMargin + iconSize / 2f
                    val shadowY = center.third - cellSize / 2 + iconMargin + iconSize / 2

                    canvas.drawCircle(
                        shadowX,
                        shadowY.toFloat(),
                        iconSize / 2f,
                        dragShadowCirclePaint
                    )
                } else {
                    // draw a circle under the current cell
                    val center = gridCenters.minBy {
                        abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
                    }

                    val gridCells = getClosestGridCells(center)
                    if (gridCells != null) {
                        cells[gridCells]?.let { cell ->
                            val shadowX = cell.left + iconMargin + iconSize / 2f + sideMargins.left
                            val shadowY = if (gridCells.y == rowCount - 1) {
                                cellHeight - iconMargin - iconSize / 2f
                            } else {
                                iconMargin + iconSize / 2f
                            } + sideMargins.top + cell.top

                            canvas.drawCircle(
                                shadowX, shadowY, iconSize / 2f, dragShadowCirclePaint
                            )
                        }
                    }
                }

                // show the app icon itself at dragging, move it above the finger a bit to make it visible
                val drawableX = (draggedItemCurrentCoords.first - iconSize / 1.5f).toInt()
                val drawableY = (draggedItemCurrentCoords.second - iconSize / 1.2f).toInt()
                val newDrawable = if (draggedItem?.type == ITEM_TYPE_FOLDER) {
                    draggedItem!!.toFolder().generateDrawable()
                } else {
                    draggedItem!!.drawable?.constantState?.newDrawable()?.mutate()
                }
                newDrawable?.setBounds(
                    drawableX,
                    drawableY,
                    drawableX + iconSize,
                    drawableY + iconSize
                )
                newDrawable?.draw(canvas)
            } else if (draggedItem!!.type == ITEM_TYPE_WIDGET) {
                // at first draw we are loading the widget from the database at some exact spot, not dragging it
                if (!isFirstDraw) {
                    val center = gridCenters.minBy {
                        abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
                    }

                    val gridCells = getClosestGridCells(center)
                    if (gridCells != null) {
                        val widgetRect = getWidgetOccupiedRect(gridCells)
                        val widgetPos = calculateWidgetPos(Point(widgetRect.left, widgetRect.top))
                        val leftSide = widgetPos.x.toFloat()
                        val topSide = widgetPos.y.toFloat()
                        val rightSide = leftSide + draggedItem!!.getWidthInCells() * cellWidth
                        val bottomSide = topSide + draggedItem!!.getHeightInCells() * cellHeight
                        canvas.drawRoundRect(
                            leftSide,
                            topSide,
                            rightSide,
                            bottomSide,
                            roundedCornerRadius,
                            roundedCornerRadius,
                            dragShadowCirclePaint
                        )
                    }

                    // show the widget preview itself at dragging
                    draggedItem!!.drawable?.also { drawable ->
                        val aspectRatio = drawable.minimumHeight / drawable.minimumWidth.toFloat()
                        val drawableX =
                            (draggedItemCurrentCoords.first - drawable.minimumWidth / 2f).toInt()
                        val drawableY =
                            (draggedItemCurrentCoords.second - drawable.minimumHeight / 3f).toInt()
                        val drawableWidth =
                            draggedItem!!.getWidthInCells() * cellWidth - iconMargin * (draggedItem!!.getWidthInCells() - 1)
                        drawable.setBounds(
                            drawableX,
                            drawableY,
                            drawableX + drawableWidth,
                            (drawableY + drawableWidth * aspectRatio).toInt()
                        )
                        drawable.draw(canvas)
                    }
                }
            }
        }

        isFirstDraw = false
    }

    private fun fillCellSizes() {
        labelLayouts.clear()
        cellWidth = getFakeWidth() / columnCount
        cellHeight = getFakeHeight() / rowCount
        val extraXMargin = if (cellWidth > cellHeight) {
            (cellWidth - cellHeight) / 2
        } else {
            0
        }
        val extraYMargin = if (cellHeight > cellWidth) {
            (cellHeight - cellWidth) / 2
        } else {
            0
        }
        // The profile's icon scale is applied through the margin, so labels, hit rects and drag
        // shadows - all measured from cell + margin - move with the icon instead of overlapping it.
        val baseMargin = (context.resources.getDimension(R.dimen.launcher_icon_side_margin) * 5 / columnCount).toInt()
        val cell = min(cellWidth, cellHeight)
        // The previous launcher's icon size, when known, is drawn at exactly that many dp (the
        // margin absorbs the rest of the cell); otherwise the grid's own icon scaled by the profile.
        val profileIconDp = context.launcherConfig.iconSizeDp
        val scaledIcon = if (profileIconDp > 0) {
            (profileIconDp * context.resources.displayMetrics.density).toInt().coerceIn(1, cell)
        } else {
            ((cell - 2 * baseMargin) * context.launcherConfig.iconScale).toInt().coerceIn(1, cell)
        }
        iconMargin = ((cell - scaledIcon) / 2).coerceAtLeast(0)
        iconSize = cell - 2 * iconMargin
        // Set below, once the dock's own icon size is known - the dots belong in the gap it leaves.
        // The dock (last row) is DOCK_COLUMN_COUNT slots spread over the full width, each holding
        // a cell of the grid's own size so icons, hit rects and drops need no dock special case;
        // dock cells beyond that count simply do not exist, so nothing can be dropped there.
        dockSlotCount = context.launcherConfig.dockColumnCount.coerceIn(1, MAX_DOCK_SLOTS)
        val dockColumnCount = dockSlotCount
        val dockSlotWidth = getFakeWidth() / dockColumnCount
        // A dock wider than the grid (five slots over four columns) gets narrower cells, and the
        // icon inside shrinks with them; a dock no wider keeps the grid's own cell.
        dockCellWidth = min(cellWidth, dockSlotWidth)
        // The dock draws the profile's own dock size when it has one (One UI's label-less hotseat
        // is larger than its workspace icons), else the grid icon; either way centred in its cell.
        val dockDp = context.launcherConfig.dockIconSizeDp
        dockIconSize = if (dockDp > 0) {
            (dockDp * context.resources.displayMetrics.density).toInt().coerceIn(1, min(dockCellWidth, cellHeight))
        } else {
            min(iconSize, dockCellWidth - 2 * iconMargin).coerceAtLeast(1)
        }
        dockIconMargin = ((dockCellWidth - dockIconSize) / 2).coerceAtLeast(0)
        // The page dots sit between what the last row of labels actually reaches and the top of
        // the dock's icons, centred in that band. Measuring from the labels rather than from the
        // row's edge is what keeps them clear of a name that wraps to two lines, which is taller
        // than the cell holding it and used to crowd them.
        val dockRowTop = (rowCount - 1) * cellHeight
        val dockIconTop = dockRowTop + cellHeight - dockBottomMargin - dockIconSize
        val labelBottom = if (context.launcherConfig.showHomeAppLabels) {
            val lineHeight = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top
            (rowCount - 2) * cellHeight + iconMargin + iconSize + labelSideMargin + (LABEL_MAX_LINES * lineHeight).toInt()
        } else {
            dockRowTop
        }
        val bandTop = max(dockRowTop, labelBottom)
        val clearance = pageIndicatorClearance.toInt()
        pageIndicatorsYPos = ((bandTop + dockIconTop) / 2)
            .coerceAtLeast(bandTop + clearance)
            .coerceAtMost(maxOf(dockIconTop - clearance, bandTop + clearance))
        val dockCellOffset = (dockSlotWidth - dockCellWidth) / 2
        val dockExtraXMargin = if (dockCellWidth < cellWidth) 0 else extraXMargin
        for (i in 0 until max(columnCount, dockColumnCount)) {
            for (j in 0 until rowCount) {
                val isDock = j == rowCount - 1
                if (isDock && i >= dockColumnCount) {
                    continue
                }
                if (!isDock && i >= columnCount) {
                    continue
                }
                val yMarginToAdd = if (isDock) 0 else extraYMargin
                val cellLeft = if (isDock) i * dockSlotWidth + dockCellOffset else i * cellWidth
                val rect = Rect(
                    cellLeft + (if (isDock) dockExtraXMargin else extraXMargin),
                    j * cellHeight + yMarginToAdd,
                    cellLeft + (if (isDock) dockCellWidth - dockExtraXMargin else cellWidth - extraXMargin),
                    (j + 1) * cellHeight - yMarginToAdd,
                )
                cells[Point(i, j)] = rect
                gridCenters.add(Point(rect.centerX(), rect.centerY()))
                if (isDock) {
                    dockCellY = j * cellHeight
                }
            }
        }
    }

    fun fragmentExpanded() {
        widgetViews.forEach {
            it.ignoreTouches = true
        }
        closeFolder(true)
    }

    fun fragmentCollapsed() {
        // the grid fades out behind an expanding fragment, bring it back right away so that
        // it is visible during the closing animation
        animate().cancel()
        alpha = 1f
        widgetViews.forEach {
            it.ignoreTouches = false
        }
    }

    // get the clickable area around the icon, it includes text too
    fun getClickableRect(item: HomeScreenGridItem): Rect {
        if (cells.isEmpty()) {
            fillCellSizes()
        }

        val folder = currentlyOpenFolder
        val clickableLeft: Int
        val clickableTop: Int
        if (folder != null && item.parentId == folder.item.id) {
            val itemRect = folder.getItemRect(item)
            clickableLeft = itemRect.left
            clickableTop = itemRect.top - iconMargin
        } else {
            val cell = cells[item.getTopLeft(rowCount)] ?: return Rect(0, 0, 0, 0)
            clickableLeft = cell.left + sideMargins.left
            clickableTop = if (item.docked) {
                // Same margin the dock draws with, so the target sits on the icon - see drawItemInCell.
                dockCellY + cellHeight - dockIconSize - dockBottomMargin
            } else {
                cell.top - iconMargin
            } + sideMargins.top
        }
        val additionalHeight = if (!item.docked && context.launcherConfig.showHomeAppLabels) {
            // The label's own height, so every item's target is the same and matches what is drawn.
            (LABEL_MAX_LINES * (textPaint.fontMetrics.bottom - textPaint.fontMetrics.top)).toInt()
        } else 0
        val size = if (item.docked) dockIconSize else iconSize
        val margin = if (item.docked) dockIconMargin else iconMargin
        return Rect(
            clickableLeft,
            clickableTop,
            clickableLeft + size + 2 * margin,
            clickableTop + size + 2 * margin + additionalHeight
        )
    }

    // drag the center of the widget, not the top left corner
    private fun getWidgetOccupiedRect(item: Point): Rect {
        val left = item.x - floor((draggedItem!!.getWidthInCells() - 1) / 2.0).toInt()
        val rect = Rect(
            left,
            item.y,
            left + draggedItem!!.getWidthInCells() - 1,
            item.y + draggedItem!!.getHeightInCells() - 1
        )
        if (rect.left < 0) {
            rect.right -= rect.left
            rect.left = 0
        } else if (rect.right > columnCount - 1) {
            val diff = rect.right - columnCount + 1
            rect.right -= diff
            rect.left -= diff
        }

        if (rect.top < 0) {
            rect.bottom -= rect.top
            rect.top = 0
        } else if (rect.bottom > rowCount - 2) {
            val diff = rect.bottom - rowCount + 2
            rect.bottom -= diff
            rect.top -= diff
        }

        return rect
    }

    fun isClickingGridItem(x: Int, y: Int): HomeScreenGridItem? {
        if (pager.isAnimatingPageChange() || pager.isSwiped()) {
            return null
        }

        currentlyOpenFolder?.also { folder ->
            folder.getItems().forEach { gridItem ->
                val rect = getClickableRect(gridItem)
                if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                    return gridItem
                }
            }
        }

        // if a folder is open, we only want to allow clicks on items in the folder
        if (currentlyOpenFolder != null) {
            return null
        }

        for (gridItem in gridItems.filterVisibleOnCurrentPageOnly()) {
            if (gridItem.outOfBounds()) {
                continue
            }

            if (gridItem.isSingleCellType()) {
                val rect = getClickableRect(gridItem)
                if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                    return gridItem
                }
            } else if (gridItem.type == ITEM_TYPE_WIDGET) {
                val widgetPos = calculateWidgetPos(gridItem.getTopLeft(rowCount))
                val left = widgetPos.x.toFloat()
                val top = widgetPos.y.toFloat()
                val right = left + gridItem.getWidthInCells() * cellWidth
                val bottom = top + gridItem.getHeightInCells() * cellHeight

                if (x >= left && x <= right && y >= top && y <= bottom) {
                    return gridItem
                }
            }
        }

        return null
    }

    fun intoViewSpaceCoords(screenSpaceX: Float, screenSpaceY: Float): Pair<Float, Float> {
        val viewLocation = IntArray(2)
        getLocationOnScreen(viewLocation)
        val x = screenSpaceX - viewLocation[0]
        val y = screenSpaceY - viewLocation[1]
        return Pair(x, y)
    }

    private fun HomeScreenGridItem.outOfBounds(): Boolean {
        return ((!docked && (left >= columnCount || right >= columnCount))
                // a dock slot past the slot count has no cell (an install docked before the dock shrank)
                || (docked && right >= dockSlotCount)
                || (!docked && (top >= rowCount - 1 || bottom >= rowCount - 1))
                || (type == ITEM_TYPE_WIDGET && (bottom - top > rowCount - 1 || right - left > columnCount - 1))
                )
    }

    private inner class HomeScreenGridTouchHelper(host: View) : ExploreByTouchHelper(host) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val item = isClickingGridItem(x.toInt(), y.toInt())

            return if (item != null) {
                item.id?.toInt() ?: INVALID_ID
            } else {
                INVALID_ID
            }
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>?) {
            val sorted =
                gridItems
                    .filter {
                        it.visibleOnCurrentPage()
                                || (
                                currentlyOpenFolder != null
                                        && it.parentId == currentlyOpenFolder?.item?.id
                                )
                    }
                    .sortedBy {
                        (if (it.parentId == null) {
                            it.getDockAdjustedTop(rowCount)
                        } else {
                            1
                        }) * 100 + it.left
                    }
            sorted.forEachIndexed { index, homeScreenGridItem ->
                virtualViewIds?.add(index, homeScreenGridItem.id?.toInt() ?: index)
            }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            val viewLocation = IntArray(2)
            getLocationOnScreen(viewLocation)

            // home screen
            if (virtualViewId == -1) {
                // The host app label, not a module string: a screen reader announcing the home
                // screen should name the app the user installed.
                node.text = runCatching {
                    context.applicationInfo.loadLabel(context.packageManager).toString()
                }.getOrElse { context.getString(R.string.launcher_app_launcher_name) }
                val viewBounds = Rect(left, top, right, bottom)
                val onScreenBounds = Rect(viewBounds)
                onScreenBounds.offset(viewLocation[0], viewLocation[1])
                node.setBoundsInScreen(onScreenBounds)
                node.setBoundsInParent(viewBounds)

                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
                node.setParent(this@HomeScreenGrid)
            }

            val item = gridItems.firstOrNull { it.id?.toInt() == virtualViewId }
                ?: throw IllegalArgumentException("Unknown id")

            node.text = if (item.type == ITEM_TYPE_WIDGET) {
                item.providerInfo?.loadLabel(context.packageManager) ?: item.title
            } else {
                item.title
            }

            val viewBounds = if (item == currentlyOpenFolder?.item) {
                currentlyOpenFolder?.getDrawingRect()?.toRect()
            } else if (item.type == ITEM_TYPE_WIDGET) {
                val widgetPos = calculateWidgetPos(item.getTopLeft(rowCount))
                val left = widgetPos.x
                val top = widgetPos.y
                val right = left + item.getWidthInCells() * cellWidth
                val bottom = top + item.getHeightInCells() * cellHeight

                Rect(left, top, right, bottom)
            } else {
                getClickableRect(item)
            }
            val onScreenBounds = Rect(viewBounds)
            onScreenBounds.offset(viewLocation[0], viewLocation[1])
            node.setBoundsInScreen(onScreenBounds)
            node.setBoundsInParent(viewBounds)

            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            node.setParent(this@HomeScreenGrid)
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            val item = gridItems.firstOrNull { it.id?.toInt() == virtualViewId }
                ?: throw IllegalArgumentException("Unknown id")
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> itemClickListener?.apply {
                    if (item == currentlyOpenFolder?.item) {
                        closeFolder(true)
                    } else {
                        invoke(item)
                    }
                    return true
                }

                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> itemLongClickListener?.apply {
                    invoke(item)
                    return true
                }
            }

            return false
        }
    }

    private fun deletePage(page: Int) {
        gridItems.filter { it.page > page }.forEach {
            it.page -= 1
        }
        context.homeScreenGridItemsDB.shiftPage(page, -1)

        if (pager.isOutsideOfPageRange()) {
            post {
                prevPage()
            }
        }
    }

    private fun getMaxPage() =
        gridItems.filter { !it.docked && !it.outOfBounds() }.maxOfOrNull { it.page } ?: 0

    fun nextPage(redraw: Boolean = false): Boolean {
        return pager.nextPage(redraw)
    }

    fun prevPage(redraw: Boolean = false): Boolean {
        return pager.prevPage(redraw)
    }

    fun skipToPage(targetPage: Int): Boolean {
        return pager.skipToPage(targetPage)
    }

    fun getCurrentIconSize(): Int = iconSize


    fun setSwipeMovement(diffX: Float) {
        if (draggedItem == null) {
            pager.setSwipeMovement(diffX)
        }
    }

    fun finalizeSwipe() {
        pager.finalizeSwipe()
    }

    /** See AnimatedGridPager.flingSwipe. */
    fun flingSwipe(towardsNext: Boolean): Boolean = draggedItem == null && pager.flingSwipe(towardsNext)

    /** The first page - the home page, the only one the left-edge (host) panel opens from. */
    fun isOnFirstPage() = pager.getCurrentPage() == 0 && !pager.isPaging()

    /** The last page, the only one the right-edge (apps) panel opens from. */
    fun isOnLastPage() = pager.getCurrentPage() >= getMaxPage() && !pager.isPaging()

    fun openFolder(folder: HomeScreenGridItem) {
        if (currentlyOpenFolder == null) {
            currentlyOpenFolder = folder.toFolder(animateOpening = true)
            redrawGrid()
        } else if (currentlyOpenFolder?.item?.id != folder.id) {
            closeFolder()
        }
        accessibilityHelper.invalidateRoot()
    }

    fun closeFolder(redraw: Boolean = false) {
        currentlyOpenFolder?.animateClosing {
            currentlyOpenFolder = null
            if (redraw) {
                redrawGrid()
            }
            accessibilityHelper.invalidateRoot()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return if (currentlyOpenFolder != null) {
            true
        } else {
            super.onInterceptTouchEvent(ev)
        }
    }

    private fun Canvas.drawItemInCell(item: HomeScreenGridItem, cell: Rect) {
        if (item.id != draggedItem?.id) {
            val drawableX = cell.left + (if (item.docked) dockIconMargin else iconMargin)

            // A folder's tile is composed from its children each time; an app icon is drawn from
            // the bitmap rasterised for it once (see iconBitmap) - re-rendering an adaptive icon
            // for every icon on two pages per frame is what made a page swipe crawl.
            val drawable = if (item.type == ITEM_TYPE_FOLDER) {
                item.toFolder().generateDrawable()
            } else {
                null
            }
            val bitmap = if (drawable == null) item.iconBitmap(if (item.docked) dockIconSize else iconSize) else null

            if (item.docked) {
                val drawableY = dockCellY + cellHeight - dockBottomMargin - dockIconSize + sideMargins.top

                drawable?.setBounds(
                    drawableX,
                    drawableY,
                    drawableX + dockIconSize,
                    drawableY + dockIconSize
                )
                bitmap?.let { drawBitmap(it, drawableX.toFloat(), drawableY.toFloat(), transitPaint()) }
            } else {
                val drawableY = cell.top + iconMargin
                drawable?.setBounds(
                    drawableX,
                    drawableY,
                    drawableX + iconSize,
                    drawableY + iconSize
                )
                bitmap?.let { drawBitmap(it, drawableX.toFloat(), drawableY.toFloat(), transitPaint()) }

                if (item.id != draggedItem?.id && item.title.isNotEmpty() && context.launcherConfig.showHomeAppLabels) {
                    val textX = cell.left.toFloat() + labelSideMargin
                    val textY = cell.top.toFloat() + iconSize + iconMargin + labelSideMargin
                    val textPaintToUse = if (item.parentId == null) {
                        textPaint
                    } else {
                        contrastTextPaint
                    }
                    val staticLayout = labelLayout(item.title, textPaintToUse)

                    val fullAlpha = textPaintToUse.alpha
                    textPaintToUse.alpha = pageTransitAlpha
                    withTranslation(textX, textY) {
                        staticLayout.draw(this)
                    }
                    textPaintToUse.alpha = fullAlpha
                }
            }

            drawable?.alpha = pageTransitAlpha
            drawable?.draw(this)
        }
    }

    /** Null while the page is in place, so the common case draws the bitmap with no paint at all. */
    private fun transitPaint(): Paint? = if (pageTransitAlpha >= 255) {
        null
    } else {
        pageTransitPaint.apply { alpha = pageTransitAlpha }
    }

    /**
     * The item's icon as a bitmap of [size] px, rasterised once and kept on the item until its
     * drawable or the size changes. Null when the item has no drawable yet.
     */
    private fun HomeScreenGridItem.iconBitmap(size: Int): Bitmap? {
        val source = drawable ?: return null
        if (size <= 0) return null
        iconRaster?.takeIf { iconRasterSource === source && it.width == size }?.let { return it }
        val bitmap = createBitmap(size, size)
        val painter = source.constantState?.newDrawable()?.mutate() ?: source
        painter.setBounds(0, 0, size, size)
        painter.draw(Canvas(bitmap))
        iconRaster = bitmap
        iconRasterSource = source
        return bitmap
    }

    /** Label layouts by title and paint, built once per cell width; cleared when the cells are re-measured. */
    private val labelLayouts = HashMap<String, StaticLayout>()

    private fun labelLayout(title: String, paint: TextPaint): StaticLayout {
        val key = if (paint === contrastTextPaint) "c:" + title else "n:" + title
        return labelLayouts.getOrPut(key) {
            StaticLayout.Builder
                .obtain(title, 0, title.length, paint, cellWidth - 2 * labelSideMargin)
                .setMaxLines(LABEL_MAX_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
        }
    }

    /**
     * Draws [block] as the page it belongs to is travelling: scaled down and faded in proportion
     * to how far off centre it is ([xFactor] 0 in place, 1 a full page away). The dock is drawn
     * outside this, so it stays put the way a dock does.
     */
    private fun Canvas.inPageTransit(xFactor: Float, block: () -> Unit) {
        val progress = min(abs(xFactor), 1f)
        if (progress == 0f) {
            block()
            return
        }
        pageTransitAlpha = (255 * (1f - PAGE_TRANSIT_FADE * progress)).toInt().coerceIn(0, 255)
        val scale = 1f - PAGE_TRANSIT_SCALE * progress
        val pivotY = sideMargins.top + (rowCount - 1) * cellHeight / 2f
        withScale(scale, scale, width / 2f, pivotY) {
            block()
        }
        pageTransitAlpha = 255
    }

    private fun Rect.withOffset(offsetX: Int, offsetY: Int, block: Rect.() -> Unit) {
        offset(offsetX, offsetY)
        try {
            block()
        } finally {
            offset(-offsetX, -offsetY)
        }
    }

    private fun List<HomeScreenGridItem>.filterVisibleOnCurrentPageOnly() =
        filter { it.visibleOnCurrentPage() }

    private fun HomeScreenGridItem.visibleOnCurrentPage() =
        (pager.isItemOnCurrentPage(this) || docked) && parentId == null

    private fun HomeScreenGridItem.isSingleCellType() =
        (type == ITEM_TYPE_ICON || type == ITEM_TYPE_SHORTCUT || type == ITEM_TYPE_FOLDER)

    private fun HomeScreenGridItem.toFolder(animateOpening: Boolean = false) =
        HomeScreenFolder(this, animateOpening)

    private inner class HomeScreenFolder(
        val item: HomeScreenGridItem,
        animateOpening: Boolean,
    ) {
        var scale: Float = 1f
        private var closing = false

        init {
            if (animateOpening) {
                scale = 0f
                post {
                    ValueAnimator.ofFloat(0f, 1f)
                        .apply {
                            interpolator = DecelerateInterpolator()
                            addUpdateListener {
                                scale = it.animatedValue as Float
                                redrawGrid()
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    super.onAnimationEnd(animation)
                                    scale = 1f
                                    redrawGrid()
                                }
                            })
                            duration = FOLDER_ANIMATION_DURATION
                            start()
                        }
                }
            }
        }

        fun getItems(): List<HomeScreenGridItem> {
            return gridItems
                .toList()
                .filter { it.isSingleCellType() && it.parentId == item.id }
        }

        fun generateDrawable(): Drawable? {
            if (iconSize == 0) {
                return null
            }

            val items = getItems()
            val itemsCount = getItems().count()

            if (itemsCount == 0) {
                return null
            }

            val bitmap = createBitmap(iconSize, iconSize)
            val canvas = Canvas(bitmap)
            val circlePath = Path().apply {
                addCircle(
                    (iconSize / 2).toFloat(),
                    (iconSize / 2).toFloat(),
                    (iconSize / 2).toFloat(),
                    Path.Direction.CCW
                )
            }
            canvas.clipPath(circlePath)
            canvas.drawPaint(folderIconBackgroundPaint)
            val folderColumnCount = ceil(sqrt(itemsCount.toDouble())).roundToInt()
            val folderRowCount = ceil(itemsCount.toFloat() / folderColumnCount).roundToInt()
            val scaledCellSize = (iconSize.toFloat() / folderColumnCount)
            val scaledGap = scaledCellSize / 4f
            val scaledIconSize =
                (iconSize - (folderColumnCount + 1) * scaledGap) / folderColumnCount
            val extraYMargin =
                if (folderRowCount < folderColumnCount) (scaledIconSize + scaledGap) / 2 else 0f
            items.forEach {
                val (row, column) = getItemPosition(it)
                val drawableX = (scaledGap + column * scaledIconSize + column * scaledGap).toInt()
                val drawableY =
                    (extraYMargin + scaledGap + row * scaledIconSize + row * scaledGap).toInt()
                val newDrawable = it.drawable?.constantState?.newDrawable()?.mutate()
                newDrawable?.setBounds(
                    drawableX,
                    drawableY,
                    drawableX + scaledIconSize.toInt(),
                    drawableY + scaledIconSize.toInt()
                )
                newDrawable?.draw(canvas)
            }
            return bitmap.toDrawable(resources)
        }

        fun getDrawingRect(): RectF {
            val count = getItems().count()
            if (count == 0) {
                return RectF(0f, 0f, 0f, 0f)
            }
            val columnsCount = ceil(sqrt(count.toDouble())).toInt()
            val rowsCount = ceil(count.toFloat() / columnsCount).toInt()
            val cellSize = getCellSize()
            val gap = getGapSize()
            val yGap = if (context.launcherConfig.showHomeAppLabels) {
                gap + textPaint.textSize + 2 * labelSideMargin
            } else gap
            val cell = cells[item.getTopLeft(rowCount)] ?: return RectF(0f, 0f, 0f, 0f)
            val centerX = sideMargins.left + cell.centerX()
            val centerY = sideMargins.top + cell.centerY()
            val folderDialogWidth =
                columnsCount * cellSize + 2 * folderPadding + (columnsCount - 1) * gap
            val folderDialogHeight =
                rowsCount * cellSize + 3 * folderPadding + folderTitleTextPaint.textSize + rowsCount * yGap
            var folderDialogTop = centerY - folderDialogHeight / 2
            var folderDialogLeft = centerX - folderDialogWidth / 2

            if (folderDialogLeft < left + sideMargins.left) {
                folderDialogLeft += left + sideMargins.left - folderDialogLeft
            }
            if (folderDialogLeft + folderDialogWidth > right - sideMargins.right) {
                folderDialogLeft -= folderDialogLeft + folderDialogWidth - (right - sideMargins.right)
            }
            if (folderDialogTop < top + sideMargins.top) {
                folderDialogTop += top + sideMargins.top - folderDialogTop
            }
            if (folderDialogTop + folderDialogHeight > bottom - sideMargins.bottom) {
                folderDialogTop -= folderDialogTop + folderDialogHeight - (bottom - sideMargins.bottom)
            }

            return RectF(
                folderDialogLeft,
                folderDialogTop,
                folderDialogLeft + folderDialogWidth,
                folderDialogTop + folderDialogHeight
            )
        }

        fun getItemsDrawingRect(): RectF {
            val folderRect = getDrawingRect()
            return RectF(
                folderRect.left + folderPadding,
                folderRect.top + folderPadding * 2 + folderTitleTextPaint.textSize,
                folderRect.right - folderPadding,
                folderRect.bottom - folderPadding
            )
        }

        fun getItemsGridCenters(): List<Triple<Int, Int, Int>> {
            val count = getItems().count()
            if (count == 0) {
                return emptyList()
            }

            val columnsCount = ceil(sqrt(count.toDouble())).roundToInt()
            val rowsCount = ceil(count.toFloat() / columnsCount).roundToInt()
            val folderItemsRect = getItemsDrawingRect()
            val cellSize = getCellSize()
            val gap = getGapSize()
            val yGap = if (context.launcherConfig.showHomeAppLabels) {
                gap + textPaint.textSize + 2 * labelSideMargin
            } else gap
            return (0 until columnsCount * rowsCount)
                .toList()
                .map { Pair(it % columnsCount, it / columnsCount) }
                .mapIndexed { index, (x, y) ->
                    Triple(
                        index,
                        (folderItemsRect.left + x * cellSize + x * gap + cellSize / 2).toInt(),
                        (folderItemsRect.top + y * cellSize + y * yGap + cellSize / 2).toInt()
                    )
                }
        }

        private fun getItemPosition(item: HomeScreenGridItem): Pair<Int, Int> {
            val count = getItems().count()
            val columnsCount = ceil(sqrt(count.toDouble())).roundToInt()
            val column = item.left % columnsCount
            val row = item.left / columnsCount
            return Pair(row, column)
        }

        fun getItemRect(item: HomeScreenGridItem): Rect {
            val (row, column) = getItemPosition(item)
            val itemsRect = getItemsDrawingRect()
            val cellSize = getCellSize()
            val gapSize = getGapSize()
            val yGapSize = if (context.launcherConfig.showHomeAppLabels) {
                gapSize + textPaint.textSize + 2 * labelSideMargin
            } else gapSize
            val left = (itemsRect.left + column * cellSize + column * gapSize).roundToInt()
            val top = (itemsRect.top + row * cellSize + row * yGapSize).roundToInt()
            return Rect(
                left,
                top,
                left + cellSize,
                top + cellSize
            )
        }

        fun animateClosing(callback: () -> Unit) {
            post {
                if (closing) {
                    return@post
                }
                closing = true
                ValueAnimator.ofFloat(scale, 0.2f)
                    .apply {
                        interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            scale = it.animatedValue as Float
                            redrawGrid()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                super.onAnimationEnd(animation)
                                scale = 0.2f
                                callback()
                            }
                        })
                        duration = (FOLDER_ANIMATION_DURATION * (max(0f, scale - 0.2f))).toLong()
                        start()
                    }
            }
        }

        fun getCellSize(): Int = min(cellWidth, cellHeight)

        private fun getGapSize(): Float {
            val cellSize = getCellSize()
            return if (cellSize == cellWidth) {
                0f
            } else {
                cellSize / 5f
            }
        }
    }

    companion object {
        /**
         * The view placed for the grid row [itemId]: matched by the row first, since the row's
         * [widgetId] can be stale after a refetch that raced its bind; by the widget id otherwise.
         */
        internal fun <V> placedWidgetView(
            views: List<V>,
            tagOf: (V) -> Any?,
            itemIdOf: (V) -> Long?,
            widgetId: Int,
            itemId: Long?,
        ): V? = views.firstOrNull { itemId != null && itemIdOf(it) == itemId }
            ?: views.firstOrNull { tagOf(it) == widgetId }

        /**
         * The views in [viewItemIds] whose grid row id is not in [liveItemIds]. A view with no row
         * recorded is kept: it is never guessed at.
         */
        internal fun <V> orphanWidgetViews(viewItemIds: Map<V, Long?>, liveItemIds: Set<Long>): List<V> =
            viewItemIds.filter { (_, id) -> id != null && id !in liveItemIds }.keys.toList()

        /** How far the active page dot stretches towards the next dot half-way through a swipe, as a share of the dot pitch. */
        /**
         * A home label is one line on every device and grid: a name that wraps pushes past the
         * cell that holds it, which crowds the page dots under the last row and leaves rows of
         * unequal height. What does not fit is ellipsised, as the drawer already does.
         */
        private const val LABEL_MAX_LINES = 1

        private const val PAGE_INDICATOR_STRETCH = 0.6f
        private const val PAGE_INDICATOR_INACTIVE_ALPHA = 112
        /**
         * What the row is worth when nobody is touching it; the rest of the way is the swipe's.
         * High enough that white dots still read over a pale wallpaper, which is where the halo
         * below carries most of the contrast.
         */
        private const val PAGE_INDICATOR_RESTING_ALPHA = 210f
        /** How far a page shrinks and fades as it slides out; small on purpose. */
        private const val PAGE_TRANSIT_SCALE = 0.08f
        private const val PAGE_TRANSIT_FADE = 0.45f

        /** How much a dot grows as the capsule arrives over it. */
        private const val PAGE_INDICATOR_DOT_SWELL = 0.18f
        /** The row's size as it appears, growing into place rather than blinking on. */
        private const val PAGE_INDICATOR_APPEAR_SCALE = 0.75f
        private const val FOLDER_OPEN_HOLD_THRESHOLD = 500L
        private const val FOLDER_CLOSE_HOLD_THRESHOLD = 300L
        private const val FOLDER_ANIMATION_DURATION = 200L
    }
}

/**
 * Helper class responsible for managing current page and providing utilities for animating page changes,
 * as well as partial dragigng between pages
 */
private class AnimatedGridPager(
    private val getMaxPage: () -> Int,
    private val redrawGrid: () -> Unit,
    private val getWidth: () -> Int,
    private val getHandler: () -> Handler,
    private val getNextPageBound: () -> Int,
    private val getPrevPageBound: () -> Int,
    private val pageChangeStarted: () -> Unit,
) {

    companion object {
        private const val PAGE_CHANGE_HOLD_THRESHOLD = 500L

        /** A whole page's settle; a partly dragged page gets its share of this. */
        private const val PAGE_SETTLE_MS = 220L

        /** A page walked from a swipe queued while the previous one settled. */
        private const val PAGE_SETTLE_QUEUED_MS = 160L

        /** Floor, so a page released almost in place still visibly lands. */
        private const val PAGE_SETTLE_MIN_MS = 80L
        private const val PAGE_INDICATORS_FADE_DELAY = 600L
        private const val PAGE_INDICATORS_FADE_IN = 120L

        private enum class PageChangeArea {
            LEFT,
            MIDDLE,
            RIGHT
        }
    }

    private var lastPage = 0
    private var currentPage = 0
    private var pageChangeLastArea = PageChangeArea.MIDDLE
    private var pageChangeLastAreaEntryTime = 0L
    private var pageChangeAnimLeftPercentage = 0f
    private var pageChangeEnabled = true
    private var pageChangeIndicatorsAlpha = 0f
    /** When the indicator last became visible, for its fade-in; see [getIndicatorsFadeIn]. */
    private var indicatorsShownAt = 0L
    private var pageChangeSwipedPercentage = 0f
    /** Flings that arrived while a page was settling, net: >0 pages ahead, <0 pages back; see [flingSwipe]. */
    private var pendingStep = 0

    fun getCurrentPage() = currentPage

    /** True while a page is being dragged or is still settling - a horizontal gesture is paging. */
    fun isPaging() = isSwiped() || isAnimatingPageChange() || !pageChangeEnabled

    fun isItemOnCurrentPage(item: HomeScreenGridItem) = item.page == currentPage

    fun isItemOnLastPage(item: HomeScreenGridItem) = item.page == lastPage

    fun getPageCount() = max(getMaxPage(), currentPage) + 1


    fun isOutsideOfPageRange() = currentPage > getMaxPage()

    fun isItemInSwipeRange(item: HomeScreenGridItem) =
        ((pageChangeSwipedPercentage > 0f && item.page == currentPage - 1) || (pageChangeSwipedPercentage < 0f && item.page == currentPage + 1))

    fun isSwiped() = abs(pageChangeSwipedPercentage) > 0f

    fun isAnimatingPageChange() = pageChangeAnimLeftPercentage != 0f

    /**
     * The dots are part of the home screen, not a passing hint: they stay while there is more
     * than one page, dim at rest and at full strength while a page is being dragged or settling -
     * the way MIUI / HyperOS keeps them above the dock.
     */
    fun shouldDisplayPageChangeIndicator() =
        getPageCount() > 1 || isSwiped() || isAnimatingPageChange() || pageChangeIndicatorsAlpha != 0f

    /** 0..1: how "live" the indicator is - resting dim, full while the user is moving pages. */
    fun getIndicatorsEmphasis(): Float =
        if (isSwiped() || isAnimatingPageChange()) 1f else pageChangeIndicatorsAlpha

    /** 0..1 over the first [PAGE_INDICATORS_FADE_IN] ms after the indicator appears. */
    fun getIndicatorsFadeIn(): Float =
        ((SystemClock.uptimeMillis() - indicatorsShownAt).toFloat() / PAGE_INDICATORS_FADE_IN).coerceIn(0f, 1f)

    private fun markIndicatorsShown() {
        if (!shouldDisplayPageChangeIndicator()) {
            indicatorsShownAt = SystemClock.uptimeMillis()
        }
    }

    fun getPageChangeIndicatorsAlpha() = if (pageChangeIndicatorsAlpha != 0f) {
        (pageChangeIndicatorsAlpha * 255.0f).toInt()
    } else {
        255
    }

    fun getXFactorForCurrentPage(): Float {
        return if (abs(pageChangeSwipedPercentage) > 0f) {
            pageChangeSwipedPercentage
        } else {
            if (currentPage > lastPage) {
                pageChangeAnimLeftPercentage
            } else {
                -pageChangeAnimLeftPercentage
            }
        }
    }

    fun getXFactorForLastPage(): Float {
        return if (abs(pageChangeSwipedPercentage) > 0f) {
            (1 - abs(pageChangeSwipedPercentage)) * -sign(pageChangeSwipedPercentage)
        } else {
            if (currentPage > lastPage) {
                pageChangeAnimLeftPercentage - 1
            } else {
                1 - pageChangeAnimLeftPercentage
            }
        }
    }

    fun getCurrentViewPositionInFullPageSpace(): Float {
        val rangeStart = lastPage.toFloat()
        val rangeEndPage = if (abs(pageChangeSwipedPercentage) > 0f) {
            if (pageChangeSwipedPercentage > 0f) {
                currentPage - 1
            } else {
                currentPage + 1
            }
        } else {
            currentPage
        }
        val rangeEnd = rangeEndPage.toFloat()
        val lerpAmount = if (pageChangeAnimLeftPercentage != 0f) {
            1 - pageChangeAnimLeftPercentage
        } else {
            abs(pageChangeSwipedPercentage)
        }
        return MathUtils.lerp(rangeStart, rangeEnd, lerpAmount)
    }

    fun setSwipeMovement(diffX: Float) {
        if (!pageChangeEnabled) {
            return
        }

        if (currentPage < getMaxPage() && diffX > 0f || currentPage > 0 && diffX < 0f) {
            markIndicatorsShown()
            pageChangeSwipedPercentage = (-diffX / getWidth().toFloat()).coerceIn(-1f, 1f)
            pageChangeStarted()
            redrawGrid()
        }
    }

    /**
     * A fling while a page is being dragged commits it in the fling's direction whatever distance
     * the drag covered, the way every launcher pages. A fling while a page is still settling from
     * the previous swipe is queued and played the moment that one lands, so quick consecutive
     * swipes walk the pages one after another instead of being dropped. A fling that runs against
     * the drag is consumed too and the release settles the page by distance. In all of these the
     * gesture belongs to paging; only false (nothing moving, nothing dragged) lets the caller
     * hand the fling to a side panel.
     */
    fun flingSwipe(towardsNext: Boolean): Boolean {
        if (isAnimatingPageChange() || !pageChangeEnabled) {
            // Every swipe counts: three quick flings walk three pages; one back cancels one ahead.
            pendingStep += if (towardsNext) 1 else -1
            return true
        }
        if (!isSwiped()) {
            return false
        }
        // Negative = the finger went left = the next page is being pulled in (see setSwipeMovement).
        if ((pageChangeSwipedPercentage < 0f) != towardsNext) {
            return true
        }
        lastPage = currentPage
        currentPage += if (towardsNext) 1 else -1
        handlePageChange(true)
        return true
    }

    fun finalizeSwipe() {
        if (abs(pageChangeSwipedPercentage) == 0f) {
            return
        }

        if (abs(pageChangeSwipedPercentage) > 0.5f) {
            lastPage = currentPage
            currentPage = if (pageChangeSwipedPercentage > 0f) {
                currentPage - 1
            } else {
                currentPage + 1
            }
            handlePageChange(true)
        } else {
            lastPage = if (pageChangeSwipedPercentage > 0f) {
                currentPage - 1
            } else {
                currentPage + 1
            }
            pageChangeSwipedPercentage =
                sign(pageChangeSwipedPercentage) * (1 - abs(pageChangeSwipedPercentage))
            handlePageChange(true)
        }
    }

    fun handleItemMovement(x: Int, y: Int) {
        showIndicators()
        if (x > getNextPageBound()) {
            doWithPageChangeDelay(PageChangeArea.RIGHT) {
                nextOrAdditionalPage()
            }
        } else if (x < getPrevPageBound()) {
            doWithPageChangeDelay(PageChangeArea.LEFT) {
                prevPage()
            }
        } else {
            clearPageChangeFlags()
        }
    }

    fun itemMovementStopped() {
        scheduleIndicatorsFade()
    }

    fun nextPage(redraw: Boolean = false): Boolean {
        if (currentPage < getMaxPage() && pageChangeEnabled) {
            lastPage = currentPage
            currentPage++
            handlePageChange(redraw)
            return true
        }

        return false
    }

    fun prevPage(redraw: Boolean = false): Boolean {
        if (currentPage > 0 && pageChangeEnabled) {
            lastPage = currentPage
            currentPage--
            handlePageChange(redraw)
            return true
        }

        return false
    }

    fun skipToPage(targetPage: Int): Boolean {
        if (currentPage != targetPage && targetPage < getMaxPage() + 1) {
            lastPage = currentPage
            currentPage = targetPage
            handlePageChange()
            return true
        }

        return false
    }

    private val checkAndExecuteDelayedPageChange: Runnable = Runnable {
        if (System.currentTimeMillis() - pageChangeLastAreaEntryTime > PAGE_CHANGE_HOLD_THRESHOLD) {
            when (pageChangeLastArea) {
                PageChangeArea.RIGHT -> nextOrAdditionalPage(true)
                PageChangeArea.LEFT -> prevPage(true)
                else -> clearPageChangeFlags()
            }
        }
    }

    private val startFadingIndicators: Runnable = Runnable {
        // To rest, not to nothing: on a multi-page home the dots stay, only quieter.
        ValueAnimator.ofFloat(1f, 0f)
            .apply {
                addUpdateListener {
                    pageChangeIndicatorsAlpha = it.animatedValue as Float
                    redrawGrid()
                }
                start()
            }
    }

    private fun showIndicators() {
        markIndicatorsShown()
        pageChangeIndicatorsAlpha = 1f
        getHandler().removeCallbacks(startFadingIndicators)
    }

    private fun clearPageChangeFlags() {
        pageChangeLastArea = PageChangeArea.MIDDLE
        pageChangeLastAreaEntryTime = 0
        getHandler().removeCallbacks(checkAndExecuteDelayedPageChange)
    }

    private fun schedulePageChange() {
        pageChangeLastAreaEntryTime = System.currentTimeMillis()
        getHandler().postDelayed(checkAndExecuteDelayedPageChange, PAGE_CHANGE_HOLD_THRESHOLD)
    }

    private fun scheduleIndicatorsFade() {
        pageChangeIndicatorsAlpha = 1f
        getHandler().postDelayed(startFadingIndicators, PAGE_INDICATORS_FADE_DELAY)
    }

    private fun doWithPageChangeDelay(needed: PageChangeArea, pageChangeFunction: () -> Boolean) {
        if (pageChangeLastArea != needed) {
            pageChangeLastArea = needed
            schedulePageChange()
        } else if (System.currentTimeMillis() - pageChangeLastAreaEntryTime > PAGE_CHANGE_HOLD_THRESHOLD) {
            if (pageChangeFunction()) {
                clearPageChangeFlags()
            }
        }
    }

    private fun nextOrAdditionalPage(redraw: Boolean = false): Boolean {
        if (currentPage < getMaxPage() + 1 && pageChangeEnabled) {
            lastPage = currentPage
            currentPage++
            handlePageChange(redraw)
            return true
        }

        return false
    }

    private fun handlePageChange(redraw: Boolean = false) {
        markIndicatorsShown()
        pageChangeEnabled = false
        pageChangeIndicatorsAlpha = 0f
        pageChangeStarted()
        val startingAt = 1 - abs(pageChangeSwipedPercentage)
        pageChangeSwipedPercentage = 0f
        getHandler().removeCallbacks(startFadingIndicators)
        pageChangeStarted()
        if (redraw) {
            redrawGrid()
        }
        ValueAnimator.ofFloat(startingAt, 0f)
            .apply {
                // Timed by what is left to travel: left at the framework's 300 ms, a page the finger
                // had already dragged most of the way still took the full time for its last
                // stretch, and the next swipe waited behind it - paging read as slow. A page walked
                // from swipes queued while this one settled goes quicker still.
                val full = if (pendingStep != 0) PAGE_SETTLE_QUEUED_MS else PAGE_SETTLE_MS
                duration = (full * startingAt).toLong().coerceAtLeast(PAGE_SETTLE_MIN_MS)
                // Settles rather than bounces: a page that springs past its edge and comes back
                // is the part of this that looked wrong. A shorter tail than (0.2, 0, 0, 1), which
                // spent its last third crawling the final few pixels.
                interpolator = PathInterpolator(0.2f, 0f, 0.2f, 1f)
                addUpdateListener {
                    if (it.animatedValue != 0f) {
                        pageChangeAnimLeftPercentage = it.animatedValue as Float
                        redrawGrid()
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        super.onAnimationEnd(animation)
                        pageChangeAnimLeftPercentage = 0f
                        pageChangeEnabled = true
                        lastPage = currentPage
                        clearPageChangeFlags()
                        // Swipes made while this page was settling, played one page at a time from
                        // rest; any that would run past the first or last page are dropped.
                        val step = pendingStep.sign
                        if (step > 0 && currentPage < getMaxPage() || step < 0 && currentPage > 0) {
                            pendingStep -= step
                            currentPage += step
                            handlePageChange(true)
                            return
                        }
                        pendingStep = 0
                        scheduleIndicatorsFade()
                        redrawGrid()
                    }
                })
                start()
            }
    }
}
