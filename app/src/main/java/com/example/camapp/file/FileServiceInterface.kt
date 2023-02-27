package com.example.camapp.file

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.*

interface FileServiceInterface {
    @Multipart
    @POST("/sqr/verify")
    fun uploadFile(
        @QueryMap params: Map<String, String>,
        @Part filePart: MultipartBody.Part
    ): Call<Response>
}