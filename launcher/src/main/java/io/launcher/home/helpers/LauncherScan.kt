package io.launcher.home.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import io.launcher.home.R
import io.launcher.home.extensions.getDrawableForPackageName
import io.launcher.home.extensions.hiddenIconsDB
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.extensions.launchersDB
import io.launcher.home.models.AppLauncher
import timber.log.Timber
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Builds the launcher list - every installed app with its decoded icon - into [IconCache].
 *
 * Lifted out of LauncherPanel so it can also run *before* the launcher exists: the host calls
 * [prewarm] while the user is still on its onboarding screens, so when the Home role is granted and
 * the launcher opens, the list and icons are already there instead of being decoded in front of the
 * user. Same code, same cache, same signature check - a prewarm that finds nothing changed is a
 * no-op.
 */
object LauncherScan {

    private const val COLOR_SAMPLE_EDGE = 16

    /**
     * How often, at most, a cold scan hands its partial list to the screen. Every hand-off is a
     * sort and a list diff in the drawer and the apps panel, so it is paced by time rather than
     * by icon count: a few hundred apps would otherwise mean dozens of full-list passes on the
     * main thread in the first second.
     */
    private const val PROGRESS_INTERVAL_MS = 150L

    private const val MICRO_G = "com.google.android.gms"

    // Two callers in quick succession (a prewarm still running when the launcher opens, a Home
    // press landing on a fresh process) each start a rebuild before either has filled the cache;
    // the second waits here and then finds the first one's answer by signature instead of decoding
    // every icon again.
    private val lock = Any()

    /** Fire and forget; safe to call from any thread and any number of times. */
    fun prewarm(context: Context) {
        val app = context.applicationContext
        Thread { runCatching { scan(app) } }.apply { priority = Thread.MIN_PRIORITY + 2 }.start()
    }

    /**
     * Every launchable app with its label and no icon - one PackageManager query, no decoding.
     * Enough for everything that only places or names apps (seeding the home, filling its pages),
     * so a first run does not sit on an empty workspace while [scan] renders every icon.
     */
    @SuppressLint("WrongConstant")
    fun quick(context: Context): List<AppLauncher> = with(context) {
        val hidden = hiddenIconsDB.getHiddenIcons().map { it.getIconIdentifier() }
        wanted(this, launchable(this), hidden).map { info -> bare(info, info.loadLabel(packageManager).toString()) }
    }

