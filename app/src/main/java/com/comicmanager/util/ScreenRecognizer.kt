package com.comicmanager.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class RecognizedText(val text: String, val cx: Float, val cy: Float)

object ScreenRecognizer {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    fun captureWebView(webView: View): Bitmap {
        val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        webView.draw(canvas)
        return bitmap
    }

    suspend fun findAllText(bitmap: Bitmap): List<RecognizedText> {
        return suspendCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val items = mutableListOf<RecognizedText>()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox
                            if (box != null) {
                                items.add(RecognizedText(
                                    text = line.text,
                                    cx = box.centerX().toFloat(),
                                    cy = box.centerY().toFloat()
                                ))
                            }
                        }
                    }
                    cont.resume(items)
                }
                .addOnFailureListener {
                    cont.resume(emptyList())
                }
        }
    }
}
