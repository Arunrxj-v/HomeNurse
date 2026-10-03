package com.homenurse.domain.usecase

import com.homenurse.domain.model.AiContext
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.repository.CarePlanRepository
import com.homenurse.domain.repository.DocumentRepository
import com.homenurse.domain.repository.FactRepository
import com.homenurse.domain.repository.MedicationRepository
import kotlinx.coroutines.flow.first

/**
 * Assembles the ONLY context the on-device model ever sees:
 * confirmed facts, confirmed medications, care tasks and document titles.
 *
 * Pending/rejected extraction candidates are filtered out here (single choke
 * point), so an unreviewed OCR result can never influence an AI answer.
 */
class BuildAiContextUseCase(
    private val facts: FactRepository,
    private val medications: MedicationRepository,
    private val carePlan: CarePlanRepository,
    private val documents: DocumentRepository,
) {

    suspend operator fun invoke(): AiContext {
        val confirmedFacts = facts.confirmedFacts().filter { it.status == FactStatus.CONFIRMED }
        val confirmedMedications = medications.all().filter { it.confirmedByUser }
        val plan = carePlan.observePlan().first()

        val documentIds = (confirmedFacts.map { it.documentId } + confirmedMedications.map { it.documentId })
            .toSet()
        val titles = documentIds.mapNotNull { id ->
            documents.getDocument(id)?.let { id to it.title }
        }.toMap()

        return AiContext(
            confirmedFacts = confirmedFacts,
            medications = confirmedMedications,
            careTasks = plan?.tasks.orEmpty(),
            documentTitles = titles,
        )
    }
}
