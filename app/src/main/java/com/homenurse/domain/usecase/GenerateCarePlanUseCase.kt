package com.homenurse.domain.usecase

import com.homenurse.domain.model.CarePlan
import com.homenurse.domain.model.CareTask
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.TaskKind
import com.homenurse.domain.model.TaskSource
import com.homenurse.domain.repository.CarePlanRepository
import com.homenurse.domain.repository.FactRepository
import com.homenurse.domain.repository.MedicationRepository
import com.homenurse.domain.util.IdGenerator

/**
 * Rebuilds the machine-generated portion of the care plan from confirmed
 * medical information only:
 *
 *  * one task per reminder slot of each confirmed medication
 *    (slot times come from [MedicineSchedule]; frequency text comes from the
 *    confirmed fact — nothing is invented),
 *  * one follow-up task per confirmed FOLLOW_UP fact.
 *
 * User-added tasks are preserved (the repository only replaces generated
 * rows). Fully deterministic — no AI involved — so the plan is grounded in
 * confirmed data by construction.
 */
class GenerateCarePlanUseCase(
    private val medications: MedicationRepository,
    private val facts: FactRepository,
    private val carePlan: CarePlanRepository,
    private val ids: IdGenerator,
) {

    suspend operator fun invoke(): CarePlan {
        val plan = carePlan.ensurePlan()
        val tasks = mutableListOf<CareTask>()

        medications.all().filter { it.confirmedByUser }.forEach { med ->
            val doseSuffix = med.dose?.let { " $it" }.orEmpty()
            val frequencySuffix = med.frequency?.let { " ($it)" }.orEmpty()
            val timingSuffix = med.timing?.let { ", $it" }.orEmpty()
            val baseTitle = "Take ${med.name}$doseSuffix$frequencySuffix$timingSuffix"

            val times = MedicineSchedule.timesFor(med.frequency, med.timing)
            if (times.isEmpty()) {
                // No recognised schedule: create the task without a reminder
                // time rather than inventing one; the user can set a time.
                tasks += CareTask(
                    id = ids.newId(),
                    planId = plan.id,
                    title = baseTitle,
                    kind = TaskKind.MEDICATION,
                    source = TaskSource.MEDICATION,
                    timeOfDayMin = null,
                    dueDate = null,
                    documentId = med.documentId,
                    factId = med.factId,
                    completed = false,
                )
            } else {
                times.forEach { time ->
                    tasks += CareTask(
                        id = ids.newId(),
                        planId = plan.id,
                        title = baseTitle,
                        kind = TaskKind.MEDICATION,
                        source = TaskSource.MEDICATION,
                        timeOfDayMin = time,
                        dueDate = null,
                        documentId = med.documentId,
                        factId = med.factId,
                        completed = false,
                    )
                }
            }
        }

        facts.confirmedFacts()
            .filter { it.type == FactType.FOLLOW_UP }
            .forEach { fact ->
                tasks += CareTask(
                    id = ids.newId(),
                    planId = plan.id,
                    title = fact.value,
                    kind = TaskKind.FOLLOW_UP,
                    source = TaskSource.CONFIRMED_FACT,
                    timeOfDayMin = null,
                    dueDate = null,
                    documentId = fact.documentId,
                    factId = fact.id,
                    completed = false,
                )
            }

        carePlan.replaceGeneratedTasks(plan.id, tasks)
        return carePlan.ensurePlan()
    }
}
