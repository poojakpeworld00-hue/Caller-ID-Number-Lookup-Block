package com.calleridapp.numberlookup.services

import com.google.gson.JsonObject
import com.calleridapp.numberlookup.models.DialResponse
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("api/similar-phone-number/{id}")
    suspend fun checkPhoneNumber(
        @Path("id") id: String,
        @Query("phone") phone: String,
        @Query("hash_key") hashKey: String,
        @Header("Authorization") token: String
    ): Response<DialResponse>

    @Multipart
    @POST("/api/save_contact2")
    fun saveContact(
        @Query("hash_key") apiKey: String,
        @Part file: MultipartBody.Part,
    ): Call<JsonObject>
}
