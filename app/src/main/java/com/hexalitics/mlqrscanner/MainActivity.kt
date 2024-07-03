package com.hexalitics.mlqrscanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import com.afollestad.materialdialogs.MaterialDialog
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.hexalitics.mlqrscanner.databinding.ActivityMainBinding
import com.hexalitics.mlqrscanner.databinding.DialogScanResultBinding
import com.hexalitics.mlqrscanner.helper.BaseActivity
import com.hexalitics.mlqrscanner.helper.QRAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pub.devrel.easypermissions.EasyPermissions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), EasyPermissions.PermissionCallbacks {
    //Region MARK: - public fields
    //Endregion

    //Region MARK: - private fields
    private val binding by lazy(LazyThreadSafetyMode.NONE) {
        ActivityMainBinding.inflate(
            layoutInflater
        )
    }
    private val cameraProviderFuture by lazy { ProcessCameraProvider.getInstance(this) }
    private val cameraExecutor by lazy { Executors.newSingleThreadExecutor() }
    private var stopScanner = false
    private val rec2 by lazy {
        RectF(
            binding.previewView.left.toFloat(),
            binding.previewView.top.toFloat(),
            binding.previewView.right.toFloat(),
            binding.previewView.bottom.toFloat()
        )
        /*RectF(
            binding.img.left.toFloat(),
            binding.img.top.toFloat(),
            binding.img.right.toFloat(),
            binding.img.bottom.toFloat()
        )*/
    }
    private var scaleFactorX = 1.0f
    private var scaleFactorY = 1.0f
    //Endregion

    private var cameraProvider: ProcessCameraProvider? = null
    private var mCamera: Camera? = null

    private var isFlashOn = false
    private lateinit var imageCapture: ImageCapture
    private var isPopupVisible = false

    //Region MARK: - public methods
    override fun onResume() {
        super.onResume()
        // Check if the Camera permission has been granted
        if (checkSelfPermissionCompat(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Permission is missing and must be requested.
            requestCameraPermission()
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        recreate()
    }

    override fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        MaterialDialog(this).show {
            title(R.string.app_name)
            message(R.string.camera_permission_denied)
            positiveButton(R.string.close) {
                requestCameraPermission()
            }
            negativeButton(R.string.grant) {
                this@MainActivity.finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.ivBack.setOnClickListener { finish() }
        binding.ivFlash.setOnClickListener(flashListener)
        binding.ivCapture.setOnClickListener {
            capturePhoto()
        }
    }

    //Endregion

    //Region MARK: - private methods
    @SuppressLint("UnsafeExperimentalUsageError")
    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        binding.previewView.post {
            val preview: Preview = Preview.Builder()
                .build()
            val cameraSelector: CameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            preview.setSurfaceProvider(binding.previewView.surfaceProvider)

            // ImageCapture use case
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(1920, 1080)) // Adjust resolution as needed
                .build()

            // Get screen metrics used to setup camera for full screen resolution
            val metrics = DisplayMetrics().also { binding.previewView.display.getRealMetrics(it) }
            val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(screenAspectRatio)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(
                cameraExecutor,
                QRAnalyzer(QRAnalyzer.FORMAT_ALL) { barcode: Barcode, inputImage: InputImage ->
                    if (isPortraitMode()) {
                        scaleFactorY = binding.previewView.height.toFloat() / inputImage.width
                        scaleFactorX = binding.previewView.width.toFloat() / inputImage.height
                    } else {
                        scaleFactorY = binding.previewView.height.toFloat() / inputImage.height
                        scaleFactorX = binding.previewView.width.toFloat() / inputImage.width
                    }
                    if (!isPopupVisible) {
                        barcode.boundingBox?.let { rect ->
                            val qrCoreRect = translateRect(rect)
                            if (rec2.contains(qrCoreRect)) {
                                if (stopScanner.not()) {
                                    stopScanner = true
                                    // cameraProvider.unbindAll()
                                    openResultDialog(
                                        cameraSelector,
                                        imageAnalysis,
                                        preview,
                                        barcode,
                                        inputImage
                                    )
                                    /*MaterialDialog(this@MainActivity).show {
                                        title(R.string.app_name)
                                        message(text = barcode.rawValue?.toString())
                                        positiveButton(R.string.close) {
                                            stopScanner = false
                                            mCamera = cameraProvider.bindToLifecycle(
                                                this@MainActivity as LifecycleOwner,
                                                cameraSelector,
                                                imageAnalysis,
                                                preview
                                            )
                                        }
                                    }*/
                                }
                            }
                        }
                    }
                })
            mCamera = cameraProvider.bindToLifecycle(
                this@MainActivity as LifecycleOwner,
                cameraSelector,
                imageAnalysis,
                preview,
                imageCapture
            )
        }
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun requestCameraPermission() {
        EasyPermissions.requestPermissions(
            this, getString(R.string.camera_access_required),
            242, Manifest.permission.CAMERA
        )
    }

    private fun translateX(x: Float): Float = x * scaleFactorX
    private fun translateY(y: Float): Float = y * scaleFactorY
    private fun translateRect(rect: Rect) = RectF(
        translateX(rect.left.toFloat()),
        translateY(rect.top.toFloat()),
        translateX(rect.right.toFloat()),
        translateY(rect.bottom.toFloat())
    )

    private fun startCamera() {
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider!!)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun AppCompatActivity.checkSelfPermissionCompat(permission: String) =
        ActivityCompat.checkSelfPermission(this, permission)

    private fun isPortraitMode(): Boolean {
        val orientation: Int = resources.configuration.orientation
        return orientation == Configuration.ORIENTATION_PORTRAIT
    }


    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    /**
     * Flash switch button
     */
    private val flashListener = View.OnClickListener {
        try {
            // val isSuccess = CameraManager.get().setFlashLight(!isFlashOn)
            if (mCamera?.cameraInfo?.hasFlashUnit() == true) {
                val isSuccess = mCamera!!.cameraControl.enableTorch(!isFlashOn)

                isFlashOn = if (isFlashOn) {
                    // Turn off the flash
                    binding!!.ivFlash.setImageResource(R.drawable.ic_flash_off)
                    false
                } else {
                    // Turn on the flash
                    binding!!.ivFlash.setImageResource(R.drawable.ic_flash_on)
                    true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Capture a photo
    private fun capturePhoto() {
        CoroutineScope(Dispatchers.Main).launch {
            // Create output file to hold the image
            val photoFile = File(
                outputDirectory,
                SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis()) + ".jpg"
            )

            // Create output options object which contains file + metadata
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            // Set up image capture listener, which is triggered after photo has been taken
            imageCapture.takePicture(
                outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e("CameraSetup", "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = Uri.fromFile(photoFile)
                        val msg = "Photo capture succeeded: $savedUri"
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        }
                        Log.d("CameraSetup", msg)
                    }
                })
        }
    }

    // Define the output directory and file format
    private val outputDirectory: File by lazy {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    companion object {
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }


    private fun openResultDialog(cameraSelector: CameraSelector,
                                 imageAnalysis: ImageAnalysis,
                                 preview: Preview,
                                 barcode: Barcode,
                                 inputImage: InputImage) {
        val dialogBox = AlertDialog.Builder(this)
        dialogBox.setCancelable(false)

        val scanBinding = DialogScanResultBinding.inflate(layoutInflater)
        dialogBox.setView(scanBinding.root)
        val alertDialog = dialogBox.create()
        alertDialog.window!!.setBackgroundDrawableResource(android.R.color.transparent)

        isPopupVisible = true

        scanBinding.ivQR.setImageBitmap(inputImage.bitmapInternal)
        scanBinding.tvResult.text = barcode.rawValue


        scanBinding.tvCancel.setOnClickListener {
            cameraProvider?.unbindAll()
            stopScanner = false
            mCamera = cameraProvider?.bindToLifecycle(
                this@MainActivity as LifecycleOwner,
                cameraSelector,
                imageAnalysis,
                preview
            )
            isPopupVisible = false
            alertDialog.dismiss()
        }

        alertDialog.show()
    }

}