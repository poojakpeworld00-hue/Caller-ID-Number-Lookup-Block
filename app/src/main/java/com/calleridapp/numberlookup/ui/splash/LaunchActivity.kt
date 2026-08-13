package com.calleridapp.numberlookup.ui.splash

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.lighthouse.push.LightHouse
import io.lighthouse.push.extended.LightHouseRichPush
import com.calleridapp.admesh.data.OnDataReady
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.AppOpenAdRegistry
import com.calleridapp.admesh.presentation.showAppRedirectPopup
import com.calleridapp.numberlookup.ui.ShellActivity
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.numberlookup.data.VaultRegistry
import com.calleridapp.numberlookup.databinding.ActivitySplashBinding
import com.calleridapp.numberlookup.launcher.activities.OnboardingWelcomeActivity
import com.calleridapp.numberlookup.launcher.helpers.LauncherFlow
import com.calleridapp.numberlookup.ui.intro.IntroRevealPolicy
import com.calleridapp.numberlookup.ui.language.LocaleActivity
import com.calleridapp.numberlookup.ui.onboarding.IntroActivity
import com.calleridapp.numberlookup.ui.terms.ConsentActivity
import com.calleridapp.numberlookup.util.openActivity
import java.security.MessageDigest

/**
 * Entry point. Shows branding briefly, then routes to the correct screen
 * based on first-run / onboarding state.
 */
class LaunchActivity : HostActivity<ActivitySplashBinding>() {

    override val layoutId: Int = R.layout.activity_splash
    private val handler = Handler(Looper.getMainLooper())

    /** Running splash animators, cancelled in onDestroy so nothing leaks. */
    private val splashAnimators = mutableListOf<Animator>()

    private val prefs by lazy { VaultRegistry(this) }

    // Ensures we navigate exactly once, whether the win comes from getData's
    // callback or the watchdog below.
    private val proceeded = java.util.concurrent.atomic.AtomicBoolean(false)

    // Navigation waits on BOTH gates: the getData chain must be ready AND the
    // intro animation must have played long enough. maybeProceed() fires only
    // when both are set, so a fast getData never cuts the animation short.
    private val dataReady = java.util.concurrent.atomic.AtomicBoolean(false)
    private val animMinElapsed = java.util.concurrent.atomic.AtomicBoolean(false)

    private companion object {
        // Shared with ADDashboardActivity's tracing. Filter: `adb logcat -s SplashFlow`
        const val SPLASH_FLOW_TAG = "SplashFlow"

        // Minimum time the intro animation is allowed to play before we navigate,
        // even when getData is ready sooner. Covers the reveal (icon → title →
        // the three verdicts pop in, last one lands ~3.1s) plus a short hold.
        const val ANIM_MIN_MS = 3600L

        // Shorter floor when the user has animations disabled — the scene is shown
        // as a static final frame, so there's nothing to wait for.
        const val ANIM_MIN_REDUCED_MS = 700L

        // Hard upper bound on how long the splash may wait on the getData chain
        // (consent → remote config → geo → permissions). If no callback fires by
        // then, force navigation so the splash can never hang forever. Tune as
        // needed: lower = snappier worst case, but a slow network may skip the
        // runtime permission prompt for that session (requested next launch).
        const val WATCHDOG_TIMEOUT_MS = 20_000L
    }

