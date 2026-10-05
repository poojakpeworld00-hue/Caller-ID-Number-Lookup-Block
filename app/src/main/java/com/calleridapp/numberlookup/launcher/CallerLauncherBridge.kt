package com.calleridapp.numberlookup.launcher

import com.calleridapp.numberlookup.util.Analytics

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import android.provider.Telephony
import androidx.fragment.app.Fragment
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.admesh.domain.LauncherPlacementAds
import com.calleridapp.admesh.presentation.AppOpenAdRegistry
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.LookupShellApp
import com.calleridapp.numberlookup.data.LocaleRegistry
import com.calleridapp.numberlookup.onboard.LauncherFlow
import com.calleridapp.numberlookup.onboard.LauncherHintPrompt
import com.calleridapp.numberlookup.onboard.RoleHintActivity
import com.calleridapp.numberlookup.ui.ShellActivity
import com.calleridapp.numberlookup.util.AppVault
import com.calleridapp.numberlookup.util.GuardRail
import com.calleridapp.numberlookup.util.applyNativeAdTheme
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.launcher.home.activities.LauncherPanel
import io.launcher.home.api.LauncherBridge
import io.launcher.home.api.LauncherKeys
import org.json.JSONObject

/** What only this app can answer for the launcher module. Pulled on demand, never cached. */
class CallerLauncherBridge : LauncherBridge {

    override fun configString(key: String, fallback: String): String {
        // The module's ads-helper slot entries are not used here: CallerLauncherAds reads this
        // app's own `launcher_ads` slot blocks instead, so the module keeps its defaults. Only the
        // drawer ad's row is read from it; the value comes from `launcher_ads`
        // (`app_drawer.bottom_native.position`).
        if (key == LauncherKeys.ADS_CONFIG) {
            val row = LauncherAdsConfig.appDrawerSlot(LookupShellApp.appContext).position
            return """{"screenWiseAds":{"app_drawer":{"ad_row_position":$row}}}"""
        }
        if (key == LauncherKeys.LAUNCHER_CONFIG) return launcherConfig(fallback)
        return topLevel(key).ifBlank { fallback }
    }

    private fun topLevel(key: String): String =
        runCatching { FirebaseRemoteConfig.getInstance().getString(key) }.getOrNull().orEmpty()

    /**
     * `launcher_config`, merged key by key from two places:
     *
     *  - the top-level `launcher_config` parameter (audience-resolved here if it has
     *    `organic` / `marketing` blocks), as the base;
     *  - the copy inside the GET_DATA_LIST / DEBUG_GET_DATA_LIST audience block, already
     *    audience-resolved at ingestion by ConfigSync, whose keys win.
     *
     * So a key left out of the data list is still controlled by the top-level parameter.
     */
    private fun launcherConfig(fallback: String): String {
        val base = runCatching { JSONObject(topLevel(LauncherKeys.LAUNCHER_CONFIG)) }.getOrNull()
            ?.let { root -> root.optJSONObject(if (isOrganicAudience()) "organic" else "marketing") ?: root }
        val overlay = AdsVault.getInstance(LookupShellApp.appContext)
            .getString(LauncherKeys.LAUNCHER_CONFIG, "")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (base == null && overlay == null) return fallback
        val merged = base ?: JSONObject()
        overlay?.let { o -> o.keys().forEach { merged.put(it, o.get(it)) } }
        return merged.toString()
    }

    /**
     * The dock's reserved slot. `launcher_config.dock_host_app: false` gives it to the default SMS
     * app instead of this app. Asked on every resume, so the switch reaches existing installs.
     */
    override fun dockSlotPackage(context: Context): String? {
        val showHost = runCatching { JSONObject(launcherConfig("")).optBoolean("dock_host_app", true) }
            .getOrDefault(true)
        return if (showHost) context.applicationContext.packageName else Telephony.Sms.getDefaultSmsPackage(context)
    }

