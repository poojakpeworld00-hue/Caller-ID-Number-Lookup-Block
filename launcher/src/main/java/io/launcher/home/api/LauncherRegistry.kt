package io.launcher.home.api

import android.content.Context
import io.launcher.home.config.LauncherSetup

/**
 * The single hand-off point between a host app and the launcher.
 *
 * A host calls [install] once, from its `Application.onCreate`:
 *
 * ```kotlin
 * LauncherRegistry.install(this)                       // plain launcher, no ads, no panel
 *
 * LauncherRegistry.install(                            // or wire what this app has
 *     context = this,
 *     bridge  = AppLauncherBridge(this),
 *     ads     = AppLauncherAds(),
 * )
 * ```
 *
 * ### Why install is optional
 *
 * The launcher's Activities can be started by the system — the user pressed Home — before any host
 * code has run in that process, and on some OEM builds before `Application.onCreate` has finished.
 * A registry that could be un-installed at that moment would have to be null-checked at every call
 * site, and the one call site that forgot would crash the home screen. So [ensure] fills in an
 * application context and the do-nothing defaults on first touch, and [install] simply replaces
 * them. Nothing here is ever null.
 */
object LauncherRegistry {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installedBridge: LauncherBridge = LauncherBridge.Default

    @Volatile
    private var installedAds: LauncherAds = LauncherAds.NoOp

    /** The host's bridge, or the do-nothing default when it installed none. */
    val bridge: LauncherBridge get() = installedBridge

    /** The host's ad wiring, or [LauncherAds.NoOp] when it installed none. */
    val ads: LauncherAds get() = installedAds

    /**
     * Wires the launcher to its host. Call once from `Application.onCreate`.
     *
     * Safe to call again — a host that re-installs (a test, a process restart, a late-arriving
     * dependency) replaces what was there rather than stacking on it.
     */
    @JvmStatic
    @JvmOverloads
    fun install(
        context: Context,
        bridge: LauncherBridge = LauncherBridge.Default,
        ads: LauncherAds = LauncherAds.NoOp,
    ) {
        appContext = context.applicationContext
        installedBridge = bridge
        installedAds = ads
    }

    /**
     * Records an application context if [install] has not run yet. Called by the launcher's own
     * entry points, never by a host.
     */
    fun ensure(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** The application context, for the launcher's own use. */
    fun context(): Context = requireNotNull(appContext) {
        "LauncherRegistry has no context: an entry point did not call ensure(context)"
    }

    /**
     * `launcher_config`, resolved for this install's audience.
     *
     * Parsed on every call rather than cached, so a config that lands seconds into a cold start is
     * picked up by the next thing that asks, instead of by the next process. Parsing is a few
     * hundred bytes of JSON and every caller is already on a user-visible path that does more work
     * than this.
     */
    fun setup(): LauncherSetup = LauncherSetup.parse(
        raw = installedBridge.configString(LauncherKeys.LAUNCHER_CONFIG, ""),
        organic = installedBridge.isOrganicAudience(),
    )
}

/** Remote Config keys the launcher reads through [LauncherBridge.configString]. */
object LauncherKeys {

    /** Behaviour switches: panels, guide steps, gesture ads, unlock ads. */
    const val LAUNCHER_CONFIG = "launcher_config"

    /** The ads blob, for the two launcher-only fields the ad SDK model drops. */
    const val ADS_CONFIG = "ads_config"

    /** OEM home-layout match rules — which profile applies to which device. */
    const val HOME_PROFILE_RULES = "home_profile_rules"

    /** OEM home-layout probes — where to look inside each OEM launcher's APK. */
    const val HOME_PROFILE_PROBES = "home_profile_probes"
}
