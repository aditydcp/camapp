package com.example.camapp.file

import android.util.Log
import com.example.camapp.ServiceBuilder
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Callback

class FileService {
    fun uploadFile(params: Map<String, String>, filePart: MultipartBody.Part, onResult: (Response?) -> Unit) {
        val retrofit = ServiceBuilder.buildService(FileServiceInterface::class.java)
        retrofit.uploadFile(
            params,
            filePart
        ).enqueue(
            object : Callback<Response> {
                override fun onResponse(
                    call: Call<Response>,
                    response: retrofit2.Response<Response>
                ) {
                    Log.d(TAG, "Full response: $response")
                    val extractedResponse = response.body()
                    onResult(extractedResponse)
                }

                override fun onFailure(call: Call<Response>, t: Throwable) {
                    Log.e(TAG, "uploadFile error: $t", t)
                    onResult(null)
                }
            }
        )
    }

    companion object {
        private const val TAG = "FileService class"
    }
}