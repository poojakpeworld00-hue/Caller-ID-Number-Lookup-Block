package io.launcher.home.api

import android.app.Activity
import android.widget.FrameLayout

/**
 * The launcher's ad slots, as seen from the launcher side.
 *
 * The launcher decides *whether* a slot should be asked for — that is `launcher_config`'s business
 * and it stays in the module. It never decides *how* one is filled: every app wires its own SDK,
 * its own unit ids and its own consent, so each call here is a request the host may quietly ignore.
 *
 * An app with no ads passes [NoOp] (or nothing), and every launcher surface still works — the
 * frames simply stay GONE.
 *
 * Implementations must be safe to call before the ad SDK has finished initialising. The launcher
 * asks for its inline slots during view setup, which on a cold start is earlier than most SDKs are
 * ready; dropping the request there leaves a permanent blank gap, so defer it instead.
 */
interface LauncherAds {

    /**
     * Whether the ad SDK is initialised enough for an *interstitial* to be worth attempting.
     *
     * Only the interstitial paths ask, because those are the ones that cannot be deferred: a
     * gesture-triggered ad that arrives late lands on a screen the user has already left.
     */
    fun isReady(): Boolean = false

    /**
     * Fills [container] with the native placement named [key], or leaves it GONE.
     *
     * The launcher passes its own keys (`launcher_panel_bottom`, `launcher_panel_mid`,
     * `launcher_drawer_top`). Implementations should hide the frame on failure rather than leave a
     * shimmer on a panel that is never going to show an ad.
     */
    fun bindNative(activity: Activity?, key: String, container: FrameLayout?) {}

    /**
     * Same as [bindNative], but for a *re-open* of a surface that may already hold an ad.
     *
     * Split out because "do not replace an ad the user is already looking at" is a host policy, not
     * a launcher one — and there is nothing to replace if the first request never filled.
     */
    fun bindNativeOnOpen(activity: Activity?, key: String, container: FrameLayout?) =
        bindNative(activity, key, container)

    /**
     * Shows an interstitial and then calls [onDone], whatever happened.
     *
     * [onDone] is the continuation of whatever the user asked for — a panel opening, a drawer
     * sliding up — so it must run exactly once even when no ad was shown, the SDK failed, or the
     * host decided to skip it. The default does precisely that and shows nothing.
     */
    fun showInterstitial(activity: Activity, tag: String, onDone: () -> Unit) = onDone()

    /**
     * A full-screen native for [tag] — the app-launch placement asks for this. Same contract as
     * [showInterstitial]: run [onDone] exactly once. Defaults to the interstitial.
     */
    fun showFullNative(activity: Activity, tag: String, onDone: () -> Unit) =
        showInterstitial(activity, tag, onDone)

    /**
     * Opens a sponsored drawer tile's landing page. Hosts route it through their own link opener
     * (WebView / Custom Tab); the default hands it to whatever handles `ACTION_VIEW`.
     */
    fun openSponsored(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            )
        }
    }

    /** No ads anywhere. Every launcher surface still works. */
    object NoOp : LauncherAds

    companion object {
        /**
         * Native slot at the bottom of the app-search panel.
         *
         * These three are also the `screenWiseAds` keys in the ads parameter, so the name a
         * host switches on here, the name the launcher passes, and the name in the console are
         * one string rather than three that have to be kept in step.
         */
        const val SLOT_PANEL_BOTTOM = "apps_panel"

        /** Native slot in the middle of the app-search panel, under the suggested apps. */
        const val SLOT_PANEL_MID = "suggested_recent_panel"

        /** Native slot inside the app drawer, at `ad_row_position`. */
        const val SLOT_DRAWER_TOP = "app_drawer"
    }
}
