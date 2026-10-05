package com.calleridapp.admesh.domain

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.calleridapp.numberlookup.BuildConfig
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The few facts every ad class has to agree on, in one place:
 *
 *  - **Consent.** [canRequestAds] is the UMP verdict. No ad of any format is requested before it
 *    is true; that used to depend on each class, and none of them asked.
 *  - **SDK start.** [startSdk] runs `MobileAds.initialize` once per process, only after consent.
 *    It used to run from the splash alone, so a process started on the launcher home (boot, a
 *    Home press after the process died), by a call, or by a push never initialised the SDK or
 *    asked for consent. [ensure] is what non-splash entry points call.
 *  - **A full-screen ad is on screen.** [fullScreenShown] / [fullScreenDismissed] are called by
 *    every full-screen format (Google, Facebook, launcher placements, rewarded, splash), so the
 *    App Open ad never lands on top of one of them, or on the return from its click-out.
 *  - **The app sent the user out itself.** [skipNextAppOpen] (a direct link, a call, a share, a
 *    Settings page) suppresses the App Open on the way back, and expires on its own so a return
 *    that never comes cannot swallow a real one later.
 */
object AdsGate {

    private const val TAG = "AdsGate"

    /** A full-screen ad that never reported its dismissal no longer blocks anything after this. */
    private const val FULL_SCREEN_MAX_MS = 5 * 60_000L

    /** How long an app-initiated trip out may take before its App Open suppression lapses. */
    private const val SKIP_APP_OPEN_MS = 10 * 60_000L

    private val sdkStarted = AtomicBoolean(false)
    private val consentInFlight = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val onReady = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var isSdkReady: Boolean = false
        private set

    /** UMP's verdict: consent obtained or not required. False until the first consent update. */
    fun canRequestAds(context: Context): Boolean = runCatching {
        UserMessagingPlatform.getConsentInformation(context.applicationContext).canRequestAds()
    }.getOrDefault(false)

    /** Starts the Mobile Ads SDK once, if consent allows it. True when it is started (or was). */
    fun startSdk(context: Context): Boolean {
        val app = context.applicationContext
        if (!canRequestAds(app)) {
            log("SDK not started: consent does not allow ad requests")
            return false
        }
        if (sdkStarted.getAndSet(true)) return true
        executor.execute {
            // Meta Audience Network is the fallback network (`IsFail_FB`, `IsAdType=facebook`). It
            // was never initialised - only the core Facebook SDK was - so its first request paid
            // the init cost or failed. Same consent gate as Google, since it is started from here.
            runCatching { com.facebook.ads.AudienceNetworkAds.initialize(app) }
                .onFailure { Log.w(TAG, "AudienceNetworkAds.initialize failed", it) }
            runCatching {
                MobileAds.initialize(app) {
                    isSdkReady = true
                    log("Mobile Ads SDK ready")
                    onReady.forEach { runCatching(it) }
                    onReady.clear()
                }
            }.onFailure {
                sdkStarted.set(false)
                Log.e(TAG, "MobileAds.initialize failed", it)
            }
        }
        return true
    }

    /** Runs [block] once the SDK is up (now, when it already is). */
    fun whenReady(block: () -> Unit) {
        if (isSdkReady) block() else onReady += block
    }

    /**
     * For every entry point but the splash: the consent update (and form, when one is required)
     * and the SDK start, once per process. Safe to call on every resume.
     */
    fun ensure(activity: Activity, onStarted: (() -> Unit)? = null) {
        if (sdkStarted.get()) return
        if (startSdk(activity)) {
            onStarted?.let { whenReady(it) }
            return
        }
        if (!consentInFlight.compareAndSet(false, true)) return
        runCatching {
            GoogleMobileAdsConsentRegistry.getInstance(activity.applicationContext).gatherConsent(activity) { error ->
                consentInFlight.set(false)
                if (error != null) log("consent update failed: ${error.message}")
                if (startSdk(activity)) onStarted?.let { whenReady(it) }
            }
        }.onFailure {
            consentInFlight.set(false)
            Log.w(TAG, "consent request failed", it)
        }
    }

    // ---------------- full-screen state ----------------

    @Volatile
    private var fullScreenSince = 0L

    fun fullScreenShown() {
        fullScreenSince = SystemClock.uptimeMillis()
    }

    fun fullScreenDismissed() {
        fullScreenSince = 0L
    }

    val isFullScreenShowing: Boolean
        get() {
            val since = fullScreenSince
            return since != 0L && SystemClock.uptimeMillis() - since < FULL_SCREEN_MAX_MS
        }

    // ---------------- app-initiated trips out ----------------

    @Volatile
    private var skipAppOpenSince = 0L

    /** The app is sending the user out (link, call, share, Settings): no App Open on the return. */
    fun skipNextAppOpen() {
        skipAppOpenSince = SystemClock.uptimeMillis()
    }

    /** Whether a suppression is pending, without using it up. */
    val isSkippingAppOpen: Boolean
        get() {
            val since = skipAppOpenSince
            return since != 0L && SystemClock.uptimeMillis() - since < SKIP_APP_OPEN_MS
        }

    fun clearSkipAppOpen() {
        skipAppOpenSince = 0L
    }

    /** Reads and clears the suppression. Expired suppressions read false. */
    fun consumeSkipAppOpen(): Boolean {
        val since = skipAppOpenSince
        skipAppOpenSince = 0L
        return since != 0L && SystemClock.uptimeMillis() - since < SKIP_APP_OPEN_MS
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
