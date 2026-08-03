package com.calleridapp.numberlookup.models

import com.calleridapp.numberlookup.data.PersonItem

data class DataPersonForUpload(
    val contactId: String,
    val firstNameOriginal: String? = "",
    val surName: String? = "",
    val jobPosition: String? = "",
    val websites: String? = "",
    var contactEmail: String? = "",
    var contactNumber: MutableList<PhoneDigitUpload>,
    var contactCreationTime: Long? = null,
)

data class PhoneDigitUpload(
    val normalizedNumber: String,
    val type: String,
)

/** Maps a device contact into the server upload shape. */
fun PersonItem.toUploadModel(): DataPersonForUpload {
    val parts = name.trim().split(" ").filter { it.isNotEmpty() }
    val firstName = parts.firstOrNull() ?: name
    val surName = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""

    return DataPersonForUpload(
        contactId = detail.ifBlank { name },
        firstNameOriginal = firstName,
        surName = surName,
        jobPosition = "",
        websites = "",
        contactEmail = "",
        contactNumber = mutableListOf(
            PhoneDigitUpload(normalizedNumber = detail, type = "mobile")
        ),
        contactCreationTime = System.currentTimeMillis()
    )
}

fun List<PersonItem>.toUploadList(): List<DataPersonForUpload> = map { it.toUploadModel() }
