package com.calleridapp.numberlookup.ui.language

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.calleridapp.admesh.presentation.NativeADs
import com.calleridapp.admesh.presentation.oninterAds.InterADsNormal
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.BaseActivity
import com.calleridapp.numberlookup.data.GeoLocator
import com.calleridapp.numberlookup.data.LocaleManager
import com.calleridapp.numberlookup.data.PrefsManager
import com.calleridapp.numberlookup.databinding.ActivityLanguageBinding
import com.calleridapp.numberlookup.permission.PermissionEngine
import com.calleridapp.numberlookup.permission.fsi.FsiPermission
import com.calleridapp.numberlookup.permission.fsi.FsiPermissionActivity
import com.calleridapp.numberlookup.ui.MainActivity
import com.calleridapp.numberlookup.ui.intro.IntroDisplayConfig
import com.calleridapp.numberlookup.ui.intro.IntroDisplayPolicy
import com.calleridapp.numberlookup.ui.onboarding.OnboardingActivity
import com.calleridapp.numberlookup.ui.terms.TermsActivity
import com.calleridapp.numberlookup.util.AppPrefs
import com.calleridapp.numberlookup.util.SafeSide
import kotlinx.coroutines.launch
import com.calleridapp.numberlookup.util.followAdContainer

class LanguageActivity : BaseActivity<ActivityLanguageBinding>() {

    override val layoutId: Int = R.layout.activity_language

    private val viewModel: LanguageViewModel by viewModels()
    private val prefs by lazy { PrefsManager(this) }

    /** True when opened from Settings to change language (vs. the first-run flow). */
    private val standalone by lazy { intent.getBooleanExtra(EXTRA_STANDALONE, false) }

    // Two lists share one selection: a compact "Suggested" group and the full
    // "All languages" group. Both adapters observe the same selectedTag.
    private lateinit var suggestedAdapter: LanguageAdapter
    private lateinit var allAdapter: LanguageAdapter

    /** One-shot guard so a back-press can't fire the forward flow twice. */
    private var forwarding = false

    override fun initView() {
        // Count this as an intro show only in the first-run flow (not when opened
        // from Settings to change language) — drives the once/count frequency gate.
        if (!standalone) IntroDisplayPolicy.markShown(this, IntroDisplayConfig.LANGUAGE)

        ViewCompat.setOnApplyWindowInsetsListener(binding.languageRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Current language comes from AppPrefs (the store BaseActivity.applyLocale reads).
        // First launch (no saved language) → "Default" (follow system).
        val current = AppPrefs.language(this) ?: AppPrefs.LANGUAGE_DEFAULT
        viewModel.init(current)

        // Mid native ad shown above the Continue button.
        NativeADs().showBigNative(this, binding.adNativeFrame, binding.adShimmer)
        binding.adNativeDivider.followAdContainer(binding.adNativeFrame)

        // 1) Resolve the region FIRST, before the lists exist. The device seed is
        //    synchronous, so viewModel.suggested/others already hold the correct,
        //    region-specific groups by the time the adapters observe them — the
        //    Suggested list is right on the very first frame (no default flash).
        //    The IP refine is async and updates the lists if it disagrees.
        resolveRegion()

        // 2) Now build the lists. The adapters observe suggested/others (wired in
        //    initObservers), so they render the region-correct list immediately.
        val onPick: (LanguageItem) -> Unit = { viewModel.select(it.tag) }
        suggestedAdapter = LanguageAdapter(onPick).apply { setCurrent(current) }
        allAdapter = LanguageAdapter(onPick).apply { setCurrent(current) }

        binding.rvSuggested.layoutManager = LinearLayoutManager(this)
        binding.rvSuggested.adapter = suggestedAdapter

        binding.rvLanguages.layoutManager = LinearLayoutManager(this)
        binding.rvLanguages.adapter = allAdapter

        binding.btnBack.setOnClickListener { goBack() }
        binding.btnInfo.setOnClickListener { showInfoDialog() }
        binding.btnContinue.setOnClickListener { onContinue() }

        // First-run flow: the system back must NOT exit the app — advance forward
        // exactly like Continue. The callback stays enabled so back never falls
        // through to BaseActivity's exit handler; `forwarding` blocks re-entry.
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
        SafeSide.log(TAG, "resolveRegion: device=$device (sync seed)")
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
            val geo = GeoLocator.detectCountry(this@LanguageActivity) ?: run {
                SafeSide.log(TAG, "IP geo unavailable → keeping device seed")
                return@launch
            }
            SafeSide.log(TAG, "IP refine → country=${geo.iso}")
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
        val tag = viewModel.selectedTag.value ?: AppPrefs.LANGUAGE_DEFAULT
        AppPrefs.setLanguage(this, tag)  // source of truth for Splash + BaseActivity.applyLocale
        prefs.isLanguageSelected = true

        // Opened from Settings: just apply and return; don't drive the first-run flow.
        if (standalone) {
            LocaleManager.apply(tag) // recreates activities with the new locale
            finish()
            return
        }

        // First-run flow. Show the permission(s) FIRST, then apply the locale and
        // navigate in the completion callback. Applying the locale recreates this
        // Activity, and finishing it early tears it down — either one aborts an
        // in-flight permission request (that's why nothing showed and the app
        // closed). So we defer BOTH until the engine reports it's done.
        PermissionEngine.check(this) {
            // Mirror Splash's routing: Terms and Onboarding both follow the same
            // IntroDisplayPolicy frequency gate.
            val next = when {
                IntroDisplayPolicy.shouldShowTerms(this) -> TermsActivity::class.java
                IntroDisplayPolicy.shouldShowOnboarding(this) -> OnboardingActivity::class.java
                else -> MainActivity::class.java
            }
            // Conditional Full-Screen-Intent Screen: when the Remote Config gate
            // passes, it shows here (after Language) and then continues to `next`.
            val intent = if (FsiPermission.shouldShowScreen(this)) {
                FsiPermissionActivity.newIntent(this, next)
            } else {
                Intent(this, next)
            }
            // Permission done → show the interstitial (Firebase-gated; fires its
            // callback immediately when there's nothing to show) → THEN apply the
            // locale and navigate. LocaleManager.apply recreates this Activity, so
            // it must run after the ad (doing it earlier would tear the ad down).
            InterADsNormal().showInterAds(this) {
                LocaleManager.apply(tag) // recreates activities with the new locale
                startActivity(intent)
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "LanguageActivity"
        private const val EXTRA_STANDALONE = "extra_standalone"

        /** Standalone = opened from Settings to change language (returns on Continue). */
        fun newIntent(context: Context, standalone: Boolean = false): Intent =
            Intent(context, LanguageActivity::class.java)
                .putExtra(EXTRA_STANDALONE, standalone)
    }
}
