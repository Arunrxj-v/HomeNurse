package com.homenurse.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Adaptive-preprocessing properties on synthetic pages. The rule under test:
 * every stage is analysis-driven (no-op on clean pages, applied where the
 * image actually needs it) and geometry never loses content.
 */
class ImagePreprocessorTest {

    // --- fixtures ---------------------------------------------------------------------

    /** Page with horizontal text-like rows (dark glyph runs with gaps). */
    private fun textPage(width: Int = 300, height: Int = 400, lines: Int = 10): GrayImage {
        val data = IntArray(width * height) { 245 }
        for (line in 0 until lines) {
            val y0 = 30 + line * ((height - 60) / lines)
            var x = 20
            while (x < width - 20) {
                val word = 8 + (x * 7) % 30
                val end = minOf(x + word, width - 20)
                for (y in y0 until minOf(y0 + 14, height - 5)) {
                    for (xx in x until end) data[y * width + xx] = 30
                }
                x = end + 8
            }
        }
        return GrayImage(width, height, data)
    }

    private fun meanOfQuarter(image: GrayImage, top: Boolean): Double {
        val from = if (top) 0 else image.height * 3 / 4
        val to = if (top) image.height / 4 else image.height
        var sum = 0.0
        for (y in from until to) {
            for (x in 0 until image.width) sum += image.get(x, y)
        }
        return sum / ((to - from) * image.width)
    }

    // --- contrast ---------------------------------------------------------------------

    @Test
    fun `low contrast page is stretched to a usable dynamic range`() {
        val poor = GrayImage(200, 200, IntArray(200 * 200) { 110 + (it % 30) })
        assertTrue(poor.dynamicRange() < 40)

        val stretched = poor.contrastStretch()

        assertTrue(stretched.dynamicRange() > 150)
        assertTrue(stretched.percentile(2.0) <= 5)
        assertTrue(stretched.percentile(98.0) >= 250)
    }

    @Test
    fun `clean page is not resampled by the contrast stage`() {
        val page = textPage()
        assertSame(page, page.contrastStretch()) // identity — no needless work
    }

    // --- shadow / uneven illumination ---------------------------------------------------

    @Test
    fun `strong shadow gradient is flattened`() {
        val width = 240
        val height = 320
        val data = IntArray(width * height) { i ->
            val y = i / width
            (230 - (150.0 * y / height)).toInt().coerceIn(40, 255)
        }
        val shadowed = GrayImage(width, height, data)
        val before = abs(meanOfQuarter(shadowed, true) - meanOfQuarter(shadowed, false))
        assertTrue("gradient must be strong before", before > 50)

        val flat = shadowed.flattenIllumination()

        val after = abs(meanOfQuarter(flat, true) - meanOfQuarter(flat, false))
        assertTrue("gradient must shrink ($before → $after)", after < before / 3)
    }

    @Test
    fun `evenly lit page skips the shadow stage`() {
        val page = textPage()
        assertSame(page, page.flattenIllumination())
    }

    // --- noise --------------------------------------------------------------------------

    @Test
    fun `impulse noise is detected and removed`() {
        val page = textPage()
        val noisyData = page.data.copyOf()
        var seed = 42L
        for (i in noisyData.indices) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            if (((seed ushr 33) % 100) < 15) {
                noisyData[i] = if ((seed and 1L) == 0L) 0 else 255
            }
        }
        val noisy = GrayImage(page.width, page.height, noisyData)
        val before = noisy.impulseFraction()
        assertTrue("noise must be detected ($before)", before > 0.10)

