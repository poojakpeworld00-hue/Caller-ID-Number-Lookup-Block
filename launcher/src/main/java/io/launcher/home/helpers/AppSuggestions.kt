package io.launcher.home.helpers

import android.content.pm.ApplicationInfo
import io.launcher.home.models.AppLauncher
import io.launcher.home.models.AppUsage
import kotlin.math.ln
import kotlin.math.pow

/**
 * The apps the drawer's search offers before anything is typed - what One UI's Finder calls
 * "Suggested apps": the ones the user opens most and most recently, merged into one ranked list.
 *
 * The launcher's own launch log is the only source ([AppUsage]; no Usage access permission).
 * While it is still short - the first hours after install - the list is padded with what can be
 * known without guessing: apps installed recently, then apps whose own manifest declares a
 * social / news / video / audio / maps / productivity category. Pure; unit-tested.
 */
object AppSuggestions {

    const val LIMIT = 8

    /** Fewer than this and the card is not worth showing. */
    const val MIN_TO_SHOW = 4

    /** A launch's recency weight halves every three days. */
    private const val HALF_LIFE_MS = 3 * 24 * 60 * 60 * 1000.0

    /** "Recently installed" reaches this far back. */
    private const val RECENT_INSTALL_MS = 14L * 24 * 60 * 60 * 1000

    /** The categories padded in, in this order, when the log is short. Declared by the apps themselves. */
    val PADDING_CATEGORIES = listOf(
        ApplicationInfo.CATEGORY_SOCIAL,
        ApplicationInfo.CATEGORY_NEWS,
        ApplicationInfo.CATEGORY_VIDEO,
        ApplicationInfo.CATEGORY_AUDIO,
        ApplicationInfo.CATEGORY_MAPS,
        ApplicationInfo.CATEGORY_PRODUCTIVITY,
    )

    /** What is known about an installed app besides its launcher entry. */
    class Facts(val firstInstallTime: Long, val category: Int)

    /**
     * Relevance of one usage row at [now]: recency (1.0 for a launch just now, halving every
     * three days) plus a gently growing count term, so an app opened twice today outranks one
     * opened twenty times last month, and both outrank one opened once a week ago.
     */
    fun score(usage: AppUsage, now: Long): Double {
        val age = (now - usage.lastLaunched).coerceAtLeast(0L)
        val recency = 0.5.pow(age / HALF_LIFE_MS)
        return recency + 0.35 * ln(1.0 + usage.launchCount)
    }

    /**
     * Up to [limit] of [installed] to show, best first. [usage] rows for apps no longer in
     * [installed] are ignored; each package appears once.
     */
    fun rank(
        installed: List<AppLauncher>,
        usage: List<AppUsage>,
        facts: (packageName: String) -> Facts?,
        now: Long,
        limit: Int = LIMIT,
    ): List<AppLauncher> {
        val byPackage = installed.associateBy { it.packageName }
        val picked = LinkedHashMap<String, AppLauncher>()

        usage.filter { it.packageName in byPackage }
            .sortedByDescending { score(it, now) }
            .forEach { picked.putIfAbsent(it.packageName, byPackage.getValue(it.packageName)) }
        if (picked.size >= limit) return picked.values.take(limit)

        // Padding: newest installs first, then the declared categories in their listed order,
        // alphabetical within each.
        installed.asSequence()
            .filter { it.packageName !in picked }
            .mapNotNull { app -> facts(app.packageName)?.let { app to it } }
            .filter { (_, f) -> now - f.firstInstallTime in 0..RECENT_INSTALL_MS }
            .sortedByDescending { (_, f) -> f.firstInstallTime }
            .forEach { (app, _) -> if (picked.size < limit) picked.putIfAbsent(app.packageName, app) }
        if (picked.size >= limit) return picked.values.take(limit)

        for (category in PADDING_CATEGORIES) {
            installed.asSequence()
                .filter { it.packageName !in picked && facts(it.packageName)?.category == category }
                .sortedBy { it.title.lowercase() }
                .forEach { if (picked.size < limit) picked.putIfAbsent(it.packageName, it) }
            if (picked.size >= limit) break
        }
        return picked.values.take(limit)
    }
}
