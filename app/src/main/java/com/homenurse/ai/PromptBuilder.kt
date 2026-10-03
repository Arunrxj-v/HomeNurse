package com.homenurse.ai

import com.homenurse.domain.model.AiContext
import com.homenurse.domain.model.AiRequest
import com.homenurse.domain.model.AiTask
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.MedicalDocument

/**
 * Builds prompts for the local Gemma model.
 *
 * Design rules (medical safety):
 *  * The system instruction pins behaviour: answer ONLY from the provided
 *    context, never invent facts, never diagnose, never suggest dose changes,
 *    always emit the exact JSON schema, always mark uncertainty.
 *  * Only CONFIRMED facts / confirmed medications / care tasks are included —
 *    pending or rejected extraction candidates are never sent.
 *  * Context is bounded (line budget) so prompts stay within the model's
 *    context window on low-end devices.
 */
object PromptBuilder {

    private const val MAX_CONTEXT_LINES = 60
    private const val MAX_DOC_EXCERPT_CHARS = 6000

    const val RESPONSE_SCHEMA =
        """{"summary":"...","explanation":"...","evidence":[{"factId":"...","documentId":"...","label":"..."}],"warnings":["..."],"uncertainty":"...","requiresClinician":false,"safetyLevel":"normal|caution|urgent|emergency","suggestedNextSteps":["..."]}"""

    val SYSTEM_INSTRUCTION = buildString {
        appendLine("You are HomeNurse, a private on-device health information assistant.")
        appendLine("Rules you must always follow:")
        appendLine("1. Answer ONLY from the CONFIRMED context provided in the user message.")
        appendLine("2. If the context does not contain the answer, say you do not have that information in the user's confirmed records.")
        appendLine("3. Never invent medicines, doses, test values, dates or diagnoses.")
        appendLine("4. Never diagnose. Never claim certainty about a medical condition.")
        appendLine("5. Never advise starting, stopping, increasing or decreasing any medicine or dose. Direct the user to their doctor or pharmacist for any dose change.")
        appendLine("6. If symptoms described sound life-threatening, set safetyLevel to emergency and tell the user to call emergency services.")
        appendLine("7. Reply with ONE JSON object only — no markdown, no extra text — exactly matching this schema:")
        appendLine(RESPONSE_SCHEMA)
        appendLine("8. Write summary and explanation in simple, calm language an older adult can understand.")
    }

    fun buildPrompt(request: AiRequest): String = buildString {
        appendLine("[TASK] " + taskLabel(request.task))
        appendLine()
        appendLine("[CONFIRMED CONTEXT]")
        appendLine(contextBlock(request.context))
        if (request.document != null) {
            appendLine()
            appendLine("[DOCUMENT] ${request.document.title}")
            val excerpt = request.documentTextExcerpt.orEmpty()
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(MAX_DOC_EXCERPT_CHARS)
            if (excerpt.isNotEmpty()) {
                appendLine("[DOCUMENT TEXT EXCERPT] $excerpt")
            } else {
                appendLine("[DOCUMENT TEXT EXCERPT] (no extracted text available)")
            }
            appendLine("Explain only what this document actually says. Point evidence at documentId=${request.document.id}.")
        }
        request.question?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("[USER QUESTION]")
            appendLine(it.trim())
        }
        appendLine()
        appendLine("Reply with the JSON object only.")
    }

    private fun taskLabel(task: AiTask): String = when (task) {
        AiTask.EXPLAIN_DOCUMENT ->
            "Explain this medical document in plain language, using the confirmed context where relevant."
        AiTask.ANSWER_QUESTION ->
            "Answer the user's question using the confirmed context. Keep it short and practical."
    }

    private fun contextBlock(context: AiContext): String {
        val lines = mutableListOf<String>()

        context.documentTitles.forEach { (id, title) ->
            lines += "- Document: $title (id=$id)"
        }
        if (context.confirmedFacts.isEmpty() && context.medications.isEmpty()) {
            lines += "- (no confirmed medical facts yet — user has not confirmed any extractions)"
        }
        context.medications.forEach { med ->
            buildString {
                append("- Medicine: ${med.name}")
                med.dose?.let { append(" | dose: $it") }
                med.frequency?.let { append(" | frequency: $it") }
                med.timing?.let { append(" | timing: $it") }
                med.duration?.let { append(" | duration: $it") }
                append(" | confirmedByUser=true")
            }.let { lines += it }
        }
        context.confirmedFacts
            .filter { it.type != FactType.MEDICATION }
            .forEach { fact ->
                buildString {
                    append("- ${fact.type}: ${fact.value}")
                    append(" (id=${fact.id}, confirmedByUser=true)")
                }.let { lines += it }
            }
        context.careTasks.filter { !it.completed }.forEach { task ->
            lines += "- Care task: ${task.title}"
        }

        return lines.take(MAX_CONTEXT_LINES).joinToString("\n") +
            if (lines.size > MAX_CONTEXT_LINES) "\n- (older context truncated)" else ""
    }
}
