package com.calleridapp.admesh.domain

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.facebook.shimmer.ShimmerFrameLayout
import com.calleridapp.admesh.presentation.BannerAdWatcher
import com.calleridapp.admesh.presentation.BannerKind
import com.calleridapp.admesh.presentation.BannerPromo
import com.calleridapp.admesh.presentation.BannerScale
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.admesh.presentation.NativePromoBanner
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.launcher.extensions.isDefaultLauncher
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

    private val DEFAULT_ORDER = listOf(
        OnboardScreen.WELCOME,
        OnboardScreen.SET_DEFAULT,
        OnboardScreen.INTRO,
        OnboardScreen.LANGUAGE,
    )

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

    private fun openLink(activity: Activity, url: String) {
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            log("no browser for $url")
        } catch (e: Exception) {
            log("fallback link failed: ${e.message}")
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
