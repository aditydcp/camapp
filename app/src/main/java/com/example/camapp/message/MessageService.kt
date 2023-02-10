package com.example.camapp.message

import android.util.Log
import com.example.camapp.ServiceBuilder
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MessageService {
    private val TAG = "MessageService class"

    fun sendMessage(message: Message, onResult: (Message?) -> Unit) {
        val retrofit = ServiceBuilder.buildService(MessageServiceInterface::class.java)
        retrofit.sendMessage(message).enqueue(
            object : Callback<Message> {
                override fun onResponse(call: Call<Message>, response: Response<Message>) {
                    val newMessage = response.body()
                    Log.d(TAG, "$response")
                    onResult(newMessage)
                }
                override fun onFailure(call: Call<Message>, t: Throwable) {
                    Log.e(TAG, "sendMessage error: $t", t)
                    onResult(null)
                }
            }
        )
    }
}