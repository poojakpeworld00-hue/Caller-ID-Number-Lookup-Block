package com.calleridapp.numberlookup.ui.language

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.numberlookup.data.RegionLocator
import com.calleridapp.numberlookup.data.LocaleRegistry
import com.calleridapp.numberlookup.data.VaultRegistry
import com.calleridapp.numberlookup.databinding.ActivityLanguageBinding
import com.calleridapp.numberlookup.launcher.helpers.LauncherFlow
import com.calleridapp.numberlookup.permission.AccessEngine
import com.calleridapp.numberlookup.permission.fsi.FullScreenAccess
import com.calleridapp.numberlookup.permission.fsi.FullScreenAccessActivity
import com.calleridapp.numberlookup.ui.ShellActivity
import com.calleridapp.numberlookup.ui.intro.IntroRevealConfig
import com.calleridapp.numberlookup.ui.intro.IntroRevealPolicy
import com.calleridapp.numberlookup.ui.onboarding.IntroActivity
import com.calleridapp.numberlookup.ui.terms.ConsentActivity
import com.calleridapp.numberlookup.util.AppVault
import com.calleridapp.numberlookup.util.GuardRail
import kotlinx.coroutines.launch
import com.calleridapp.numberlookup.util.followAdContainer

class LocaleActivity : HostActivity<ActivityLanguageBinding>() {

    override val layoutId: Int = R.layout.activity_language

    private val viewModel: LocaleViewModel by viewModels()
    private val prefs by lazy { VaultRegistry(this) }

    /** True when opened from Settings to change language (vs. the first-run flow). */
    private val standalone by lazy { intent.getBooleanExtra(EXTRA_STANDALONE, false) }

    // Two lists share one selection: a compact "Suggested" group and the full
    // "All languages" group. Both adapters observe the same selectedTag.
    private lateinit var suggestedAdapter: LocaleAdapter
    private lateinit var allAdapter: LocaleAdapter

    /** One-shot guard so a back-press can't fire the forward flow twice. */
    private var forwarding = false

    /**
     * One-shot latch on the navigation itself. Back and Continue both reach it, and the ad
     * callback can arrive late — a second pass would start the next screen twice.
     */
    private var navigated = false

