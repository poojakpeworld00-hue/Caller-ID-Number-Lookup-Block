package com.calleridapp.numberlookup.ui.recents

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.CallRecord
import com.calleridapp.numberlookup.data.CallLogSource
import com.calleridapp.numberlookup.data.CallKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class TimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CallLogSource(app)
    private var allCalls: List<CallRecord> = emptyList()
    private var query: String = ""

    private val _rows = MutableLiveData<List<TimelineRow>>(emptyList())
    val rows: LiveData<List<TimelineRow>> = _rows

    private val _filter = MutableLiveData(CallScope.ALL)
    val filter: LiveData<CallScope> = _filter

    private val _sort = MutableLiveData(CallOrder.NEWEST)
    val sort: LiveData<CallOrder> = _sort

    fun load() {
        viewModelScope.launch {
            allCalls = withContext(Dispatchers.IO) { repository.getCalls() }
            rebuild()
        }
    }

    fun setFilter(filter: CallScope) {
        if (_filter.value == filter) return
        _filter.value = filter
        rebuild()
    }

    fun setSort(sort: CallOrder) {
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
        val active = _filter.value ?: CallScope.ALL
        val order = _sort.value ?: CallOrder.NEWEST
        val q = query.lowercase(Locale.getDefault())
        val filtered = allCalls.filter { call ->
            matches(active, call.type) && (q.isEmpty() ||
                call.name?.lowercase(Locale.getDefault())?.contains(q) == true ||
                call.number.lowercase(Locale.getDefault()).contains(q))
        }
        _rows.value = when (order) {
            // Date sorts keep the Today/Yesterday/… section headers.
            CallOrder.NEWEST -> group(filtered.sortedByDescending { it.date })
            CallOrder.OLDEST -> group(filtered.sortedBy { it.date })
            // Name sorts flatten the list — date headers no longer apply.
            CallOrder.NAME_ASC ->
                filtered.sortedBy { sortName(it) }.map { TimelineRow.Call(it) }
            CallOrder.NAME_DESC ->
                filtered.sortedByDescending { sortName(it) }.map { TimelineRow.Call(it) }
        }
    }

    /** Key used for name sorting: caller name when present, otherwise the number. */
    private fun sortName(call: CallRecord): String =
        (call.name?.takeIf { it.isNotBlank() } ?: call.number).lowercase(Locale.getDefault())

    private fun matches(filter: CallScope, type: CallKind): Boolean = when (filter) {
        CallScope.ALL -> true
        CallScope.INCOMING -> type == CallKind.INCOMING
        CallScope.OUTGOING -> type == CallKind.OUTGOING
        CallScope.MISSED -> type == CallKind.MISSED
    }

    private fun group(calls: List<CallRecord>): List<TimelineRow> {
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

        val rows = mutableListOf<TimelineRow>()
        var lastBucket = -1
        for (call in calls) {
            val bucket = when {
                call.date >= todayStart -> 0
                call.date >= yesterdayStart -> 1
                call.date >= weekStart -> 2
                else -> 3
            }
            if (bucket != lastBucket) {
                rows.add(TimelineRow.Header(bucketTitle(bucket)))
                lastBucket = bucket
            }
            rows.add(TimelineRow.Call(call))
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
