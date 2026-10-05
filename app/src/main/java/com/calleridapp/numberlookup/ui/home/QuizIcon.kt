package com.calleridapp.numberlookup.ui.home

import android.app.Activity
import android.content.Context
import android.util.Log
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.DirectLinkOpener
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.util.Analytics
import org.json.JSONObject

/**
 * The game-quiz icon in the App Home header. A tap opens one custom link and nothing else — no
 * interstitial, no ad chain, and the App Open ad is skipped on the way back ([DirectLinkOpener]
 * marks a link that leaves the app).
 *
 * ```json
 * "quiz_icon": { "enabled": true, "url": "https://…", "open_in": "custom_tab" }
 * ```
 *
 * `open_in` is `webview` | `custom_tab` | `browser`; blank uses the global `link_open_in`. Off when
 * the block is absent, `enabled` is false, or `url` is blank, so the icon never shows without a link.
 */
object QuizIcon {

    private const val TAG = "QuizIcon"
    const val CONFIG_KEY = "quiz_icon"

    private fun config(context: Context): JSONObject? {
        val raw = AdsVault.getInstance(context).getString(CONFIG_KEY)
        if (raw.isNullOrBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun url(context: Context): String? =
        config(context)?.takeIf { it.optBoolean("enabled", false) }
            ?.optString("url").orEmpty().trim().takeIf { it.isNotEmpty() }

    fun isVisible(context: Context): Boolean = url(context) != null

    fun open(activity: Activity) {
        val url = url(activity) ?: return
        val mode = DirectLinkOpener.modeOf(config(activity)?.optString("open_in"))
        Analytics.log("home_quiz_icon_click")
        val opened = DirectLinkOpener.open(activity, url, mode)
        if (BuildConfig.DEBUG) Log.d(TAG, "open '$url' mode=$mode → $opened")
    }
}
