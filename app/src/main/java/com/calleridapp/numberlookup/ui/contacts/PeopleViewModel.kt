package com.calleridapp.numberlookup.ui.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.calleridapp.numberlookup.data.PersonItem
import com.calleridapp.numberlookup.data.PeopleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class PeopleViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PeopleSource(app)
    private var allContacts: List<PersonItem> = emptyList()
    private var query: String = ""

    private val _rows = MutableLiveData<List<PersonRow>>(emptyList())
    val rows: LiveData<List<PersonRow>> = _rows

    private val _filter = MutableLiveData(PersonFilter.ALL)
    val filter: LiveData<PersonFilter> = _filter

    fun load() {
        viewModelScope.launch {
            allContacts = withContext(Dispatchers.IO) { repository.getContacts() }
            rebuild()
        }
    }

    fun setQuery(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        rebuild()
    }

    fun setFilter(filter: PersonFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        rebuild()
    }

    private fun rebuild() {
        // Tab filter (keeps name order so alpha grouping / fast-scroll stay valid).
        val byTab = when (_filter.value ?: PersonFilter.ALL) {
            PersonFilter.ALL -> allContacts
            PersonFilter.FAVORITES -> allContacts.filter { it.starred }
            PersonFilter.RECENTS -> allContacts.filter { it.lastContacted > 0L }
            PersonFilter.GROUPS -> allContacts.filter { it.inGroup }
        }

        val filtered = if (query.isEmpty()) {
            byTab
        } else {
            val q = query.lowercase(Locale.getDefault())
            byTab.filter {
                it.name.lowercase(Locale.getDefault()).contains(q) || it.detail.contains(q)
            }
        }
        _rows.value = group(filtered)
    }

    private fun group(contacts: List<PersonItem>): List<PersonRow> {
        if (contacts.isEmpty()) return emptyList()
        val rows = mutableListOf<PersonRow>()
        var lastLetter = ""
        for (contact in contacts) {
            val first = contact.name.firstOrNull()?.uppercaseChar()
            val letter = if (first != null && first.isLetter()) first.toString() else "#"
            if (letter != lastLetter) {
                rows.add(PersonRow.Header(letter))
                lastLetter = letter
            }
            rows.add(PersonRow.Item(contact))
        }
        return rows
    }
}