    override fun initView() {

        // Rich-push cold start: if this launch came from a push routed via the
        // launcher, hand off to the rich-push activity and skip the splash flow.
        if (LightHouseRichPush.handleFromSplash(this, binding.root)) return

        // One session = one cold start. Bump before nextScreen() so the intro
        // `app_launches` frequency counts this launch.
        prefs.appLaunchCount = prefs.appLaunchCount + 1

        // Tint the status bar to the hero gradient's own top colour — the same green the
        // app icon is built from — with light (white) icons to match the splash's
        // light-on-color content. Using the gradient token rather than the app-wide
        // `primary` keeps the top edge seamless with the artwork underneath it.
        window.statusBarColor = ContextCompat.getColor(this, R.color.splash_grad_start)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        // Edge-to-edge (default on Android 15+): pad the root by the system-bar
        // insets so the title/footer never sit under the status or navigation bar
        // (incl. the Android 16 gesture pill). The gradient still draws full-bleed
        // because a View's background fills its padding.
        ViewCompat.setOnApplyWindowInsetsListener(binding.splashRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Footer "Secure vX.Y" — sourced from the build so it tracks the real
        // app version instead of a hardcoded string.
        binding.tvSecureVersion.text =
            getString(R.string.splash_secure_version, BuildConfig.VERSION_NAME)

        // Play the redesigned splash animation, then let it finish before we move
        // on: the animation-min gate below (animMinElapsed) holds navigation until
        // the intro has played, and getData() supplies the second gate.
        setupSplashAnimation()
        val animMin = if (animationsDisabled()) ANIM_MIN_REDUCED_MS else ANIM_MIN_MS
        handler.postDelayed({
            Log.d(SPLASH_FLOW_TAG, "animation min elapsed (${animMin}ms) → maybeProceed")
            animMinElapsed.set(true)
            maybeProceed()
        }, animMin)

        printHashKey(this)
        Log.d(SPLASH_FLOW_TAG, "SplashActivity.initView → getData()")
        // Hand off to the ad module. It runs consent + SDK init + runtime
        // permission prompts + native/banner/interstitial preloads, then
        // fires one of the two callbacks below when it's time to move on.
        getData(this, true, object : OnDataReady {
            override fun onSuccess() {
                Log.d(SPLASH_FLOW_TAG, "getData onSuccess → dataReady")
                AppOpenAdRegistry.loadAd(this@LaunchActivity)
                dataReady.set(true)
                maybeProceed()
            }

            override fun onError() {
                Log.d(SPLASH_FLOW_TAG, "getData onError → dataReady")
                dataReady.set(true)
                maybeProceed()
            }
        })

//        // Watchdog: if the getData chain never calls back (a hung native step),
//        // force the splash forward so it can't stall indefinitely.
//        handler.postDelayed({
//            if (!proceeded.get()) {
//                Log.w(SPLASH_FLOW_TAG, "watchdog fired after ${WATCHDOG_TIMEOUT_MS}ms — forcing navigation")
//                proceedNow()
//            }
//        }, WATCHDOG_TIMEOUT_MS)
    }

    /** Navigate only once BOTH gates are met: data ready AND the intro has played. */
    private fun maybeProceed() {
        if (dataReady.get() && animMinElapsed.get()) {
            Log.d(SPLASH_FLOW_TAG, "both gates met (data + animation) → proceedNow")
            proceedNow()
        }
    }

    private fun proceedNow() {
        // Win exactly once — callback or watchdog, whichever lands first.
        if (!proceeded.compareAndSet(false, true)) {
            Log.d(SPLASH_FLOW_TAG, "proceedNow ignored (already proceeded)")
            return
        }
        handler.removeCallbacksAndMessages(null) // cancel the watchdog
        if (isFinishing || isDestroyed) {
            Log.d(SPLASH_FLOW_TAG, "proceedNow skipped (finishing/destroyed)")
            return
        }
        val redirectLink = AdsVault.getInstance(this).getString("In_App_Update_Link")
        if (!redirectLink.isNullOrEmpty()) {
            Log.d(SPLASH_FLOW_TAG, "proceedNow → showAppRedirectPopup")
            // After the redirect popup is dismissed, launch the next screen.
            showAppRedirectPopup { launchNext() }
        } else {
            Log.d(SPLASH_FLOW_TAG, "proceedNow → launchNext")
            launchNext()
        }
    }

    private fun launchNext() {
        if (isFinishing || isDestroyed) {
            Log.d(SPLASH_FLOW_TAG, "launchNext skipped (finishing/destroyed)")
            return
        }
        Log.d(SPLASH_FLOW_TAG, "launchNext → ensureDataDisclosure")
        // The LightHouse data-disclosure screen briefly sends the app
        // background→foreground. That foreground event reaches
        // AppOpenAdRegistry (via LookupShellApp.handleAppForeground) and,
        // because we've navigated off the excluded LaunchActivity by then, would
        // pop the returning-app App Open ad right after the disclosure — in the
        // middle of the first-launch flow. Suppress it for this one trip (the
        // splash's own splash-ad path already handles any intended splash ad).
        AppOpenAdRegistry.skipNextAppOpenAd = true
        LightHouse.ensureDataDisclosure(this) {
            if (isFinishing || isDestroyed) return@ensureDataDisclosure
            LightHouse.subscribeAsync()
            // Read before nextScreen(): starting the launcher's first run can decide the
            // whole order has nothing to show and mark onboarding completed on the spot.
            val launcherOnboarding = !LauncherFlow.wasOnboardingCompleted(this)
            val next = nextScreen()
            Log.d(SPLASH_FLOW_TAG, "ensureDataDisclosure done → launching ${next.simpleName}")
            // The first-run marker tells Intro and Language they are steps in the launcher's
            // sequence rather than the caller-ID app's own gated screens — it matters when
            // the order starts on one of them.
            val intent = if (launcherOnboarding && next != LauncherFlow.homeActivity()) {
                LauncherFlow.onboardingIntent(this, next)
            } else {
                Intent(this, next)
            }
            // Splash → onboarding/main — no interstitial on the very first launch.
            openActivity(intent, isShowAd = false)
            finish()
        }
    }

    // ─────────────────────────── Splash animation (UI only) ───────────────────────────
    // Logo pop + breathing rings → title/tagline rise-in → a self-typing demo number
    // with a blinking cursor → a radar scan pulse → a verdict card that springs up.
    // Purely decorative: it never drives navigation (that stays owned by getData()).

    /** Base offsets (dp) of the three caller-info elements from the composition centre.
     *  Tuned so the widest pill ("Fraud blocked") keeps a comfortable margin from the
     *  screen edge. */
    private val orbitOffsets by lazy {
        listOf(
            binding.incomingWrap to (dp(-52f) to dp(-92f)),
            binding.fraudWrap to (dp(-52f) to dp(92f)),
            binding.avatarWrap to (dp(96f) to dp(24f)),
        )
    }

    private fun alive() = !isFinishing && !isDestroyed

    /** True when the user has turned system animations off ("Remove animations"). */
    private fun animationsDisabled() = Settings.Global.getFloat(
        contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f

    private fun setupSplashAnimation() {
        // Seat the orbiting elements around the icon.
        orbitOffsets.forEach { (v, off) ->
            v.translationX = off.first
            v.translationY = off.second
        }

        if (animationsDisabled()) {
            // Accessibility / "remove animations": jump straight to the final frame.
            listOf(
                binding.composition, binding.iconTile, binding.chipIncoming,
                binding.chipFraud, binding.avatarInner, binding.tvAppName,
                binding.tvTagline, binding.progressTrack, binding.footer
            ).forEach { it.alpha = 1f }
            binding.scanRing.alpha = 0.9f
            binding.progressTrack.post {
                if (alive()) setFillWidth((binding.progressTrack.width * 0.94f).toInt())
            }
            return
        }

        // Composition fades in; the app icon pops + then breathes; the orbit ring pulses.
        start(fadeIn(binding.composition, delay = 0L, dur = 460L))
        binding.iconTile.scaleX = 0.8f
        binding.iconTile.scaleY = 0.8f
        start(fadeScaleIn(binding.iconTile, delay = 0L, dur = 570L))
        handler.postDelayed({ if (alive()) start(breathe(binding.iconTile, delay = 0L)) }, 620L)
        handler.postDelayed({ if (alive()) start(orbitBreathe(binding.orbitRing)) }, 800L)

        // Title + tagline rise in; footer + progress bar fade in and the bar fills.
        start(riseIn(binding.tvAppName, delay = 250L, dyDp = 14f))
        start(riseIn(binding.tvTagline, delay = 330L, dyDp = 14f))
        start(fadeIn(binding.footer, delay = 300L, dur = 520L))
        start(fadeIn(binding.progressTrack, delay = 300L, dur = 520L))
        startProgressFill(delay = 340L)

        // The caller-info verdicts pop in one after another; the AI scan ring sweeps.
        popIn(binding.chipIncoming, delay = 630L)
        handler.postDelayed({ if (alive()) startScanSpin() }, 880L)
        popIn(binding.chipFraud, delay = 2140L)
        popIn(binding.avatarInner, delay = 2650L)

        // Once they've landed, a gentle vertical float keeps the scene alive. (No full
        // orbit — a 360° sweep drags the wide pills off the screen edges.)
        floatLoop(binding.chipIncoming, delay = 1400L, dyDp = 5f, dur = 3000L)
        floatLoop(binding.chipFraud, delay = 2700L, dyDp = -5f, dur = 3400L)
        floatLoop(binding.avatarInner, delay = 3200L, dyDp = 5f, dur = 3800L)
    }

    /** Starts [anim] and tracks it so onDestroy can cancel it. */
    private fun start(anim: Animator) {
        splashAnimators += anim
        anim.start()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun fadeScaleIn(v: View, delay: Long, dur: Long) = AnimatorSet().apply {
        startDelay = delay
        duration = dur
        interpolator = OvershootInterpolator(1.4f)
        playTogether(
            ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f),
            ObjectAnimator.ofFloat(v, View.SCALE_X, 0.8f, 1f),
            ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.8f, 1f),
        )
    }

    private fun fadeIn(v: View, delay: Long, dur: Long) =
        ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).apply {
            startDelay = delay
            duration = dur
        }

