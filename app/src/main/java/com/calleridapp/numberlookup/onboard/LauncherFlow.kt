package com.calleridapp.numberlookup.onboard

import com.calleridapp.numberlookup.util.Analytics

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.admesh.domain.LauncherAdsConfig.OnboardScreen
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.LookupShellApp
import com.calleridapp.numberlookup.R
import io.launcher.home.activities.LauncherPanel
import com.calleridapp.numberlookup.ui.ShellActivity
import com.calleridapp.numberlookup.util.GuardRail
import org.fossify.commons.extensions.getSharedPrefs
import io.launcher.home.extensions.isDefaultLauncher
import com.calleridapp.numberlookup.ui.language.LocaleActivity
import com.calleridapp.numberlookup.ui.onboarding.IntroActivity

/**
 * One place that owns the first-run route, so the caller-ID screens and the launcher screens
 * agree on where the user is headed.
 *
 * The sequence itself comes from Remote Config — `launcher_ads.onboarding.order` — and
 * defaults to:
 *
 *     Splash
 *       └─ Set as default launcher?   (opens the system home-app page on arrival)
 *            ├─ allowed  → Home        (with skip_rest_on_grant, the default)
 *            └─ skipped  → Language → Welcome (notifications + phone state) → Intro → Home
 *
 * Reorder it, drop a screen, or list `set_default` twice to ask again at the end; see
 * `docs/launcher-ads-config.md`. "Home" is [homeActivity]: the launcher module's [LauncherPanel] by
 * default, or the caller-ID app's own [ShellActivity] when `onboarding_home` is `"app"`.
 *
 * How far the sequence has got is kept in fossify's shared prefs (`onboarding_step`), an index into the resolved
 * order. A cold start that finds onboarding unfinished restarts it from the top, exactly as
 * the hardcoded flow did.
 */
object LauncherFlow {

    private const val TAG = "LauncherFlow"

    // Kept in fossify's shared "Prefs" file under the in-app launcher's old key names, so an install
    // that finished (or is part-way through) onboarding before the launcher swap does not replay it.
    private const val WAS_ONBOARDING_COMPLETED = "was_onboarding_completed"
    private const val ONBOARDING_STEP = "onboarding_step"

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

    /**
     * Whether Back moves the user forward through onboarding. Paid (marketing) users: yes.
     * Organic users: no — Back is Back, handled by the screen's default (leaving the screen).
     */
    fun backMovesForward(context: Context): Boolean =
        AdsVault.getInstance(context).getBoolean("OnMaketing")

    /** Hands a Back press on to the next handler (the screen's default), from inside [callback]. */
    fun passBackThrough(activity: ComponentActivity, callback: OnBackPressedCallback) {
        callback.isEnabled = false
        activity.onBackPressedDispatcher.onBackPressed()
    }

    fun wasOnboardingCompleted(context: Context): Boolean =
        context.getSharedPrefs().getBoolean(WAS_ONBOARDING_COMPLETED, false)

    /** Intent for the next onboarding screen, carrying the first-run marker forward. */
    fun onboardingIntent(context: Context, target: Class<*>): Intent =
        Intent(context, target).putExtra(EXTRA_LAUNCHER_ONBOARDING, true)

    /**
     * Records that the first run is over, so the next cold start goes straight to the home
     * screen. Kept separate from [goHome] because a screen can decide onboarding is finished and
     * still have a conditional step (the full-screen-intent prompt) to route through first.
     */
    fun markOnboardingCompleted(context: Context) {
        context.getSharedPrefs().edit().putBoolean(WAS_ONBOARDING_COMPLETED, true).apply()
        // The first run is over: events stop carrying the first_ prefix.
        Analytics.endFirstSession()
    }

    /**
     * The end of every first-run path. Marks onboarding done and clears the onboarding screens
     * off the back stack, so Back from the home screen never walks back into them.
     */
    fun goHome(activity: Activity) {
        markOnboardingCompleted(activity)
        activity.startActivity(
            Intent(activity, homeActivity()).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        activity.finish()
    }

    /**
     * Where the app lands once onboarding is over — the end of the flow and every later
     * app-icon tap. `onboarding_home` in the GET_DATA_LIST audience block: `"app"` opens the
     * caller-ID app's own home ([ShellActivity]); anything else, or unset, the launcher home.
     * The system HOME button always opens the launcher once the app holds the Home role; this
     * only decides the app's own hand-off.
     */
    fun homeActivity(): Class<*> =
        if (AdsVault.getInstance(LookupShellApp.appContext).getString(HOME_KEY)
                ?.trim()?.lowercase() == "app"
        ) {
            ShellActivity::class.java
        } else {
            LauncherPanel::class.java
        }

    private const val HOME_KEY = "onboarding_home"

    // ===================== "Step n of m" =====================

    /**
     * The screens that carry a "Step n of m" indicator, in numbering order. The intro slides are
     * deliberately absent — they show their own page dots — so the count the user sees never
     * includes a step they will not be numbered through.
     */
    private val NUMBERED_STEPS = setOf(
        OnboardScreen.SET_DEFAULT,
        OnboardScreen.LANGUAGE,
        OnboardScreen.WELCOME,
    )

    /** 1-based position of a screen and the numbered total, computed from the live order. */
    data class StepPosition(val index: Int, val total: Int)

    /**
     * Where [screen] sits among the numbered steps of the current onboarding order, or null when
     * it is not a numbered step. The total follows the order, so removing a step from Remote
     * Config re-counts the rest.
     */
    fun stepPosition(context: Context, screen: OnboardScreen): StepPosition? {
        val numbered = LauncherAdsConfig.onboardingOrder(context).filter { it in NUMBERED_STEPS }
        val pos = numbered.indexOf(screen)
        return if (pos < 0) null else StepPosition(pos + 1, numbered.size)
    }

    /**
     * Fills the shared step header (`view_onboarding_step_header.xml`) found under [root], and
     * hides it when [screen] is not a numbered step. Done here rather than in each screen so the
     * steps cannot disagree on the count.
     */
    fun bindStepHeader(context: Context, screen: OnboardScreen, root: View) {
        val header = root.findViewById<View>(R.id.onboarding_step_header) ?: return
        val position = stepPosition(context, screen)
        if (position == null) {
            header.visibility = View.GONE
            return
        }
        header.visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.onboarding_step_pill).text =
            context.getString(R.string.onboarding_step_counter, position.index, position.total)
        root.findViewById<ProgressBar>(R.id.onboarding_step_progress).progress =
            position.index * 100 / position.total
    }

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
        resolveFrom(context, context.getSharedPrefs().getInt(ONBOARDING_STEP, 0) + 1)

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