    /**
     * The full list, icons decoded. [onQuick] is handed the label-only list straight away when the
     * cache is empty, and [onProgress] the list so far (decoded icons, then the rest as placeholder
     * rows) while a cold scan runs, so a caller with a screen up can paint tiles before every icon
     * is done. Both run on the calling thread.
     */
    @SuppressLint("WrongConstant")
    fun scan(
        context: Context,
        onQuick: (List<AppLauncher>) -> Unit = {},
        onProgress: (List<AppLauncher>) -> Unit = {},
    ): ArrayList<AppLauncher> = synchronized(lock) {
        with(context) {
            val hiddenIcons = hiddenIconsDB.getHiddenIcons().map { it.getIconIdentifier() }
            val list = launchable(this)

            // Everything below decodes an icon per installed app. Skipped outright when the
            // installed set is byte-for-byte what it was when the cache was built - which is every
            // resume that is just the user pressing Home. The signature is derived from `list`,
            // which we needed anyway, so the check itself is one already-paid binder call plus a
            // string compare.
            val signature = installedSignature(list, hiddenIcons)
            val cached = IconCache.launchers
            if (cached.isNotEmpty() && signature == IconCache.installedSignature) {
                return@synchronized ArrayList(cached)
            }

            val started = SystemClock.elapsedRealtime()
            val wanted = wanted(this, list, hiddenIcons)

            // Labels are read once and reused by the quick list, the decode order and the final
            // rows. Decoding runs alphabetically - the drawer's own order - so the icons that arrive
            // first are the ones already on screen.
            val labels = HashMap<ResolveInfo, String>(wanted.size * 2)
            wanted.forEach { labels[it] = it.loadLabel(packageManager).toString() }
            val ordered = wanted.sortedBy { labels[it]!!.lowercase() }

            // Nothing to show yet (first run, or the process came back with an empty table): hand
            // the drawer and the apps panel the labels straight away, on their placeholder tiles,
            // so the lists are usable while the icons below are still rendering.
            if (cached.isEmpty()) {
                onQuick(ArrayList(ordered.map { bare(it, labels[it]!!) }))
            }

            // A launcher already cached from the same APK keeps its icon: one app installed or
            // removed decodes one icon, not every installed app's again.
            val sourceDirs = wanted.associate { keyOf(it) to it.activityInfo.applicationInfo.sourceDir.orEmpty() }
            val knownDirs = IconCache.sourceDirs
            val reusable = cached
                .filter { it.drawable != null }
                .associateBy { "${it.packageName}/${it.activityName}" }
                .filterKeys { key -> knownDirs[key] != null && knownDirs[key] == sourceDirs[key] }

            // The on-demand cache must agree: an app whose APK moved (an update) is decoded again,
            // not handed back the icon a tile cached before the update. Same for a tray change.
            val legacyTray = launcherConfig.legacyIconTray
            IconLoader.syncTray(legacyTray)
            IconLoader.invalidate(sourceDirs.keys.filter { key -> knownDirs[key] != null && knownDirs[key] != sourceDirs[key] })

            // One icon per installed app: an XML inflate, a render and a colour average each, all
            // independent, so they are spread over the cores rather than queued on one thread. The
            // render is capped at the size any list here draws it - an adaptive icon's intrinsic
            // size is 108 dp, which at this density is a 400 px bitmap nobody ever sees.
            val iconPx = resources.getDimensionPixelSize(R.dimen.launcher_icon_cache_size)
            val allApps = ArrayList<AppLauncher>()
            val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
            try {
                val decoded = ordered.map { info ->
                    reusable[keyOf(info)]?.let { kept ->
                        // A package mid-removal answers with its bare package name: keep the name it had.
                        val label = labels[info]!!
                        return@map CompletableFuture.completedFuture<AppLauncher?>(
                            kept.copy(title = label.takeIf { it.isNotBlank() && it != kept.packageName } ?: kept.title)
                        )
                    }
                    pool.submit<AppLauncher?> { decode(this, info, labels[info]!!, iconPx, legacyTray) }
                }

                // One app whose icon will not load (a broken resource, an uninstall mid-decode) is
                // skipped, the way the loop it replaced skipped it - not the whole list dropped.
                // While the lists are still on placeholders, what has finished is handed on in
                // order, so the first screenful of real icons does not wait for the slowest one.
                // "Cold" is a cache with no decoded icons in it, not only an empty one: after a
                // process start the list is restored from Room as bare rows (name and tint, no
                // drawable), so the screen is on placeholder tiles even though `cached` is not empty.
                val progressive = cached.none { it.drawable != null }
                val restored = cached.associateBy { "${it.packageName}/${it.activityName}" }
                var lastProgress = SystemClock.elapsedRealtime()
                decoded.forEachIndexed { i, future ->
                    runCatching { future.get() }.getOrNull()?.let(allApps::add)
                    val now = SystemClock.elapsedRealtime()
                    if (progressive && i + 1 < decoded.size && now - lastProgress >= PROGRESS_INTERVAL_MS) {
                        lastProgress = now
                        // The rest keep the tint the previous run stored, so a tile does not change
                        // colour twice on its way to its icon.
                        val rest = ordered.drop(i + 1).map { info -> restored[keyOf(info)] ?: bare(info, labels[info]!!) }
                        onProgress(ArrayList(allApps) + rest)
                    }
                }
            } finally {
                pool.shutdown()
            }

            Timber.d("LauncherScan: ${allApps.size} launchers, ${allApps.size - reusable.size} icons decoded in ${SystemClock.elapsedRealtime() - started} ms")
            launchersDB.insertAll(allApps)
            // Last, so a throw anywhere above leaves the signature stale and the next resume
            // retries rather than caching a half-built list.
            IconCache.installedSignature = signature
            IconCache.sourceDirs = sourceDirs
            allApps
        }
    }

