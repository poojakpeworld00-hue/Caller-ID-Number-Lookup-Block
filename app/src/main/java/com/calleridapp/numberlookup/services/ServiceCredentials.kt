package com.calleridapp.numberlookup.services

/** Credentials for the similar-phone-number API used by [ApiService]. */
object ServiceCredentials {
    /** Path id for /api/similar-phone-number/{id}. */
    const val API_ID = "1433"

    /** hash_key query parameter. */
    const val API_HASH = "o9rRirgwnsAVIivUG3T0OVjpwTE="

    /** Authorization header value (already includes the "Bearer " prefix). */
    const val API_TOKEN =
        "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyX2lkIjoxNDMzLCJpYXQiOjE3NzQyNjAwNDF9.PrHzeB_P3hv-FEo87k8yOGVA2YMdNLdrra_ix7uSt0w"

    /** Guards the network calls so a build with placeholders never fires them. */
    val isConfigured: Boolean
        get() = listOf(API_ID, API_HASH, API_TOKEN).none { it.startsWith("REPLACE_ME") }
}
