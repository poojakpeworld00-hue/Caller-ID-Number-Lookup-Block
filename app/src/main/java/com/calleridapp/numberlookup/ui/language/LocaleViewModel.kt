package com.calleridapp.numberlookup.ui.language

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.calleridapp.numberlookup.base.HostViewModel

class LocaleViewModel : HostViewModel() {

    val languages: List<LocaleItem> = Locales.all

    // The "Suggested" group is chosen by the user's region (GEO). "All languages"
    // holds everything not already surfaced under Suggested, so nothing repeats.
    // Both are LiveData because the region can arrive asynchronously (IP lookup),
    // refining an initial guess made from the device locale.
    private val _suggested = MutableLiveData<List<LocaleItem>>()
    val suggested: LiveData<List<LocaleItem>> = _suggested

    private val _others = MutableLiveData<List<LocaleItem>>()
    val others: LiveData<List<LocaleItem>> = _others

    private val _selectedTag = MutableLiveData(Locales.all.first().tag)
    val selectedTag: LiveData<String> = _selectedTag

    /** Remembers the last region applied so an IP result equal to it is a no-op. */
    private var appliedCountry: String? = "__none__"

    fun init(currentTag: String) {
        if (languages.any { it.tag == currentTag }) {
            _selectedTag.value = currentTag
        }
    }

    /**
     * Rebuilds the Suggested / All groups for the given country (ISO-3166 alpha-2,
     * null when unknown). Idempotent for the same country so repeated calls from
     * the locale guess and the IP refine don't thrash the lists.
     */
    fun applyCountry(iso2: String?) {
        val normalized = iso2?.uppercase()
        if (normalized == appliedCountry) return
        appliedCountry = normalized

        val suggested = Locales.suggestedFor(normalized)
        val suggestedTags = suggested.map { it.tag }.toSet()
        _suggested.value = suggested
        _others.value = languages.filterNot { it.tag in suggestedTags }
    }

    fun select(tag: String) {
        if (_selectedTag.value != tag) _selectedTag.value = tag
    }
}
