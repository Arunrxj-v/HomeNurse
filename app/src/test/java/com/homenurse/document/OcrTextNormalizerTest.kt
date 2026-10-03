package com.homenurse.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conservative-normalization properties:
 *  * raw text is preserved verbatim next to the normalized form,
 *  * cosmetic fixes never mark uncertainty,
 *  * structural fixes (numeric confusion, decimals) DO mark uncertainty so
 *    the fact is flagged "needs verification",
 *  * medicine names and ambiguous tokens are NEVER rewritten,
 *  * units stay intact (mg, mcg, g/dl, mmHg, ...).
 */
class OcrTextNormalizerTest {

    private fun normalize(vararg lines: String, confidence: Float? = null): NormalizedOcr =
        OcrTextNormalizer.normalize(
            lines.map { text ->
                OcrLine(text = text, confidence = confidence, page = 1)
            },
        )

    private fun text(vararg lines: String): String = normalize(*lines).lines.single().text

    private fun uncertain(vararg lines: String): Boolean = normalize(*lines).lines.single().uncertain

    // --- cosmetic (never uncertainty) -------------------------------------------------

    @Test
    fun `fullwidth digits are mapped to ascii without uncertainty`() {
        val result = normalize("Metformin ５００ mg")
        assertEquals("Metformin 500 mg", result.lines.single().text)
        assertFalse(result.lines.single().uncertain)
    }

    @Test
    fun `whitespace runs collapse without uncertainty`() {
        val result = normalize("  Metformin   500\t mg  ")
        assertEquals("Metformin 500 mg", result.lines.single().text)
        assertFalse(result.lines.single().uncertain)
    }

    @Test
    fun `zero width characters and smart quotes are cleaned cosmetically`() {
        val result = normalize("Asha​ Rao")
        assertEquals("Asha Rao", result.lines.single().text)
        assertFalse(result.lines.single().uncertain)

        val quoted = normalize("“Dr. Rao” said stop")
        assertEquals("\"Dr. Rao\" said stop", quoted.lines.single().text)
        assertFalse(quoted.lines.single().uncertain)
    }

    // --- raw is never destroyed -------------------------------------------------------

    @Test
    fun `raw and normalized representations are kept separately`() {
        val result = normalize("Haemoglobin 13,2 g/dl")
        assertEquals("Haemoglobin 13,2 g/dl", result.rawText) // verbatim recognizer output
        assertEquals("Haemoglobin 13.2 g/dl", result.normalizedText) // conservative fix
        assertTrue(result.changed)
    }

    @Test
    fun `lines on one page join with newlines`() {
        val result = normalize("Name: Asha", "Metformin 500 mg")
        assertEquals("Name: Asha\nMetformin 500 mg", result.rawText)
        assertEquals("Name: Asha\nMetformin 500 mg", result.normalizedText)
    }

    @Test
    fun `pages are separated by blank lines in both representations`() {
        val result = OcrTextNormalizer.normalize(
            listOf(OcrLine(text = "page one line", page = 1), OcrLine(text = "page two line", page = 2)),
        )
        assertEquals("page one line\n\npage two line", result.rawText)
        assertEquals("page one line\n\npage two line", result.normalizedText)
    }

    // --- structural fixes mark uncertainty --------------------------------------------

    @Test
    fun `comma decimal point is repaired and flagged`() {
        assertEquals("13.2 g/dl", text("13,2 g/dl"))
        assertTrue(uncertain("13,2 g/dl"))
    }

    @Test
    fun `thousands separator is removed and flagged`() {
        assertEquals("1000 mg", text("1,000 mg"))
        assertTrue(uncertain("1,000 mg"))
    }

    @Test
    fun `spaced decimal point is joined and flagged`() {
        assertEquals("13.2", text("13 . 2"))
        assertTrue(uncertain("13 . 2"))
    }

    @Test
    fun `digit-confusable letters next to a unit are corrected and flagged`() {
        assertEquals("Metformin 500 mg", text("Metformin 5OO mg"))
        assertTrue(uncertain("Metformin 5OO mg"))
    }

