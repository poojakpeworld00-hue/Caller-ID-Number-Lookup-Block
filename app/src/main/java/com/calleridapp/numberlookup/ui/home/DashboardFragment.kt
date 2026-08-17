package com.calleridapp.numberlookup.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.ColorStateList
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostFragment
import com.calleridapp.admesh.domain.logPermissionResult
import com.calleridapp.numberlookup.data.RegionLocator
import com.calleridapp.numberlookup.data.VaultRegistry
import kotlinx.coroutines.launch
import com.calleridapp.numberlookup.databinding.FragmentHomeBinding
import com.calleridapp.numberlookup.databinding.ItemQuickActionBinding
import com.calleridapp.numberlookup.ui.blocklist.BlockRosterActivity
import com.calleridapp.numberlookup.ui.common.CallRowAdapter
import com.calleridapp.admesh.presentation.NativePromoBanner
import com.calleridapp.numberlookup.ui.common.CoachMarkFloat
import com.calleridapp.numberlookup.ui.common.HomeMotion
import com.calleridapp.numberlookup.util.openActivity
import com.calleridapp.numberlookup.ui.dialer.KeypadActivity
import com.calleridapp.numberlookup.ui.lookup.Territories
import com.calleridapp.numberlookup.ui.lookup.TerritoryPickerActivity
import com.calleridapp.numberlookup.ui.settings.PreferencesActivity
import com.calleridapp.numberlookup.ui.tools.UtilityActivity
import com.calleridapp.numberlookup.util.followAdContainer

class DashboardFragment : HostFragment<FragmentHomeBinding>() {

    private val viewModel: DashboardViewModel by viewModels()
    private val recentAdapter = CallRowAdapter(
        mutableListOf(),
        onCall = { placeCall(it) },
        onIdentify = { number -> homeShell?.showLookup(number) }
    )
    private val prefs by lazy { VaultRegistry(requireContext()) }

    /** Dialing code selected in the search country chip (no leading '+'). */
    private var homeDial: String = ""

