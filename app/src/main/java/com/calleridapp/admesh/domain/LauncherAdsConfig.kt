package com.calleridapp.admesh.domain

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.facebook.shimmer.ShimmerFrameLayout
import com.calleridapp.admesh.presentation.BannerAdWatcher
import com.calleridapp.admesh.presentation.BannerKind
import com.calleridapp.admesh.presentation.BannerPromo
import com.calleridapp.admesh.presentation.BannerScale
import com.calleridapp.admesh.presentation.DirectLinkOpener
import com.calleridapp.admesh.presentation.DrawerAdRunner
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.admesh.presentation.NativePromoBanner
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal
import com.calleridapp.numberlookup.BuildConfig
import io.launcher.home.extensions.isDefaultLauncher
import org.json.JSONObject

/**
 * The `launcher_ads` Remote Config block — everything the launcher does around ads, the
 * home-screen coach mark and the first-run route. The full schema lives in
 * `docs/launcher-ads-config.md`; the short version:
 *
 * ```json
 * {
 *   "app_click":   { "enabled": true, "ad_type": "inter", "ads_counter": 3, … },
 *   "swipe_right": { … },
 *   "swipe_left":  { … },
 *   "home_hint":   { "enabled": true, "swipeHints": ["right","left","up"], "show_mode": "once" },
 *   "right_panel": { "bottom_native": { "enabled": true, "ad_type": "native", … } },
 *   "default_home_screen": { "enabled": true, "skip_if_default": true, "skip_rest_on_grant": true },
 *   "onboarding":  { "order": [ … ], "welcome": { "inter_enabled": false, "slot": { … } }, … },
 *   "defaultHome":    { … },
 *   "notDefaultHome": { … }
 * }
 * ```
 *
 * Stored as a JSON string in AdsVault and read back with [JSONObject], the same route
 * `intro_display` and `ScreenAds` take — see `ADDashboardActivity.ingestConfig`.
 *
 * ## Variants
 *
 * Everything outside `defaultHome` / `notDefaultHome` is the base. Exactly one variant is
 * merged on top of it per read, picked by whether we hold the HOME role, and the merge is
 * deep: a key present in the variant wins, a key absent inherits, at every level. Arrays are
 * replaced whole. So `"defaultHome": { "swipe_right": { "ads_counter": 1 } }` changes the one
 * counter and leaves the rest of `swipe_right` alone.
 *
 * ## Pacing
 *
 * **`ads_counter`** paces ONE "ad moment" — it is how many events are SKIPPED before that
 * moment lands. `3` skips three and fires on the fourth; `0` fires every time. Each surface
 * counts in its own pref, so the pacing survives the launcher process being killed. The
 * counter never advances while there is nothing at all to show, so flipping ads back on does
 * not immediately fire one.
 *
 * These gates sit ON TOP of the ones inside [InterstitialNormal.showInterAds], which still
 * applies the network check, `IsAdsON`, the `InterAds` master switch and the global
 * `InterCounter`.
 *
 * One limitation worth knowing: `showInterAds` reports no-fill and network failures only to
 * its own close callback, so from out here a failed interstitial is indistinguishable from a
 * shown one. The link therefore substitutes when the interstitial is turned OFF
 * (`ad_type: "link"`), not when it merely fails to fill.
 *
 * A second: the native renderers draw from one preloaded pool and take no per-call ad unit,
 * so a slot's `ad_unit_id` is honoured for `banner` only — a native slot inherits the global
 * `googleNative` (or its screen-wise id) as it always has.
 *
 * In DEBUG every decision is logged under the tag `LauncherAdsConfig`.
 */
object LauncherAdsConfig {

    private const val TAG = "LauncherAdsConfig"
    private const val CONFIG_KEY = "launcher_ads"

    private const val VARIANT_DEFAULT_HOME = "defaultHome"
    private const val VARIANT_NOT_DEFAULT_HOME = "notDefaultHome"

    // ===================== gestures =====================

    /** The three gestures that leave the home screen, with the pref each one counts in. */
    enum class Surface(val block: String, val counterKey: String) {
        APP_CLICK("app_click", "__launcher_ads_app_click_count"),
        SWIPE_RIGHT("swipe_right", "__launcher_ads_swipe_right_count"),
        SWIPE_LEFT("swipe_left", "__launcher_ads_swipe_left_count"),
    }

    /** What fills a gesture's ad moment. */
    enum class GestureAd { INTER, LINK, NONE }

    data class Rule(
        val enabled: Boolean,
        val adType: GestureAd,
        val adsCounter: Int,
        val fallbackLinkEnabled: Boolean,
        val fallbackLink: String,
    ) {
        val canShowInter: Boolean get() = adType == GestureAd.INTER
        val canOpenLink: Boolean
            get() = adType == GestureAd.LINK && fallbackLinkEnabled && fallbackLink.isNotBlank()
    }

    /** Everything off — what a missing or unparseable block resolves to. */
    private val DISABLED = Rule(
        enabled = false,
        adType = GestureAd.NONE,
        adsCounter = 0,
        fallbackLinkEnabled = false,
        fallbackLink = "",
    )

