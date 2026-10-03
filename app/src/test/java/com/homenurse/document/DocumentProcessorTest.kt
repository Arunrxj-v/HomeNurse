package com.homenurse.document

import android.graphics.Bitmap
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.domain.repository.DocumentRepository
import com.homenurse.testing.FakeDocumentRepository
import com.homenurse.testing.FakeFactRepository
import com.homenurse.testing.FixedClock
import com.homenurse.testing.SequentialIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Document pipeline tests with fake renderer + fake OCR (no ML Kit, no
 * network): status transitions, provenance-preserving fact extraction, and
 * honest failure states. Raw OCR text is stored but every fact stays PENDING.
 *
 * Runs under Robolectric because the pipeline works on real page bitmaps
 * (preprocess → recognize) — pixel access needs the Android runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DocumentProcessorTest {

    private val ids = SequentialIds()
    private val clock = FixedClock()
    private lateinit var docs: FakeDocumentRepository
    private lateinit var facts: FakeFactRepository
    private lateinit var ocr: ScriptedOcr
    private lateinit var processor: DocumentProcessor

    private class ScriptedOcr(
        var result: OcrResult = OcrResult(emptyList()),
        var failure: Throwable? = null,
    ) : OcrProvider {
        var calls = 0
        override suspend fun recognize(pages: List<Bitmap>): OcrResult {
            calls++
            failure?.let { throw it }
            return result
        }
    }

    private class FakeRenderer(private val pages: Int = 1) : DocumentRenderer {
        override suspend fun renderForOcr(bytes: ByteArray, mimeType: String): List<Bitmap> =
            List(pages) { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }

        override suspend fun renderPage(bytes: ByteArray, mimeType: String, pageIndex: Int): Bitmap? =
            null
    }

    @Before
    fun setUp() {
        docs = FakeDocumentRepository()
        facts = FakeFactRepository()
        ocr = ScriptedOcr()
        processor = DocumentProcessor(
            documents = docs,
            facts = facts,
            ocrProvider = ocr,
            renderer = FakeRenderer(),
            ids = ids,
            clock = clock,
        )
    }

    private fun putDocument(status: ProcessingStatus = ProcessingStatus.CAPTURED) {
        docs.put(
            document = MedicalDocument(
                id = "doc-1",
                title = "Prescription",
                mimeType = "image/jpeg",
                sizeBytes = 512,
                pageCount = 1,
                status = status,
                extractedText = null,
                failureReason = null,
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
            payload = byteArrayOf(1, 2, 3),
        )
    }

    @Test
    fun `successful pipeline stores text and creates pending facts`() = runTest {
        putDocument()
        ocr.result = OcrResult.of(
            listOf(
                "Name: Asha Rao",
                "Metformin 500 mg twice daily after food",
                "Diagnosis: Type 2 diabetes",
            ),
        )

        processor.process("doc-1")

        // Status: CAPTURED → PROCESSING → REVIEW_REQUIRED.
        assertEquals(
            listOf(ProcessingStatus.PROCESSING, ProcessingStatus.REVIEW_REQUIRED),
            docs.statusLog.map { it.first },
        )
        val document = docs.getDocument("doc-1")!!
        assertEquals(ProcessingStatus.REVIEW_REQUIRED, document.status)
        assertNull(document.failureReason) // a clean read is not flagged
        assertEquals(1, document.pageCount) // one OCR page
        assertTrue(document.extractedText!!.contains("Metformin 500 mg twice daily"))

        // Facts exist but are NEVER auto-trusted.
        assertTrue(facts.all.isNotEmpty())
        assertTrue(facts.all.all { it.status == FactStatus.PENDING })
        assertTrue(facts.all.all { it.documentId == "doc-1" })
        assertTrue(facts.all.all { it.sourceText.isNotBlank() })
        assertTrue(facts.all.any { it.type == com.homenurse.domain.model.FactType.MEDICATION })
    }

    @Test
    fun `readable text is also stored in its normalized form, raw untouched`() = runTest {
        putDocument()
        ocr.result = OcrResult.of(listOf("Haemoglobin 13,2 g/dl"))

        processor.process("doc-1")

        val document = docs.getDocument("doc-1")!!
        assertEquals("Haemoglobin 13,2 g/dl", document.extractedText) // raw, verbatim
        assertEquals("Haemoglobin 13.2 g/dl", document.normalizedText) // decimal repaired
    }

    @Test
    fun `empty read fails honestly instead of pretending success`() = runTest {
        putDocument()
        ocr.result = OcrResult(listOf(OcrPage(emptyList())))

        processor.process("doc-1")

        val document = docs.getDocument("doc-1")!!
        assertEquals(ProcessingStatus.FAILED, document.status)
        assertEquals("no_text", document.failureReason)
        assertTrue(facts.all.isEmpty())
    }

    @Test
    fun `ocr unavailable fails with honest reason and stays retryable`() = runTest {
        putDocument()
        ocr.failure = OcrUnavailableException()

        processor.process("doc-1")

        val document = docs.getDocument("doc-1")!!
        assertEquals(ProcessingStatus.FAILED, document.status)
        assertEquals("ocr_unavailable", document.failureReason)
        assertTrue(facts.all.isEmpty())
    }

    @Test
    fun `unreadable document fails with processing_failed`() = runTest {
        putDocument()
        ocr.failure = RuntimeException("corrupt jpeg")

        processor.process("doc-1")

        val document = docs.getDocument("doc-1")!!
        assertEquals(ProcessingStatus.FAILED, document.status)
        assertEquals("processing_failed", document.failureReason)
    }

    @Test
    fun `oversized document fails with too_large`() = runTest {
        putDocument()
        // Simulate the repository refusing to hand out an oversized file.
        val failingDocs = object : com.homenurse.domain.repository.DocumentRepository by docs {
            override suspend fun openBytes(documentId: String): ByteArray =
                throw DocumentRepository.ImportTooLargeException()
        }
        val processorWithLimits = DocumentProcessor(
            documents = failingDocs,
            facts = facts,
            ocrProvider = ocr,
            renderer = FakeRenderer(),
            ids = ids,
            clock = clock,
        )

        processorWithLimits.process("doc-1")

        val document = docs.getDocument("doc-1")!!
        assertEquals(ProcessingStatus.FAILED, document.status)
        assertEquals("too_large", document.failureReason)
    }

    @Test
    fun `missing document fails with not_found`() = runTest {
        processor.process("absent")

        assertEquals(
            listOf(ProcessingStatus.FAILED to "not_found"),
            docs.statusLog,
        )
    }

    @Test
    fun `low-confidence read is flagged poor but stays reviewable`() = runTest {
        putDocument()
        ocr.result = OcrResult(
            listOf(
                OcrPage(
                    listOf(
                        OcrLine("Metformin 500 mg twice daily", confidence = 0.2f),
                        OcrLine("Paracetamol 650 mg", confidence = 0.15f),
                    ),
                ),
            ),
        )

        processor.process("doc-1")

        val document = docs.getDocument("doc-1")!!
        assertEquals(ProcessingStatus.REVIEW_REQUIRED, document.status)
        assertEquals("low_ocr_quality", document.failureReason)
        // Even a poor read never auto-trusts anything.
        assertTrue(facts.all.isNotEmpty())
        assertTrue(facts.all.all { it.status == FactStatus.PENDING })
        // The low OCR confidence flows into the fact so review flags it.
        assertTrue(facts.all.all { it.needsVerification })
    }

    @Test
    fun `multi-page document is read page by page`() = runTest {
        putDocument()
        val multiOcr = ScriptedOcr(
            result = OcrResult.of(
                listOf("Name: Asha Rao"),
                listOf("Metformin 500 mg twice daily"),
            ),
        )
        val multiProcessor = DocumentProcessor(
            documents = docs,
            facts = facts,
            ocrProvider = multiOcr,
            renderer = FakeRenderer(pages = 2),
            ids = ids,
            clock = clock,
        )

        multiProcessor.process("doc-1")

        assertEquals(2, multiOcr.calls) // one recognition per page (single candidate)
        val document = docs.getDocument("doc-1")!!
        assertEquals(2, document.pageCount)
        assertTrue(document.extractedText!!.contains("Name: Asha Rao"))
        assertTrue(document.extractedText!!.contains("Metformin 500 mg"))
        assertTrue(facts.all.any { it.type == com.homenurse.domain.model.FactType.MEDICATION })
    }

    @Test
    fun `re-read replaces pending candidates but keeps confirmed facts`() = runTest {
        putDocument()
        facts.seed(
            MedicalFact(
                id = "confirmed-1",
                documentId = "doc-1",
                type = com.homenurse.domain.model.FactType.CONDITION,
                status = FactStatus.CONFIRMED,
                name = "Hypertension",
                dose = null,
                frequency = null,
                timing = null,
                duration = null,
                value = "Hypertension",
                sourceText = "Diagnosis: Hypertension",
                confidence = 1.0,
            ),
        )
        ocr.result = OcrResult.of(listOf("Allergy: Penicillin"))

        processor.process("doc-1")

        // Confirmed decision survives the re-read; candidates are fresh.
        assertTrue(facts.all.any { it.id == "confirmed-1" && it.status == FactStatus.CONFIRMED })
        val pending = facts.all.filter { it.status == FactStatus.PENDING }
        assertTrue(pending.isNotEmpty())
        assertTrue(pending.all { it.id != "confirmed-1" })
        assertTrue(pending.all { it.type == com.homenurse.domain.model.FactType.ALLERGY })
    }
}
