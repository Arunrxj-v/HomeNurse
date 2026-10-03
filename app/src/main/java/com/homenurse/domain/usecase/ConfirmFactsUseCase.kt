package com.homenurse.domain.usecase

import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.domain.repository.DocumentRepository
import com.homenurse.domain.repository.FactRepository
import com.homenurse.domain.repository.MedicationRepository
import com.homenurse.domain.repository.ProfileRepository
import com.homenurse.domain.util.Clock

/**
 * User review of extracted facts: Confirm / Edit / Reject.
 *
 * This is the trust boundary of the whole app: an OCR extraction only becomes
 * "confirmed medical information" (medication list, profile conditions,
 * allergies, labs, AI context) after the user acts here. Rejecting removes
 * anything derived from the fact again.
 */
class ConfirmFactsUseCase(
    private val facts: FactRepository,
    private val medications: MedicationRepository,
    private val profiles: ProfileRepository,
    private val documents: DocumentRepository,
    private val generateCarePlan: GenerateCarePlanUseCase,
    private val clock: Clock,
) {

    suspend fun confirm(factId: String) {
        val fact = facts.getFact(factId) ?: return
        facts.setStatus(factId, FactStatus.CONFIRMED)
        deriveFrom(fact.copy(status = FactStatus.CONFIRMED))
        afterChange(fact.documentId)
    }

    suspend fun confirmMany(factIds: List<String>) {
        factIds.forEach { confirm(it) }
    }

    /**
     * Manually added facts (review screen "add item"). The user asserts the
     * content themselves, so they enter as CONFIRMED — passing through the
     * same single trust gate (derive + bookkeeping) as [confirm].
     */
    suspend fun addConfirmed(fact: MedicalFact) {
        require(fact.status == FactStatus.CONFIRMED) { "manual facts must enter confirmed" }
        facts.addFacts(listOf(fact))
        deriveFrom(fact)
        afterChange(fact.documentId)
    }

    suspend fun reject(factId: String) {
        val fact = facts.getFact(factId) ?: return
        facts.setStatus(factId, FactStatus.REJECTED)
        medications.removeForFact(factId)
        profiles.removeDerived(factId)
        afterChange(fact.documentId)
    }

    suspend fun editAndConfirm(
        factId: String,
        name: String?,
        dose: String?,
        frequency: String?,
        timing: String?,
        duration: String?,
        value: String,
    ) {
        val fact = facts.getFact(factId) ?: return
        facts.updateFields(
            id = factId,
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            dose = dose?.trim()?.takeIf { it.isNotEmpty() },
            frequency = frequency?.trim()?.takeIf { it.isNotEmpty() },
            timing = timing?.trim()?.takeIf { it.isNotEmpty() },
            duration = duration?.trim()?.takeIf { it.isNotEmpty() },
            value = value.trim(),
        )
        val updated = facts.getFact(factId) ?: return
        facts.setStatus(factId, FactStatus.CONFIRMED)
        medications.removeForFact(factId)
        profiles.removeDerived(factId)
        deriveFrom(updated.copy(status = FactStatus.CONFIRMED))
        afterChange(fact.documentId)
    }

    private suspend fun deriveFrom(fact: MedicalFact) {
        when (fact.type) {
            FactType.MEDICATION -> medications.upsertFromFact(fact.id)
            FactType.LAB_RESULT -> profiles.upsertLabFromFact(
                factId = fact.id,
                testName = fact.name ?: fact.value,
                value = fact.value,
                unit = fact.unit,
                referenceRange = fact.referenceRange,
                sourceDocumentId = fact.documentId,
            )
            FactType.CONDITION -> profiles.upsertConditionFromFact(
                factId = fact.id,
                name = fact.name ?: fact.value,
                sourceDocumentId = fact.documentId,
            )
            FactType.ALLERGY -> profiles.upsertAllergyFromFact(
                factId = fact.id,
                name = fact.name ?: fact.value,
                sourceDocumentId = fact.documentId,
            )
            else -> Unit // PATIENT_INFO / instructions etc. stay fact-level only.
        }
    }

    private suspend fun afterChange(documentId: String) {
        val pending = facts.pendingCount(documentId)
        val confirmedAny = facts.confirmedFacts().any { it.documentId == documentId }
        val document = documents.getDocument(documentId) ?: return
        if (pending == 0 && confirmedAny &&
            document.status == ProcessingStatus.REVIEW_REQUIRED
        ) {
            documents.setStatus(documentId, ProcessingStatus.CONFIRMED)
        }
        // Keep reminder tasks in sync with medication changes.
        generateCarePlan()
    }
}
