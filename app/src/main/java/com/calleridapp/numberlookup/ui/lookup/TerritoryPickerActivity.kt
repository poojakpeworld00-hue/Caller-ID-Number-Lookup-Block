package com.calleridapp.numberlookup.ui.lookup

import android.app.Activity
import android.content.Intent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.numberlookup.databinding.ActivityCountryPickerBinding

/** Searchable country list. Returns the chosen country's ISO/dial/name. */
class TerritoryPickerActivity : HostActivity<ActivityCountryPickerBinding>() {

    override val layoutId: Int = R.layout.activity_country_picker

    private lateinit var adapter: TerritoryAdapter

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.countryRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        adapter = TerritoryAdapter { country ->
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_ISO, country.iso2)
                    .putExtra(EXTRA_DIAL, country.dial)
                    .putExtra(EXTRA_NAME, country.name)
            )
            finish()
        }
        binding.rvCountries.layoutManager = LinearLayoutManager(this)
        binding.rvCountries.adapter = adapter
        adapter.submit(Territories.all)

        binding.btnBack.setOnClickListener { goBack() }
        binding.etSearch.addTextChangedListener { text -> filter(text?.toString().orEmpty()) }
    }

    private fun filter(query: String) {
        val q = query.trim()
        val list = if (q.isEmpty()) {
            Territories.all
        } else {
            Territories.all.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.dial.contains(q) ||
                    it.iso2.contains(q, ignoreCase = true)
            }
        }
        adapter.submit(list)
    }

    companion object {
        const val EXTRA_ISO = "extra_iso"
        const val EXTRA_DIAL = "extra_dial"
        const val EXTRA_NAME = "extra_name"
    }
}
