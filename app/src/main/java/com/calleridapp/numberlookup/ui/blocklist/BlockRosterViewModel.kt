package com.calleridapp.numberlookup.ui.blocklist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.BarredEntry
import com.calleridapp.numberlookup.data.BlockRosterRegistry
import com.calleridapp.numberlookup.data.PeopleSource

class BlockRosterViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = BlockRosterRegistry(app)
    private val contacts = PeopleSource(app)

    private val _rows = MutableLiveData<List<BarredRowUi>>(emptyList())
    val rows: LiveData<List<BarredRowUi>> = _rows

    private val _count = MutableLiveData(0)
    val count: LiveData<Int> = _count

    init {
        refresh()
    }

    fun add(number: String) {
        manager.add(number)
        refresh()
    }

    fun remove(number: String) {
        manager.remove(number)
        refresh()
    }

    fun isBlocked(number: String): Boolean = manager.isBlocked(number)

    private fun refresh() {
        val entries = manager.getEntries()
        _count.value = entries.size
        _rows.value = entries.map { it.toRow() }
    }

    /**
     * A blocked number the user added has no spam classification, so it renders
     * with the neutral treatment; the label is the contact name when we can
     * resolve one, otherwise a friendly fallback.
     */
    private fun BarredEntry.toRow(): BarredRowUi {
        val name = contacts.lookupNameByNumber(number)?.takeIf { it.isNotBlank() }
        return BarredRowUi(
            entry = this,
            label = name ?: getApplication<Application>().getString(R.string.blocklist_unknown_caller),
            isSpam = false,
        )
    }
}
