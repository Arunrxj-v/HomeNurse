package com.homenurse.safety

import com.homenurse.domain.model.AiResponse
import com.homenurse.domain.model.SafetyLevel

/** Verdict produced by [SafetyEngine] for user input or model output. */
sealed interface SafetyVerdict {
    data object Allow : SafetyVerdict

    /** Life-threatening situation detected — surface emergency guidance, never call the model. */
    data class Emergency(val ruleId: String, val message: String) : SafetyVerdict

    /** Urgent (not immediately life-threatening) — advise urgent clinical contact, no model call. */
    data class Urgent(val ruleId: String, val message: String) : SafetyVerdict

    /** Request/output that must be refused (dose changes, diagnosis, stop medication). */
    data class Blocked(val kind: BlockKind, val message: String) : SafetyVerdict

    /** Allowed, but flagged (e.g. side effects) — model may answer with extra caution. */
    data class Caution(val ruleId: String) : SafetyVerdict
}

enum class BlockKind { DOSE_CHANGE, STOP_MEDICATION, DIAGNOSIS, MEDICAL_CERTAINTY }

private data class SafetyRule(val id: String, val pattern: Regex)
private data class BlockedRule(val id: String, val kind: BlockKind, val pattern: Regex)

/**
 * All user-facing safety wording in one place so a reviewer or clinician can
 * adjust it without touching logic.
 */
data class SafetyMessages(
    val emergencyResponse: String,
    val urgentResponse: String,
    val refuseDoseChange: String,
    val refuseStopMedication: String,
    val refuseDiagnosis: String,
    val refuseCertainty: String,
) {
    companion object {
        val DEFAULT = SafetyMessages(
            emergencyResponse = "This may be a medical emergency. Call your local emergency " +
                "number now or go to the nearest emergency department immediately. " +
                "Do not wait for an app response.",
            urgentResponse = "This may need prompt medical attention. Please contact your " +
                "doctor or clinic today, or go to an emergency department if it gets worse.",
            refuseDoseChange = "I can't suggest changing your dose. Dose decisions must be " +
                "made by your doctor or pharmacist who knows your full medical history. " +
                "Please contact them.",
            refuseStopMedication = "I can't advise stopping any medicine. Stopping treatment " +
                "on your own can be harmful. Please talk to your doctor or pharmacist first.",
            refuseDiagnosis = "I can't diagnose conditions. I can explain information from " +
                "your documents and confirmed records, but only a qualified clinician can " +
                "examine you and give a diagnosis.",
            refuseCertainty = "I can't confirm a diagnosis with certainty. Please share this " +
                "with your doctor, who can examine you and interpret it properly.",
        )
    }
}

/**
 * Deterministic, rule-based safety layer.
 *
 * Runs BEFORE any model invocation (dangerous requests are refused without
 * spending a single inference) and AFTER the model answers (unsafe output is
 * blocked before reaching the UI). Rules are regex/keyword based — no AI is
 * involved — so behaviour is unit-testable and auditable.
 */
