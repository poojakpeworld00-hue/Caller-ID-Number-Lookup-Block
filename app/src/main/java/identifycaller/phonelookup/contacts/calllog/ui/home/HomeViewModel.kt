package identifycaller.phonelookup.contacts.calllog.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import identifycaller.phonelookup.contacts.calllog.data.CallEntry
import identifycaller.phonelookup.contacts.calllog.data.CallLogItem
import identifycaller.phonelookup.contacts.calllog.data.CallLogRepository
import identifycaller.phonelookup.contacts.calllog.ui.common.CallFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for [HomeFragment]. Exposes protection stats and the latest calls.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val callLogRepository = CallLogRepository(app)

    private val _blockedCount = MutableLiveData(0)
    val blockedCount: LiveData<Int> = _blockedCount

    private val _spamCount = MutableLiveData(0)
    val spamCount: LiveData<Int> = _spamCount

    private val _recent = MutableLiveData<List<CallLogItem>>(emptyList())
    val recent: LiveData<List<CallLogItem>> = _recent

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

    private fun toItem(entry: CallEntry): CallLogItem {
        val ctx = getApplication<Application>()
        val typeLabel = ctx.getString(CallFormat.typeLabelRes(entry.type))
        val time = CallFormat.timeLabel(entry.date)
        val duration = CallFormat.durationLabel(entry.durationSec)
        val info = buildString {
            append(typeLabel).append(" · ").append(time)
            if (duration.isNotEmpty()) append(" · ").append(duration)
        }
        return CallLogItem(
            name = CallFormat.displayName(entry.name, entry.number),
            time = time,
            info = info,
            initials = CallFormat.initials(entry.name, entry.number),
            type = entry.type,
            number = entry.number,
            identified = !entry.name.isNullOrBlank()
        )
    }
}
