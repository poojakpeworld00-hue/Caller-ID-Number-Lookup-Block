package com.calleridapp.numberlookup.data

import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.calleridapp.numberlookup.BuildConfig

/**
 * Applies a per-app language using the AndroidX AppCompat locale APIs.
 * Persistence is handled by AppCompat (see AppLocalesMetadataHolderService in the manifest).
 */
object LocaleRegistry {

    private const val TAG = "LocaleRegistry"

    /**
     * Sets the app language, and survives the system refusing to.
     *
     * On Android 14+ the framework restarts the app's activities to apply a locale, and it
     * throws `IllegalStateException: Can't change activity type once set … activityType=home`
     * when that restart would touch the home task — which is every call this app makes once
     * the user has made the launcher their default home.
     *
     * That throw used to escape into whatever called this. On the language picker it landed
     * between the ad callback and the navigation that followed it, so the first run stopped
     * dead on the language screen with no crash and nothing on screen to explain it.
     *
     * Swallowing it costs the language change on that path — the saved tag stays in AppVault
     * and takes effect the next time a call succeeds — but never the flow the caller was in.
     */
    fun apply(languageTag: String) {
        try {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTag)
            )
        } catch (e: IllegalStateException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "locale '$languageTag' refused: ${e.message}")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "locale '$languageTag' failed: ${e.message}")
        }
    }

}
