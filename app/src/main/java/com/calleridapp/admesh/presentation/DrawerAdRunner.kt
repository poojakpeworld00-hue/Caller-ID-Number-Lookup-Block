package com.calleridapp.admesh.presentation

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.calleridapp.admesh.domain.LauncherAdsConfig.DrawerAdFlow
import com.calleridapp.admesh.domain.LauncherAdsConfig.DrawerAdSpec
import com.calleridapp.admesh.domain.LauncherAdsConfig.DrawerAdType
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.R

/**
 * Runs the app-drawer ad sequence: loads and holds each configured ad format itself (so it has a
 * real "is this ready?" signal, which the app's shared ad classes do not expose), then on an app
 * tap walks the sequence from a persistent pointer and shows the first ready ad.
 *
 * Owning the loads is what lets each format honour its own `ad_unit_id` from Remote Config and lets
 * a not-loaded / failing / disabled / id-less format be skipped cleanly to the next in the chain.
 * The pointer advances past whatever showed and wraps after the last item, so the sequence restarts
 * correctly once exhausted. Everything is best-effort: any failure falls through to [run]'s
 * `proceed`, so a tap never gets stuck behind an ad.
 */
object DrawerAdRunner {

    private const val TAG = "DrawerAdRunner"

    /** The pause between two chained full-screen ads in `show_all` mode. */
    private const val CHAIN_GAP_MS = 400L

    /** Where the direct-link URL rotation has got to. Shared by both drawer flows on purpose. */
    private const val DIRECTLINK_POINTER_KEY = "__launcher_ads_directlink_url_ptr"

    private val mainHandler = Handler(Looper.getMainLooper())

    // Keyed by ad-unit id: the launcher's placements (leftPanel / rightPanel / drawer) each carry
    // their own unit, and one shared slot per format would serve one placement's ad on another.
    private val interAds = HashMap<String, InterstitialAd>()
    private val appOpenAds = HashMap<String, AppOpenAd>()
    private val rewardedAds = HashMap<String, RewardedAd>()
    private val nativeAds = HashMap<String, NativeAd>()

    private val loading = HashSet<String>()

    /** Loads every loadable format in the sequence that is not already in hand. */
    fun preload(context: Context, flow: DrawerAdFlow) {
        flow.sequence.forEach { spec ->
            when (spec.type) {
                DrawerAdType.INTER -> loadInter(context, spec.adUnitId)
                DrawerAdType.APPOPEN -> loadAppOpen(context, spec.adUnitId)
                DrawerAdType.REWARDED -> loadRewarded(context, spec.adUnitId)
                DrawerAdType.FULLSCREEN_NATIVE -> loadNative(context, spec.adUnitId)
                DrawerAdType.DIRECTLINK, DrawerAdType.CUSTOM -> Unit // a URL / house ad, nothing to load
            }
        }
    }

    private fun isReady(spec: DrawerAdSpec): Boolean = when (spec.type) {
        DrawerAdType.INTER -> interAds.containsKey(spec.adUnitId)
        DrawerAdType.APPOPEN -> appOpenAds.containsKey(spec.adUnitId)
        DrawerAdType.REWARDED -> rewardedAds.containsKey(spec.adUnitId)
        DrawerAdType.FULLSCREEN_NATIVE -> nativeAds.containsKey(spec.adUnitId)
        DrawerAdType.DIRECTLINK -> spec.adUnitId.isNotBlank() || spec.urls.isNotEmpty()
        // Nothing to preload; whether a house ad exists is decided when it renders (miss → next).
        DrawerAdType.CUSTOM -> true
    }

    // ---------------- On-demand ----------------

    /** Callbacks waiting on an in-flight load, keyed like [loading] ("inter:<unit>", …). */
    private val waiters = HashMap<String, MutableList<(Boolean) -> Unit>>()

    /** Tells whoever waits on [key] whether its load succeeded. */
    private fun settle(key: String, ok: Boolean) {
        waiters.remove(key)?.forEach { runCatching { it(ok) } }
    }