    fun rule(context: Context, surface: Surface): Rule {
        val block = config(context).optJSONObject(surface.block) ?: return DISABLED

        val linkEnabled = block.optBoolean("fallback_link_enabled", false)
        val link = block.optString("fallback_link", "")
        val interEnabled = block.optBoolean("inter_enabled", false)

        // `ad_type` wins when present; otherwise it is derived from the v1 `inter_enabled`
        // switch, so a config written before this key existed behaves exactly as it did.
        val adType = when (block.optString("ad_type").trim().lowercase()) {
            "inter" -> GestureAd.INTER
            "link" -> GestureAd.LINK
            "none" -> GestureAd.NONE
            else -> when {
                interEnabled -> GestureAd.INTER
                linkEnabled && link.isNotBlank() -> GestureAd.LINK
                else -> GestureAd.NONE
            }
        }

        return Rule(
            enabled = block.optBoolean("enabled", false),
            adType = adType,
            adsCounter = block.optInt("ads_counter", 0),
            fallbackLinkEnabled = linkEnabled,
            fallbackLink = link,
        )
    }

    /**
     * Runs [surface]'s monetisation, then [proceed].
     *
     * [proceed] is invoked exactly once on every path — ad shown, ad skipped, ads off, no
     * network, load failure — so the gesture the user made never gets swallowed by an ad
     * that did not turn up.
     */
    fun run(activity: Activity, surface: Surface, proceed: () -> Unit) {
        val rule = rule(activity, surface)

        if (!rule.enabled) {
            log("${surface.block}: disabled")
            return proceed()
        }

        // Nothing is available to fill the slot, so don't spend a counter tick on it —
        // otherwise turning ads back on would fire one immediately.
        if (!rule.canShowInter && !rule.canOpenLink) {
            log("${surface.block}: nothing enabled (ad_type=${rule.adType})")
            return proceed()
        }

        if (!isDue(activity, surface.counterKey, rule.adsCounter, surface.block)) {
            return proceed()
        }

        if (rule.canShowInter) {
            log("${surface.block}: showing interstitial")
            InterstitialNormal().showInterAds(activity) { proceed() }
            return
        }

        log("${surface.block}: ad_type=link, opening fallback link")
        openLink(activity, rule.fallbackLink)
        proceed()
    }

    // ===================== ad slots =====================

    /** What fills an on-screen ad frame. */
    enum class SlotAd { NATIVE, BANNER, NONE }

    data class Slot(
        val enabled: Boolean,
        val adType: SlotAd,
        val nativeType: String,
        val bannerType: String,
        val adUnitId: String,
        /**
         * Where the slot sits in a *list* that carries it, counted in *rows* from the top.
         *
         * Only the app drawer reads this — its grid scrolls the ad along with the apps, so the
         * ad needs a place in the list rather than a fixed edge. `0` is the top of the list and
         * the behaviour that shipped before this field existed; a row past the end of the list
         * clamps to the end, so a deliberately large number reads as "last".
         *
         * Rows, not item indexes: the drawer is a grid, and dropping a full-width ad between
         * two icons of the same row would leave a hole in it.
         */
        val position: Int = 0,
    ) {
        val visible: Boolean get() = enabled && adType != SlotAd.NONE
        /** True when the slot needs the native pool warmed before it can render. */
        val needsNativePreload: Boolean get() = visible && adType == SlotAd.NATIVE
    }

    /** The frame at the bottom of the swipe-in app panel. Defaults to today's mid2 native. */
    fun rightPanelSlot(context: Context): Slot = slot(
        block = config(context).optJSONObject("right_panel")?.optJSONObject("bottom_native"),
        defaultNativeType = "mid2",
        label = "right_panel.bottom_native",
    )

    /**
     * The frame under the panel's suggested-apps grid. Off unless Remote Config asks for it,
     * and a native banner by default — it sits between two sections, so the tall renderers
     * would push the recents and the search results off the screen.
     */
    fun rightPanelSuggestedSlot(context: Context): Slot {
        val block = config(context).optJSONObject("right_panel")?.optJSONObject("suggested_banner")
            ?: return Slot(false, SlotAd.NONE, "native_banner", "adaptive", "")

        return slot(block, defaultNativeType = "native_banner", label = "right_panel.suggested_banner")
    }

    /**
     * The frame at the bottom of the swipe-up app drawer. Off unless Remote Config asks for
     * it — the drawer shipped without an ad, so a missing block keeps it that way.
     */
    fun appDrawerSlot(context: Context): Slot {
        val block = config(context).optJSONObject("app_drawer")?.optJSONObject("bottom_native")
            ?: return Slot(false, SlotAd.NONE, "mid2", "adaptive", "")

        return slot(block, defaultNativeType = "mid2", label = "app_drawer.bottom_native")
    }

    /** What the install / uninstall result screen may do, from `package_result` in Remote Config. */
    data class PackageResultSettings(
        val enabled: Boolean,
        val minGapMs: Long,
        val bodyNative: Boolean,
    )

    /**
     * `package_result: { "enabled": true, "min_gap_ms": 0, "body_native": true }`.
     *
     * `enabled` turns the whole screen off without a release; `min_gap_ms` throttles it so a burst
     * of package events cannot show it repeatedly; `body_native` carries its in-card native ad.
     */
    fun packageResultSettings(context: Context): PackageResultSettings {
        val block = config(context).optJSONObject("package_result")
        return PackageResultSettings(
            enabled = block?.optBoolean("enabled", true) ?: true,
            minGapMs = block?.optLong("min_gap_ms", 0L) ?: 0L,
            bodyNative = block?.optBoolean("body_native", true) ?: true,
        ).also { log("package_result → $it") }
    }

