package com.homenurse.domain.usecase

import com.homenurse.data.local.database.HomeNurseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Builds a complete, human-readable JSON export of everything HomeNurse knows
 * about the patient — produced entirely on-device from the local database.
 *
 * The file contains ONLY data the user entered or confirmed/reviewed from
 * their own documents; nothing is derived or invented. The user shares it
 * explicitly (e.g. writes it to a location they choose via the share sheet).
 */
class ExportMedicalDataUseCase(
    private val database: HomeNurseDatabase,
) {

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val patientDao = database.patientDao()
        val documentDao = database.documentDao()
        val factDao = database.factDao()
        val medicationDao = database.medicationDao()
        val carePlanDao = database.carePlanDao()
        val conversationDao = database.conversationDao()
        val analysisDao = database.analysisDao()

        val analyses = analysisDao.getAllAnalyses()
        val evidenceByAnalysis = analysisDao.getAllEvidence().groupBy { it.analysisId }

        val bundle = ExportBundle(
            schemaVersion = 1,
            exportedAt = System.currentTimeMillis(),
            patient = patientDao.getPatient("local")?.let {
                ExportPatient(it.displayName, it.dateOfBirth, it.sex, it.emergencyRegion)
            },
            conditions = patientDao.getAllConditions().map {
                ExportCondition(name = it.name, sourceDocumentId = it.sourceDocumentId)
            },
            allergies = patientDao.getAllAllergies().map {
                ExportAllergy(name = it.name, sourceDocumentId = it.sourceDocumentId)
            },
            labResults = patientDao.getAllLabResults().map {
                ExportLabResult(
                    testName = it.testName,
                    value = it.value,
                    unit = it.unit,
                    referenceRange = it.referenceRange,
                    sourceDocumentId = it.sourceDocumentId,
                )
            },
            documents = documentDao.getAllDocuments().map { doc ->
                ExportDocument(
                    id = doc.id,
                    title = doc.title,
                    mimeType = doc.mimeType,
                    pageCount = doc.pageCount,
                    status = doc.status.name,
                    createdAt = doc.createdAt,
                    extractedText = doc.extractedText,
                )
            },
            facts = factDao.getAllFacts().map { fact ->
                ExportFact(
                    id = fact.id,
                    documentId = fact.documentId,
                    type = fact.type.name,
                    status = fact.status.name,
                    name = fact.name,
                    dose = fact.dose,
                    frequency = fact.frequency,
                    timing = fact.timing,
                    duration = fact.duration,
                    value = fact.value,
                    sourceText = fact.sourceText,
                    confidence = fact.confidence,
                )
            },
            medications = medicationDao.getAll().map { med ->
                ExportMedication(
                    name = med.name,
                    dose = med.dose,
                    frequency = med.frequency,
                    timing = med.timing,
                    duration = med.duration,
                    confirmedByUser = med.confirmedByUser,
                    factId = med.factId,
                    sourceDocumentId = med.documentId,
                )
            },
            careTasks = carePlanDao.getAllTasks().map { task ->
                ExportCareTask(
                    title = task.title,
                    kind = task.kind.name,
                    source = task.source.name,
                    timeOfDayMin = task.timeOfDayMin,
                    dueDate = task.dueDate,
                    completed = task.completed,
                    documentId = task.documentId,
                    factId = task.factId,
                )
            },
            conversations = conversationDao.getAllConversations().map { conv ->
                ExportConversation(
                    title = conv.title,
                    messages = conversationDao.getAllMessages()
                        .filter { it.conversationId == conv.id }
                        .map { msg ->
                            ExportMessage(
                                role = msg.role.name,
                                content = msg.content,
                                safetyLevel = msg.safetyLevel?.name,
                                createdAt = msg.createdAt,
                            )
                        },
                )
            },
            analyses = analyses.map { analysis ->
                ExportAnalysis(
                    kind = analysis.kind,
                    documentId = analysis.documentId,
                    summary = analysis.summary,
                    explanation = analysis.explanation,
                    warnings = analysis.warnings,
                    suggestedNextSteps = analysis.suggestedNextSteps,
                    uncertainty = analysis.uncertainty,
                    requiresClinician = analysis.requiresClinician,
                    safetyLevel = analysis.safetyLevel.name,
                    createdAt = analysis.createdAt,
                    evidence = evidenceByAnalysis[analysis.id].orEmpty().map { ev ->
                        ExportEvidence(
                            factId = ev.factId,
                            documentId = ev.documentId,
                            label = ev.label,
                            sourceText = ev.sourceText,
                        )
                    },
                )
            },
        )

        prettyJson.encodeToString(ExportBundle.serializer(), bundle)
    }

    companion object {
        private val prettyJson = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

// --- export DTOs (explicit schema; independent from Room/domain internals) ---

@Serializable
data class ExportBundle(
    val schemaVersion: Int,
    val exportedAt: Long,
    val patient: ExportPatient?,
    val conditions: List<ExportCondition>,
    val allergies: List<ExportAllergy>,
    val labResults: List<ExportLabResult>,
    val documents: List<ExportDocument>,
    val facts: List<ExportFact>,
    val medications: List<ExportMedication>,
    val careTasks: List<ExportCareTask>,
    val conversations: List<ExportConversation>,
    val analyses: List<ExportAnalysis>,
)

@Serializable
data class ExportPatient(
    val displayName: String,
    val dateOfBirth: String?,
    val sex: String?,
    val emergencyRegion: String?,
)

@Serializable
data class ExportCondition(val name: String, val sourceDocumentId: String?)

@Serializable
data class ExportAllergy(val name: String, val sourceDocumentId: String?)

@Serializable
data class ExportLabResult(
    val testName: String,
    val value: String,
    val unit: String?,
    val referenceRange: String?,
    val sourceDocumentId: String?,
)

@Serializable
data class ExportDocument(
    val id: String,
    val title: String,
    val mimeType: String,
    val pageCount: Int,
    val status: String,
    val createdAt: Long,
    val extractedText: String?,
)

@Serializable
data class ExportFact(
    val id: String,
    val documentId: String,
    val type: String,
    val status: String,
    val name: String?,
    val dose: String?,
    val frequency: String?,
    val timing: String?,
    val duration: String?,
    val value: String,
    val sourceText: String,
    val confidence: Double,
)

@Serializable
data class ExportMedication(
    val name: String,
    val dose: String?,
    val frequency: String?,
    val timing: String?,
    val duration: String?,
    val confirmedByUser: Boolean,
    val factId: String,
    val sourceDocumentId: String,
)

@Serializable
data class ExportCareTask(
    val title: String,
    val kind: String,
    val source: String,
    val timeOfDayMin: Int?,
    val dueDate: String?,
    val completed: Boolean,
    val documentId: String?,
    val factId: String?,
)

@Serializable
data class ExportConversation(
    val title: String,
    val messages: List<ExportMessage>,
)

@Serializable
data class ExportMessage(
    val role: String,
    val content: String,
    val safetyLevel: String?,
    val createdAt: Long,
)

@Serializable
data class ExportAnalysis(
    val kind: String,
    val documentId: String?,
    val summary: String,
    val explanation: String,
    val warnings: List<String>,
    val suggestedNextSteps: List<String>,
    val uncertainty: String?,
    val requiresClinician: Boolean,
    val safetyLevel: String,
    val createdAt: Long,
    val evidence: List<ExportEvidence>,
)

@Serializable
data class ExportEvidence(
    val factId: String?,
    val documentId: String?,
    val label: String,
    val sourceText: String,
)