    /**
     * Loads [spec] now and calls [then] with whether it is ready — or false after
     * [ON_DEMAND_TIMEOUT_MS], so a slow network cannot hold the user's action hostage.
     */
    private fun loadNow(context: Context, spec: DrawerAdSpec, then: (Boolean) -> Unit) {
        val prefix = when (spec.type) {
            DrawerAdType.INTER -> "inter"
            DrawerAdType.APPOPEN -> "appopen"
            DrawerAdType.REWARDED -> "rewarded"
            DrawerAdType.FULLSCREEN_NATIVE -> "native"
            else -> return then(isReady(spec))
        }
        var answered = false
        val once: (Boolean) -> Unit = { ok -> if (!answered) { answered = true; then(ok) } }
        waiters.getOrPut("$prefix:${spec.adUnitId}") { mutableListOf() }.add(once)
        mainHandler.postDelayed({ once(false) }, ON_DEMAND_TIMEOUT_MS)
        preload(context, DrawerAdFlow(true, 0, listOf(spec)))
    }

    /**
     * Walks the sequence and shows ads according to [DrawerAdFlow.showAll] /
     * [DrawerAdFlow.startFromFirst]:
     *
     * - `showAll` — every ready ad in turn, each one's dismissal starting the next, then [proceed].
     * - `startFromFirst` — the first ready ad, always trying the sequence from the top.
     * - neither — the first ready ad, resuming from where the last tap left off.
     *
     * If nothing is ready the pointer is reset and [proceed] runs immediately. [proceed] is invoked
     * exactly once.
     */
    fun run(activity: Activity, flow: DrawerAdFlow, pointerKey: String, proceed: () -> Unit) {
        val done = once(proceed)
        val size = flow.sequence.size
        // Rotating is only meaningful when one tap shows one ad and we want taps to take turns.
        val start = if (flow.showAll || flow.startFromFirst) 0 else {
            AdsVault.getInstance(activity).getInt(pointerKey, 0)
                .let { if (it in 0 until size) it else 0 }
        }
        val order = (0 until size).map { (start + it) % size }
        attempt(activity, flow, order, 0, pointerKey, done)
    }

    /**
     * Tries the candidate at [order]`[k]`; if it is not ready, is skipped, or fails to show, moves
     * on to the next candidate ("skip it and try the next ad in the sequence"). When the list is
     * exhausted the pointer is reset to the start and [done] runs.
     *
     * In `show_all` mode a *successful* show also moves on to the next candidate, so one tap walks
     * the whole chain; otherwise the pointer is advanced past the ad that showed and [done] runs, so
     * the next tap resumes after it and wraps at the end.
     *
     * A direct link is the one step with no dismissal callback of its own — it hands the foreground
     * to a WebView — so its continuation waits on the launcher coming back instead. See [onReturnTo].
     */
    private fun attempt(
        activity: Activity,
        flow: DrawerAdFlow,
        order: List<Int>,
        k: Int,
        pointerKey: String,
        done: () -> Unit,
    ) {
        val size = flow.sequence.size
        if (k >= order.size || activity.isFinishing || activity.isDestroyed) {
            AdsVault.getInstance(activity).putInt(pointerKey, 0)
            log("sequence exhausted — pointer reset, proceeding")
            return done()
        }

        val idx = order[k]
        val spec = flow.sequence[idx]
        if (!isReady(spec)) {
            // Only the first candidate waits: every step waiting would stack one timeout per step.
            if (flow.onDemand && k == 0 && spec.adUnitId.isNotBlank()) {
                log("${spec.type.key} not ready — loading on demand")
                loadNow(activity, spec) { ok ->
                    if (ok && !activity.isFinishing && !activity.isDestroyed) {
                        // Ready now: retry this step without on-demand, so a second miss moves on.
                        attempt(activity, flow.copy(onDemand = false), order, k, pointerKey, done)
                    } else {
                        attempt(activity, flow, order, k + 1, pointerKey, done)
                    }
                }
                return
            }
            log("${spec.type.key} not ready — next")
            attempt(activity, flow, order, k + 1, pointerKey, done)
            return
        }

        if (!flow.showAll && !flow.startFromFirst) {
            AdsVault.getInstance(activity).putInt(pointerKey, (idx + 1) % size)
        }
        val next = { attempt(activity, flow, order, k + 1, pointerKey, done) }
        // Chaining full-screen ads back to back needs a beat: the dismissal callback fires while the
        // previous ad's activity is still finishing, and showing into that window is refused.
        val afterShown: () -> Unit =
            if (flow.showAll) ({ mainHandler.postDelayed({ next() }, CHAIN_GAP_MS) }) else done
        log("attempting ${spec.type.key} (idx=$idx)${if (flow.showAll) " [show_all]" else ""}")

        runCatching {
            when (spec.type) {
                DrawerAdType.INTER -> showInter(activity, spec.adUnitId, afterShown, next)
                DrawerAdType.APPOPEN -> showAppOpen(activity, spec.adUnitId, afterShown, next)
                DrawerAdType.REWARDED -> showRewarded(activity, spec.adUnitId, afterShown, next)
                DrawerAdType.FULLSCREEN_NATIVE -> showNative(activity, spec.adUnitId, afterShown, next)
                DrawerAdType.CUSTOM -> showCustom(activity, afterShown, next)
                DrawerAdType.DIRECTLINK -> {
                    if (!openLink(activity, spec)) {
                        next()
                    } else {
                        // The landing page is now on top, and it is the ad — it has to be read, not
                        // raced. Launching the app (or the next ad in the chain) here would shove it
                        // off the foreground the moment it appeared, so wait for the user to come
                        // back to the launcher and carry on from there.
                        onReturnTo(activity) { afterShown() }
                    }
                }
            }
        }.onFailure {
            Log.e(TAG, "attempt ${spec.type.key} threw", it)
            next()
        }
    }

