package com.homenurse.document

/**
 * Conservative OCR normalization.
 *
 * Two representations are kept for every line:
 *  * [NormalizedLine.raw] — the recognizer's output, verbatim (provenance).
 *  * [NormalizedLine.text] — a *conservative* cleanup used for extraction.
 *
 * Rules of engagement:
 *  * Never rewrite medicine names or free text — only obvious numeric/unit
 *    artifacts are corrected, and only in numeric context ("5OO mg" →
 *    "500 mg"; "Metforrnin" is NEVER silently "fixed").
 *  * Every structural correction marks the line [NormalizedLine.uncertain],
 *    which caps the fact's confidence so the item is flagged for review.
 *  * Cosmetic fixes (whitespace, fullwidth digits, smart quotes) do not
 *    mark uncertainty.
 */
data class NormalizedLine(
    /** Normalized text used for extraction. */
    val text: String,
    /** Verbatim recognizer output for this (possibly merged) line. */
    val raw: String,
    val page: Int,
    val box: OcrBox?,
    val confidence: Float?,
    val uncertain: Boolean,
) {
    val looksUnreliable: Boolean
        get() = uncertain || (confidence != null && confidence < UNRELIABLE_CONFIDENCE)

    companion object {
        /** Line confidence below this flags the extracted item for review. */
        const val UNRELIABLE_CONFIDENCE = 0.6f
    }
}

/** Raw + normalized document text with uncertainty bookkeeping. */
data class NormalizedOcr(val lines: List<NormalizedLine>) {

    val changed: Boolean
        get() = lines.any { it.text != it.raw }

    val uncertainCount: Int
        get() = lines.count { it.uncertain }

    val rawText: String
        get() = joinPages(lines.map { it.raw })

    val normalizedText: String
        get() = joinPages(lines.map { it.text })

    private fun joinPages(lineTexts: List<String>): String {
        // lines are in page order; insert a blank line between pages.
        val builder = StringBuilder()
        var lastPage: Int? = null
        lines.forEachIndexed { index, line ->
            if (lastPage != null && line.page != lastPage) builder.append("\n\n")
            else if (builder.isNotEmpty()) builder.append('\n')
            builder.append(lineTexts[index])
            lastPage = line.page
        }
        return builder.toString()
    }
}

object OcrTextNormalizer {

    fun normalize(rawLines: List<OcrLine>): NormalizedOcr {
        if (rawLines.isEmpty()) return NormalizedOcr(emptyList())
        val merged = mergeBrokenWords(rawLines)
        return NormalizedOcr(merged.map { normalizeLine(it) })
    }

    // --- line merging (dehyphenation across line breaks) -------------------------------

    private data class Working(val text: String, val raw: String, val line: OcrLine)

    private fun mergeBrokenWords(rawLines: List<OcrLine>): List<Working> {
        val out = ArrayList<Working>(rawLines.size)
        var i = 0
        while (i < rawLines.size) {
            val current = rawLines[i]
            val next = rawLines.getOrNull(i + 1)
            val breakable = next != null &&
                next.page == current.page &&
                current.text.trimEnd().endsWith('-') &&
                next.text.trimStart().firstOrNull()?.isLowerCase() == true
            if (breakable) {
                val text = current.text.trimEnd().dropLast(1) + next.text.trimStart()
                out += Working(
                    text = text,
                    raw = current.text.trimEnd() + " " + next.text.trimStart(),
                    line = OcrLine(
                        text = text,
                        confidence = minConfidence(current.confidence, next.confidence),
                        box = current.box?.let { c -> next.box?.let { c.union(it) } }
                            ?: next.box ?: current.box,
                        angle = current.angle,
                        page = current.page,
                    ),
                )
                i += 2
            } else {
                out += Working(current.text, current.text, current)
                i++
            }
        }
        return out
    }

    private fun minConfidence(a: Float?, b: Float?): Float? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    // --- per-line normalization ----------------------------------------------------------

    private fun normalizeLine(working: Working): NormalizedLine {
        var text = cosmeticNormalize(working.text)
        val raw = working.raw

        // Structural fixes — each one marks the line uncertain.
        val structural = StringBuilder()
        val beforeStructural = text
        text = fixSpacedDecimal(text).also { if (it != beforeStructural) structural.append("decimal ") }
        var step = text
        text = fixCommaDecimal(text).also { if (it != step) structural.append("comma ") }
        step = text
        text = fixThousands(text).also { if (it != step) structural.append("thousands ") }
        step = text
        text = fixBrokenUnits(text).also { if (it != step) structural.append("unit ") }
        step = text
        text = fixMmHg(text).also { if (it != step) structural.append("mmhg ") }
        step = text
        val confusable = fixNumericConfusables(text)
        text = confusable.first
        if (confusable.second) structural.append("confusable ")
        step = text
        text = fixDuplicatedLetters(text).also { if (it != step) structural.append("dup ") }

        val uncertain = structural.isNotEmpty() || looksDamaged(text) ||
            (working.line.confidence != null &&
                working.line.confidence < NormalizedLine.UNRELIABLE_CONFIDENCE)

        return NormalizedLine(
            text = text,
            raw = raw,
            page = working.line.page,
            box = working.line.box,
            confidence = working.line.confidence,
            uncertain = uncertain,
        )
    }

