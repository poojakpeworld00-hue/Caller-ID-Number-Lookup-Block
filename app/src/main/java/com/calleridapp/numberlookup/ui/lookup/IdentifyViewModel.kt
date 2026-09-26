package com.calleridapp.numberlookup.ui.lookup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.calleridapp.numberlookup.data.PeopleSource
import com.calleridapp.numberlookup.data.lookup.IdentifyTraceStore
import com.calleridapp.numberlookup.data.lookup.OfflineDigitIdentify
import com.calleridapp.numberlookup.models.DialResponse
import com.calleridapp.numberlookup.services.ServiceCredentials
import com.calleridapp.numberlookup.services.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class IdentifyViewModel(app: Application) : AndroidViewModel(app) {

    private val contactsRepository = PeopleSource(app)
    private val historyStore = IdentifyTraceStore(app)

    private val _state = MutableLiveData<IdentifyState>(IdentifyState.Idle)
    val state: LiveData<IdentifyState> = _state

    private val _history = MutableLiveData(historyStore.all())
    val history: LiveData<List<TraceEntry>> = _history

    private var searchToken = 0

    // Territory selected in the picker (defaults to device region).
    private var selectedIso: String? = null
    private var selectedDial: String? = null

    fun setRegion(iso: String, dial: String) {
        selectedIso = iso
        selectedDial = dial
    }

    fun search(raw: String) {
        val typed = DigitInfo.normalize(raw)
        // Apply the chosen country's dialing code when the user didn't type a '+'.
        val normalized = when {
            typed.startsWith("+") -> typed
            !selectedDial.isNullOrBlank() -> "+" + selectedDial + typed.filter { it.isDigit() }
            else -> typed
        }
        val digitCount = normalized.count { it.isDigit() }
        if (digitCount < MIN_DIGITS) {
            _state.value = IdentifyState.Idle
            return
        }

        val token = ++searchToken
        _state.value = IdentifyState.Loading
        viewModelScope.launch {
            // Never blank — a blank region makes libphonenumber fail to parse
            // bare numbers, nulling out country/carrier/line-type/city.
            val region = (selectedIso ?: Locale.getDefault().country).ifBlank { "US" }
            // Show the shimmer for a fixed minimum so the loading state is visible.
            val data = withContext(Dispatchers.IO) {
                val contactName = contactsRepository.lookupNameByNumber(normalized)
                val offline = OfflineDigitIdentify.lookup(normalized, region) // libphonenumber, no network
                val apiResponse = fetchFromApi(normalized)
                Triple(contactName, offline, apiResponse)
            }
            delay(SHIMMER_MIN_MS)
            if (token != searchToken) return@launch // a newer query superseded this one

            val (contactName, offline, apiResponse) = data
            val apiList = apiResponse?.data.orEmpty()
            // Names people saved this number under. Entries with no letters at all
            // ("98765 43210", "+91…", "1") are just the number echoed back, not a name.
            val apiNames = apiList
                .mapNotNull { it.name?.trim()?.takeIf { n -> n.any(Char::isLetter) } }
            // Prefer the community/network name so a saved contact shows how OTHERS
            // identify this number (not the name you already gave it). Fall back to
            // your own contact name only when the network has no name at all.
            val apiPrimary = apiNames.firstOrNull()
            val displayName = apiPrimary ?: contactName

            // Pull each enrichment field from whichever backend record has it — row
            // level first, then contact-saver's number-level fields — then fall back
            // to the offline (libphonenumber) result.
            val apiCarrier = apiList.firstNotNullOfOrNull { it.carrierOrNull }
            val apiCountry = apiList.firstNotNullOfOrNull { it.country?.takeIf { c -> c.isNotBlank() } }
                ?: apiResponse?.country?.takeIf { it.isNotBlank() }?.let(::isoToCountryName)
            val apiLineType = apiList.firstNotNullOfOrNull { it.lineTypeOrNull }
            val apiCity = apiList.firstNotNullOfOrNull { it.city?.takeIf { c -> c.isNotBlank() } }
                ?: apiResponse?.location?.let { loc ->
                    listOfNotNull(loc.city, loc.state)
                        .map(String::trim).filter(String::isNotBlank).distinct()
                        .joinToString(", ").takeIf { it.isNotBlank() }
                }
            val api = apiList.firstOrNull()

            // "Also known as": distinct network names, excluding the primary shown name
            // AND your own saved contact name — so your own name is never echoed here.
            val nicknames = apiNames
                .distinct()
                .filter {
                    !it.equals(displayName, ignoreCase = true) &&
                        !it.equals(contactName, ignoreCase = true)
                }
                .take(10)

            val result = IdentifyResult(
                name = displayName,
                number = DigitInfo.format(normalized),
                rawNumber = normalized,
                inContacts = contactName != null,
                regionCode = DigitInfo.regionCode(normalized),
                country = apiCountry ?: offline?.regionName ?: offline?.location,
                carrier = apiCarrier ?: offline?.carrier,
                lineType = apiLineType ?: offline?.lineType,
                valid = offline?.valid,
                city = apiCity ?: offline?.location,
                isSpam = apiResponse?.spam == true || api?.is_spam == true || api?.is_user_spam == true,
                spamType = api?.spamType,
                nicknames = nicknames
            )
            Log.d(TAG, "carrier: api=$apiCarrier offline=${offline?.carrier} → ${result.carrier}")
            Log.d(TAG, "result: carrier=${result.carrier}, lineType=${result.lineType}, country=${result.country}")
            _state.value = IdentifyState.Result(result)
            saveToHistory(result)
        }
    }

    /** Queries the caller-ID API for this number; null when unavailable or failed. */
    private suspend fun fetchFromApi(phone: String): DialResponse? = runCatching {
        if (!ServiceCredentials.isConfigured) {
            Log.w(TAG, "checkPhoneNumber skipped: no API key configured")
            return@runCatching null
        }
        val response = RetrofitClient.api.checkPhoneNumber(phone = phone)
        if (response.isSuccessful) {
            response.body()
        } else {
            Log.e(TAG, "checkPhoneNumber failed (${response.code()})")
            null
        }
    }.onFailure { Log.e(TAG, "checkPhoneNumber error: ${it.message}") }.getOrNull()

    /** `IN` → `India`; the raw code when the platform doesn't know it. */
    private fun isoToCountryName(iso: String): String =
        Locale("", iso).displayCountry.takeIf { it.isNotBlank() && !it.equals(iso, true) } ?: iso

    private fun saveToHistory(result: IdentifyResult) {
        val subtitle = result.country
            ?: DigitInfo.regionName(result.rawNumber)
            ?: result.number
        historyStore.add(
            TraceEntry(
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
        _state.value = IdentifyState.Idle
    }

    fun regionName(normalized: String): String? = DigitInfo.regionName(normalized)

    companion object {
        private const val TAG = "IdentifyViewModel"
        private const val MIN_DIGITS = 3
        private const val SHIMMER_MIN_MS = 2_000L
    }
}