    /**
     * The `recent_ad` block — the full-screen page shown when the user reopens the app from the
     * **Recents / overview** list.
     *
     * [nativeType] is the body format (`big` / `mid` / `mid2`, or `off` for no body ad) and
     * [closeAd] is what fires when the page is closed (`inter` | `directlink` | `none`).
     */
    data class RecentAdSettings(
        val enabled: Boolean,
        val nativeType: String,
        val closeAd: String,
        val minGapMs: Long,
    ) {
        val showsBodyNative: Boolean
            get() = nativeType.isNotBlank() && !nativeType.equals("off", ignoreCase = true)

        val closeShowsInterstitial: Boolean get() = closeAd.equals("inter", ignoreCase = true)

        val closeShowsLink: Boolean
            get() = closeAd.equals("directlink", ignoreCase = true) ||
                closeAd.equals("direct_link", ignoreCase = true) ||
                closeAd.equals("link", ignoreCase = true)
    }

    /**
     * `recent_ad: { "enabled": false, "native": "off", "close_ad": "none", "min_gap_sec": 0 }`.
     *
     * **Defaults off, and every gate fails closed** — an absent, blank or malformed block, or
     * `enabled` not explicitly true, shows nothing. This is deliberate and worth keeping: the page
     * appears on a resume the user did not ask for, which Google Play's Disruptive Ads policy treats
     * as an out-of-context ad, and the reference ships it off for exactly that reason. It also leans
     * on `CLOSE_SYSTEM_DIALOGS`, a broadcast the platform keeps narrowing, so it may simply stop
     * firing on a future release.
     */
    fun recentAdSettings(context: Context): RecentAdSettings =
        eventAdSettings(config(context).optJSONObject("recent_ad"), "recent_ad")

    /**
     * `system_ads.charge` / `system_ads.discharge` — the screen shown when the charger is plugged in
     * or pulled out (QRScanner's `system_ads`). Same shape and the same fail-closed defaults as
     * `recent_ad`, for the same Disruptive Ads reason: it appears on an event the user did not start
     * inside the app. [trigger] is `charge` or `discharge`.
     */
    fun systemAdSettings(context: Context, trigger: String): RecentAdSettings =
        eventAdSettings(config(context).optJSONObject("system_ads")?.optJSONObject(trigger), "system_ads.$trigger")

    private fun eventAdSettings(block: JSONObject?, label: String): RecentAdSettings {
        val off = RecentAdSettings(false, "off", "none", 0L)
        if (block == null) return off
        return runCatching {
            RecentAdSettings(
                enabled = block.optBoolean("enabled", false),
                nativeType = block.optString("native", "off").ifBlank { "off" },
                closeAd = block.optString("close_ad", "none").ifBlank { "none" },
                minGapMs = block.optLong("min_gap_sec", 0L).coerceAtLeast(0L) * 1000L,
            )
        }.getOrDefault(off).also { log("$label → $it") }
    }

    /**
     * The app-wide direct link — the flat `DirectLink` pref, the same one the drawer's `directlink`
     * step falls back to. Used by surfaces that have no link of their own, such as the Recents
     * page's `close_ad: directlink`.
     */
    fun directLink(context: Context): String =
        AdsVault.getInstance(context).getString("DirectLink").orEmpty().trim()

    /** One configurable promo tile in the app drawer: an icon + title that opens [link] when tapped. */
    data class DrawerPromo(
        val position: Int,
        val title: String,
        val icon: String,
        val link: String,
    )

