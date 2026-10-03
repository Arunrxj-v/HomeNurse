package com.homenurse.document

import android.graphics.Bitmap

/**
 * Adaptive preprocessing for medical-document OCR.
 *
 * Nothing is applied blindly: each stage only runs when image analysis says
 * it is needed, and the *original document bytes are never modified* —
 * preprocessing produces candidate bitmaps used solely for recognition.
 *
 * Pipeline (see [enhance]):
 *   1. orientation probe — sideways pages yield BOTH 90° candidates and real
 *      OCR decides which direction is right (never guessed blind);
 *   2. conservative content crop (uniform borders only — desk frame, page
 *      margin; never touches content rows);
 *   3. shadow / uneven-illumination flattening;
 *   4. percentile contrast stretch (poor-contrast scans);
 *   5. median denoise (impulse-noisy camera photos);
 *   6. unsharp mask (soft/blurry captures only);
 *   7. deskew via projection-profile scoring;
 *   8. upscale (small images or sub-pixel text strokes).
 *
 * Deliberately NOT done: full perspective warp (would need a CV stack plus
 * ground-truth validation — a wrong quad destroys evidence; ML Kit's text
 * detector handles mild perspective on photos) and global O/0 binarization
 * fed to OCR (hard thresholding erases light table rules and gray text that
 * the recognizer still reads; Sauvola-style binarization is used only for
 * the geometric analysis inside [GrayImage]).
 */
data class PreprocessResult(
    /** Candidates in priority order — OCR until one is good enough. */
    val candidates: List<GrayImage>,
    /** True when the page looked sideways and both 90° turns were produced. */
    val orientationFallback: Boolean,
)

class DocumentImagePreprocessor {

    fun preprocess(input: GrayImage): PreprocessResult {
        return if (input.isLikelySideways()) {
            PreprocessResult(
                candidates = listOf(
                    enhance(input.rotateQuarter(1)),
                    enhance(input.rotateQuarter(3)),
                ),
                orientationFallback = true,
            )
        } else {
            PreprocessResult(candidates = listOf(enhance(input)), orientationFallback = false)
        }
    }

    private fun enhance(input: GrayImage): GrayImage {
        var img = input.trimUniformBorders()
        img = img.flattenIllumination()
        img = img.contrastStretch()
        if (img.impulseFraction() > 0.10) {
            img = img.median3()
        }
        if (img.softEdgeFraction() >= 0.55) {
            img = img.unsharp()
        }
        val skew = img.estimateSkew()
        if (skew != 0.0) {
            img = img.rotate(-skew)
        }
        return upscaleIfSmall(img)
    }

    private fun upscaleIfSmall(input: GrayImage): GrayImage {
        var img = input
        val stroke = img.medianInkRun()
        val density = img.inkDensity()
        val thinText = stroke in 0.5..2.5 && density in 0.005..0.5
        val smallImage = img.width < 1000
        if (!thinText && !smallImage) return img

        var factor = if (smallImage) (1600.0 / img.width).coerceAtLeast(2.0) else 2.0
        factor = factor.coerceAtMost(4.0)
        // Memory guard: never exceed ~6 MP after upscaling.
        while (factor > 1.0 &&
            img.width.toDouble() * factor * img.height.toDouble() * factor > MAX_UPSCALE_PIXELS
        ) {
            factor -= 0.5
        }
        return if (factor > 1.0) img.upscale(factor) else img
    }

    companion object {
        private const val MAX_UPSCALE_PIXELS = 6_000_000.0
    }
}

// --- Bitmap glue (pipeline edges only; the ops themselves are pure Kotlin) --------------

/** ARGB bitmap → grayscale buffer. */
fun Bitmap.toGrayImage(): GrayImage {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return GrayImage.fromArgb(pixels, width, height)
}

/** Grayscale buffer → ARGB bitmap (for the recognizer). */
fun GrayImage.toBitmap(): Bitmap =
    Bitmap.createBitmap(toArgb(), width, height, Bitmap.Config.ARGB_8888)