    /** Press-scale (0.96) with a spring release — the design's quick-action motion.
     *  Returns false so the view's own click listener still fires. */
    @SuppressLint("ClickableViewAccessibility")
    private val pressScale = View.OnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120L).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(OvershootInterpolator()).setDuration(260L).start()
        }
        false
    }

    /** Quick-action permission queue + its result launcher (see [withCorePermissions]). */
    private val corePermQueue = ArrayDeque<String>()
    private var corePermAction: (() -> Unit)? = null
    private var lastCorePermission: String? = null
    private val corePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        lastCorePermission?.let { context?.logPermissionResult(it, granted) }
        advanceCorePermissions()
    }

    private val countryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data ?: return@registerForActivityResult
            val iso = data.getStringExtra(TerritoryPickerActivity.EXTRA_ISO) ?: return@registerForActivityResult
            val dial = data.getStringExtra(TerritoryPickerActivity.EXTRA_DIAL).orEmpty()
            prefs.homeCountryIso = iso // remember the user's explicit choice
            applyHomeCountry(iso, dial)
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHomeBinding.inflate(inflater, container, false)

    override fun initView() {
        // Hero bleeds under the status bar; pad its content down by the inset.
        val baseTop = binding.heroHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroHeader) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = baseTop + top)
            insets
        }

        // Per-tile tint = Claude Design's actions-3 cells (g-700/teal/clay/amber).
        // Blocklist/Tools deliberately do NOT reuse the danger/success verdict
        // colors here -- those are fixed verdict roles, not decorative tints.
        bindQuick(binding.qaDialer, R.drawable.ph_grid_nine, R.string.quick_dialer, R.color.primary, R.color.primary_container)
        bindQuick(binding.qaLookup, R.drawable.ph_magnifying_glass, R.string.quick_lookup, R.color.cid_teal, R.color.cid_teal_100)
        bindQuick(binding.qaBlocklist, R.drawable.ph_prohibit, R.string.quick_blocklist, R.color.cid_clay, R.color.cid_clay_100)
        bindQuick(binding.qaTools, R.drawable.ph_squares_four, R.string.quick_tools, R.color.cid_amber, R.color.cid_amber_100)

        binding.rvRecent.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecent.adapter = recentAdapter

        // Native banner above the recent calls.
        NativePromoBanner().showNativeBannerNative(requireActivity(), binding.adRecentBanner, binding.adRecentShimmer)
        binding.adNativeDivider.followAdContainer(binding.adRecentBanner)
        binding.adNativeDivider1.followAdContainer(binding.adRecentBanner)
        binding.btnSettings.setOnClickListener {
            requireActivity().openActivity<PreferencesActivity>()
        }

        binding.qaDialer.root.setOnClickListener {
            withCorePermissions { requireActivity().openActivity<KeypadActivity>() }
        }
        binding.qaLookup.root.setOnClickListener {
            withCorePermissions { homeShell?.showLookup() }
        }
        setupHomeCountry()
        binding.llHomeCountry.setOnClickListener {
            countryLauncher.launch(Intent(requireContext(), TerritoryPickerActivity::class.java))
        }
        binding.btnHomeSearch.setOnClickListener { submitSearch() }
        binding.etHomeSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch(); true
            } else false
        }
        binding.qaBlocklist.root.setOnClickListener {
            withCorePermissions { requireActivity().openActivity<BlockRosterActivity>() }
        }
        binding.qaTools.root.setOnClickListener {
            withCorePermissions { requireActivity().openActivity<UtilityActivity>() }
        }

        // Protection card → Blocklist; Recent "See all" → Recents tab.
        binding.cardProtection.setOnClickListener {
            requireActivity().openActivity<BlockRosterActivity>()
        }
        binding.tvSeeAll.setOnClickListener {
            homeShell?.showRecents()
        }

        // Quick-action tiles: press-scale 0.96 with a spring release (design motion).
        listOf(binding.qaDialer, binding.qaLookup, binding.qaBlocklist, binding.qaTools)
            .forEach { it.root.setOnTouchListener(pressScale) }
        binding.btnAllowCallLog.setOnClickListener {
            requestPermissionChain(
                listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
            ) {
                loadRecentIfAllowed()
                homeShellController?.startOverlayPermissionFlow()
            }
        }

        binding.btnPermManage.setOnClickListener {
            homeShellController?.showPermissionSheet()
        }

        HomeMotion.attachFocusScale(binding.searchBar, binding.etHomeSearch)

        loadRecentIfAllowed()
        maybeShowSearchHint()
        refreshPermissionHint()
        playEntrance()
    }

    /**
     * Screen entrance: the Protection banner rises first, then the search pill,
     * the quick-actions card and the "Recent activity" header, each a beat
     * later -- the design's `cid-rise-in` rhythm. The recent list's own stagger
     * continues from [CallRowAdapter]. Runs once per Fragment instance (Home's
     * view is created once; tab switches show/hide it rather than recreating it).
     */
    private fun playEntrance() {
        HomeMotion.riseIn(binding.cardProtection, delay = 0L)
        HomeMotion.riseIn(binding.searchBar, delay = 90L)
        HomeMotion.riseIn(binding.quickActionsRow, delay = 150L)
        HomeMotion.riseIn(binding.recentHeaderRow, delay = 200L)
    }

    /**
     * Surfaces the "Manage permissions" hint once the ShellActivity permission
     * sheet has been dismissed with permissions still pending, and hides it again
     * as soon as everything is granted. Safe to call any time the fragment is
     * attached — ShellActivity owns the actual condition.
     */
    fun refreshPermissionHint() {
        if (view == null) return
        val show = homeShellController?.shouldShowPermissionHint() == true
        binding.llPermHint.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Core permissions nudged from the four Quick Action buttons: post-notifications
     * (Android 13+) so call alerts can show, and read-phone-state for call detection.
     */
    private fun corePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.READ_PHONE_STATE)
    }

    /**
     * Requests the [corePermissions] (skipping any already granted) and then runs
     * [action] — which navigates to the tapped quick action's screen.
     *
     * Each tap re-attempts a still-denied permission. Once it has been denied a
     * second time (permanently — the system stops showing its dialog), we do NOT
     * bounce the user to App Settings; we simply skip it and continue to the next
     * activity so the quick action still works.
     */
    private fun withCorePermissions(action: () -> Unit) {
        corePermQueue.clear()
        corePermQueue.addAll(corePermissions())
        corePermAction = action
        advanceCorePermissions()
    }

    /** Walks the queue, prompting where the dialog still shows, then runs the action. */
    private fun advanceCorePermissions() {
        val ctx = context ?: return
        val prefs = VaultRegistry(ctx)
        while (corePermQueue.isNotEmpty()) {
            val permission = corePermQueue.removeFirst()
            if (ContextCompat.checkSelfPermission(ctx, permission)
                == PackageManager.PERMISSION_GRANTED
            ) continue

            when {
                // First-ever request → show the system dialog (callback resumes the walk).
                !prefs.hasRequestedPermission(permission) -> {
                    prefs.markPermissionRequested(permission)
                    lastCorePermission = permission
                    corePermLauncher.launch(permission)
                    return
                }
                // Denied before but the dialog still appears → re-attempt.
                shouldShowRequestPermissionRationale(permission) -> {
                    lastCorePermission = permission
                    corePermLauncher.launch(permission)
                    return
                }
                // Permanently denied → skip (no Settings redirect) and keep going.
                else -> continue
            }
        }
        // Queue drained — proceed to the next activity.
        val action = corePermAction
        corePermAction = null
        action?.invoke()
    }

    /**
     * First-run coach-mark: dims the whole screen, spotlights the Home search
     * bar through the scrim, and shows a hint bubble beneath it. Shown only once
     * (persisted via [VaultRegistry.isSearchHintShown]); a tap anywhere dismisses
     * it.
     */
    private fun maybeShowSearchHint() {
        if (prefs.isSearchHintShown) return
        val anchor = binding.searchBar
        anchor.post {
            if (!isAdded || view == null) return@post
            val act = activity ?: return@post
            prefs.isSearchHintShown = true
            CoachMarkFloat.show(act, anchor, R.layout.view_search_hint)
        }
    }

    /**
     * Picks the search country chip. Uses the saved choice if any; otherwise shows the
     * device region immediately and refines it to the IP-detected country in the background.
     */
    private fun setupHomeCountry() {
        // 1) Honour an explicit choice from the country picker.
        val saved = prefs.homeCountryIso
        if (saved.length == 2) {
            applyHomeCountry(saved, dialFor(saved))
            return
        }
        // 2) The SIM/network country is the most accurate source for a phone
        //    (an Indian SIM → "IN" even when the device language is US English).
        val sim = simCountryIso()
        if (sim != null) {
            applyHomeCountry(sim, dialFor(sim))
            return
        }
        // 3) No SIM → device region immediately (never the globe), refined via IP.
        val region = java.util.Locale.getDefault().country
        val fallbackIso = if (region.length == 2) region else "US"
        applyHomeCountry(fallbackIso, dialFor(fallbackIso))
        detectCountryByIp()
    }

    /** SIM (then network) registered country as an uppercase ISO-2, or null. No permission needed. */
    private fun simCountryIso(): String? {
        val tm = context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val iso = tm.simCountryIso?.takeIf { it.length == 2 }
            ?: tm.networkCountryIso?.takeIf { it.length == 2 }
        return iso?.uppercase()
    }

    private fun dialFor(iso: String): String =
        (Territories.byIso(iso)?.dial ?: Territories.dialOf(iso)).orEmpty()

    /** Resolves the country from the user's IP and updates the chip (best-effort). */
    private fun detectCountryByIp() {
        viewLifecycleOwner.lifecycleScope.launch {
            val geo = RegionLocator.detectCountry(requireContext()) ?: return@launch
            // Bail if the view is gone or the user picked a country meanwhile.
            if (view == null || prefs.homeCountryIso.isNotBlank()) return@launch
            // Prefer the dial code straight from the API; fall back to our local map.
            val dial = geo.dial.ifBlank { dialFor(geo.iso) }
            // Don't persist an auto-detected country — only the picker records a choice.
            applyHomeCountry(geo.iso, dial)
        }
    }

    private fun applyHomeCountry(iso: String, dial: String) {
        homeDial = dial
        binding.tvHomeFlag.text = Territories.flag(iso)
        binding.tvHomeDial.text = if (dial.isBlank()) iso else "+$dial"
    }

    /** Navigates to the Lookup tab and runs the lookup for the entered number. */
    private fun submitSearch() {
        val typed = binding.etHomeSearch.text?.toString()?.trim().orEmpty()
        // Prefix the selected country code unless the user already typed a '+'.
        val number = when {
            typed.isBlank() -> ""
            typed.startsWith("+") -> typed
            homeDial.isNotBlank() -> "+$homeDial" + typed.filter { it.isDigit() }
            else -> typed
        }
        homeShell?.showLookup(number.ifBlank { null })
        binding.etHomeSearch.setText("")
    }

    private fun bindQuick(
        item: ItemQuickActionBinding,
        @DrawableRes icon: Int,
        @StringRes label: Int,
        @ColorRes fgColor: Int,
        @ColorRes softColor: Int
    ) {
        item.qaIcon.setImageResource(icon)
        item.qaLabel.setText(label)
        val ctx = requireContext()
        item.qaIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, fgColor))
        item.qaIconCircle.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, softColor))
    }

    override fun initObservers() {
        viewModel.recent.observe(viewLifecycleOwner) { recentAdapter.submit(it) }
        // Protection banner subtitle -- Claude Design's hero C reads the count inline.
        viewModel.blockedCount.observe(viewLifecycleOwner) {
            binding.tvProtectionSub.text = getString(R.string.home_protection_subtitle, it)
        }
    }

    override fun onResume() {
        super.onResume()
        loadRecentIfAllowed()
        refreshPermissionHint()
    }

    /** Reloads when this tab becomes visible again (show/hide keeps the fragment resumed). */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            loadRecentIfAllowed()
            refreshPermissionHint()
        }
    }

    private fun loadRecentIfAllowed() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        // Swap the recent list for a permission prompt when access is missing.
        binding.llRecentPermission.visibility = if (granted) View.GONE else View.VISIBLE
        binding.rvRecent.visibility = if (granted) View.VISIBLE else View.GONE

        if (granted) viewModel.loadRecent()
    }
}
