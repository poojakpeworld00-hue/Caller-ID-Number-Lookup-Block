package com.calleridapp.numberlookup

import com.calleridapp.numberlookup.util.Analytics

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetHost
import android.content.Context
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.multidex.MultiDex
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.calleridapp.admesh.data.AdKind
import com.calleridapp.admesh.domain.AdsGate
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.AdPreloader
import com.calleridapp.admesh.domain.ConfigSync
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.admesh.domain.LauncherPlacementAds
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal
import com.calleridapp.admesh.presentation.ADDashboardActivity
import com.calleridapp.admesh.presentation.AppOpenAdRegistry
import com.calleridapp.admesh.presentation.AppOpenAdRegistry.isAdAvailable
import com.calleridapp.admesh.presentation.my_main_counter.My_Shell_Screen
import io.launcher.home.activities.LauncherPanel as LauncherHomeActivity
import com.calleridapp.numberlookup.launcher.AppExitAd
import com.calleridapp.numberlookup.launcher.CallerLauncherAds
import com.calleridapp.numberlookup.launcher.SystemButtonAds
import com.calleridapp.numberlookup.launcher.UnlockAdWatcher
import com.calleridapp.numberlookup.onboard.LauncherFlow
import com.calleridapp.numberlookup.ui.charging.ChargeEventWatcher
import com.calleridapp.numberlookup.ui.pkgresult.PackageEventWatcher
import com.calleridapp.numberlookup.ui.recent.RecentAdWatcher
import com.calleridapp.numberlookup.launcher.CallerLauncherBridge
import io.launcher.home.api.LauncherRegistry
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.helpers.BaseConfig
import com.calleridapp.numberlookup.permission.AccessEngine
import com.calleridapp.numberlookup.ui.splash.LaunchActivity
import com.calleridapp.numberlookup.util.CrashGuard
import com.calleridapp.numberlookup.util.CrashKeys
import com.calleridapp.numberlookup.util.CrashRestartActivity
import com.calleridapp.numberlookup.util.GuardRail
import io.lighthouse.push.LightHouse
import io.lighthouse.push.LightHouseConfig
import io.lighthouse.push.extended.LightHouseRichPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fossify.commons.helpers.SIDELOADING_FALSE