        val clean = noisy.median3()
        val after = clean.impulseFraction()
        assertTrue("noise must drop ($before → $after)", after < before / 3)
    }

    @Test
    fun `clean page keeps its texture through the noise detector`() {
        assertTrue(textPage().impulseFraction() <= 0.10)
    }

    // --- sharpening gate ------------------------------------------------------------------

    @Test
    fun `soft edges are measured higher on a blurred page than a sharp one`() {
        val sharp = textPage(width = 600, height = 600)
        val blurred = sharp.boxBlur(2).boxBlur(2)

        val sharpness = sharp.softEdgeFraction()
        val blurriness = blurred.softEdgeFraction()

        assertTrue("sharp=$sharpness blurred=$blurriness", blurriness > sharpness)
        assertTrue("sharp pages must not be sharpened ($sharpness)", sharpness < 0.55)
        assertTrue("blurred pages must be sharpened ($blurriness)", blurriness >= 0.55)
    }

    // --- geometry --------------------------------------------------------------------------

    @Test
    fun `skew is detected and removed`() {
        val page = textPage()
        val skewed = page.rotate(6.0)

        val detected = skewed.estimateSkew()
        assertTrue("skew ≈6°, detected=$detected", abs(detected - 6.0) < 1.6)

        val deskewed = skewed.rotate(-detected)
        val residual = deskewed.estimateSkew()
        assertTrue("residual skew too large: $residual", abs(residual) < 1.6)
    }

    @Test
    fun `upright page is not flagged sideways and has no skew`() {
        val page = textPage()
        assertFalse(page.isLikelySideways())
        assertEquals(0.0, page.estimateSkew(), 0.001)
    }

    @Test
    fun `quarter turned page is detected as sideways`() {
        val page = textPage()
        assertTrue(page.rotateQuarter(1).isLikelySideways())
        assertTrue(page.rotateQuarter(3).isLikelySideways())
    }

    @Test
    fun `quarter turn mapping is an exact rotation`() {
        // 2×3 distinct pixels: 90° cw maps src(x,y) → dst(h-1-y, x).
        val src = GrayImage(2, 3, intArrayOf(1, 2, 3, 4, 5, 6))
        val cw = src.rotateQuarter(1)
        assertEquals(3, cw.width)
        assertEquals(2, cw.height)
        assertTrue(
            intArrayOf(5, 3, 1, 6, 4, 2).contentEquals(cw.data),
        )

        val ccw = src.rotateQuarter(3)
        assertTrue(
            intArrayOf(2, 4, 6, 1, 3, 5).contentEquals(ccw.data),
        )

        val half = src.rotateQuarter(2)
        assertTrue(
            intArrayOf(6, 5, 4, 3, 2, 1).contentEquals(half.data),
        )
    }

    @Test
    fun `180 turn returns to the original`() {
        val page = textPage()
        val twice = page.rotateQuarter(2).rotateQuarter(2)
        assertTrue(page.data.contentEquals(twice.data))
    }

    // --- boundaries --------------------------------------------------------------------------

    @Test
    fun `uniform desk border is cropped while content survives`() {
        val width = 300
        val height = 400
        val border = 30
        val data = IntArray(width * height) { 5 } // black desk everywhere
        val inner = textPage(width - 2 * border, height - 2 * border, lines = 8)
        for (y in 0 until inner.height) {
            for (x in 0 until inner.width) {
                data[(y + border) * width + (x + border)] = inner.get(x, y)
            }
        }
        val framed = GrayImage(width, height, data)

        val cropped = framed.trimUniformBorders()

        assertTrue("top/bottom border must be trimmed", cropped.height < height)
        assertTrue("left/right border must be trimmed", cropped.width < width)
        assertTrue("content area must remain (≥60%)", cropped.width >= width * 0.6)
        // The text rows survived: dark pixels are still present.
        var ink = 0
        for (v in cropped.data) if (v < 100) ink++
        assertTrue("content ink must survive", ink > 1000)
    }

    @Test
    fun `content reaching the edge is never cut away`() {
        val width = 200
        val height = 200
        // Grid pattern touching every edge: no uniform border row or column
        // exists, so the trim stage must return the page untouched.
        // 3×3 checkerboard touching every edge: every row and column mixes
        // dark and light, so no uniform border row/column exists.
        val data = IntArray(width * height) { i ->
            val y = i / width
            val x = i % width
            if ((x / 3 + y / 3) % 2 == 0) 30 else 245
        }
        val bleeding = GrayImage(width, height, data)

        assertSame(bleeding, bleeding.trimUniformBorders())
    }

    @Test
    fun `paper white margins are kept as part of the page`() {
        // Cropping paper-white margins changes canvas geometry enough to flip
        // recognizer glyph decisions on identical pixels (measured: g/dL →
        // gldL). Only uniform borders darker than the paper are surround.
        val page = textPage(300, 400)

        assertSame(page, page.trimUniformBorders())
    }

    // --- resolution ----------------------------------------------------------------------------

    @Test
    fun `small page is upscaled for recognition`() {
        val small = textPage(width = 300, height = 400)
        val result = DocumentImagePreprocessor().preprocess(small)

        assertTrue(result.candidates.single().width >= 1000)
    }

    @Test
    fun `upright page yields a single candidate`() {
        val result = DocumentImagePreprocessor().preprocess(textPage(width = 1200, height = 1600))

        assertFalse(result.orientationFallback)
        assertEquals(1, result.candidates.size)
    }

    @Test
    fun `sideways page yields both 90 degree candidates for OCR to judge`() {
        val sideways = textPage(width = 1200, height = 1600).rotateQuarter(1)
        val result = DocumentImagePreprocessor().preprocess(sideways)

        assertTrue(result.orientationFallback)
        assertEquals(2, result.candidates.size)
    }

    // --- color conversion ------------------------------------------------------------------------

    @Test
    fun `argb roundtrip preserves luminance for gray pixels`() {
        val pixel = 0xFF303030.toInt()
        val gray = GrayImage.fromArgb(intArrayOf(pixel), 1, 1)
        assertEquals(0x30, gray.get(0, 0))

        val back = gray.toArgb()
        assertEquals(0xFF303030.toInt(), back[0])
    }

    @Test
    fun `luminance conversion weights green highest`() {
        val red = GrayImage.fromArgb(intArrayOf(0xFFFF0000.toInt()), 1, 1).get(0, 0)
        val green = GrayImage.fromArgb(intArrayOf(0xFF00FF00.toInt()), 1, 1).get(0, 0)
        val blue = GrayImage.fromArgb(intArrayOf(0xFF0000FF.toInt()), 1, 1).get(0, 0)
        assertTrue("green=$green red=$red", green > red)
        assertTrue("green=$green blue=$blue", green > blue)
    }
}
