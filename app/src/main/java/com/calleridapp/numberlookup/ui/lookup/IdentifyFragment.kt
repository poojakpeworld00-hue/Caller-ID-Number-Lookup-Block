package com.calleridapp.numberlookup.ui.lookup

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.RegionLocator
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.RewardedPromo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.calleridapp.numberlookup.base.HostFragment
import com.calleridapp.numberlookup.data.VaultRegistry
import com.calleridapp.numberlookup.util.openActivity
import com.calleridapp.numberlookup.databinding.DialogWatchAdBinding
import com.calleridapp.numberlookup.databinding.FragmentLookupBinding
import java.util.Locale

class IdentifyFragment : HostFragment<FragmentLookupBinding>() {

    private val viewModel: IdentifyViewModel by viewModels()
    private lateinit var historyAdapter: IdentifyTraceAdapter
    private var currentState: IdentifyState = IdentifyState.Idle

    /** A number handed in from elsewhere (e.g. Home search) to look up on arrival. */
    private var pendingNumber: String? = null

    /** Clipboard value already used via the Paste chip — don't offer it again. */
    private var consumedClip: String? = null

    private val countryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data ?: return@registerForActivityResult
            val iso = data.getStringExtra(TerritoryPickerActivity.EXTRA_ISO) ?: return@registerForActivityResult
            val dial = data.getStringExtra(TerritoryPickerActivity.EXTRA_DIAL).orEmpty()
            VaultRegistry(requireContext()).homeCountryIso = iso // keep Home + Lookup in sync
            applyCountry(iso, dial)
        }
    }

    /** Opens the standalone history screen; a picked number is searched on return. */
    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val number = res.data?.getStringExtra(IdentifyTraceActivity.EXTRA_NUMBER)
                ?.takeIf { it.isNotBlank() } ?: return@registerForActivityResult
            binding.etNumberInput.setText(number)
            binding.etNumberInput.setSelection(number.length)
            viewModel.search(number)
            hideKeyboard()
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentLookupBinding.inflate(inflater, container, false)

    override fun initView() {
        // Let the blue hero extend under the status bar; pad its top by the inset.
        // Hero bleeds under the status bar; pad its content down by the inset.
        val baseTop = binding.heroHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroHeader) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = baseTop + top)
            insets
        }
        setupCountryChip()
        // Preload the rewarded ad so it's ready when the user reveals a result.
        RewardedPromo.preload(requireContext())
        binding.llCountryPickerSearch.setOnClickListener {
            countryLauncher.launch(Intent(requireContext(), TerritoryPickerActivity::class.java))
        }

        binding.btnLookupHistory.setOnClickListener {
            historyLauncher.launch(IdentifyTraceActivity.newIntent(requireContext()))
        }

        historyAdapter = IdentifyTraceAdapter(
            onClick = { entry -> binding.etNumberInput.setText(entry.rawNumber); binding.etNumberInput.setSelection(entry.rawNumber.length) },
            onCall = { entry -> dial(entry.rawNumber) },
            onRevealName = { entry -> revealHistoryName(entry) }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = historyAdapter

        binding.etNumberInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                binding.ivClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                // Note: no live search — results are shown only after tapping Lookup.
                if (text.isEmpty()) maybeShowPasteChip() else binding.chipPaste.visibility = View.GONE
            }
        })

        binding.etNumberInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.search(binding.etNumberInput.text?.toString().orEmpty())
                hideKeyboard()
                true
            } else false
        }

        binding.btnSearch.setOnClickListener {
            viewModel.search(binding.etNumberInput.text?.toString().orEmpty())
            hideKeyboard()
        }

        binding.ivClear.setOnClickListener {
            binding.etNumberInput.setText("")
            viewModel.clear()
        }

        // Empty-state CTA: focus the field and pop the keyboard.
        binding.btnEmptySearch.setOnClickListener {
            binding.etNumberInput.requestFocus()
            showKeyboard()
        }

        // Paste chip: one tap fills the field from the clipboard and searches
        // (uses the raw clipboard value stored on the chip, not the pretty display).
        binding.chipPaste.setOnClickListener {
            val n = (binding.chipPaste.tag as? String)?.takeIf { it.isNotBlank() }
                ?: binding.tvPasteNumber.text?.toString().orEmpty()
            if (n.isNotBlank()) {
                consumedClip = n // used once → don't re-offer this same clipboard number
                binding.etNumberInput.setText(n)
                binding.etNumberInput.setSelection(n.length)
                viewModel.search(n)
                hideKeyboard()
                binding.chipPaste.visibility = View.GONE
            }
        }

        // Focusing the field re-checks the clipboard (covers copying from inside the app).
        binding.etNumberInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) maybeShowPasteChip()
        }

        binding.tvHistoryClearAll.setOnClickListener { viewModel.clearHistory() }

        consumePendingSearch()
    }

    override fun onResume() {
        super.onResume()
        // The standalone history screen may have cleared/changed entries while away.
        if (view != null) {
            viewModel.refreshHistory()
            maybeShowPasteChip(autoFocusIfUsed = true)
        }
    }

    /** Tab became visible again (ShellActivity uses show/hide, so onResume won't fire). */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) {
            maybeShowPasteChip(autoFocusIfUsed = true)
            // Re-entering the tab re-hides names: each visit must re-earn via a rewarded ad.
            historyAdapter.resetReveals()
        }
    }

    /** Called by the host (e.g. from Home search) to look up a number on this tab. */
    fun requestSearch(number: String) {
        pendingNumber = number
        if (_isViewReady()) consumePendingSearch()
    }

    private fun _isViewReady(): Boolean = view != null

    private fun consumePendingSearch() {
        val number = pendingNumber?.takeIf { it.isNotBlank() } ?: return
        pendingNumber = null
        binding.etNumberInput.setText(number)
        binding.etNumberInput.setSelection(number.length)
        viewModel.search(number)
        hideKeyboard()
    }

    override fun initObservers() {
        viewModel.state.observe(viewLifecycleOwner) { state ->
            currentState = state
            when (state) {
                is IdentifyState.Idle -> render(loading = false, result = null)
                is IdentifyState.Loading -> render(loading = true, result = null)
                is IdentifyState.Result -> render(loading = false, result = state.result)
            }
            maybeShowPasteChip()
        }
        viewModel.history.observe(viewLifecycleOwner) { items ->
            historyAdapter.submit(items)
            val hasHistory = items.isNotEmpty()
            binding.llHistoryHeader.visibility = if (hasHistory) View.VISIBLE else View.GONE
            binding.rvHistory.visibility = if (hasHistory) View.VISIBLE else View.GONE
            updateEmptyState(hasHistory)
        }
    }

    private fun render(loading: Boolean, result: IdentifyResult?) {
        binding.shimmerResult.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) binding.shimmerResult.startShimmer() else binding.shimmerResult.stopShimmer()

        // Shimmer overlays the scroll region, so hide the list/result content while loading.
        binding.scrollContent.visibility = if (loading) View.GONE else View.VISIBLE

        if (result != null) {
            binding.cvResult.visibility = View.VISIBLE
            bindResult(result)
        } else {
            binding.cvResult.visibility = View.GONE
        }
        updateEmptyState(binding.rvHistory.visibility == View.VISIBLE)
    }

    /** Empty state only when idle with no result and no history. */
    private fun updateEmptyState(hasHistory: Boolean) {
        val idle = currentState is IdentifyState.Idle
        binding.llEmptyState.visibility =
            if (idle && !hasHistory) View.VISIBLE else View.GONE
    }

    private fun bindResult(result: IdentifyResult) {
        // Name is blurred on the card; revealed (full name + detail screen) after a rewarded ad.
        val hasName = !result.name.isNullOrBlank()
        val fullName = result.name?.takeIf { it.isNotBlank() } ?: getString(R.string.lookup_unknown_caller)
        binding.tvResName.text = if (hasName) blurName(fullName) else fullName
        binding.ivRevealName.visibility = if (hasName) View.VISIBLE else View.GONE
        binding.tvResNumber.text = result.number

        when {
            result.isSpam -> bindStatusPill(
                R.string.lookup_spam_risk, R.color.danger, R.color.danger_soft, R.drawable.ic_warning
            )
            result.valid == true -> bindStatusPill(
                R.string.lookup_valid_number, R.color.success, R.color.success_soft, R.drawable.ic_verified
            )
            result.valid == false -> bindStatusPill(
                R.string.lookup_invalid_number, R.color.danger, R.color.danger_soft, R.drawable.ic_warning
            )
            else -> bindStatusPill(
                if (result.inContacts) R.string.lookup_in_contacts else R.string.lookup_not_in_contacts,
                R.color.on_surface_variant, R.color.neutral_soft, R.drawable.ic_info
            )
        }

        binding.tvResCountry.text = result.country
            ?: viewModel.regionName(result.rawNumber)
            ?: getString(
                if (result.regionCode.isEmpty()) R.string.lookup_local_number
                else R.string.lookup_international
            )
        binding.tvResCarrier.text = result.carrier?.takeIf { it.isNotBlank() }
            ?: getString(R.string.lookup_unknown_value)
        binding.tvResType.text = result.lineType?.takeIf { it.isNotBlank() }
            ?: getString(R.string.lookup_unknown_value)

        binding.btnResCall.setOnClickListener { dial(result.rawNumber) }
        binding.btnResShare.setOnClickListener { share(result) }
        // Eye icon + "Show full detail" → watch a rewarded ad, then reveal.
        val reveal = { revealFullDetail(result, fullName) }
        binding.ivRevealName.setOnClickListener { reveal() }
        binding.btnShowFullDetail.setOnClickListener { reveal() }
    }

    /**
     * Reveals the caller: shows a "watch ad" confirmation dialog → rewarded ad →
     * un-blurs the card name and opens [IdentifyInsightActivity] (incl. nicknames).
     * Goes straight through when ads are off.
     */
    private fun revealFullDetail(result: IdentifyResult, fullName: String) {
        val act = activity ?: return
        val open = {
            if (view != null) {
                binding.tvResName.text = fullName
                binding.ivRevealName.visibility = View.GONE
                requireActivity().openActivity(IdentifyInsightActivity.newIntent(requireContext(), result), false)
            }
        }

        // Ads off → straight to detail, no ad, no dialog.
        if (!AdsVault.getInstance(act).getBoolean("IsAdsON")) {
            open()
            return
        }

        // Ads on → confirm with a dialog, then play the rewarded ad, then open.
        val db = DialogWatchAdBinding.inflate(layoutInflater)
        val dialog = Dialog(act).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(db.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        db.tvPreviewName.text = blurName(fullName)
        db.tvPreviewNumber.text = result.number
        db.btnWatchAd.setOnClickListener {
            dialog.dismiss()
            RewardedPromo().show(act) { open() }
        }
        db.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** First letter + dots (e.g. "John" → "J•••"). */
    private fun blurName(name: String): String =
        if (name.isNotEmpty()) name[0] + "•".repeat(name.length - 1) else name

    /** Recent-list name tap: gate the reveal behind a rewarded ad, then un-mask that row. */
    private fun revealHistoryName(entry: TraceEntry) {
        val act = activity ?: return
        val name = entry.name ?: return
        RewardedReveal.reveal(act, name, entry.number) {
            if (view != null) historyAdapter.revealName(entry.rawNumber)
        }
    }

    private fun setupCountryChip() {
        // 1) Honour an explicit choice from the country picker.
        val saved = VaultRegistry(requireContext()).homeCountryIso
        if (saved.length == 2) {
            applyCountry(saved, dialFor(saved))
            return
        }
        // 2) SIM/network country — the most accurate source for a phone.
        val sim = simCountryIso()
        if (sim != null) {
            applyCountry(sim, dialFor(sim))
            return
        }
        // 3) No SIM → device region immediately (never the globe), refined via IP.
        val region = Locale.getDefault().country
        val fallbackIso = if (region.length == 2) region else "US"
        applyCountry(fallbackIso, dialFor(fallbackIso))
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

    /**
     * Resolves the country from the user's IP via the shared, cache-first
     * [RegionLocator] and updates the chip (best-effort). The country is detected
     * once app-wide (ADDashboardActivity) and reused here — no repeat network call.
     * The resolved ISO maps to its dial code so the chip shows the flag 🇮🇳 and
     * "+91". Failures leave the fallback.
     */
    private fun detectCountryByIp() {
        viewLifecycleOwner.lifecycleScope.launch {
            val iso = RegionLocator.detectCountry(requireContext())?.iso ?: return@launch
            // Bail if the view is gone or the user picked a country meanwhile.
            if (view == null || VaultRegistry(requireContext()).homeCountryIso.isNotBlank()) return@launch
            val dial = dialFor(iso)
            if (dial.isBlank()) return@launch
            // Don't persist an auto-detected country — only the picker records a choice.
            applyCountry(iso, dial)
        }
    }

    private fun applyCountry(iso: String, dial: String) {
        binding.tvFlagSearch.text = Territories.flag(iso)
        binding.tvCountrySearch.text = if (dial.isBlank()) iso else "+$dial"
        viewModel.setRegion(iso, dial)
    }

    /** Styles the status pill (text, text/icon color, soft background) for one lookup state. */
    private fun bindStatusPill(textRes: Int, fgColor: Int, bgColor: Int, iconRes: Int) {
        val fg = color(fgColor)
        binding.tvResValid.apply {
            setText(textRes)
            setTextColor(fg)
            backgroundTintList = ColorStateList.valueOf(color(bgColor))
            setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(fg))
        }
    }

    private fun color(resId: Int) = ContextCompat.getColor(requireContext(), resId)

    private fun dial(number: String) = placeCall(number)

    private fun share(result: IdentifyResult) {
        val details = buildString {
            append(result.name ?: getString(R.string.lookup_unknown_caller))
            append("\n").append(result.number)
            result.country?.let { append("\n").append(getString(R.string.lookup_country)).append(": ").append(it) }
            result.carrier?.let { append("\n").append(getString(R.string.lookup_carrier)).append(": ").append(it) }
            result.lineType?.let { append("\n").append(getString(R.string.lookup_line_type)).append(": ").append(it) }
        }
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, details)
            }
            startActivity(Intent.createChooser(intent, null))
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etNumberInput.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etNumberInput, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Offers a one-tap "Paste <number>" chip when the clipboard holds something that
     * looks like a phone number and the field is empty (idle, no result yet).
     */
    private fun maybeShowPasteChip(autoFocusIfUsed: Boolean = false) {
        if (view == null) return
        val idleEmpty = currentState is IdentifyState.Idle && binding.etNumberInput.text.isNullOrBlank()
        if (!idleEmpty) {
            binding.chipPaste.visibility = View.GONE
            return
        }
        val clip = clipboardPhone()
        when {
            // A fresh phone number in the clipboard → offer the Paste chip.
            clip != null && clip != consumedClip -> {
                binding.chipPaste.tag = clip // raw value used for the search
                binding.tvPasteNumber.text =
                    runCatching { DigitInfo.format(clip) }.getOrNull()?.takeIf { it.isNotBlank() } ?: clip
                binding.chipPaste.visibility = View.VISIBLE
            }
            // Already pasted this same number once → don't re-offer it; open the
            // keyboard so the user can just type instead.
            clip != null && clip == consumedClip && autoFocusIfUsed -> {
                binding.chipPaste.visibility = View.GONE
                binding.etNumberInput.requestFocus()
                showKeyboard()
            }
            else -> binding.chipPaste.visibility = View.GONE
        }
    }

    /** The clipboard text if it looks like a phone number, else null. */
    private fun clipboardPhone(): String? {
        val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString()?.trim().orEmpty()
        return clip.takeIf { looksLikePhone(it) }
    }

    /** Loose phone-number check: 6–20 chars, mostly digits, only dialling characters. */
    private fun looksLikePhone(s: String): Boolean {
        if (s.length < 6 || s.length > 20) return false
        if (s.count { it.isDigit() } < 6) return false
        return s.all { it.isDigit() || it in "+-().,  " }
    }
}