class LookupShellApp : Application() , Application.ActivityLifecycleCallbacks,
    LifecycleObserver, Configuration.Provider {
    private var currentActivity: Activity? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** Application context, set in [onCreate] — used where only a Context is needed
         *  (e.g. building the OkHttp client's Chucker interceptor). */
        lateinit var appContext: Context
            private set

        // One-time cleanup of the in-app launcher the :launcher module replaced.
        private const val LEGACY_LAUNCHER_DROPPED = "legacy_launcher_dropped_v1"
        private const val LEGACY_WIDGET_HOST_ID = 12345
    }

    /**
     * WorkManager reads this the first time something asks for it, now that the manifest has
     * taken it off the androidx.startup path. Nothing here is customised — the point is only that
     * initialisation happens somewhere the app can survive it failing.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /**
     * Runs before any content provider — Firebase's included — so the handler installed here is
     * the one Crashlytics captures and delegates to. See [CrashGuard] for why that matters.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashGuard.installQuietTerminator()
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // onCreate runs in every process, and the `:restart` one exists only to bring the app
        // back after a crash (see CrashRestartActivity). Initialising ads, push, Firebase — or a
        // second CrashGuard — in it would be waste at best and a crash inside crash handling at
        // worst, so it gets nothing but the context set above.
        if (CrashRestartActivity.isRestartProcess(this)) return

        MultiDex.install(this)
        AdsVault.getInstance(this)
        Analytics.init(this)

        // App install / removal → the result screen, shown from whichever of our screens is
        // foreground (queued otherwise). Also seeds the package metadata cache.
        PackageEventWatcher.register(this)

        // Charger plugged in / pulled out → the charging screen. Inert unless `system_ads.charge` /
        // `discharge` is enabled in Remote Config.
        ChargeEventWatcher.register(this)

        // An ad after unlock, on our own home. Inert unless `launcher_config.unlock_ads.enabled`.
        UnlockAdWatcher.register(this)

        // The app reopened from the Recents list. Inert unless `recent_ad.enabled` is on in
        // Remote Config — registering it costs a dormant install almost nothing.
        RecentAdWatcher.register(this)

        // The default-home "Next" ad, when the role was granted from the Settings list and the
        // system started the launcher instead of resuming the default-home screen.
        LauncherFlow.registerGrantAd(this)

        // Home / Back on the launcher home: `launcher_ads.system_buttons.{home,back}`.
        SystemButtonAds.register(this)

        // Fossify Commons runs an anti-clone heuristic that probes one of its own drawable ids
        // and, on a lookup miss, wedges the app behind a permanent "download the original"
        // dialog. This is a legitimate rebuild of Fossify's GPL sources, so the check is a false
        // positive here — recording the result up front means the probe never runs. It has to be
        // in onCreate rather than attachBaseContext: Context.config is not safe to read earlier.
        BaseConfig.newInstance(this).appSideloadingStatus = SIDELOADING_FALSE

        dropLegacyLauncherState()
        // Home grid, drawer and side panels come from the :launcher module; its gesture ads and
        // inline slots are served by this app's own ad layer (CallerLauncherAds).
        LauncherRegistry.install(
            context = this,
            bridge = CallerLauncherBridge(),
            ads = CallerLauncherAds(),
        )

        // Register the splash + rich-push activities so the SDK can forward a
        // push-launched cold start from the splash (see LaunchActivity.handleFromSplash).
        LightHouseRichPush.setActivities(
            splashActivity = LaunchActivity::class.java,
            richPushActivity = My_Shell_Screen::class.java,
        )
        LightHouse.initialize(
            context = this,
            config = LightHouseConfig(
                apiKey = Scrambled.s(BuildConfig.LH_API_KEY),
                baseUrl = Scrambled.s(BuildConfig.LH_BASE_URL),
                richPushActivity = My_Shell_Screen::class.java,
                // How long the audience gate waits for the Play install-referrer verdict
                // before settling for what it has. First launch only — the SDK caches it
                // afterwards. One number for every attribution wait in the app: the
                // disclosure gate and ADDashboardActivity's resolveAttribution both use it.
                attributionWaitMs = 5_000L,
                // rc_sync: a "Remote Config changed" push. The SDK hands us the template version
                // and we fetch + ingest at once, rather than waiting for the next foreground's
                // stale check — this is how a change reaches a phone parked on the home screen or
                // in the background, where the realtime channel is closed.
                useRemoteConfig = true,
                onRemoteConfigSync = { version ->
                    GuardRail.log("LH_RemoteConfig", "rc_sync v=$version → fetching")
                    ConfigSync.refreshIfStale(this@LookupShellApp, force = true)
                },
            ),
        )
        // Sideloads have no install referrer, so the SDK would classify them organic while
        // ADDashboardActivity runs whichever half the audience constants pick. Force the SDK to
        // the same side so the disclosure screen and the config under test agree — without this
        // they disagree, and the app reads as "organic install got the marketing config".
        //
        // Release normally keeps real attribution and never gets here; the second branch is the
        // deliberate test-build override, and is dead code whenever
        // FORCE_RELEASE_AUDIENCE_MARKETING is false, which is how it ships.
        //
        // The forced source alone is not enough: a device the SDK has flagged as a reinstall /
        // cross-app install is classified ORGANIC *before* the forced source is consulted
        // ("installSource → ORGANIC (device flagged …)" under LH_Disclosure). On any phone that has
        // had this app installed before — every test device — that silently turned the pinned
        // MARKETING audience into an organic disclosure decision, and the organic disclosure spec
        // is the one that shows the screen. Clearing the flag makes the forced source win, so the
        // SDK and ADDashboardActivity settle on the same audience.
        if (BuildConfig.DEBUG) {
            LightHouse.debugForceFlagged(false)
            LightHouse.debugForceInstallSource(
                if (ADDashboardActivity.DEBUG_AUDIENCE_MARKETING) "paid" else "organic"
            )
        } else if (ADDashboardActivity.FORCE_RELEASE_AUDIENCE_MARKETING) {
            LightHouse.debugForceFlagged(false)
            LightHouse.debugForceInstallSource("paid")
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                FirebaseApp.initializeApp(this@LookupShellApp)
                // Global permission engine — fetches the latest `permission_engine`
                // Remote Config so every screen can be gated dynamically. Requires
                // FirebaseApp to be initialised first (above).
                // No subscribeAsync() here: LaunchActivity does it from the
                // ensureDataDisclosure callback, which is the one place that knows the
                // user has acknowledged the disclosure. Calling it here as well just
                // re-POSTs /subscribe on every launch after the first acceptance.
                AccessEngine.init(this@LookupShellApp)
            } catch (e: Exception) {
                GuardRail.error("CallerPhoneLookApp", "Firebase / AccessEngine init failed", e)
            }
        }

        // A config ingested while the launcher home is on screen (realtime update, rc_sync push)
        // takes effect now, not on the next time the user leaves and comes back.
        ConfigSync.onApplied = {
            CrashKeys.update(this)
            (currentActivity as? LauncherHomeActivity)?.takeIf { !it.isFinishing && !it.isDestroyed }?.let { home ->
                if (!home.reapplyLauncherStyle()) home.onConfigUpdated()
            }
        }

        registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    CrashKeys.update(this@LookupShellApp)
                    handleAppForeground()
                    // Remote Config follows the PROCESS, not one screen.
                    //
                    // These used to hang off the launcher home's onStart/onResume/onStop,
                    // which meant a console change only ever reached a device that was
                    // sitting on the home grid — never a user inside the app, and never at
                    // all on a device where we are not the default launcher. Owning it here
                    // covers every screen: the realtime channel is live for as long as the
                    // app is foreground, and dies with it rather than with an Activity.
                    ConfigSync.startRealtime(this@LookupShellApp)
                    // Backstop for the push channel (offline when the template was
                    // published, or a device it never reached). Throttled by
                    // `Config_Sync_Hrs`, so repeat foregrounds inside the window cost
                    // nothing; a zero window means every foreground fetches.
                    ConfigSync.refreshIfStale(this@LookupShellApp)
                }

                override fun onStop(owner: LifecycleOwner) {
                    // A live server connection has no business outliving the foreground.
                    ConfigSync.stopRealtime()
                }
            }
        )

        // Last: it wraps whichever UncaughtExceptionHandler is already installed (Crashlytics',
        // via Firebase's init provider — content providers are created before this method).
        // Process lifecycle, not currentActivity, is the foreground signal: currentActivity is
        // kept for the app-open ad and is only cleared on destroy, so it stays set while the app
        // sits in the background.
        CrashGuard.install(this) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
    }

    /**
     * One-time, on the first start after the in-app launcher was replaced by the :launcher module.
     *
     * The old launcher kept its home grid in a hand-rolled SQLite `apps.db` and marked it built
     * with `was_home_screen_init` in fossify's shared prefs. The module uses the same file name for
     * its Room database and the same pref key, so left alone Room would try to adopt a schema it did
     * not create and the module would skip seeding a grid it never built.
     */
    private fun dropLegacyLauncherState() {
        val prefs = getSharedPrefs()
        if (prefs.getBoolean(LEGACY_LAUNCHER_DROPPED, false)) return
        deleteDatabase("apps.db")
        // Both launchers bind widgets under host id 12345. The ids the old one allocated are still
        // held by the system with no grid row pointing at them; release them all so the module
        // starts on a clean host.
        runCatching { AppWidgetHost(this, LEGACY_WIDGET_HOST_ID).deleteHost() }
        prefs.edit()
            .remove("was_home_screen_init")
            // Grid sizes come from the module's OEM layout match on first run, not the old defaults.
            .remove("home_column_count")
            .remove("home_row_count")
            .remove("drawer_column_count")
            .putBoolean(LEGACY_LAUNCHER_DROPPED, true)
            .apply()
    }

    // ---------------- APP FOREGROUND ----------------
    // --------------------------------------------------
    // APP FOREGROUND HANDLER (APP OPEN AD)
    // --------------------------------------------------
    private fun handleAppForeground() {

        GuardRail.log("AppOpen", "handleAppForeground() called")

        val activity = currentActivity
        if (activity == null) {
            GuardRail.log("AppOpen", "❌ No RESUMED activity")
            return
        }

        GuardRail.log("AppOpen", "Activity = ${activity::class.java.simpleName}")

        if (activity.isFinishing || activity.isDestroyed) {
            GuardRail.log("AppOpen", "❌ Activity invalid")
            return
        }

        // The splash owns its own ad path and must never be monetised on foreground.
        if (activity is LaunchActivity) {
            GuardRail.log("AppOpen", "⛔ Excluded screen (splash)")
            return
        }

        // Every return to the app refills what the session has used up; the pools used to be
        // filled by the splash alone.
        AdPreloader.topUp(activity)

        // Back from an ad's own click-out, or an ad is still up: never an App Open on top of it.
        if (AdsGate.isFullScreenShowing) {
            GuardRail.log("AppOpen", "⛔ A full-screen ad is showing")
            AppOpenAdRegistry.consumeExpectReturnAd()
            return
        }

        // One-shot skip for app-initiated returns (e.g. the overlay-permission
        // flow opens system Settings itself — that return must not be monetised).
        if (AppOpenAdRegistry.skipNextAppOpenAd) {
            AppOpenAdRegistry.skipNextAppOpenAd = false
            // A programmatic return is not monetised, so drop the launch flag too.
            AppOpenAdRegistry.consumeExpectReturnAd()
            GuardRail.log("AppOpen", "⛔ Skipped (app-initiated settings return)")
            return
        }

        // Coming back from an app the launcher just opened is the one time an ad shows on the
        // launcher home: `gestures.app_exit` on → the app-exit flow on the `appExit` placement;
        // off → the `launcher_ads.app_drawer` sequence (App Open / interstitial / direct link,
        // counter-gated). A direct link that wins opens on the way back, never alongside the app.
        if (AppOpenAdRegistry.consumeExpectReturnAd()) {
            // Unlocked back onto the launcher: the unlock ad is this moment's one ad. Checked here
            // as well, since this branch used to run first and the two then fired together.
            if (UnlockAdWatcher.claimsForeground()) {
                GuardRail.log("AppOpen", "⛔ Return ad skipped — unlock ad owns this foreground")
                return
            }
            GuardRail.log("AppOpen", "↩ other_app_return")
            activity.runWhenWindowFocused {
                if (activity.isFinishing || activity.isDestroyed) return@runWhenWindowFocused
                if (!AppExitAd.run(activity)) LauncherAdsConfig.runDrawerAdFlow(activity) {}
            }
            return
        }

        // Ordinary returns never monetise the always-foreground launcher home or the post-call
        // screen: the launcher is resumed on every press of Home, which is not an app launch.
        if (activity is LauncherHomeActivity || activity is My_Shell_Screen) {
            GuardRail.log("AppOpen", "⛔ Excluded screen")
            return
        }

        // Screens that are an ad, carry their own ad, or float over another app: an App Open there
        // stacked a second full-screen ad on the first (charging / package / recents pages) or
        // covered a system page the user was sent to.
        if (com.calleridapp.numberlookup.util.AdSurfaces.isExcluded(activity)) {
            GuardRail.log("AppOpen", "⛔ Excluded screen (${activity::class.java.simpleName})")
            return
        }

        // Just unlocked onto our home: the unlock ad is this moment's one ad, not both.
        if (UnlockAdWatcher.claimsForeground()) {
            GuardRail.log("AppOpen", "⛔ Unlock ad owns this foreground")
            return
        }

        // `appOpen_ad_flow` / an `appOpen_` link chain: the dynamic flow instead of the App Open ad.
        if (LauncherPlacementAds.hasOwnFlow(activity, "appOpen")) {
            if (!AdsVault.getInstance(activity).getBoolean("IsAdsON") ||
                !LauncherPlacementAds.placementEnabled(activity, "appOpen") ||
                AppOpenAdRegistry.isShowingAd || InterstitialNormal.isInterShow
            ) return
            GuardRail.log("AppOpen", "🚀 appOpen flow (appOpen_*)")
            activity.runWhenWindowFocused {
                if (activity.isFinishing || activity.isDestroyed) return@runWhenWindowFocused
                LauncherPlacementAds.showInterstitial(activity, "appOpen") {}
            }
            return
        }

        val adType = AdKind.fromString(
            AdsVault.getInstance(activity).getString("IsAdType")
        )

        GuardRail.log(
            "AppOpen",
            "AdType=$adType | available=${isAdAvailable} | showing=${AppOpenAdRegistry.isShowingAd}"
        )

        if (
            adType == AdKind.GOOGLE &&
            isAdAvailable &&
            !AppOpenAdRegistry.isShowingAd
        ) {

            activity.runWhenWindowFocused {

                if (activity.isFinishing || activity.isDestroyed) return@runWhenWindowFocused

                GuardRail.log("AppOpen", "🚀 Showing App Open Ad")

                AppOpenAdRegistry.showAdIfAvailable(
                    activity,
                    object : AppOpenAdRegistry.OnShowAdCompleteListener {
                        override fun onShowAdComplete() {
                            GuardRail.log("AppOpen", "✅ App Open Ad closed safely")
                        }
                    }
                )
            }

        } else {
            GuardRail.log("AppOpen", "❌ Ad NOT shown (conditions failed)")
            // Not loaded (or expired): load now for the next return. This branch used to end the
            // App Open ad for the session - nothing else ever reloaded it.
            if (adType == AdKind.GOOGLE && !AppOpenAdRegistry.isShowingAd) AppOpenAdRegistry.loadAd(activity)
        }
    }

    override fun onActivityCreated(p0: Activity, p1: Bundle?) {

    }

    // --------------------------------------------------
    // ACTIVITY LIFECYCLE
    // --------------------------------------------------
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        // Consent + SDK start for sessions the splash never ran in: the launcher home after a reboot,
        // our icon in our own launcher, a push. Only on our own full screens - never an ad page, the
        // post-call screen or a page floating over another app - and a no-op once the SDK is up.
        if (com.calleridapp.numberlookup.util.AdSurfaces.isLanding(activity)) {
            AdsGate.ensure(activity) {
                activity.window?.decorView?.post { AdPreloader.topUp(activity) }
            }
        }
        // NOTE: the AccessEngine is no longer auto-triggered here. Trigger it
        // where you want it (e.g. a button click) with `AccessEngine.check(this)`.
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) {
            currentActivity = null
        }
    }

    override fun onActivityPaused(p0: Activity) {

    }

    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {

    }

    override fun onActivityStarted(p0: Activity) {

    }

    override fun onActivityStopped(p0: Activity) {

    }

    // --------------------------------------------------
    // WINDOW FOCUS SAFE EXECUTION
    // --------------------------------------------------
    private fun Activity.runWhenWindowFocused(action: () -> Unit) {
        if (hasWindowFocus()) {
            action()
        } else {
            val decorView = window.decorView
            val listener =
                object : ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (hasFocus) {
                            decorView.viewTreeObserver
                                .removeOnWindowFocusChangeListener(this)
                            action()
                        }
                    }
                }
            decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        }
    }

}
