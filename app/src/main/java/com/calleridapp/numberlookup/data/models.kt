package com.calleridapp.numberlookup.data

enum class CallKind { INCOMING, OUTGOING, MISSED, SPAM }

data class CallCardData(
    val name: String,
    val time: String,
    val info: String,
    val initials: String,
    val type: CallKind,
    val number: String = "",
    /** True when a contact name resolved; false for unknown/unsaved numbers (→ "Identify"). */
    val identified: Boolean = true
)

data class PersonItem(
    val name: String,
    val detail: String,
    val initials: String,
    val photoUri: String? = null,
    val starred: Boolean = false,
    val lastContacted: Long = 0L,
    val inGroup: Boolean = false
)

/** Demo data used until real CallLog / Contacts providers are wired in. */
object DemoData {

    val recents: List<CallCardData> = listOf(
        CallCardData("Sarah Khan", "9:24", "Incoming · 4m 12s", "SK", CallKind.INCOMING),
        CallCardData("+1 (800) 244-0199", "8:50", "Spam · Telemarketer", "!", CallKind.SPAM),
        CallCardData("Dad Mobile", "7:32", "Missed call", "DM", CallKind.MISSED),
        CallCardData("+44 20 7946 0321", "Tue", "Outgoing · London, UK", "+9", CallKind.OUTGOING),
        CallCardData("Aisha Lawson", "Tue", "Incoming · 1m 03s", "AL", CallKind.INCOMING)
    )

    val homeRecent: List<CallCardData> = recents.take(2)

    val contacts: List<PersonItem> = listOf(
        PersonItem("Aisha Lawson", "+1 (415) 555-0178", "AL"),
        PersonItem("Amir Raza", "Acme Corp", "AR"),
        PersonItem("Dad Mobile", "+1 (415) 555-0143", "DM"),
        PersonItem("Sarah Khan", "Brightline Bank", "SK")
    )
}
