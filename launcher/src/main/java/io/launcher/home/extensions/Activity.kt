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
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import io.launcher.home.activities.DefaultHomeHintPanel
import androidx.core.view.MenuCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.forEach
import com.google.android.material.color.MaterialColors
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isDynamicTheme
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isSPlus
import timber.log.Timber
import io.launcher.home.R
import io.launcher.home.api.LauncherRegistry
import io.launcher.home.activities.LauncherPrefsPanel
import io.launcher.home.helpers.ITEM_TYPE_FOLDER
import io.launcher.home.helpers.ITEM_TYPE_ICON
import io.launcher.home.helpers.ITEM_TYPE_WIDGET
import io.launcher.home.helpers.REQUEST_DEFAULT_SMS
import io.launcher.home.helpers.REQUEST_SET_DEFAULT
import io.launcher.home.helpers.UNINSTALL_APP_REQUEST_CODE
import io.launcher.home.interfaces.ItemMenuListener
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
}

fun Activity.handleGridItemPopupMenu(
    anchorView: View,
    gridItem: HomeScreenGridItem,
    isOnAllAppsFragment: Boolean,
    listener: ItemMenuListener,
): PopupMenu {
    val contextTheme = ContextThemeWrapper(this, getPopupMenuTheme())
    return PopupMenu(contextTheme, anchorView, Gravity.TOP or Gravity.END).apply {
        if (isQPlus()) {
            setForceShowIcon(true)
        }

        inflate(R.menu.lnch_menu_app_icon)
        menu.forEach {
            val default = getProperTextColor()
            val color = if (isSPlus() && isDynamicTheme()) {
                default
            } else {
                MaterialColors.getColor(contextTheme, android.R.attr.actionMenuTextColor, default)
            }
            it.iconTintList = ColorStateList.valueOf(color)
        }
        menu.findItem(R.id.renameUi).isVisible =
            (gridItem.type == ITEM_TYPE_ICON || gridItem.type == ITEM_TYPE_FOLDER) && !isOnAllAppsFragment
        menu.findItem(R.id.hide_iconUi).isVisible =
            gridItem.type == ITEM_TYPE_ICON && isOnAllAppsFragment
        menu.findItem(R.id.resizeUi).isVisible = gridItem.type == ITEM_TYPE_WIDGET
        // Our own icon offers no App info (nor, below, Uninstall): the app is not managed from here.
        menu.findItem(R.id.app_infoUi).isVisible = gridItem.type == ITEM_TYPE_ICON
                && gridItem.packageName != applicationContext.packageName
        // Our own icon: the system Uninstall only when the host says it has no uninstall flow of
        // its own to run instead - see LauncherBridge.allowUninstallingHostIcon.
        menu.findItem(R.id.uninstallUi).isVisible = gridItem.type == ITEM_TYPE_ICON
                && canAppBeUninstalled(gridItem.packageName)
                && (gridItem.packageName != applicationContext.packageName || LauncherRegistry.bridge.allowUninstallingHostIcon())
        menu.findItem(R.id.removeUi).isVisible = !isOnAllAppsFragment

        val launcherApps =
            applicationContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val shortcuts = if (launcherApps.hasShortcutHostPermission()) {
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

        val hasShortcuts = !shortcuts.isNullOrEmpty()
        MenuCompat.setGroupDividerEnabled(menu, hasShortcuts)
        menu.setGroupVisible(R.id.group_shortcutsUi, hasShortcuts)
        if (hasShortcuts) {
            val iconSize = resources.getDimensionPixelSize(R.dimen.launcher_menu_icon_size)
            shortcuts?.forEach { shortcutInfo ->
                val iconDrawable = launcherApps.getShortcutIconDrawable(
                    shortcutInfo, resources.displayMetrics.densityDpi
                )

                menu.add(R.id.group_shortcutsUi, Menu.NONE, Menu.NONE, shortcutInfo.getLabel())
                    .setIcon(
                        (iconDrawable ?: Color.TRANSPARENT.toDrawable())
                            .toBitmap(width = iconSize, height = iconSize)
                            .toDrawable(resources)
                    )
                    .setOnMenuItemClickListener { _ ->
                        listener.onAnyClick()
                        val id = shortcutInfo.id
                        val packageName = shortcutInfo.`package`
                        val userHandle = Process.myUserHandle()
                        launcherApps.startShortcut(packageName, id, Rect(), null, userHandle)
                        true
                    }
            }
        }

        setOnMenuItemClickListener { item ->
            listener.onAnyClick()
            when (item.itemId) {
                R.id.hide_iconUi -> listener.hide(gridItem)
                R.id.renameUi -> listener.rename(gridItem)
                R.id.resizeUi -> listener.resize(gridItem)
                R.id.app_infoUi -> listener.appInfoUi(gridItem)
                R.id.removeUi -> listener.remove(gridItem)
                R.id.uninstallUi -> listener.uninstall(gridItem)
            }
            true
        }

        setOnDismissListener {
            listener.onDismiss()
        }

        listener.beforeShow(menu)

        show()
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
