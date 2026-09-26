package io.launcher.home.extensions

import android.app.role.RoleManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import io.launcher.home.helpers.IconShaper
import android.os.Build
import android.os.Process
import android.util.Size
import android.provider.Telephony
import androidx.annotation.RequiresApi
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isSPlus
import io.launcher.home.databases.AppsDatabase
import io.launcher.home.helpers.Config
import io.launcher.home.interfaces.AppLaunchersDao
import io.launcher.home.interfaces.AppUsageDao
import io.launcher.home.interfaces.HiddenIconsDao
import io.launcher.home.interfaces.HomeScreenGridItemsDao
import kotlin.math.ceil
import kotlin.math.max

val Context.launcherConfig: Config get() = Config.newInstance(applicationContext)

val Context.launchersDB: AppLaunchersDao
    get() = AppsDatabase.getInstance(applicationContext).AppLaunchersDao()

val Context.homeScreenGridItemsDB: HomeScreenGridItemsDao
    get() = AppsDatabase.getInstance(
        applicationContext
    ).HomeScreenGridItemsDao()

val Context.hiddenIconsDB: HiddenIconsDao
    get() = AppsDatabase.getInstance(applicationContext).HiddenIconsDao()

val Context.appUsageDB: AppUsageDao
    get() = AppsDatabase.getInstance(applicationContext).AppUsageDao()

@get:RequiresApi(Build.VERSION_CODES.Q)
val Context.roleManager: RoleManager
    get() = getSystemService(RoleManager::class.java)

fun Context.getDrawableForPackageName(packageName: String): Drawable? {
    var drawable: Drawable? = null
    try {
        // try getting the properly colored launcher icons
        val launcher = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activityList = launcher.getActivityList(packageName, Process.myUserHandle())[0]
        drawable = activityList.getBadgedIcon(0)
    } catch (e: Exception) {
    } catch (e: Error) {
    }

    if (drawable == null) {
        drawable = try {
            packageManager.getApplicationIcon(packageName)
        } catch (ignored: Exception) {
            null
        }
    }

    // A legacy single-bitmap icon gets the device's icon shape like every adaptive one.
    return drawable?.let { IconShaper.shape(it, launcherConfig.legacyIconTray) }
}

fun Context.getInitialCellSize(
    info: AppWidgetProviderInfo,
    fallbackWidth: Int,
    fallbackHeight: Int
): Size {
    return if (isSPlus() && info.targetCellWidth != 0 && info.targetCellHeight != 0) {
        Size(info.targetCellWidth, info.targetCellHeight)
    } else {
        val widthCells = getCellCount(fallbackWidth)
        val heightCells = getCellCount(fallbackHeight)
        Size(widthCells, heightCells)
    }
}

fun Context.getCellCount(size: Int): Int {
    val tiles = ceil(((size / resources.displayMetrics.density) - 30) / 70.0).toInt()
    return max(tiles, 1)
}

/**
 * Whether this app is the SMS handler — the messages panel's gate for asking to become it.
 *
 * The role is the authority on Q+, exactly as the messaging module's `PermissionManagerImpl` reads
 * it. `getDefaultSmsPackage` is the legacy telephony answer and the two can disagree: it goes
 * through the telephony stack, so it comes back null or stale on builds without it — a tablet, an
 * emulator with no SIM, a secondary user — while the role is genuinely held. Reading only that one
 * left the panel believing it was not the handler and firing the role request on every open, with
 * the system dismissing it straight away because there was nothing to grant.
 *
 * Still the fallback below Q, where the role does not exist.
 */
fun Context.isDefaultSmsApp(): Boolean {
    val roleHeld = if (isQPlus()) {
        runCatching {
            with(roleManager) {
                if (isRoleAvailable(RoleManager.ROLE_SMS)) {
                    isRoleHeld(RoleManager.ROLE_SMS)
                } else {
                    null
                }
            }
        }.getOrNull()
    } else {
        null
    }

    return roleHeld ?: (Telephony.Sms.getDefaultSmsPackage(this) == packageName)
}

fun Context.isDefaultLauncher(): Boolean {
    return if (isQPlus()) {
        with(roleManager) {
            isRoleAvailable(RoleManager.ROLE_HOME) && isRoleHeld(RoleManager.ROLE_HOME)
        }
    } else {
        val filters = ArrayList<IntentFilter>()
        val activities = ArrayList<ComponentName>()
        @Suppress("DEPRECATION")
        packageManager.getPreferredActivities(filters, activities, null)
        return activities.indices.any { i ->
            activities[i].packageName == packageName &&
                    filters[i].hasAction(Intent.ACTION_MAIN) &&
                    filters[i].hasCategory(Intent.CATEGORY_HOME)
        }
    }
}
