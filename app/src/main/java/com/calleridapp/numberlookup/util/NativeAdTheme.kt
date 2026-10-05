package com.calleridapp.numberlookup.util

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.numberlookup.util.AppVault.THEME_DARK
import com.calleridapp.numberlookup.util.AppVault.THEME_LIGHT
import com.calleridapp.numberlookup.util.AppVault.THEME_SYSTEM
import org.json.JSONObject

private const val TAG = "NativeTheme"

/**
 * AdsVault key holding the native-ad palette of the user's audience: the `NativeTheme` object of
 * the `organic` or `marketing` block, `{ "NativeLight": {…}, "NativeDark": {…} }`. Written once
 * per config ingest by `ConfigSync`, read here.
 */
const val NATIVE_THEME_KEY = "NativeTheme"

/** `NativeLight` / `NativeDark` for [theme] (the app's theme choice; system follows the device). */
fun Context.nativeThemeMode(
    theme: String = AppVault.selectedTheme(this).ifEmpty { THEME_SYSTEM }
): String = when (theme) {
    THEME_DARK -> "NativeDark"
    THEME_LIGHT -> "NativeLight"
    THEME_SYSTEM -> {
        val isSystemDark =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        if (isSystemDark) "NativeDark" else "NativeLight"
    }

    else -> "NativeLight"
}

/**
 * Copies the light/dark native-ad palette for the active theme into the ad preferences, where
 * the native renderers read it from.
 *
 * The palette keys (`NativebtnColor`, `NativeBgColor`, …) are **global and last-write-wins** —
 * nothing re-derives them at render time — so whichever screen wrote them last decides how every
 * native ad afterwards looks. That makes this a per-screen responsibility, not a one-off:
 * a screen that renders natives without calling this shows them in whatever mode some earlier
 * screen left behind, or unset entirely on a cold boot (dark-on-dark, effectively invisible).
 *
 * Called by [com.calleridapp.numberlookup.base.HostActivity] for every normal screen, by
 * `ConfigSync` right after a config lands, and separately by the launcher home — which does not
 * extend it, yet is the device HOME and so is often the first screen after a reboot.
 *
 * Organic and marketing each carry their own palette in Remote Config; [NATIVE_THEME_KEY] already
 * holds the one for this user's audience, so there is no audience choice to make here.
 */
fun Context.applyNativeAdTheme(theme: String = AppVault.selectedTheme(this).ifEmpty { THEME_SYSTEM }) {
    val adsPref = AdsVault.getInstance(this)
    val modeKey = nativeThemeMode(theme)

    try {
        val palette = JSONObject(adsPref.getString(NATIVE_THEME_KEY, "{}").orEmpty().ifBlank { "{}" })
        val themeJson = palette.optJSONObject(modeKey)
        if (themeJson == null) {
            // No palette to copy — Remote Config has not landed yet (the launcher is the device
            // HOME, so it can run before any fetch has ever happened) or the key is absent. Say
            // so rather than logging a write that did not occur.
            Log.d(TAG, "No $modeKey palette in $NATIVE_THEME_KEY — native colors left unchanged")
            return
        }

        adsPref.update {
            putString("NativebtnColor", themeJson.optString("btnColor"))
            putString("NativebtntxtColor", themeJson.optString("btnText"))
            putString("NativeBgColor", themeJson.optString("bgColor"))
            putString("NativetxtColor", themeJson.optString("textColor"))
        }

        Log.d(TAG, "Applied $modeKey theme to ads dynamically")
    } catch (e: Exception) {
        Log.e(TAG, "Error applying native theme dynamically", e)
    }
}
