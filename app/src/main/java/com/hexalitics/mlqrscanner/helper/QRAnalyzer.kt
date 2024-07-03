package com.hexalitics.mlqrscanner.helper

import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
/*import com.google.mlkit.vision.barcode.Barcode*/
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.Vector

class QRAnalyzer constructor(private val allowedFormat: String, private val qrCode: (Barcode, InputImage) -> Unit) :
    ImageAnalysis.Analyzer {

    @OptIn(ExperimentalGetImage::class)
    @SuppressLint("UnsafeExperimentalUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        imageProxy.image?.let { image ->
            val inputImage = InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)
            val scanner = BarcodeScanning.getClient()
            scanner.process(inputImage)
                .addOnCompleteListener {
                    imageProxy.close()
                    if (it.isSuccessful) {
                        for (barcode in it.result) {
                            if (isAcceptableFormat(barcode.format)) {
                                qrCode(barcode, inputImage)
                            }
                            /*when (barcode.format) {
                                Barcode.FORMAT_QR_CODE -> {
                                    qrCode(barcode, inputImage)
                                }
                            }*/
                        }
                    } else {
                        it.exception?.printStackTrace()
                    }
                }
        }
    }

    private fun isAcceptableFormat(format: Int): Boolean {
        when(allowedFormat) {
            FORMAT_BARCODE -> return isBarcode(format)
            FORMAT_QRCODE -> return isQrcode(format)
            FORMAT_ALL -> return isQrcode(format) || isBarcode(format)
        }
        return true
    }

    private fun isBarcode(format: Int): Boolean {
        return format == Barcode.FORMAT_UPC_A ||
                format == Barcode.FORMAT_UPC_E ||
                format == Barcode.FORMAT_EAN_13 ||
                format == Barcode.FORMAT_EAN_8 ||
                format == Barcode.FORMAT_CODE_39 ||
                format == Barcode.FORMAT_CODE_93 ||
                format == Barcode.FORMAT_CODE_128 ||
                format == Barcode.FORMAT_ITF
    }

    private fun isQrcode(format: Int): Boolean {
        return format == Barcode.FORMAT_QR_CODE ||
                format == Barcode.FORMAT_DATA_MATRIX
    }

    companion object {
        const val FORMAT_BARCODE = "barcode"
        const val FORMAT_QRCODE = "qrcode"
        const val FORMAT_ALL = "all"
    }
}
