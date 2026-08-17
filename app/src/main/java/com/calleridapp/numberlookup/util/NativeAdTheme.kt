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
 * Copies the light/dark native-ad palette for the active theme into the ad preferences, where
 * the native renderers read it from.
 *
 * The palette keys (`NativebtnColor`, `NativeBgColor`, …) are **global and last-write-wins** —
 * nothing re-derives them at render time — so whichever screen wrote them last decides how every
 * native ad afterwards looks. That makes this a per-screen responsibility, not a one-off:
 * a screen that renders natives without calling this shows them in whatever mode some earlier
 * screen left behind, or unset entirely on a cold boot (dark-on-dark, effectively invisible).
 *
 * Called by [com.calleridapp.numberlookup.base.HostActivity] for every normal screen, and
 * separately by the launcher home — which does not extend it, yet is the device HOME and so is
 * often the first screen after a reboot.
 */
fun Context.applyNativeAdTheme(
    theme: String = AppVault.selectedTheme(this).ifEmpty { THEME_SYSTEM }
) {
    val adsPref = AdsVault.getInstance(this)
    val modeKey = when (theme) {
        THEME_LIGHT -> "NativeLight"
        THEME_DARK -> "NativeDark"
        THEME_SYSTEM -> {
            val isSystemDark =
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            if (isSystemDark) "NativeDark" else "NativeLight"
        }

        else -> "NativeLight"
    }

    try {
        // Load saved marketing and default theme JSONs
        val marketingJson = JSONObject(adsPref.getString("NativeTheme_marketing", "{}"))
        val defaultJson = JSONObject(adsPref.getString("NativeTheme_default", "{}"))

        // Choose which theme to apply (marketing preferred if enabled)
        val themeJson = if (adsPref.getBoolean("OnMaketing") && marketingJson.has(modeKey))
            marketingJson.optJSONObject(modeKey)
        else
            defaultJson.optJSONObject(modeKey)

        if (themeJson == null) {
            // No palette to copy — Remote Config has not landed yet (the launcher is the device
            // HOME, so it can run before any fetch has ever happened) or the key is absent. Say
            // so rather than logging a write that did not occur.
            Log.d(TAG, "No $modeKey palette in NativeTheme_* — native colors left unchanged")
            return
        }

        adsPref.putString("NativebtnColor", themeJson.optString("btnColor"))
        adsPref.putString("NativebtntxtColor", themeJson.optString("btnText"))
        adsPref.putString("NativeBgColor", themeJson.optString("bgColor"))
        adsPref.putString("NativetxtColor", themeJson.optString("textColor"))

        Log.d(TAG, "Applied $modeKey theme to ads dynamically")
    } catch (e: Exception) {
        Log.e(TAG, "Error applying native theme dynamically", e)
    }
}
