package com.calleridapp.numberlookup.launcher.helpers

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.calleridapp.numberlookup.launcher.activities.MainActivity
import com.calleridapp.numberlookup.launcher.extensions.config

/**
 * One place that owns the first-run route, so the caller-ID screens and the launcher screens
 * agree on where the user is headed.
 *
 *     Splash
 *       └─ Welcome (notifications + phone state)
 *            └─ Set as default launcher?
 *                 ├─ allowed  → Home screen                    (the rest is skipped)
 *                 └─ skipped  → Intro (3 pages) → Language → Home screen
 *
 * "Home screen" is the launcher's [MainActivity], not the caller-ID app's own home — that one is
 * a swipe right away from here.
 */
object LauncherFlow {

    /**
     * Set on the Intro and Language screens when they are being shown as part of the launcher's
     * first-run sequence, so they chain into each other instead of following the caller-ID app's
     * own per-screen Remote Config gating.
     */
    const val EXTRA_LAUNCHER_ONBOARDING = "extra_launcher_onboarding"

    fun isOnboarding(activity: Activity): Boolean =
        activity.intent.getBooleanExtra(EXTRA_LAUNCHER_ONBOARDING, false)

    fun wasOnboardingCompleted(context: Context): Boolean = context.config.wasOnboardingCompleted

    /** Intent for the next onboarding screen, carrying the first-run marker forward. */
    fun onboardingIntent(context: Context, target: Class<*>): Intent =
        Intent(context, target).putExtra(EXTRA_LAUNCHER_ONBOARDING, true)

    /**
     * Records that the first run is over, so the next cold start goes straight to the home
     * screen. Kept separate from [goHome] because a screen can decide onboarding is finished and
     * still have a conditional step (the full-screen-intent prompt) to route through first.
     */
    fun markOnboardingCompleted(context: Context) {
        context.config.wasOnboardingCompleted = true
    }

    /**
     * The end of every first-run path. Marks onboarding done and clears the onboarding screens
     * off the back stack, so Back from the home screen never walks back into them.
     */
    fun goHome(activity: Activity) {
        markOnboardingCompleted(activity)
        activity.startActivity(
            Intent(activity, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        activity.finish()
    }

    /** The same destination as [goHome] for callers that build their own intent chain. */
    fun homeActivity(): Class<*> = MainActivity::class.java
}
