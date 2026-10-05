package io.launcher.home.helpers

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.net.toUri
import io.launcher.home.extensions.homeScreenGridItemsDB
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.models.HomeScreenGridItem
import timber.log.Timber

/**
 * The fake uninstall seen from our own launcher: our app leaves the dock, the home pages and the
 * drawer, and the dock slot it held gets the phone's own messaging app instead - so the home
 * screen looks exactly like the app was removed.
 *
 * `launcherConfig.selfIconHidden` is one-way: once set, [isOurRow] rows are dropped wherever the
 * grid is loaded (HomeScreenGrid.fetchGridItems) and LauncherPanel's messaging slot resolves to
 * [systemMessagingPackage] rather than to us, so no seed, profile copy or SMS-role sync can put the
 * icon back. The drawer stops listing our package too (LauncherScan filters it once hidden).
 *
 * The launcher's own built-in widgets (clock, search bar) are stored under our package name too;
 * they are the launcher, not "the app", and always stay.
 */
object SelfRemoval {

    /** The phone maker's own messaging app, preferred over Google Messages when both ship. */
    private val MAKER_MESSAGING = mapOf(
        "samsung" to "com.samsung.android.messaging",
        "xiaomi" to "com.miui.mms",
        "redmi" to "com.miui.mms",
        "poco" to "com.miui.mms",
        "oneplus" to "com.oneplus.mms",
        "oppo" to "com.coloros.mms",
        "realme" to "com.coloros.mms",
        "motorola" to "com.motorola.messaging",
        "sony" to "com.sonyericsson.conversations",
        "htc" to "com.htc.sense.mms",
        "lge" to "com.lge.message",
        "asus" to "com.asus.message",
    )

    /** Known stock messaging apps, most likely first; any other system SMS app comes after these. */
    private val STOCK_MESSAGING = listOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.miui.mms",
        "com.oneplus.mms",
        "com.coloros.mms",
        "com.oplus.mms",
        "com.android.messaging",
        "com.motorola.messaging",
        "com.sonyericsson.conversations",
        "com.htc.sense.mms",
        "com.lge.message",
        "com.asus.message",
    )

    /** A home-grid row that belongs to the app (icon, pinned shortcut, real widget), not to the launcher. */
    fun isOurRow(item: HomeScreenGridItem, ownPackage: String): Boolean =
        item.packageName == ownPackage && !item.className.startsWith(PSEUDO_WIDGET_PREFIX)

    /**
     * Hides the app from our launcher for good. Blocking (Room) - call off the main thread.
     * Never throws: a failure here must not stop the caller from closing the app.
     */
    fun hide(context: Context) {
        val app = context.applicationContext
        val own = app.packageName
        app.launcherConfig.selfIconHidden = true
        runCatching {
            val db = app.homeScreenGridItemsDB
            val items = db.getAllItems()
            val ours = items.filter { isOurRow(it, own) }
            val dockSpot = ours.firstOrNull { it.docked && it.parentId == null }

            ours.forEach { row -> row.id?.let { db.deleteById(it) } }

            val messaging = systemMessagingPackage(app)
            if (messaging != null) {
                val alreadyDocked = items.any { it.docked && it.parentId == null && it.packageName == messaging }
                if (dockSpot != null && !alreadyDocked) {
                    db.insert(dockRow(app, messaging, dockSpot))
                }
                // The slot is now the stock app's; LauncherPanel's SMS-role sync must not fight it.
                app.launcherConfig.dockHostPackage = messaging
            }
            Timber.d("SelfRemoval: hid %d rows, dock spot=%s -> %s", ours.size, dockSpot?.left, messaging)
        }.onFailure { Timber.e(it, "SelfRemoval: could not clear our rows") }
    }

    /**
     * The phone's own messaging app, for the dock slot we leave: a system app that handles
     * `smsto:` and has a launcher entry, the well-known stock ones first. Null when there is none.
     */
    fun systemMessagingPackage(context: Context): String? = runCatching {
        val pm = context.packageManager
        val own = context.packageName
        val handlers = pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, "smsto:".toUri()), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != own && pm.getLaunchIntentForPackage(it) != null }
        val system = handlers.filter { isSystemApp(pm, it) }
        val maker = MAKER_MESSAGING[android.os.Build.MANUFACTURER.orEmpty().lowercase()]
        maker?.takeIf { it in system }
            ?: STOCK_MESSAGING.firstOrNull { it in system }
            ?: system.firstOrNull()
            ?: STOCK_MESSAGING.firstOrNull { it in handlers }
            ?: STOCK_MESSAGING.firstOrNull { it != own && pm.getLaunchIntentForPackage(it) != null }
    }.getOrNull()

    private fun isSystemApp(pm: PackageManager, packageName: String): Boolean = runCatching {
        (pm.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    private fun dockRow(context: Context, packageName: String, spot: HomeScreenGridItem): HomeScreenGridItem {
        val pm = context.packageManager
        val title = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault("")
        return HomeScreenGridItem(
            id = null,
            left = spot.left,
            top = spot.top,
            right = spot.right,
            bottom = spot.bottom,
            page = spot.page,
            packageName = packageName,
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
    }
}
