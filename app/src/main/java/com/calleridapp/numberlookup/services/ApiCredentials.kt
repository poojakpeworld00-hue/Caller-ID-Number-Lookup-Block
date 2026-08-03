package com.calleridapp.numberlookup.services

/**
 * Credentials for the similar-phone-number API used by [ApiService].
 *
 * PLACEHOLDERS. The source app's live account id, hash key and bearer token
 * were removed on cloning -- calling that backend with them would bill and
 * identify the original app. Supply your own before release; [isConfigured]
 * lets callers skip the request while they are unset.
 */
object ApiCredentials {
    /** Path id for /api/similar-phone-number/{id}. */
    const val API_ID = "REPLACE_ME_API_ID"

    /** hash_key query parameter. */
    const val API_HASH = "REPLACE_ME_API_HASH"

    /** Authorization header value (must include the "Bearer " prefix). */
    const val API_TOKEN = "REPLACE_ME_API_BEARER_TOKEN"

    /** False until real credentials are supplied. */
    val isConfigured: Boolean
        get() = listOf(API_ID, API_HASH, API_TOKEN).none { it.startsWith("REPLACE_ME") }
}
