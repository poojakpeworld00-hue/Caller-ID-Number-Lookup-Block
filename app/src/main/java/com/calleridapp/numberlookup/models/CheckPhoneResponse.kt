package com.calleridapp.numberlookup.models

/**
 * `GET similar-phone-number` response. contact-saver puts the number-level facts
 * (ISO country, location, spam flag) at the top level and only names in [data].
 */
data class DialResponse(
    val success: Boolean,
    val count: Int = 0,
    /** ISO 3166 region code of the number, e.g. `IN`. */
    val country: String? = null,
    val location: DialLocation? = null,
    val spam: Boolean = false,
    val data: List<DialData>?
)

data class DialLocation(
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null
)

data class DialData(
    val is_spam: Boolean = false,
    val is_user_spam: Boolean = false,
    val spamReportCounter: Int = 0,
    val spamType: String? = null,
    val name: String? = null,
    val profile: String? = null,
    val city: String? = null,
    val country: String? = null,
    val carrier: String? = null,
    // Line type may arrive under different keys depending on the backend.
    val line_type: String? = null,
    val lineType: String? = null,
    val type: String? = null
) {
    /** Carrier if present and non-blank. */
    val carrierOrNull: String? get() = carrier?.takeIf { it.isNotBlank() }

    /** Best-effort line type from whichever field the server populated. */
    val lineTypeOrNull: String?
        get() = (line_type ?: lineType ?: type)?.takeIf { it.isNotBlank() }
}
