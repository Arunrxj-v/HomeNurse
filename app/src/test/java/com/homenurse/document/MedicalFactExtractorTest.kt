package com.homenurse.document

import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.util.Clock
import com.homenurse.domain.util.IdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic extraction tests. Properties under test:
 *  * values are copied verbatim from the source line (provenance preserved),
 *  * unmatched lines produce NO fact (nothing is invented),
 *  * every fact starts PENDING and carries its exact source line,
 *  * duplicates are collapsed and output is capped.
 */
class MedicalFactExtractorTest {

    private val ids = object : IdGenerator {
        private var counter = 0
        override fun newId(): String = "id-${counter++}"
    }
    private val clock = object : Clock {
        override fun now(): Long = 1_700_000_000_000L
    }

    private fun extract(vararg lines: String): List<MedicalFact> =
        MedicalFactExtractor.extract("doc-1", lines.toList(), ids, clock)

    private fun single(vararg lines: String): MedicalFact =
        extract(*lines).single()

    // --- patient info ---------------------------------------------------------

    @Test
    fun `patient label line becomes patient info fact`() {
        val fact = single("Name: Rajesh Kumar")
        assertEquals(FactType.PATIENT_INFO, fact.type)
        assertEquals("Name", fact.name)
        assertEquals("Rajesh Kumar", fact.value)
        assertEquals("doc-1", fact.documentId)
        assertEquals("Name: Rajesh Kumar", fact.sourceText)
    }

    @Test
    fun `age line becomes patient info fact`() {
        val fact = single("Age: 67")
        assertEquals(FactType.PATIENT_INFO, fact.type)
        assertEquals("67", fact.value)
    }

    // --- allergies ------------------------------------------------------------

    @Test
    fun `allergy label is extracted`() {
        val fact = single("Allergy: Penicillin")
        assertEquals(FactType.ALLERGY, fact.type)
        assertEquals("Penicillin", fact.name)
        assertEquals("Penicillin", fact.value)
    }

    @Test
    fun `allergic to phrasing is extracted`() {
        val fact = single("Patient is allergic to sulfa drugs")
        assertEquals(FactType.ALLERGY, fact.type)
        assertEquals("sulfa drugs", fact.value)
    }

    @Test
    fun `negative allergy statement produces no allergy fact`() {
        val facts = extract("No known allergies")
        assertTrue(facts.none { it.type == FactType.ALLERGY })
    }

    // --- lab results ----------------------------------------------------------

    @Test
    fun `lab value with unit and reference range keeps all parts`() {
        val fact = single("Haemoglobin 14.2 g/dl (reference 13.0 - 15.0)")
        assertEquals(FactType.LAB_RESULT, fact.type)
        assertEquals("Haemoglobin", fact.name)
        assertEquals("14.2 g/dl (reference 13.0 - 15.0)", fact.value)
    }

    @Test
    fun `blood pressure pair is extracted as lab result`() {
        val fact = single("Blood pressure 120/80 mmHg")
        assertEquals(FactType.LAB_RESULT, fact.type)
        assertEquals("120/80 mmHg", fact.value)
    }

    @Test
    fun `drug dose line is never misread as lab result`() {
        val facts = extract("Metformin 500 mg twice daily")
        assertTrue(facts.none { it.type == FactType.LAB_RESULT })
        assertEquals(FactType.MEDICATION, facts.single().type)
    }

    // --- medications ----------------------------------------------------------

    @Test
    fun `full prescription line is decomposed with provenance`() {
        val fact = single("Metformin 500 mg twice daily after food for 30 days")
        assertEquals(FactType.MEDICATION, fact.type)
        assertEquals("Metformin", fact.name)
        assertEquals("500 mg", fact.dose)
        assertEquals("Twice daily", fact.frequency)
        assertEquals("After food", fact.timing)
        assertEquals("30 days", fact.duration)
        assertEquals(
            "Metformin, 500 mg, Twice daily, After food, 30 days",
            fact.value,
        )
        assertEquals("Metformin 500 mg twice daily after food for 30 days", fact.sourceText)
        assertEquals(0.9, fact.confidence, 0.0001)
    }

    @Test
    fun `every N hours frequency is rendered from the number`() {
        val fact = single("Paracetamol 1 g every 6 hours")
        assertEquals("Every 6 hours", fact.frequency)
        assertEquals("Paracetamol", fact.name)
    }

    @Test
    fun `form-only line yields medication with form name`() {
        val fact = single("Cream to affected area")
        assertEquals(FactType.MEDICATION, fact.type)
        assertEquals("Cream", fact.name)
    }

    @Test
    fun `frequency-only line yields medication without inventing a name`() {
        val fact = single("twice daily")
        assertEquals(FactType.MEDICATION, fact.type)
        assertNull(fact.name) // no name invented — frequency comes verbatim from the line
        assertEquals("Twice daily", fact.frequency)
    }

