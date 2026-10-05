package io.launcher.home.profile

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetHost
import android.content.Context
import android.os.Build
import io.launcher.home.api.LauncherRegistry
import io.launcher.home.config.LauncherSetup
import io.launcher.home.extensions.homeScreenGridItemsDB
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.helpers.ITEM_TYPE_WIDGET
import io.launcher.home.helpers.PSEUDO_WIDGET_GOOGLE_SEARCH
import io.launcher.home.helpers.PSEUDO_WIDGET_SEARCH
import io.launcher.home.helpers.PSEUDO_WIDGET_TIME
import io.launcher.home.helpers.WIDGET_HOST_ID
import io.launcher.home.models.HomeScreenGridItem
import timber.log.Timber

/**
 * The first home page's two widgets - and nothing else, on every layout: the time at the top left
 * and a Google search bar on the row above the dock. App icons start on the page after it.
 *
 * The search bar is the widget launcher_config.search_widget names - Google's (the default) or
 * Chrome's - where this phone can place it without its setup screen, else the other one, else our
 * drawn Google bar ([PSEUDO_WIDGET_GOOGLE_SEARCH]). A change of that key swaps the bar
 * ([syncFirstPage]). A real widget costs
 * one system confirmation - `BIND_APPWIDGET` is privileged, so a launcher that ships through the
 * store can only ask. The grid asks once when it first draws the row (HomeScreenGrid.bindAttempted).
 *
 * launcher_config.should_show_time_widget / should_show_search_widget decide whether each widget is
 * laid down at all; the search one off means no search row, so nothing is asked for.
 */
object HomeWidgetSeeder {

    /**
     * Google's search bars, best first, by provider class. Never the "premium" one
     * (`PremiumSearchWidgetProvider`, the Glance bar an OEM home embeds): it binds and then draws
     * nothing for a launcher that does not hold the Home role.
     */
    private val GOOGLE_PROVIDERS = listOf(
        "com.google.android.googlequicksearchbox.SearchWidgetProvider",
        "com.google.android.apps.gsa.staticplugins.searchwidget.GoogleSearchWidgetProvider",
    )

    /** Chrome's search bars, best first: the quick-action one (search, mic, incognito, lens, dino), then the plain one. */
    private val CHROME_PROVIDERS = listOf(
        "org.chromium.chrome.browser.quickactionsearchwidget.QuickActionSearchWidgetProvider\$QuickActionSearchWidgetProviderSearch",
        "org.chromium.chrome.browser.searchwidget.SearchWidgetProvider",
    )

    /** The time widget spans this many columns (fewer on a narrower grid) and two rows. */
    private const val TIME_COLUMNS = 4
    private const val TIME_ROWS = 2

    /**
     * The row the previous build laid the time on, to leave the top free for the "set as default
     * home" banner. The time is back on row 0 and the banner moves below it instead (LauncherPanel
     * places it from HomeScreenGrid.onTimeWidgetBottomChanged); [moveTimeWidgetToTop] brings it up.
     */
    private const val LEGACY_TIME_TOP_ROW = 2

    /** A search bar is one row tall; anything taller is a different widget from the same app. */
    private const val MAX_ROWS_TALL_DP = 140

    /**
     * Lays down the first-page widgets launcher_config asks for; each is skipped when the page
     * already has it. Records what was applied, for [syncFirstPage].
     */
    fun seedFirstPage(context: Context) {
        val config = context.launcherConfig
        val setup = LauncherRegistry.setup()
        config.timeWidgetShown = setup.showTimeWidget
        config.searchWidgetShown = setup.showSearchWidget
        config.timeWidgetAtTop = true
        if (setup.showTimeWidget) seedTimeWidget(context)
        if (setup.showSearchWidget) seedSearchRow(context)
    }

