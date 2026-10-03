package com.homenurse.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AI layer contracts.
 *
 * The on-device model must answer with this exact JSON structure; the reply
 * is parsed strictly (unknown keys ignored, enums case-insensitive) and never
 * used unless parsing succeeds. `evidence` must point back to confirmed facts
 * or documents that were actually part of the request context.
 */

enum class AiTask {
    @SerialName("explain_document") EXPLAIN_DOCUMENT,
    @SerialName("answer_question") ANSWER_QUESTION,
}

@Serializable
data class AiEvidenceRef(
    val factId: String? = null,
    val documentId: String? = null,
    val label: String = "",
)

@Serializable
data class AiResponse(
    val summary: String,
    val explanation: String,
    val evidence: List<AiEvidenceRef> = emptyList(),
    val warnings: List<String> = emptyList(),
    val uncertainty: String? = null,
    val requiresClinician: Boolean = false,
    val safetyLevel: SafetyLevel = SafetyLevel.NORMAL,
    val suggestedNextSteps: List<String> = emptyList(),
)

/** Minimal slice of confirmed context handed to the model (nothing else). */
data class AiContext(
    val confirmedFacts: List<MedicalFact>,
    val medications: List<Medication>,
    val careTasks: List<CareTask>,
    val documentTitles: Map<String, String>,
)

data class AiRequest(
    val task: AiTask,
    val question: String?,
    val document: MedicalDocument?,
    val documentTextExcerpt: String?,
    val context: AiContext,
)
