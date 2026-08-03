package com.calleridapp.numberlookup.ui.lookup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.calleridapp.numberlookup.data.lookup.IdentifyTraceStore

/** Backs the standalone search-history screen; reads/clears the shared history store. */
class IdentifyTraceViewModel(app: Application) : AndroidViewModel(app) {

    private val historyStore = IdentifyTraceStore(app)

    private val _history = MutableLiveData<List<TraceEntry>>(emptyList())
    val history: LiveData<List<TraceEntry>> = _history

    fun load() {
        _history.value = historyStore.all()
    }

    fun clear() {
        historyStore.clear()
        _history.value = emptyList()
    }
}
