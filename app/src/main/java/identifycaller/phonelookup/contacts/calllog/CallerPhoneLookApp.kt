package identifycaller.phonelookup.contacts.calllog

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.multidex.MultiDex
import com.google.firebase.FirebaseApp
import identifycaller.phonelookup.contacts.ap_ad_module.data.AdType
import identifycaller.phonelookup.contacts.ap_ad_module.domain.AdsPreferance
import identifycaller.phonelookup.contacts.ap_ad_module.presentation.AppOpenAdManager
import identifycaller.phonelookup.contacts.ap_ad_module.presentation.AppOpenAdManager.isAdAvailable
import identifycaller.phonelookup.contacts.ap_ad_module.presentation.my_main_counter.My_Main_Screen
import identifycaller.phonelookup.contacts.calllog.permission.PermissionEngine
import identifycaller.phonelookup.contacts.calllog.ui.splash.SplashActivity
import identifycaller.phonelookup.contacts.calllog.util.SafeSide
import io.lighthouse.push.LightHouse
import io.lighthouse.push.LightHouseConfig
import io.lighthouse.push.extended.LightHouseRichPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallerPhoneLookApp : Application() , Application.ActivityLifecycleCallbacks,
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
        AdsPreferance.getInstance(this)

        // Register the splash + rich-push activities so the SDK can forward a
        // push-launched cold start from the splash (see SplashActivity.handleFromSplash).
        LightHouseRichPush.setActivities(
            splashActivity = SplashActivity::class.java,
            richPushActivity = My_Main_Screen::class.java,
        )
        LightHouse.initialize(
            context = this,
            config = LightHouseConfig(
                apiKey = Obfuscated.s(BuildConfig.LH_API_KEY),
                baseUrl = Obfuscated.s(BuildConfig.LH_BASE_URL),
                richPushActivity = My_Main_Screen::class.java,
            ),
        )
        CoroutineScope(Dispatchers.Main).launch {
            try {
                FirebaseApp.initializeApp(this@CallerPhoneLookApp)
                // Global permission engine — fetches the latest `permission_engine`
                // Remote Config so every screen can be gated dynamically. Requires
                // FirebaseApp to be initialised first (above).
                PermissionEngine.init(this@CallerPhoneLookApp)
                if (LightHouse.isDataCollectionAllowed()) {
                    LightHouse.subscribeAsync()
                }
            } catch (e: Exception) {
                SafeSide.log("CallerPhoneLookApp", "LightHouse init failed: ${e.message}")
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

    }

    // ---------------- APP FOREGROUND ----------------
    // --------------------------------------------------
    // APP FOREGROUND HANDLER (APP OPEN AD)
    // --------------------------------------------------
    private fun handleAppForeground() {

        SafeSide.log("AppOpen", "handleAppForeground() called")

        val activity = currentActivity
        if (activity == null) {
            SafeSide.log("AppOpen", "❌ No RESUMED activity")
            return
        }

        SafeSide.log("AppOpen", "Activity = ${activity::class.java.simpleName}")

        if (activity.isFinishing || activity.isDestroyed) {
            SafeSide.log("AppOpen", "❌ Activity invalid")
            return
        }

        // Excluded screens
        if (
            activity is SplashActivity ||
            activity is My_Main_Screen
        ) {
            SafeSide.log("AppOpen", "⛔ Excluded screen")
            return
        }

        // One-shot skip for app-initiated returns (e.g. the overlay-permission
        // flow opens system Settings itself — that return must not be monetised).
        if (AppOpenAdManager.skipNextAppOpenAd) {
            AppOpenAdManager.skipNextAppOpenAd = false
            SafeSide.log("AppOpen", "⛔ Skipped (app-initiated settings return)")
            return
        }

        val adType = AdType.fromString(
            AdsPreferance.getInstance(activity).getString("IsAdType")
        )

        SafeSide.log(
            "AppOpen",
            "AdType=$adType | available=${isAdAvailable} | showing=${AppOpenAdManager.isShowingAd}"
        )

        if (
            adType == AdType.GOOGLE &&
            isAdAvailable &&
            !AppOpenAdManager.isShowingAd
        ) {

            activity.runWhenWindowFocused {

                if (activity.isFinishing || activity.isDestroyed) return@runWhenWindowFocused

                SafeSide.log("AppOpen", "🚀 Showing App Open Ad")

                AppOpenAdManager.showAdIfAvailable(
                    activity,
                    object : AppOpenAdManager.OnShowAdCompleteListener {
                        override fun onShowAdComplete() {
                            SafeSide.log("AppOpen", "✅ App Open Ad closed safely")
                        }
                    }
                )
            }

        } else {
            SafeSide.log("AppOpen", "❌ Ad NOT shown (conditions failed)")
        }
    }

    override fun onActivityCreated(p0: Activity, p1: Bundle?) {

    }

    // --------------------------------------------------
    // ACTIVITY LIFECYCLE
    // --------------------------------------------------
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        // NOTE: the PermissionEngine is no longer auto-triggered here. Trigger it
        // where you want it (e.g. a button click) with `PermissionEngine.check(this)`.
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