    /**
     * What our own icon opens from the launcher (dock, drawer, apps panel): the app's home, not its
     * LAUNCHER entry — that is the splash, which may route straight back to this launcher. The
     * launcher only runs once onboarding is done.
     */
    override fun hostLaunchComponent(context: Context): ComponentName =
        ComponentName(context, ShellActivity::class.java)

    /**
     * The launcher's "Setup Not Complete" card and long-press "Set as default" open the same system
     * list onboarding does, so they get onboarding's hint too — one centred, dimmed card with the
     * tapping hand, wherever the list was opened from.
     */
    override fun defaultHomeHintIntent(context: Context): Intent = RoleHintActivity.intent(context)

    override fun isOrganicAudience(): Boolean =
        !AdsVault.getInstance(LookupShellApp.appContext).getBoolean("OnMaketing")

    override fun panelFragment(): Fragment = LauncherShellFragment()

    /**
     * Granting the Home role mid-onboarding makes the system start the launcher instead of the next
     * onboarding step. Finish the flow first; [LauncherFlow.goHome] starts the launcher again.
     */
    override fun onLauncherStart(activity: Activity): Boolean {
        if (LauncherFlow.resumeIfUnfinished(activity)) {
            activity.finish()
            return false
        }
        // The launcher is not one of our HostActivities, so it inherits none of their setup.
        LocaleRegistry.applySaved(activity)
        activity.applyNativeAdTheme()
        (activity as? LauncherPanel)?.let { LauncherShellHost.attach(it) }
        LauncherPlacementAds.preload(activity)
        Analytics.log("launcher_home_open")
        return true
    }

    /**
     * Another app was just opened from the launcher: the return from it is its own monetised moment
     * (AppExitAd, else the `launcher_ads.app_drawer` sequence, run by LookupShellApp on the next
     * foreground).
     */
    override fun onAppLaunched(packageName: String) {
        Analytics.log("launcher_app_click")
        AppOpenAdRegistry.expectReturnAd()
        LauncherAdsConfig.preloadDrawerAds(LookupShellApp.appContext)
        // The app-exit flow loads while the user is in the other app.
        LauncherPlacementAds.preload(LookupShellApp.appContext)
    }

    override fun onLauncherResume(activity: Activity) {
        // Back from the system "Default home app" list: the coach-mark drawn over it comes down.
        LauncherHintPrompt.dismiss()
        LocaleRegistry.applySaved(activity)
        LauncherShellHost.of(activity)?.homeShellController?.onHostResume()
        // Replaces whatever the last gesture used, so the next one has an ad ready.
        LauncherPlacementAds.preload(activity)
    }

    /**
     * The launcher's context in the in-app language. AppCompat's per-app locale cannot reach the
     * home task on Android 14+ (LocaleRegistry swallows that refusal), so the launcher is built in
     * the saved language here instead, and rebuilds itself when it changes.
     */
    override fun localizeContext(base: Context): Context {
        val saved = runCatching { AppVault.selectedLanguage(base) }.getOrDefault("")
        if (saved.isEmpty()) return base
        val tag = if (saved == "in") "id" else saved
        val config = Configuration(base.resources.configuration)
        if (config.locales[0]?.language == tag) return base
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }

    /** `launcher_ads.system_buttons.home`. */
    override fun onHomePressed(activity: Activity, alreadyOnHome: Boolean) =
        SystemButtonAds.onHome(activity, alreadyOnHome)

    /** `launcher_ads.system_buttons.back`. */
    override fun onWorkspaceBack(activity: Activity) = SystemButtonAds.onBack(activity)

    /** Anything the launcher caught and survived goes to Crashlytics as a non-fatal. */
    override fun onNonFatal(error: Throwable) {
        GuardRail.error("Launcher", error.message ?: error.javaClass.simpleName, error)
    }

    override fun onEvent(name: String, params: Map<String, String>) {
        // The launcher module's own events go through the same logger (first_ prefix, debug echo).
        Analytics.log(name, *params.map { (k, v) -> k to v }.toTypedArray())
    }

    override fun isDebug(): Boolean = BuildConfig.DEBUG
}
