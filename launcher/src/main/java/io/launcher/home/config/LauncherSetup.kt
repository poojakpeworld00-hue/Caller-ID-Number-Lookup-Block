package io.launcher.home.config

import org.json.JSONObject

/**
 * Parsed `launcher_config` (debug/kp twin `debug_launcher_config`): the launcher's behaviour
 * switches, split by audience like every other key.
 *
 * ```json
 * { "organic": {
 *     "os_style": false,
 *     "search_widget": "google",
 *     "default_launcher_prompt": true,
 *     "panels":   { "apps": true, "host": true },
 *     "guide":    { "enabled": true, "swipe_right_contacts": true, "swipe_left_apps": true, "swipe_up_drawer": true },
 *     "gestures": { "left_swipe":  { "inter_enabled": false, "inter_counter": 10, "url_enabled": false, "url": "" },
 *                   "right_swipe": { ... }, "drawer_open": { ... } },
 *     "unlock_ads": { "enabled": true, "start_after_hours": 24, "gap_minutes": 30, "max_per_day": 5,
 *                     "ad_type": "onload", "countries": { "IN": { "enabled": false } } }
 * }, "marketing": { ... } }
 * ```
 *
 * A blob with no audience branches is read flat and applies to both. Ad *placements* are not
 * here: every one, launcher ones included, is an `ads_config.screenWiseAds` entry. The
 * `unlock_ads` object is the device-unlock interstitial's schedule, kept as raw JSON and read by
 * `kit.unlockad.UnlockAdConfig`, which resolves the country layer. [parse] never throws;
 * anything missing or malformed falls back to the defaults below, which are what the app shipped
 * with (our own layout, prompt on, panels on, guide on, gestures off, unlock ads off). In-app update is
 * `app_update_config`, not here.
 */