    // --- conditions, warnings, follow-up, instructions ------------------------

    @Test
    fun `diagnosis label becomes condition`() {
        val fact = single("Diagnosis: Type 2 diabetes mellitus")
        assertEquals(FactType.CONDITION, fact.type)
        assertEquals("Type 2 diabetes mellitus", fact.value)
    }

    @Test
    fun `diagnosed with phrasing becomes condition`() {
        val fact = single("Patient diagnosed with chronic kidney disease stage 3")
        assertEquals(FactType.CONDITION, fact.type)
        assertEquals("chronic kidney disease stage 3", fact.value)
    }

    @Test
    fun `warning phrasing becomes warning signs fact`() {
        val fact = single("Return immediately if fever continues")
        assertEquals(FactType.WARNING_SIGNS, fact.type)
    }

    @Test
    fun `follow up phrasing becomes follow up fact`() {
        val fact = single("Follow up with your doctor in 2 weeks")
        assertEquals(FactType.FOLLOW_UP, fact.type)
    }

    @Test
    fun `take instruction line becomes instruction fact`() {
        val fact = single("Take one tablet daily with food")
        assertEquals(FactType.INSTRUCTION, fact.type)
        assertEquals(FactStatus.PENDING, fact.status)
    }

    @Test
    fun `procedure label becomes procedure fact`() {
        val fact = single("Procedure: Cataract surgery, left eye")
        assertEquals(FactType.PROCEDURE, fact.type)
        assertEquals("Cataract surgery, left eye", fact.value)
    }

    // --- honesty guarantees ---------------------------------------------------

    @Test
    fun `non medical line produces no fact`() {
        assertTrue(extract("Thank you for visiting City Hospital today.").isEmpty())
        assertTrue(extract("Reception closes at 9 pm on weekdays.").isEmpty())
    }

    @Test
    fun `every extracted fact starts pending`() {
        val facts = extract(
            "Name: Asha Rao",
            "Allergy: Penicillin",
            "Metformin 500 mg twice daily",
            "Diagnosis: Hypertension",
        )
        assertTrue(facts.isNotEmpty())
        assertTrue(facts.all { it.status == FactStatus.PENDING })
        assertTrue(facts.all { it.documentId == "doc-1" })
        assertTrue(facts.all { it.sourceText.isNotBlank() })
    }

    @Test
    fun `duplicate lines are collapsed`() {
        val facts = extract("Allergy: Penicillin", "Allergy: Penicillin")
        assertEquals(1, facts.size)
    }

    @Test
    fun `extraction is capped at 200 facts`() {
        val lines = (1..250).map { "Name: Patient number $it" }
        assertEquals(200, MedicalFactExtractor.extract("doc-1", lines, ids, clock).size)
    }

    @Test
    fun `blank and too short lines are skipped`() {
        assertTrue(extract("", "a", "   ", "Age").isEmpty())
    }

    // --- layout: label/value pairs on one visual row ---------------------------------

    private fun line(
        text: String,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        confidence: Float? = null,
        uncertain: Boolean = false,
    ) = NormalizedLine(
        text = text,
        raw = text,
        page = 1,
        box = OcrBox(left = left, top = top, right = right, bottom = bottom),
        confidence = confidence,
        uncertain = uncertain,
    )

    private fun normalLine(
        text: String,
        confidence: Float? = null,
        uncertain: Boolean = false,
    ) = NormalizedLine(
        text = text,
        raw = text,
        page = 1,
        box = null,
        confidence = confidence,
        uncertain = uncertain,
    )

    private fun extractLines(vararg lines: NormalizedLine): List<MedicalFact> =
        MedicalFactExtractor.extract("doc-1", lines.toList(), ids, clock)

    @Test
    fun `label and value on one visual row merge into a lab fact`() {
        val facts = extractLines(
            line("Hemoglobin", left = 0f, right = 60f, top = 100f, bottom = 120f),
            line("13.2 g/dL", left = 70f, right = 160f, top = 102f, bottom = 118f),
        )

        val fact = facts.single()
        assertEquals(FactType.LAB_RESULT, fact.type)
        assertEquals("Hemoglobin", fact.name)
        assertEquals("13.2 g/dL", fact.value)
        assertEquals("g/dl", fact.unit)
        assertEquals("Hemoglobin 13.2 g/dL", fact.sourceText)
        assertFalse(fact.needsVerification) // clean geometry, clean text
    }

