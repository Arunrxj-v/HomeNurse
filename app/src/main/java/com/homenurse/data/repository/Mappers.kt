package com.homenurse.data.repository

import com.homenurse.data.local.database.entity.AiAnalysisEntity
import com.homenurse.data.local.database.entity.AllergyEntity
import com.homenurse.data.local.database.entity.CarePlanEntity
import com.homenurse.data.local.database.entity.CareTaskEntity
import com.homenurse.data.local.database.entity.ConditionEntity
import com.homenurse.data.local.database.entity.ConversationEntity
import com.homenurse.data.local.database.entity.ConversationMessageEntity
import com.homenurse.data.local.database.entity.EvidenceEntity
import com.homenurse.data.local.database.entity.LabResultEntity
import com.homenurse.data.local.database.entity.MedicalDocumentEntity
import com.homenurse.data.local.database.entity.MedicalFactEntity
import com.homenurse.data.local.database.entity.MedicationEntity
import com.homenurse.data.local.database.entity.PatientEntity
import com.homenurse.domain.model.AiAnalysis
import com.homenurse.domain.model.Allergy
import com.homenurse.domain.model.CarePlan
import com.homenurse.domain.model.CareTask
import com.homenurse.domain.model.Condition
import com.homenurse.domain.model.Conversation
import com.homenurse.domain.model.ConversationMessage
import com.homenurse.domain.model.Evidence
import com.homenurse.domain.model.LabResult
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.Medication
import com.homenurse.domain.model.Patient

internal fun PatientEntity.toDomain() = Patient(
    id = id,
    displayName = displayName,
    dateOfBirth = dateOfBirth,
    sex = sex,
    emergencyRegion = emergencyRegion,
)

internal fun ConditionEntity.toDomain() = Condition(id, factId, name, sourceDocumentId)

internal fun AllergyEntity.toDomain() = Allergy(id, factId, name, sourceDocumentId)

internal fun LabResultEntity.toDomain() = LabResult(
    id, factId, testName, value, unit, referenceRange, sourceDocumentId,
)

internal fun MedicalDocumentEntity.toDomain() = MedicalDocument(
    id = id,
    title = title,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    pageCount = pageCount,
    status = status,
    extractedText = extractedText,
    failureReason = failureReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
    normalizedText = normalizedText,
)

internal fun MedicalFactEntity.toDomain() = MedicalFact(
    id = id,
    documentId = documentId,
    type = type,
    status = status,
    name = name,
    dose = dose,
    frequency = frequency,
    timing = timing,
    duration = duration,
    value = value,
    sourceText = sourceText,
    confidence = confidence,
    route = route,
    instructions = instructions,
    unit = unit,
    referenceRange = referenceRange,
)

internal fun MedicationEntity.toDomain() = Medication(
    id = id,
    factId = factId,
    documentId = documentId,
    name = name,
    dose = dose,
    frequency = frequency,
    timing = timing,
    duration = duration,
    confirmedByUser = confirmedByUser,
)

internal fun CareTaskEntity.toDomain() = CareTask(
    id = id,
    planId = planId,
    title = title,
    kind = kind,
    source = source,
    timeOfDayMin = timeOfDayMin,
    dueDate = dueDate,
    documentId = documentId,
    factId = factId,
    completed = completed,
)

internal fun CarePlanEntity.withTasks(tasks: List<CareTask>) = CarePlan(id, title, tasks)

internal fun ConversationEntity.toDomain() = Conversation(id, title, updatedAt)

internal fun ConversationMessageEntity.toDomain() = ConversationMessage(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    safetyLevel = safetyLevel,
    createdAt = createdAt,
)

internal fun AiAnalysisEntity.toDomainWith(evidence: List<EvidenceEntity>) = AiAnalysis(
    id = id,
    kind = kind,
    question = question,
    documentId = documentId,
    summary = summary,
    explanation = explanation,
    warnings = warnings,
    suggestedNextSteps = suggestedNextSteps,
    uncertainty = uncertainty,
    requiresClinician = requiresClinician,
    safetyLevel = safetyLevel,
    createdAt = createdAt,
    evidence = evidence.map {
        Evidence(
            id = it.id,
            factId = it.factId,
            documentId = it.documentId,
            label = it.label,
            sourceText = it.sourceText,
        )
    },
)
