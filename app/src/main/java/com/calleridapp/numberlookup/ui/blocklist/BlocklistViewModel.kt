package com.calleridapp.numberlookup.ui.blocklist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.BlockedEntry
import com.calleridapp.numberlookup.data.BlocklistManager
import com.calleridapp.numberlookup.data.ContactsRepository

class BlocklistViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = BlocklistManager(app)
    private val contacts = ContactsRepository(app)

    private val _rows = MutableLiveData<List<BlockedRowUi>>(emptyList())
    val rows: LiveData<List<BlockedRowUi>> = _rows

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
    private fun BlockedEntry.toRow(): BlockedRowUi {
        val name = contacts.lookupNameByNumber(number)?.takeIf { it.isNotBlank() }
        return BlockedRowUi(
            entry = this,
            label = name ?: getApplication<Application>().getString(R.string.blocklist_unknown_caller),
            isSpam = false,
        )
    }
}
