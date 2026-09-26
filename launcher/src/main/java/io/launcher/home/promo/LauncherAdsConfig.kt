package io.launcher.home.promo

import io.launcher.home.api.LauncherAds
import io.launcher.home.api.LauncherKeys
import io.launcher.home.api.LauncherRegistry
import io.launcher.home.config.LauncherSetup
import org.json.JSONObject

/**
 * The launcher's own placements and behaviour switches.
 *
 * ### What the console decides: `ads_config` / `debug_ads_config`
 *
 * The three native slots - [KEY_PANEL_MIDDLE], [KEY_PANEL_BOTTOM], [KEY_APP_DRAWER] - are
 * `<audience>.screenWiseAds` entries, the same key that owns every other placement in the app.
 * The SDK reads `adType` / `loadType` / `adData` / `adDataList` (so `isActive`, the unit and the
 * subtype are its business - a key missing from `screenWiseAds` leaves the slot GONE, see
 * the host ad wiring (see integration/adshelper)). Two launcher-only fields sit in the same entry and are read
 * here from the raw blob, because the SDK's model drops what it does not know:
 *
 * ```json
 * "app_drawer": { "reload_ad_on_open": false, "ad_row_position": 9, "adType": "native", ... }
 * ```
 *
 * The audience branch is picked with the host bridge, the same LightHouse attribution the SDK
 * uses for its own pick. Defaults when the blob, the branch or the field is missing:
 * `reload_ad_on_open` = true, `ad_row_position` = 0.
 *
 * ### What the console decides: `launcher_config` / `debug_launcher_config`
 *
 * The default-home prompt, the side-panel switches, the guide steps and the gesture interstitials / promo links come from
 * `<audience>` in `launcher_config` via [LauncherRegistry.setup] - see
 * `LauncherSetup` for the shape and the defaults (prompt on, guide on, gestures off, so an
 * unfetched blob leaves the launcher exactly as shipped and never introduces an ad nobody asked
 * for). [interEnabled] false makes `LauncherPromoController` run the gesture's action straight
 * away.
 */
object LauncherAdsConfig {

    /** Gesture keys, as `LauncherPromoController` names them. */
    const val LEFT_SWIPE = "leftSwipe"
    const val RIGHT_SWIPE = "rightSwipe"
    const val DRAWER_OPEN = "drawerOpen"

    /**
     * Leaving for another app — from the drawer, the home grid or the apps panel (QRScanner's
     * `appLaunch`). Read from `gestures.app_launch`, falling back to `gestures.drawer_open`, which is
     * what gated drawer taps before this placement covered all three.
     */
    const val APP_LAUNCH = "appLaunch"

    /**
     * `screenWiseAds` keys for the launcher native slots.
     *
     * Taken from [LauncherAds] rather than restated, so the console key, the key the launcher
     * passes and the key a host matches on cannot drift apart.
     */
    const val KEY_PANEL_MIDDLE = LauncherAds.SLOT_PANEL_MID
    const val KEY_PANEL_BOTTOM = LauncherAds.SLOT_PANEL_BOTTOM
    const val KEY_APP_DRAWER = LauncherAds.SLOT_DRAWER_TOP

    /**
     * Whether the coach mark for [step] may be shown: `guide.enabled` ANDed with the step's own
     * flag, both defaulting on. A disabled step is **skipped, not deferred** - see
     * `LauncherPanel.nextGuideStep`, which walks past it to the next enabled one.
     */
    fun guideStepEnabled(step: LauncherGuideStep): Boolean {
        val setup = LauncherRegistry.setup()
        // A step that teaches a panel the console has switched off would teach a dead gesture.
        val panelOn = when (step) {
            LauncherGuideStep.SWIPE_LEFT_APPS -> setup.panelEnabled(LauncherSetup.PANEL_APPS)
            LauncherGuideStep.SWIPE_RIGHT_HOST -> setup.panelEnabled(LauncherSetup.PANEL_HOST)
            else -> true
        }
        return panelOn && setup.guideStepEnabled(step.configKey)
    }

