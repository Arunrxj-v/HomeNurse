package com.homenurse.document

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Grayscale image (one byte per pixel) in **pure Kotlin** — no
 * android.graphics — so every preprocessing operation is unit-testable on
 * the JVM and the pipeline can run against raw buffers.
 *
 * Values are luminance 0..255. Conversions to/from ARGB live in
 * [BitmapGray] at the pipeline edges only.
 */
class GrayImage(val width: Int, val height: Int, val data: IntArray) {

    init {
        require(width > 0 && height > 0) { "empty image" }
        require(data.size == width * height) { "buffer size mismatch" }
    }

    val size: Int get() = width * height

    fun get(x: Int, y: Int): Int = data[y * width + x]

    fun set(x: Int, y: Int, value: Int) {
        data[y * width + x] = value
    }

    fun copy(): GrayImage = GrayImage(width, height, data.copyOf())

    // --- creation -----------------------------------------------------------------------

    companion object {
        /** Analysis-stage limits (projection scoring runs on ≤400 px images). */
        const val MAX_ANALYSIS_DIM = 400
        const val MIN_INK_FOR_GEOMETRY = 400

        /** Contrast range (2..98 percentile) that needs no stretch at all. */
        const val EXCELLENT_CONTRAST = 200

        /** Below this the image is flat — stretching would only amplify noise. */
        const val MIN_STRETCH_RANGE = 8

        /** Block size of the illumination background estimate. */
        const val FLATTEN_BLOCK = 32

        /**
         * A uniform border counts as non-page surround only when it is at
         * least this much darker than the page's paper percentile.
         */
        const val NON_PAGE_CONTRAST = 60

        /** ARGB pixels → luminance (BT.601 integer approximation). */
        fun fromArgb(pixels: IntArray, width: Int, height: Int): GrayImage {
            require(pixels.size == width * height) { "buffer size mismatch" }
            val out = IntArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[i] = (r * 77 + g * 150 + b * 29 + 128) shr 8
            }
            return GrayImage(width, height, out)
        }
    }

    /** Luminance → opaque ARGB (gray). */
    fun toArgb(): IntArray {
        val out = IntArray(size)
        for (i in data.indices) {
            val v = data[i]
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return out
    }

    // --- statistics ---------------------------------------------------------------------

    private fun histogram(): IntArray {
        val hist = IntArray(256)
        for (v in data) hist[v]++
        return hist
    }

    /** Percentile (0..100) via cumulative histogram. */
    fun percentile(p: Double): Int {
        val hist = histogram()
        val target = size * (p.coerceIn(0.0, 100.0) / 100.0)
        var cumulative = 0
        for (v in 0..255) {
            cumulative += hist[v]
            if (cumulative >= target) return v
        }
        return 255
    }

    fun meanAndStd(): Pair<Double, Double> {
        var sum = 0.0
        for (v in data) sum += v
        val mean = sum / size
        var variance = 0.0
        for (v in data) {
            val d = v - mean
            variance += d * d
        }
        return mean to sqrt(variance / size)
    }

    /** p95 − p5: usable dynamic range (low ⇒ poor contrast). */
    fun dynamicRange(): Int = percentile(95.0) - percentile(5.0)

    /** Mean value of the 1-px border (fill color for rotations). */
    fun borderMean(): Int {
        var sum = 0L
        var count = 0L
        for (x in 0 until width) {
            sum += get(x, 0); count++
            sum += get(x, height - 1); count++
        }
        for (y in 0 until height) {
            sum += get(0, y); count++
            sum += get(width - 1, y); count++
        }
        return (sum / count).toInt()
    }

    // --- illumination / contrast --------------------------------------------------------

    /**
     * Separable box blur (clamped edges) used for background estimation and
     * sharpening. O(n) with running sums.
     */
    fun boxBlur(radius: Int): GrayImage {
        if (radius <= 0) return copy()
        val tmp = IntArray(size)
        val out = IntArray(size)
        val norm = 2 * radius + 1
        // Horizontal pass.
        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            for (x in -radius..radius) sum += data[row + x.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                tmp[row + x] = sum / norm
                sum += data[row + (x + radius + 1).coerceIn(0, width - 1)]
                sum -= data[row + (x - radius).coerceIn(0, width - 1)]
            }
        }
        // Vertical pass.
        for (x in 0 until width) {
            var sum = 0
            for (y in -radius..radius) sum += tmp[y.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                out[y * width + x] = sum / norm
                sum += tmp[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                sum -= tmp[(y - radius).coerceIn(0, height - 1) * width + x]
            }
        }
        return GrayImage(width, height, out)
    }

    /**
     * Normalize uneven illumination (shadow bands, lamp falloff, vignetting)
     * without ever mistaking printed text for a shadow:
     *
     *  1. the background of each 32 px block is its **90th percentile** —
     *     paper is the brightest thing on a medical document, so any block
     *     with ink still reports the paper level,
     *  2. the coarse grid is smoothed twice (illumination is low-frequency),
     *  3. if the background varies by ≤18 levels the page is already evenly
     *     lit and this returns `this` (identity — clean pages are untouched),
     *  4. otherwise every pixel is rescaled toward a common background target.
     */
    fun flattenIllumination(): GrayImage {
        val block = FLATTEN_BLOCK
        val gw = (width + block - 1) / block
        val gh = (height + block - 1) / block
        val counts = IntArray(256)
        val grid = IntArray(gw * gh)
        for (by in 0 until gh) {
            for (bx in 0 until gw) {
                java.util.Arrays.fill(counts, 0)
                val x0 = bx * block
                val y0 = by * block
                val x1 = min(width, x0 + block)
                val y1 = min(height, y0 + block)
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) counts[data[row + x]]++
                }
                val total = (x1 - x0) * (y1 - y0)
                val rank = (total * 9 + 9) / 10 // ceil(90 %)
                var acc = 0
                var background = 0
                for (v in 0..255) {
                    acc += counts[v]
                    if (acc >= rank) {
                        background = v
                        break
                    }
                }
                grid[by * gw + bx] = background
            }
        }

        // Smooth the coarse grid twice — the residual block structure of the
        // printed page must not survive into the illumination estimate.
        var g = grid
        repeat(2) {
            val next = IntArray(g.size)
            for (y in 0 until gh) {
                for (x in 0 until gw) {
                    var sum = 0
                    var n = 0
                    for (dy in -1..1) {
                        val yy = y + dy
                        if (yy !in 0 until gh) continue
                        for (dx in -1..1) {
                            val xx = x + dx
                            if (xx in 0 until gw) {
                                sum += g[yy * gw + xx]
                                n++
                            }
                        }
                    }
                    next[y * gw + x] = sum / n
                }
            }
            g = next
        }

        var lo = 255
        var hi = 0
        for (v in g) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (hi - lo <= 18) return this // even illumination — nothing to fix

        var sum = 0L
        for (v in g) sum += v
        val target = max(1.0, sum.toDouble() / g.size)

        val out = IntArray(size)
        for (y in 0 until height) {
            val fy = ((y + 0.5) / block - 0.5).coerceIn(0.0, (gh - 1).toDouble())
            val gy0 = floor(fy).toInt().coerceIn(0, gh - 1)
            val gy1 = min(gy0 + 1, gh - 1)
            val ty = fy - gy0
            for (x in 0 until width) {
                val fx = ((x + 0.5) / block - 0.5).coerceIn(0.0, (gw - 1).toDouble())
                val gx0 = floor(fx).toInt().coerceIn(0, gw - 1)
                val gx1 = min(gx0 + 1, gw - 1)
                val tx = fx - gx0
                val top = g[gy0 * gw + gx0] * (1 - tx) + g[gy0 * gw + gx1] * tx
                val bottom = g[gy1 * gw + gx0] * (1 - tx) + g[gy1 * gw + gx1] * tx
                val background = (top * (1 - ty) + bottom * ty).coerceAtLeast(1.0)
                val v = data[y * width + x]
                out[y * width + x] = min(255, max(0, (v * target / background).toInt()))
            }
        }
        return GrayImage(width, height, out)
    }

    /**
     * Percentile contrast stretch (2..98). Only acts where the analysis
     * justifies it: an already excellent range is left untouched, a
     * degenerate (flat) range has nothing to stretch, and everything in
     * between — the washed-out photo case — is expanded to full range.
     */
    fun contrastStretch(lowPct: Double = 2.0, highPct: Double = 98.0): GrayImage {
        val lo = percentile(lowPct)
        val hi = percentile(highPct)
        val range = hi - lo
        if (range >= EXCELLENT_CONTRAST || range < MIN_STRETCH_RANGE) return this
        val lut = IntArray(256)
        val scale = 255.0 / range
        for (v in 0..255) {
            lut[v] = min(255, max(0, ((v - lo) * scale).toInt()))
        }
        val out = IntArray(size)
        for (i in data.indices) out[i] = lut[data[i]]
        return GrayImage(width, height, out)
    }

    // --- noise / sharpness --------------------------------------------------------------

    /** Fraction of sampled pixels that differ from their local median by > 25. */
    fun impulseFraction(stride: Int = 2): Double {
        var sampled = 0
        var impulses = 0
        var y = 1
        while (y < height - 1) {
            var x = 1
            while (x < width - 1) {
                val m = median3At(x, y)
                if (absDiff(get(x, y), m) > 25) impulses++
                sampled++
                x += stride
            }
            y += stride
        }
        return if (sampled == 0) 0.0 else impulses.toDouble() / sampled
    }

    /** 3×3 median filter (salt-and-pepper removal). */
    fun median3(): GrayImage {
        val out = IntArray(size)
        System.arraycopy(data, 0, out, 0, size)
        val window = IntArray(9)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var k = 0
                for (dy in -1..1) {
                    val row = (y + dy) * width
                    for (dx in -1..1) window[k++] = data[row + x + dx]
                }
                // Insertion sort of 9 values.
                for (i in 1..8) {
                    val v = window[i]
                    var j = i - 1
                    while (j >= 0 && window[j] > v) {
                        window[j + 1] = window[j]
                        j--
                    }
                    window[j + 1] = v
                }
                out[y * width + x] = window[4]
            }
        }
        return GrayImage(width, height, out)
    }

    private fun median3At(x: Int, y: Int): Int {
        val window = IntArray(9)
        var k = 0
        for (dy in -1..1) {
            val row = (y + dy) * width
            for (dx in -1..1) window[k++] = data[row + x + dx]
        }
        for (i in 1..8) {
            val v = window[i]
            var j = i - 1
            while (j >= 0 && window[j] > v) {
                window[j + 1] = window[j]
                j--
            }
            window[j + 1] = v
        }
        return window[4]
    }

    /**
     * Softness of edges among sampled gradient pixels: the fraction of edge
     * pixels whose own value sits mid-range (60..190). Sharp glyph edges
     * jump almost directly (≈0.3), blurred ones linger mid-gray (≥0.6).
     * Gates sharpening so clean scans are not re-sharpened blindly.
     */
    fun softEdgeFraction(stride: Int = 2): Double {
        var edges = 0
        var soft = 0
        var y = 1
        while (y < height - 1) {
            var x = 1
            while (x < width - 1) {
                val v = get(x, y)
                val gx = absDiff(get(x + 1, y), get(x - 1, y))
                val gy = absDiff(get(x, y + 1), get(x, y - 1))
                if (max(gx, gy) >= 40) {
                    edges++
                    if (v in 60..190) soft++
                }
                x += stride
            }
            y += stride
        }
        if (edges < 60) return 0.0
        return soft.toDouble() / edges
    }

    /** Unsharp mask (3×3). */
    fun unsharp(amount: Double = 0.7): GrayImage {
        val blur = boxBlur(1)
        val out = IntArray(size)
        for (i in data.indices) {
            val v = data[i] + amount * (data[i] - blur.data[i])
            out[i] = min(255, max(0, v.toInt()))
        }
        return GrayImage(width, height, out)
    }

    // --- binarization (analysis stage) ----------------------------------------------------

    /** Otsu threshold. */
    fun otsuThreshold(): Int {
        val hist = histogram()
        val total = size.toDouble()
        var sumAll = 0.0
        for (v in 0..255) sumAll += v * hist[v]
        var sumB = 0.0
        var wB = 0L
        var best = 127
        var bestVar = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0L) continue
            val wF = size - wB
            if (wF == 0L) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > bestVar) {
                bestVar = between
                best = t
            }
        }
        return best
    }

    /** Binarize at Otsu level: ink (dark text) → 0, background → 255. */
    fun binarized(): GrayImage {
        val t = otsuThreshold()
        val out = IntArray(size)
        for (i in data.indices) out[i] = if (data[i] <= t) 0 else 255
        return GrayImage(width, height, out)
    }

    /**
     * Median vertical run of ink: a proxy for text stroke thickness (for
     * horizontal text, vertical runs through glyphs ≈ stroke width). Used to
     * decide whether small text must be upscaled before recognition.
     */
    fun medianInkRun(): Double {
        val bin = binarized()
        val runs = ArrayList<Int>(4096)
        for (x in 0 until bin.width step 2) {
            var run = 0
            for (y in 0 until bin.height) {
                if (bin.data[y * bin.width + x] == 0) {
                    run++
                } else {
                    if (run > 0) runs.add(run)
                    run = 0
                }
            }
            if (run > 0) runs.add(run)
        }
        if (runs.isEmpty()) return 0.0
        runs.sort()
        return runs[runs.size / 2].toDouble()
    }

    fun inkDensity(): Double {
        val bin = binarized()
        var ink = 0
        for (v in bin.data) if (v == 0) ink++
        return ink.toDouble() / bin.size
    }

    // --- geometry: orientation, skew, rotation -------------------------------------------

    /** Ink pixel coordinates (downscaled + binarized), shared by projection work. */
    private fun inkPoints(maxDim: Int): Pair<IntArray, IntArray> {
        val small = downscale(maxDim)
        val bin = small.binarized()
        val xs = ArrayList<Int>(8192)
        val ys = ArrayList<Int>(8192)
        for (y in 0 until bin.height) {
            val row = y * bin.width
            for (x in 0 until bin.width) {
                if (bin.data[row + x] == 0) {
                    xs.add(x)
                    ys.add(y)
                }
            }
        }
        return IntArray(xs.size) { xs[it] } to IntArray(ys.size) { ys[it] }
    }

    /**
     * Variance of the ink histogram projected onto rows at angle [angleDeg]
     * (0 = image rows). Text lines produce sharp, high-variance projections
     * when the angle matches their tilt.
     */
    fun projectionVariance(angleDeg: Double, xs: IntArray, ys: IntArray): Double {
        if (xs.isEmpty()) return 0.0
        val rad = Math.toRadians(angleDeg)
        val sin = sin(rad)
        val cos = cos(rad)
        var minT = Int.MAX_VALUE
        var maxT = Int.MIN_VALUE
        val ts = IntArray(xs.size)
        for (i in xs.indices) {
            // Row coordinate of the projection.
            val t = (-xs[i] * sin + ys[i] * cos).toInt()
            ts[i] = t
            if (t < minT) minT = t
            if (t > maxT) maxT = t
        }
        val hist = LongArray(maxT - minT + 1)
        for (t in ts) hist[t - minT]++
        var sum = 0.0
        var sumSq = 0.0
        for (count in hist) {
            sum += count
            sumSq += count.toDouble() * count
        }
        val n = hist.size
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /**
     * True when text lines look vertical (sideways page): the 90° projection
     * is far sharper than the 0° one. Conservative threshold — never fires
     * on upright pages; the pipeline then tries both 90° candidates and lets
     * real OCR decide the correct direction (never guessed blind).
     */
    fun isLikelySideways(): Boolean {
        val (xs, ys) = inkPoints(MAX_ANALYSIS_DIM)
        if (xs.size < MIN_INK_FOR_GEOMETRY) return false
        val v0 = projectionVariance(0.0, xs, ys)
        val v90 = projectionVariance(90.0, xs, ys)
        if (v0 <= 0.0) return false
        return v90 > 2.0 * v0
    }

    /** Skew angle of text lines in [-maxAngle, +maxAngle] via projection scoring. */
    fun estimateSkew(maxAngle: Double = 8.0, step: Double = 0.5): Double {
        val (xs, ys) = inkPoints(MAX_ANALYSIS_DIM)
        if (xs.size < MIN_INK_FOR_GEOMETRY) return 0.0
        var bestAngle = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var angle = -maxAngle
        while (angle <= maxAngle) {
            val score = projectionVariance(angle, xs, ys)
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
            angle += step
        }
        // 0° is the reference for an upright page; only correct meaningful skew.
        return if (kotlin.math.abs(bestAngle) < 0.5) 0.0 else bestAngle
    }

    /**
     * Rotate by [degrees] (positive = clockwise on screen), expanding the
     * canvas so no content is cropped, bilinear sampling, border fill.
     */
    fun rotate(degrees: Double): GrayImage {
        val rad = Math.toRadians(degrees)
        val cos = cos(rad)
        val sin = sin(rad)
        val absCos = kotlin.math.abs(cos)
        val absSin = kotlin.math.abs(sin)
        val newW = ceil(width * absCos + height * absSin).toInt().coerceAtLeast(1)
        val newH = ceil(width * absSin + height * absCos).toInt().coerceAtLeast(1)
        val fill = borderMean()
        val out = IntArray(newW * newH)
        val cxSrc = (width - 1) / 2.0
        val cySrc = (height - 1) / 2.0
        val cxDst = (newW - 1) / 2.0
        val cyDst = (newH - 1) / 2.0
        for (yd in 0 until newH) {
            for (xd in 0 until newW) {
                val dx = xd - cxDst
                val dy = yd - cyDst
                // Inverse rotation into source coordinates.
                val sx = dx * cos + dy * sin + cxSrc
                val sy = -dx * sin + dy * cos + cySrc
                out[yd * newW + xd] = if (sx < 0 || sy < 0 || sx > width - 1 || sy > height - 1) {
                    fill
                } else {
                    bilinear(sx, sy)
                }
            }
        }
        return GrayImage(newW, newH, out)
    }

    private fun bilinear(sx: Double, sy: Double): Int {
        val x0 = sx.toInt()
        val y0 = sy.toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val fx = sx - x0
        val fy = sy - y0
        val top = get(x0, y0) * (1 - fx) + get(x1, y0) * fx
        val bottom = get(x0, y1) * (1 - fx) + get(x1, y1) * fx
        return (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
    }

    /** Exact quarter-turn rotation: 1 = 90° cw, 2 = 180°, 3 = 90° ccw. */
    fun rotateQuarter(turns: Int): GrayImage {
        val t = ((turns % 4) + 4) % 4
        if (t == 0) return copy()
        return if (t == 2) {
            val out = IntArray(size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[(height - 1 - y) * width + (width - 1 - x)] = data[y * width + x]
                }
            }
            GrayImage(width, height, out)
        } else {
            val newW = height
            val newH = width
            val out = IntArray(size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val src = data[y * width + x]
                    if (t == 1) {
                        // 90° cw: (x,y) → (h-1-y, x)
                        out[x * newW + (newW - 1 - y)] = src
                    } else {
                        // 90° ccw: (x,y) → (y, w-1-x); row = w-1-x, col = y
                        out[(width - 1 - x) * newW + y] = src
                    }
                }
            }
            GrayImage(newW, newH, out)
        }
    }

    /** Bilinear upscale by [factor] (≥1). */
    fun upscale(factor: Double): GrayImage {
        if (factor <= 1.001) return this
        val newW = (width * factor).roundToInt().coerceAtLeast(1)
        val newH = (height * factor).roundToInt().coerceAtLeast(1)
        val out = IntArray(newW * newH)
        for (yd in 0 until newH) {
            val sy = (yd / factor).coerceAtMost(height - 1.0)
            for (xd in 0 until newW) {
                val sx = (xd / factor).coerceAtMost(width - 1.0)
                out[yd * newW + xd] = bilinear(sx, sy)
            }
        }
        return GrayImage(newW, newH, out)
    }

    /** Box-average downscale so the long side ≤ [maxDim] (no-op if smaller). */
    fun downscale(maxDim: Int): GrayImage {
        if (width <= maxDim && height <= maxDim) return this
        val scale = maxOf(width.toDouble() / maxDim, height.toDouble() / maxDim)
        val newW = max(1, (width / scale).toInt())
        val newH = max(1, (height / scale).toInt())
        val out = IntArray(newW * newH)
        for (yd in 0 until newH) {
            val y0 = (yd * height / newH)
            val y1 = max(y0 + 1, ((yd + 1) * height / newH))
            for (xd in 0 until newW) {
                val x0 = (xd * width / newW)
                val x1 = max(x0 + 1, ((xd + 1) * width / newW))
                var sum = 0
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) sum += data[row + x]
                }
                out[yd * newW + xd] = sum / ((x1 - x0) * (y1 - y0))
            }
        }
        return GrayImage(newW, newH, out)
    }

    /**
     * Conservative content crop: trim borders that are *uniform* (near-
     * constant rows/columns — desk surface, scanner black frame, page
     * margin), never touching rows that contain content. Capped so a mostly
     * blank page can never lose its actual text area.
     */
    /**
     * Conservative content crop: trim borders that are uniform **and darker
     * than the page's own paper** (near-constant rows/columns that surround
     * the sheet — desk surface, scanner black frame), never touching rows
     * that contain content. Capped so a mostly blank page can never lose its
     * actual text area.
     *
     * Paper-white margins are deliberately *kept*: they are part of the
     * page, and cropping them changes the canvas geometry enough to flip
     * recognizer glyph decisions on otherwise identical pixels (measured:
     * `g/dL` → `gldL` on a trimmed but not on the untrimmed page).
     */
    fun trimUniformBorders(maxFraction: Double = 0.18): GrayImage {
        val tolerance = 10
        val paper = percentile(90.0)
        fun uniformRowValue(y: Int): Int? {
            var min = 255
            var max = 0
            for (x in 0 until width step 2) {
                val v = get(x, y)
                if (v < min) min = v
                if (v > max) max = v
            }
            return if (max - min <= tolerance) max else null
        }
        fun uniformColValue(x: Int): Int? {
            var min = 255
            var max = 0
            for (y in 0 until height step 2) {
                val v = get(x, y)
                if (v < min) min = v
                if (v > max) max = v
            }
            return if (max - min <= tolerance) max else null
        }
        // Non-page surround: uniform and clearly darker than the paper.
        fun surround(value: Int?): Boolean = value != null && paper - value >= NON_PAGE_CONTRAST
        val maxTop = (height * maxFraction).toInt()
        val maxBottom = (height * maxFraction).toInt()
        val maxLeft = (width * maxFraction).toInt()
        val maxRight = (width * maxFraction).toInt()

        var top = 0
        while (top < maxTop && surround(uniformRowValue(top))) top++
        var bottom = height - 1
        while (height - 1 - bottom < maxBottom && bottom > top && surround(uniformRowValue(bottom))) bottom--
        var left = 0
        while (left < maxLeft && surround(uniformColValue(left))) left++
        var right = width - 1
        while (width - 1 - right < maxRight && right > left && surround(uniformColValue(right))) right--

        if (top == 0 && left == 0 && bottom == height - 1 && right == width - 1) return this
        val newW = right - left + 1
        val newH = bottom - top + 1
        if (newW < width * 0.6 || newH < height * 0.6) return this
        val out = IntArray(newW * newH)
        for (y in 0 until newH) {
            val srcRow = (top + y) * width + left
            System.arraycopy(data, srcRow, out, y * newW, newW)
        }
        return GrayImage(newW, newH, out)
    }

    private fun absDiff(a: Int, b: Int): Int = if (a > b) a - b else b - a
}
