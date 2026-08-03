package com.calleridapp.numberlookup.ui.recents

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.CallEntry
import com.calleridapp.numberlookup.data.CallLogRepository
import com.calleridapp.numberlookup.data.CallType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class RecentsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CallLogRepository(app)
    private var allCalls: List<CallEntry> = emptyList()
    private var query: String = ""

    private val _rows = MutableLiveData<List<RecentRow>>(emptyList())
    val rows: LiveData<List<RecentRow>> = _rows

    private val _filter = MutableLiveData(CallFilter.ALL)
    val filter: LiveData<CallFilter> = _filter

    private val _sort = MutableLiveData(CallSort.NEWEST)
    val sort: LiveData<CallSort> = _sort

    fun load() {
        viewModelScope.launch {
            allCalls = withContext(Dispatchers.IO) { repository.getCalls() }
            rebuild()
        }
    }

    fun setFilter(filter: CallFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        rebuild()
    }

    fun setSort(sort: CallSort) {
        if (_sort.value == sort) return
        _sort.value = sort
        rebuild()
    }

    fun setQuery(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        rebuild()
    }

    private fun rebuild() {
        val active = _filter.value ?: CallFilter.ALL
        val order = _sort.value ?: CallSort.NEWEST
        val q = query.lowercase(Locale.getDefault())
        val filtered = allCalls.filter { call ->
            matches(active, call.type) && (q.isEmpty() ||
                call.name?.lowercase(Locale.getDefault())?.contains(q) == true ||
                call.number.lowercase(Locale.getDefault()).contains(q))
        }
        _rows.value = when (order) {
            // Date sorts keep the Today/Yesterday/… section headers.
            CallSort.NEWEST -> group(filtered.sortedByDescending { it.date })
            CallSort.OLDEST -> group(filtered.sortedBy { it.date })
            // Name sorts flatten the list — date headers no longer apply.
            CallSort.NAME_ASC ->
                filtered.sortedBy { sortName(it) }.map { RecentRow.Call(it) }
            CallSort.NAME_DESC ->
                filtered.sortedByDescending { sortName(it) }.map { RecentRow.Call(it) }
        }
    }

    /** Key used for name sorting: caller name when present, otherwise the number. */
    private fun sortName(call: CallEntry): String =
        (call.name?.takeIf { it.isNotBlank() } ?: call.number).lowercase(Locale.getDefault())

    private fun matches(filter: CallFilter, type: CallType): Boolean = when (filter) {
        CallFilter.ALL -> true
        CallFilter.INCOMING -> type == CallType.INCOMING
        CallFilter.OUTGOING -> type == CallType.OUTGOING
        CallFilter.MISSED -> type == CallType.MISSED
    }

    private fun group(calls: List<CallEntry>): List<RecentRow> {
        if (calls.isEmpty()) return emptyList()

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        val yesterdayStart = todayStart - DAY_MS
        val weekStart = todayStart - 6 * DAY_MS

        val rows = mutableListOf<RecentRow>()
        var lastBucket = -1
        for (call in calls) {
            val bucket = when {
                call.date >= todayStart -> 0
                call.date >= yesterdayStart -> 1
                call.date >= weekStart -> 2
                else -> 3
            }
            if (bucket != lastBucket) {
                rows.add(RecentRow.Header(bucketTitle(bucket)))
                lastBucket = bucket
            }
            rows.add(RecentRow.Call(call))
        }
        return rows
    }

    private fun bucketTitle(bucket: Int): Int = when (bucket) {
        0 -> R.string.recents_today
        1 -> R.string.recents_yesterday
        2 -> R.string.recents_this_week
        else -> R.string.recents_earlier
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
