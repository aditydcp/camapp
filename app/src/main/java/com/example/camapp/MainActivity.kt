package com.example.camapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64.encodeToString
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camapp.message.Message
import com.example.camapp.message.MessageService
import com.example.camapp.databinding.ActivityMainBinding
import com.example.camapp.file.FileService
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var barcodeScanner: BarcodeScanner

    private class MyBarcodeAnalyzer()

    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy) {

            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request all permissions required
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        // This part is used to hold the image before saving to a named file
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
//        val outputOptions = ImageCapture.OutputFileOptions
//            .Builder(contentResolver,
//                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
//                contentValues)
//            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
//        imageCapture.takePicture(
//            outputOptions,
//            ContextCompat.getMainExecutor(this),
//            object : ImageCapture.OnImageSavedCallback {
//                override fun onError(exc: ImageCaptureException) {
//                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
//                }
//
//                override fun
//                        onImageSaved(output: ImageCapture.OutputFileResults){
//                    val msg = "Photo capture succeeded: ${output.savedUri}"
//                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
//                    Log.d(TAG, msg)
//                }
//            }
//        )

        // referenced from here:
        // https://stackoverflow.com/questions/71797696/convert-imageproxy-to-jpeg-or-png
        // this approach seems to be promising
        // Setting up capture listener
        // have the image saved as ByteArrayOutputStream
        val result = ByteArrayOutputStream()

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(result)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val cache = File(cacheDir.absolutePath + "/captured.png")
                    try {
                        val stream = FileOutputStream(cache)
                        stream.write(result.toByteArray())
                        uploadFile(cache)
                    }
                    catch (exc: Exception) {
                        Log.e(TAG, "onImageSaved failed: ${exc.message}", exc)
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "onError called: ${exc.message}", exc)
                }
            }
        )

//        imageCapture.takePicture(
//            ContextCompat.getMainExecutor(this),
//            object : ImageCapture.OnImageCapturedCallback() {
//                override fun onError(exception: ImageCaptureException) {
//                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
//                }
//
//                override fun onCaptureSuccess(image: ImageProxy) {
//                    val msg = "Photo capture succeeded: ${image.imageInfo}"
//                    Log.d(TAG, msg)
//                    runOnUiThread {
//                        viewBinding.imageCapturedView.setImageURI(image.)
//                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
//                    }
//                }
//            }
//        )
    }

    private fun captureVideo() {}

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        // setup Barcode Detector
        val barcodeScannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(barcodeScannerOptions)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Previewer
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // Image Captor
            imageCapture = ImageCapture.Builder()
                .build()

            // Image Analyzer
            val imageAnalyzer = ImageAnalysis.Builder()
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                        Log.d(TAG, "Average luminosity: $luma")

                        // @Adet's
                        runOnUiThread {
                            viewBinding.luminosityValue.text = luma.toString()
                        }
                    })
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalyzer
                )

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun sendMessage(file: File) {
        // covert the file into base64 string
        val base64String = encodeToString(
            file.readBytes(),
            android.util.Base64.NO_WRAP
        )
        val messageService = MessageService()
        val message = Message(
            message = null,
            content = base64String,
        )

        messageService.sendMessage(message) {
            if (it != null) {
                Toast.makeText(
                    applicationContext,
                    it.message,
                    Toast.LENGTH_LONG
                ).show()
            }
            else {
                Toast.makeText(
                    applicationContext,
                    "No Acknowledgement",
                    Toast.LENGTH_LONG
                ).show()
            }
            Log.d(TAG, "Response: $it")
        }
    }

    private fun uploadFile(file: File) {
        // initialize form data
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val filePart = MultipartBody.Part.createFormData(
            "image",
            file.name,
            file.asRequestBody("image/*".toMediaTypeOrNull())
        )

        val fileService = FileService()

        fileService.uploadFile(filePart) {
            if (it != null) {
                Toast.makeText(
                    applicationContext,
                    it.message,
                    Toast.LENGTH_LONG
                ).show()
            }
            else {
                Toast.makeText(
                    applicationContext,
                    "No Acknowledgement",
                    Toast.LENGTH_LONG
                ).show()
            }
            Log.d(TAG, "Response: $it")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}