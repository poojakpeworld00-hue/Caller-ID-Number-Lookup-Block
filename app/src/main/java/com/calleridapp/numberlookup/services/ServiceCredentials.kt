package com.calleridapp.numberlookup.services

import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.Scrambled

/**
 * Credential for the contact-saver API used by [ApiService].
 *
 * Comes from `contactsaver.apiKey` in local.properties, XOR-obfuscated into
 * BuildConfig and decoded here at runtime. [AuthInterceptor] attaches it as the
 * `x-api-key` header, so it never appears in an endpoint signature or a URL.
 *
 * `val ... by lazy` rather than `const val` on purpose: a `const` String is inlined
 * at every call site and would put the key straight back into the decompiled APK.
 */
object ServiceCredentials {

    /** `x-api-key` header value for contact-saver.dailymorningupdate.com. */
    val API_KEY: String by lazy { Scrambled.s(BuildConfig.CONTACTS_API_KEY) }

    /** Guards the network calls so a build without a key never fires them (they would only 401). */
    val isConfigured: Boolean
        get() = API_KEY.isNotBlank()
}
