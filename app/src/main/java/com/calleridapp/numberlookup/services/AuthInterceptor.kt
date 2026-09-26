package com.calleridapp.numberlookup.services

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

/**
 * Attaches the contact-saver `x-api-key` header.
 *
 * Only to HTTPS requests bound for the configured API host: the base URL can be
 * repointed from Remote Config, which is publicly readable, so the key must not
 * follow it to an arbitrary host or over cleartext. A request failing either check
 * is still sent — unauthenticated, so the server rejects it — rather than dropped,
 * which would look like a network fault.
 */
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val url = request.url
        val key = ServiceCredentials.API_KEY
        val apiHost = RetrofitClient.resolvedBaseUrl().toHttpUrlOrNull()?.host

        val send = key.isNotBlank() &&
            url.isHttps &&
            apiHost != null &&
            url.host.equals(apiHost, ignoreCase = true)

        if (!send) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER_API_KEY, key).build())
    }

    companion object {
        const val HEADER_API_KEY = "x-api-key"
    }
}
