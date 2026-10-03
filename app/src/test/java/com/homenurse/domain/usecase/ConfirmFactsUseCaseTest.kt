package com.homenurse.domain.usecase

import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.testing.FakeCarePlanRepository
import com.homenurse.testing.FakeDocumentRepository
import com.homenurse.testing.FakeFactRepository
import com.homenurse.testing.FakeMedicationRepository
import com.homenurse.testing.FakeProfileRepository
import com.homenurse.testing.FixedClock
import com.homenurse.testing.SequentialIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The trust boundary: only explicit user confirmation promotes an extracted
 * fact to confirmed medical information and derives medications/profile rows;
 * rejection removes everything derived; the document only becomes CONFIRMED
 * once ALL its facts are reviewed and at least one is confirmed.
 */
class ConfirmFactsUseCaseTest {

    private val clock = FixedClock()
    private lateinit var facts: FakeFactRepository
    private lateinit var meds: FakeMedicationRepository
    private lateinit var profiles: FakeProfileRepository
    private lateinit var docs: FakeDocumentRepository
    private lateinit var carePlan: FakeCarePlanRepository
    private lateinit var useCase: ConfirmFactsUseCase

    @Before
    fun setUp() {
        facts = FakeFactRepository()
        meds = FakeMedicationRepository(facts)
        profiles = FakeProfileRepository()
        docs = FakeDocumentRepository()
        carePlan = FakeCarePlanRepository()
        useCase = ConfirmFactsUseCase(
            facts = facts,
            medications = meds,
            profiles = profiles,
            documents = docs,
            generateCarePlan = GenerateCarePlanUseCase(meds, facts, carePlan, SequentialIds("task")),
            clock = clock,
        )
    }

