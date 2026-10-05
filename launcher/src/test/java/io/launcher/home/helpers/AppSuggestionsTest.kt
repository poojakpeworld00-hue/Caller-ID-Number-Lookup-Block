package io.launcher.home.helpers

import android.content.pm.ApplicationInfo
import io.launcher.home.models.AppLauncher
import io.launcher.home.models.AppUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSuggestionsTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    private fun app(pkg: String, title: String = pkg) = AppLauncher(null, title, pkg, "$pkg.Main", 0, 0, null)
    private fun used(pkg: String, count: Int, agoMs: Long) = AppUsage(pkg, "$pkg.Main", count, now - agoMs)
    private fun facts(installedAgo: Map<String, Long> = emptyMap(), categories: Map<String, Int> = emptyMap()) =
        { pkg: String -> AppSuggestions.Facts(now - (installedAgo[pkg] ?: 60 * day), categories[pkg] ?: ApplicationInfo.CATEGORY_UNDEFINED) }

    @Test
    fun recentAndFrequentOutrankStaleAndOneOff() {
        val installed = listOf("a", "b", "c", "d").map { app(it) }
        val usage = listOf(
            used("a", 2, 1 * day),      // twice, yesterday
            used("b", 20, 30 * day),    // often, a month ago
            used("c", 1, 7 * day),      // once, a week ago
        )
        val ranked = AppSuggestions.rank(installed, usage, facts(), now, limit = 8).map { it.packageName }
        assertEquals(listOf("a", "b", "c"), ranked.take(3))
    }

    @Test
    fun eachPackageOnceAndUninstalledIgnored() {
        val installed = listOf(app("a"), app("b"))
        val usage = listOf(used("a", 5, 0), used("gone", 50, 0), used("b", 1, day))
        val ranked = AppSuggestions.rank(installed, usage, facts(), now).map { it.packageName }
        assertEquals(listOf("a", "b"), ranked)
    }

    @Test
    fun shortLogIsPaddedWithRecentInstallsThenDeclaredCategories() {
        val installed = listOf("used", "new1", "new2", "social", "news", "game", "old").map { app(it) }
        val f = facts(
            installedAgo = mapOf("new1" to 1 * day, "new2" to 3 * day, "old" to 40 * day),
            categories = mapOf("social" to ApplicationInfo.CATEGORY_SOCIAL, "news" to ApplicationInfo.CATEGORY_NEWS, "game" to ApplicationInfo.CATEGORY_GAME),
        )
        val ranked = AppSuggestions.rank(installed, listOf(used("used", 1, 0)), f, now, limit = 5).map { it.packageName }
        assertEquals(listOf("used", "new1", "new2", "social", "news"), ranked)
    }

    @Test
    fun limitIsRespectedAndLogWinsWhenLongEnough() {
        val installed = (1..12).map { app("p$it") }
        val usage = (1..12).map { used("p$it", it, (12 - it) * day) }
        val ranked = AppSuggestions.rank(installed, usage, facts(), now)
        assertEquals(AppSuggestions.LIMIT, ranked.size)
        assertEquals("p12", ranked.first().packageName)
        assertTrue(ranked.none { it.packageName == "p1" })
    }

    @Test
    fun nothingKnownGivesNothing() {
        val installed = listOf(app("a"), app("b"))
        assertTrue(AppSuggestions.rank(installed, emptyList(), facts(), now).isEmpty())
    }
}
