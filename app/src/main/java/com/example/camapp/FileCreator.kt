package com.example.camapp

object FileCreator {

    const val JPEG_FORMAT = ".jpg"

    fun createTempFile(fileFormat: String) =
        createTempFile(System.currentTimeMillis().toString(), fileFormat)
}