data class LauncherSetup(
    /**
     * True lays the launcher out like the one it replaced (grid, drawer, icons read per OS, brand
     * and model); false - and unset - is our own setup everywhere: 4x6, drawer on, every app on the
     * pages as well. A change is re-applied to homes already built.
     */
    val osStyle: Boolean = false,
    /**
     * The first page's search bar: [SEARCH_WIDGET_GOOGLE] (and unset, or anything else) or
     * [SEARCH_WIDGET_CHROME]. A change swaps the bar on homes already built.
     */
    val searchWidget: String = SEARCH_WIDGET_GOOGLE,
    val defaultLauncherPrompt: Boolean = true,
    /** Side panels the launcher may open: `apps` (left swipe) and `host` (right swipe). A panel not listed is on. */
    val panels: Map<String, Boolean> = emptyMap(),
    val guideEnabled: Boolean = true,
    /** Per-step switches keyed by `LauncherGuideStep.configKey`; a step not listed is on. */
    val guideSteps: Map<String, Boolean> = emptyMap(),
    /** Keyed by the blob's gesture names: `left_swipe`, `right_swipe`, `drawer_open`. */
    val gestures: Map<String, GestureSetup> = emptyMap(),
    /** The audience's `unlock_ads` object as raw JSON; empty when absent, which reads as off. */
    val unlockAdsJson: String = "",
    /** Sponsored tiles seeded among the drawer's apps: one after every [drawerAppAdEvery] apps. */
    val drawerAppAdEnabled: Boolean = false,
    val drawerAppAdEvery: Int = 8,
    val drawerAppAds: List<SponsoredApp> = emptyList(),
) {
    /** One sponsored drawer tile: an app-icon-shaped cell that opens [landingUrl]. */
    data class SponsoredApp(val logo: String, val landingUrl: String, val label: String)

    fun guideStepEnabled(configKey: String): Boolean = guideEnabled && (guideSteps[configKey] ?: true)

    fun panelEnabled(name: String): Boolean = panels[name] ?: true

    fun gesture(name: String): GestureSetup = gestures[name] ?: GestureSetup()

    data class GestureSetup(
        val interEnabled: Boolean = false,
        val interCounter: Int = 0,
        val urlEnabled: Boolean = false,
        val url: String = "",
    )

    companion object {
        const val KEY_OS_STYLE = "os_style"
        const val KEY_SEARCH_WIDGET = "search_widget"
        const val SEARCH_WIDGET_GOOGLE = "google"
        const val SEARCH_WIDGET_CHROME = "chrome"
        const val KEY_DEFAULT_PROMPT = "default_launcher_prompt"
        const val KEY_PANELS = "panels"
        const val PANEL_APPS = "apps"
        /**
         * The right-hand panel, which holds the host app own UI.
         *
         * The blob spelled this `messages` while the launcher lived in a messaging app, and live
         * configs still carry that spelling, so [parse] accepts either and this one wins when both
         * are present.
         */
        const val PANEL_HOST = "host"

        /** Pre-rename spelling of [PANEL_HOST], still honoured. */
        const val PANEL_HOST_LEGACY = "messages"
        const val KEY_GUIDE = "guide"
        const val KEY_GESTURES = "gestures"
        const val KEY_UNLOCK_ADS = "unlock_ads"
        const val KEY_ENABLED = "enabled"

        val DEFAULT = LauncherSetup()

        fun parse(raw: String, organic: Boolean): LauncherSetup {
            if (raw.isBlank()) return DEFAULT
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return DEFAULT
            val audience = root.optJSONObject(if (organic) "organic" else "marketing") ?: root

            val panelsObj = audience.optJSONObject(KEY_PANELS)
            val panels = mutableMapOf<String, Boolean>()
            panelsObj?.keys()?.forEach { k -> panels[k] = panelsObj.optBoolean(k, true) }
            // A config written before the rename says `messages`; read it as the host panel unless
            // the blob also carries the current spelling, which then wins.
            panels.remove(PANEL_HOST_LEGACY)?.let { panels.putIfAbsent(PANEL_HOST, it) }

            val guide = audience.optJSONObject(KEY_GUIDE)
            val guideSteps = mutableMapOf<String, Boolean>()
            guide?.keys()?.forEach { k -> if (k != KEY_ENABLED) guideSteps[k] = guide.optBoolean(k, true) }

            val gestures = audience.optJSONObject(KEY_GESTURES)
            val gestureMap = mutableMapOf<String, GestureSetup>()
            gestures?.keys()?.forEach { name ->
                val g = gestures.optJSONObject(name) ?: return@forEach
                gestureMap[name] = GestureSetup(
                    interEnabled = g.optBoolean("inter_enabled", false),
                    interCounter = g.optInt("inter_counter", 0).coerceAtLeast(0),
                    urlEnabled = g.optBoolean("url_enabled", false),
                    url = g.optString("url", "").trim(),
                )
            }

            return LauncherSetup(
                osStyle = audience.optBoolean(KEY_OS_STYLE, false),
                searchWidget = audience.optString(KEY_SEARCH_WIDGET).trim().lowercase()
                    .takeIf { it == SEARCH_WIDGET_CHROME } ?: SEARCH_WIDGET_GOOGLE,
                defaultLauncherPrompt = audience.optBoolean(KEY_DEFAULT_PROMPT, true),
                panels = panels,
                guideEnabled = guide?.optBoolean(KEY_ENABLED, true) ?: true,
                guideSteps = guideSteps,
                gestures = gestureMap,
                unlockAdsJson = audience.optJSONObject(KEY_UNLOCK_ADS)?.toString().orEmpty(),
                // QRScanner's key names, so its feed pastes across unchanged.
                drawerAppAdEnabled = audience.optBoolean("drawerAppAdEnabled", false),
                drawerAppAdEvery = audience.optInt("drawerAppAdEvery", 8).coerceAtLeast(1),
                drawerAppAds = sponsoredApps(audience.optJSONArray("drawerAppAds")),
            )
        }

        private fun sponsoredApps(array: org.json.JSONArray?): List<SponsoredApp> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("landing_url").trim()
                if (url.isEmpty()) null
                else SponsoredApp(o.optString("logo").trim(), url, o.optString("label").trim())
            }
        }
    }
}
