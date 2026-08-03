package com.calleridapp.numberlookup.ui.intro

import android.content.Context
import com.calleridapp.numberlookup.data.VaultRegistry

/**
 * Decides whether an intro screen (Language / Onboarding) shows this launch, from
 * its [IntroReveal] Remote Config policy plus the persisted ledger in
 * [VaultRegistry]. A screen calls [markShown] when it actually appears.
 *
 * Session = one cold start: [VaultRegistry.appLaunchCount] is bumped once per launch
 * (in Splash), so `app_launches` counts launches and [markShown] can de-dupe within
 * a single launch even if the gate is evaluated at several points in the funnel.
 */
object IntroRevealPolicy {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun shouldShow(context: Context, key: String, config: IntroReveal): Boolean {
        if (!config.enabled) return false
        val prefs = VaultRegistry(context)
        return when (config.frequency) {
            PromptCadence.NEVER -> false
            PromptCadence.ALWAYS -> true
            PromptCadence.ONCE -> prefs.introShownCount(key) == 0
            PromptCadence.EVERY_DAYS -> {
                val last = prefs.introLastShownMs(key)
                if (last == 0L) true
                else (System.currentTimeMillis() - last) / DAY_MS >= config.interval
            }
            // Show on every Nth launch (interval must be positive to be meaningful).
            PromptCadence.APP_LAUNCHES ->
                config.interval > 0 && prefs.appLaunchCount % config.interval == 0
        }
    }

    /** Record that [key] was shown this launch (bumps count + stamps time, once per launch). */
    fun markShown(context: Context, key: String) {
        val prefs = VaultRegistry(context)
        val session = prefs.appLaunchCount
        if (prefs.introLastShownSession(key) == session) return
        prefs.recordIntroShown(key, session)
    }

    fun shouldShowLanguage(context: Context): Boolean =
        shouldShow(context, IntroRevealConfig.LANGUAGE, IntroRevealConfig.language(context))

    fun shouldShowTerms(context: Context): Boolean =
        shouldShow(context, IntroRevealConfig.TERMS, IntroRevealConfig.terms(context))

    fun shouldShowOnboarding(context: Context): Boolean =
        shouldShow(context, IntroRevealConfig.ONBOARDING, IntroRevealConfig.onboarding(context))

    /** Frequency gate only — callers still AND this with their own "is anything pending?" check. */
    fun shouldShowPermissionSheet(context: Context): Boolean =
        shouldShow(context, IntroRevealConfig.PERMISSION_SHEET, IntroRevealConfig.permissionSheet(context))
}