    /**
     * Places the search row on the first page. Returns true when a row was written. No-op when the
     * page already carries one, or its row is taken.
     */
    fun seedSearchRow(context: Context): Boolean {
        val config = context.launcherConfig
        val columns = config.homeColumnCount
        if (columns <= 0) return false

        // The bar sits on the last row of the page, right above the dock, where an OEM home keeps
        // its own - not at the top, where ours used to put it.
        val row = config.homeRowCount - 2
        if (row < 0) return false

        val db = context.homeScreenGridItemsDB
        val items = db.getAllItems()
        if (items.any { isSeededSearchRow(it) }) return false
        val rowTaken = items.any { !it.docked && it.parentId == null && it.page == 0 && it.top <= row && it.bottom >= row }
        if (rowTaken) return false

        val choice = LauncherRegistry.setup().searchWidget
        config.searchWidget = choice
        val provider = searchProvider(context, choice)
        val item = HomeScreenGridItem(
            id = null,
            left = 0,
            top = row,
            right = columns - 1,
            bottom = row,
            page = 0,
            packageName = provider?.provider?.packageName ?: context.packageName,
            activityName = "",
            title = "",
            type = ITEM_TYPE_WIDGET,
            className = provider?.provider?.className ?: PSEUDO_WIDGET_GOOGLE_SEARCH,
            // Bound when the grid first draws it; a pseudo bar needs no provider at all.
            widgetId = -1,
            shortcutId = "",
            icon = null,
            docked = false,
            parentId = null,
        )
        db.insert(item)
        Timber.i("HomeWidgetSeeder: search row ($choice) = ${item.packageName}/${item.className}")
        return true
    }

    /**
     * Applies launcher_config to a home already built: removes the time / search widget switched
     * off, lays down one switched back on, and swaps the search bar when search_widget changed.
     * Runs off the main thread on every refresh; returns true when a widget was removed, so the
     * screen is rebuilt to drop its view.
     */
    fun syncFirstPage(context: Context): Boolean {
        val config = context.launcherConfig
        if (!config.homeSeeded) return false
        val setup = LauncherRegistry.setup()
        var removed = false

        if (!config.timeWidgetAtTop) {
            config.timeWidgetAtTop = true
            moveTimeWidgetToTop(context)
        }

        if (config.timeWidgetShown != setup.showTimeWidget) {
            config.timeWidgetShown = setup.showTimeWidget
            if (setup.showTimeWidget) seedTimeWidget(context) else removed = removeWidgets(context) { it.className == PSEUDO_WIDGET_TIME }
            Timber.i("HomeWidgetSeeder: time widget ${if (setup.showTimeWidget) "on" else "off"}")
        }

        if (config.searchWidgetShown != setup.showSearchWidget) {
            config.searchWidgetShown = setup.showSearchWidget
            if (setup.showSearchWidget) seedSearchRow(context) else removed = removeWidgets(context) { isSeededSearchRow(it) } || removed
            Timber.i("HomeWidgetSeeder: search widget ${if (setup.showSearchWidget) "on" else "off"}")
        } else if (setup.showSearchWidget && config.searchWidget != setup.searchWidget) {
            removeWidgets(context) { isSeededSearchRow(it) }
            // Written even when the row cannot go back (its cells taken), so this is not retried forever.
            config.searchWidget = setup.searchWidget
            seedSearchRow(context)
            removed = true
            Timber.i("HomeWidgetSeeder: search widget switched to ${setup.searchWidget}")
        }
        return removed
    }

    /**
     * Moves a time widget the previous build laid on [LEGACY_TIME_TOP_ROW] back up to row 0, above
     * the default-home banner. Left where it is when those cells are taken, or when it sits
     * anywhere else - the user put it there. Runs once per install.
     */
    private fun moveTimeWidgetToTop(context: Context) {
        val db = context.homeScreenGridItemsDB
        val items = db.getAllItems()
        val time = items.firstOrNull {
            it.type == ITEM_TYPE_WIDGET && it.className == PSEUDO_WIDGET_TIME && it.page == 0 && it.top == LEGACY_TIME_TOP_ROW
        } ?: return
        val bottom = time.bottom - time.top
        val taken = items.any {
            it.id != time.id && !it.docked && it.parentId == null && it.page == 0 &&
                it.left <= time.right && it.right >= time.left && it.top <= bottom
        }
        if (taken) return
        db.updateItemPosition(time.left, 0, time.right, bottom, time.page, false, null, time.id ?: return)
        Timber.i("HomeWidgetSeeder: time widget moved back to row 0, above the default-home banner")
    }

    /** Deletes every grid widget [match] picks, releasing its bound id. Returns true when any went. */
    private fun removeWidgets(context: Context, match: (HomeScreenGridItem) -> Boolean): Boolean {
        val db = context.homeScreenGridItemsDB
        val gone = db.getAllItems().filter { it.type == ITEM_TYPE_WIDGET && match(it) }
        gone.forEach { item ->
            if (item.widgetId > 0) runCatching { AppWidgetHost(context, WIDGET_HOST_ID).deleteAppWidgetId(item.widgetId) }
            db.deleteItemById(item.id ?: return@forEach)
        }
        return gone.isNotEmpty()
    }

