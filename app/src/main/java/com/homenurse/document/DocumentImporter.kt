package com.homenurse.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import com.homenurse.domain.model.MedicalDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Turns stored document bytes into bitmaps for OCR and for on-screen viewing.
 *
 * * Images are decoded directly from memory (no plaintext temp file).
 * * PDFs need a seekable file descriptor for [PdfRenderer], so pages are
 *   rendered from a short-lived app-private cache file that is deleted in a
 *   `finally` block immediately after rendering. The cache directory is
 *   excluded from backup and is never readable by other apps.
 *
 * Interface extracted so the processing pipeline can be tested with fakes.
 */
interface DocumentRenderer {
    suspend fun renderForOcr(bytes: ByteArray, mimeType: String): List<Bitmap>
    suspend fun renderPage(bytes: ByteArray, mimeType: String, pageIndex: Int): Bitmap?
}

class DocumentImporter(
    private val cacheDir: File,
) : DocumentRenderer {

    /** Page bitmaps for OCR (bounded resolution + page count). */
    override suspend fun renderForOcr(bytes: ByteArray, mimeType: String): List<Bitmap> =
        withContext(Dispatchers.IO) {
            if (mimeType.equals("application/pdf", ignoreCase = true)) {
                renderPdf(bytes, maxPages = MAX_OCR_PAGES, maxPixelWidth = OCR_PIXEL_WIDTH)
            } else {
                listOfNotNull(decodeImage(bytes, OCR_PIXEL_WIDTH))
            }
        }

    /** Page bitmap for the document viewer. */
    override suspend fun renderPage(bytes: ByteArray, mimeType: String, pageIndex: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (mimeType.equals("application/pdf", ignoreCase = true)) {
                renderPdfPage(bytes, pageIndex, VIEW_PIXEL_WIDTH)
            } else if (pageIndex == 0) {
                decodeImage(bytes, VIEW_PIXEL_WIDTH)
            } else {
                null
            }
        }

    /** Total pages (images are always 1; PDFs via a lightweight render pass). */
    suspend fun pageCount(bytes: ByteArray, mimeType: String): Int =
        withContext(Dispatchers.IO) {
            if (!mimeType.equals("application/pdf", ignoreCase = true)) return@withContext 1
            withPdfRenderer(bytes) { renderer, _ -> renderer.pageCount }
        }

    private fun renderPdf(bytes: ByteArray, maxPages: Int, maxPixelWidth: Int): List<Bitmap> =
        withPdfRenderer(bytes) { renderer, _ ->
            val pages = minOf(renderer.pageCount, maxPages)
            (0 until pages).mapNotNull { index ->
                renderPdfPageWith(renderer, index, maxPixelWidth)
            }
        }

    private fun renderPdfPage(bytes: ByteArray, pageIndex: Int, maxPixelWidth: Int): Bitmap? =
        withPdfRenderer(bytes) { renderer, _ ->
            if (pageIndex !in 0 until renderer.pageCount) null
            else renderPdfPageWith(renderer, pageIndex, maxPixelWidth)
        }

    private inline fun <T> withPdfRenderer(
        bytes: ByteArray,
        block: (PdfRenderer, File) -> T,
    ): T {
        val temp = File(cacheDir, "pdf_${System.nanoTime()}.tmp")
        try {
            temp.writeBytes(bytes)
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    return block(renderer, temp)
                }
            }
        } finally {
            temp.delete()
        }
    }

    private fun renderPdfPageWith(renderer: PdfRenderer, index: Int, maxPixelWidth: Int): Bitmap? {
        renderer.openPage(index).use { page ->
            val scale = (maxPixelWidth.toFloat() / page.width).coerceAtLeast(1f)
            val width = (page.width * scale).toInt()
            val height = (page.height * scale).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    private fun decodeImage(bytes: ByteArray, maxPixelWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        var width = bounds.outWidth
        while (width / 2 >= maxPixelWidth) {
            width /= 2
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        // Camera photos store their orientation in EXIF; without applying it
        // the recognizer sees the page sideways. Never trust pixel order
        // alone — this is the most common real-world failure of the pipeline.
        bitmap = applyExifOrientation(bitmap, readExifOrientation(bytes))

        // Power-of-two sampling leaves the image larger than needed; scale
        // down exactly once so resolution is predictable for OCR.
        if (bitmap.width > maxPixelWidth) {
            val targetHeight =
                (bitmap.height.toLong() * maxPixelWidth / bitmap.width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, maxPixelWidth, targetHeight, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return bitmap
    }

    private fun readExifOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (error: OutOfMemoryError) {
            bitmap
        }
    }

    companion object {
        const val MAX_OCR_PAGES = 10
        const val OCR_PIXEL_WIDTH = 1800
        const val VIEW_PIXEL_WIDTH = 2400
    }
}
