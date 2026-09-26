package io.launcher.home.profile

import android.content.Context
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.helpers.Config
import io.launcher.home.helpers.IconShaper
import io.launcher.home.helpers.MAX_COLUMN_COUNT
import io.launcher.home.helpers.MAX_DOCK_SLOTS
import io.launcher.home.helpers.MAX_ROW_COUNT
import io.launcher.home.helpers.MIN_COLUMN_COUNT
import io.launcher.home.helpers.MIN_ROW_COUNT
import io.launcher.home.api.LauncherRegistry
import org.json.JSONObject
import timber.log.Timber

/**
 * Seeds the launcher's knobs from the [HomeProfile] once, on the very first home-screen build.
 * Runs before the workspace is inflated so the grid is born at the right size; nothing here
 * touches an install whose home screen already exists, and the user remains free to change any
 * of it in the launcher's settings afterwards.
 */
object HomeProfileApplier {

    /** Written even when nothing is applied, so the decision is taken exactly once per install. */
    private const val APPLIED_NONE = "{}"

    /**
     * Re-sizes the icons of a profile whose sizes are a share of the screen width, from the width
     * the display has now: the screen zoom can change after the profile was applied, and the old
     * launcher would have followed it. Runs on every launcher start; writes only on a change, and
     * leaves dp-sized profiles (and installs without a profile) alone.
     */
    fun refreshIconSizes(context: Context) {
        val config = context.launcherConfig
        val json = config.homeProfileJson.takeIf { it.isNotEmpty() && it != APPLIED_NONE } ?: return
        runCatching {
            val saved = HomeProfile.fromJson(JSONObject(json), source = ProfileSource.STANDARD) ?: return
            // The sizing fields follow the rules row the profile came from as it reads today, so a
            // corrected row (One UI's dropped 0.94 inset) reaches installs that applied the old one.
            val row = LauncherFingerprint.stored(context)?.let { fp ->
                val remote = HomeProfileRules.parse(LauncherRegistry.bridge.configString(HomeProfileResolver.RC_RULES_KEY, ""), ProfileSource.RC_RULE)
                (remote?.select(fp) ?: HomeProfileRules.builtIn.select(fp))
            }?.takeIf { it.id == saved.id }
            val stored = row?.let {
                saved.copy(
                    iconWidthFraction = it.iconWidthFraction,
                    iconWidthFractionByDpi = it.iconWidthFractionByDpi,
                    dockIconWidthFraction = it.dockIconWidthFraction,
                    drawerIconWidthFraction = it.drawerIconWidthFraction,
                    iconInset = it.iconInset,
                )
            } ?: saved
            if (stored.iconWidthFraction <= 0f && stored.dockIconWidthFraction <= 0f && stored.drawerIconWidthFraction <= 0f) return
            val metrics = context.resources.displayMetrics
            val widthDp = (minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density).toInt()
            val profile = stored.resolveFractions(widthDp, metrics.densityDpi)
            if (config.iconSizeDp != profile.homeArtDp) config.iconSizeDp = profile.homeArtDp
            if (config.dockIconSizeDp != profile.dockArtDp) config.dockIconSizeDp = profile.dockArtDp
            if (config.drawerIconSizeDp != profile.drawerArtDp) config.drawerIconSizeDp = profile.drawerArtDp
        }.onFailure { Timber.w(it, "HomeProfile: icon refresh failed") }
    }

    /** Which layout the launcher follows - see [applyStyle]. */
    const val STYLE_OS = "os"
    const val STYLE_DEFAULT = "default"
    const val DEFAULT_ICON_DP = 62

    /**
     * Our own layout, the same on every OS, brand and model: 4x6 above a four-slot dock, a
     * vertical drawer with its search bar at the bottom, our label size. One icon size on the pages, the dock and the drawer alike -
     * [DEFAULT_ICON_DP], a touch above the ~59 dp the drawer drew on its own. The drawer is on and
     * the pages carry every app too.
     */
    val DEFAULT_SETUP: HomeProfile = HomeProfile.AOSP.copy(
        id = STYLE_DEFAULT,
        homeCols = 4,
        homeRows = 6,
        dockSize = 4,
        drawerCols = 4,
        iconDp = DEFAULT_ICON_DP,
        dockIconDp = DEFAULT_ICON_DP,
        drawerIconDp = DEFAULT_ICON_DP,
        drawerSearchPosition = DrawerSearchPosition.BOTTOM,
    )

    /** `launcher_config.os_style`: true follows the replaced launcher, false (or unset) is [DEFAULT_SETUP]. */
    fun styleFor(osStyle: Boolean): String = if (osStyle) STYLE_OS else STYLE_DEFAULT

