package com.calleridapp.numberlookup.services

import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.LookupShellApp
import com.calleridapp.numberlookup.util.GuardRail
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    private const val TAG = "RetrofitClient"

    /**
     * Compiled-in API base URL, and the fallback whenever Remote Config has nothing usable.
     *
     * Kept as the fallback rather than removed: this is the one config value the app cannot ask
     * the network for, since it *is* the network address. A blank or malformed [RC_KEY] must
     * leave the app working, not offline.
     */
    const val BASE_URL = "https://callerid.kpeworld.com/"

    /** Remote Config key that overrides [BASE_URL] — a full origin, e.g. `https://api.host/`. */
    private const val RC_KEY = "api_base_url"

    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor())
            // On-device HTTP inspector. Real in debug (captures + shows a Chucker
            // notification/UI); the release no-op variant is a pass-through, so
            // nothing is captured or shown to users.
            .addInterceptor(ChuckerInterceptor.Builder(LookupShellApp.appContext).build())

        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor { message ->
                android.util.Log.d("OkHttp", message)
            }.apply { level = HttpLoggingInterceptor.Level.BODY }
            builder.addInterceptor(logging)
        }

        builder.build()
    }

    /**
     * The base URL to use right now: [RC_KEY] when Remote Config supplies something that parses,
     * otherwise [BASE_URL].
     *
     * Normalises the trailing slash, because Retrofit rejects a base URL without one outright —
     * so a console value typed as `https://api.host` would otherwise take the whole API down.
     */
    fun resolvedBaseUrl(): String {
        val raw = runCatching {
            AdsVault.getInstance(LookupShellApp.appContext).getString(RC_KEY, "").orEmpty()
        }.getOrDefault("").trim()

        if (raw.isBlank()) return BASE_URL

        val normalized = if (raw.endsWith("/")) raw else "$raw/"
        // toHttpUrlOrNull also rejects anything that is not http/https, which is what we want:
        // Retrofit would throw on those, and a throw here reaches every API caller.
        if (normalized.toHttpUrlOrNull() == null) {
            GuardRail.error(TAG, "ignoring malformed $RC_KEY='$raw' — using $BASE_URL")
            return BASE_URL
        }

        return normalized
    }

    /** The service, paired with the base URL it was built for. */
    @Volatile
    private var cached: Pair<String, ApiService>? = null

    /**
     * Resolved per access rather than once, so a Remote Config fetch that lands *after* the first
     * API call still takes effect — nothing is readable at the top of a cold start, and the app
     * can reach this before the fetch completes. Retrofit is only rebuilt when the URL actually
     * changes, which for a whole session is normally never.
     */
    val api: ApiService
        get() {
            val url = resolvedBaseUrl()
            cached?.let { (built, service) -> if (built == url) return service }

            return synchronized(this) {
                cached?.let { (built, service) -> if (built == url) return@synchronized service }

                GuardRail.log(TAG, "building API client for $url")
                val service = Retrofit.Builder()
                    .baseUrl(url)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(ApiService::class.java)

                cached = url to service
                service
            }
        }
}