    /** Whitespace, fullwidth forms, quotes — cosmetic, never uncertainty. */
    private fun cosmeticNormalize(input: String): String {
        val mapped = buildString(input.length) {
            for (c in input) {
                when {
                    c.code in 0xFF01..0xFF5E -> append((c.code - 0xFEE0).toChar())
                    c == '\u3000' -> append(' ')
                    c == '\u200B' || c == '\uFEFF' || c == '\u00AD' -> Unit
                    c == '\u2018' || c == '\u2019' -> append('\'')
                    c == '\u201C' || c == '\u201D' -> append('"')
                    c == '\u2212' -> append('-')
                    c == '\u00A0' -> append(' ')
                    else -> append(c)
                }
            }
        }
        return mapped.replace(Regex("""[ \t]+"""), " ").trim()
    }

    private fun fixSpacedDecimal(text: String): String =
        DECIMAL_SPACED.replace(text) { "${it.groupValues[1]}.${it.groupValues[2]}" }

    private fun fixCommaDecimal(text: String): String =
        COMMA_DECIMAL.replace(text) { "${it.groupValues[1]}.${it.groupValues[2]}" }

    private fun fixThousands(text: String): String =
        THOUSANDS.replace(text) { it.groupValues[1] + it.groupValues[2] }

    private fun fixBrokenUnits(text: String): String =
        BROKEN_UNIT.replace(text) { "${it.groupValues[1]}/${it.groupValues[2]}" }

    private fun fixMmHg(text: String): String =
        MM_HG_SPACED.replace(text) { "${it.groupValues[1]} mmHg" }

    private fun fixDuplicatedLetters(text: String): String =
        DUP_LETTERS.replace(text) { "${it.groupValues[1]}${it.groupValues[1]}" }

    // --- context-sensitive O/0, I/l/1, S/5, B/8 correction ------------------------------

    private val NUMERIC_TOKEN = Regex("""[0-9OoIlSsBb|,./+-]+""")
    private val UNIT_AFTER = Regex(
        """^\s*(?:mg/dl|g/dl|mcg/dl|mmol/l|iu/l|miu/l|ng/ml|pg/ml|u/l|meq/l|k/µl|cells/µl|""" +
            """mmhg|mcg|µg|ug|mg|kg|ml|iu|units?|g|l|%|bpm)(?:\b|(?=\s|$))""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Confusable characters are corrected only inside numeric tokens that
     * already contain a real digit, and only when a unit sits right next to
     * the token or at least two confusables are present. This keeps
     * "B12", "O2" and medicine names untouched.
     */
    private fun fixNumericConfusables(text: String): Pair<String, Boolean> {
        var changed = false
        val out = NUMERIC_TOKEN.replace(text) { match ->
            val token = match.value
            if (!token.any { it.isDigit() }) return@replace token
            val after = text.substring(match.range.last + 1)
            val unitAdjacent = UNIT_AFTER.containsMatchIn(after)
            val trimmed = token.trimEnd(',', '.', '/', '+', '-')
            val confusables = trimmed.count { it in "OoIlSsBb|" }
            if (confusables == 0) return@replace token
            if (!unitAdjacent && confusables < 2) return@replace token

            // Vitamin-style digit blocks (B12, B6) must never become numbers.
            val digits = trimmed.filter { it.isDigit() }
            val vitaminLike = digits.length <= 2 && (digits.toIntOrNull() ?: -1) in 1..12

            val fixed = buildString {
                for (c in token) {
                    append(
                        when {
                            c == 'O' || c == 'o' -> '0'
                            c == 'l' || c == 'I' || c == '|' -> '1'
                            (c == 'S' || c == 's') && '-' !in token -> '5'
                            (c == 'B' || c == 'b') && !vitaminLike && digits.length >= 2 -> '8'
                            else -> c
                        },
                    )
                }
            }
            if (fixed != token) changed = true
            fixed
        }
        return out to changed
    }

    /** Replacement-character boxes, FFFD, repeated question marks. */
    private fun looksDamaged(text: String): Boolean =
        text.contains('\uFFFD') ||
            text.contains('□') ||
            DAMAGED_RUNS.containsMatchIn(text)

    // --- regexes -------------------------------------------------------------------------

    private val DECIMAL_SPACED = Regex("""(\d)\s+\.\s+(\d)""")
    private val COMMA_DECIMAL = Regex("""(\d{1,4}),(\d{1,2})(?!\d)""")
    private val THOUSANDS = Regex("""(\d),(\d{3})(?!\d)""")
    private val BROKEN_UNIT = Regex(
        """\b(mcg|mg|kg|g|mmol|meq|µmol)\s*/\s*(dl|l|ml|kg)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MM_HG_SPACED = Regex("""(\d{2,4})\s+mm\s+hg\b""", RegexOption.IGNORE_CASE)
    private val DUP_LETTERS = Regex("""([a-zA-Z])\1{2,}""")
    private val DAMAGED_RUNS = Regex("""(?:\?\s*){2,}|_{3,}|\|{3,}""")
}
