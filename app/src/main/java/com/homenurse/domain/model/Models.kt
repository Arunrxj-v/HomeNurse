package com.homenurse.domain.model

/**
 * Domain models for HomeNurse.
 *
 * These are the application's business objects — UI and use cases only ever
 * see these, never Room entities. Every model that represents extracted
 * medical information carries provenance (source document / source text) and
 * an explicit confirmation state: AI/OCR extraction is never trusted on its
 * own.
 */

enum class ProcessingStatus { CAPTURED, PROCESSING, REVIEW_REQUIRED, CONFIRMED, ANALYSIS_READY, FAILED }

enum class FactType {
    PATIENT_INFO, MEDICATION, LAB_RESULT, CONDITION, ALLERGY,
    INSTRUCTION, WARNING_SIGNS, FOLLOW_UP, PROCEDURE
}

enum class FactStatus { PENDING, CONFIRMED, REJECTED }

enum class ConversationRole { USER, MODEL, SAFETY }

enum class TaskKind { MEDICATION, HYDRATION, FOLLOW_UP, APPOINTMENT, RECOVERY, WARNING_SIGN, USER }

enum class TaskSource { MEDICATION, CONFIRMED_FACT, USER }

enum class SafetyLevel { NORMAL, CAUTION, URGENT, EMERGENCY }

data class Patient(
    val id: String,
    val displayName: String,
    val dateOfBirth: String?,
    val sex: String?,
    val emergencyRegion: String?,
)

data class Condition(
    val id: String,
    val factId: String?,
    val name: String,
    val sourceDocumentId: String?,
)

data class Allergy(
    val id: String,
    val factId: String?,
    val name: String,
    val sourceDocumentId: String?,
)

data class LabResult(
    val id: String,
    val factId: String?,
    val testName: String,
    val value: String,
    val unit: String?,
    val referenceRange: String?,
    val sourceDocumentId: String?,
)

data class MedicalDocument(
    val id: String,
    val title: String,
    val mimeType: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val status: ProcessingStatus,
    /** Raw OCR text of the document, verbatim (inside the encrypted database). */
    val extractedText: String?,
    /** Coarse failure category — never medical content. */
    val failureReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Conservatively normalized OCR text (see OcrTextNormalizer); raw text is kept as-is. */
    val normalizedText: String? = null,
)

data class MedicalFact(
    val id: String,
    val documentId: String,
    val type: FactType,
    val status: FactStatus,
    val name: String?,
    val dose: String?,
    val frequency: String?,
    val timing: String?,
    val duration: String?,
    val value: String,
    val sourceText: String,
    val confidence: Double,
    /** Route of administration, verbatim from the document (medications). */
    val route: String? = null,
    /** Additional instruction fragments, verbatim (medications). */
    val instructions: String? = null,
    /** Lab unit, verbatim (lab results). */
    val unit: String? = null,
    /** Lab reference range, verbatim (lab results). */
    val referenceRange: String? = null,
) {
    /** Only confirmed facts form the trusted medical context for the AI. */
    val isTrusted: Boolean get() = status == FactStatus.CONFIRMED

    /**
     * Extraction was shaky (low OCR confidence, corrected characters or a
     * garbled-looking name) — the review screen highlights these. It never
     * changes the trust rule: PENDING until the user confirms.
     */
    val needsVerification: Boolean
        get() = confidence < NEEDS_VERIFICATION_BELOW
}

/** Confidence at or below which a pending fact is highlighted for closer review. */
private const val NEEDS_VERIFICATION_BELOW = 0.70

data class Medication(
    val id: String,
    val factId: String,
    val documentId: String,
    val name: String,
    val dose: String?,
    val frequency: String?,
    val timing: String?,
    val duration: String?,
    val confirmedByUser: Boolean,
)

data class CareTask(
    val id: String,
    val planId: String,
    val title: String,
    val kind: TaskKind,
    val source: TaskSource,
    val timeOfDayMin: Int?,
    val dueDate: String?,
    val documentId: String?,
    val factId: String?,
    val completed: Boolean,
)

data class CarePlan(
    val id: String,
    val title: String,
    val tasks: List<CareTask>,
)

data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
)

data class ConversationMessage(
    val id: String,
    val conversationId: String,
    val role: ConversationRole,
    val content: String,
    val safetyLevel: SafetyLevel?,
    val createdAt: Long,
)

data class Evidence(
    val id: String,
    val factId: String?,
    val documentId: String?,
    val label: String,
    val sourceText: String,
)

data class AiAnalysis(
    val id: String,
    val kind: String,
    val question: String?,
    val documentId: String?,
    val summary: String,
    val explanation: String,
    val warnings: List<String>,
    val suggestedNextSteps: List<String>,
    val uncertainty: String?,
    val requiresClinician: Boolean,
    val safetyLevel: SafetyLevel,
    val createdAt: Long,
    val evidence: List<Evidence>,
)
