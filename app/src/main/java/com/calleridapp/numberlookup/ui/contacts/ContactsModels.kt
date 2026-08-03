package com.calleridapp.numberlookup.ui.contacts

import com.calleridapp.numberlookup.data.ContactItem

/** Top filter tabs for the contacts list. */
enum class ContactFilter { ALL, FAVORITES, RECENTS, GROUPS }

/** A row in the contacts list: an alphabetical section letter or a contact. */
sealed interface ContactRow {
    data class Header(val letter: String) : ContactRow
    data class Item(val contact: ContactItem) : ContactRow
}
