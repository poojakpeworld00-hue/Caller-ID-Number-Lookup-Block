package com.calleridapp.numberlookup.ui.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.calleridapp.numberlookup.R
import com.calleridapp.admesh.presentation.NativePromoBanner
import com.calleridapp.numberlookup.base.HostFragment
import com.calleridapp.numberlookup.util.openActivity
import com.calleridapp.numberlookup.databinding.FragmentContactsBinding
import com.calleridapp.numberlookup.ui.detail.CallInsightActivity
import com.calleridapp.numberlookup.util.followAdContainer
import com.calleridapp.numberlookup.ui.home.homeShellController

class PeopleFragment : HostFragment<FragmentContactsBinding>() {

    private val viewModel: PeopleViewModel by viewModels()
    private val adapter = PeopleAdapter(::dialNumber, ::openDetail)
    private lateinit var layoutManager: LinearLayoutManager

    private var sectionLetters: List<String> = emptyList()
    private var letterToPosition: Map<String, Int> = emptyMap()

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentContactsBinding.inflate(inflater, container, false)

    override fun initView() {
        // Hero bleeds under the status bar; pad its content down by the inset.
        val baseTop = binding.heroHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroHeader) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = baseTop + top)
            insets
        }

        layoutManager = LinearLayoutManager(requireContext())
        binding.rvContacts.layoutManager = layoutManager
        binding.rvContacts.adapter = adapter

        // Native banner at the bottom of the contacts screen.
        NativePromoBanner().showNativeBannerNative(requireActivity(), binding.adNativeFrame, binding.adShimmer)
        binding.adNativeDivider.followAdContainer(binding.adNativeFrame)
        binding.adNativeDivider1.followAdContainer(binding.adNativeFrame)

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                viewModel.setQuery(text)
                binding.btnClearSearch.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
        })
        binding.btnClearSearch.setOnClickListener { binding.etSearch.setText("") }
        binding.btnContactsAdd.setOnClickListener { openAddContact() }

        binding.tabAll.setOnClickListener { viewModel.setFilter(PersonFilter.ALL) }
        binding.tabFavorites.setOnClickListener { viewModel.setFilter(PersonFilter.FAVORITES) }
        binding.tabRecents.setOnClickListener { viewModel.setFilter(PersonFilter.RECENTS) }
        binding.tabGroups.setOnClickListener { viewModel.setFilter(PersonFilter.GROUPS) }

        setupAlphaIndexTouch()
        binding.btnGrant.setOnClickListener {
            requestPermissionChain(
                listOf(Manifest.permission.READ_CONTACTS)
            ) {
                if (hasContactsPermission()) onPermissionGranted() else showPermissionState()
                homeShellController?.startOverlayPermissionFlow()
            }
        }

        if (hasContactsPermission()) onPermissionGranted() else showPermissionState()
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate after returning from Settings so a freshly granted permission
        // loads the contact list without needing to leave the screen.
        if (view != null) {
            if (hasContactsPermission()) onPermissionGranted() else showPermissionState()
        }
    }

    override fun initObservers() {
        viewModel.filter.observe(viewLifecycleOwner) { active ->
            highlightTab(binding.tabAll, active == PersonFilter.ALL)
            highlightTab(binding.tabFavorites, active == PersonFilter.FAVORITES)
            highlightTab(binding.tabRecents, active == PersonFilter.RECENTS)
            highlightTab(binding.tabGroups, active == PersonFilter.GROUPS)
        }
        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submit(rows)

            val count = rows.count { it is PersonRow.Item }
            binding.tvContactsCount.text =
                if (count > 0) getString(R.string.contacts_count_fmt, count)
                else getString(R.string.nav_contacts)

            sectionLetters = rows.filterIsInstance<PersonRow.Header>().map { it.letter }
            letterToPosition = buildMap {
                rows.forEachIndexed { index, row ->
                    if (row is PersonRow.Header && !containsKey(row.letter)) put(row.letter, index)
                }
            }
            buildAlphaIndex()

            val hasData = rows.isNotEmpty()
            binding.alphaIndex.visibility = if (hasData) View.VISIBLE else View.GONE
            binding.tvEmpty.visibility =
                if (!hasData && hasContactsPermission()) View.VISIBLE else View.GONE
        }
    }

    private fun buildAlphaIndex() {
        binding.alphaIndex.removeAllViews()
        sectionLetters.forEach { letter ->
            val tv = TextView(requireContext()).apply {
                text = letter
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            binding.alphaIndex.addView(tv)
        }
    }

    private fun setupAlphaIndexTouch() {
        binding.alphaIndex.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val count = sectionLetters.size
                    if (count > 0 && v.height > 0) {
                        val idx = ((event.y / v.height) * count).toInt().coerceIn(0, count - 1)
                        val letter = sectionLetters[idx]
                        scrollToLetter(letter)
                        showBubble(letter)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.letterBubble.visibility = View.GONE
                    v.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun scrollToLetter(letter: String) {
        val position = letterToPosition[letter] ?: return
        layoutManager.scrollToPositionWithOffset(position, 0)
    }

    private fun showBubble(letter: String) {
        binding.letterBubble.text = letter
        binding.letterBubble.visibility = View.VISIBLE
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

    private fun onPermissionGranted() {
        binding.permState.visibility = View.GONE
        binding.rvContacts.visibility = View.VISIBLE
        viewModel.load()
        // First-time upload of device contacts to the server.
        com.calleridapp.numberlookup.services.PersonUploader
            .uploadOnceIfNeeded(requireContext())
    }

    private fun showPermissionState() {
        binding.permState.visibility = View.VISIBLE
        binding.rvContacts.visibility = View.GONE
        binding.tvEmpty.visibility = View.GONE
        binding.alphaIndex.visibility = View.GONE
    }

    private fun highlightTab(tab: TextView, active: Boolean) {
        tab.isActivated = active
        tab.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (active) R.color.white else R.color.on_surface_variant
            )
        )
        // Selected: tint the leading icon white. Unselected: clear the tint so the
        // icon keeps its own colour.
        TextViewCompat.setCompoundDrawableTintList(
            tab,
            if (active) {
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.white))
            } else {
                null
            }
        )
    }

    private fun openAddContact() {
        // Don't gate on resolveActivity(): on Android 11+ (enforced on 16) it
        // returns null unless the contacts app is declared in <queries>, even
        // though startActivity() would launch fine. Just try and handle failure.
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(requireContext(), R.string.app_unavailable, Toast.LENGTH_SHORT).show()
            }
    }

    private fun dialNumber(number: String) = placeCall(number)

    private fun openDetail(contact: com.calleridapp.numberlookup.data.PersonItem) {
        requireActivity().openActivity(CallInsightActivity.newIntent(requireContext(), contact.detail, contact.name))
    }
}
