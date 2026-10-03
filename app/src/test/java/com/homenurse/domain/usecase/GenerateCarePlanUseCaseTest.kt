package com.homenurse.domain.usecase

import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.TaskKind
import com.homenurse.domain.model.TaskSource
import com.homenurse.testing.FakeCarePlanRepository
import com.homenurse.testing.FakeFactRepository
import com.homenurse.testing.FakeMedicationRepository
import com.homenurse.testing.SequentialIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Care plan grounding tests: the generated plan may only contain rows derived
 * from CONFIRMED medications and CONFIRMED follow-up facts, reminder slots
 * come from the deterministic schedule, unrecognised frequencies get NO
 * invented reminder time, and user-added tasks survive regeneration.
 */
class GenerateCarePlanUseCaseTest {

    private lateinit var facts: FakeFactRepository
    private lateinit var meds: FakeMedicationRepository
    private lateinit var carePlan: FakeCarePlanRepository
    private lateinit var useCase: GenerateCarePlanUseCase

    @Before
    fun setUp() {
        facts = FakeFactRepository()
        meds = FakeMedicationRepository(facts)
        carePlan = FakeCarePlanRepository()
        useCase = GenerateCarePlanUseCase(meds, facts, carePlan, SequentialIds("task"))
    }

    private fun medFact(
        id: String,
        status: FactStatus,
        name: String = "Metformin",
        dose: String? = "500 mg",
        frequency: String? = "Twice daily",
        timing: String? = null,
    ) = MedicalFact(
        id = id,
        documentId = "doc-1",
        type = FactType.MEDICATION,
        status = status,
        name = name,
        dose = dose,
        frequency = frequency,
        timing = timing,
        duration = null,
        value = "$name $dose",
        sourceText = "$name $dose $frequency",
        confidence = 0.9,
    )

    private fun followUpFact(id: String, status: FactStatus, value: String) = MedicalFact(
        id = id,
        documentId = "doc-1",
        type = FactType.FOLLOW_UP,
        status = status,
        name = null,
        dose = null,
        frequency = null,
        timing = null,
        duration = null,
        value = value,
        sourceText = value,
        confidence = 0.9,
    )

    @Test
    fun `confirmed twice daily medication creates one task per reminder slot`() = runTest {
        facts.seed(medFact("f1", FactStatus.CONFIRMED))
        meds.upsertFromFact("f1")

        val plan = useCase()
        val medTasks = plan.tasks.filter { it.kind == TaskKind.MEDICATION }

        assertEquals(2, medTasks.size)
        assertEquals(listOf(8 * 60, 20 * 60), medTasks.mapNotNull { it.timeOfDayMin }.sorted())
        assertTrue(medTasks.all { it.title.contains("Metformin") })
        assertTrue(medTasks.all { it.title.contains("500 mg") })
        assertTrue(medTasks.all { it.title.contains("Twice daily") })
        assertTrue(medTasks.all { it.source == TaskSource.MEDICATION })
        assertTrue(medTasks.all { it.factId == "f1" && it.documentId == "doc-1" })
    }

    @Test
    fun `unconfirmed medication fact produces zero tasks`() = runTest {
        facts.seed(medFact("f1", FactStatus.PENDING))
        meds.upsertFromFact("f1") // derives row with confirmedByUser = false

        val plan = useCase()
        assertTrue(plan.tasks.isEmpty())
    }

    @Test
    fun `unrecognised frequency gets a task without an invented reminder time`() = runTest {
        facts.seed(
            medFact("f1", FactStatus.CONFIRMED, name = "Tramadol", frequency = "As needed for pain"),
        )
        meds.upsertFromFact("f1")

        val plan = useCase()
        assertEquals(1, plan.tasks.size)
        assertNull(plan.tasks.single().timeOfDayMin)
    }

    @Test
    fun `confirmed follow up fact creates follow up task with provenance`() = runTest {
        facts.seed(followUpFact("f2", FactStatus.CONFIRMED, value = "Repeat blood test in 4 weeks"))

        val plan = useCase()

        assertEquals(1, plan.tasks.size)
        val task = plan.tasks.single()
        assertEquals(TaskKind.FOLLOW_UP, task.kind)
        assertEquals(TaskSource.CONFIRMED_FACT, task.source)
        assertEquals("Repeat blood test in 4 weeks", task.title)
        assertEquals("f2", task.factId)
    }

    @Test
    fun `pending follow up fact does not reach the care plan`() = runTest {
        facts.seed(followUpFact("f2", FactStatus.PENDING, value = "Repeat blood test"))
        val plan = useCase()
        assertTrue(plan.tasks.isEmpty())
    }

    @Test
    fun `user tasks survive regeneration while generated tasks are replaced`() = runTest {
        carePlan.ensurePlan()
        carePlan.addUserTask("Drink a glass of water", timeOfDayMin = 11 * 60)

        facts.seed(medFact("f1", FactStatus.CONFIRMED))
        meds.upsertFromFact("f1")
        useCase()
        val afterFirst = useCase() // regenerate again

        assertEquals(1, afterFirst.tasks.count { it.source == TaskSource.USER })
        assertEquals(2, afterFirst.tasks.count { it.source == TaskSource.MEDICATION })
        assertTrue(afterFirst.tasks.any { it.title == "Drink a glass of water" })

        // Regenerating with no medications removes generated rows but keeps the user task.
        meds.removeForFact("f1")
        val afterRemove = useCase()
        assertEquals(1, afterRemove.tasks.size)
        assertEquals(TaskSource.USER, afterRemove.tasks.single().source)
    }

    @Test
    fun `regeneration is idempotent for generated tasks`() = runTest {
        facts.seed(medFact("f1", FactStatus.CONFIRMED, frequency = "Once daily"))
        meds.upsertFromFact("f1")

        val first = useCase()
        val second = useCase()

        assertEquals(first.tasks.size, second.tasks.size)
        assertEquals(
            first.tasks.map { it.title to it.timeOfDayMin }.toSet(),
            second.tasks.map { it.title to it.timeOfDayMin }.toSet(),
        )
    }
}
