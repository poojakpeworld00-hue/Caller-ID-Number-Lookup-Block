package com.calleridapp.admesh.presentation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach

/**
 * The in-app landing page for direct-link promos (the drawer's `directlink` ad step and the drawer's
 * configured promo tiles).
 *
 * A WebView rather than a Custom Tab or the browser, because neither of those can hide its toolbar
 * and the whole point is that the landing page fills the screen. Chrome-less on purpose: no
 * toolbar, no title, back goes back through the page's own history and only leaves once it has none.
 *
 * Views are built in code — a single full-bleed WebView does not earn a layout file.
 */
class PromoWebActivity : Activity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.getStringExtra(EXTRA_URL)?.trim()
        if (url.isNullOrEmpty()) {
            // Nothing to show; never leave an empty white screen on the stack.
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        val web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val target = request.url
                    // market://, intent://, tel:, mailto: … are for other apps, not the WebView.
                    // If nothing resolves we return false and let the WebView try (and fail quietly).
                    if (!DirectLinkOpener.isWebScheme(target)) {
                        val handed = runCatching {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, target)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.isSuccess
                        // Step aside once the hand-off takes. We are a separate task, so staying
                        // alive leaves Play (or the dialler, or the mail app) opening *behind* a
                        // WebView the user then has to dismiss to see what they tapped.
                        if (handed) finish()
                        return handed
                    }
                    return false
                }
            }
        }
        webView = web
        root.addView(web)
        setContentView(root)

        // Pad the root, not the WebView: the page then scrolls under nothing and its own content
        // still starts below the status bar.
        applyInsets(root)
        web.loadUrl(url)
    }

    private fun applyInsets(root: FrameLayout) {
        fun apply(insets: WindowInsetsCompat?) {
            val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars()) ?: return
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        }
        root.doOnAttach { apply(ViewCompat.getRootWindowInsets(root)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            apply(insets)
            insets
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val web = webView
        if (web != null && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "promo_web_url"
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        fun intentFor(context: Context, url: String): Intent =
            Intent(context, PromoWebActivity::class.java).putExtra(EXTRA_URL, url)

        /**
         * Opens [url] in this WebView, falling back to whatever else can handle it. Blank or
         * scheme-less links are ignored, so a misconfigured Remote Config entry is a no-op rather
         * than a crash. Returns true if something was opened.
         */
        fun open(context: Context, url: String): Boolean {
            val uri = runCatching { url.trim().toUri().takeIf { !it.scheme.isNullOrBlank() } }
                .getOrNull() ?: return false
            if (runCatching { context.startActivity(intentFor(context, url.trim())) }.isSuccess) {
                return true
            }
            return runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
        }
    }
}
