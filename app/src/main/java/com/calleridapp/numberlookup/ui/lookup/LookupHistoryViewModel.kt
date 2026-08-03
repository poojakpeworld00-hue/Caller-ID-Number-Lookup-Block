package com.calleridapp.numberlookup.ui.lookup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.calleridapp.numberlookup.data.lookup.LookupHistoryStore

/** Backs the standalone search-history screen; reads/clears the shared history store. */
class LookupHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val historyStore = LookupHistoryStore(app)

    private val _history = MutableLiveData<List<HistoryEntry>>(emptyList())
    val history: LiveData<List<HistoryEntry>> = _history

    fun load() {
        _history.value = historyStore.all()
    }

    fun clear() {
        historyStore.clear()
        _history.value = emptyList()
    }
}