    /**
     * The drawer's promo tiles, from `app_drawer.promo` in Remote Config:
     * `{ "enabled": true, "items": [ { position, title, icon, link, enabled } ] }`. Only enabled
     * items with both an icon and a link survive; each is placed at its own `position` in the list.
     * `landing_url` is accepted as an alias for `link`. An absent or disabled block yields nothing.
     */
    fun drawerPromoItems(context: Context): List<DrawerPromo> {
        val block = config(context).optJSONObject("app_drawer")?.optJSONObject("promo")
            ?: return emptyList()
        if (!block.optBoolean("enabled", true)) return emptyList()
        val items = block.optJSONArray("items") ?: return emptyList()

        val out = ArrayList<DrawerPromo>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (!item.optBoolean("enabled", true)) continue
            // Accept the reference field names as aliases: logo→icon, label→title, landing_url→link.
            val icon = item.optString("icon").ifBlank { item.optString("logo") }.trim()
            val link = item.optString("link").ifBlank { item.optString("landing_url") }.trim()
            val title = item.optString("title").ifBlank { item.optString("label") }.trim()
            if (icon.isBlank() || link.isBlank()) continue
            out += DrawerPromo(
                position = item.optInt("position", 0).coerceAtLeast(0),
                title = title,
                icon = icon,
                link = link,
            )
        }
        log("app_drawer.promo → ${out.size} item(s)")
        return out
    }

    // ---------- App-drawer ad sequence (Remote-Config-driven fallback chain) ----------

    /**
     * The ad formats the app-drawer sequence can play. `idFallbackKey` is the legacy PromoVault key
     * an ad-unit-id (or URL, for [DIRECTLINK]) falls back to when the config leaves `ad_unit_id`
     * blank, so existing global ids keep working without being duplicated into the new block.
     */
    enum class DrawerAdType(val key: String, val idFallbackKey: String) {
        INTER("inter", "googleInter"),
        APPOPEN("appopen", "googleAppopen"),
        DIRECTLINK("directlink", "DirectLink"),
        REWARDED("rewarded", "googleRewarded"),
        FULLSCREEN_NATIVE("fullscreen_native", "googleNative"),

        /** This app's own house ad (`custom_ads`), full screen. Needs `IsCustomADS`; no unit id. */
        CUSTOM("custom", "");

        companion object {
            fun from(raw: String?): DrawerAdType? =
                entries.firstOrNull { it.key == raw?.trim()?.lowercase() }
        }
    }

    /**
     * One resolved ad in the sequence: its format and the ad-unit-id (or URL, for directlink).
     *
     * [urls] and [openType] are direct-link only. [urls] is the configured rotation — the next one
     * is picked when the link actually opens, not here, because this is parsed several times per
     * trigger (preload, then run) and rotating on parse would burn through the list. [openType]
     * overrides the global `DirectLinkType` for this one slot; blank means "use the global".
     */
    data class DrawerAdSpec(
        val type: DrawerAdType,
        val adUnitId: String,
        val urls: List<String> = emptyList(),
        val openType: String = "",
    )

    /**
     * The whole app-drawer ad flow: whether it runs, how often, the resolved chain, and how the
     * chain is walked.
     *
     * [showAll] and [startFromFirst] are the two switches that pick the variant:
     *
     * | `show_all_ads` | `always_start_first` | one app tap does…                                   |
     * |----------------|----------------------|-----------------------------------------------------|
     * | `true`         | (ignored)            | shows **every** ready ad back to back — inter closes, app-open closes, rewarded closes, fullscreen native closes — then opens the app |
     * | `false`        | `true`               | shows the **first** ready ad, always trying inter first and falling through the chain when one is not ready or fails |
     * | `false`        | `false`             | the same, but resuming where the last tap left off, so taps rotate through the formats |
     */
    data class DrawerAdFlow(
        val enabled: Boolean,
        val counter: Int,
        val sequence: List<DrawerAdSpec>,
        val showAll: Boolean = false,
        val startFromFirst: Boolean = false,
        /**
         * A format that is not loaded yet is loaded on the spot and shown, instead of skipped
         * (QRScanner's OnDemandInterstitial). For a placement whose own unit nothing preloads.
         */
        val onDemand: Boolean = false,
    )

    private val DEFAULT_DRAWER_SEQUENCE = listOf(
        DrawerAdType.INTER,
        DrawerAdType.APPOPEN,
        DrawerAdType.DIRECTLINK,
        DrawerAdType.REWARDED,
        DrawerAdType.FULLSCREEN_NATIVE,
    )

    private const val DRAWER_COUNTER_KEY = "__launcher_ads_app_drawer_count"
    private const val DRAWER_POINTER_KEY = "__launcher_ads_app_drawer_seq_ptr"

    /** The click-time flow's own counter and pointer, kept apart from the return-time flow's. */
    private const val CLICK_COUNTER_KEY = "__launcher_ads_app_drawer_click_count"
    private const val CLICK_POINTER_KEY = "__launcher_ads_app_drawer_click_seq_ptr"

    /**
     * Parses `launcher_ads.app_drawer` into the ad flow. Every field is optional and defaults
     * safely: a missing block, or `enabled=false`, yields a flow that shows nothing. `sequence`
     * sets the fallback priority (unknown names dropped, duplicates collapsed, an empty/omitted
     * list → the default order); each per-type entry may be disabled or carry its own `ad_unit_id`,
     * and a type with no id (neither configured nor a legacy fallback key) is dropped from the chain.
     * `bottom_native` and `promo` are read elsewhere and untouched by this.
     *
     * `show_all_ads` and `always_start_first` choose the variant — see [DrawerAdFlow].
     *
     * Two spellings of the same block are accepted, because the live config and our own template
     * disagree: the master switch may be `enabled` or `fallback_enabled`, and the per-type objects
     * may sit under `ads` or directly on `app_drawer`. Reading both is what stops a config that
     * looks correct from silently yielding a flow that shows nothing.
     */
    fun drawerAdFlow(context: Context): DrawerAdFlow {
        val block = config(context).optJSONObject("app_drawer")
            ?: return DrawerAdFlow(false, 0, emptyList())
        val flow = parseFlow(context, block, block, "app_drawer")
        // `applist_app_close: { enabled }` is the readable name for this flow's master switch, and
        // overrides `fallback_enabled` when it is present.
        val gate = block.optJSONObject("applist_app_close") ?: return flow
        return flow.copy(enabled = gate.optBoolean("enabled", flow.enabled)).also {
            log("app_drawer: applist_app_close.enabled=${it.enabled}")
        }
    }

    /**
     * The *click-time* flow, from `app_drawer.click` — the ad that shows when an app icon is tapped,
     * before the app opens, matching the reference's `appLaunch` placement.
     *
     * It is a flow of its own, not a copy of the return-time one: independent `enabled` and
     * `counter`, its own sequence and pointer. That is what lets one cohort get click + return and
     * another get return only, which is exactly how the reference splits it (`appLaunchInterEnabled`
     * true for marketing, false for organic). Absent block → off, so nothing changes without config.
     *
     * Ad-unit ids still come from `app_drawer` (or the legacy flat keys), so the formats do not have
     * to be configured twice.
     */
    fun drawerClickFlow(context: Context): DrawerAdFlow {
        val drawer = config(context).optJSONObject("app_drawer")
            ?: return DrawerAdFlow(false, 0, emptyList())
        // `applist_app_click` is the readable name; `click` is the short one. Either will do.
        val click = drawer.optJSONObject("applist_app_click")
            ?: drawer.optJSONObject("click")
            ?: return DrawerAdFlow(false, 0, emptyList())
        return parseFlow(context, click, drawer, "app_drawer.click")
    }

    /**
     * Shared parser for both drawer flows. [block] carries the switches (`enabled`, `ad_counter`,
     * `sequence`, `show_all_ads`, `always_start_first`); [idsFrom] is where the per-format objects
     * live, which for the click flow is the parent `app_drawer` block.
     */
    private fun parseFlow(
        context: Context,
        block: JSONObject,
        idsFrom: JSONObject,
        label: String,
    ): DrawerAdFlow {
        val enabled = block.optBoolean("enabled", block.optBoolean("fallback_enabled", false))
        val counter = block.optInt("ad_counter", block.optInt("counter", 0)).coerceAtLeast(0)
        val showAll = block.optBoolean("show_all_ads", block.optBoolean("show_all", false))
        val startFromFirst =
            block.optBoolean("always_start_first", block.optBoolean("restart_sequence", false))
        val ads = block.optJSONObject("ads") ?: idsFrom.optJSONObject("ads")

        val listed = block.optJSONArray("sequence")
        val order = if (listed == null) DEFAULT_DRAWER_SEQUENCE else
            (0 until listed.length()).mapNotNull { DrawerAdType.from(listed.optString(it)) }
                .ifEmpty { DEFAULT_DRAWER_SEQUENCE }

        val seen = LinkedHashSet<DrawerAdType>()
        val specs = ArrayList<DrawerAdSpec>(order.size)
        order.forEach { type ->
            if (!seen.add(type)) return@forEach
            val ad = ads?.optJSONObject(type.key)
                ?: block.optJSONObject(type.key)
                ?: idsFrom.optJSONObject(type.key)
            if (ad != null && !ad.optBoolean("enabled", true)) return@forEach
            val id = resolveDrawerAdId(context, type, ad)
            val urls = if (type == DrawerAdType.DIRECTLINK) directLinkUrls(ad) else emptyList()
            if (id.isBlank() && urls.isEmpty()) {
                log("$label: ${type.key} dropped — no ad_unit_id (nor '${type.idFallbackKey}')")
                return@forEach
            }
            val openType = if (type == DrawerAdType.DIRECTLINK) {
                ad?.optString("open_type", "").orEmpty().trim()
            } else {
                ""
            }
            specs += DrawerAdSpec(type, id, urls, openType)
        }

        return DrawerAdFlow(enabled, counter, specs, showAll, startFromFirst).also {
            log(
                "$label ad flow → enabled=$enabled counter=$counter " +
                    "mode=${if (showAll) "show_all" else if (startFromFirst) "first_ready" else "rotate"} " +
                    "seq=${specs.map { s -> s.type.key }}"
            )
        }
    }

    /**
     * A direct link's "id" is a URL, so `url` / `link` / `landing_url` are read as well as
     * `ad_unit_id` — naming it after an ad unit reads wrong in Remote Config and was getting left
     * blank, which dropped `directlink` out of the chain entirely.
     */
    /**
     * The direct link's URL rotation: `urls` (or `links` / `landing_urls`), an array of strings or
     * of `{ url, enabled }` objects. Blank entries and disabled objects are dropped. Empty when the
     * slot is configured with a single `url` instead — the two forms can coexist, and the array
     * wins because a list of one is the same thing.
     */
    private fun directLinkUrls(ad: JSONObject?): List<String> {
        val array = ad?.optJSONArray("urls")
            ?: ad?.optJSONArray("links")
            ?: ad?.optJSONArray("landing_urls")
            ?: return emptyList()

        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
            val url = if (item != null) {
                if (!item.optBoolean("enabled", true)) continue
                item.optString("url").ifBlank { item.optString("link") }
            } else {
                array.optString(i)
            }.trim()
            if (url.isNotEmpty()) out += url
        }
        return out
    }

    private fun resolveDrawerAdId(context: Context, type: DrawerAdType, ad: JSONObject?): String {
        val keys = if (type == DrawerAdType.DIRECTLINK) {
            listOf("ad_unit_id", "url", "link", "landing_url")
        } else {
            listOf("ad_unit_id")
        }
        keys.forEach { key ->
            val configured = ad?.optString(key, "").orEmpty().trim()
            if (configured.isNotBlank()) return configured
        }
        return AdsVault.getInstance(context).getString(type.idFallbackKey).orEmpty().trim()
    }

    /**
     * Preload both drawer flows' formats so one is ready by the time an app is tapped, and another
     * by the time the user comes back. They share [DrawerAdRunner]'s single cache per format, so
     * preloading both costs at most one load each.
     */
    fun preloadDrawerAds(context: Context) {
        if (!AdsVault.getInstance(context).getBoolean("IsAdsON")) return
        listOf(drawerClickFlow(context), drawerAdFlow(context))
            .filter { it.enabled && it.sequence.isNotEmpty() }
            .forEach { DrawerAdRunner.preload(context, it) }
    }

    /**
     * The *click-time* gate: run `app_drawer.click` when an app icon is tapped, and open the app
     * only once it is done. Off unless the block says otherwise, so the default stays "tap opens the
     * app immediately, the ad waits for the way back".
     *
     * [proceed] is invoked exactly once, whatever happens — a tap never gets stuck behind an ad.
     */
    fun runDrawerClickAdFlow(activity: Activity, proceed: () -> Unit) {
        if (!AdsVault.getInstance(activity).getBoolean("IsAdsON")) return proceed()
        val flow = drawerClickFlow(activity)
        if (!flow.enabled || flow.sequence.isEmpty()) {
            log("app_drawer.click: flow off — opening the app straight away")
            return proceed()
        }
        if (!isDue(activity, CLICK_COUNTER_KEY, flow.counter, "app_drawer.click")) return proceed()

        DrawerAdRunner.run(activity, flow, CLICK_POINTER_KEY, proceed)
    }

    /**
     * The app-drawer app-tap gate: every `ad_counter`-th tap, walk the configured sequence, then
     * continue to [proceed]. Any ad that is disabled, has no id, is not loaded or fails to show is
     * skipped for the next in the chain; if none can show (or ads are off, or the flow is disabled),
     * the tap continues immediately. How much of the chain one tap shows, and where it starts, comes
     * from `show_all_ads` / `always_start_first` — see [DrawerAdFlow].
     */
    fun runDrawerAdFlow(activity: Activity, proceed: () -> Unit) {
        if (!AdsVault.getInstance(activity).getBoolean("IsAdsON")) return proceed()
        val flow = drawerAdFlow(activity)
        if (!flow.enabled || flow.sequence.isEmpty()) {
            log("app_drawer: flow off — proceeding")
            return proceed()
        }
        if (!isDue(activity, DRAWER_COUNTER_KEY, flow.counter, "app_drawer")) return proceed()

        DrawerAdRunner.run(activity, flow, DRAWER_POINTER_KEY, proceed)
    }

    /**
     * Renders [slot] into [container]. Hides the frame outright when the slot is off, so a
     * screen that follows the frame's visibility (the hairline dividers do) collapses with it.
     */
    fun showSlot(
        activity: Activity,
        slot: Slot,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (!slot.visible) {
            log("slot hidden (enabled=${slot.enabled}, ad_type=${slot.adType})")
            container.removeAllViews()
            container.visibility = View.GONE
            shimmer?.stopShimmer()
            shimmer?.visibility = View.GONE
            return
        }

        if (slot.adType == SlotAd.BANNER) {
            val (size, collapsible) = when (slot.bannerType.lowercase()) {
                "inline" -> BannerScale.INLINE to false
                "normal" -> BannerScale.NORMAL to false
                "collapsible" -> BannerScale.ADAPTIVE to true
                else -> BannerScale.ADAPTIVE to false
            }
            log("slot: banner type=${slot.bannerType} collapsible=$collapsible")

            // disableInternalFallback=true → BannerPromo reports a single onAdFailed() so the
            // native-banner fallback owns the failure path, same as ScreenPromoConfig.showAd.
            BannerPromo().showBanner(
                activity = activity,
                container = container,
                type = BannerKind.AUTO,
                size = size,
                isCollapsable = collapsible,
                shimmer = shimmer,
                customAdUnitId = slot.adUnitId.takeIf { it.isNotBlank() },
                disableInternalFallback = true,
                observer = object : BannerAdWatcher {
                    override fun onAdFailed() {
                        log("slot: banner failed, falling back to native banner")
                        NativePromoBanner().showNativeBannerNative(activity, container, shimmer)
                    }
                }
            )
            return
        }

        log("slot: native type=${slot.nativeType}")
        when (slot.nativeType.lowercase()) {
            "big" -> NativePromo().showBigNative(activity, container, shimmer)
            "mid" -> NativePromo().showMidNative(activity, container, shimmer)
            "native_banner" -> NativePromoBanner().showNativeBannerNative(activity, container, shimmer)
            else -> NativePromo().showMidNative2(activity, container, shimmer)
        }
    }

    /**
     * [showSlot] with a refresh policy, for the surfaces that are opened over and over — the
     * app drawer and the left panel.
     *
     * Re-showing unconditionally is what made those two look broken. The native pool is a
     * SINGLE static ad: rendering it consumes the ad and starts a refill, so a second open
     * before that refill lands finds nothing, drops through to the fallback branch, and
     * replaces a perfectly good ad with a custom one. The left panel hit it every single
     * time — it asks for two native frames back to back, and the second could never win.
     *
     * So: fill an empty frame exactly as before, replace a filled one only when a fresh
     * native is actually in hand, and otherwise leave what is on screen and warm the next.
     * Banner slots are untouched — they own their own refresh.
     */
    fun refreshSlot(
        activity: Activity,
        slot: Slot,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
    ) {
        // The shimmer placeholder is a child too; only a real ad view counts as "holding an ad".
        val holdsAnAd = (0 until container.childCount).any { container.getChildAt(it) !== shimmer }
        if (!slot.needsNativePreload || !holdsAnAd || NativePromo.hasPreloadedNative()) {
            showSlot(activity, slot, container, shimmer)
            return
        }

        log("slot: no fresh native — keeping the one on screen, warming the next")
        NativePromo().loadNativeADs(activity)
    }

    private fun slot(block: JSONObject?, defaultNativeType: String, label: String): Slot {
        if (block == null) {
            // No entry at all → the frame behaves exactly as it did before the block existed.
            return Slot(true, SlotAd.NATIVE, defaultNativeType, "adaptive", "")
        }

        val adType = when (block.optString("ad_type").trim().lowercase()) {
            "banner" -> SlotAd.BANNER
            "none" -> SlotAd.NONE
            else -> SlotAd.NATIVE
        }

        return Slot(
            enabled = block.optBoolean("enabled", true),
            adType = adType,
            nativeType = block.optString("native_type").ifBlank { defaultNativeType },
            bannerType = block.optString("banner_type").ifBlank { "adaptive" },
            adUnitId = block.optString("ad_unit_id", ""),
            // Negative values would push the ad off the front of the list — floor at the top.
            position = block.optInt("position", 0).coerceAtLeast(0),
        ).also { log("$label → $it") }
    }

    // ===================== home-screen coach mark =====================

    enum class HintDirection(val key: String) {
        RIGHT("right"), LEFT("left"), UP("up"), DOWN("down");

        companion object {
            fun from(raw: String?): HintDirection? =
                entries.firstOrNull { it.key == raw?.trim()?.lowercase() }
        }
    }

    /** How often the hint comes back. */
    enum class HintMode { ONCE, ALWAYS, APP_LAUNCHES }

    data class HomeHint(
        val enabled: Boolean,
        val directions: List<HintDirection>,
        val mode: HintMode,
        val interval: Int,
        val autoHideSec: Int,
    ) {
        val visible: Boolean get() = enabled && directions.isNotEmpty()
    }

    private val DEFAULT_HINT = HomeHint(
        enabled = true,
        directions = listOf(HintDirection.RIGHT),
        mode = HintMode.ONCE,
        interval = 0,
        autoHideSec = 0,
    )

    fun homeHint(context: Context): HomeHint {
        val block = config(context).optJSONObject("home_hint") ?: return DEFAULT_HINT

        val listed = block.optJSONArray("swipeHints")
        val directions = when {
            listed == null -> DEFAULT_HINT.directions
            // A present-but-empty list is an explicit "teach nothing", not a fallback.
            else -> (0 until listed.length()).mapNotNull { HintDirection.from(listed.optString(it)) }
        }

        return HomeHint(
            enabled = block.optBoolean("enabled", true),
            directions = directions,
            mode = when (block.optString("show_mode").trim().lowercase()) {
                "always" -> HintMode.ALWAYS
                "app_launches" -> HintMode.APP_LAUNCHES
                else -> HintMode.ONCE
            },
            interval = block.optInt("interval", 0),
            autoHideSec = block.optInt("auto_hide_sec", 0),
        ).also { log("home_hint → $it") }
    }

    /**
     * Whether an `app_launches` hint is due on this launch. `once` and `always` are decided by
     * the caller (the launcher's own `wasSwipeHintShown` pref latches `once`, so an install
     * that has already seen the hint does not see it again after an update).
     */
    fun isHintDue(context: Context, hint: HomeHint): Boolean =
        isDue(context, HINT_COUNTER_KEY, hint.interval, "home_hint")

    private const val HINT_COUNTER_KEY = "__launcher_ads_home_hint_count"

    // ===================== onboarding =====================

    /**
     * The first-run screens, with the ad behaviour each one had before this block existed:
     * Welcome and the default-home ask carry a mid native and no interstitial, the intro
     * carousel a mid2 native, the language picker a big native, both with an interstitial on
     * the way out.
     */
    enum class OnboardScreen(
        val key: String,
        private val interByDefault: Boolean,
        private val nativeByDefault: String,
    ) {
        WELCOME("welcome", false, "mid"),
        SET_DEFAULT("set_default", false, "mid"),
        INTRO("intro", true, "mid2"),
        LANGUAGE("language", true, "big");

        val counterKey: String get() = "__launcher_ads_onboarding_${key}_count"

        internal fun defaults(): Pair<Boolean, String> = interByDefault to nativeByDefault

        companion object {
            fun from(raw: String?): OnboardScreen? =
                entries.firstOrNull { it.key == raw?.trim()?.lowercase() }
        }
    }

    /** The ad frame on a first-run screen. */
    fun onboardingSlot(context: Context, screen: OnboardScreen): Slot = slot(
        block = onboardingBlock(context, screen)?.optJSONObject("slot"),
        defaultNativeType = screen.defaults().second,
        label = "onboarding.${screen.key}.slot",
    )

    /**
     * The parts of a first-run screen that are not ads.
     *
     * [skipEnabled] hides the Skip affordance when false, which turns the screen into a
     * required step — the CTA (or Back, where the screen offers it) is then the only way on.
     *
     * [backAdvances] applies to the intro carousel: `true` makes Back leave for the next
     * screen instead of walking forward through the remaining pages.
     */
    data class ScreenUi(val skipEnabled: Boolean, val backAdvances: Boolean)

    fun onboardingUi(context: Context, screen: OnboardScreen): ScreenUi {
        val block = onboardingBlock(context, screen)
        return ScreenUi(
            skipEnabled = block?.optBoolean("skip_enabled", true) ?: true,
            backAdvances = block?.optString("back_action")
                ?.trim()?.lowercase() == "next_screen",
        ).also { log("onboarding.${screen.key}.ui → $it") }
    }

    /**
     * Runs [screen]'s exit interstitial, then [proceed] — invoked exactly once on every path,
     * so a first-run screen never dead-ends on a missing ad.
     */
    fun runOnboardingInter(activity: Activity, screen: OnboardScreen, proceed: () -> Unit) {
        val block = onboardingBlock(activity, screen)
        val enabled = block?.optBoolean("inter_enabled", screen.defaults().first)
            ?: screen.defaults().first

        if (!enabled) {
            log("onboarding.${screen.key}: interstitial off")
            return proceed()
        }

        val counter = block?.optInt("ads_counter", 0) ?: 0
        if (!isDue(activity, screen.counterKey, counter, "onboarding.${screen.key}")) {
            return proceed()
        }

        // `onboarding_<screen>_ads_on` / `onboarding_ads_on` false: no ad at all on this Next.
        if (!LauncherPlacementAds.placementEnabled(activity, "onboarding_${screen.key}")) {
            log("onboarding.${screen.key}: ads_on=false")
            return proceed()
        }
        // Link-first replaces the interstitial when configured: `onboarding_<screen>_DirectLink` +
        // `onboarding_<screen>_link_first_then`, each falling back to the shared `onboarding_…`
        // keys, so screens differ only where they are configured to.
        if (LauncherPlacementAds.showLinkFirst(activity, "onboarding_${screen.key}", proceed)) {
            log("onboarding.${screen.key}: link-first")
            return
        }
        log("onboarding.${screen.key}: showing interstitial")
        InterstitialNormal().showInterAds(activity) { proceed() }
    }

    /**
     * The first-run sequence. Unknown names are dropped; an empty or missing `order` falls
     * back to the historical flow. Repeats are kept — listing `set_default` twice asks again
     * at the end — and are resolved by the caller, which skips an entry with nothing to do.
     */
    fun onboardingOrder(context: Context): List<OnboardScreen> {
        val listed = config(context).optJSONObject("onboarding")?.optJSONArray("order")
            ?: return DEFAULT_ORDER

        val order = (0 until listed.length()).mapNotNull { OnboardScreen.from(listed.optString(it)) }
        return order.ifEmpty { DEFAULT_ORDER }.also { log("onboarding.order → ${it.map(OnboardScreen::key)}") }
    }

    /** The default-home ask comes first, so the user's first decision is the one that matters. */
    private val DEFAULT_ORDER = listOf(
        OnboardScreen.SET_DEFAULT,
        OnboardScreen.LANGUAGE,
        OnboardScreen.WELCOME,
        OnboardScreen.INTRO,
    )

    /**
     * `onboarding.<screen>.auto_next`: whether a step the user has already satisfied (the Home
     * role granted) moves on by itself, and after how long. `auto_next` defaults true — a config
     * that fails to fetch must not strand the user on a finished step; `auto_next_delay_ms`
     * defaults 650, clamped to 0–5000.
     */
    fun onboardingAutoNext(context: Context, screen: OnboardScreen): Pair<Boolean, Long> {
        val block = onboardingBlock(context, screen)
        val auto = block?.optBoolean("auto_next", true) ?: true
        val delay = (block?.optLong("auto_next_delay_ms", 650L) ?: 650L).coerceIn(0L, 5_000L)
        return auto to delay
    }

    private fun onboardingBlock(context: Context, screen: OnboardScreen): JSONObject? =
        config(context).optJSONObject("onboarding")?.optJSONObject(screen.key)

    // ===================== the "set as default launcher" step =====================

    data class DefaultHomeStep(
        val enabled: Boolean,
        val skipIfDefault: Boolean,
        val skipRestOnGrant: Boolean,
    )

    /** Defaults reproduce the shipped flow: shown, skipped when already default, grant → home. */
    fun defaultHomeStep(context: Context): DefaultHomeStep {
        val block = config(context).optJSONObject("default_home_screen")
        return DefaultHomeStep(
            enabled = block?.optBoolean("enabled", true) ?: true,
            skipIfDefault = block?.optBoolean("skip_if_default", true) ?: true,
            skipRestOnGrant = block?.optBoolean("skip_rest_on_grant", true) ?: true,
        )
    }

    // ===================== plumbing =====================

    /**
     * The parsed block with the right variant merged in. Cached per (raw config, role) pair —
     * the merge runs on the UI thread from gesture handlers, and the raw string only changes
     * when a fresh config is ingested.
     */
    @Volatile
    private var cache: Pair<String, JSONObject>? = null

    private fun config(context: Context): JSONObject {
        val raw = AdsVault.getInstance(context).getString(CONFIG_KEY, "").orEmpty()
        if (raw.isBlank()) return JSONObject()

        val isDefaultHome = runCatching { context.isDefaultLauncher() }.getOrDefault(false)
        val cacheKey = "${isDefaultHome}|$raw"
        cache?.let { (key, value) -> if (key == cacheKey) return value }

        val base = runCatching { JSONObject(raw) }.getOrNull() ?: return JSONObject()
        val variantKey = if (isDefaultHome) VARIANT_DEFAULT_HOME else VARIANT_NOT_DEFAULT_HOME
        val variant = base.optJSONObject(variantKey)

        val merged = runCatching { JSONObject(base.toString()) }.getOrNull() ?: JSONObject()
        merged.remove(VARIANT_DEFAULT_HOME)
        merged.remove(VARIANT_NOT_DEFAULT_HOME)
        if (variant != null) deepMerge(merged, variant)

        log("config resolved with $variantKey (${variant?.length() ?: 0} override(s))")
        cache = cacheKey to merged
        return merged
    }

    /**
     * Copies [overlay] onto [base] in place: nested objects merge key by key, everything else
     * (scalars, arrays) is replaced whole. Arrays deliberately do not merge — a variant's
     * `swipeHints` is the whole list, not additions to the base list.
     */
    private fun deepMerge(base: JSONObject, overlay: JSONObject) {
        overlay.keys().forEach { key ->
            val overlayValue = overlay.opt(key)
            val baseValue = base.opt(key)
            if (overlayValue is JSONObject && baseValue is JSONObject) {
                deepMerge(baseValue, overlayValue)
            } else {
                base.put(key, overlayValue)
            }
        }
    }

    /**
     * Skip-then-show, counted in prefs so it survives the launcher process being killed —
     * which happens often, and an in-memory counter would reset the pacing every time.
     */
    private fun isDue(context: Context, counterKey: String, target: Int, label: String): Boolean {
        if (target <= 0) return true

        val pref = AdsVault.getInstance(context)
        val seen = pref.getInt(counterKey, 0)

        return if (seen < target) {
            pref.putInt(counterKey, seen + 1)
            log("$label: counter ${seen + 1}/$target, skipping")
            false
        } else {
            pref.putInt(counterKey, 0)
            true
        }
    }

    /** Opens through [DirectLinkOpener], so `DirectLinkType` (webview / custom tab / browser) applies. */
    private fun openLink(activity: Activity, url: String) {
        if (!DirectLinkOpener.open(activity, url)) log("fallback link failed for '$url'")
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
