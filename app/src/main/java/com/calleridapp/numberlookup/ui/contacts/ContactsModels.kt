package com.calleridapp.numberlookup.ui.contacts

import com.calleridapp.numberlookup.data.PersonItem

/** Top filter tabs for the contacts list. */
enum class PersonFilter { ALL, FAVORITES, RECENTS, GROUPS }

/** A row in the contacts list: an alphabetical section letter or a contact. */
sealed interface PersonRow {
    data class Header(val letter: String) : PersonRow
    data class Item(val contact: PersonItem) : PersonRow
}
