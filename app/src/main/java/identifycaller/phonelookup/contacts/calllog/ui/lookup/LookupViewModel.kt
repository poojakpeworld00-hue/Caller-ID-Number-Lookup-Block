package identifycaller.phonelookup.contacts.calllog.ui.lookup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import android.util.Log
import identifycaller.phonelookup.contacts.calllog.data.ContactsRepository
import identifycaller.phonelookup.contacts.calllog.data.lookup.LookupHistoryStore
import identifycaller.phonelookup.contacts.calllog.data.lookup.OfflineNumberLookup
import identifycaller.phonelookup.contacts.calllog.models.PhoneData
import identifycaller.phonelookup.contacts.calllog.services.ApiCredentials
import identifycaller.phonelookup.contacts.calllog.services.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LookupViewModel(app: Application) : AndroidViewModel(app) {

    private val contactsRepository = ContactsRepository(app)
    private val historyStore = LookupHistoryStore(app)

    private val _state = MutableLiveData<LookupState>(LookupState.Idle)
    val state: LiveData<LookupState> = _state

    private val _history = MutableLiveData(historyStore.all())
    val history: LiveData<List<HistoryEntry>> = _history

    private var searchToken = 0

    // Country selected in the picker (defaults to device region).
    private var selectedIso: String? = null
    private var selectedDial: String? = null

    fun setRegion(iso: String, dial: String) {
        selectedIso = iso
        selectedDial = dial
    }

    fun search(raw: String) {
        val typed = NumberInfo.normalize(raw)
        // Apply the chosen country's dialing code when the user didn't type a '+'.
        val normalized = when {
            typed.startsWith("+") -> typed
            !selectedDial.isNullOrBlank() -> "+" + selectedDial + typed.filter { it.isDigit() }
            else -> typed
        }
        val digitCount = normalized.count { it.isDigit() }
        if (digitCount < MIN_DIGITS) {
            _state.value = LookupState.Idle
            return
        }

        val token = ++searchToken
        _state.value = LookupState.Loading
        viewModelScope.launch {
            // Never blank — a blank region makes libphonenumber fail to parse
            // bare numbers, nulling out country/carrier/line-type/city.
            val region = (selectedIso ?: Locale.getDefault().country).ifBlank { "US" }
            // Show the shimmer for a fixed minimum so the loading state is visible.
            val data = withContext(Dispatchers.IO) {
                val contactName = contactsRepository.lookupNameByNumber(normalized)
                val offline = OfflineNumberLookup.lookup(normalized, region) // libphonenumber, no network
                val apiList = fetchFromApi(normalized)
                Triple(contactName, offline, apiList)
            }
            delay(SHIMMER_MIN_MS)
            if (token != searchToken) return@launch // a newer query superseded this one

            val (contactName, offline, apiList) = data
            val api = apiList.firstOrNull()
            // Prefer the community/network name so a saved contact shows how OTHERS
            // identify this number (not the name you already gave it). Fall back to
            // your own contact name only when the network has no name at all.
            val apiPrimary = api?.name?.trim()?.takeIf { it.isNotBlank() }
            val displayName = apiPrimary ?: contactName

            // Pull each enrichment field from whichever backend record has it,
            // then fall back to the offline (libphonenumber) result.
            val apiCarrier = apiList.firstNotNullOfOrNull { it.carrierOrNull }
            val apiCountry = apiList.firstNotNullOfOrNull { it.country?.takeIf { c -> c.isNotBlank() } }
            val apiLineType = apiList.firstNotNullOfOrNull { it.lineTypeOrNull }
            val apiCity = apiList.firstNotNullOfOrNull { it.city?.takeIf { c -> c.isNotBlank() } }

            // "Also known as": distinct network names, excluding the primary shown name
            // AND your own saved contact name — so your own name is never echoed here.
            val nicknames = apiList
                .mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotBlank() } }
                .distinct()
                .filter {
                    !it.equals(displayName, ignoreCase = true) &&
                        !it.equals(contactName, ignoreCase = true)
                }
                .take(10)

            val result = LookupResult(
                name = displayName,
                number = NumberInfo.format(normalized),
                rawNumber = normalized,
                inContacts = contactName != null,
                regionCode = NumberInfo.regionCode(normalized),
                country = apiCountry ?: offline?.regionName ?: offline?.location,
                carrier = apiCarrier ?: offline?.carrier,
                lineType = apiLineType ?: offline?.lineType,
                valid = offline?.valid,
                city = apiCity ?: offline?.location,
                isSpam = api?.is_spam == true || api?.is_user_spam == true,
                spamType = api?.spamType,
                nicknames = nicknames
            )
            Log.d(TAG, "carrier: api=$apiCarrier offline=${offline?.carrier} → ${result.carrier}")
            Log.d(TAG, "result: carrier=${result.carrier}, lineType=${result.lineType}, country=${result.country}")
            _state.value = LookupState.Result(result)
            saveToHistory(result)
        }
    }

    /** Queries the caller-ID API for this number; returns all matching records (may be empty). */
    private suspend fun fetchFromApi(phone: String): List<PhoneData> = runCatching {
        val response = RetrofitClient.api.checkPhoneNumber(
            id = ApiCredentials.API_ID,
            phone = phone,
            hashKey = ApiCredentials.API_HASH,
            token = ApiCredentials.API_TOKEN
        )
        if (response.isSuccessful) {
            response.body()?.data.orEmpty()
        } else {
            Log.e(TAG, "checkPhoneNumber failed (${response.code()})")
            emptyList()
        }
    }.onFailure { Log.e(TAG, "checkPhoneNumber error: ${it.message}") }.getOrDefault(emptyList())

    private fun saveToHistory(result: LookupResult) {
        val subtitle = result.country
            ?: NumberInfo.regionName(result.rawNumber)
            ?: result.number
        historyStore.add(
            HistoryEntry(
                rawNumber = result.rawNumber,
                number = result.number,
                name = result.name,
                subtitle = subtitle
            )
        )
        _history.value = historyStore.all()
    }

    fun clearHistory() {
        historyStore.clear()
        _history.value = emptyList()
    }

    /** Re-reads the persisted history (e.g. after the standalone history screen edits it). */
    fun refreshHistory() {
        _history.value = historyStore.all()
    }

    fun clear() {
        _state.value = LookupState.Idle
    }

    fun regionName(normalized: String): String? = NumberInfo.regionName(normalized)

    companion object {
        private const val TAG = "LookupViewModel"
        private const val MIN_DIGITS = 3
        private const val SHIMMER_MIN_MS = 2_000L
    }
}