    // ---------------- Interstitial ----------------

    private fun loadInter(context: Context, unitId: String) {
        if (unitId.isBlank() || interAds.containsKey(unitId) || !loading.add("inter:$unitId")) return
        InterstitialAd.load(
            context, unitId, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interAds[unitId] = ad
                    loading.remove("inter:$unitId")
                    settle("inter:$unitId", true)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading.remove("inter:$unitId")
                    settle("inter:$unitId", false)
                    log("inter load failed: ${error.message}")
                }
            }
        )
    }

    private fun showInter(activity: Activity, unitId: String, onShown: () -> Unit, onFailed: () -> Unit) {
        val ad = interAds.remove(unitId) ?: return onFailed()
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadInter(activity, unitId)
                onShown()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                loadInter(activity, unitId)
                onFailed()
            }
        }
        ad.show(activity)
    }

    // ---------------- App Open ----------------

    private fun loadAppOpen(context: Context, unitId: String) {
        if (unitId.isBlank() || appOpenAds.containsKey(unitId) || !loading.add("appopen:$unitId")) return
        AppOpenAd.load(
            context, unitId, AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAds[unitId] = ad
                    loading.remove("appopen:$unitId")
                    settle("appopen:$unitId", true)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading.remove("appopen:$unitId")
                    settle("appopen:$unitId", false)
                    log("appopen load failed: ${error.message}")
                }
            }
        )
    }

    private fun showAppOpen(activity: Activity, unitId: String, onShown: () -> Unit, onFailed: () -> Unit) {
        val ad = appOpenAds.remove(unitId) ?: return onFailed()
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadAppOpen(activity, unitId)
                onShown()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                loadAppOpen(activity, unitId)
                onFailed()
            }
        }
        ad.show(activity)
    }

    // ---------------- Rewarded ----------------

    private fun loadRewarded(context: Context, unitId: String) {
        if (unitId.isBlank() || rewardedAds.containsKey(unitId) || !loading.add("rewarded:$unitId")) return
        RewardedAd.load(
            context, unitId, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAds[unitId] = ad
                    loading.remove("rewarded:$unitId")
                    settle("rewarded:$unitId", true)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading.remove("rewarded:$unitId")
                    settle("rewarded:$unitId", false)
                    log("rewarded load failed: ${error.message}")
                }
            }
        )
    }

    private fun showRewarded(activity: Activity, unitId: String, onShown: () -> Unit, onFailed: () -> Unit) {
        val ad = rewardedAds.remove(unitId) ?: return onFailed()
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadRewarded(activity, unitId)
                onShown()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                loadRewarded(activity, unitId)
                onFailed()
            }
        }
        ad.show(activity) { /* reward earned — the sequence proceeds regardless */ }
    }

    // ---------------- Fullscreen native ----------------

    private fun loadNative(context: Context, unitId: String) {
        if (unitId.isBlank() || nativeAds.containsKey(unitId) || !loading.add("native:$unitId")) return
        AdLoader.Builder(context, unitId)
            .forNativeAd { ad ->
                nativeAds.put(unitId, ad)?.destroy()
                loading.remove("native:$unitId")
                settle("native:$unitId", true)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading.remove("native:$unitId")
                    settle("native:$unitId", false)
                    log("native load failed: ${error.message}")
                }
            })
            .build()
            .loadAd(AdRequest.Builder().build())
    }

    private fun showNative(activity: Activity, unitId: String, onShown: () -> Unit, onFailed: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return onFailed()
        val ad = nativeAds.remove(unitId) ?: return onFailed()

        val shown = runCatching {
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val view = LayoutInflater.from(activity)
                .inflate(R.layout.drawer_ad_fullscreen_native, null) as NativeAdView
            bindNative(view, ad)
            view.findViewById<ImageView>(R.id.drawer_native_close).setOnClickListener { dialog.dismiss() }
            dialog.setContentView(view, ViewGroup.LayoutParams(MATCH, MATCH))
            dialog.setOnDismissListener {
                runCatching { ad.destroy() }
                loadNative(activity, unitId)
                onShown()
            }
            dialog.setCancelable(true)
            dialog.show()
            true
        }.getOrElse {
            Log.e(TAG, "native dialog failed", it)
            false
        }

        if (!shown) {
            runCatching { ad.destroy() }
            loadNative(activity, unitId)
            onFailed()
        }
    }

    private fun bindNative(view: NativeAdView, ad: NativeAd) {
        val headline = view.findViewById<TextView>(R.id.drawer_native_headline)
        val body = view.findViewById<TextView>(R.id.drawer_native_body)
        val cta = view.findViewById<Button>(R.id.drawer_native_cta)
        val icon = view.findViewById<ImageView>(R.id.drawer_native_icon)
        val advertiser = view.findViewById<TextView>(R.id.drawer_native_advertiser)
        val media = view.findViewById<MediaView>(R.id.drawer_native_media)

        headline.text = ad.headline
        view.headlineView = headline

        body.text = ad.body.orEmpty()
        body.visibility = if (ad.body.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        view.bodyView = body

        cta.text = ad.callToAction.orEmpty()
        cta.visibility = if (ad.callToAction.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        view.callToActionView = cta

        val iconAsset = ad.icon
        if (iconAsset?.drawable != null) {
            icon.setImageDrawable(iconAsset.drawable)
            icon.visibility = android.view.View.VISIBLE
        } else {
            icon.visibility = android.view.View.GONE
        }
        view.iconView = icon

        advertiser.text = ad.advertiser.orEmpty()
        advertiser.visibility = if (ad.advertiser.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        view.advertiserView = advertiser

        view.mediaView = media
        ad.mediaContent?.let { media.mediaContent = it }

        view.setNativeAd(ad)
    }

    // ---------------- House ad (custom_ads) ----------------

    /**
     * This app's own full-screen house ad over the current screen, with a close button — QRScanner's
     * `custom` fallback step. No house ad (or `IsCustomADS` off) → [onFailed], so the chain moves on.
     */
    private fun showCustom(activity: Activity, onShown: () -> Unit, onFailed: () -> Unit) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return onFailed()
        val overlay = android.widget.FrameLayout(activity).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            elevation = 64f
        }
        val remove = { runCatching { content.removeView(overlay) } }
        val close = ImageView(activity).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            val size = (40 * resources.displayMetrics.density).toInt()
            layoutParams = android.widget.FrameLayout.LayoutParams(
                size, size, android.view.Gravity.TOP or android.view.Gravity.END
            ).apply { topMargin = size; marginEnd = size / 2 }
            setOnClickListener { remove(); onShown() }
        }
        val slot = android.widget.FrameLayout(activity)
        overlay.addView(slot, ViewGroup.LayoutParams(MATCH, MATCH))
        overlay.addView(close)
        content.addView(overlay, ViewGroup.LayoutParams(MATCH, MATCH))
        CustomAdsRegistry().loadCustomAd(
            context = activity,
            container = slot,
            type = CustomAdsRegistry.CustomAdType.FULLSCREEN_NATIVE,
            onFail = { remove(); onFailed() },
        )
    }

    // ---------------- Direct link ----------------

    /**
     * The direct link opens in the Remote-Config open type ([DirectLinkOpener]: WebView / Custom Tab
     * / browser, default WebView), which falls back to `ACTION_VIEW` and ignores a blank / scheme-less
     * link.
     *
     * Returns false when nothing was opened, so the caller can fall through to the next ad rather
     * than sitting waiting for a return from a page that never appeared.
     */
    private fun openLink(activity: Activity, spec: DrawerAdSpec): Boolean {
        val url = pickDirectLink(activity, spec)
        if (url.isBlank()) {
            log("directlink has no usable url — next")
            return false
        }
        val opened = DirectLinkOpener.open(activity, url, DirectLinkOpener.modeOf(spec.openType))
        if (!opened) log("directlink open failed for '$url' — next")
        return opened
    }

    /**
     * The next URL from the slot's rotation, or its single configured URL.
     *
     * Rotating here rather than at parse time matters: the config is read several times per trigger
     * (preload, then run), so advancing on parse would skip most of the list. The pointer is
     * persisted, so the rotation survives a restart instead of always replaying the first link.
     */
    private fun pickDirectLink(activity: Activity, spec: DrawerAdSpec): String {
        val urls = spec.urls
        if (urls.isEmpty()) return spec.adUnitId

        val vault = AdsVault.getInstance(activity)
        val at = vault.getInt(DIRECTLINK_POINTER_KEY, 0).let { if (it in urls.indices) it else 0 }
        vault.putInt(DIRECTLINK_POINTER_KEY, (at + 1) % urls.size)
        log("directlink url ${at + 1}/${urls.size}")
        return urls[at]
    }

    /**
     * Runs [action] once, the next time [activity] comes back to the foreground.
     *
     * The direct link is the only step in the chain that cannot tell us when it is finished with the
     * screen: there is no `onAdDismissed` for a WebView we launched. Watching the launcher's own
     * resume is the signal — it fires whether the user pressed back, swiped away or was taken to the
     * Play Store and returned. If the launcher is destroyed while they are away the flow is simply
     * dropped: they have left, and there is nothing left to continue on.
     */
    fun onReturnTo(activity: Activity, action: () -> Unit) {
        val app = activity.application
        if (app == null) return action()

        val callbacks = object : Application.ActivityLifecycleCallbacks {
            private var spent = false

            private fun release(run: Boolean) {
                if (spent) return
                spent = true
                runCatching { app.unregisterActivityLifecycleCallbacks(this) }
                if (run) action()
            }

            override fun onActivityResumed(a: Activity) {
                if (a === activity) release(run = true)
            }

            override fun onActivityDestroyed(a: Activity) {
                if (a === activity) release(run = false)
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        log("directlink opened — holding the flow until the launcher returns")
    }

    // ---------------- helpers ----------------

    private fun once(block: () -> Unit): () -> Unit {
        var called = false
        return {
            if (!called) {
                called = true
                block()
            }
        }
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private const val ON_DEMAND_TIMEOUT_MS = 8_000L

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
