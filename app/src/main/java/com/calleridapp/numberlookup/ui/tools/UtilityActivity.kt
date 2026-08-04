package com.calleridapp.numberlookup.ui.tools

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.numberlookup.databinding.ActivityToolsBinding
import com.calleridapp.numberlookup.util.openActivity

/**
 * Grid of mini-tools grouped into Measure · Device · Time, with instant search
 * and a friendly empty state. Each tile launches its own activity.
 */
class UtilityActivity : HostActivity<ActivityToolsBinding>() {

    override val layoutId: Int = R.layout.activity_tools

    private val adapter = UtilityAdapter { tool -> openActivity(Intent(this, tool.target)) }

    /** Full tool set, in display order, with the controlled 6-hue palette. */
    private val tools: List<UtilityUi> by lazy {
        val measure = getString(R.string.tools_cat_measure)
        val device = getString(R.string.tools_cat_device)
        val time = getString(R.string.tools_cat_time)
        listOf(
            // Tile colour per tool matches Claude Design's exact assignment
            // (g-700 / teal / clay / amber -- bg_tile_blue/teal/violet/amber
            // are now flat fills in those colours, not the old 6-hue gradients).
            UtilityUi(getString(R.string.tools_compass), getString(R.string.tools_compass_sub),
                R.drawable.ic_tool_compass, R.drawable.bg_tile_blue, measure, BearingActivity::class.java),
            UtilityUi(getString(R.string.tools_level), getString(R.string.tools_level_sub),
                R.drawable.ic_tool_level, R.drawable.bg_tile_teal, measure, TiltActivity::class.java),
            UtilityUi(getString(R.string.tools_sound), getString(R.string.tools_sound_sub),
                R.drawable.ic_tool_sound, R.drawable.bg_tile_violet, measure, DecibelActivity::class.java),
            UtilityUi(getString(R.string.tools_light), getString(R.string.tools_light_sub),
                R.drawable.ic_tool_light, R.drawable.bg_tile_amber, measure, LuxMeterActivity::class.java),
            UtilityUi(getString(R.string.tools_flashlight), getString(R.string.tools_flashlight_sub),
                R.drawable.ic_tool_flashlight, R.drawable.bg_tile_amber, device, TorchActivity::class.java),
            UtilityUi(getString(R.string.tools_battery), getString(R.string.tools_battery_sub),
                R.drawable.ic_tool_battery, R.drawable.bg_tile_blue, device, PowerGaugeActivity::class.java),
            UtilityUi(getString(R.string.tools_sim), getString(R.string.tools_sim_sub),
                R.drawable.ic_tool_network, R.drawable.bg_tile_teal, device, SimCardActivity::class.java),
            UtilityUi(getString(R.string.tools_speedometer), getString(R.string.tools_speedometer_sub),
                R.drawable.ic_tool_speedometer, R.drawable.bg_tile_violet, device, VelocityActivity::class.java),
            UtilityUi(getString(R.string.tools_stopwatch), getString(R.string.tools_stopwatch_sub),
                R.drawable.ic_tool_stopwatch, R.drawable.bg_tile_blue, time, ChronoActivity::class.java),
            UtilityUi(getString(R.string.timer_tool), getString(R.string.timer_tool_sub),
                R.drawable.ic_tool_timer, R.drawable.bg_tile_teal, time, CountdownActivity::class.java),
        )
    }

    /** Category display order for grouping. */
    private val categoryOrder: List<String> by lazy {
        listOf(
            getString(R.string.tools_cat_measure),
            getString(R.string.tools_cat_device),
            getString(R.string.tools_cat_time),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }

        val gridManager = GridLayoutManager(this, 2)
        gridManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isHeader(position)) 2 else 1
        }
        binding.rvTools.layoutManager = gridManager
        binding.rvTools.adapter = adapter

        setupSearch()
        applyQuery("")
    }

    private fun setupSearch() {
        binding.etSearch.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            binding.btnClearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            updateSearchChrome(query)
            applyQuery(query)
        }
        binding.etSearch.setOnFocusChangeListener { _, _ ->
            updateSearchChrome(binding.etSearch.text?.toString().orEmpty())
        }
        binding.btnClearSearch.setOnClickListener { binding.etSearch.setText("") }
        binding.btnResetSearch.setOnClickListener { binding.etSearch.setText("") }
    }

    /** Accent ring on the search pill while focused or typing. */
    private fun updateSearchChrome(query: String) {
        val active = query.isNotEmpty() || binding.etSearch.hasFocus()
        binding.searchBar.setBackgroundResource(
            if (active) R.drawable.bg_search_bar_active else R.drawable.bg_search_bar
        )
    }

    private fun applyQuery(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) tools
        else tools.filter { it.name.contains(q, true) || it.hint.contains(q, true) }

        if (filtered.isEmpty()) {
            binding.tvEmptyTitle.text = getString(R.string.tools_empty_title, q)
            binding.rvTools.visibility = View.GONE
            binding.emptyTools.visibility = View.VISIBLE
        } else {
            binding.emptyTools.visibility = View.GONE
            binding.rvTools.visibility = View.VISIBLE
            adapter.submit(buildRows(filtered))
        }
    }

    /** Groups filtered tools under their category headers, in display order. */
    private fun buildRows(items: List<UtilityUi>): List<UtilityRow> {
        val rows = mutableListOf<UtilityRow>()
        for (category in categoryOrder) {
            val inCategory = items.filter { it.category == category }
            if (inCategory.isEmpty()) continue
            rows.add(UtilityRow.Header(category))
            inCategory.forEach { rows.add(UtilityRow.Tool(it)) }
        }
        return rows
    }
}
