package com.homenurse.document

import android.graphics.Bitmap

/**
 * On-device OCR abstraction.
 *
 * Implementations must run fully locally (no document text ever leaves the
 * device). The default implementation is ML Kit Text Recognition v2 with the
 * bundled model — the model is statically linked into the APK.
 *
 * Structured output: every recognized line keeps its confidence, bounding
 * box and angle (all provided by the recognizer and previously discarded).
 * Positions are what preserve label → value layout (lab tables, dose →
 * frequency columns) instead of an unordered text block.
 */

/** Axis-aligned box in page-pixel coordinates (platform-free for tests). */
data class OcrBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun union(other: OcrBox): OcrBox = OcrBox(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    /** Vertical overlap as a fraction of the shorter box's height (0..1). */
    fun verticalOverlapRatio(other: OcrBox): Float {
        val overlap = minOf(bottom, other.bottom) - maxOf(top, other.top)
        if (overlap <= 0f) return 0f
        val reference = minOf(height, other.height)
        if (reference <= 0f) return 0f
        return (overlap / reference).coerceIn(0f, 1f)
    }
}

/**
 * One recognized text line.
 *
 * @property confidence recognizer confidence in [0,1], or null when the
 *   engine did not provide one. Never treated as medical truth — low
 *   confidence only flags an item for closer user review.
 * @property box bounding box in the page bitmap's pixel coordinates.
 * @property angle line angle in degrees as reported by the recognizer.
 * @property page 1-based page number.
 */
data class OcrLine(
    val text: String,
    val confidence: Float? = null,
    val box: OcrBox? = null,
    val angle: Float? = null,
    val page: Int = 1,
)

data class OcrPage(val lines: List<OcrLine>)

data class OcrResult(val pages: List<OcrPage>) {

    val pageCount: Int get() = pages.size

    val lines: List<OcrLine> get() = pages.flatMap { it.lines }

    val allLines: List<String> get() = lines.map { it.text }

    val text: String
        get() = pages.joinToString("\n\n") { page -> page.lines.joinToString("\n") { it.text } }

    companion object {
        /** Convenience for fakes/tests: one page per list of plain strings. */
        fun of(vararg pageLines: List<String>): OcrResult =
            OcrResult(pageLines.mapIndexed { index, lines ->
                OcrPage(lines.map { OcrLine(text = it, page = index + 1) })
            })
    }
}

interface OcrProvider {
    suspend fun recognize(pages: List<Bitmap>): OcrResult
}

/** Clear, honest error when OCR could not process an image. */
class OcrUnavailableException(cause: Throwable? = null) :
    Exception("Text extraction failed", cause)