class SafetyEngine(
    private val messages: SafetyMessages = SafetyMessages.DEFAULT,
) {

    fun gateUserInput(input: String): SafetyVerdict {
        val text = input.lowercase()

        emergencyRules.firstOrNull { it.pattern.containsMatchIn(text) }?.let { rule ->
            return SafetyVerdict.Emergency(rule.id, messages.emergencyResponse)
        }
        blockedRequestRules.firstOrNull { it.pattern.containsMatchIn(text) }?.let { rule ->
            return SafetyVerdict.Blocked(rule.kind, refusalFor(rule.kind))
        }
        urgentRules.firstOrNull { it.pattern.containsMatchIn(text) }?.let { rule ->
            return SafetyVerdict.Urgent(rule.id, messages.urgentResponse)
        }
        if (cautionRules.any { it.pattern.containsMatchIn(text) }) {
            return SafetyVerdict.Caution("caution_flag")
        }
        return SafetyVerdict.Allow
    }

    /** Inspects model output before it reaches the user. */
    fun gateAiOutput(response: AiResponse): SafetyVerdict {
        if (response.safetyLevel == SafetyLevel.EMERGENCY) {
            return SafetyVerdict.Emergency(
                "model_flagged_emergency",
                messages.emergencyResponse,
            )
        }
        val combined = (
            listOf(response.summary, response.explanation) +
                response.warnings +
                response.suggestedNextSteps
            ).joinToString("\n").lowercase()

        outputBlockRules.firstOrNull { it.pattern.containsMatchIn(combined) }?.let { rule ->
            return SafetyVerdict.Blocked(rule.kind, refusalFor(rule.kind))
        }
        return SafetyVerdict.Allow
    }

    fun refusalFor(kind: BlockKind): String = when (kind) {
        BlockKind.DOSE_CHANGE -> messages.refuseDoseChange
        BlockKind.STOP_MEDICATION -> messages.refuseStopMedication
        BlockKind.DIAGNOSIS -> messages.refuseDiagnosis
        BlockKind.MEDICAL_CERTAINTY -> messages.refuseCertainty
    }

    companion object {
        private val emergencyRules = listOf(
            SafetyRule("chest_pain", Regex("""chest (?:pain|pressure|tightness)|pain in (?:your )?chest|tightness in (?:your )?chest""")),
            SafetyRule("breathing", Regex("""(?:can'?t|cannot|can not|hard to|struggl\w+ to) breathe|shortness of breath|not breathing|gasping for air""")),
            SafetyRule("consciousness", Regex("""(?:lost|passed out|blacked out) consciousness|passed out|blacked out|unconscious|not responding|not waking|seizure|convulsion""")),
            SafetyRule("stroke_signs", Regex("""face droop|slurred speech|numbness on one side|sudden (?:weakness|numbness)|cannot (?:lift|move) (?:your |one )?arm""")),
            SafetyRule("bleeding", Regex("""(?:heavy|uncontrolled|spurting|won'?t stop) bleed|bleeding (?:heavily|that won'?t stop)|coughing up blood|vomiting blood|blood in (?:your )? vomit""")),
            SafetyRule("anaphylaxis", Regex("""anaphyla\w*|throat (?:closing|swelling)|swollen (?:tongue|lips|throat)|can'?t swallow""")),
            SafetyRule("overdose", Regex("""(?:took|swallowed|ingested) (?:too many|an overdose)|overdose of|poisoning|swallowed poison""")),
            SafetyRule("self_harm", Regex("""(?:want|going|plan\w*) to (?:kill|hurt) (?:myself|me)|suicid\w*|end my life|self[- ]harm""")),
            SafetyRule("choking", Regex("""choking|object stuck in (?:the )?throat""")),
            SafetyRule("severe_burn", Regex("""(?:severe|deep|major|third[- ]degree) burn""")),
        )

        private val blockedRequestRules = listOf(
            BlockedRule("increase_dose", BlockKind.DOSE_CHANGE, Regex("""(?:increase|raise|double|triple|higher|more)\s+(?:the\s+)?(?:dose|dosage|amount)|(?:can|should|may)\s+i\s+(?:take|increase|raise|double)\s+(?:more|a higher|an extra)|(?:extra|additional|another)\s+(?:dose|tablet|pill)""")),
            BlockedRule("decrease_dose", BlockKind.DOSE_CHANGE, Regex("""(?:reduce|lower|decrease|cut|less|smaller)\s+(?:the\s+)?(?:dose|dosage|amount)|(?:can|should|may)\s+i\s+(?:take|use)\s+less\b""")),
            BlockedRule("stop_medication", BlockKind.STOP_MEDICATION, Regex("""(?:can|should|may|do)\s+i\s+(?:stop|skip|discontinue|quit|pause)\s+(?:the\s+)?(?:medicine|medication|drug|tablet|pills?|dose|treatment)|stop taking (?:the )?(?:medicine|medication|tablet|pills?)|(?:should|can) i stop taking""")),
            BlockedRule("diagnosis_request", BlockKind.DIAGNOSIS, Regex("""(?:what|which)\s+(?:disease|illness|condition|disorder)\s+(?:do i have|is this)|diagnose me|(?:do|i've|i have|have i) got (?:cancer|diabetes|hiv|aids|leuk\w*|tumou?r|lymphoma|tuberculosis)""")),
        )

        private val outputBlockRules = listOf(
            BlockedRule("model_diagnosis", BlockKind.DIAGNOSIS, Regex("""you have (?:a|an|the) (?:disease|condition|infection|cancer|tumou?r)|i am certain you have|i'm certain you have|you definitely have|this confirms that you have|diagnosis:""")),
            BlockedRule("model_dose_change", BlockKind.DOSE_CHANGE, Regex("""(?:increase|double|triple|raise|reduce|lower|cut) (?:your|the) (?:dose|dosage|dosing)|stop taking (?:your|the) (?:medicine|medication|tablet|drug)|take (?:an? )?(?:extra|additional|double) (?:dose|tablet|pill)""")),
            BlockedRule("model_certainty", BlockKind.MEDICAL_CERTAINTY, Regex("""i (?:can|do) confirm (?:that )?you have|it is certain that you|you certainly have|you definitely have""")),
        )

        private val urgentRules = listOf(
            SafetyRule("high_fever", Regex("""(?:high|very high|high-grade|persistent|continuous) fever|fever (?:above|over|of) (?:10[0-9]|[34][0-9])(?:\.\d+)?|fever (?:for|since) (?:more than|over) \d+ days""")),
            SafetyRule("severe_pain", Regex("""severe (?:pain|headache|abdominal|stomach)|worst pain|excruciating""")),
            SafetyRule("persistent_vomiting", Regex("""cannot keep (?:down|food|water)|can't keep (?:down|food|water)|vomiting (?:blood|black)|persistent(?:ly)? vomit""")),
            SafetyRule("pregnancy_concern", Regex("""pregnan\w+ (?:and|with)|bleeding during pregnancy|reduced fetal movement""")),
            SafetyRule("infant_concern", Regex("""(?:newborn|infant|baby) (?:under|less than) \d+ months|baby (?:is )?(?:not feeding|listless|floppy)|dehydrated baby""")),
        )

        private val cautionRules = listOf(
            SafetyRule("side_effect", Regex("""side effect|allergic reaction|rash after taking|swelling after taking""")),
            SafetyRule("missed_dose", Regex("""missed (?:a |my )?dose|forgot (?:to take|my) (?:dose|medicine|tablet|pills?)""")),
            SafetyRule("medicine_interaction", Regex("""(?:medicine|tablet|pill|medication|drug) interact|(?:is it safe|can i).*(?:with alcohol|while drinking)""")),
        )
    }
}