    private fun riseIn(v: View, delay: Long, dyDp: Float) = AnimatorSet().apply {
        startDelay = delay
        duration = 420L
        interpolator = OvershootInterpolator(1.1f)
        playTogether(
            ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f),
            ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, dp(dyDp), 0f),
        )
    }

    private fun breathe(v: View, delay: Long) = ValueAnimator.ofFloat(1f, 1.08f).apply {
        startDelay = delay
        duration = 1600L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener {
            val s = it.animatedValue as Float
            v.scaleX = s
            v.scaleY = s
        }
    }

    /** Springy pop-in (scale 0.4 → 1 + fade) for a caller-info verdict element. */
    private fun popIn(v: View, delay: Long) {
        v.scaleX = 0.4f
        v.scaleY = 0.4f
        v.alpha = 0f
        start(AnimatorSet().apply {
            startDelay = delay
            duration = 460L
            interpolator = OvershootInterpolator(2.2f)
            playTogether(
                ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(v, View.SCALE_X, 0.4f, 1f),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.4f, 1f),
            )
        })
    }

    /** The dashed orbit ring gently pulses (scale + fade) forever. */
    private fun orbitBreathe(v: View) = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 5000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener {
            val p = it.animatedValue as Float
            v.scaleX = 1f + 0.05f * p
            v.scaleY = 1f + 0.05f * p
            v.alpha = 0.85f - 0.35f * p
        }
    }

    /** Blue AI-scan ring: fades in, then spins continuously (the "scanning" beat). */
    private fun startScanSpin() {
        binding.scanRing.alpha = 0f
        start(ObjectAnimator.ofFloat(binding.scanRing, View.ALPHA, 0f, 1f).apply { duration = 420L })
        start(ObjectAnimator.ofFloat(binding.scanRing, View.ROTATION, 0f, 360f).apply {
            duration = 2600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        })
    }

    /** Endless gentle vertical bob for a floating element. */
    private fun floatLoop(v: View, delay: Long, dyDp: Float, dur: Long) {
        start(ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, dp(dyDp), 0f).apply {
            startDelay = delay
            duration = dur
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        })
    }

    /** Sets the progress fill's width in px (keeps its rounded ends crisp). */
    private fun setFillWidth(px: Int) {
        val lp = binding.progressFill.layoutParams
        lp.width = px
        binding.progressFill.layoutParams = lp
    }

    /** Grows the progress fill by animating its real width (not scaleX, which would
     *  stretch the rounded ends into a lens) from a nub to ~94% of the track. */
    private fun startProgressFill(delay: Long) {
        binding.progressTrack.post {
            if (!alive()) return@post
            val track = binding.progressTrack.width
            if (track <= 0) return@post
            val from = binding.progressFill.width.coerceAtLeast(dp(8f).toInt())
            val to = (track * 0.94f).toInt()
            start(ValueAnimator.ofInt(from, to).apply {
                startDelay = delay
                duration = 4200L
                interpolator = DecelerateInterpolator()
                addUpdateListener { setFillWidth(it.animatedValue as Int) }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        splashAnimators.forEach { it.cancel() }
        splashAnimators.clear()
        handler.removeCallbacksAndMessages(null)
    }
    /**
     * Picks the next screen in the launch flow.
     *
     * Language, Terms and Onboarding are all gated by [IntroRevealPolicy] (the
     * `intro_display` Remote Config block: per-screen `prompt_frequency` = always |
     * once | every_days | app_launches | never, with `prompt_interval`).
     */
    private fun nextScreen(): Class<*> = when {
        // The launcher's own onboarding runs once, ahead of everything else, and ends on the
        // launcher home screen. Its sequence comes from `launcher_ads.onboarding.order` and
        // defaults to Welcome → set as default → Intro → Language; firstScreen() returns the
        // first step that still has something to do.
        !LauncherFlow.wasOnboardingCompleted(this) -> LauncherFlow.firstScreen(this)
        IntroRevealPolicy.shouldShowLanguage(this) -> LocaleActivity::class.java
        IntroRevealPolicy.shouldShowTerms(this) -> ConsentActivity::class.java
        IntroRevealPolicy.shouldShowOnboarding(this) -> IntroActivity::class.java
        else -> LauncherFlow.homeActivity()
    }
    private fun route() {
        val next = when {
            !prefs.isLanguageSelected -> LocaleActivity::class.java
            !prefs.isTermsAccepted -> ConsentActivity::class.java
            !prefs.isOnboardingDone -> IntroActivity::class.java
            else -> ShellActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun printHashKey(context: Context) {
        try {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                info.signingInfo?.apkContentsSigners
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            } ?: return

            signatures.forEach { sig ->
                val md = MessageDigest.getInstance("SHA")
                md.update(sig.toByteArray())
                Log.d("KeyHash", Base64.encodeToString(md.digest(), Base64.DEFAULT))
            }
        } catch (e: Exception) {
        }
    }
}