    @Test
    fun `row columns are ordered left to right regardless of read order`() {
        // Value column read first (slightly higher on the page) — the row must
        // still be assembled in reading order, not recognizer order.
        val facts = extractLines(
            line("13.2 g/dL", left = 200f, right = 300f, top = 100f, bottom = 120f),
            line("Hemoglobin", left = 0f, right = 100f, top = 101f, bottom = 119f),
        )

        val fact = facts.single()
        assertEquals("Hemoglobin", fact.name)
        assertEquals("13.2 g/dL", fact.value)
        assertEquals("Hemoglobin 13.2 g/dL", fact.sourceText)
    }

    @Test
    fun `lab fact carries unit and reference range separately from the value`() {
        val facts = extractLines(
            line("Haemoglobin", left = 0f, right = 60f, top = 100f, bottom = 120f),
            line("13.2 g/dL ref 12.0 - 15.0", left = 70f, right = 260f, top = 102f, bottom = 118f),
        )

        val fact = facts.single()
        assertEquals(FactType.LAB_RESULT, fact.type)
        assertEquals("13.2 g/dL (reference 12.0 - 15.0)", fact.value)
        assertEquals("g/dl", fact.unit)
        assertEquals("12.0 - 15.0", fact.referenceRange)
    }

    @Test
    fun `blood pressure pair keeps its numeric form`() {
        val facts = extractLines(
            line("Blood pressure", left = 0f, right = 70f, top = 100f, bottom = 120f),
            line("120/80 mmHg", left = 80f, right = 190f, top = 102f, bottom = 118f),
        )

        val fact = facts.single()
        assertEquals(FactType.LAB_RESULT, fact.type)
        assertEquals("Blood pressure", fact.name)
        assertEquals("120/80 mmHg", fact.value)
        assertEquals("mmhg", fact.unit)
    }

    @Test
    fun `rows without geometric overlap are not merged into a fake pair`() {
        val facts = extractLines(
            line("Hemoglobin", left = 0f, right = 60f, top = 100f, bottom = 120f),
            line("13.2 g/dL", left = 0f, right = 100f, top = 300f, bottom = 320f),
        )

        // No lab fact is invented from disconnected lines, and whatever else
        // comes out of the orphan value is never trusted.
        assertTrue(facts.none { it.type == FactType.LAB_RESULT })
        assertTrue(facts.all { it.needsVerification })
    }

    // --- structured medication fields -------------------------------------------------

    @Test
    fun `prescription line yields structured medication fields verbatim`() {
        val fact = single("Amoxicillin 500 mg by mouth three times a day with plenty of water")

        assertEquals(FactType.MEDICATION, fact.type)
        assertEquals("Amoxicillin", fact.name)
        assertEquals("500 mg", fact.dose)
        assertEquals("Three times daily", fact.frequency)
        assertEquals("by mouth", fact.route)
        assertEquals("with plenty of water", fact.instructions)
        assertEquals("Amoxicillin, 500 mg, Three times daily", fact.value)
        assertFalse(fact.needsVerification)
    }

    @Test
    fun `medication keeps timing and duration when present`() {
        val fact = single("Metformin 500 mg twice daily after food for 30 days")

        assertEquals("Metformin", fact.name)
        assertEquals("500 mg", fact.dose)
        assertEquals("Twice daily", fact.frequency)
        assertEquals("After food", fact.timing)
        assertEquals("30 days", fact.duration)
    }

    // --- uncertainty: never silently trusted or "corrected" ----------------------------

    @Test
    fun `uncertain line caps confidence so the fact needs verification`() {
        val fact = extractLines(normalLine("Metformin 500 mg twice daily", uncertain = true)).single()

        assertEquals(0.55, fact.confidence, 0.001)
        assertTrue(fact.needsVerification)
        assertEquals(FactStatus.PENDING, fact.status)
    }

    @Test
    fun `low recognizer confidence flags the fact for verification`() {
        val fact = extractLines(normalLine("Metformin 500 mg twice daily", confidence = 0.3f)).single()

        assertEquals(0.648, fact.confidence, 0.001) // 0.9 × (0.6 + 0.4×0.3)
        assertTrue(fact.needsVerification)
    }

    @Test
    fun `high recognizer confidence alone never confirms a fact`() {
        val fact = extractLines(normalLine("Metformin 500 mg twice daily", confidence = 0.95f)).single()

        assertEquals(0.882, fact.confidence, 0.001)
        assertFalse(fact.needsVerification) // eligible for review…
        assertEquals(FactStatus.PENDING, fact.status) // …but still not trusted
    }

    @Test
    fun `garbled medicine name is kept verbatim and flagged, never corrected`() {
        val fact = single("M3tformin 500 mg twice daily")

        assertEquals(FactType.MEDICATION, fact.type)
        assertEquals("M3tformin", fact.name) // NOT silently rewritten to "Metformin"
        assertEquals(0.55, fact.confidence, 0.001)
        assertTrue(fact.needsVerification)
    }
}
