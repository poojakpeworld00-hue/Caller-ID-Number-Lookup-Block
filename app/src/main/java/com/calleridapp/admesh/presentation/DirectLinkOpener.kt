package com.calleridapp.admesh.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.numberlookup.BuildConfig

/**
 * The one place a direct link opens, so how it opens is a single Remote-Config switch that every
 * surface obeys — the drawer's `directlink` ad step, the drawer promo tiles, the gesture-link
 * fallback, and the interstitial / splash custom-ad links.
 *
 * The type comes from the flat `DirectLinkType` pref (same store as `DirectLink` / `IsCustomADS`):
 *
 *  - `webview` (default) — our own chrome-less full-screen [PromoWebActivity]; the user stays in-app.
 *  - `custom_tab` — a Chrome Custom Tab.
 *  - `browser` — the external browser (`ACTION_VIEW`).
 *
 * An unknown or missing value is treated as `webview`. Every path is best-effort and falls back to
 * the external browser, and a blank / scheme-less URL is a no-op rather than a crash.
 */
object DirectLinkOpener {

    private const val TAG = "DirectLinkOpener"

    /** Pref key holding the open type. Flat, like `DirectLink`, so every surface can read it. */
    const val MODE_KEY = "DirectLinkType"

    enum class Mode { WEBVIEW, CUSTOM_TAB, BROWSER }

    fun mode(context: Context): Mode = modeOf(AdsVault.getInstance(context).getString(MODE_KEY))
        ?: Mode.WEBVIEW

    /**
     * Parses one open-type name, or null when it names nothing we know — including blank, which is
     * how a per-slot `open_type` says "use the global setting".
     */
    fun modeOf(name: String?): Mode? = when (name?.trim()?.lowercase()) {
        "custom_tab", "customtab", "chrome", "tab" -> Mode.CUSTOM_TAB
        "browser", "external", "external_browser" -> Mode.BROWSER
        "webview", "web_view", "in_app", "inapp" -> Mode.WEBVIEW
        else -> null
    }

    /**
     * Opens [url] in the configured mode. Fire-and-forget — it does not report when the page closes.
     * Returns true if something opened; false for a blank / unopenable link so a caller can fall
     * through (e.g. to the next ad in a sequence).
     */
    @JvmOverloads
    fun open(context: Context, url: String?, override: Mode? = null): Boolean {
        val u = url?.trim().orEmpty()
        if (u.isBlank()) return false

        // A store link is never a web page. Handing it to a WebView or a Custom Tab shows the
        // scheme error, or Play's own "open in the app?" interstitial, in front of the real
        // listing — so it goes straight out to whoever owns it.
        if (isStoreLink(u)) {
            log("store link — straight to ACTION_VIEW: '$u'")
            return openBrowser(context, u).also { if (it) leftApp() }
        }

        return when (override ?: mode(context)) {
            // PromoWebActivity.open already carries its own ACTION_VIEW fallback.
            Mode.WEBVIEW -> PromoWebActivity.open(context, u)
            Mode.CUSTOM_TAB -> (openCustomTab(context, u) || openBrowser(context, u)).also { if (it) leftApp() }
            Mode.BROWSER -> openBrowser(context, u).also { if (it) leftApp() }
        }.also { if (!it) log("nothing opened for '$u'") }
    }

    /**
     * The link took the user out of the app (Custom Tab, browser, Play Store): the way back is our
     * own doing, not a return to monetise, so the App Open ad is skipped for it.
     */
    private fun leftApp() = com.calleridapp.admesh.domain.AdsGate.skipNextAppOpen()

    /** http / https — the only two schemes a WebView or a Custom Tab can actually render. */
    fun isWebScheme(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    /**
     * A Play Store destination: the `market://` scheme, or a `play.google.com` web link. Both belong
     * to the Play app, which opens them far better than anything we could host.
     */
    fun isStoreLink(url: String): Boolean {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() == "market") return true
        val host = uri.host?.lowercase() ?: return false
        return host == "play.google.com" || host.endsWith(".play.google.com")
    }

    private fun openCustomTab(context: Context, url: String): Boolean = runCatching {
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tab.launchUrl(context, url.toUri())
        true
    }.getOrDefault(false)

    private fun openBrowser(context: Context, url: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
