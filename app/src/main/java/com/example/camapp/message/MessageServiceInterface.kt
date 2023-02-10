package com.example.camapp.message

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface MessageServiceInterface {
    @Headers("Content-Type: application/json")
    @POST("/message")
    fun sendMessage(@Body message: Message): Call<Message>
}