package com.homenurse.domain.usecase

import com.homenurse.ai.AiProvider
import com.homenurse.ai.AiProviderException
import com.homenurse.domain.model.AiAnalysis
import com.homenurse.domain.model.AiRequest
import com.homenurse.domain.model.AiTask
import com.homenurse.domain.model.Evidence
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.domain.repository.ConversationRepository
import com.homenurse.domain.repository.DocumentRepository
import com.homenurse.domain.repository.FactRepository
import com.homenurse.domain.util.Clock
import com.homenurse.domain.util.IdGenerator
import com.homenurse.safety.SafetyEngine
import com.homenurse.safety.SafetyVerdict

/**
 * "Explain this document": local Gemma reads the document's own OCR text plus
 * confirmed context and returns a structured plain-language explanation.
 *
 * The result is persisted as an [AiAnalysis] (with evidence rows pointing at
 * confirmed facts) and the document moves to ANALYSIS_READY. Model output is
 * safety-gated before storage; errors are reported honestly as outcomes.
 */
class ExplainDocumentUseCase(
    private val documents: DocumentRepository,
    private val facts: FactRepository,
    private val buildContext: BuildAiContextUseCase,
    private val aiProvider: AiProvider,
    private val safetyEngine: SafetyEngine,
    private val conversations: ConversationRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) {

    sealed interface Outcome {
        data class Success(val analysis: AiAnalysis) : Outcome
        data class AiError(val kind: AiErrorKind) : Outcome
        data class SafetyBlocked(val message: String) : Outcome
        data object NotFound : Outcome
        data object NotConfirmed : Outcome
    }

    suspend operator fun invoke(documentId: String): Outcome {
        val document = documents.getDocument(documentId) ?: return Outcome.NotFound
        val hasConfirmedFacts = facts.confirmedFacts().any { it.documentId == documentId }
        if (!hasConfirmedFacts) {
            // Explanations may only draw on confirmed review results.
            return Outcome.NotConfirmed
        }

        val context = buildContext()
        val response = try {
            aiProvider.generate(
                AiRequest(
                    task = AiTask.EXPLAIN_DOCUMENT,
                    question = null,
                    document = document,
                    // The conservatively normalized text reads cleaner for the
                    // model; raw text remains stored and shown to the user.
                    documentTextExcerpt = document.normalizedText ?: document.extractedText,
                    context = context,
                ),
            )
        } catch (error: AiProviderException.NotReady) {
            return Outcome.AiError(AiErrorKind.MODEL_NOT_READY)
        } catch (error: AiProviderException.MalformedResponse) {
            return Outcome.AiError(AiErrorKind.MALFORMED_RESPONSE)
        } catch (error: AiProviderException) {
            return Outcome.AiError(AiErrorKind.INFERENCE)
        }

        when (val verdict = safetyEngine.gateAiOutput(response)) {
            is SafetyVerdict.Blocked ->
                return Outcome.SafetyBlocked(verdict.message)
            is SafetyVerdict.Emergency ->
                return Outcome.SafetyBlocked(verdict.message)
            else -> Unit
        }

        val confirmedById = facts.confirmedFacts().associateBy { it.id }
        val evidence = response.evidence.mapNotNull { ref ->
            val fact = ref.factId?.let { confirmedById[it] }
            when {
                fact != null -> Evidence(
                    id = ids.newId(),
                    factId = fact.id,
                    documentId = fact.documentId,
                    label = fact.name ?: fact.value,
                    sourceText = fact.sourceText,
                )
                ref.documentId == documentId || ref.documentId == null && ref.label.isNotBlank() ->
                    Evidence(
                        id = ids.newId(),
                        factId = null,
                        documentId = documentId,
                        label = ref.label.ifBlank { document.title },
                        sourceText = document.extractedText?.take(300).orEmpty(),
                    )
                else -> null
            }
        }

        val analysis = AiAnalysis(
            id = ids.newId(),
            kind = "document_explanation",
            question = null,
            documentId = documentId,
            summary = response.summary,
            explanation = response.explanation,
            warnings = response.warnings,
            suggestedNextSteps = response.suggestedNextSteps,
            uncertainty = response.uncertainty,
            requiresClinician = response.requiresClinician,
            safetyLevel = response.safetyLevel,
            createdAt = clock.now(),
            evidence = evidence,
        )
        conversations.saveAnalysis(analysis)
        if (document.status != ProcessingStatus.ANALYSIS_READY) {
            documents.setStatus(documentId, ProcessingStatus.ANALYSIS_READY)
        }
        return Outcome.Success(analysis)
    }
}
