package com.calleridapp.numberlookup.ui.recents

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.calleridapp.numberlookup.R
import com.calleridapp.admesh.presentation.NativeBanner
import com.calleridapp.numberlookup.base.BaseFragment
import com.calleridapp.numberlookup.util.openActivity
import com.calleridapp.numberlookup.databinding.FragmentRecentsBinding
import com.calleridapp.numberlookup.ui.MainActivity
import com.calleridapp.numberlookup.ui.detail.CallDetailActivity
import com.calleridapp.numberlookup.ui.dialer.DialerActivity
import com.calleridapp.numberlookup.util.followAdContainer

class RecentsFragment : BaseFragment<FragmentRecentsBinding>() {

    private val viewModel: RecentsViewModel by viewModels()
    private val adapter = RecentsAdapter(
        ::dialNumber,
        ::openDetail,
        onIdentify = { number -> (activity as? MainActivity)?.showLookup(number) }
    )

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentRecentsBinding.inflate(inflater, container, false)

    override fun initView() {
        // Hero bleeds under the status bar; pad its content down by the inset.
        val baseTop = binding.heroHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroHeader) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = baseTop + top)
            insets
        }
        binding.btnRecentsDial.setOnClickListener {
            requireActivity().openActivity<DialerActivity>()
        }
        binding.btnRecentsFilter.setOnClickListener { showSortMenu(it) }

        binding.rvRecents.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecents.adapter = adapter

        // Native banner at the bottom of the recents screen.
        NativeBanner().showNativeBannerNative(requireActivity(), binding.adNativeFrame, binding.adShimmer)
        binding.adNativeDivider.followAdContainer(binding.adNativeFrame)
        binding.adNativeDivider1.followAdContainer(binding.adNativeFrame)

        binding.tabAll.setOnClickListener { viewModel.setFilter(CallFilter.ALL) }
        binding.tabIncoming.setOnClickListener { viewModel.setFilter(CallFilter.INCOMING) }
        binding.tabOutgoing.setOnClickListener { viewModel.setFilter(CallFilter.OUTGOING) }
        binding.tabMissed.setOnClickListener { viewModel.setFilter(CallFilter.MISSED) }
        binding.btnGrant.setOnClickListener {
            requestPermissionChain(
                listOf(Manifest.permission.READ_CALL_LOG)
            ) {
                if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
                (activity as? MainActivity)?.startOverlayPermissionFlow()
            }
        }

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                viewModel.setQuery(text)
                binding.btnClearSearch.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
        })
        binding.btnClearSearch.setOnClickListener { binding.etSearch.setText("") }

        if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate after returning from Settings (or a system dialog) so a freshly
        // granted permission shows the list without needing to leave the screen.
        if (view != null) {
            if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
        }
    }

    override fun initObservers() {
        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submit(rows)
            binding.tvEmpty.visibility =
                if (rows.isEmpty() && hasCallLogPermission()) View.VISIBLE else View.GONE
        }
        viewModel.filter.observe(viewLifecycleOwner) { active ->
            highlightTab(binding.tabAll, active == CallFilter.ALL)
            highlightTab(binding.tabIncoming, active == CallFilter.INCOMING)
            highlightTab(binding.tabOutgoing, active == CallFilter.OUTGOING)
            highlightTab(binding.tabMissed, active == CallFilter.MISSED)
        }
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

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Custom sort popup: a styled card anchored under the filter button that changes the
     * list order (date / name). Type filtering stays on the tabs.
     */
    private fun showSortMenu(anchor: View) {
        val options = listOf(
            R.string.sort_newest to CallSort.NEWEST,
            R.string.sort_oldest to CallSort.OLDEST,
            R.string.sort_name_asc to CallSort.NAME_ASC,
            R.string.sort_name_desc to CallSort.NAME_DESC
        )
        val current = viewModel.sort.value ?: CallSort.NEWEST

        val inflater = LayoutInflater.from(requireContext())
        val content = inflater.inflate(R.layout.popup_sort, null) as LinearLayout
        val container = content.findViewById<LinearLayout>(R.id.sortContainer)

        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 8f * resources.displayMetrics.density
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        options.forEach { (titleRes, sort) ->
            val row = inflater.inflate(R.layout.item_sort_option, container, false)
            row.findViewById<TextView>(R.id.tvSortLabel).setText(titleRes)
            row.findViewById<ImageView>(R.id.ivSortCheck).visibility =
                if (sort == current) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                viewModel.setSort(sort)
                popup.dismiss()
            }
            container.addView(row)
        }

        val yOffset = (4f * resources.displayMetrics.density).toInt()
        popup.showAsDropDown(anchor, 0, yOffset, Gravity.END)
    }

    private fun onPermissionGranted() {
        binding.permState.visibility = View.GONE
        binding.rvRecents.visibility = View.VISIBLE
        viewModel.load()
    }

    private fun showPermissionState() {
        binding.permState.visibility = View.VISIBLE
        binding.rvRecents.visibility = View.GONE
        binding.tvEmpty.visibility = View.GONE
    }

    private fun dialNumber(number: String) = placeCall(number)

    private fun openDetail(entry: com.calleridapp.numberlookup.data.CallEntry) {
        requireActivity().openActivity(CallDetailActivity.newIntent(requireContext(), entry.number, entry.name))
    }
}
