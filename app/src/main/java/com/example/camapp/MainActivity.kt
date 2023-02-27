package com.example.camapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camapp.databinding.ActivityMainBinding
import com.example.camapp.file.FileService
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraController: LifecycleCameraController
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var previewView: PreviewView
    private lateinit var inputOuterBoundary: Rect
    private lateinit var inputInnerBoundary: Rect
    private lateinit var autoFocusFuture: ListenableFuture<FocusMeteringResult>

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

        // setup input zone boundaries
//        val display = windowManager.defaultDisplay
//        val displaySize = Point()
//        display.getSize(displaySize)
//        val boundaryDrawable = BoundaryDrawable(displaySize)
//        viewBinding.viewFinder
//            .overlay.add(boundaryDrawable)
//        // Set up outer boundary equals to the input boundary in layout
//        inputOuterBoundary = viewBinding.inputBoundary.clipBounds
//
//        // Set up inner boundary as an offset from outer boundary
//        inputInnerBoundary = Rect().apply {
//            this.set(inputOuterBoundary)
//            this.inset(50,50)
//        }
//
//        val outerBoundaryDrawable = BoundaryDrawable(inputOuterBoundary)
//        val innerBoundaryDrawable = BoundaryDrawable(inputInnerBoundary)
//        viewBinding.viewFinder
//            .overlay.apply {
//                this.add(outerBoundaryDrawable)
//                this.add(innerBoundaryDrawable)
//            }

        viewBinding.inputBoundary.post {
            // Set up outer boundary equals to the input boundary in layout
            inputOuterBoundary = Rect(
                viewBinding.inputBoundary.left,
                viewBinding.inputBoundary.top,
                viewBinding.inputBoundary.right,
                viewBinding.inputBoundary.bottom
            )

            Log.d(TAG, "Input Boundary position:\n" +
                    "Top: ${viewBinding.inputBoundary.top}\n" +
                    "Left: ${viewBinding.inputBoundary.left}\n" +
                    "Right: ${viewBinding.inputBoundary.right}\n" +
                    "Bottom: ${viewBinding.inputBoundary.bottom}")

            // Set up inner boundary as an offset from outer boundary
            inputInnerBoundary = Rect().apply {
                this.set(inputOuterBoundary)
                this.inset(200,200)
            }
        }

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
//                    viewBinding.viewFinder.setOnTouchListener {
//                            _, _ -> false } //no-op
                    viewBinding.viewFinder
                        .setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                startFocus(event)
                            }
                            true
                        }
                    runOnUiThread {
                        viewBinding.scanStatus.text =
                            getString(R.string.scan_status_default)
                    }
                    return@MlKitAnalyzer
                }

                // debug purpose
                // locate barcode coordinates in view
                val corners = barcodeResults[0].cornerPoints
//                Log.d(TAG, "Barcode locations on screen:\n" +
//                        "Top-left: (" +
//                        "${corners?.get(0)?.x}, " +
//                        "${corners?.get(0)?.y})\n" +
//                        "Top-right: (" +
//                        "${corners?.get(1)?.x}, " +
//                        "${corners?.get(1)?.y})\n" +
//                        "Bottom-left: (" +
//                        "${corners?.get(3)?.x}, " +
//                        "${corners?.get(3)?.y})\n" +
//                        "Bottom-right: (" +
//                        "${corners?.get(2)?.x}, " +
//                        "${corners?.get(2)?.y})"
//                )

                val qrCodeViewModel = QrCodeViewModel(barcodeResults[0])
                val qrCodeDrawable = QrCodeDrawable(qrCodeViewModel)

                viewBinding.viewFinder
                    .setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            startFocus(event) {
                                Log.d(TAG, "Lambda function for testing is running!")
                            }
                        }
                        true
                    }

                viewBinding.viewFinder
                    .overlay.clear()
                viewBinding.viewFinder
                    .overlay.add(qrCodeDrawable)

                val outerBoundaryDrawable = BoundaryDrawable(inputOuterBoundary)
                val innerBoundaryDrawable = BoundaryDrawable(inputInnerBoundary)
                viewBinding.viewFinder
                    .overlay.apply {
                        this.add(outerBoundaryDrawable)
                        this.add(innerBoundaryDrawable)
                    }

                if (isDetectedInRange(barcodeResults[0].boundingBox!!)) {
                    runOnUiThread {
                        viewBinding.scanStatus.text = getString(R.string.scan_status_ok)
                    }
                } else {
                    runOnUiThread {
                        viewBinding.scanStatus.text = getString(R.string.scan_status_out_of_bounds)
                    }
                }
            }
        )

        cameraController.imageAnalysisBackpressureStrategy = STRATEGY_KEEP_ONLY_LATEST
        cameraController.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        cameraController.unbind()
        cameraController.bindToLifecycle(this)
        previewView.controller = cameraController
    }

    private fun startFocus(event: MotionEvent,
                           function: () -> Unit = {
                              Log.d(TAG, "Default function of startFocus\n" +
                                      "Pass in a function to run when focus is done.")
                           }) {
        val factory: MeteringPointFactory =
            SurfaceOrientedMeteringPointFactory(
                previewView.width.toFloat(),
                previewView.height.toFloat()
            )
        val autoFocusPoint = factory.createPoint(event.x, event.y)
        try {
            Log.d(TAG, "Attempting to auto focus...")
            autoFocusFuture = cameraController
                .cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(
                        autoFocusPoint,
                        FocusMeteringAction.FLAG_AF
                    ).apply {
                        //focus only when the user tap the preview
                        disableAutoCancel()
                    }.build()
                ) as ListenableFuture<FocusMeteringResult>
//            autoFocusFuture.addListener({
//                Log.d(TAG, "Auto focus has completed")
//            }, cameraExecutor)

            // Set up focus listener
            autoFocusFuture.addListener({
                Log.d(TAG, "Auto focus has completed")
//                Log.d(TAG, "Proceeding to image capture...")
                function()
            }, ContextCompat.getMainExecutor(this))
        } catch (e: CameraInfoUnavailableException) {
            Log.d(TAG, "Cannot access camera " +
                    "when configuring auto focus", e)
        }
    }

    private fun isDetectedInRange(detectedRect: Rect): Boolean {
        return inputOuterBoundary.contains(detectedRect) &&
                !inputInnerBoundary.contains(detectedRect)
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

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
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