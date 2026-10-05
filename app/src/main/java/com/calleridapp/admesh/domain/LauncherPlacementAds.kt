package com.calleridapp.admesh.domain

import android.app.Activity
import android.content.Context
import android.util.Log
import com.calleridapp.admesh.domain.LauncherAdsConfig.DrawerAdFlow
import com.calleridapp.admesh.domain.LauncherAdsConfig.DrawerAdSpec
import com.calleridapp.admesh.domain.LauncherAdsConfig.DrawerAdType
import com.calleridapp.admesh.presentation.DirectLinkOpener
import com.calleridapp.admesh.presentation.DrawerAdRunner
import com.calleridapp.admesh.presentation.AppOpenAdRegistry
import com.calleridapp.numberlookup.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * The launcher's full-screen ad placements, configured with QRScanner's keys so one Remote Config
 * shape serves both apps. Every key resolves `<placement>_<key>` first, then the global `<key>`:
 *
 * | key                                | meaning                                                   |
 * |------------------------------------|-----------------------------------------------------------|
 * | `<p>_ads_on`                       | `false` mutes the placement (default on)                  |
 * | `googleInter` / `googleFullNative` | the unit for that format (`googleFullNative` → `googleNative`) |
 * | `googleRewarded` / `googleAppopen` | units for the link-first follow-ups                       |
 * | `DirectLink`                       | one URL or a JSON array of URLs                           |
 * | `link_first_then`                  | e.g. `"reward,inter,app_open"`: open the DirectLink first, then these once it closes. Needs `IsCustomADS` |
 * | `link_first_show_all`              | `true` shows every follow-up that is ready; default is a waterfall (first that shows ends it) |
 * | `link_open_in`                     | `webview` / `custom_tab` / `browser`                      |
 * | `ad_flow`                          | the chain for the placement: `["inter","directlink","appopen","fullnative","rewarded"]` (or the same as a comma list), any order |
 * | `ad_flow_mode`                     | how a show walks the chain: `one` (first ready format, every time), `all` (every ready format, back to back), `sequence` (one per show, each starting after the one shown last). Without it, `ad_flow_show_all` true = `all`, else `one` |
 * | `counter`                          | shows SKIPPED between ads on this placement (0 = every time). Only for placements whose caller does not count itself: `recent`, `unlock`, `install`, `uninstall`, `charge`, `discharge`; the gestures count in `launcher_config.gestures`, the nav buttons in `system_buttons` |
 * | `inter_fallback`                   | what an interstitial that cannot show falls back to, in order: `rewarded`, `full_native` (`custom` is QRScanner's house ad and is skipped here) |
 *
 * Placements (the launcher's gesture names are mapped the way QRScanner maps them):
 * `leftSwipe` → `leftPanel`, `rightSwipe` → `rightPanel`, `appLaunch` → `drawer`.
 *
 * Everything runs through [DrawerAdRunner], which owns per-unit loading and guarantees its
 * `proceed` runs exactly once — a gesture is never left stuck behind an ad.
 *
 * ```
 * adb logcat -s LauncherPlacementAds DrawerAdRunner
 * ```
 */
object LauncherPlacementAds {

    private const val TAG = "LauncherPlacementAds"

    // `onboarding` is the "Next" of every onboarding screen (ShellPromoConfig.runOnboardInterstitial).
    // `appExit` is the return to the launcher from an app it opened (CallerID's own placement).
    // The last six are the places that were fixed before: they use the engine only when they have
    // their own `ad_flow` / link chain (see hasOwnFlow).
    private val PLACEMENTS = listOf(
        "leftPanel", "rightPanel", "drawer", "onboarding", "recent", "appExit", "unlock",
        "splash", "back", "appOpen", "install", "uninstall", "charge", "discharge", "home",
    )

    private val GLOBAL_KEYS = setOf(
        "link_first_then", "link_first_show_all", "link_open_in", "inter_fallback", "RewardedAds",
        "googleFullNative", "ad_flow", "ad_flow_show_all", "ad_flow_mode",
    )

    /**
     * Flat keys that share a placement's prefix but are not placement keys. They are ingested with
     * their own types (a boolean, an int) elsewhere; storing them again as text would overwrite them.
     */
    private val NOT_PLACEMENT_KEYS = setOf("recent_playstore", "recent_playstore_window_sec", "onboarding_home")

    /** Whether [key] is one [PromoConfigLoader] must ingest for this class (legacy flat form). */
    fun isPlacementKey(key: String): Boolean =
        key !in NOT_PLACEMENT_KEYS && (key in GLOBAL_KEYS || PLACEMENTS.any { key.startsWith("${it}_") })

    // ---------------- Where a placement's settings live ----------------
    //
    // The clean form is one `placements` object in the audience block:
    //   "placements": { "drawer": { "ads_on": true, "DirectLink": [...], "link_first_then": "reward" }, … }
    // The older flat form (`drawer_ads_on`, `drawer_DirectLink`, …) is still read, after it, so a config
    // not yet migrated keeps working exactly as before. Global keys (`googleInter`, `link_first_then`,
    // `inter_fallback`, …) stay flat: the whole ad layer reads them there.

    private var placementsRaw: String? = null
    private var placementsJson: JSONObject? = null

    private fun placementsBlock(vault: AdsVault): JSONObject? {
        val raw = vault.getString(PLACEMENTS_KEY).orEmpty()
        synchronized(this) {
            if (raw != placementsRaw) {
                placementsRaw = raw
                placementsJson = raw.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            }
            return placementsJson
        }
    }

    /**
     * [name]'s own value for [key] as text (arrays / objects as JSON, booleans as "true"/"false"),
     * from `placements.<name>.<key>`, else the flat `<name>_<key>`; null when neither is set.
     */
    private fun own(vault: AdsVault, name: String, key: String): String? {
        val nested = section(placementsBlock(vault), name)
            ?.takeIf { it.has(key) && !it.isNull(key) }?.get(key)?.toString()
        return nested?.takeIf { it.isNotBlank() }
            ?: vault.getString("${name}_$key")?.takeIf { it.isNotBlank() }
    }

    /** `placements.<a>.<b>` for the path `"a.b"`; null when any step is missing. */
    private fun section(root: JSONObject?, path: String): JSONObject? =
        path.split('.').fold(root) { node, step -> node?.optJSONObject(step) }

    /** The object at `placements.<path>` (`"drawer.bottom_native"`), for the callers that read a slot from it. */
    fun section(context: Context, path: String): JSONObject? =
        section(placementsBlock(AdsVault.getInstance(context)), path)

    /** The audience-block key [PromoConfigLoader] stores the `placements` object under. */
    const val PLACEMENTS_KEY = "placements"

    private fun normalize(placement: String?): String? = when (placement) {
        "leftSwipe", "leftPanel" -> "leftPanel"
        "rightSwipe", "rightPanel" -> "rightPanel"
        "appLaunch", "drawerOpen", "drawer" -> "drawer"
        else -> placement
    }

    /**
     * The placement's own name, then its parent. An onboarding screen (`onboarding_welcome`, …) falls
     * back to the shared `onboarding` keys, so one set configures every screen and a screen only
     * needs keys of its own where it differs.
     */
    private fun names(placement: String?): List<String> {
        val name = normalize(placement)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return when {
            name.startsWith("onboarding_") -> listOf(name, "onboarding")
            // One `drawer` block, two sides: `app_click` (leaving for an app) and `app_close` (back
            // from it). Each side's own keys first, then the block's shared `ads_on`. The older
            // flat `drawer` / `appExit` blocks still read, after the new ones.
            name == "drawer" -> listOf("drawer.app_click", "drawer")
            name == "appExit" -> listOf("drawer.app_close", "appExit", "drawer")
            else -> listOf(name)
        }
    }

    private fun resolve(vault: AdsVault, placement: String?, key: String): String {
        names(placement).forEach { name -> own(vault, name, key)?.let { return it } }
        return vault.getString(key).orEmpty()
    }

    private fun resolveFullNative(vault: AdsVault, placement: String?): String =
        resolve(vault, placement, "googleFullNative").ifBlank { vault.getString("googleNative").orEmpty() }

    /** `<placement>_ads_on` (or its parent's) set to `false` mutes it; default on. */
    fun placementEnabled(context: Context, placement: String?): Boolean {
        val vault = AdsVault.getInstance(context)
        val value = names(placement).firstNotNullOfOrNull { name -> own(vault, name, "ads_on")?.trim() }
        return value?.lowercase() != "false"
    }

    private fun directLinks(vault: AdsVault, placement: String?): List<String> {
        val raw = resolve(vault, placement, "DirectLink").trim()
        if (raw.isEmpty()) return emptyList()
        if (!raw.startsWith("[")) return listOf(raw)
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    /** The follow-up names QRScanner uses, as runner formats. Unknown names are dropped. */
    private fun followUp(vault: AdsVault, placement: String?, step: String): DrawerAdSpec? =
        when (step.trim().lowercase()) {
            "reward", "rewarded" ->
                if (vault.getString("RewardedAds")?.trim()?.lowercase() == "false") null
                else DrawerAdSpec(DrawerAdType.REWARDED, resolve(vault, placement, "googleRewarded"))
            "inter", "interstitial" -> DrawerAdSpec(DrawerAdType.INTER, resolve(vault, placement, "googleInter"))
            "app_open", "appopen" -> DrawerAdSpec(DrawerAdType.APPOPEN, resolve(vault, placement, "googleAppopen"))
            "full_native", "fullscreen_native", "fullnative", "full" ->
                DrawerAdSpec(DrawerAdType.FULLSCREEN_NATIVE, resolveFullNative(vault, placement))
            // This app's house ad; needs `IsCustomADS`, like QRScanner's `custom` step.
            "custom", "custom_ads" ->
                if (vault.getBoolean("IsCustomADS")) DrawerAdSpec(DrawerAdType.CUSTOM, "custom") else null
            "directlink", "direct_link", "link", "browser" -> directLinks(vault, placement).takeIf { it.isNotEmpty() }
                ?.let { DrawerAdSpec(DrawerAdType.DIRECTLINK, it.first(), it, resolve(vault, placement, "link_open_in")) }
            else -> null
        }?.takeIf { it.adUnitId.isNotBlank() }

    private class LinkFirst(val links: List<String>, val openIn: DirectLinkOpener.Mode?, val then: DrawerAdFlow)

    /**
     * The link-first chain for [placement] — its links, then the follow-ups — or null when it is
     * off (no order, no links, or no `IsCustomADS`).
     */
    private fun linkFirst(vault: AdsVault, placement: String?): LinkFirst? {
        val order = resolve(vault, placement, "link_first_then")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val links = directLinks(vault, placement)
        if (order.isEmpty() || links.isEmpty() || !vault.getBoolean("IsCustomADS")) return null
        val openIn = DirectLinkOpener.modeOf(resolve(vault, placement, "link_open_in"))
        val showAll = resolve(vault, placement, "link_first_show_all").trim().toBoolean()
        val then = DrawerAdFlow(
            enabled = true,
            counter = 0,
            sequence = order.mapNotNull { followUp(vault, placement, it) },
            showAll = showAll,
            startFromFirst = true,
        )
        return LinkFirst(links, openIn, then)
    }

    private fun fallbacks(vault: AdsVault, placement: String?): List<String> =
        resolve(vault, placement, "inter_fallback").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /**
     * `<placement>_ad_flow` (or the global `ad_flow`): the no-link chain in any order, e.g.
     * `"reward,app_open,inter"`, with `ad_flow_show_all` `true` = every ready one, else a
     * waterfall. Null when not configured, so the placement keeps its default chain.
     */
    private fun adFlow(vault: AdsVault, placement: String?): DrawerAdFlow? {
        val raw = resolve(vault, placement, "ad_flow").trim()
        val steps = if (raw.startsWith("[")) {
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.optString(it).trim() }
            }.getOrDefault(emptyList())
        } else {
            raw.split(',').map { it.trim() }
        }.filter { it.isNotEmpty() }
        if (steps.isEmpty()) return null
        val sequence = steps.mapNotNull { followUp(vault, placement, it) }
        val mode = flowMode(vault, placement)
        return DrawerAdFlow(
            true, 0, sequence,
            showAll = mode == "all",
            // `sequence` resumes after the format shown last; `one` / `all` start at the top.
            startFromFirst = mode != "sequence",
            onDemand = true,
        )
    }

    /** `one` | `all` | `sequence`; the older `ad_flow_show_all` switch still means `all`. */
    private fun flowMode(vault: AdsVault, placement: String?): String {
        val mode = resolve(vault, placement, "ad_flow_mode").trim().lowercase()
        if (mode == "one" || mode == "all" || mode == "sequence") return mode
        return if (resolve(vault, placement, "ad_flow_show_all").trim().toBoolean()) "all" else "one"
    }

    /** The placement's own `counter` (not inherited from a global key), 0 when unset. */
    private fun ownCounter(vault: AdsVault, placement: String?): Int =
        names(placement).firstNotNullOfOrNull { own(vault, it, "counter")?.trim()?.toIntOrNull() }
            ?.coerceAtLeast(0) ?: 0

    /**
     * `counter` shows skipped between ads: false (and counted) until `counter` shows have been
     * skipped, then true and the count restarts. 0 → always true.
     */
    private fun counterDue(vault: AdsVault, placement: String?): Boolean {
        val target = ownCounter(vault, placement)
        if (target <= 0) return true
        val key = "__launcher_placement_count_${normalize(placement)}"
        val seen = vault.getInt(key, 0)
        return if (seen < target) {
            vault.putInt(key, seen + 1)
            log("$placement: counter ${seen + 1}/$target — skipped")
            false
        } else {
            vault.putInt(key, 0)
            true
        }
    }

    /**
     * Whether [placement] has a flow **of its own** — its own `ad_flow`, or its own link chain that
     * can run. Global keys do not count: the places that used to be fixed (splash, back, app open,
     * install / uninstall, charge / discharge) switch to the engine only when configured for it by
     * name, so a global setting never changes them silently.
     */
    fun hasOwnFlow(context: Context, placement: String): Boolean {
        val vault = AdsVault.getInstance(context)
        fun set(key: String) = names(placement).any { own(vault, it, key) != null }
        return set("ad_flow") || ownCounter(vault, placement) > 0 || (set("link_first_then") && linkFirst(vault, placement) != null)
    }

    /** The plain chain: the placement's interstitial, then each `inter_fallback` format in order. */
    private fun interFlow(vault: AdsVault, placement: String?): DrawerAdFlow {
        adFlow(vault, placement)?.let { return it }
        val sequence = buildList {
            resolve(vault, placement, "googleInter").takeIf { it.isNotBlank() }
                ?.let { add(DrawerAdSpec(DrawerAdType.INTER, it)) }
            fallbacks(vault, placement).forEach { name -> followUp(vault, placement, name)?.let { add(it) } }
        }
        return DrawerAdFlow(true, 0, sequence, showAll = false, startFromFirst = true, onDemand = true)
    }

    /** App launch: a full-screen native, falling back to the placement's interstitial. */
    private fun fullNativeFlow(vault: AdsVault, placement: String?): DrawerAdFlow {
        adFlow(vault, placement)?.let { return it }
        val sequence = buildList {
            resolveFullNative(vault, placement).takeIf { it.isNotBlank() }
                ?.let { add(DrawerAdSpec(DrawerAdType.FULLSCREEN_NATIVE, it)) }
            resolve(vault, placement, "googleInter").takeIf { it.isNotBlank() }
                ?.let { add(DrawerAdSpec(DrawerAdType.INTER, it)) }
        }
        return DrawerAdFlow(true, 0, sequence, showAll = false, startFromFirst = true, onDemand = true)
    }

    /**
     * Whether [placement] wants the runner at all. False means the caller keeps its existing path
     * (the app-wide preloaded interstitial): no link-first chain, and no unit of its own.
     */
    fun hasOwnInter(context: Context, placement: String?): Boolean {
        val vault = AdsVault.getInstance(context)
        if (linkFirst(vault, placement) != null) return true
        if (adFlow(vault, placement) != null) return true
        if (ownCounter(vault, placement) > 0) return true
        val unit = resolve(vault, placement, "googleInter")
        return (unit.isNotBlank() && unit != vault.getString("googleInter").orEmpty()) ||
            fallbacks(vault, placement).any { followUp(vault, placement, it) != null }
    }

    /** Loads every format the three placements may need, so the first gesture has something to show. */
    fun preload(context: Context) {
        val vault = AdsVault.getInstance(context)
        if (!vault.getBoolean("IsAdsON")) return
        listOf("leftSwipe", "rightSwipe", "appLaunch", "appExit").filter { placementEnabled(context, it) }.forEach { p ->
            linkFirst(vault, p)?.let { DrawerAdRunner.preload(context, it.then) }
            val fullNative = p == "appLaunch" || p == "appExit"
            DrawerAdRunner.preload(context, if (fullNative) fullNativeFlow(vault, p) else interFlow(vault, p))
        }
    }

    /** The interstitial placement: link-first when configured, else the placement's own interstitial. */
    fun showInterstitial(activity: Activity, placement: String?, proceed: () -> Unit) {
        val vault = AdsVault.getInstance(activity)
        if (!vault.getBoolean("IsAdsON") || !placementEnabled(activity, placement)) return proceed()
        if (!counterDue(vault, placement)) return proceed()

        if (runLinkFirst(activity, vault, placement, proceed)) return

        val flow = interFlow(vault, placement)
        log("$placement: inter chain ${flow.sequence.map { "${it.type.key}(${it.adUnitId})" }}")
        DrawerAdRunner.run(activity, flow, pointerKey(placement), proceed)
    }

    /** The app-launch placement: a full-screen native (QRScanner's choice for leaving to another app). */
    fun showFullNative(activity: Activity, placement: String?, proceed: () -> Unit) {
        val vault = AdsVault.getInstance(activity)
        if (!vault.getBoolean("IsAdsON") || !placementEnabled(activity, placement)) return proceed()
        // A link-first chain configured for the drawer placement takes the app click too.
        if (runLinkFirst(activity, vault, placement, proceed)) return
        val flow = fullNativeFlow(vault, placement)
        log("$placement: full-native chain ${flow.sequence.map { "${it.type.key}(${it.adUnitId})" }}")
        DrawerAdRunner.run(activity, flow, pointerKey(placement), proceed)
    }

    /**
     * Runs [placement]'s link-first chain when one is configured; false when there is none.
     *
     * As in QRScanner, every link opens at once as a stack — last one on top, so the user closes
     * them in the listed order and each close reveals the next. The follow-ups run once the whole
     * stack is gone and the launcher is back in front.
     */
    private fun runLinkFirst(activity: Activity, vault: AdsVault, placement: String?, proceed: () -> Unit): Boolean {
        val chain = linkFirst(vault, placement) ?: return false
        // The follow-ups load while the user reads the links, so they are ready on the way back.
        DrawerAdRunner.preload(activity, chain.then)
        log("$placement: link-first → ${chain.links.size} link(s), then ${chain.then.sequence.map { it.type.key }}")
        val afterLinks = {
            // Consumed by the foreground hook on a real return; cleared here for links that never opened.
            AppOpenAdRegistry.skipNextAppOpenAd = false
            if (chain.then.sequence.isEmpty()) proceed()
            else DrawerAdRunner.run(activity, chain.then, pointerKey(placement), proceed)
        }
        // Suppress the global App Open on the return from the links: the follow-up chosen here is the ad.
        AppOpenAdRegistry.skipNextAppOpenAd = true
        val opened = chain.links.asReversed().count { DirectLinkOpener.open(activity, it, chain.openIn) }
        if (opened > 0) DrawerAdRunner.onReturnTo(activity, afterLinks) else afterLinks()
        return true
    }

    /**
     * One named ad for [placement] — `custom`, `rewarded`, `directlink`, … (the `inter_fallback`
     * vocabulary) — then [proceed], whether or not it could show.
     */
    fun showStep(activity: Activity, placement: String?, step: String, proceed: () -> Unit) {
        val vault = AdsVault.getInstance(activity)
        val spec = followUp(vault, placement, step) ?: return proceed()
        DrawerAdRunner.run(activity, DrawerAdFlow(true, 0, listOf(spec), startFromFirst = true, onDemand = true), pointerKey(placement), proceed)
    }

    /** Whether `inter_fallback` (for [placement], or global) names any step this app can show. */
    fun hasFallback(context: Context, placement: String?): Boolean {
        val vault = AdsVault.getInstance(context)
        return fallbacks(vault, placement).any { followUp(vault, placement, it) != null }
    }

    /**
     * QRScanner's InterFallbackAds: when an interstitial could not show, walk `inter_fallback`
     * (`rewarded`, `full_native`, `custom`, `directlink`, in the configured order) and show the
     * first that can. [proceed] runs exactly once, after it or straight away when nothing shows.
     */
    fun runFallback(activity: Activity, placement: String?, proceed: () -> Unit) {
        val vault = AdsVault.getInstance(activity)
        val sequence = fallbacks(vault, placement).mapNotNull { followUp(vault, placement, it) }
        if (sequence.isEmpty()) return proceed()
        log("$placement: inter_fallback ${sequence.map { it.type.key }}")
        DrawerAdRunner.run(activity, DrawerAdFlow(true, 0, sequence, startFromFirst = true), pointerKey(placement), proceed)
    }

    /** Runs [placement]'s `ad_flow` if it has one (false = none, the caller shows its own ad). */
    fun showAdFlow(activity: Activity, placement: String, proceed: () -> Unit): Boolean {
        val vault = AdsVault.getInstance(activity)
        val flow = adFlow(vault, placement) ?: return false
        log("$placement: ad_flow ${flow.sequence.map { it.type.key }}${if (flow.showAll) " [show_all]" else ""}")
        DrawerAdRunner.run(activity, flow, pointerKey(placement), proceed)
        return true
    }

    /**
     * Runs [placement]'s link-first chain if it has one and ads are on. False means nothing was
     * started and the caller shows its own ad; true means [proceed] will be called by the chain.
     */
    fun showLinkFirst(activity: Activity, placement: String, proceed: () -> Unit): Boolean {
        val vault = AdsVault.getInstance(activity)
        if (!vault.getBoolean("IsAdsON") || !placementEnabled(activity, placement)) return false
        return runLinkFirst(activity, vault, placement, proceed)
    }

    private fun pointerKey(placement: String?) = "__launcher_placement_ptr_${normalize(placement)}"

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
