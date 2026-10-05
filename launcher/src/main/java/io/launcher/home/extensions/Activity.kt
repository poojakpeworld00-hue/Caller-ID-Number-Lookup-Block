package io.launcher.home.extensions

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import io.launcher.home.profile.LauncherFingerprint
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import io.launcher.home.activities.DefaultHomeHintPanel
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import io.launcher.home.R
import io.launcher.home.api.LauncherRegistry
import timber.log.Timber
import io.launcher.home.activities.LauncherPrefsPanel
import io.launcher.home.helpers.ITEM_TYPE_FOLDER
import io.launcher.home.helpers.ITEM_TYPE_ICON
import io.launcher.home.helpers.ITEM_TYPE_WIDGET
import io.launcher.home.helpers.REQUEST_DEFAULT_SMS
import io.launcher.home.helpers.REQUEST_SET_DEFAULT
import io.launcher.home.helpers.UNINSTALL_APP_REQUEST_CODE
import io.launcher.home.activities.LauncherPanel
import io.launcher.home.interfaces.ItemMenuListener
import io.launcher.home.dialogs.ItemActionsTray
import io.launcher.home.models.HomeScreenGridItem

/**
 * Puts the navigation bar back after an ad load has taken it away.
 *
 * `DynamicAdsHelper.loadDynamicAd` ORs `SYSTEM_UI_FLAG_HIDE_NAVIGATION or
 * SYSTEM_UI_FLAG_IMMERSIVE_STICKY` (0x1002) onto the host Activity's decor view on every call,
 * unconditionally and with nothing in the ads launcherConfig asking for it. Sticky is the part that makes
 * it look broken rather than deliberate: the bar comes back on a swipe and then goes away again a
 * few seconds later, so the buttons are never where the user left them.
 *
 * Called right after each load and again when a fill arrives. Clearing the flags is enough to end
 * the immersive behaviour; the bar itself is asked back explicitly so it does not wait for the next
 * interaction. The window still draws edge to edge behind a transparent bar — the *space* is given
 * back by the insets padding each screen already applies, not here.
 */
fun Activity.restoreNavigationBar() {
    val decorView = window.decorView

    @Suppress("DEPRECATION")
    decorView.systemUiVisibility = decorView.systemUiVisibility and (
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        ).inv()

    WindowInsetsControllerCompat(window, decorView)
        .show(WindowInsetsCompat.Type.systemBars())
}

/**
 * Opens another app, behind the `appLaunch` promo when one is configured.
 *
 * The gate lives here rather than at the call sites because there are three of them — the drawer,
 * the home grid and the apps panel — and the placement is "the user is leaving for another app",
 * which is true of all three. The launch itself always happens; the promo only precedes it.
 *
 * The host app itself is not gated: opening it from its own home screen is not leaving.
 */
fun Activity.launchApp(packageName: String, activityName: String) {
    if (packageName == applicationContext.packageName) return launchAppNow(packageName, activityName)
    io.launcher.home.promo.LauncherPromoController.run(
        this, io.launcher.home.promo.LauncherAdsConfig.APP_LAUNCH
    ) {
        // Just as the app opens, so the host can arm the ad it shows on the way back.
        io.launcher.home.api.LauncherRegistry.bridge.onAppLaunched(packageName)
        launchAppNow(packageName, activityName)
    }
}

/** The bare launch, with no promo. */
fun Activity.launchAppNow(packageName: String, activityName: String) {
    try {
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            `package` = packageName
            component = ComponentName.unflattenFromString("$packageName/$activityName")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            startActivity(this)
        }
    } catch (e: Exception) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            startActivity(launchIntent)
        } catch (e: Exception) {
            showErrorToast(e)
            return
        }
    }
    // Every launch from the home screen, dock, drawer and search passes here: the launcher's own
    // "most / recently opened" record, which the drawer's search suggests from. Local only.
    ensureBackgroundThread {
        runCatching { appUsageDB.recordLaunch(packageName, activityName) }
    }
}

fun Activity.launchAppInfo(packageName: String) {
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        startActivity(this)
    }
}

/**
 * Tries, in order: the system's home-app picker, the Q+ role request (shows a one-tap system
 * confirmation instead of a settings list), the generic default-apps settings, then plain
 * Settings. Used by the long-press "Set as default" menu item and by onboarding.
 */
