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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The AI context choke point: the on-device model must only ever see
 * confirmed facts + confirmed medications. Pending or rejected extraction
 * candidates are filtered HERE, even if the repository returned them
 * (defense in depth against a data-source bug).
 */
class BuildAiContextUseCaseTest {

    private lateinit var facts: FakeFactRepository
    private lateinit var meds: FakeMedicationRepository
    private lateinit var docs: FakeDocumentRepository
    private lateinit var carePlan: FakeCarePlanRepository
    private lateinit var useCase: BuildAiContextUseCase

    @Before
    fun setUp() {
        facts = FakeFactRepository()
        meds = FakeMedicationRepository(facts)
        docs = FakeDocumentRepository()
        carePlan = FakeCarePlanRepository()
        useCase = BuildAiContextUseCase(facts, meds, carePlan, docs)
    }

    private fun fact(
        id: String,
        documentId: String,
        status: FactStatus,
        type: FactType = FactType.CONDITION,
        name: String? = "Hypertension",
        value: String = name ?: "v",
    ) = MedicalFact(
        id = id,
        documentId = documentId,
        type = type,
        status = status,
        name = name,
        dose = null,
        frequency = null,
        timing = null,
        duration = null,
        value = value,
        sourceText = value,
        confidence = 0.9,
    )

    private fun doc(id: String, title: String) = docs.put(
        MedicalDocument(
            id = id,
            title = title,
            mimeType = "image/jpeg",
            sizeBytes = 1,
            pageCount = 1,
            status = ProcessingStatus.CONFIRMED,
            extractedText = null,
            failureReason = null,
            createdAt = 0,
            updatedAt = 0,
        ),
    )

    @Test
    fun `only confirmed facts reach the model context`() = runTest {
        doc("doc-1", "Discharge")
        doc("doc-2", "Lab report")
        facts.seed(
            fact("f1", "doc-1", FactStatus.CONFIRMED),
            fact("f2", "doc-1", FactStatus.PENDING),
            fact("f3", "doc-2", FactStatus.REJECTED),
        )

        val context = useCase()

        assertEquals(listOf("f1"), context.confirmedFacts.map { it.id })
    }

    @Test
    fun `context filters status itself even when the repository returns everything`() = runTest {
        facts.confirmedFactsReturnsAll = true
        facts.seed(
            fact("f1", "doc-1", FactStatus.CONFIRMED),
            fact("f2", "doc-1", FactStatus.PENDING),
        )

        val context = useCase()

        assertEquals(listOf("f1"), context.confirmedFacts.map { it.id })
    }

    @Test
    fun `unconfirmed medication never reaches the context`() = runTest {
        facts.seed(fact("f1", "doc-1", FactStatus.PENDING, type = FactType.MEDICATION, name = "Metformin"))
        meds.upsertFromFact("f1") // confirmedByUser = false

        val context = useCase()
        assertTrue(context.medications.isEmpty())
    }

    @Test
    fun `confirmed medication reaches the context`() = runTest {
        facts.seed(fact("f1", "doc-1", FactStatus.CONFIRMED, type = FactType.MEDICATION, name = "Metformin"))
        meds.upsertFromFact("f1")

        val context = useCase()
        assertEquals(listOf("Metformin"), context.medications.map { it.name })
        assertTrue(context.medications.single().confirmedByUser)
    }

    @Test
    fun `document titles are limited to documents referenced by confirmed data`() = runTest {
        doc("doc-1", "Discharge summary")
        doc("doc-9", "Unrelated scan")
        facts.seed(
            fact("f1", "doc-1", FactStatus.CONFIRMED),
            fact("f2", "doc-9", FactStatus.PENDING),
        )

        val context = useCase()

        assertEquals(mapOf("doc-1" to "Discharge summary"), context.documentTitles)
    }

    @Test
    fun `context of pending-only data is empty`() = runTest {
        facts.seed(fact("f2", "doc-1", FactStatus.PENDING))
        val context = useCase()
        assertTrue(context.confirmedFacts.isEmpty())
        assertTrue(context.medications.isEmpty())
        assertTrue(context.documentTitles.isEmpty())
        assertTrue(context.careTasks.isEmpty())
    }
}
