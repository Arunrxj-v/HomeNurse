package com.homenurse.safety

import com.homenurse.domain.model.AiResponse
import com.homenurse.domain.model.SafetyLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic safety-layer tests: emergency short-circuit, dangerous
 * request refusals, urgency triage, caution flags and the output gate.
 * These tests require no network, no model and no Android runtime.
 */
class SafetyEngineTest {

    private val engine = SafetyEngine()

    // --- input gate: emergencies -------------------------------------------

    @Test
    fun `chest pain is classified as emergency`() {
        val verdict = engine.gateUserInput("I suddenly have chest pain")
        assertTrue(verdict is SafetyVerdict.Emergency)
        assertEquals("chest_pain", (verdict as SafetyVerdict.Emergency).ruleId)
    }

    @Test
    fun `cannot breathe is classified as emergency`() {
        assertTrue(engine.gateUserInput("I can't breathe") is SafetyVerdict.Emergency)
    }

    @Test
    fun `stroke signs are classified as emergency`() {
        assertTrue(engine.gateUserInput("my face is drooping and slurred speech") is SafetyVerdict.Emergency)
    }

    @Test
    fun `self harm is classified as emergency`() {
        assertTrue(engine.gateUserInput("I want to end my life") is SafetyVerdict.Emergency)
    }

    // --- input gate: dangerous requests are refused ------------------------

    @Test
    fun `increasing dose is blocked as dose change`() {
        val verdict = engine.gateUserInput("should I increase the dose of metformin")
        assertTrue(verdict is SafetyVerdict.Blocked)
        assertEquals(BlockKind.DOSE_CHANGE, (verdict as SafetyVerdict.Blocked).kind)
    }

    @Test
    fun `stopping medication is blocked`() {
        val verdict = engine.gateUserInput("can I stop taking my blood pressure medicine")
        assertTrue(verdict is SafetyVerdict.Blocked)
        assertEquals(BlockKind.STOP_MEDICATION, (verdict as SafetyVerdict.Blocked).kind)
    }

    @Test
    fun `diagnosis request is blocked`() {
        val verdict = engine.gateUserInput("what disease do I have?")
        assertTrue(verdict is SafetyVerdict.Blocked)
        assertEquals(BlockKind.DIAGNOSIS, (verdict as SafetyVerdict.Blocked).kind)
    }

    @Test
    fun `refusal message for dose change is the configured wording`() {
        val verdict = engine.gateUserInput("please increase the dose to 10 mg")
        assertEquals(
            SafetyMessages.DEFAULT.refuseDoseChange,
            (verdict as SafetyVerdict.Blocked).message,
        )
    }

    // --- input gate: urgency and caution -----------------------------------

    @Test
    fun `high fever is urgent not emergency`() {
        val verdict = engine.gateUserInput("my child has a high fever")
        assertTrue(verdict is SafetyVerdict.Urgent)
    }

    @Test
    fun `side effect question is caution not block`() {
        val verdict = engine.gateUserInput("are there any side effects of this medicine")
        assertTrue(verdict is SafetyVerdict.Caution)
    }

    @Test
    fun `ordinary care question is allowed`() {
        assertEquals(SafetyVerdict.Allow, engine.gateUserInput("what is on my care plan today?"))
    }

    // --- priority: emergency wins over everything else ----------------------

    @Test
    fun `emergency takes priority over blocked request in same input`() {
        val verdict = engine.gateUserInput("chest pain — should I stop taking my medicine?")
        assertTrue(verdict is SafetyVerdict.Emergency)
    }

    // --- output gate --------------------------------------------------------

    @Test
    fun `model output flagged emergency level is blocked`() {
        val response = AiResponse(
            summary = "Seek help now",
            explanation = "Symptoms described may be life-threatening.",
            safetyLevel = SafetyLevel.EMERGENCY,
        )
        assertTrue(engine.gateAiOutput(response) is SafetyVerdict.Emergency)
    }

    @Test
    fun `model asserting certainty about disease is blocked`() {
        val response = AiResponse(
            summary = "Result",
            explanation = "It is certain that you have diabetes.",
        )
        val verdict = engine.gateAiOutput(response)
        assertTrue(verdict is SafetyVerdict.Blocked)
        assertEquals(
            BlockKind.MEDICAL_CERTAINTY,
            (verdict as SafetyVerdict.Blocked).kind,
        )
    }

    @Test
    fun `model instructing dose change is blocked`() {
        val response = AiResponse(
            summary = "Adjustment",
            explanation = "You should increase your dose to 20 mg.",
        )
        val verdict = engine.gateAiOutput(response)
        assertTrue(verdict is SafetyVerdict.Blocked)
    }

    @Test
    fun `model diagnosis claim is blocked`() {
        val response = AiResponse(
            summary = "Assessment",
            explanation = "Diagnosis: you have a condition called hypertension.",
        )
        val verdict = engine.gateAiOutput(response)
        assertTrue(verdict is SafetyVerdict.Blocked)
        assertEquals(BlockKind.DIAGNOSIS, (verdict as SafetyVerdict.Blocked).kind)
    }

    @Test
    fun `harmless model answer passes the output gate`() {
        val response = AiResponse(
            summary = "Your prescription lists two medicines.",
            explanation = "Both were confirmed by you during document review.",
            warnings = listOf("Take them as prescribed on your discharge sheet."),
        )
        assertEquals(SafetyVerdict.Allow, engine.gateAiOutput(response))
    }

    // --- configurable wording ----------------------------------------------

    @Test
    fun `emergency wording is configurable`() {
        val custom = SafetyMessages.DEFAULT.copy(emergencyResponse = "Call 112 immediately.")
        val customEngine = SafetyEngine(custom)
        val verdict = engine.gateUserInput("chest pain")
        val customVerdict = customEngine.gateUserInput("chest pain")
        assertEquals("Call 112 immediately.", (customVerdict as SafetyVerdict.Emergency).message)
        // Default engine still uses default wording.
        assertEquals(
            SafetyMessages.DEFAULT.emergencyResponse,
            (verdict as SafetyVerdict.Emergency).message,
        )
    }
}
