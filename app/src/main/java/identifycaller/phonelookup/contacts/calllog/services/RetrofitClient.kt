package identifycaller.phonelookup.contacts.calllog.services

import com.chuckerteam.chucker.api.ChuckerInterceptor
import identifycaller.phonelookup.contacts.calllog.BuildConfig
import identifycaller.phonelookup.contacts.calllog.CallerPhoneLookApp
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    /** API base URL. */
    const val BASE_URL = "https://callerid.kpeworld.com/"

    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor())
            // On-device HTTP inspector. Real in debug (captures + shows a Chucker
            // notification/UI); the release no-op variant is a pass-through, so
            // nothing is captured or shown to users.
            .addInterceptor(ChuckerInterceptor.Builder(CallerPhoneLookApp.appContext).build())

        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor { message ->
                android.util.Log.d("OkHttp", message)
            }.apply { level = HttpLoggingInterceptor.Level.BODY }
            builder.addInterceptor(logging)
        }

        builder.build()
    }

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
