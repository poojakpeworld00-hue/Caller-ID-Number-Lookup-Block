package io.launcher.home.promo

import android.app.Activity
import io.launcher.home.api.LauncherRegistry
import io.launcher.home.config.LauncherSetup.SponsoredApp
import io.launcher.home.models.AppLauncher

/**
 * Sponsored tiles in the app drawer (`drawerAppAdEnabled` / `drawerAppAdEvery` / `drawerAppAds` in
 * `launcher_config`, QRScanner's keys).
 *
 * A tile rides in the drawer's list as a marker [AppLauncher], the same trick as
 * `LaunchersLineup.AD_SLOT`, so the scrolling grid, the pages and the diffing need no new row type.
 * Each marker gets its own package name because [AppLauncher.equals] compares only that; the landing
 * URL travels in `activityName` and the logo is looked up here.
 */
object SponsoredTiles {

    /** Not a package any launcher can resolve, so a tile can never collide with a real app. */
    private const val PACKAGE_PREFIX = "io.launcher.home.sponsored."

    private val logos = HashMap<String, String>()

    fun isSponsored(item: AppLauncher) = item.packageName.startsWith(PACKAGE_PREFIX)

    fun logo(item: AppLauncher): String = logos[item.activityName].orEmpty()

    /**
     * [apps] with a tile after every `drawerAppAdEvery` of them. Each feed entry is used once, in
     * order, so the drawer never repeats a tile. The caller skips this while searching: the list is a
     * different, shorter thing every keystroke, and a promo drifting through results reads as one.
     */
    fun interleave(apps: List<AppLauncher>): List<AppLauncher> {
        val setup = LauncherRegistry.setup()
        val feed = setup.drawerAppAds
        if (!setup.drawerAppAdEnabled || feed.isEmpty() || apps.isEmpty()) return apps
        val every = setup.drawerAppAdEvery.coerceAtLeast(1)
        val out = ArrayList<AppLauncher>(apps.size + feed.size)
        var fed = 0
        apps.forEachIndexed { i, app ->
            out.add(app)
            if ((i + 1) % every == 0 && fed < feed.size) out.add(marker(feed[fed], fed++))
        }
        return out
    }

    private fun marker(ad: SponsoredApp, index: Int): AppLauncher {
        logos[ad.landingUrl] = ad.logo
        return AppLauncher(
            id = null,
            title = ad.label,
            packageName = "$PACKAGE_PREFIX$index",
            activityName = ad.landingUrl,
            order = 0,
            thumbnailColor = 0,
            drawable = null,
        )
    }

    fun open(activity: Activity, item: AppLauncher) =
        LauncherRegistry.ads.openSponsored(activity, item.activityName)
}