        val step = activity.getSharedPrefs().getInt(ONBOARDING_STEP, 0)
        // The user picked us in the Settings list: the system started the launcher, and the
        // default-home screen that would have run its "Next" ad is never resumed. That ad is owed
        // to whichever screen comes up now — the launcher home, or the next onboarding step.
        if (LauncherAdsConfig.onboardingOrder(activity).getOrNull(step) == OnboardScreen.SET_DEFAULT &&
            activity.isDefaultLauncher()
        ) {
            AdsVault.getInstance(activity).apply {
                putBoolean(GRANT_AD_PENDING, true)
                putLong(GRANT_AD_AT, System.currentTimeMillis())
            }
            log("role granted from Settings → grant ad pending")
        }

        val next = resolveFrom(activity, step)
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
                context.getSharedPrefs().edit().putInt(ONBOARDING_STEP, index).apply()
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
        // Never in the order (inOrder = false): they place themselves, so there is no hop to them.
        OnboardScreen.FSI, OnboardScreen.SPLASH -> error("${screen.key} is not a step of onboarding.order")
    }

    // ===================== the default-home grant ad =====================

    private const val GRANT_AD_PENDING = "__set_default_grant_ad_pending"

    /** When the grant was seen. The ad is owed to the next landing screen, not to one hours later. */
    private const val GRANT_AD_AT = "__set_default_grant_ad_at"
    private const val GRANT_AD_TTL_MS = 2 * 60_000L

    /** The default-home screen ran its own "Next" ad; nothing is owed any more. */
    fun clearGrantAd(context: Context) {
        AdsVault.getInstance(context).putBoolean(GRANT_AD_PENDING, false)
    }

    /**
     * Shows the default-home "Next" ad ([LauncherAdsConfig.runOnboardingInter], `set_default`)
     * on the first of our screens to resume after a grant made from the Settings list — see
     * [resumeIfUnfinished]. Registered once, from the Application.
     */
    fun registerGrantAd(app: android.app.Application) {
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val vault = AdsVault.getInstance(activity)
                if (!vault.getBoolean(GRANT_AD_PENDING)) return
                // A grant ad whose landing screen never came up (the process died, the user left)
                // would otherwise fire on some unrelated resume much later.
                if (System.currentTimeMillis() - vault.getLong(GRANT_AD_AT, 0L) > GRANT_AD_TTL_MS) {
                    vault.putBoolean(GRANT_AD_PENDING, false)
                    return
                }
                // Only on a screen the user is meant to land on: the launcher home, or an
                // onboarding step. Never on the coach-mark over Settings or an ad page.
                val landing = activity is LauncherPanel || activity is ShellActivity ||
                    activity.intent?.getBooleanExtra(EXTRA_LAUNCHER_ONBOARDING, false) == true
                // The default-home screen shows this very ad itself once it sees the role (its
                // onResume → goHome). Running it here as well started a second chain for the same
                // moment: two ads, and a rewarded step over the first one.
                if (activity is OnboardingDefaultLauncherActivity) {
                    vault.putBoolean(GRANT_AD_PENDING, false)
                    return
                }
                if (!landing || activity.isFinishing) return
                vault.putBoolean(GRANT_AD_PENDING, false)
                log("grant ad → ${activity::class.java.simpleName}")
                activity.window.decorView.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    LauncherAdsConfig.runOnboardingInter(activity, OnboardScreen.SET_DEFAULT) {}
                }
            }

            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}

/**
 * Drops this app's task from Recents; the onboarding steps are not somewhere to come back to.
 *
 * The launcher's home task is left alone: excluding it is what left the fallback Recents half
 * drawn on Android 16 / ColorOS 16 (the module stopped excluding it in its manifest for the same
 * reason), and the system keeps home tasks out of the cards anyway.
 */
fun Activity.excludeAppFromRecents() {
    try {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val home = LauncherPanel::class.java.name
        manager.appTasks.forEach { task ->
            val info = runCatching { task.taskInfo }.getOrNull()
            if (info?.baseActivity?.className == home || info?.topActivity?.className == home) return@forEach
            task.setExcludeFromRecents(true)
        }
    } catch (e: Exception) {
        GuardRail.error("Recents", "could not exclude task from recents", e)
    }
}