    /**
     * Brings the launcher's knobs in line with the style Remote Config asks for. Runs before the
     * workspace inflates and again on every resume and rc_sync push; a no-op while the style is
     * unchanged. Returns true when an already-built home switched style: its app icons are then
     * rebuilt on the next refresh ([Config.homeRebuildPending]) and the caller recreates the screen
     * so every surface reads the new grid.
     */
    fun applyStyle(context: Context): Boolean {
        val config = context.launcherConfig
        val want = styleFor(LauncherRegistry.setup().osStyle)
        val built = config.wasHomeScreenInit || config.homeProfileJson.isNotEmpty()
        // An install from before the switch existed took the replaced launcher's layout.
        val have = config.launcherStyle.ifEmpty { if (built) STYLE_OS else "" }
        if (have == want) {
            if (config.launcherStyle.isEmpty()) config.launcherStyle = have
            return want == STYLE_DEFAULT && refreshDefault(config)
        }

        if (want == STYLE_OS) {
            val profile = resolveOs(context)
            if (profile == null) {
                config.homeProfileJson = APPLIED_NONE
                config.homeAndDrawer = false
            } else {
                // Home + Drawer: the replaced launcher's own build says it has a drawer (ColorOS
                // config_default_launcher_mode 2), and that launcher keeps every app on its pages as well.
                write(config, profile, homeAndDrawer = profile.drawerMode != DrawerMode.NONE &&
                    profile.sourceOf(HomeProfile.KEY_DRAWER_MODE) == ProfileSource.APK)
            }
        } else {
            write(config, DEFAULT_SETUP, homeAndDrawer = true)
        }
        config.launcherStyle = want
        Timber.i("HomeProfile: launcher style $have -> $want")
        if (!built) return false
        config.homeSeeded = false
        config.homeRebuildPending = true
        return true
    }

    /**
     * Our default setup changed in an app update (a new icon size, say): its knobs are written
     * again. The icons are laid out afresh only when the grid or the dock changed shape. Returns
     * true when anything was written, so the screen redraws.
     */
    private fun refreshDefault(config: Config): Boolean {
        val json = DEFAULT_SETUP.toJson().toString()
        if (config.homeProfileJson == json) return false
        val saved = runCatching { HomeProfile.fromJson(JSONObject(config.homeProfileJson), source = ProfileSource.STANDARD) }.getOrNull()
        write(config, DEFAULT_SETUP, homeAndDrawer = true)
        val reshaped = saved == null || saved.homeCols != DEFAULT_SETUP.homeCols ||
            saved.homeRows != DEFAULT_SETUP.homeRows || saved.dockSize != DEFAULT_SETUP.dockSize
        if (reshaped) {
            config.homeSeeded = false
            config.homeRebuildPending = true
        }
        return true
    }

    /** The replaced launcher's profile: its rules row with its own build's numbers laid over it. */
    private fun resolveOs(context: Context): HomeProfile? {
        return runCatching {
            val fp = LauncherFingerprint.stored(context)?.takeIf { it.homePackage.isNotEmpty() }
                ?: LauncherFingerprint.captureAndStore(context)
            HomeProfileResolver.resolve(context, fp)
        }.onFailure { Timber.w(it, "HomeProfile: resolve failed, keeping defaults") }.getOrNull()
    }

    private fun write(config: Config, profile: HomeProfile, homeAndDrawer: Boolean) {
        // The profile counts rows above the dock; the grid counts the dock as its last row.
        config.homeRowCount = (profile.homeRows + 1).coerceIn(MIN_ROW_COUNT, MAX_ROW_COUNT)
        config.homeColumnCount = profile.homeCols.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        config.drawerColumnCount = profile.drawerCols.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        config.showSearchBar = profile.drawerSearch
        config.showHomeAppLabels = profile.homeLabels
        config.showDrawerAppLabels = profile.drawerLabels
        config.drawerMode = profile.drawerMode
        if (profile.drawerMode != DrawerMode.NONE) config.drawerStyleWhenOn = profile.drawerMode
        if (profile.drawerRows > 0) config.drawerRowCount = profile.drawerRows.coerceIn(MIN_ROW_COUNT, MAX_ROW_COUNT)
        // The dock is its own row and may hold more slots than the grid has columns (a 4x6
        // ColorOS home carries five); the grid narrows dock cells to fit.
        config.dockColumnCount = profile.dockSize.coerceIn(1, MAX_DOCK_SLOTS)
        config.drawerSearchAtBottom = profile.drawerSearchPosition == DrawerSearchPosition.BOTTOM
        config.iconScale = profile.iconScale
        // The size each surface draws: the launcher's item size less its own margin around the art.
        config.iconSizeDp = profile.homeArtDp
        config.dockIconSizeDp = profile.dockArtDp
        config.drawerIconSizeDp = profile.drawerArtDp
        config.labelTextSp = profile.labelSp
        config.legacyIconTray = IconShaper.Tray.parse(profile.legacyIconTray) ?: IconShaper.Tray.DOMINANT
        config.homeAndDrawer = homeAndDrawer
        config.homeProfileJson = profile.toJson().toString()
        Timber.i("HomeProfile: applied ${profile.id} — home ${profile.homeCols}x${profile.homeRows}+dock, drawer ${profile.drawerCols} cols")
    }
}
