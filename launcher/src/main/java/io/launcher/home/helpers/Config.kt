package io.launcher.home.helpers

import android.content.Context
import org.fossify.commons.helpers.BaseConfig
import io.launcher.home.R
import io.launcher.home.promo.LauncherGuideStep
import io.launcher.home.profile.DrawerMode

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var wasHomeScreenInit: Boolean
        get() = prefs.getBoolean(WAS_HOME_SCREEN_INIT, false)
        set(wasHomeScreenInit) = prefs.edit().putBoolean(WAS_HOME_SCREEN_INIT, wasHomeScreenInit).apply()

    var dockHostPackage: String
        get() = prefs.getString(DOCK_HOST_PACKAGE, "") ?: ""
        set(dockHostPackage) = prefs.edit().putString(DOCK_HOST_PACKAGE, dockHostPackage).apply()

    /**
     * One-way: the fake uninstall at the end of the uninstall flow sets it and nothing clears it
     * short of a reinstall. While set, hostDockItem never builds our own dock row.
     */
    var selfIconHidden: Boolean
        get() = prefs.getBoolean(SELF_ICON_HIDDEN, false)
        set(selfIconHidden) = prefs.edit().putBoolean(SELF_ICON_HIDDEN, selfIconHidden).apply()

    var wasOnboardingCompleted: Boolean
        get() = prefs.getBoolean(WAS_ONBOARDING_COMPLETED, false)
        set(wasOnboardingCompleted) = prefs.edit()
            .putBoolean(WAS_ONBOARDING_COMPLETED, wasOnboardingCompleted).apply()

    // The search pill used to be seeded onto the home screen on first run. It is gone, and this
    // records the one-time sweep that takes it off the home screens it was already seeded onto —
    // once only, so a pill the user adds back from the widgets picker is left alone.
    var wasSearchBarPurged: Boolean
        get() = prefs.getBoolean(WAS_SEARCH_BAR_PURGED, false)
        set(wasSearchBarPurged) = prefs.edit().putBoolean(WAS_SEARCH_BAR_PURGED, wasSearchBarPurged).apply()

    /**
     * Whether [step]'s gesture has actually been performed.
     *
     * Not "was it shown", which is what the flag this replaces recorded and which was spent the
     * moment the overlay appeared. A step that is only shown teaches nothing if the user walks away
     * from it, so only the gesture itself retires one. Turning the guide off is what the Remote
     * Config flags are for — see [io.launcher.home.promo.LauncherGuideStep].
     */
    fun isGuideStepDone(step: LauncherGuideStep): Boolean =
        prefs.getBoolean(GUIDE_STEP_DONE_PREFIX + step.name, false)

    fun markGuideStepDone(step: LauncherGuideStep) =
        prefs.edit().putBoolean(GUIDE_STEP_DONE_PREFIX + step.name, true).apply()

    /**
     * When the user last declined Play's update prompt — see
     * [io.launcher.home.update.LauncherUpdateGate].
     *
     * Only ever written for a *normal* update. Play re-raises a forced one on every launch by
     * design, so nothing here could hold one back.
     *
     * Not keyed to a version: Play owns which build is on offer, and it is not asked until after
     * this has already decided whether to ask at all.
     */
    var updateDeclinedAt: Long
        get() = prefs.getLong(UPDATE_DECLINED_AT, 0L)
        set(updateDeclinedAt) = prefs.edit().putLong(UPDATE_DECLINED_AT, updateDeclinedAt).apply()

    var homeFingerprintJson: String
        get() = prefs.getString(HOME_FINGERPRINT_JSON, "") ?: ""
        set(homeFingerprintJson) = prefs.edit().putString(HOME_FINGERPRINT_JSON, homeFingerprintJson).apply()

    var homeProfileJson: String
        get() = prefs.getString(HOME_PROFILE_JSON, "") ?: ""
        set(homeProfileJson) = prefs.edit().putString(HOME_PROFILE_JSON, homeProfileJson).apply()

    /**
     * How the app drawer is reached. [DrawerMode.NONE] is the classic no-drawer home: every app
     * sits on a page, the swipe-up gesture is off and new installs land on the last page. Seeded
     * from the home profile, switchable in settings.
     */
    var drawerMode: DrawerMode
        get() = DrawerMode.entries.firstOrNull { it.name.equals(prefs.getString(DRAWER_MODE, ""), ignoreCase = true) }
            ?: DrawerMode.VERTICAL
        set(drawerMode) = prefs.edit().putString(DRAWER_MODE, drawerMode.name.lowercase()).apply()

    val isDrawerEnabled: Boolean get() = drawerMode != DrawerMode.NONE

    /** The style to come back to when the drawer is switched off and on again in settings. */
    var drawerStyleWhenOn: DrawerMode
        get() = if (prefs.getString(DRAWER_STYLE_WHEN_ON, "").equals(DrawerMode.PAGED.name, ignoreCase = true)) DrawerMode.PAGED else DrawerMode.VERTICAL
        set(style) = prefs.edit().putString(DRAWER_STYLE_WHEN_ON, style.name.lowercase()).apply()

    /** Rows per page when [drawerMode] is [DrawerMode.PAGED]. */
    var drawerRowCount: Int
        get() = prefs.getInt(DRAWER_ROW_COUNT, DRAWER_ROW_COUNT_DEFAULT)
        set(drawerRowCount) = prefs.edit().putInt(DRAWER_ROW_COUNT, drawerRowCount).apply()

    /** Dock slots; the grid caps this at the home column count. */
    var dockColumnCount: Int
        get() = prefs.getInt(DOCK_COLUMN_COUNT_PREF, DOCK_COLUMN_COUNT)
        set(dockColumnCount) = prefs.edit().putInt(DOCK_COLUMN_COUNT_PREF, dockColumnCount).apply()

    var drawerSearchAtBottom: Boolean
        get() = prefs.getBoolean(DRAWER_SEARCH_AT_BOTTOM, false)
        set(drawerSearchAtBottom) = prefs.edit().putBoolean(DRAWER_SEARCH_AT_BOTTOM, drawerSearchAtBottom).apply()

    /** Home and drawer icon size relative to the grid's own; clamped so a bad value cannot hide icons. */
    var iconScale: Float
        get() = prefs.getFloat(ICON_SCALE, 1f).let { if (it.isNaN()) 1f else it.coerceIn(0.5f, 1.5f) }
        set(iconScale) = prefs.edit().putFloat(ICON_SCALE, iconScale.coerceIn(0.5f, 1.5f)).apply()

    /** The previous launcher's icon size in dp (home, dock and drawer draw exactly this); 0 = use [iconScale]. */
    var iconSizeDp: Int
        get() = prefs.getInt(ICON_SIZE_DP, 0).let { if (it in 24..120) it else 0 }
        set(iconSizeDp) = prefs.edit().putInt(ICON_SIZE_DP, iconSizeDp).apply()

    /** Dock icon in dp; 0 = same as [iconSizeDp]. */
    var dockIconSizeDp: Int
        get() = prefs.getInt(DOCK_ICON_SIZE_DP, 0).let { if (it in 24..120) it else 0 }
        set(dockIconSizeDp) = prefs.edit().putInt(DOCK_ICON_SIZE_DP, dockIconSizeDp).apply()

    /** Drawer icon in dp; 0 = same as [iconSizeDp]. */
    var drawerIconSizeDp: Int
        get() = prefs.getInt(DRAWER_ICON_SIZE_DP, 0).let { if (it in 24..120) it else 0 }
        set(drawerIconSizeDp) = prefs.edit().putInt(DRAWER_ICON_SIZE_DP, drawerIconSizeDp).apply()

    /** Tray behind a wrapped legacy (single-bitmap) icon; see [IconShaper.Tray]. */
    var legacyIconTray: IconShaper.Tray
        get() = IconShaper.Tray.parse(prefs.getString(LEGACY_ICON_TRAY, null)) ?: IconShaper.Tray.DOMINANT
        set(tray) = prefs.edit().putString(LEGACY_ICON_TRAY, tray.name.lowercase()).apply()

    /** App-label text size (home + drawer) in sp from the home profile; 0 keeps the layouts' own size. */
    var labelTextSp: Float
        get() = prefs.getFloat(LABEL_TEXT_SP, 0f).let { if (it.isNaN() || it < 8f || it > 20f) 0f else it }
        set(labelTextSp) = prefs.edit().putFloat(LABEL_TEXT_SP, labelTextSp).apply()


    var homeColumnCount: Int
        get() = prefs.getInt(HOME_COLUMN_COUNT, COLUMN_COUNT)
        set(homeColumnCount) = prefs.edit().putInt(HOME_COLUMN_COUNT, homeColumnCount).apply()

    var homeRowCount: Int
        get() = prefs.getInt(HOME_ROW_COUNT, ROW_COUNT)
        set(homeRowCount) = prefs.edit().putInt(HOME_ROW_COUNT, homeRowCount).apply()

    var drawerColumnCount: Int
        get() = prefs.getInt(DRAWER_COLUMN_COUNT, context.resources.getInteger(R.integer.launcher_portrait_column_count))
        set(drawerColumnCount) = prefs.edit().putInt(DRAWER_COLUMN_COUNT, drawerColumnCount).apply()

    var showSearchBar: Boolean
        get() = prefs.getBoolean(SHOW_SEARCH_BAR, true)
        set(showSearchBar) = prefs.edit().putBoolean(SHOW_SEARCH_BAR, showSearchBar).apply()

    var closeAppDrawer: Boolean
        get() = prefs.getBoolean(CLOSE_APP_DRAWER, false)
        set(closeAppDrawer) = prefs.edit().putBoolean(CLOSE_APP_DRAWER, closeAppDrawer).apply()

    var autoShowKeyboardInAppDrawer: Boolean
        get() = prefs.getBoolean(AUTO_SHOW_KEYBOARD_IN_APP_DRAWER, false)
        set(autoShowKeyboardInAppDrawer) = prefs.edit()
            .putBoolean(AUTO_SHOW_KEYBOARD_IN_APP_DRAWER, autoShowKeyboardInAppDrawer).apply()

    var showDrawerAppLabels: Boolean
        get() = prefs.getBoolean(SHOW_DRAWER_APP_LABELS, true)
        set(showDrawerAppLabels) = prefs.edit().putBoolean(SHOW_DRAWER_APP_LABELS, showDrawerAppLabels).apply()

    var showHomeAppLabels: Boolean
        get() = prefs.getBoolean(SHOW_HOME_APP_LABELS, true)
        set(showHomeAppLabels) = prefs.edit().putBoolean(SHOW_HOME_APP_LABELS, showHomeAppLabels).apply()

    /** True once the first home page has been built - see HomeSeeder. */
    var homeSeeded: Boolean
        get() = prefs.getBoolean(HOME_SEEDED, false)
        set(homeSeeded) = prefs.edit().putBoolean(HOME_SEEDED, homeSeeded).apply()

    /** The newest `firstInstallTime` the seeder has already accounted for. */
    var lastInstallSeen: Long
        get() = prefs.getLong(LAST_INSTALL_SEEN, 0L)
        set(lastInstallSeen) = prefs.edit().putLong(LAST_INSTALL_SEEN, lastInstallSeen).apply()

    /** The launcher style last applied - HomeProfileApplier.STYLE_OS / STYLE_DEFAULT; empty before the first. */
    var launcherStyle: String
        get() = prefs.getString(LAUNCHER_STYLE, "") ?: ""
        set(launcherStyle) = prefs.edit().putString(LAUNCHER_STYLE, launcherStyle).apply()

    /** The style changed on a built home: its app icons are cleared and laid out again on the next refresh. */
    var homeRebuildPending: Boolean
        get() = prefs.getBoolean(HOME_REBUILD_PENDING, false)
        set(homeRebuildPending) = prefs.edit().putBoolean(HOME_REBUILD_PENDING, homeRebuildPending).apply()

    /** The search widget the first page's row was built for (launcher_config.search_widget); empty before the first. */
    var searchWidget: String
        get() = prefs.getString(SEARCH_WIDGET, "") ?: ""
        set(searchWidget) = prefs.edit().putString(SEARCH_WIDGET, searchWidget).apply()

    /** The pages hold every app beside the drawer: our default setup, or Home + Drawer read from the replaced launcher. */
    var homeAndDrawer: Boolean
        get() = prefs.getBoolean(HOME_AND_DRAWER, false)
        set(homeAndDrawer) = prefs.edit().putBoolean(HOME_AND_DRAWER, homeAndDrawer).apply()
}
