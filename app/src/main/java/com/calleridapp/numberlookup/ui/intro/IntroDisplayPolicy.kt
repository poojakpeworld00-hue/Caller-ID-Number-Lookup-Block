package com.calleridapp.numberlookup.ui.intro

import android.content.Context
import com.calleridapp.numberlookup.data.PrefsManager

/**
 * Decides whether an intro screen (Language / Onboarding) shows this launch, from
 * its [IntroDisplay] Remote Config policy plus the persisted ledger in
 * [PrefsManager]. A screen calls [markShown] when it actually appears.
 *
 * Session = one cold start: [PrefsManager.appLaunchCount] is bumped once per launch
 * (in Splash), so `app_launches` counts launches and [markShown] can de-dupe within
 * a single launch even if the gate is evaluated at several points in the funnel.
 */
object IntroDisplayPolicy {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun shouldShow(context: Context, key: String, config: IntroDisplay): Boolean {
        if (!config.enabled) return false
        val prefs = PrefsManager(context)
        return when (config.frequency) {
            PromptFrequency.NEVER -> false
            PromptFrequency.ALWAYS -> true
            PromptFrequency.ONCE -> prefs.introShownCount(key) == 0
            PromptFrequency.EVERY_DAYS -> {
                val last = prefs.introLastShownMs(key)
                if (last == 0L) true
                else (System.currentTimeMillis() - last) / DAY_MS >= config.interval
            }
            // Show on every Nth launch (interval must be positive to be meaningful).
            PromptFrequency.APP_LAUNCHES ->
                config.interval > 0 && prefs.appLaunchCount % config.interval == 0
        }
    }

    /** Record that [key] was shown this launch (bumps count + stamps time, once per launch). */
    fun markShown(context: Context, key: String) {
        val prefs = PrefsManager(context)
        val session = prefs.appLaunchCount
        if (prefs.introLastShownSession(key) == session) return
        prefs.recordIntroShown(key, session)
    }

    fun shouldShowLanguage(context: Context): Boolean =
        shouldShow(context, IntroDisplayConfig.LANGUAGE, IntroDisplayConfig.language(context))

    fun shouldShowTerms(context: Context): Boolean =
        shouldShow(context, IntroDisplayConfig.TERMS, IntroDisplayConfig.terms(context))

    fun shouldShowOnboarding(context: Context): Boolean =
        shouldShow(context, IntroDisplayConfig.ONBOARDING, IntroDisplayConfig.onboarding(context))

    /** Frequency gate only — callers still AND this with their own "is anything pending?" check. */
    fun shouldShowPermissionSheet(context: Context): Boolean =
        shouldShow(context, IntroDisplayConfig.PERMISSION_SHEET, IntroDisplayConfig.permissionSheet(context))
}
