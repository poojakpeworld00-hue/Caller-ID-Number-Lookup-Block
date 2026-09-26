package io.launcher.home.profile

import android.content.Context
import io.launcher.home.extensions.homeScreenGridItemsDB
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.helpers.FIRST_APPS_PAGE
import io.launcher.home.helpers.ITEM_TYPE_ICON
import io.launcher.home.models.AppLauncher
import io.launcher.home.models.HomeScreenGridItem
import io.launcher.home.models.appLauncherComparator
import timber.log.Timber

/**
 * Puts icons on the workspace: row-major, first free cell, never moving what is already there -
 * existing icons, folders, widgets and the dock all stay. Two callers, both on a background
 * thread with the launcher list the drawer would have shown, so hidden icons stay hidden:
 * [placeMissing] for the no-drawer home ([DrawerMode.NONE], the MIUI / Funtouch / EMUI factory
 * look, where every app lives on a page), and [HomeSeeder] for each app installed afterwards.
 * Icons start on [FIRST_APPS_PAGE]: the first page belongs to the time and search widgets.
 */
object HomeAppsFiller {

    /** Places every app not on the workspace yet; returns how many icons were added. */
    fun placeMissing(context: Context, launchers: List<AppLauncher>): Int =
        place(context, launchers.sortedWith(appLauncherComparator), spread = true)

    /**
     * How many icons a page should take so [count] of them share [pages] pages evenly, rather than
     * filling each page and leaving the last one holding two icons. Never more than [capacity],
     * and the remainder rides on the earlier pages, so any raggedness is at the end where a real
     * home screen has it too.
     */
    fun perPage(count: Int, capacity: Int, pages: Int): Int {
        if (capacity <= 0 || pages <= 0) return capacity
        val even = (count + pages - 1) / pages
        return even.coerceIn(1, capacity)
    }

    /**
     * Places [apps] in the order given, skipping any already on the workspace, appending pages as
     * they fill. With [spread] the icons share the pages they need evenly instead of filling each one
     * and leaving the last holding a couple - the arrangement no real home screen has. Returns how
     * many icons were added.
     */
    fun place(context: Context, apps: List<AppLauncher>, spread: Boolean = false): Int {
        val config = context.launcherConfig
        val columns = config.homeColumnCount
        // The grid's last row is the dock; the pages above it are what icons fill.
        val rows = config.homeRowCount - 1
        if (columns <= 0 || rows <= 0 || apps.isEmpty()) return 0

        val db = context.homeScreenGridItemsDB
        val items = db.getAllItems()

        // Already on the home screen, in any form: a page cell, the dock, or inside a folder. A
        // package-only match covers dock rows, which are seeded without an activity name.
        val placedIds = HashSet<String>()
        val placedPackages = HashSet<String>()
        items.filter { it.type == ITEM_TYPE_ICON }.forEach {
            if (it.activityName.isEmpty()) placedPackages.add(it.packageName) else placedIds.add(it.getItemIdentifier())
        }

        val occupied = HashSet<Triple<Int, Int, Int>>()
        items.filter { it.parentId == null && !it.docked }.forEach { item ->
            for (x in item.left..item.right) for (y in item.top..item.bottom) {
                occupied.add(Triple(item.page, x, y))
            }
        }

        val missing = apps
            .filter { it.getLauncherIdentifier() !in placedIds && it.packageName !in placedPackages }
        if (missing.isEmpty()) return 0

        // Evenly over as many pages as the icons need; the remainder rides on the earlier pages,
        // so what is ragged is the last page, where a real home screen is ragged too.
        val capacity = columns * rows
        val pageCap = if (spread) {
            val pages = ((missing.size + capacity - 1) / capacity).coerceAtLeast(1)
            perPage(missing.size, capacity, pages)
        } else {
            null
        }

        var page = FIRST_APPS_PAGE
        var row = 0
        var col = 0
        fun advance() {
            col++
            if (col >= columns) {
                col = 0
                row++
                if (row >= rows) {
                    row = 0
                    page++
                }
            }
        }

        val added = ArrayList<HomeScreenGridItem>(missing.size)
        var pageOfCount = -1
        var placedOnPage = 0
        for (launcher in missing) {
            while (Triple(page, col, row) in occupied) advance()
            if (page != pageOfCount) {
                pageOfCount = page
                placedOnPage = 0
            }
            // This page has taken its share; the rest belong to the next one.
            if (pageCap != null && placedOnPage >= pageCap) {
                page++
                row = 0
                col = 0
                pageOfCount = page
                placedOnPage = 0
                while (Triple(page, col, row) in occupied) advance()
            }
            added.add(
                HomeScreenGridItem(
                    id = null,
                    left = col,
                    top = row,
                    right = col,
                    bottom = row,
                    page = page,
                    packageName = launcher.packageName,
                    activityName = launcher.activityName,
                    title = launcher.title,
                    type = ITEM_TYPE_ICON,
                    className = "",
                    widgetId = -1,
                    shortcutId = "",
                    icon = null,
                    docked = false,
                    parentId = null,
                )
            )
            occupied.add(Triple(page, col, row))
            placedOnPage++
            advance()
        }
        db.insertAll(added)
        Timber.i("HomeAppsFiller: placed ${added.size} apps on the workspace (pages up to $page)")
        return added.size
    }
}
