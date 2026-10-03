package com.homenurse.domain.usecase

import com.homenurse.domain.model.AiAnalysis
import com.homenurse.domain.model.AiRequest
import com.homenurse.domain.model.AiResponse
import com.homenurse.domain.model.AiTask
import com.homenurse.domain.model.ConversationMessage
import com.homenurse.domain.model.ConversationRole
import com.homenurse.domain.model.Evidence
import com.homenurse.domain.model.SafetyLevel
import com.homenurse.ai.AiProvider
import com.homenurse.ai.AiProviderException
import com.homenurse.domain.repository.ConversationRepository
import com.homenurse.domain.util.Clock
import com.homenurse.domain.util.IdGenerator
import com.homenurse.safety.SafetyEngine
import com.homenurse.safety.SafetyVerdict

enum class AiErrorKind { MODEL_NOT_READY, MALFORMED_RESPONSE, INFERENCE }

/**
 * "Ask HomeNurse" pipeline:
 *
 *  1. Safety gate on the user's question — emergencies / dose-change /
 *     diagnosis requests short-circuit to a safety message WITHOUT calling
 *     the model.
 *  2. Build confirmed-only context.
 *  3. Local Gemma generates a structured [AiResponse].
 *  4. Safety gate on the model output — unsafe replies are replaced with the
 *     refusal message before reaching the UI.
 *  5. Persist user message + reply/analysis in the encrypted local database.
 */
class AskHomeNurseUseCase(
    private val safetyEngine: SafetyEngine,
    private val conversations: ConversationRepository,
    private val buildContext: BuildAiContextUseCase,
    private val aiProvider: AiProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
) {

    sealed interface Outcome {
        /** Normal answer: safety message or model message (with analysis row). */
        data class Reply(val message: ConversationMessage, val analysis: AiAnalysis?) : Outcome

        /** Safety intervention (emergency/urgent/blocked) — already persisted. */
        data class Safety(
            val message: ConversationMessage,
            val verdict: SafetyVerdict,
        ) : Outcome

        /** Local model problem — UI shows a friendly, honest error. */
        data class AiError(val kind: AiErrorKind) : Outcome
    }

    suspend operator fun invoke(question: String): Outcome {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return Outcome.AiError(AiErrorKind.INFERENCE)

        val conversation = conversations.getOrCreateActive()
        conversations.addMessage(conversation.id, ConversationRole.USER, trimmed)

        when (val verdict = safetyEngine.gateUserInput(trimmed)) {
            is SafetyVerdict.Emergency -> return safetyReply(conversation.id, verdict.message, SafetyLevel.EMERGENCY, verdict)
            is SafetyVerdict.Urgent -> return safetyReply(conversation.id, verdict.message, SafetyLevel.URGENT, verdict)
            is SafetyVerdict.Blocked -> return safetyReply(conversation.id, verdict.message, SafetyLevel.CAUTION, verdict)
            is SafetyVerdict.Caution,
            SafetyVerdict.Allow,
            -> Unit
        }

        val context = buildContext()
        val response = try {
            aiProvider.generate(
                AiRequest(
                    task = AiTask.ANSWER_QUESTION,
                    question = trimmed,
                    document = null,
                    documentTextExcerpt = null,
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

        when (val outVerdict = safetyEngine.gateAiOutput(response)) {
            is SafetyVerdict.Emergency ->
                return safetyReply(conversation.id, outVerdict.message, SafetyLevel.EMERGENCY, outVerdict)
            is SafetyVerdict.Blocked ->
                return safetyReply(conversation.id, outVerdict.message, SafetyLevel.CAUTION, outVerdict)
            else -> Unit
        }

        val analysis = buildAnalysis(response, context)
        conversations.saveAnalysis(analysis)
        val composed = compose(response)
        val message = conversations.addMessage(
            conversationId = conversation.id,
            role = ConversationRole.MODEL,
            content = composed,
            safetyLevel = response.safetyLevel.takeIf { it != SafetyLevel.NORMAL },
        )
        return Outcome.Reply(message, analysis)
    }

    private suspend fun safetyReply(
        conversationId: String,
        text: String,
        level: SafetyLevel,
        verdict: SafetyVerdict,
    ): Outcome {
        val message = conversations.addMessage(
            conversationId = conversationId,
            role = ConversationRole.SAFETY,
            content = text,
            safetyLevel = level,
        )
        return Outcome.Safety(message, verdict)
    }

    private fun compose(response: AiResponse): String = buildString {
        append(response.summary.trim())
        val explanation = response.explanation.trim()
        if (explanation.isNotEmpty() && explanation != response.summary.trim()) {
            append("\n\n")
            append(explanation)
        }
        if (response.warnings.isNotEmpty()) {
            append("\n\n")
            append(response.warnings.joinToString("\n") { "! $it" })
        }
        if (response.uncertainty.isNullOrBlank().not()) {
            append("\n\n")
            append("Uncertainty: ")
            append(response.uncertainty)
        }
        if (response.suggestedNextSteps.isNotEmpty()) {
            append("\n\n")
            append(response.suggestedNextSteps.joinToString("\n") { "• $it" })
        }
        if (response.requiresClinician) {
            append("\n\n")
            append("Please discuss this with your doctor or clinic.")
        }
    }

    private fun buildAnalysis(
        response: AiResponse,
        context: com.homenurse.domain.model.AiContext,
    ): AiAnalysis {
        val evidence = response.evidence.mapNotNull { ref ->
            val fact = ref.factId?.let { id -> context.confirmedFacts.firstOrNull { it.id == id } }
            when {
                fact != null -> Evidence(
                    id = ids.newId(),
                    factId = fact.id,
                    documentId = fact.documentId,
                    label = fact.name ?: fact.value,
                    sourceText = fact.sourceText,
                )
                ref.documentId != null && context.documentTitles.containsKey(ref.documentId) ->
                    Evidence(
                        id = ids.newId(),
                        factId = null,
                        documentId = ref.documentId,
                        label = context.documentTitles[ref.documentId].orEmpty(),
                        sourceText = "Document title reference",
                    )
                else -> null // Drop evidence that does not point at confirmed data.
            }
        }
        return AiAnalysis(
            id = ids.newId(),
            kind = "answer",
            question = null,
            documentId = null,
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
    }
}
