package com.calleridapp.numberlookup.util

import android.app.Activity
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.numberlookup.ui.splash.LaunchActivity
import io.launcher.home.activities.LauncherPanel

/**
 * Which screens an unrequested ad moment may land on.
 *
 * The charging and package-result pages, the App Open ad and the consent prompt are all things
 * that arrive on a screen the user did not open for them. They belong on our own full screens - the
 * app's HostActivities and the launcher home - and never on an ad page, the splash, the incoming /
 * post-call screens, or a page floating over another app (they used to drain onto whatever resumed
 * next, the incoming-call screen included).
 */
object AdSurfaces {

    /** Never an App Open, a queued event page or a consent form on these (simple class names). */
    val EXCLUDED = setOf(
        "ChargingStatusActivity", "PackageResultActivity", "RecentAdActivity", "PromoWebActivity",
        "OverlayGuideActivity", "RoleHintActivity", "FullScreenAccessActivity", "InboundCallActivity",
        "My_Shell_Screen", "AdActivity", "AudienceNetworkActivity",
    )

    fun isExcluded(activity: Activity) = activity::class.java.simpleName in EXCLUDED

    /** One of our own full screens the user is actually on. */
    fun isLanding(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed || isExcluded(activity)) return false
        return (activity is HostActivity<*> && activity !is LaunchActivity) || activity is LauncherPanel
    }
}
