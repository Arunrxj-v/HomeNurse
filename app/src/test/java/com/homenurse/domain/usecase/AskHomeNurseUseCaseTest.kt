package com.homenurse.domain.usecase

import com.homenurse.ai.AiProviderException
import com.homenurse.domain.model.AiEvidenceRef
import com.homenurse.domain.model.AiResponse
import com.homenurse.domain.model.AiTask
import com.homenurse.domain.model.ConversationRole
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.domain.model.SafetyLevel
import com.homenurse.safety.SafetyEngine
import com.homenurse.safety.SafetyVerdict
import com.homenurse.testing.FakeAiProvider
import com.homenurse.testing.FakeCarePlanRepository
import com.homenurse.testing.FakeConversationRepository
import com.homenurse.testing.FakeDocumentRepository
import com.homenurse.testing.FakeFactRepository
import com.homenurse.testing.FakeMedicationRepository
import com.homenurse.testing.FixedClock
import com.homenurse.testing.SequentialIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Ask HomeNurse" pipeline tests — the core safety guarantees:
 *
 *  1. emergencies / dose-change / diagnosis requests NEVER reach the model,
 *  2. unsafe model output never reaches the UI,
 *  3. model failures surface as honest typed errors (no fabricated answers),
 *  4. answers carry provenance evidence pointing at CONFIRMED facts only.
 */
class AskHomeNurseUseCaseTest {

    private val ids = SequentialIds()
    private val clock = FixedClock()
    private lateinit var facts: FakeFactRepository
    private lateinit var meds: FakeMedicationRepository
    private lateinit var docs: FakeDocumentRepository
    private lateinit var carePlan: FakeCarePlanRepository
    private lateinit var conversations: FakeConversationRepository
    private lateinit var ai: FakeAiProvider
    private lateinit var useCase: AskHomeNurseUseCase

    @Before
    fun setUp() {
        facts = FakeFactRepository()
        meds = FakeMedicationRepository(facts)
        docs = FakeDocumentRepository()
        carePlan = FakeCarePlanRepository()
        conversations = FakeConversationRepository(ids, clock)
        ai = FakeAiProvider()
        useCase = AskHomeNurseUseCase(
            safetyEngine = SafetyEngine(),
            conversations = conversations,
            buildContext = BuildAiContextUseCase(facts, meds, carePlan, docs),
            aiProvider = ai,
            ids = ids,
            clock = clock,
        )
    }

