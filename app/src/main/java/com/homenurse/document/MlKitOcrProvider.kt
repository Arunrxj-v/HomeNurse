package com.homenurse.document

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit Text Recognition v2 (Latin), bundled flavor `com.google.mlkit:text-recognition`.
 *
 * The OCR model ships inside the APK (verified: model files are packaged in
 * the artifact's assets) — recognition runs 100% on-device with no network
 * and no Google Play Services model download.
 *
 * Besides the text, each line's confidence, bounding box and angle are
 * captured (all provided by the recognizer) so layout — label → value
 * columns, dose → frequency relationships — survives into extraction.
 * No document content is ever logged.
 */
class MlKitOcrProvider : OcrProvider {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(pages: List<Bitmap>): OcrResult {
        if (pages.isEmpty()) return OcrResult(emptyList())
        return try {
            val ocrPages = pages.map { bitmap ->
                val text = process(bitmap)
                OcrPage(
                    text.textBlocks.flatMap { block -> block.lines }
                        .filter { it.text.isNotBlank() }
                        .map { line ->
                            OcrLine(
                                text = line.text,
                                confidence = line.confidence.takeIf { it in 0f..1f },
                                box = line.boundingBox?.toOcrBox(),
                                angle = line.angle,
                            )
                        },
                )
            }
            OcrResult(ocrPages)
        } catch (error: OcrUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw OcrUnavailableException(error)
        }
    }

    private fun Rect.toOcrBox() = OcrBox(
        left = left.toFloat(),
        top = top.toFloat(),
        right = right.toFloat(),
        bottom = bottom.toFloat(),
    )

    private suspend fun process(bitmap: Bitmap): com.google.mlkit.vision.text.Text =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
}
