package com.example.camapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64.encodeToString
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camapp.QrCodeDrawable
import com.example.camapp.QrCodeViewModel
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

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraController: LifecycleCameraController
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var previewView: PreviewView

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
//        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable camera controller
        val cameraController = cameraController

        // Setting up capture listener
        // have the image saved as ByteArrayOutputStream
        val result = ByteArrayOutputStream()

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(result)
            .build()

        // invoke take picture use case
        cameraController.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val cache = File(cacheDir.absolutePath + "/captured.png")
                    try {
                        val stream = FileOutputStream(cache)
                        stream.write(result.toByteArray())
                        uploadFile(cache)
                    } catch (exc: Exception) {
                        Log.e(TAG, "onImageSaved failed: ${exc.message}", exc)
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "onError called: ${exc.message}", exc)
                }
            }
        )
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun startCamera() {
        cameraController = LifecycleCameraController(baseContext)
        previewView = viewBinding.viewFinder

        // setup Barcode Detector
        val barcodeScannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(barcodeScannerOptions)

        // bind Image Analysis use case
        cameraController.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(this),
            MlKitAnalyzer(
                listOf(barcodeScanner),
                COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(this)
            ) { result: MlKitAnalyzer.Result? ->
                val barcodeResults = result?.getValue(barcodeScanner)
                if ((barcodeResults == null) ||
                    (barcodeResults.size == 0) ||
                    (barcodeResults.first() == null)
                ) {
                    viewBinding.viewFinder.overlay.clear()
                    viewBinding.viewFinder
                        .setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                val factory: MeteringPointFactory =
                                    SurfaceOrientedMeteringPointFactory(
                                        previewView.width.toFloat(),
                                        previewView.height.toFloat()
                                )
                                val autoFocusPoint = factory.createPoint(event.x, event.y)
                                try {
                                    cameraController.cameraControl?.startFocusAndMetering(
                                        FocusMeteringAction.Builder(
                                            autoFocusPoint,
                                            FocusMeteringAction.FLAG_AF
                                        ).apply {
                                            //focus only when the user tap the preview
                                            disableAutoCancel()
                                        }.build()
                                    )
                                } catch (e: CameraInfoUnavailableException) {
                                    Log.d(TAG, "Cannot access camera " +
                                            "when configuring auto focus", e)
                                }
                            }
                            true
                        }
                    runOnUiThread {
                        viewBinding.scanStatus.text =
                            getString(R.string.scan_status_default)
                    }
                    return@MlKitAnalyzer
                }

                val qrCodeViewModel = QrCodeViewModel(barcodeResults[0])
                val qrCodeDrawable = QrCodeDrawable(qrCodeViewModel)

                viewBinding.viewFinder
                    .setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            takePhoto()
                        }
                        true
                    }

                viewBinding.viewFinder
                    .overlay.clear()
                viewBinding.viewFinder
                    .overlay.add(qrCodeDrawable)

                runOnUiThread {
                    viewBinding.scanStatus.text = getString(R.string.scan_status_ok)
                }
            }
        )

        cameraController.imageAnalysisBackpressureStrategy = STRATEGY_KEEP_ONLY_LATEST
        cameraController.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        cameraController.unbind()
        cameraController.bindToLifecycle(this)
        previewView.controller = cameraController
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun uploadFile(file: File) {
        // initialize form data
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
        cameraController.unbind()
        cameraExecutor.shutdown()
        barcodeScanner.close()
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
//        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
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