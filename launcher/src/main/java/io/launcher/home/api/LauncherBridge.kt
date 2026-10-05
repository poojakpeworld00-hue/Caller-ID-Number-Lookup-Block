package io.launcher.home.api

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment

/**
 * Everything the launcher needs from the app it is embedded in.
 *
 * Every method has a default, so a host that wants a plain launcher implements nothing at all and
 * passes no bridge. The methods are *pulled* — the launcher asks at the moment it needs the answer,
 * never caching it — so a host may change its mind at runtime (a purchase completes, a theme flips,
 * a config lands) and the next draw already reflects it. That is also why this is an interface of
 * functions rather than a data class of values: a value handed over at install time is a snapshot
 * that goes stale the first time anything changes.
 *
 * Nothing here may hand the launcher an Activity or a View. [panelFragment] returns a fresh
 * instance per call for the same reason: the launcher outlives any one screen of the host.
 */
interface LauncherBridge {

    /**
     * Raw JSON for a Remote Config key, or [fallback] when the host has nothing (yet).
     *
     * The launcher asks for its own keys only — see `LauncherKeys`. The host decides where the
     * value comes from: Firebase Remote Config, its own ads SDK's ingested preferences, a
     * hardcoded string in a debug build, or nothing at all.
     *
     * Returning [fallback] on a cold start is correct and expected: the launcher's own defaults
     * are conservative (every ad off, every permission prompt on), so an unfetched config can
     * never start showing ads on its own.
     */
    fun configString(key: String, fallback: String = ""): String = fallback

    /**
     * Which audience branch of the config applies — `organic` when true, `marketing` when false.
     *
     * Defaults to organic, the quieter of the two, because an app that cannot tell the difference
     * should not be treated as paid traffic.
     */
    fun isOrganicAudience(): Boolean = true

    /**
     * The host's own UI for the right-hand panel, or null for no right panel at all.
     *
     * Return a **new** instance each call. The launcher commits it once, keeps it across opens to
     * hold scroll position and selection, and asks again only if that one is gone.
     *
     * If the fragment also implements [LauncherPanelContent] it is told when the panel is actually
     * on screen — which matters, because the panel is committed while parked off-screen. Anything
     * the host must not do until the user can see it (load ads, prompt, animate) belongs behind
     * that callback, not in `onCreate`.
     */
    fun panelFragment(): Fragment? = null

    /**
     * Where the host's own icon on the home screen should point.
     *
     * Defaults to the app's declared launch activity. Override when the launcher's dock icon
     * should open something other than the app's normal entry point.
     */
    fun hostLaunchComponent(context: Context): ComponentName? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.component

    /**
     * First refusal on a launch of the launcher home screen. Return false to take the foreground
     * back and have the launcher build nothing at all.
     *
     * This exists because the Home role changes who the system starts. The moment a user grants it
     * — which a host often asks for in the middle of its own first-run flow — pressing Home starts
     * *this* Activity, and the host screen that was waiting to advance is never resumed to notice.
     * A host with a flow to finish answers false here, does whatever it needs, and starts the
     * launcher again (with [io.launcher.home.activities.LauncherPanel.EXTRA_OPEN_HOST_PANEL] if the
     * user should land inside the panel rather than on the grid).
     *
     * Called before any setup work, so a false answer costs nothing. Defaults to true: a host with
     * no flow of its own always wants the home screen.
     */
    fun onLauncherStart(activity: Activity): Boolean = true

    /**
     * Called from the launcher's `onResume`, for host housekeeping that would otherwise never run.
     *
     * A launcher process can stay alive for weeks, so a host that relies on its own Activities
     * resuming — entitlement checks, sync, a daily task — gets no other chance. Keep it cheap and
     * idempotent: duplicate resumes are normal.
     */
    fun onLauncherResume(activity: Activity) {}

    /**
     * Which app owns the dock's reserved slot. Defaults to the host itself.
     *
     * Override when the slot should follow a role rather than a package — a messaging host, for
     * instance, answers with whatever currently holds the SMS role, so the dock always carries a
     * working messaging app even after the user switches. Return null to leave the slot empty and
     * let the user fill it.
     *
     * Asked on every resume, so a role that changes hands while the app was in the background is
     * picked up on the way back.
     */
    fun dockSlotPackage(context: Context): String? = context.packageName

    /**
     * Whether the long-press menu on the host's *own* icon may offer the system Uninstall.
     *
     * False by default, which is what a launcher that is also the app should do: the row the user
     * long-pressed is the app drawing the menu. A host that has no uninstall flow of its own — or
     * that turns one off per audience — answers true and lets the system dialog through.
     */
    fun allowUninstallingHostIcon(): Boolean = false

    /**
     * The coach-mark to draw over the system "Default home app" list, or null for the launcher's
     * own card.
     *
     * A host whose onboarding already has a hint for that list answers with it here, so the user
     * meets one design whether the list was opened from onboarding or from the launcher's "Setup Not
     * Complete" card. The launcher still owns the timing — it waits for the system list to be on
     * screen before starting this — and adds `FLAG_ACTIVITY_NEW_TASK`; the host's Activity should
     * use an empty `taskAffinity` so it floats over Settings rather than joining the host's task.
     */
    fun defaultHomeHintIntent(context: Context): Intent? = null

    /**
     * Wraps the launcher's base context in the host's in-app language.
     *
     * The home screen outlives a language change made inside the host, so the launcher asks this on
     * attach and again on every resume, and rebuilds itself when the answer's locale differs from
     * the one it was built with. Return [base] untouched to follow the system language, which is
     * the default.
     */
    fun localizeContext(base: Context): Context = base

    /**
     * Whether picking the host's own dynamic shortcut [shortcutId] from its long-press menu should
     * remove the host's icon from the home screen in place, instead of launching the shortcut.
     *
     * This is how a host runs a "fake uninstall" without sending the user anywhere: the launcher
     * sets its self-hidden flag, drops every row of the host from the grid, and redraws - the user
     * never leaves Home. False by default, which launches the shortcut like any other.
     */
    fun removesHostIconInPlace(activity: Activity, shortcutId: String): Boolean = false

    /**
     * An analytics event from inside the launcher, e.g. `uninstall_flow_self_hidden`. Forward it to
     * whatever the host logs with; the default drops it.
     */
    fun onEvent(name: String, params: Map<String, String> = emptyMap()) {}

    /**
     * An exception the launcher caught and survived - a home-screen item that failed to draw, say.
     *
     * The launcher skips the broken thing and carries on, so nothing crashes and nothing reaches the
     * host's crash reporter on its own. Forward it (Crashlytics `recordException`, or whatever the
     * host uses) to find out which widget or row is doing it. Called at most once per broken item.
     */
    fun onNonFatal(error: Throwable) {}

    /**
     * The user pressed Home and landed on the launcher. [alreadyOnHome] is true when the launcher was
     * the screen in front (a second Home press), false when Home brought it up from somewhere else.
     * Called after the launcher has closed its drawer and panels; the default does nothing.
     */
    fun onHomePressed(activity: Activity, alreadyOnHome: Boolean) {}

    /** Back on the bare workspace, with nothing open to close. A launcher swallows it; the default does nothing. */
    fun onWorkspaceBack(activity: Activity) {}

    /** Reported after every app launch from the home screen or drawer, including the host's own. */
    fun onAppLaunched(packageName: String) {}

    /** Verbose logging. Wire it to the host's `BuildConfig.DEBUG`. */
    fun isDebug(): Boolean = false

    /** The do-nothing bridge, used when a host installs the launcher without one. */
    object Default : LauncherBridge
}
