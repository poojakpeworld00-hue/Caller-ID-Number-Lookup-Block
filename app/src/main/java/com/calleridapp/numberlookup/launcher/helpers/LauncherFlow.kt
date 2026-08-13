package com.calleridapp.numberlookup.launcher.helpers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.admesh.domain.LauncherAdsConfig.OnboardScreen
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.launcher.activities.MainActivity
import com.calleridapp.numberlookup.launcher.activities.OnboardingDefaultLauncherActivity
import com.calleridapp.numberlookup.launcher.activities.OnboardingWelcomeActivity
import com.calleridapp.numberlookup.launcher.extensions.config
import com.calleridapp.numberlookup.launcher.extensions.isDefaultLauncher
import com.calleridapp.numberlookup.ui.language.LocaleActivity
import com.calleridapp.numberlookup.ui.onboarding.IntroActivity

/**
 * One place that owns the first-run route, so the caller-ID screens and the launcher screens
 * agree on where the user is headed.
 *
 * The sequence itself comes from Remote Config — `launcher_ads.onboarding.order` — and
 * defaults to the flow this app shipped with:
 *
 *     Splash
 *       └─ Welcome (notifications + phone state)
 *            └─ Set as default launcher?
 *                 ├─ allowed  → Home screen        (with skip_rest_on_grant, the default)
 *                 └─ skipped  → Intro (3 pages) → Language → Home screen
 *
 * Reorder it, drop a screen, or list `set_default` twice to ask again at the end; see
 * `docs/launcher-ads-config.md`. "Home screen" is the launcher's [MainActivity], not the
 * caller-ID app's own home — that one is a swipe right away from here.
 *
 * How far the sequence has got is kept in `Config.onboardingStep`, an index into the resolved
 * order. A cold start that finds onboarding unfinished restarts it from the top, exactly as
 * the hardcoded flow did.
 */
object LauncherFlow {

    private const val TAG = "LauncherFlow"

    /**
     * Set on the Intro and Language screens when they are being shown as part of the launcher's
     * first-run sequence, so they chain into each other instead of following the caller-ID app's
     * own per-screen Remote Config gating.
     */
    const val EXTRA_LAUNCHER_ONBOARDING = "extra_launcher_onboarding"

    fun isOnboarding(activity: Activity): Boolean =
        activity.intent.getBooleanExtra(EXTRA_LAUNCHER_ONBOARDING, false)

    /**
     * Whether this screen is running as part of the launcher's first run, marker or not.
     *
     * The marker cannot always survive the trip: the full-screen-intent screen rebuilds the
     * intent for whatever follows it from a class name alone, so a step reached through it
     * arrives unmarked and would otherwise mistake itself for the caller-ID app's own copy of
     * that screen and end the run early. Which steps that hits depends purely on the order —
     * with `language` before `intro`, the intro carousel is the one that loses it.
     *
     * An unfinished first run is the honest test: until it completes, the launcher order is the
     * only route to these screens.
     */
    fun isOnboardingActive(activity: Activity): Boolean =
        isOnboarding(activity) || !wasOnboardingCompleted(activity)

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

    // ===================== the RC-ordered sequence =====================

    /**
     * Where the first run begins — the first screen of the resolved order that still has
     * something to do. [homeActivity] when every screen is switched off, so Splash always has
     * somewhere to send the user.
     */
    fun firstScreen(context: Context): Class<*> = resolveFrom(context, 0)

    /**
     * Moves off the screen at the current step and finishes [activity]. Lands on the home
     * screen once the order runs out.
     *
     * [skipRest] is the default-home grant shortcut: it abandons whatever the order still had
     * queued, which is what `skip_rest_on_grant` (on by default) asks for.
     */
    fun advance(activity: Activity, skipRest: Boolean = false) {
        if (skipRest) {
            log("skipping the rest of the order")
            return goHome(activity)
        }

        val next = nextActivity(activity)
        if (next == homeActivity()) {
            return goHome(activity)
        }

        activity.startActivity(onboardingIntent(activity, next))
        activity.finish()
    }

