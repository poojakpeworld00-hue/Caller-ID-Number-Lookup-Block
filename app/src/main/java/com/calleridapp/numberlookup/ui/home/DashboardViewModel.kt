package com.calleridapp.numberlookup.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.calleridapp.numberlookup.data.CallRecord
import com.calleridapp.numberlookup.data.CallCardData
import com.calleridapp.numberlookup.data.CallLogSource
import com.calleridapp.numberlookup.ui.common.CallPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for [DashboardFragment]. Exposes protection stats and the latest calls.
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val callLogRepository = CallLogSource(app)

    private val _blockedCount = MutableLiveData(0)
    val blockedCount: LiveData<Int> = _blockedCount

    private val _spamCount = MutableLiveData(0)
    val spamCount: LiveData<Int> = _spamCount

    private val _recent = MutableLiveData<List<CallCardData>>(emptyList())
    val recent: LiveData<List<CallCardData>> = _recent

    init {
        // TODO: derive from real protection history once available.
        _blockedCount.value = 128
        _spamCount.value = 37
    }

    /** Loads the two most-recent calls. Caller must ensure READ_CALL_LOG is granted. */
    fun loadRecent() {
        viewModelScope.launch {
            val calls = withContext(Dispatchers.IO) { callLogRepository.getCalls(limit = 2) }
            _recent.value = calls.map { toItem(it) }
        }
    }

    private fun toItem(entry: CallRecord): CallCardData {
        val ctx = getApplication<Application>()
        val typeLabel = ctx.getString(CallPresenter.typeLabelRes(entry.type))
        val time = CallPresenter.timeLabel(entry.date)
        val duration = CallPresenter.durationLabel(entry.durationSec)
        val info = buildString {
            append(typeLabel).append(" · ").append(time)
            if (duration.isNotEmpty()) append(" · ").append(duration)
        }
        return CallCardData(
            name = CallPresenter.displayName(entry.name, entry.number),
            time = time,
            info = info,
            initials = CallPresenter.initials(entry.name, entry.number),
            type = entry.type,
            number = entry.number,
            identified = !entry.name.isNullOrBlank()
        )
    }
}
