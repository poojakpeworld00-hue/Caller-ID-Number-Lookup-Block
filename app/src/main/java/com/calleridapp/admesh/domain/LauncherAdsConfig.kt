package com.calleridapp.admesh.domain

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal
import com.calleridapp.numberlookup.BuildConfig
import org.json.JSONObject

/**
 * The `launcher_ads` Remote Config block — monetisation on the two launcher gestures that
 * leave the home screen: tapping an app in the swipe-left list, and flinging right into the
 * caller-ID app.
 *
 * ```json
 * "launcher_ads": {
 *   "app_click": {
 *     "enabled": true,
 *     "inter_enabled": true,
 *     "ads_counter": 3,
 *     "fallback_link_enabled": true,
 *     "fallback_link": "https://…"
 *   },
 *   "swipe_right": {
 *     "enabled": true,
 *     "inter_enabled": true,
 *     "ads_counter": 3
 *   }
 * }
 * ```
 *
 * Stored as a JSON string in AdsVault and read back with [JSONObject], the same route
 * `intro_display` and `ScreenAds` take — see `ADDashboardActivity.ingestConfig`.
 *
 * **`enabled`** is the master kill-switch for the gesture: off, and the tap or swipe does its
 * normal thing with nothing ad-related attempted, whatever the other flags say.
 *
 * **`ads_counter`** paces ONE "ad moment" shared by both the interstitial and the fallback
 * link — it is how many events are SKIPPED before that moment lands. `3` skips three taps and
 * fires on the fourth; `0` fires every time. When the moment arrives the interstitial takes it
 * if `inter_enabled`, and the link stands in only when the interstitial is switched off. The
 * counter never advances while there is nothing at all to show, so flipping ads back on does
 * not immediately fire one.
 *
 * Note this gate sits ON TOP of the ones inside [InterstitialNormal.showInterAds], which still
 * applies the network check, `IsAdsON`, the `InterAds` master switch and the global
 * `InterCounter`. With `InterCounter` at 0 — its current value — `ads_counter` is what actually
 * paces these two surfaces; raise the global one and both apply.
 *
 * One limitation worth knowing: `showInterAds` reports no-fill and network failures only to its
 * own close callback, so from out here a failed interstitial is indistinguishable from a shown
 * one. The link therefore substitutes when the interstitial is turned OFF, not when it merely
 * fails to fill.
 *
 * In DEBUG every decision is logged under the tag `LauncherAdsConfig`.
 */
object LauncherAdsConfig {

    private const val TAG = "LauncherAdsConfig"
    private const val CONFIG_KEY = "launcher_ads"

    /** The two gestures this block covers, with the pref each one counts in. */
    enum class Surface(val block: String, val counterKey: String) {
        APP_CLICK("app_click", "__launcher_ads_app_click_count"),
        SWIPE_RIGHT("swipe_right", "__launcher_ads_swipe_right_count"),
    }

    data class Rule(
        val enabled: Boolean,
        val interEnabled: Boolean,
        val adsCounter: Int,
        val fallbackLinkEnabled: Boolean,
        val fallbackLink: String,
    )

    /** Everything off — what a missing or unparseable block resolves to. */
    private val DISABLED = Rule(
        enabled = false,
        interEnabled = false,
        adsCounter = 0,
        fallbackLinkEnabled = false,
        fallbackLink = "",
    )

    fun rule(context: Context, surface: Surface): Rule {
        val raw = AdsVault.getInstance(context).getString(CONFIG_KEY, "").orEmpty()
        if (raw.isBlank()) return DISABLED

        val block = runCatching { JSONObject(raw).optJSONObject(surface.block) }.getOrNull()
            ?: return DISABLED

        return Rule(
            enabled = block.optBoolean("enabled", false),
            interEnabled = block.optBoolean("inter_enabled", false),
            adsCounter = block.optInt("ads_counter", 0),
            fallbackLinkEnabled = block.optBoolean("fallback_link_enabled", false),
            fallbackLink = block.optString("fallback_link", ""),
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

        val canShowInter = rule.interEnabled
        val canOpenLink = rule.fallbackLinkEnabled && rule.fallbackLink.isNotBlank()

        // Nothing is available to fill the slot, so don't spend a counter tick on it —
        // otherwise turning ads back on would fire one immediately.
        if (!canShowInter && !canOpenLink) {
            log("${surface.block}: nothing enabled")
            return proceed()
        }

        if (!isDue(activity, surface, rule.adsCounter)) {
            return proceed()
        }

        if (canShowInter) {
            log("${surface.block}: showing interstitial")
            InterstitialNormal().showInterAds(activity) { proceed() }
            return
        }

        log("${surface.block}: interstitial off, opening fallback link")
        openLink(activity, rule.fallbackLink)
        proceed()
    }

    /**
     * Skip-then-show, counted in prefs so it survives the launcher process being killed —
     * which happens often, and an in-memory counter would reset the pacing every time.
     */
    private fun isDue(context: Context, surface: Surface, target: Int): Boolean {
        val pref = AdsVault.getInstance(context)
        val seen = pref.getInt(surface.counterKey, 0)

        return if (seen < target) {
            pref.putInt(surface.counterKey, seen + 1)
            log("${surface.block}: counter ${seen + 1}/$target, skipping")
            false
        } else {
            pref.putInt(surface.counterKey, 0)
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
