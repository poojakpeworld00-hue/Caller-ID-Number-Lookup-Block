package com.calleridapp.numberlookup.permission.fsi

import android.content.Context
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.util.GuardRail
import org.json.JSONObject

/**
 * Parsed, audience-resolved view of the `permission_engine.fullscreen_permission`
 * Remote Config block that drives the whole Full-Screen-Intent flow.
 *
 * Everything the flow does — whether it runs at all, the min SDK, the country
 * block-list, and the Screen / Dialog behaviour and copy — comes from here. No
 * country, screen, dialog, or on/off logic is hardcoded in the app.
 *
 * Source resolution mirrors [com.calleridapp.numberlookup.permission.AccessSource]:
 *  1. a dedicated `permission_engine` Remote Config parameter, or
 *  2. the `permission_engine` key nested in the app's `GET_DATA_LIST` /
 *     `DEBUG_GET_DATA_LIST` blob.
 *
 * **Audience split:** when the user is Organic (`AdsVault.OnMaketing == false`)
 * and the block carries an `organic` object, its keys override the base — so
 * Marketing and Organic users can get different on/off, caps, and copy from the
 * one JSON block (see [applyOrganic]).
 */
data class FullScreenConfig(
    val enabled: Boolean,
    val minSdk: Int,
    val countryFilterEnabled: Boolean,
    val excludedCountries: List<String>,
    val screen: Screen,
    val dialog: Dialog,
) {
    data class Screen(
        val enabled: Boolean,
        val showOnce: Boolean,
        val delayMs: Long,
        val priority: Int,
        val title: String,
        val desc: String,
        val button: String,
    )

    data class Dialog(
        val enabled: Boolean,
        val delayMs: Long,
        val priority: Int,
        val showAfterDays: Int,
        val maxShowCount: Int,
        val title: String,
        val desc: String,
        val button: String,
    )

    companion object {
        private const val TAG = "FullScreenConfig"
        private const val LOG = "FSI" // shared debug tag with FullScreenAccess (adb logcat -s FSI)
        private const val RC_KEY = "permission_engine"
        private const val BLOCK = "fullscreen_permission"

        // Copy fallbacks — used only when the RC copy field is blank. Matches the
        // approved Screen design; Remote Config overrides them at runtime.
        private const val DEF_TITLE = "Never miss who's calling"
        private const val DEF_DESC =
            "Show verified caller details on your lock screen — the instant a call comes in."
        private const val DEF_BUTTON = "Enable Now"

        /** Fully-off config — returned whenever the block is missing or unreadable. */
        val DISABLED = FullScreenConfig(
            enabled = false,
            minSdk = 34,
            countryFilterEnabled = false,
            excludedCountries = emptyList(),
            screen = Screen(false, true, 500, 1, DEF_TITLE, DEF_DESC, DEF_BUTTON),
            dialog = Dialog(false, 1000, 2, 3, 5, DEF_TITLE, DEF_DESC, DEF_BUTTON),
        )

        /** Reads + parses the current config, applying the Organic override when relevant. */
        fun load(context: Context): FullScreenConfig {
            return try {
                val block = rawBlock()
                if (block == null) {
                    GuardRail.log(LOG, "config: no fullscreen_permission block in Remote Config → DISABLED")
                    return DISABLED
                }
                val base = parse(block)
                val isMarketing = AdsVault.getInstance(context).getBoolean("OnMaketing")
                val resolved = if (!isMarketing && block.has("organic")) {
                    base.applyOrganic(block.getJSONObject("organic"))
                } else base
                GuardRail.log(
                    LOG,
                    "config[${if (isMarketing) "MARKETING" else "ORGANIC"}]: enabled=${resolved.enabled}, minSdk=${resolved.minSdk}, " +
                        "screen=${resolved.screen.enabled}, dialog=${resolved.dialog.enabled}, " +
                        "countryFilter=${resolved.countryFilterEnabled}, excluded=${resolved.excludedCountries}"
                )
                resolved
            } catch (e: Exception) {
                GuardRail.error(TAG, "Failed to parse $BLOCK; feature disabled", e)
                DISABLED
            }
        }

        /** The `fullscreen_permission` object from Remote Config, or null when absent. */
        private fun rawBlock(): JSONObject? {
            val engine = rawEngineJson() ?: return null
            val obj = JSONObject(engine)
            return if (obj.has(BLOCK)) obj.getJSONObject(BLOCK) else null
        }

        private fun rawEngineJson(): String? {
            return try {
                val rc = FirebaseRemoteConfig.getInstance()
                rc.getString(RC_KEY).takeIf { it.isNotBlank() }?.let { return it }
                val blobKey = if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST" else "GET_DATA_LIST"
                val blob = rc.getString(blobKey)
                if (blob.isNotBlank()) {
                    val obj = JSONObject(blob)
                    // Top-level audience split: descend into marketing/organic first,
                    // then fall back to the flat top level (legacy config).
                    val root = audienceRoot(obj)
                    if (root.has(RC_KEY)) return root.getJSONObject(RC_KEY).toString()
                    if (obj.has(RC_KEY)) return obj.getJSONObject(RC_KEY).toString()
                }
                null
            } catch (e: Exception) {
                GuardRail.error(TAG, "Remote Config read failed", e)
                null
            }
        }

        /** marketing/organic sub-object (by OnMaketing), else the flat blob. */
        private fun audienceRoot(obj: JSONObject): JSONObject {
            val isMarketing = AdsVault.getOrNull()?.getBoolean("OnMaketing") ?: false
            val preferred = if (isMarketing) "marketing" else "organic"
            val fallback = if (isMarketing) "organic" else "marketing"
            obj.optJSONObject(preferred)?.let { return it }
            obj.optJSONObject(fallback)?.let { return it }
            return obj
        }

        private fun parse(o: JSONObject): FullScreenConfig {
            val screenObj = o.optJSONObject("screen") ?: JSONObject()
            val dialogObj = o.optJSONObject("dialog") ?: JSONObject()
            val excluded = o.optJSONArray("excluded_countries")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).trim().uppercase().ifBlank { null } }
            } ?: emptyList()
            return FullScreenConfig(
                enabled = o.optBoolean("enabled", false),
                minSdk = o.optInt("android_min_sdk", 34),
                countryFilterEnabled = o.optBoolean("country_filter_enabled", false),
                excludedCountries = excluded,
                screen = Screen(
                    enabled = screenObj.optBoolean("enabled", false),
                    showOnce = screenObj.optBoolean("show_once", true),
                    delayMs = screenObj.optLong("delay", 500),
                    priority = screenObj.optInt("priority", 1),
                    title = screenObj.optString("title").ifBlank { DEF_TITLE },
                    desc = screenObj.optString("desc").ifBlank { DEF_DESC },
                    button = screenObj.optString("button").ifBlank { DEF_BUTTON },
                ),
                dialog = Dialog(
                    enabled = dialogObj.optBoolean("enabled", false),
                    delayMs = dialogObj.optLong("delay", 1000),
                    priority = dialogObj.optInt("priority", 2),
                    showAfterDays = dialogObj.optInt("show_after_days", 3),
                    maxShowCount = dialogObj.optInt("max_show_count", 5),
                    title = dialogObj.optString("title").ifBlank { DEF_TITLE },
                    desc = dialogObj.optString("desc").ifBlank { DEF_DESC },
                    button = dialogObj.optString("button").ifBlank { DEF_BUTTON },
                ),
            )
        }

        /** Overlays the optional `organic` object's keys over this (base) config. */
        private fun FullScreenConfig.applyOrganic(org: JSONObject): FullScreenConfig {
            val s = org.optJSONObject("screen")
            val d = org.optJSONObject("dialog")
            // Organic can carry its own block-list; absent → inherit the base list.
            val orgExcluded = org.optJSONArray("excluded_countries")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).trim().uppercase().ifBlank { null } }
            } ?: excludedCountries
            return copy(
                enabled = org.optBoolean("enabled", enabled),
                minSdk = org.optInt("android_min_sdk", minSdk),
                countryFilterEnabled = org.optBoolean("country_filter_enabled", countryFilterEnabled),
                excludedCountries = orgExcluded,
                screen = screen.copy(
                    enabled = s?.optBoolean("enabled", screen.enabled) ?: screen.enabled,
                    showOnce = s?.optBoolean("show_once", screen.showOnce) ?: screen.showOnce,
                    delayMs = s?.optLong("delay", screen.delayMs) ?: screen.delayMs,
                    priority = s?.optInt("priority", screen.priority) ?: screen.priority,
                    title = s?.optString("title")?.ifBlank { screen.title } ?: screen.title,
                    desc = s?.optString("desc")?.ifBlank { screen.desc } ?: screen.desc,
                    button = s?.optString("button")?.ifBlank { screen.button } ?: screen.button,
                ),
                dialog = dialog.copy(
                    enabled = d?.optBoolean("enabled", dialog.enabled) ?: dialog.enabled,
                    delayMs = d?.optLong("delay", dialog.delayMs) ?: dialog.delayMs,
                    priority = d?.optInt("priority", dialog.priority) ?: dialog.priority,
                    showAfterDays = d?.optInt("show_after_days", dialog.showAfterDays) ?: dialog.showAfterDays,
                    maxShowCount = d?.optInt("max_show_count", dialog.maxShowCount) ?: dialog.maxShowCount,
                    title = d?.optString("title")?.ifBlank { dialog.title } ?: dialog.title,
                    desc = d?.optString("desc")?.ifBlank { dialog.desc } ?: dialog.desc,
                    button = d?.optString("button")?.ifBlank { dialog.button } ?: dialog.button,
                ),
            )
        }
    }
}
