package com.calleridapp.numberlookup.util

import android.content.Context
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.numberlookup.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.launcher.home.extensions.isDefaultLauncher

/**
 * The context every Crashlytics report carries, so a crash can be split by who hit it: which
 * audience half of the config the device reads, whether ads and the launcher are live on it, and
 * which build. Refreshed on every foreground and after every config ingest — all of it can change
 * while the process lives.
 */
object CrashKeys {

    fun update(context: Context) {
        runCatching {
            val vault = AdsVault.getInstance(context)
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("audience", if (vault.getBoolean("OnMaketing")) "marketing" else "organic")
                setCustomKey("ads_on", vault.getBoolean("IsAdsON"))
                setCustomKey("ad_type", vault.getString("IsAdType").orEmpty())
                setCustomKey("default_home", runCatching { context.isDefaultLauncher() }.getOrDefault(false))
                setCustomKey("build_type", BuildConfig.BUILD_TYPE)
            }
        }
    }
}