    /**
     * Whether the side panel behind [gesture] may open at all: `panels.apps` for [LEFT_SWIPE],
     * `panels.messages` for [RIGHT_SWIPE]. Off = the swipe and the tap do nothing.
     */
    fun panelEnabled(gesture: String): Boolean = when (gesture) {
        LEFT_SWIPE -> LauncherRegistry.setup().panelEnabled(LauncherSetup.PANEL_APPS)
        RIGHT_SWIPE -> LauncherRegistry.setup().panelEnabled(LauncherSetup.PANEL_HOST)
        else -> true
    }

    /** The "set as default home" prompt on the launcher: `default_launcher_prompt`. */
    fun defaultLauncherPromptEnabled(): Boolean = LauncherRegistry.setup().defaultLauncherPrompt

    /** Row index of the drawer's native ad; 0 = above the first app row. `screenWiseAds.app_drawer.ad_row_position`. */
    fun drawerAdRowPosition(): Int = entry(KEY_APP_DRAWER).optInt(FIELD_AD_ROW_POSITION, 0).coerceAtLeast(0)

    /**
     * Whether a slot that already holds an ad is asked again on the next open of its surface -
     * `screenWiseAds.<key>.reload_ad_on_open`. Defaults on, so every open is a fresh impression;
     * see LauncherAds.bindNativeOnOpen.
     */
    fun reloadAdOnOpen(key: String): Boolean = entry(key).optBoolean(FIELD_RELOAD_AD_ON_OPEN, true)

    // The parsed blob, kept for as long as the raw string is the one it came from. ads_config is
    // tens of KB and these lookups run on the main thread on every drawer / panel open - parsing
    // it each time was 180 ms of the launcher's first seconds on a debug build.
    private var parsedRaw: String? = null
    private var parsedRoot: JSONObject? = null

    /** The `screenWiseAds` entry for [key] in the current audience's branch of the live ads blob, or an empty object. */
    private fun entry(key: String): JSONObject {
        val raw = LauncherRegistry.bridge.configString(LauncherKeys.ADS_CONFIG, "")
        val root = synchronized(this) {
            if (raw != parsedRaw) {
                parsedRaw = raw
                parsedRoot = runCatching { JSONObject(raw) }.getOrNull()
            }
            parsedRoot
        } ?: return JSONObject()
        return screenWiseAd(root, LauncherRegistry.bridge.isOrganicAudience(), key)
    }

    /**
     * Pure lookup: `<organic|marketing>.screenWiseAds.<key>` out of a raw `ads_config` blob. A blob
     * with no audience branches is read flat. Never throws; anything missing or malformed is an
     * empty object, so every field falls back to its default.
     */
    internal fun screenWiseAd(raw: String, organic: Boolean, key: String): JSONObject =
        runCatching { JSONObject(raw) }.getOrNull()?.let { screenWiseAd(it, organic, key) } ?: JSONObject()

    private fun screenWiseAd(root: JSONObject, organic: Boolean, key: String): JSONObject = runCatching {
        val branch = root.optJSONObject(if (organic) "organic" else "marketing") ?: root
        branch.optJSONObject(FIELD_SCREEN_WISE_ADS)?.optJSONObject(key)
    }.getOrNull() ?: JSONObject()

    private const val FIELD_SCREEN_WISE_ADS = "screenWiseAds"
    private const val FIELD_AD_ROW_POSITION = "ad_row_position"
    private const val FIELD_RELOAD_AD_ON_OPEN = "reload_ad_on_open"

    fun interEnabled(gesture: String): Boolean = gestureSetup(gesture).interEnabled

    fun interCounter(gesture: String): Int = gestureSetup(gesture).interCounter

    /** The promo link for [gesture], or "" when `url_enabled` is off - then the interstitial runs instead. */
    fun promoUrl(gesture: String): String = gestureSetup(gesture).let { if (it.urlEnabled) it.url else "" }

    /** [LEFT_SWIPE] etc. are the code's names; the blob spells them `left_swipe` etc. */
    private fun gestureSetup(gesture: String): LauncherSetup.GestureSetup {
        val setup = LauncherRegistry.setup()
        return when (gesture) {
            LEFT_SWIPE -> setup.gesture("left_swipe")
            RIGHT_SWIPE -> setup.gesture("right_swipe")
            DRAWER_OPEN -> setup.gesture("drawer_open")
            APP_LAUNCH -> setup.gestures["app_launch"] ?: setup.gesture("drawer_open")
            else -> setup.gesture(gesture)
        }
    }
}