    @SuppressLint("WrongConstant")
    private fun launchable(context: Context): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.PERMISSION_GRANTED)
    }

    private fun wanted(context: Context, list: List<ResolveInfo>, hiddenIcons: List<String>): List<ResolveInfo> {
        val own = context.applicationContext.packageName
        val selfHidden = context.launcherConfig.selfIconHidden
        return list.filter { info ->
            val packageName = info.activityInfo.applicationInfo.packageName
            // Our own app is listed too (unless the fake uninstall hid it); tapping it goes
            // through openHostApp, not its LAUNCHER entry, which would only route back here.
            (packageName != own || !selfHidden) && packageName != MICRO_G &&
                !hiddenIcons.contains("$packageName/${info.activityInfo.name}")
        }
    }

    private fun keyOf(info: ResolveInfo) = "${info.activityInfo.applicationInfo.packageName}/${info.activityInfo.name}"

    private fun bare(info: ResolveInfo, label: String) = AppLauncher(
        id = null,
        title = label,
        packageName = info.activityInfo.applicationInfo.packageName,
        activityName = info.activityInfo.name,
        order = 0,
        thumbnailColor = 0,
        drawable = null
    )

    private fun decode(context: Context, info: ResolveInfo, label: String, iconPx: Int, tray: IconShaper.Tray): AppLauncher? {
        val packageName = info.activityInfo.applicationInfo.packageName
        val activityName = info.activityInfo.name
        // A visible tile already asked for this one (and its APK has not moved, see invalidate):
        // no second decode.
        IconLoader.cached(packageName, activityName)?.let { hit ->
            val bitmap = (hit as? BitmapDrawable)?.bitmap
            return AppLauncher(
                id = null, title = label, packageName = packageName, activityName = activityName, order = 0,
                thumbnailColor = bitmap?.let(::calculateAverageColor) ?: 0, drawable = hit,
            )
        }
        val drawable = info.loadIcon(context.packageManager)?.let { IconShaper.shape(it, tray) }
            ?: context.getDrawableForPackageName(packageName)
            ?: return null
        val bitmap = drawable.toBitmap(
            width = drawable.intrinsicWidth.coerceIn(1, iconPx),
            height = drawable.intrinsicHeight.coerceIn(1, iconPx),
            config = Bitmap.Config.ARGB_8888
        )
        val icon = bitmap.toDrawable(context.resources)
        IconLoader.put(packageName, activityName, icon)
        return AppLauncher(
            id = null,
            title = label,
            packageName = packageName,
            activityName = activityName,
            order = 0,
            thumbnailColor = calculateAverageColor(bitmap),
            drawable = icon
        )
    }

    /**
     * The installed set, in a form cheap enough to compare on every resume.
     *
     * `sourceDir` is what makes this catch app *updates* as well as installs and removals: the
     * installer writes an updated APK to a fresh directory, so the path moves whenever the icon
     * behind it could have. It rides along on the ResolveInfo already fetched, so this costs no
     * extra binder call.
     *
     * The hidden set is folded in because it is the other input to the result - without it, hiding
     * or unhiding an icon would leave the drawer showing the old list until something else
     * invalidated the cache.
     */
    private fun installedSignature(list: List<ResolveInfo>, hiddenIcons: List<String>): String {
        val apps = list
            .map { "${it.activityInfo.packageName}/${it.activityInfo.name}/${it.activityInfo.applicationInfo.sourceDir}" }
            .sorted()
        return apps.joinToString("\n") + "\u0000" + hiddenIcons.sorted().joinToString("\n")
    }

    // taken from https://gist.github.com/maxjvh/a6ab15cbba9c82a5065d
    /**
     * The mean colour of [bitmap], sampled on a grid rather than read whole.
     *
     * The result is a *placeholder* tint - LaunchersLineup shows it behind the icon only until the
     * real drawable is there - so a grid sample is as good as the exact mean. Reading every pixel
     * was not: it allocated an `IntArray(width * height)`, roughly 147 KB for a 192 px adaptive
     * icon, once per installed app, every time the launcher set was rebuilt. On a device with a few
     * hundred apps that is tens of MB of short-lived garbage in a tight loop, and the GC pressure it
     * creates is paid back as longer pauses on the main thread.
     *
     * One reused row buffer and at most [COLOR_SAMPLE_EDGE]² samples instead.
     */
    private fun calculateAverageColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return Color.TRANSPARENT
        }

        val stepX = max(1, width / COLOR_SAMPLE_EDGE)
        val stepY = max(1, height / COLOR_SAMPLE_EDGE)
        val row = IntArray(width)

        var red = 0L
        var green = 0L
        var blue = 0L
        var n = 0
        var y = 0
        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                val color = row[x]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                n++
                x += stepX
            }
            y += stepY
        }

        if (n == 0) {
            return Color.TRANSPARENT
        }

        return Color.rgb((red / n).toInt(), (green / n).toInt(), (blue / n).toInt())
    }
}
