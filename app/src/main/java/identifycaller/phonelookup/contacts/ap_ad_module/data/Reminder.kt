package identifycaller.phonelookup.contacts.ap_ad_module.data

data class Reminder(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val description: String,
    val dateTime: Long,
    val color: Int // Add this// optional
)