    override fun initView() {
        // Count this as an intro show only in the first-run flow (not when opened
        // from Settings to change language) — drives the once/count frequency gate.
        if (!standalone) IntroRevealPolicy.markShown(this, IntroRevealConfig.LANGUAGE)

        ViewCompat.setOnApplyWindowInsetsListener(binding.languageRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Current language comes from AppVault (the store HostActivity.applyLocale reads).
        // First launch (no saved language) → "Default" (follow system).
        val current = AppVault.language(this) ?: AppVault.LANGUAGE_DEFAULT
        viewModel.init(current)

        // Ad frame above the Continue button, `launcher_ads.onboarding.language.slot` — a big
        // native unless Remote Config switches it to a banner or turns it off.
        LauncherAdsConfig.showSlot(
            activity = this,
            slot = LauncherAdsConfig.onboardingSlot(this, LauncherAdsConfig.OnboardScreen.LANGUAGE),
            container = binding.adNativeFrame,
            shimmer = binding.adShimmer,
        )
        binding.adNativeDivider.followAdContainer(binding.adNativeFrame)

        // 1) Resolve the region FIRST, before the lists exist. The device seed is
        //    synchronous, so viewModel.suggested/others already hold the correct,
        //    region-specific groups by the time the adapters observe them — the
        //    Suggested list is right on the very first frame (no default flash).
        //    The IP refine is async and updates the lists if it disagrees.
        resolveRegion()

        // 2) Now build the lists. The adapters observe suggested/others (wired in
        //    initObservers), so they render the region-correct list immediately.
        val onPick: (LocaleItem) -> Unit = { viewModel.select(it.tag) }
        suggestedAdapter = LocaleAdapter(onPick).apply { setCurrent(current) }
        allAdapter = LocaleAdapter(onPick).apply { setCurrent(current) }

        binding.rvSuggested.layoutManager = LinearLayoutManager(this)
        binding.rvSuggested.adapter = suggestedAdapter

        binding.rvLanguages.layoutManager = LinearLayoutManager(this)
        binding.rvLanguages.adapter = allAdapter

        binding.btnBack.setOnClickListener { goBack() }
        binding.btnInfo.setOnClickListener { showInfoDialog() }
        binding.btnContinue.setOnClickListener { onContinue() }

        // First-run flow: the system back must NOT exit the app — advance forward
        // exactly like Continue. The callback stays enabled so back never falls
        // through to HostActivity's exit handler; `forwarding` blocks re-entry.
        // Standalone (opened from Settings) keeps the normal back = return.
        if (!standalone) {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (forwarding) return
                    forwarding = true
                    onContinue()
                }
            })
        }
    }

    override fun initObservers() {
        viewModel.selectedTag.observe(this) { tag ->
            suggestedAdapter.setSelected(tag)
            allAdapter.setSelected(tag)
            popConfirm()
        }
        // GEO-driven groups: Suggested reflects the user's region, All holds the rest.
        viewModel.suggested.observe(this) { suggestedAdapter.submitList(it) }
        viewModel.others.observe(this) { allAdapter.submitList(it) }
    }

    /**
     * Resolves the region that drives the Suggested group, checking the country
     * BEFORE the lists are populated:
     *  1. Seed synchronously from the device (SIM/network/locale) — offline, instant,
     *     so the first rendered list is already region-correct.
     *  2. Refine asynchronously from IP geo; updates the lists only if it differs
     *     (applyCountry is idempotent per country).
     */
    private fun resolveRegion() {
        val device = deviceCountry()
        GuardRail.log(TAG, "resolveRegion: device=$device (sync seed)")
        viewModel.applyCountry(device)
        detectCountryByIp()
    }

    /**
     * The device's region as an ISO-3166 alpha-2 code, preferring the SIM/network
     * country (strongest offline geo signal) and falling back to the app locale.
     * Null when nothing usable is available.
     */
    private fun deviceCountry(): String? {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = tm?.simCountryIso?.takeIf { it.isNotBlank() }
        val network = tm?.networkCountryIso?.takeIf { it.isNotBlank() }
        val locale = resources.configuration.locales[0].country.takeIf { it.isNotBlank() }
        return (sim ?: network ?: locale)?.uppercase()
    }

    /** Best-effort IP geolocation to refine the suggested languages for this region. */
    private fun detectCountryByIp() {
        lifecycleScope.launch {
            val geo = RegionLocator.detectCountry(this@LocaleActivity) ?: run {
                GuardRail.log(TAG, "IP geo unavailable → keeping device seed")
                return@launch
            }
            GuardRail.log(TAG, "IP refine → country=${geo.iso}")
            viewModel.applyCountry(geo.iso)
        }
    }

    /** Small spring on the confirm button each time the selection changes. */
    private fun popConfirm() {
        binding.btnContinue.animate().cancel()
        binding.btnContinue.scaleX = 0.8f
        binding.btnContinue.scaleY = 0.8f
        binding.btnContinue.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(3f))
            .setDuration(260L)
            .start()
    }

    private fun showInfoDialog() {
        val view =
            layoutInflater.inflate(R.layout.dialog_language_info, binding.languageRoot, false)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<View>(R.id.btnGotIt).setOnClickListener { dialog.dismiss() }

        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun onContinue() {
        val tag = viewModel.selectedTag.value ?: AppVault.LANGUAGE_DEFAULT
        AppVault.setLanguage(this, tag)  // source of truth for Splash + HostActivity.applyLocale
        prefs.isLanguageSelected = true

        // Opened from Settings: just apply and return; don't drive the first-run flow.
        if (standalone) {
            LocaleRegistry.apply(tag) // recreates activities with the new locale
            finish()
            return
        }

        // First-run flow. Show the permission(s) FIRST, then apply the locale and
        // navigate in the completion callback. Applying the locale recreates this
        // Activity, and finishing it early tears it down — either one aborts an
        // in-flight permission request (that's why nothing showed and the app
        // closed). So we defer BOTH until the engine reports it's done.
        AccessEngine.check(this) {
            // Reached as the launcher's final onboarding step (Intro → Language → Home):
            // everything ahead of it already ran, so go straight to the home screen.
            // Otherwise mirror Splash's routing — Terms and Onboarding both follow the
            // same IntroRevealPolicy frequency gate.
            val next = when {
                // In the launcher's first run the order decides what follows — usually
                // nothing, so this resolves to the home screen, but a reordered flow can put
                // another step (a second default-home ask) after the language picker.
                !standalone && LauncherFlow.isOnboardingActive(this) ->
                    LauncherFlow.nextActivity(this)
                IntroRevealPolicy.shouldShowTerms(this) -> ConsentActivity::class.java
                IntroRevealPolicy.shouldShowOnboarding(this) -> IntroActivity::class.java
                else -> LauncherFlow.homeActivity()
            }
            // Landing on the home screen means the first run is over. Recorded here rather
            // than at navigation time because the full-screen-intent prompt below may still
            // sit between this screen and the home screen.
            if (next == LauncherFlow.homeActivity()) {
                LauncherFlow.markOnboardingCompleted(this)
            }
            // Conditional Full-Screen-Intent Screen: when the Remote Config gate
            // passes, it shows here (after Language) and then continues to `next`.
            // It rebuilds the intent from a class name, so a launcher step reached
            // through it arrives without the first-run marker — which is why those
            // screens test LauncherFlow.isOnboardingActive rather than the marker alone.
            val intent = when {
                FullScreenAccess.shouldShowScreen(this) ->
                    FullScreenAccessActivity.newIntent(this, next)

                !standalone && LauncherFlow.isOnboardingActive(this) &&
                        next != LauncherFlow.homeActivity() ->
                    LauncherFlow.onboardingIntent(this, next)

                else -> Intent(this, next)
            }
            // Permission done → show this screen's interstitial (`onboarding.language.
            // inter_enabled`, on by default; the callback fires immediately when there
            // is nothing to show) → THEN apply the locale and navigate. LocaleRegistry
            // .apply recreates this Activity, so it must run after the ad (doing it
            // earlier would tear the ad down).
            LauncherAdsConfig.runOnboardingInter(this, LauncherAdsConfig.OnboardScreen.LANGUAGE) {
                if (navigated) {
                    return@runOnboardingInter
                }
                navigated = true

                // Navigate BEFORE applying the locale. Applying it restarts the app's
                // activities, and doing that first raced the start: the system tore this
                // screen down while the next one was still being dispatched, so the run
                // stopped dead on the language picker — intermittently, because the ad
                // dismissal decides where in the resume the two land. Starting first means
                // the restart falls on the screen that is already on its way in, which is
                // the one that should be redrawn in the new language anyway.
                startActivity(intent)
                if (tag != AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
                    LocaleRegistry.apply(tag)
                }
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "LocaleActivity"
        private const val EXTRA_STANDALONE = "extra_standalone"

        /** Standalone = opened from Settings to change language (returns on Continue). */
        fun newIntent(context: Context, standalone: Boolean = false): Intent =
            Intent(context, LocaleActivity::class.java)
                .putExtra(EXTRA_STANDALONE, standalone)
    }
}