    private fun seedConfirmedFact(): MedicalFact {
        docs.put(
            MedicalDocument(
                id = "doc-1",
                title = "Discharge summary",
                mimeType = "image/jpeg",
                sizeBytes = 1_000,
                pageCount = 1,
                status = ProcessingStatus.CONFIRMED,
                extractedText = "Diagnosis: Hypertension",
                failureReason = null,
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )
        val fact = MedicalFact(
            id = "f1",
            documentId = "doc-1",
            type = FactType.CONDITION,
            status = FactStatus.CONFIRMED,
            name = null,
            dose = null,
            frequency = null,
            timing = null,
            duration = null,
            value = "Hypertension",
            sourceText = "Diagnosis: Hypertension",
            confidence = 0.9,
        )
        facts.seed(fact)
        return fact
    }

    // --- input gate: the model is never called for dangerous input ------------

    @Test
    fun `emergency question short circuits without calling the model`() = runTest {
        val outcome = useCase("I suddenly have chest pain")

        assertTrue(outcome is AskHomeNurseUseCase.Outcome.Safety)
        val safety = outcome as AskHomeNurseUseCase.Outcome.Safety
        assertTrue(safety.verdict is SafetyVerdict.Emergency)
        assertEquals(SafetyLevel.EMERGENCY, safety.message.safetyLevel)
        assertEquals(ConversationRole.SAFETY, safety.message.role)

        assertTrue("model must not be called", ai.requests.isEmpty())
        assertEquals(2, conversations.allMessages.size)
        assertEquals(ConversationRole.USER, conversations.allMessages[0].role)
        assertEquals(ConversationRole.SAFETY, conversations.allMessages[1].role)
        assertTrue(conversations.allAnalyses.isEmpty())
    }

    @Test
    fun `dose change request is refused without model call`() = runTest {
        val outcome = useCase("should I increase the dose of metformin")

        assertTrue(outcome is AskHomeNurseUseCase.Outcome.Safety)
        assertTrue((outcome as AskHomeNurseUseCase.Outcome.Safety).verdict is SafetyVerdict.Blocked)
        assertTrue(ai.requests.isEmpty())
    }

    @Test
    fun `diagnosis request is refused without model call`() = runTest {
        val outcome = useCase("what disease do I have?")
        assertTrue(outcome is AskHomeNurseUseCase.Outcome.Safety)
        assertTrue((outcome as AskHomeNurseUseCase.Outcome.Safety).verdict is SafetyVerdict.Blocked)
        assertTrue(ai.requests.isEmpty())
    }

    // --- happy path -----------------------------------------------------------

    @Test
    fun `normal question reaches the model with confirmed context and persists reply`() =
        runTest {
            seedConfirmedFact()
            ai.response = AiResponse(
                summary = "Your records list one confirmed condition.",
                explanation = "Hypertension was confirmed by you during document review.",
                evidence = listOf(AiEvidenceRef(factId = "f1")),
                suggestedNextSteps = listOf("Bring this list to your next appointment."),
            )

            val outcome = useCase("what is in my medical records?")

            assertTrue(outcome is AskHomeNurseUseCase.Outcome.Reply)
            val reply = outcome as AskHomeNurseUseCase.Outcome.Reply
            assertEquals(ConversationRole.MODEL, reply.message.role)
            assertTrue(reply.message.content.contains("Your records list one confirmed condition."))
            assertTrue(reply.message.content.contains("Hypertension was confirmed by you"))
            assertTrue(reply.message.content.contains("Bring this list to your next appointment."))

            // Exactly one model request, with the right task + confirmed-only context.
            assertEquals(1, ai.requests.size)
            assertEquals(AiTask.ANSWER_QUESTION, ai.requests.single().task)
            assertEquals("what is in my medical records?", ai.requests.single().question)
            assertEquals(
                listOf("f1"),
                ai.requests.single().context.confirmedFacts.map { it.id },
            )

            // Analysis row persisted with provenance evidence.
            assertNotNull(reply.analysis)
            val evidence = reply.analysis!!.evidence
            assertEquals(1, evidence.size)
            assertEquals("f1", evidence.single().factId)
            assertEquals("doc-1", evidence.single().documentId)
            assertEquals("Diagnosis: Hypertension", evidence.single().sourceText)

            assertEquals(2, conversations.allMessages.size)
            assertEquals(1, conversations.allAnalyses.size)
        }

    @Test
    fun `evidence pointing at unconfirmed data is dropped from analysis`() = runTest {
        seedConfirmedFact()
        ai.response = AiResponse(
            summary = "Summary",
            explanation = "Explanation",
            evidence = listOf(
                AiEvidenceRef(factId = "does-not-exist"),
                AiEvidenceRef(documentId = "unknown-doc"),
            ),
        )

        val outcome = useCase("anything routine?")
        val reply = outcome as AskHomeNurseUseCase.Outcome.Reply
        assertNotNull(reply.analysis)
        assertTrue(
            "evidence must only reference confirmed data",
            reply.analysis!!.evidence.isEmpty(),
        )
    }

    // --- output gate ----------------------------------------------------------

    @Test
    fun `unsafe model output is blocked before reaching the user`() = runTest {
        ai.response = AiResponse(
            summary = "Assessment",
            explanation = "Diagnosis: you have a condition called hypertension.",
        )

        val outcome = useCase("what did my report say?")

        assertTrue(outcome is AskHomeNurseUseCase.Outcome.Safety)
        assertTrue((outcome as AskHomeNurseUseCase.Outcome.Safety).verdict is SafetyVerdict.Blocked)
        // The model ran, but no model message was persisted for the user.
        assertEquals(1, ai.requests.size)
        assertTrue(conversations.allMessages.none { it.role == ConversationRole.MODEL })
        assertTrue(conversations.allAnalyses.isEmpty())
    }

    @Test
    fun `model flagged emergency output becomes safety message`() = runTest {
        ai.response = AiResponse(
            summary = "Seek emergency help",
            explanation = "Call emergency services.",
            safetyLevel = SafetyLevel.EMERGENCY,
        )

        val outcome = useCase("how are you today?")

        assertTrue(outcome is AskHomeNurseUseCase.Outcome.Safety)
        assertTrue((outcome as AskHomeNurseUseCase.Outcome.Safety).verdict is SafetyVerdict.Emergency)
        assertTrue(conversations.allMessages.none { it.role == ConversationRole.MODEL })
    }

    // --- honest failure reporting --------------------------------------------

    @Test
    fun `model not ready surfaces as typed error not an answer`() = runTest {
        ai.failure = AiProviderException.NotReady()

        val outcome = useCase("what is my dose schedule?")

        assertEquals(AskHomeNurseUseCase.Outcome.AiError(AiErrorKind.MODEL_NOT_READY), outcome)
        assertTrue(conversations.allMessages.none { it.role == ConversationRole.MODEL })
        assertTrue(conversations.allAnalyses.isEmpty())
    }

    @Test
    fun `malformed model output surfaces as typed error`() = runTest {
        ai.failure = AiProviderException.MalformedResponse()

        val outcome = useCase("what is my dose schedule?")

        assertEquals(AskHomeNurseUseCase.Outcome.AiError(AiErrorKind.MALFORMED_RESPONSE), outcome)
        assertTrue(conversations.allAnalyses.isEmpty())
    }

    @Test
    fun `inference failure surfaces as typed error`() = runTest {
        ai.failure = AiProviderException.InferenceFailed(RuntimeException("native crash"))

        val outcome = useCase("what is my dose schedule?")

        assertEquals(AskHomeNurseUseCase.Outcome.AiError(AiErrorKind.INFERENCE), outcome)
    }

    @Test
    fun `empty question is rejected without persisting anything`() = runTest {
        val outcome = useCase("   ")
        assertEquals(AskHomeNurseUseCase.Outcome.AiError(AiErrorKind.INFERENCE), outcome)
        assertTrue(conversations.allMessages.isEmpty())
        assertTrue(ai.requests.isEmpty())
    }
}
