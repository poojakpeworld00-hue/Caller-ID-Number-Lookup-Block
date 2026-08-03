package com.calleridapp.numberlookup.ui.intro

import android.content.Context
import com.calleridapp.admesh.domain.AdsVault
import org.json.JSONObject

/**
 * How often an intro screen (Language / Terms / Onboarding / permission sheet) is
 * shown, from Remote Config.
 *
 * The `intro_display` parameter is split by audience — `marketing` and `organic`
 * (resolved from `AdsVault.OnMaketing`, matching the FSI config) — each
 * holding the per-screen objects:
 * ```json
 * {
 *   "marketing": {
 *     "language":         { "enabled": true,  "prompt_frequency": "once",         "prompt_interval": 0 },
 *     "terms":            { "enabled": false, "prompt_frequency": "once",         "prompt_interval": 0 },
 *     "onboarding":       { "enabled": true,  "prompt_frequency": "app_launches", "prompt_interval": 3 },
 *     "permission_sheet": { "enabled": true,  "prompt_frequency": "always",       "prompt_interval": 0 }
 *   },
 *   "organic": { "language": { ... }, "terms": { ... }, "onboarding": { ... }, "permission_sheet": { ... } }
 * }
 * ```
 * A flat structure (the four screen objects at the top level, no `marketing` /
 * `organic` wrapper) is still accepted for back-compat.
 *
 * `prompt_frequency` — `always` | `once` | `every_days` | `app_launches` | `never`.
 * `prompt_interval`  — the X value (days for `every_days`, launches for `app_launches`);
 *                      ignored for `always` / `once` / `never`.
 *
 * Defaults (when the parameter / audience / screen object is missing) preserve the
 * historical behaviour: Language `once`, Terms off, Onboarding off, permission
 * sheet on+always.
 */
enum class PromptCadence {
    ALWAYS, ONCE, EVERY_DAYS, APP_LAUNCHES, NEVER;

    companion object {
        fun from(raw: String?): PromptCadence = when (raw?.trim()?.lowercase()) {
            "always" -> ALWAYS
            "once" -> ONCE
            "every_days" -> EVERY_DAYS
            "app_launches" -> APP_LAUNCHES
            "never" -> NEVER
            else -> ONCE
        }
    }
}

data class IntroReveal(
    val enabled: Boolean,
    val frequency: PromptCadence,
    val interval: Int
)

object IntroRevealConfig {

    private const val RC_KEY = "intro_display"

    const val LANGUAGE = "language"
    const val TERMS = "terms"
    const val ONBOARDING = "onboarding"
    const val PERMISSION_SHEET = "permission_sheet"

    /** Language defaults to a one-time show (matches the old first-run behaviour). */
    fun language(context: Context): IntroReveal =
        load(context, LANGUAGE, defaultEnabled = true, defaultFrequency = PromptCadence.ONCE)

    /** Terms defaults to OFF (matches the old `hide_terms = true` default). */
    fun terms(context: Context): IntroReveal =
        load(context, TERMS, defaultEnabled = false, defaultFrequency = PromptCadence.ONCE)

    /** Onboarding defaults to OFF (matches the old `hide_intro = true` default). */
    fun onboarding(context: Context): IntroReveal =
        load(context, ONBOARDING, defaultEnabled = false, defaultFrequency = PromptCadence.ONCE)

    /**
     * Permission sheet defaults to ON + every launch (matches the old
     * `Perm_Sheet_Show = true` / `Perm_Sheet_Mode = "always"`). Map the old
     * `interval` mode to `every_days` and `off` to `never` / `enabled:false`.
     */
    fun permissionSheet(context: Context): IntroReveal =
        load(context, PERMISSION_SHEET, defaultEnabled = true, defaultFrequency = PromptCadence.ALWAYS)

    private fun load(
        context: Context,
        key: String,
        defaultEnabled: Boolean,
        defaultFrequency: PromptCadence
    ): IntroReveal {
        val ads = AdsVault.getInstance(context)
        val raw = ads.getString(RC_KEY)
        val obj = if (raw.isNullOrBlank()) null else runCatching {
            val root = JSONObject(raw)
            audienceContainer(root, isMarketing = ads.getBoolean("OnMaketing")).optJSONObject(key)
        }.getOrNull()

        if (obj == null) return IntroReveal(defaultEnabled, defaultFrequency, 0)

        return IntroReveal(
            enabled = obj.optBoolean("enabled", defaultEnabled),
            frequency = PromptCadence.from(
                obj.optString("prompt_frequency", defaultFrequency.name.lowercase())
            ),
            interval = obj.optInt("prompt_interval", 0)
        )
    }

    /**
     * The audience-specific sub-object for the resolved segment, preferring
     * [isMarketing] ? `marketing` : `organic`, then the other audience, then the
     * root itself (a flat, un-split `intro_display` — back-compat).
     */
    private fun audienceContainer(root: JSONObject, isMarketing: Boolean): JSONObject {
        val preferred = if (isMarketing) "marketing" else "organic"
        val fallback = if (isMarketing) "organic" else "marketing"
        root.optJSONObject(preferred)?.let { return it }
        root.optJSONObject(fallback)?.let { return it }
        return root
    }
}