    /**
     * The next destination class, committing the step as it goes — for callers that have to
     * build their own intent chain around it (the language picker wraps it in the
     * full-screen-intent screen). Returns [homeActivity] when the order is done, having marked
     * onboarding completed.
     *
     * Only call this when the caller is definitely navigating: the step moves either way.
     */
    fun nextActivity(context: Context): Class<*> =
        resolveFrom(context, context.config.onboardingStep + 1)

    /**
     * Puts an interrupted first run back on screen, and says whether it did.
     *
     * The home screen can be reached with onboarding still unfinished, because granting the
     * home role hands the system a new default launcher: the OS brings THIS activity up the
     * moment the user picks us in Settings, while the onboarding task that asked for the role
     * is still sitting behind it — never resumed, and excluded from recents, so the user has no
     * way back to it. The run would sit at its current step forever and the remaining screens
     * (language, welcome, intro on the paid order) would never be seen.
     *
     * Resolution starts AT the stored step rather than after it: the step that was interrupted
     * is usually the default-home ask, which is exactly the entry that is now satisfied and
     * gets stepped over. The screen is launched on top and the home is left underneath, so the
     * eventual [goHome] lands on a home screen that is already built.
     */
    fun resumeIfUnfinished(activity: Activity): Boolean {
        if (wasOnboardingCompleted(activity)) {
            return false
        }

        val next = resolveFrom(activity, activity.config.onboardingStep)
        if (next == homeActivity()) {
            return false
        }

        log("home reached mid-run → resuming onboarding")
        activity.startActivity(onboardingIntent(activity, next))
        return true
    }

    /**
     * The first screen at or after [from] that still has something to do, committing the step
     * as it goes. [homeActivity] when there is none, having marked onboarding completed.
     */
    private fun resolveFrom(context: Context, from: Int): Class<*> {
        val order = LauncherAdsConfig.onboardingOrder(context)
        var index = from

        while (index < order.size) {
            val screen = order[index]
            if (isApplicable(context, screen)) {
                context.config.onboardingStep = index
                log("→ [$index] ${screen.key}")
                return activityFor(screen)
            }

            // Reaching the default-home step while we already hold the role is the same
            // outcome as granting it there, so `skip_rest_on_grant` applies: the rest of the
            // order is abandoned rather than shown to a user who has nothing left to do.
            if (screen == OnboardScreen.SET_DEFAULT && alreadyGranted(context)) {
                log("[$index] ${screen.key}: role already held → home")
                break
            }

            log("skipping [$index] ${screen.key}")
            index++
        }

        log("order finished → home")
        markOnboardingCompleted(context)
        return homeActivity()
    }

    /**
     * Whether [screen] has anything to do right now. A `set_default` entry with nothing left
     * to ask is what makes a repeat of it at the end of the order harmless.
     */
    private fun isApplicable(context: Context, screen: OnboardScreen): Boolean = when (screen) {
        OnboardScreen.SET_DEFAULT -> {
            val step = LauncherAdsConfig.defaultHomeStep(context)
            step.enabled && !(step.skipIfDefault && context.isDefaultLauncher())
        }

        else -> true
    }

    /** The default-home step is being skipped because the role is already ours, not because
     *  it is switched off — and the config says that ends onboarding. */
    private fun alreadyGranted(context: Context): Boolean {
        val step = LauncherAdsConfig.defaultHomeStep(context)
        return step.enabled && step.skipIfDefault && step.skipRestOnGrant &&
                context.isDefaultLauncher()
    }

    private fun activityFor(screen: OnboardScreen): Class<*> = when (screen) {
        OnboardScreen.WELCOME -> OnboardingWelcomeActivity::class.java
        OnboardScreen.SET_DEFAULT -> OnboardingDefaultLauncherActivity::class.java
        OnboardScreen.INTRO -> IntroActivity::class.java
        OnboardScreen.LANGUAGE -> LocaleActivity::class.java
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
