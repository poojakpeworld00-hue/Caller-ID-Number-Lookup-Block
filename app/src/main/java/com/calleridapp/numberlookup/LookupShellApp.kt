package com.calleridapp.numberlookup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.multidex.MultiDex
import com.google.firebase.FirebaseApp
import com.calleridapp.admesh.data.AdKind
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.AppOpenAdRegistry
import com.calleridapp.admesh.presentation.AppOpenAdRegistry.isAdAvailable
import com.calleridapp.admesh.presentation.my_main_counter.My_Shell_Screen
import com.calleridapp.numberlookup.launcher.activities.MainActivity as LauncherHomeActivity
import com.calleridapp.numberlookup.launcher.extensions.config
import com.calleridapp.numberlookup.permission.AccessEngine
import com.calleridapp.numberlookup.ui.splash.LaunchActivity
import com.calleridapp.numberlookup.util.CrashGuard
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
    LifecycleObserver{
    private var currentActivity: Activity? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** Application context, set in [onCreate] — used where only a Context is needed
         *  (e.g. building the OkHttp client's Chucker interceptor). */
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        MultiDex.install(this)
        AdsVault.getInstance(this)

        // Fossify Commons runs an anti-clone heuristic that probes one of its own drawable ids
        // and, on a lookup miss, wedges the app behind a permanent "download the original"
        // dialog. This is a legitimate rebuild of Fossify's GPL sources, so the check is a false
        // positive here — recording the result up front means the probe never runs. It has to be
        // in onCreate rather than attachBaseContext: Context.config is not safe to read earlier.
        config.appSideloadingStatus = SIDELOADING_FALSE

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
            ),
        )
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
                GuardRail.log("CallerPhoneLookApp", "LightHouse init failed: ${e.message}")
            }
        }

        registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    handleAppForeground()
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

        // Excluded screens. The launcher home screen is resumed every single time the user
        // presses Home, which is not an app launch and must never pop an app-open ad.
        if (
            activity is LaunchActivity ||
            activity is LauncherHomeActivity ||
            activity is My_Shell_Screen
        ) {
            GuardRail.log("AppOpen", "⛔ Excluded screen")
            return
        }

        // One-shot skip for app-initiated returns (e.g. the overlay-permission
        // flow opens system Settings itself — that return must not be monetised).
        if (AppOpenAdRegistry.skipNextAppOpenAd) {
            AppOpenAdRegistry.skipNextAppOpenAd = false
            GuardRail.log("AppOpen", "⛔ Skipped (app-initiated settings return)")
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
        }
    }

    override fun onActivityCreated(p0: Activity, p1: Bundle?) {

    }

    // --------------------------------------------------
    // ACTIVITY LIFECYCLE
    // --------------------------------------------------
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
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
