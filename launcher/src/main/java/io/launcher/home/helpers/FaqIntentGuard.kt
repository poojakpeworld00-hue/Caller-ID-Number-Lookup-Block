package io.launcher.home.helpers

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.fossify.commons.activities.FAQActivity
import org.fossify.commons.helpers.APP_FAQ
import org.fossify.commons.helpers.APP_ICON_IDS
import org.fossify.commons.helpers.APP_LAUNCHER_NAME
import org.fossify.commons.models.FAQItem
import timber.log.Timber

/**
 * Keeps Fossify's FAQ screen from crashing when it is started without its list.
 *
 * FAQActivity reads `app_faq` with a non-null cast the first time it composes. Its only caller,
 * AboutActivity, always passes the list, but a FAQ screen re-created by the system - restored from
 * a killed process on some ROMs, or relaunched by an app-cloning environment - can arrive with the
 * extras gone, and then the cast throws a NullPointerException. The library cannot be changed, so
 * the list is put back here: onActivityCreated runs inside the activity's own onCreate, before the
 * first composition reads the intent.
 */
object FaqIntentGuard : Application.ActivityLifecycleCallbacks {

    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(this)
        registered = true
    }

    /** The launcher's FAQ, as the About screen is given it. */
    fun launcherFaqItems(context: Context): ArrayList<FAQItem> {
        val items = ArrayList<FAQItem>()
        if (!context.resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)) {
            items += FAQItem(
                title = org.fossify.commons.R.string.faq_2_title_commons,
                text = org.fossify.commons.R.string.faq_2_text_commons
            )
            items += FAQItem(
                title = org.fossify.commons.R.string.faq_6_title_commons,
                text = org.fossify.commons.R.string.faq_6_text_commons
            )
        }
        return items
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is FAQActivity) return
        // Reading a damaged extra can throw on its own (a Serializable that no longer unparcels),
        // which counts as missing.
        val present = runCatching { activity.intent?.getSerializableExtra(APP_FAQ) is ArrayList<*> }
            .getOrDefault(false)
        if (present) return
        Timber.w("FaqIntentGuard: FAQActivity started without $APP_FAQ, supplying the launcher's list")
        val old = activity.intent
        activity.intent = Intent(activity, FAQActivity::class.java).apply {
            putExtra(APP_FAQ, launcherFaqItems(activity))
            runCatching { old?.getStringExtra(APP_LAUNCHER_NAME) }.getOrNull()?.let { putExtra(APP_LAUNCHER_NAME, it) }
            runCatching { old?.getIntegerArrayListExtra(APP_ICON_IDS) }.getOrNull()?.let { putExtra(APP_ICON_IDS, it) }
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