    private fun putDocument(id: String, status: ProcessingStatus = ProcessingStatus.REVIEW_REQUIRED) {
        docs.put(
            MedicalDocument(
                id = id,
                title = "Prescription",
                mimeType = "image/jpeg",
                sizeBytes = 100,
                pageCount = 1,
                status = status,
                extractedText = null,
                failureReason = null,
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )
    }

    private fun fact(
        id: String,
        type: FactType,
        status: FactStatus = FactStatus.PENDING,
        name: String? = null,
        value: String = name ?: "value",
        frequency: String? = null,
    ) = MedicalFact(
        id = id,
        documentId = "doc-1",
        type = type,
        status = status,
        name = name,
        dose = null,
        frequency = frequency,
        timing = null,
        duration = null,
        value = value,
        sourceText = value,
        confidence = 0.9,
    )

    @Test
    fun `confirming a medication fact derives the medication and care tasks`() = runTest {
        putDocument("doc-1")
        facts.seed(fact("f1", FactType.MEDICATION, name = "Metformin", frequency = "Twice daily"))

        useCase.confirm("f1")

        assertEquals(FactStatus.CONFIRMED, facts.getFact("f1")!!.status)
        val derived = meds.all()
        assertEquals(1, derived.size)
        assertTrue(derived.single().confirmedByUser)
        assertEquals("Metformin", derived.single().name)
        // Care plan now contains the reminder slots.
        assertEquals(2, carePlan.currentTasks.count { it.source == com.homenurse.domain.model.TaskSource.MEDICATION })
        // No pending facts left and one confirmed → document becomes CONFIRMED.
        assertEquals(ProcessingStatus.CONFIRMED, docs.getDocument("doc-1")!!.status)
    }

    @Test
    fun `document stays in review while other facts are pending`() = runTest {
        putDocument("doc-1")
        facts.seed(
            fact("f1", FactType.MEDICATION, name = "Metformin", frequency = "Once daily"),
            fact("f2", FactType.CONDITION, name = "Hypertension"),
        )

        useCase.confirm("f1")
        assertEquals(ProcessingStatus.REVIEW_REQUIRED, docs.getDocument("doc-1")!!.status)

        useCase.confirm("f2")
        assertEquals(ProcessingStatus.CONFIRMED, docs.getDocument("doc-1")!!.status)
    }

    @Test
    fun `rejecting a medication removes it and never marks document confirmed`() = runTest {
        putDocument("doc-1")
        facts.seed(fact("f1", FactType.MEDICATION, name = "Metformin", frequency = "Once daily"))
        useCase.confirm("f1")
        assertEquals(1, meds.all().size)

        useCase.reject("f1")

        assertEquals(FactStatus.REJECTED, facts.getFact("f1")!!.status)
        assertTrue("rejected medication must be gone", meds.all().isEmpty())
        assertTrue("care plan must have no generated tasks", carePlan.currentTasks.none {
            it.source == com.homenurse.domain.model.TaskSource.MEDICATION
        })
        // Note: document review status only ever upgrades (confirm → CONFIRMED);
        // rejecting afterwards must NOT restore pending state or lose the review.
        assertEquals(ProcessingStatus.CONFIRMED, docs.getDocument("doc-1")!!.status)
        assertTrue(facts.all.all { it.status == FactStatus.REJECTED })
    }

    @Test
    fun `confirming a condition fact derives profile condition and rejecting removes it`() =
        runTest {
            putDocument("doc-1")
            facts.seed(fact("f2", FactType.CONDITION, name = "Type 2 diabetes"))

            useCase.confirm("f2")
            assertEquals(1, profiles.conditions.size)
            assertEquals("Type 2 diabetes", profiles.conditions.single().name)
            assertEquals("f2", profiles.conditions.single().factId)
            assertEquals("doc-1", profiles.conditions.single().sourceDocumentId)

            useCase.reject("f2")
            assertTrue(profiles.conditions.isEmpty())
        }

    @Test
    fun `confirming an allergy fact derives profile allergy`() = runTest {
        putDocument("doc-1")
        facts.seed(fact("f3", FactType.ALLERGY, name = "Penicillin"))

        useCase.confirm("f3")

        assertEquals(1, profiles.allergies.size)
        assertEquals("Penicillin", profiles.allergies.single().name)
    }

    @Test
    fun `confirming a lab fact derives lab result with provenance`() = runTest {
        putDocument("doc-1")
        facts.seed(fact("f4", FactType.LAB_RESULT, name = "Hb", value = "14.2 g/dl"))

        useCase.confirm("f4")

        assertEquals(1, profiles.labResults.size)
        assertEquals("Hb", profiles.labResults.single().testName)
        assertEquals("14.2 g/dl", profiles.labResults.single().value)
        assertEquals("doc-1", profiles.labResults.single().sourceDocumentId)
    }

    @Test
    fun `edit and confirm applies corrections before deriving`() = runTest {
        putDocument("doc-1")
        facts.seed(fact("f1", FactType.MEDICATION, name = "Metformn", frequency = "Twice daily"))

        useCase.editAndConfirm(
            factId = "f1",
            name = "Metformin",
            dose = "1000 mg",
            frequency = "Once daily",
            timing = null,
            duration = null,
            value = "Metformin 1000 mg once daily",
        )

        val corrected = facts.getFact("f1")!!
        assertEquals(FactStatus.CONFIRMED, corrected.status)
        assertEquals("Metformin", corrected.name)
        assertEquals("1000 mg", corrected.dose)
        assertEquals("Once daily", corrected.frequency)

        val derived = meds.all()
        assertEquals(1, derived.size)
        assertEquals("Metformin", derived.single().name)
        assertEquals("1000 mg", derived.single().dose)
        // Old derived rows were replaced, not duplicated.
        assertEquals(1, carePlan.currentTasks.count {
            it.source == com.homenurse.domain.model.TaskSource.MEDICATION
        })
    }

    @Test
    fun `confirmMany confirms every listed fact`() = runTest {
        putDocument("doc-1")
        facts.seed(
            fact("f1", FactType.MEDICATION, name = "Amlodipine", frequency = "Once daily"),
            fact("f2", FactType.CONDITION, name = "Hypertension"),
            fact("f3", FactType.ALLERGY, name = "Sulfa"),
        )

        useCase.confirmMany(listOf("f1", "f2", "f3"))

        assertTrue(facts.all.all { it.status == FactStatus.CONFIRMED })
        assertEquals(ProcessingStatus.CONFIRMED, docs.getDocument("doc-1")!!.status)
        assertEquals(1, meds.all().size)
        assertEquals(1, profiles.conditions.size)
        assertEquals(1, profiles.allergies.size)
    }

    @Test
    fun `confirm on unknown fact id is a safe no-op`() = runTest {
        putDocument("doc-1")
        useCase.confirm("missing")
        useCase.reject("missing")
        assertNotNull(docs.getDocument("doc-1"))
        assertTrue(meds.all().isEmpty())
    }

    @Test
    fun `confirmMany with empty list changes nothing`() = runTest {
        putDocument("doc-1")
        facts.seed(fact("f1", FactType.MEDICATION, name = "Metformin", frequency = "Once daily"))

        useCase.confirmMany(emptyList())

        assertEquals(FactStatus.PENDING, facts.getFact("f1")!!.status)
        assertTrue(meds.all().isEmpty())
        assertTrue(carePlan.currentTasks.isEmpty())
    }
}
