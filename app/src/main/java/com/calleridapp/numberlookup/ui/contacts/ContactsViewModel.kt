package com.calleridapp.numberlookup.ui.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.calleridapp.numberlookup.data.ContactItem
import com.calleridapp.numberlookup.data.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ContactsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ContactsRepository(app)
    private var allContacts: List<ContactItem> = emptyList()
    private var query: String = ""

    private val _rows = MutableLiveData<List<ContactRow>>(emptyList())
    val rows: LiveData<List<ContactRow>> = _rows

    private val _filter = MutableLiveData(ContactFilter.ALL)
    val filter: LiveData<ContactFilter> = _filter

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

    fun setFilter(filter: ContactFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        rebuild()
    }

    private fun rebuild() {
        // Tab filter (keeps name order so alpha grouping / fast-scroll stay valid).
        val byTab = when (_filter.value ?: ContactFilter.ALL) {
            ContactFilter.ALL -> allContacts
            ContactFilter.FAVORITES -> allContacts.filter { it.starred }
            ContactFilter.RECENTS -> allContacts.filter { it.lastContacted > 0L }
            ContactFilter.GROUPS -> allContacts.filter { it.inGroup }
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

    private fun group(contacts: List<ContactItem>): List<ContactRow> {
        if (contacts.isEmpty()) return emptyList()
        val rows = mutableListOf<ContactRow>()
        var lastLetter = ""
        for (contact in contacts) {
            val first = contact.name.firstOrNull()?.uppercaseChar()
            val letter = if (first != null && first.isLetter()) first.toString() else "#"
            if (letter != lastLetter) {
                rows.add(ContactRow.Header(letter))
                lastLetter = letter
            }
            rows.add(ContactRow.Item(contact))
        }
        return rows
    }
}