    @Test
    fun `pipe digit confusion in values is corrected`() {
        assertEquals("value 13.2 g/dl", text("value |3.2 g/dl"))
        assertTrue(uncertain("value |3.2 g/dl"))
    }

    @Test
    fun `hyphenated digit groups are repaired without touching letters`() {
        assertEquals("dose 1-0-0", text("dose 1-O-O"))
        assertTrue(uncertain("dose 1-O-O"))
    }

    @Test
    fun `spaced mmHg unit is joined and flagged`() {
        assertEquals("120 mmHg", text("120 mm Hg"))
        assertTrue(uncertain("120 mm Hg"))
    }

    @Test
    fun `broken unit spacing is repaired`() {
        assertEquals("12 g/dl", text("12 g / dl"))
        assertTrue(uncertain("12 g / dl"))
    }

    @Test
    fun `already correct units are left untouched`() {
        val result = normalize("Metformin 500 mg twice daily")
        assertEquals("Metformin 500 mg twice daily", result.lines.single().text)
        assertFalse(result.lines.single().uncertain)
    }

    // --- names and ambiguous tokens are never rewritten --------------------------------

    @Test
    fun `garbled medicine names are never corrected`() {
        val result = normalize("Metforrnin 500 mg")
        assertEquals("Metforrnin 500 mg", result.lines.single().text)
        assertFalse(result.lines.single().uncertain) // nothing needed guessing
    }

    @Test
    fun `ambiguous O2 token without a unit stays as-is`() {
        val result = normalize("O2 sat 98%")
        assertEquals("O2 sat 98%", result.lines.single().text)
        assertFalse(result.lines.single().uncertain)
    }

    @Test
    fun `vitamin B12 is never turned into a number`() {
        val result = normalize("Vitamin B12 1 mcg")
        assertEquals("Vitamin B12 1 mcg", result.lines.single().text)
        assertFalse(result.lines.single().uncertain)
    }

    @Test
    fun `single confusable without unit context is left alone`() {
        val result = normalize("Room 5O2")
        assertEquals("Room 5O2", result.lines.single().text)
        assertFalse(result.lines.single().uncertain)
    }

    // --- damaged text is flagged --------------------------------------------------------

    @Test
    fun `replacement boxes and repeated question marks flag uncertainty`() {
        assertTrue(uncertain("Cough □□"))
        assertTrue(uncertain("Fever ??"))
        assertFalse(uncertain("Is this a question?"))
    }

    @Test
    fun `low recognizer confidence flags the line`() {
        val result = OcrTextNormalizer.normalize(
            listOf(OcrLine(text = "Metformin 500 mg", confidence = 0.3f)),
        )
        assertTrue(result.lines.single().uncertain)
        assertTrue(result.lines.single().looksUnreliable)
    }

    // --- line breaks --------------------------------------------------------------------

    @Test
    fun `word broken across lines is reassembled keeping raw provenance`() {
        val result = normalize("inter-", "national units")
        assertEquals("international units", result.lines.single().text)
        assertEquals("inter- national units", result.lines.single().raw)
        assertFalse(result.lines.single().uncertain)
    }

    @Test
    fun `hyphenated line is not joined when next line starts a new value`() {
        val result = normalize("penicillin-", "500 mg twice daily")
        assertEquals(2, result.lines.size)
        assertEquals("penicillin-", result.lines[0].text)
    }

    @Test
    fun `hyphenated line is not joined across pages`() {
        val result = OcrTextNormalizer.normalize(
            listOf(
                OcrLine(text = "inter-", page = 1),
                OcrLine(text = "national units", page = 2),
            ),
        )
        assertEquals(2, result.lines.size)
    }

    // --- empty --------------------------------------------------------------------------

    @Test
    fun `empty input produces empty output`() {
        val result = OcrTextNormalizer.normalize(emptyList())
        assertTrue(result.lines.isEmpty())
        assertEquals("", result.rawText)
        assertEquals("", result.normalizedText)
    }
}
