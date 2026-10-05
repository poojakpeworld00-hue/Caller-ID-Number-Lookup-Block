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
import com.calleridapp.admesh.domain.AdRevenueMeter
import com.calleridapp.admesh.domain.AdsGate
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

    /** When the chain now running started, 0 when none. See [run]. */
    @Volatile
    private var chainSince = 0L

    /** A chain that never reports back (a lost callback) stops blocking the next one after this. */
    private const val CHAIN_MAX_MS = 60_000L

    // Keyed by ad-unit id: the launcher's placements (leftPanel / rightPanel / drawer) each carry
    // their own unit, and one shared slot per format would serve one placement's ad on another.
    private val interAds = HashMap<String, InterstitialAd>()
    private val appOpenAds = HashMap<String, AppOpenAd>()
    private val rewardedAds = HashMap<String, RewardedAd>()
    private val nativeAds = HashMap<String, NativeAd>()

    private val loading = HashSet<String>()

    /** When each cached ad loaded ("<type>:<unit>"), so a stale one is dropped instead of shown. */
    private val loadedAt = HashMap<String, Long>()

    private fun maxAgeMs(type: String) = if (type == "appopen") 4 * 60 * 60_000L else 60 * 60_000L

    private fun stamp(key: String) { loadedAt[key] = android.os.SystemClock.elapsedRealtime() }

    /** True when [map] holds a fresh ad for [unitId]; a stale one is removed (and destroyed). */
    private fun <T> fresh(map: HashMap<String, T>, type: String, unitId: String): Boolean {
        if (!map.containsKey(unitId)) return false
        val at = loadedAt["$type:$unitId"] ?: 0L
        if (android.os.SystemClock.elapsedRealtime() - at <= maxAgeMs(type)) return true
        log("$type $unitId expired - dropped")
        (map.remove(unitId) as? NativeAd)?.destroy()
        return false
    }

    /** Loads every loadable format in the sequence that is not already in hand. */
    fun preload(context: Context, flow: DrawerAdFlow) {
        // No consent, no requests. On-demand callers then see "not ready" and move on.
        if (!AdsGate.canRequestAds(context)) return
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
        DrawerAdType.INTER -> fresh(interAds, "inter", spec.adUnitId)
        DrawerAdType.APPOPEN -> fresh(appOpenAds, "appopen", spec.adUnitId)
        DrawerAdType.REWARDED -> fresh(rewardedAds, "rewarded", spec.adUnitId)
        DrawerAdType.FULLSCREEN_NATIVE -> fresh(nativeAds, "native", spec.adUnitId)
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
        // No consent: preload() would return without a request and nothing would ever answer, so the
        // black loader sat up for the whole timeout. Fail at once instead.
        if (!AdsGate.canRequestAds(context)) return then(false)
        // The user tapped and is now waiting on the network: the loading spinner covers that wait
        // when `inter_loader` is on, and comes down however the wait ends (loaded, failed, timeout).
        val activity = context as? Activity
        if (activity != null) {
            com.calleridapp.admesh.presentation.oninterAds.FullScreenSpinner.show(
                activity,
                com.calleridapp.admesh.presentation.oninterAds.InterLoader.enabled(AdsVault.getInstance(activity)),
            )
        }
        var answered = false
        val once: (Boolean) -> Unit = { ok ->
            if (!answered) {
                answered = true
                if (activity != null) com.calleridapp.admesh.presentation.oninterAds.FullScreenSpinner.hide()
                then(ok)
            }
        }
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
        // One chain at a time, and never over an ad that is already on screen. Two chains were being
        // started for one moment (the grant ad and the screen's own exit ad), and the second one's
        // rewarded step then came up over the first one's interstitial / native.
        val now = android.os.SystemClock.uptimeMillis()
        val chainBusy = chainSince != 0L && now - chainSince < CHAIN_MAX_MS
        if (chainBusy || AdsGate.isFullScreenShowing || com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal.isInterShow) {
            log("another full-screen ad is running — proceeding without one")
            return proceed()
        }
        chainSince = now
        val done = once {
            chainSince = 0L
            proceed()
        }
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
                    if (ok && !activity.isFinishing && !activity.isDestroyed && !isInFront(activity)) {
                        // The user left while it loaded (Home, Recents, another app): showing it now
                        // would put a full-screen ad over a screen they are not on. Kept for next time.
                        log("${spec.type.key} loaded after the screen left — not shown")
                        done()
                    } else if (ok && !activity.isFinishing && !activity.isDestroyed) {
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
        // This attempt ends exactly once: shown, failed to show, or threw. A show() that throws and
        // then also reports onAdFailedToShow used to advance the chain twice, skipping a step — and
        // landing on rewarded while the ad before it was still up.
        var settled = false
        // Where the ad runs, for the `screen` param of its events: the chain's pointer key, trimmed.
        val place = pointerKey.trim('_').removePrefix("launcher_").removeSuffix("_seq_ptr").take(36)
        val next: () -> Unit = {
            if (!settled) {
                settled = true
                com.calleridapp.numberlookup.util.Analytics.adEvent(spec.type.key, "failed", place)
                attempt(activity, flow, order, k + 1, pointerKey, done)
            }
        }
        // Chaining full-screen ads back to back needs a beat: the dismissal callback fires while the
        // previous ad's activity is still finishing, and showing into that window is refused.
        val afterShown: () -> Unit = {
            if (!settled) {
                settled = true
                com.calleridapp.numberlookup.util.Analytics.adEvent(spec.type.key, "close", place)
                if (flow.showAll) mainHandler.postDelayed(
                    { attempt(activity, flow, order, k + 1, pointerKey, done) }, CHAIN_GAP_MS
                ) else done()
            }
        }
        log("attempting ${spec.type.key} (idx=$idx)${if (flow.showAll) " [show_all]" else ""}")
        com.calleridapp.numberlookup.util.Analytics.adEvent(spec.type.key, "show", place)

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
            // A show() that threw never reports back; the gate it raised has to come down here, or
            // every App Open and chain after it is held off until the gate expires.
            AdsGate.fullScreenDismissed()
            next()
        }
    }

    /** Whether [activity] is the resumed screen, i.e. the user is still looking at it. */
    private fun isInFront(activity: Activity): Boolean =
        (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState
            ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            ?: activity.hasWindowFocus()

    // ---------------- Interstitial ----------------

    private fun loadInter(context: Context, unitId: String) {
        if (unitId.isBlank() || fresh(interAds, "inter", unitId) || !loading.add("inter:$unitId")) return
        InterstitialAd.load(
            context, unitId, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interAds[unitId] = ad
                    stamp("inter:$unitId")
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
        ad.setOnPaidEventListener { AdRevenueMeter.logPaidEvent(activity, it) }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                AdsGate.fullScreenDismissed()
                loadInter(activity, unitId)
                onShown()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                AdsGate.fullScreenDismissed()
                loadInter(activity, unitId)
                onFailed()
            }
        }
        AdsGate.fullScreenShown()
        ad.show(activity)
    }

    // ---------------- App Open ----------------

    private fun loadAppOpen(context: Context, unitId: String) {
        if (unitId.isBlank() || fresh(appOpenAds, "appopen", unitId) || !loading.add("appopen:$unitId")) return
        AppOpenAd.load(
            context, unitId, AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAds[unitId] = ad
                    stamp("appopen:$unitId")
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
        ad.setOnPaidEventListener { AdRevenueMeter.logPaidEvent(activity, it) }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                AdsGate.fullScreenDismissed()
                loadAppOpen(activity, unitId)
                onShown()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                AdsGate.fullScreenDismissed()
                loadAppOpen(activity, unitId)
                onFailed()
            }
        }
        AdsGate.fullScreenShown()
        ad.show(activity)
    }

    // ---------------- Rewarded ----------------

    private fun loadRewarded(context: Context, unitId: String) {
        if (unitId.isBlank() || fresh(rewardedAds, "rewarded", unitId) || !loading.add("rewarded:$unitId")) return
        RewardedAd.load(
            context, unitId, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAds[unitId] = ad
                    stamp("rewarded:$unitId")
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
        ad.setOnPaidEventListener { AdRevenueMeter.logPaidEvent(activity, it) }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                AdsGate.fullScreenDismissed()
                loadRewarded(activity, unitId)
                onShown()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                AdsGate.fullScreenDismissed()
                loadRewarded(activity, unitId)
                onFailed()
            }
        }
        AdsGate.fullScreenShown()
        ad.show(activity) { /* reward earned — the sequence proceeds regardless */ }
    }

    // ---------------- Fullscreen native ----------------

    private fun loadNative(context: Context, unitId: String) {
        if (unitId.isBlank() || fresh(nativeAds, "native", unitId) || !loading.add("native:$unitId")) return
        AdLoader.Builder(context, unitId)
            .forNativeAd { ad ->
                nativeAds.put(unitId, ad)?.destroy()
                stamp("native:$unitId")
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
            ad.setOnPaidEventListener { AdRevenueMeter.logPaidEvent(activity, it) }
            view.findViewById<ImageView>(R.id.drawer_native_close).setOnClickListener { dialog.dismiss() }
            dialog.setContentView(view, ViewGroup.LayoutParams(MATCH, MATCH))
            dialog.setOnDismissListener {
                AdsGate.fullScreenDismissed()
                runCatching { ad.destroy() }
                loadNative(activity, unitId)
                onShown()
            }
            dialog.setCancelable(true)
            AdsGate.fullScreenShown()
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
