package com.example.camapp.file

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface FileServiceInterface {
    @Multipart
    @POST("/sqr/verify")
    fun uploadFile(
        @Field("v") version: Int,
        @Field("quant") quantity: Int,
        @Field("key") key: String,
        @Part filePart: MultipartBody.Part
    ): Call<Response>
}