    /**
     * The time at the top left of the first page, [TIME_COLUMNS] wide and [TIME_ROWS] tall; the
     * default-home banner is placed below it. Returns true when it was written; no-op when the page
     * already has one or those cells are taken.
     */
    fun seedTimeWidget(context: Context): Boolean {
        val config = context.launcherConfig
        val columns = config.homeColumnCount
        val rows = config.homeRowCount - 1
        if (columns <= 0 || rows < TIME_ROWS + 1) return false
        val right = minOf(TIME_COLUMNS, columns) - 1
        val top = 0
        val bottom = TIME_ROWS - 1

        val db = context.homeScreenGridItemsDB
        val items = db.getAllItems()
        if (items.any { it.className == PSEUDO_WIDGET_TIME && it.page == 0 }) return false
        val taken = items.any {
            !it.docked && it.parentId == null && it.page == 0 && it.left <= right && it.top <= bottom && it.bottom >= top
        }
        if (taken) return false

        db.insert(
            HomeScreenGridItem(
                id = null,
                left = 0,
                top = top,
                right = right,
                bottom = bottom,
                page = 0,
                packageName = context.packageName,
                activityName = "",
                title = "",
                type = ITEM_TYPE_WIDGET,
                className = PSEUDO_WIDGET_TIME,
                widgetId = -1,
                shortcutId = "",
                icon = null,
                docked = false,
                parentId = null,
            )
        )
        Timber.i("HomeWidgetSeeder: time widget ${right + 1}x$TIME_ROWS at the top left")
        return true
    }

    /** True for either first-page widget: the time, or the search row [seedSearchRow] lays down. */
    fun isSeededWidget(item: HomeScreenGridItem): Boolean =
        isSeededSearchRow(item) ||
            (item.type == ITEM_TYPE_WIDGET && item.page == 0 && item.className == PSEUDO_WIDGET_TIME)

    /**
     * True for the row [seedSearchRow] lays down: one row tall from the left edge of the first
     * page - our drawn bar (the Google one, or the plain one older installs got) or a real
     * search widget.
     */
    /** The seeded search row as a real Google or Chrome widget, i.e. one that has to be bound. */
    fun isBoundSearchRow(item: HomeScreenGridItem): Boolean =
        isSeededSearchRow(item) && (item.className in GOOGLE_PROVIDERS || item.className in CHROME_PROVIDERS)

    private fun isSeededSearchRow(item: HomeScreenGridItem): Boolean =
        item.type == ITEM_TYPE_WIDGET && !item.docked && item.parentId == null && item.page == 0 &&
            item.left == 0 && item.top == item.bottom &&
            (item.className == PSEUDO_WIDGET_GOOGLE_SEARCH || item.className == PSEUDO_WIDGET_SEARCH ||
                item.className in GOOGLE_PROVIDERS || item.className in CHROME_PROVIDERS)

    /**
     * True when [info] has a setup screen Android lets a launcher skip (API 31's
     * `configuration_optional`): the widget works as placed, so no screen is shown.
     */
    fun configurationOptional(info: AppWidgetProviderInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0

    /**
     * The search bar for [choice]: its own widgets first, then the other app's, each short enough
     * to be a bar; null when the phone has none, which leaves our drawn Google bar.
     */
    private fun searchProvider(context: Context, choice: String): AppWidgetProviderInfo? = runCatching {
        val installed = AppWidgetManager.getInstance(context).installedProviders
            .filter { it.minHeight <= MAX_ROWS_TALL_DP * context.resources.displayMetrics.density }
            // A widget whose setup screen cannot be skipped would throw it in the user's face on
            // first launch; only ones that place themselves are seeded.
            .filter { it.configure == null || configurationOptional(it) }
        val order = if (choice == LauncherSetup.SEARCH_WIDGET_CHROME) CHROME_PROVIDERS + GOOGLE_PROVIDERS else GOOGLE_PROVIDERS + CHROME_PROVIDERS
        order.firstNotNullOfOrNull { className ->
            installed.firstOrNull { it.provider.className == className }
        }
    }.getOrNull()
}
