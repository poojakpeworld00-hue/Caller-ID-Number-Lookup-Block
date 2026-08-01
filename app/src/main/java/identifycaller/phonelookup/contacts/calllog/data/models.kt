package identifycaller.phonelookup.contacts.calllog.data

enum class CallType { INCOMING, OUTGOING, MISSED, SPAM }

data class CallLogItem(
    val name: String,
    val time: String,
    val info: String,
    val initials: String,
    val type: CallType,
    val number: String = "",
    /** True when a contact name resolved; false for unknown/unsaved numbers (→ "Identify"). */
    val identified: Boolean = true
)

data class ContactItem(
    val name: String,
    val detail: String,
    val initials: String,
    val photoUri: String? = null,
    val starred: Boolean = false,
    val lastContacted: Long = 0L,
    val inGroup: Boolean = false
)

/** Demo data used until real CallLog / Contacts providers are wired in. */
object SampleData {

    val recents: List<CallLogItem> = listOf(
        CallLogItem("Sarah Khan", "9:24", "Incoming · 4m 12s", "SK", CallType.INCOMING),
        CallLogItem("+1 (800) 244-0199", "8:50", "Spam · Telemarketer", "!", CallType.SPAM),
        CallLogItem("Dad Mobile", "7:32", "Missed call", "DM", CallType.MISSED),
        CallLogItem("+44 20 7946 0321", "Tue", "Outgoing · London, UK", "+9", CallType.OUTGOING),
        CallLogItem("Aisha Lawson", "Tue", "Incoming · 1m 03s", "AL", CallType.INCOMING)
    )

    val homeRecent: List<CallLogItem> = recents.take(2)

    val contacts: List<ContactItem> = listOf(
        ContactItem("Aisha Lawson", "+1 (415) 555-0178", "AL"),
        ContactItem("Amir Raza", "Acme Corp", "AR"),
        ContactItem("Dad Mobile", "+1 (415) 555-0143", "DM"),
        ContactItem("Sarah Khan", "Brightline Bank", "SK")
    )
}
