package identifycaller.phonelookup.contacts.calllog.services

/** Credentials for the callerid.kpeworld.com API used by [ApiService]. */
object ApiCredentials {
    /** Path id for /api/similar-phone-number/{id}. */
    const val API_ID = "1433"

    /** hash_key query parameter. */
    const val API_HASH = "o9rRirgwnsAVIivUG3T0OVjpwTE="

    /** Authorization header value (already includes the "Bearer " prefix). */
    const val API_TOKEN =
        "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyX2lkIjoxNDMzLCJpYXQiOjE3NzQyNjAwNDF9.PrHzeB_P3hv-FEo87k8yOGVA2YMdNLdrra_ix7uSt0w"
}