fun Activity.requestSetAsDefaultLauncher() {
    // Which launcher is being replaced can only be read while it still holds the role.
    LauncherFingerprint.ensureCaptured(this)
    // Paired with whether the surface is the role *dialog*, because the hint below must not be
    // raised over that one — see the loop.
    val intents = buildList<Pair<Intent, Boolean>> {
        add(Intent(Settings.ACTION_HOME_SETTINGS) to false)
        if (isQPlus()) {
            add(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME) to true)
        }
        add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) to false)
        add(Intent(Settings.ACTION_SETTINGS) to false)
    }

    for ((intent, isRoleDialog) in intents) {
        try {
            startActivityForResult(intent, REQUEST_SET_DEFAULT)
            // A hint over the Settings list, where the user has to find this app among every other
            // launcher. Never over the role dialog, which already names this app and offers a button
            // to press — a card there would cover the very thing it was pointing at.
            if (!isRoleDialog) {
                DefaultHomeHintPanel.show(this)
            }
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}

fun Activity.canAppBeUninstalled(packageName: String): Boolean {
    return try {
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
    } catch (ignored: Exception) {
        false
    }
}

fun Activity.uninstallApp(packageName: String) {
    Intent(Intent.ACTION_DELETE).apply {
        data = Uri.fromParts("package", packageName, null)
        startActivityForResult(this, UNINSTALL_APP_REQUEST_CODE)
    }
    // The dialog returns before the package is gone; the launcher watches for the removal itself.
    (this as? io.launcher.home.activities.LauncherPanel)?.watchUninstall(packageName)
}

fun Activity.handleGridItemPopupMenu(
    anchorView: View,
    gridItem: HomeScreenGridItem,
    isOnAllAppsFragment: Boolean,
    listener: ItemMenuListener,
    centerX: Float,
    itemTop: Float,
    itemBottom: Float,
): ItemActionsTray {
    val isIcon = gridItem.type == ITEM_TYPE_ICON
    val isOwn = gridItem.packageName == applicationContext.packageName
    val actions = buildList {
        // Our own icon offers no App info (nor, below, Uninstall unless the host allows it): the
        // app is not managed from here.
        if (isIcon && !isOwn) add(ItemActionsTray.Action(org.fossify.commons.R.drawable.ic_info_vector, R.string.launcher_app_info) { listener.appInfoUi(gridItem) })
        // Our own icon can't be hidden either: the drawer always lists the host.
        if (isIcon && isOnAllAppsFragment && !isOwn) add(ItemActionsTray.Action(org.fossify.commons.R.drawable.ic_hide_vector, org.fossify.commons.R.string.hide) { listener.hide(gridItem) })
        if ((isIcon || gridItem.type == ITEM_TYPE_FOLDER) && !isOnAllAppsFragment) {
            add(ItemActionsTray.Action(org.fossify.commons.R.drawable.ic_rename_vector, org.fossify.commons.R.string.rename) { listener.rename(gridItem) })
        }
        if (gridItem.type == ITEM_TYPE_WIDGET) add(ItemActionsTray.Action(R.drawable.lnch_ic_resize_vector, org.fossify.commons.R.string.resize) { listener.resize(gridItem) })
        if (!isOnAllAppsFragment) add(ItemActionsTray.Action(org.fossify.commons.R.drawable.ic_cross_vector, org.fossify.commons.R.string.remove) { listener.remove(gridItem) })
        // Our own icon: the system Uninstall only when the host says it has no uninstall flow of
        // its own to run instead - see LauncherBridge.allowUninstallingHostIcon.
        if (isIcon && canAppBeUninstalled(gridItem.packageName) && (!isOwn || LauncherRegistry.bridge.allowUninstallingHostIcon())) {
            add(ItemActionsTray.Action(org.fossify.commons.R.drawable.ic_delete_vector, R.string.launcher_uninstall) { listener.uninstall(gridItem) })
        }
    }.map { action -> ItemActionsTray.Action(action.icon, action.label) { listener.onAnyClick(); action.onClick() } }

    val launcherApps =
        applicationContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val shortcutInfos = if (launcherApps.hasShortcutHostPermission()) {
        try {
            val query = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            ).setPackage(gridItem.packageName)
            launcherApps.getShortcuts(query, Process.myUserHandle())
        } catch (e: Exception) {
            null
        }
    } else {
        null
    }

    val iconSize = resources.getDimensionPixelSize(R.dimen.launcher_menu_icon_size)
    val shortcuts = shortcutInfos.orEmpty().map { shortcutInfo ->
        val iconDrawable = launcherApps.getShortcutIconDrawable(shortcutInfo, resources.displayMetrics.densityDpi)
        ItemActionsTray.Shortcut(
            icon = (iconDrawable ?: Color.TRANSPARENT.toDrawable()).toBitmap(width = iconSize, height = iconSize).toDrawable(resources),
            label = shortcutInfo.shortLabel ?: shortcutInfo.longLabel ?: "",
        ) {
            listener.onAnyClick()
            val id = shortcutInfo.id
            val packageName = shortcutInfo.`package`
            // One of the host's own shortcuts that it wants handled as an in-place removal: no
            // funnel, the icon leaves the launcher right here. The host decides which shortcut and
            // for whom - see removesHostIconInPlace.
            val host = this@handleGridItemPopupMenu
            if (packageName == host.packageName && host is LauncherPanel &&
                LauncherRegistry.bridge.removesHostIconInPlace(host, id)
            ) {
                host.removeSelfFromHome()
                return@Shortcut
            }
            launcherApps.startShortcut(packageName, id, Rect(), null, Process.myUserHandle())
        }
    }

    return ItemActionsTray(this, shortcuts, actions, onDismiss = { listener.onDismiss() }).also {
        it.show(anchorView, centerX, itemTop, itemBottom)
    }
}

/**
 * Raises the launcher task back over whatever the user was sent to.
 *
 * REORDER_TO_FRONT rather than a fresh launch: the screen underneath is still alive and only has
 * to be brought up, not rebuilt. CLEAR_TOP clears any transparent coach-mark activity stacked on
 * top of it. Wrapped because a backgrounded app is only allowed this in narrow cases - a
 * just-granted role being one of them - and a refusal is not worth a crash.
 */
fun Activity.bringTaskToFront() {
    runCatching {
        startActivity(
            Intent(this, this::class.java).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }.onFailure { Timber.w(it, "bringTaskToFront failed") }
}
