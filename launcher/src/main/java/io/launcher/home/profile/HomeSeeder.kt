package io.launcher.home.profile

import android.appwidget.AppWidgetHost
import android.content.Context
import android.content.pm.ApplicationInfo
import io.launcher.home.extensions.homeScreenGridItemsDB
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.helpers.ITEM_TYPE_FOLDER
import io.launcher.home.helpers.ITEM_TYPE_ICON
import io.launcher.home.helpers.ITEM_TYPE_WIDGET
import io.launcher.home.helpers.WIDGET_HOST_ID
import io.launcher.home.models.AppLauncher
import timber.log.Timber

/**
 * What the first home page holds on the very first build, and what happens to apps installed
 * afterwards.
 *
 * The first page carries two widgets and no app icons, on every layout: the time at the top left
 * and a Google search bar above the dock ([HomeWidgetSeeder]). Where the pages hold every app - our
 * own setup, no drawer, or Home + Drawer read from the replaced launcher's build (ColorOS
 * `config_default_launcher_mode` 2) - they follow from the second page on. Every app installed
 * after that lands on the workspace too.
 */
object HomeSeeder {

    /** Builds the first page once per install: its two widgets, and the install mark for [placeNewInstalls]. */
    fun seedFirstPage(context: Context, launchers: List<AppLauncher>) {
        val config = context.launcherConfig
        if (config.homeSeeded || launchers.isEmpty()) return
        config.homeSeeded = true
        HomeWidgetSeeder.seedFirstPage(context)
        config.lastInstallSeen = installed(context).values.maxOfOrNull { it.at } ?: 0L
        Timber.i("HomeSeeder: first page = time + search, no app icons")
    }

    /**
     * Puts every app installed since the last look onto the workspace - on every phone, whatever
     * the replaced launcher did. Oldest first, so they land in the order they arrived.
     */
    fun placeNewInstalls(context: Context, launchers: List<AppLauncher>): Int {
        val config = context.launcherConfig
        if (!config.homeSeeded || launchers.isEmpty()) return 0
        val installed = installed(context)
        val newest = installed.values.maxOfOrNull { it.at } ?: 0L
        val since = config.lastInstallSeen
        if (newest <= since) return 0
        config.lastInstallSeen = newest
        // A home seeded before this was tracked has no mark yet: take today's as the start.
        if (since == 0L) return 0
        val fresh = launchers
            .filter { installed[it.packageName]?.takeIf { p -> p.at > since && !p.system } != null }
            .sortedBy { installed[it.packageName]?.at ?: 0L }
        if (fresh.isEmpty()) return 0
        val placed = HomeAppsFiller.place(context, fresh)
        Timber.i("HomeSeeder: $placed newly installed app(s) placed on the workspace")
        return placed
    }

    /**
     * Clears the workspace for a layout switch (launcher_config.os_style): every app icon and
     * folder off the dock goes, the dock and the user's widgets stay. Our own first-page widgets go
     * too, so the next build lays them on the new grid; any other widget or shortcut the new grid
     * would cut off is pulled back inside it. Runs off the main thread, before [seedFirstPage].
     */
    fun resetForRebuild(context: Context) {
        val config = context.launcherConfig
        val db = context.homeScreenGridItemsDB
        val items = db.getAllItems()
        val folders = items.filter { it.type == ITEM_TYPE_FOLDER && !it.docked }.mapNotNull { it.id }.toSet()
        var removed = 0
        val kept = ArrayList<io.launcher.home.models.HomeScreenGridItem>()
        items.forEach { item ->
            val id = item.id ?: return@forEach
            val drop = when {
                item.docked -> false
                item.type == ITEM_TYPE_FOLDER -> true
                item.type == ITEM_TYPE_ICON -> item.parentId == null || item.parentId in folders
                else -> HomeWidgetSeeder.isSeededWidget(item)
            }
            if (drop) {
                if (item.type == ITEM_TYPE_WIDGET && item.widgetId > 0) {
                    runCatching { AppWidgetHost(context, WIDGET_HOST_ID).deleteAppWidgetId(item.widgetId) }
                }
                db.deleteItemById(id)
                removed++
            } else if (!item.docked && item.parentId == null) {
                kept.add(item)
            }
        }

        val cols = config.homeColumnCount
        val rows = config.homeRowCount - 1
        kept.forEach { item ->
            if (item.right < cols && item.bottom < rows) return@forEach
            val width = (item.right - item.left + 1).coerceIn(1, cols)
            val height = (item.bottom - item.top + 1).coerceIn(1, rows)
            val left = item.left.coerceIn(0, cols - width)
            val top = item.top.coerceIn(0, rows - height)
            db.updateItemPosition(left, top, left + width - 1, top + height - 1, item.page, false, null, item.id!!)
        }
        Timber.i("HomeSeeder: layout switch - $removed item(s) cleared, ${kept.size} widget(s)/shortcut(s) kept")
    }

    /** When a package arrived and whether it came with the phone. */
    private class Installed(val packageName: String, val at: Long, val system: Boolean)

    /**
     * Every package with its install time and whether it is stock, from a single PackageManager
     * call - asking per app cost a binder round trip each, which on a phone with 150 apps is what
     * the home screen was waiting for. Empty when the list cannot be read.
     */
    private fun installed(context: Context): Map<String, Installed> = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getInstalledPackages(0).associate { info ->
            val system = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
            info.packageName to Installed(info.packageName, info.firstInstallTime, system)
        }
    }.getOrDefault(emptyMap())
}
