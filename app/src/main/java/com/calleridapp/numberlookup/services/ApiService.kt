package com.calleridapp.numberlookup.services

import com.google.gson.JsonObject
import com.calleridapp.numberlookup.models.DialResponse
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * The contact-saver API. The `x-api-key` credential is added by [AuthInterceptor],
 * never passed here, so it cannot drift back into a query parameter.
 *
 * Paths are relative (no leading `/`) so they resolve against the base URL's path.
 */
interface ApiService {

    /** `GET similar-phone-number?phone=…` */
    @GET("similar-phone-number")
    suspend fun checkPhoneNumber(
        @Query("phone") phone: String
    ): Response<DialResponse>

    /** `POST upload/contacts` — multipart, one CSV part named `file`. */
    @Multipart
    @POST("upload/contacts")
    fun uploadContacts(
        @Part file: MultipartBody.Part,
    ): Call<JsonObject>
}
