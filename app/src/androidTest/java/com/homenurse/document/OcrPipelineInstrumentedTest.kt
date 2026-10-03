package com.homenurse.document

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.testing.FixedClock
import com.homenurse.testing.InMemDocumentRepository
import com.homenurse.testing.InMemFactRepository
import com.homenurse.testing.SequentialIds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * End-to-end OCR pipeline runs against **real sample documents** on a real
 * device: drawn pages → byte input → [DocumentImporter] → adaptive
 * preprocessing → real ML Kit recognition → conservative normalization →
 * structured extraction → review state.
 *
 * Properties asserted for every scenario:
 *  * the pipeline never crashes and never claims unreviewed success — the
 *    document ends in REVIEW_REQUIRED (possibly with an honest
 *    `low_ocr_quality` flag) or FAILED with a coarse reason,
 *  * every extracted fact stays PENDING — OCR output is never medical truth,
 *  * nothing is silently "corrected": raw text is preserved verbatim.
 *
 * The recognition-heavy scenarios (clear prescription, lab table, multi-page
 * PDF) additionally assert that the right facts are actually extracted.
 */
@RunWith(AndroidJUnit4::class)
class OcrPipelineInstrumentedTest {

    private lateinit var importer: DocumentImporter

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        importer = DocumentImporter(context.cacheDir)
    }

    // --- fixture documents ----------------------------------------------------------

    private fun blankPage(width: Int = 1240, height: Int = 1754): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun bodyPaint(textSize: Float = 48f, color: Int = Color.BLACK): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize; this.color = color }

    private fun drawPrescriptionContent(canvas: Canvas) {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.rgb(20, 40, 80)
        }
        canvas.drawText("City Hospital", 90f, 150f, title)
        val body = bodyPaint()
        val lines = listOf(
            "Name: Asha Rao",
            "Age: 67",
            "Diagnosis: Type 2 diabetes",
            "Metformin 500 mg twice daily after food",
            "Amlodipine 5 mg by mouth once daily",
            "Allergy: Penicillin",
        )
        lines.forEachIndexed { i, text ->
            canvas.drawText(text, 90f, 330f + i * 120f, body)
        }
    }

    /** Clear, well-lit prescription page (the easy case). */
    private fun renderPrescription(): Bitmap =
        blankPage().also { drawPrescriptionContent(Canvas(it)) }

    /** Lab report drawn as a real label/value/reference table. */
    private fun renderLabReport(): Bitmap = blankPage().also { page ->
        val canvas = Canvas(page)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 58f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.rgb(20, 40, 80)
        }
        canvas.drawText("Lab Report", 90f, 150f, title)
        val body = bodyPaint(textSize = 42f)
        val rows = listOf(
            Triple("Hemoglobin", "13.2 g/dL", "ref 12.0 - 15.0"),
            Triple("Cholesterol", "180 mg/dL", "ref 100 - 200"),
            Triple("Blood pressure", "120/80 mmHg", ""),
        )
        rows.forEachIndexed { i, (label, value, ref) ->
            val y = 340f + i * 130f
            canvas.drawText(label, 90f, y, body)
            canvas.drawText(value, 600f, y, body)
            if (ref.isNotEmpty()) canvas.drawText(ref, 940f, y, body)
        }
        return page
    }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        // Flatten onto white: rotation exposes transparent corners which a
        // JPEG encoder would turn into harsh black wedges.
        val flat = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        flat.eraseColor(Color.WHITE)
        Canvas(flat).drawBitmap(rotated, 0f, 0f, null)
        return flat
    }

    private fun applyShadow(src: Bitmap): Bitmap {
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val shader = LinearGradient(
            0f, 0f,
            result.width.toFloat(), result.height * 0.7f,
            Color.TRANSPARENT, Color.argb(110, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
        val paint = Paint().apply { this.shader = shader }
        canvas.drawRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), paint)
        return result
    }

    /** Re-encodes at low quality — camera-compression artifacts. */
    private fun toJpegBytes(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }.toByteArray()

    private fun toPngBytes(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()

    private fun renderPdfBytes(vararg pages: (Canvas) -> Unit): ByteArray {
        val document = PdfDocument()
        pages.forEachIndexed { index, drawPage ->
            val info = PdfDocument.PageInfo.Builder(1240, 1754, index + 1).create()
            val page = document.startPage(info)
            drawPage(page.canvas)
            document.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    // --- pipeline runner -------------------------------------------------------------

    private data class Outcome(
        val document: MedicalDocument,
        val facts: List<MedicalFact>,
        val statusLog: List<Pair<ProcessingStatus, String?>>,
    )

    private suspend fun process(bytes: ByteArray, mimeType: String): Outcome {
        val documents = InMemDocumentRepository().apply {
            put(
                MedicalDocument(
                    id = "doc-1",
                    title = "sample",
                    mimeType = mimeType,
                    sizeBytes = bytes.size.toLong(),
                    pageCount = 1,
                    status = ProcessingStatus.CAPTURED,
                    extractedText = null,
                    failureReason = null,
                    createdAt = 1_700_000_000_000L,
                    updatedAt = 1_700_000_000_000L,
                ),
                bytes,
            )
        }
        val facts = InMemFactRepository()
        val processor = DocumentProcessor(
            documents = documents,
            facts = facts,
            ocrProvider = MlKitOcrProvider(),
            renderer = importer,
            ids = SequentialIds(),
            clock = FixedClock(),
        )
        processor.process("doc-1")
        val document = documents.getDocument("doc-1")
            ?: error("document disappeared from the repository")
        return Outcome(document, facts.all, documents.statusLog)
    }

    private val knownFailureReasons = setOf(
        "no_text", "low_ocr_quality", "unreadable", "ocr_unavailable",
        "processing_failed", "too_large", "not_found",
    )

    /**
     * The honesty contract: never a silent success, never a trusted fact,
     * never an unknown failure code (UI maps these to plain-language copy).
     */
    private fun assertHonestOutcome(outcome: Outcome) {
        val status = outcome.document.status
        assertTrue("unexpected final status: $status", status == ProcessingStatus.REVIEW_REQUIRED || status == ProcessingStatus.FAILED)
        val reason = outcome.document.failureReason
        if (status == ProcessingStatus.FAILED) {
            assertNotNull("FAILED needs a coarse reason", reason)
            assertTrue("unknown failure reason: $reason", reason in knownFailureReasons)
        } else {
            assertTrue("unexpected review flag: $reason", reason == null || reason == "low_ocr_quality")
        }
        outcome.facts.forEach { fact ->
            assertEquals(
                "OCR output must never auto-confirm facts (${fact.name ?: fact.value})",
                FactStatus.PENDING,
                fact.status,
            )
            assertTrue("fact must carry its verbatim source line", fact.sourceText.isNotBlank())
        }
    }

    private fun Outcome.medicationNamed(name: String): MedicalFact? =
        facts.firstOrNull {
            it.type == FactType.MEDICATION &&
                it.name != null &&
                it.name.contains(name, ignoreCase = true)
        }

    // --- scenarios --------------------------------------------------------------------

    @Test
    fun clearPrescription_readsMedicationsAndLandsInReview() = runBlocking {
        val outcome = process(toPngBytes(renderPrescription()), "image/png")

        assertHonestOutcome(outcome)
        assertEquals(
            listOf(ProcessingStatus.PROCESSING, ProcessingStatus.REVIEW_REQUIRED),
            outcome.statusLog.map { it.first },
        )
        assertEquals(null, outcome.document.failureReason) // a clean read is not flagged
        assertEquals(1, outcome.document.pageCount)
        assertNotNull(outcome.document.extractedText)
        assertTrue(outcome.document.extractedText!!.contains("Metformin 500 mg"))

        val metformin = outcome.medicationNamed("Metformin")
        assertNotNull("medication fact must be extracted", metformin)
        assertEquals("500 mg", metformin!!.dose)
        assertEquals("Twice daily", metformin.frequency)
        assertEquals("After food", metformin.timing)

        assertTrue(outcome.facts.any { it.type == FactType.PATIENT_INFO && it.value == "Asha Rao" })
        assertTrue(outcome.facts.any { it.type == FactType.CONDITION && it.name == "Type 2 diabetes" })
        assertTrue(outcome.facts.any { it.type == FactType.ALLERGY && it.name == "Penicillin" })
    }

    @Test
    fun labReport_extractsUnitsAndRangesWithoutInterpretation() = runBlocking {
        val outcome = process(toPngBytes(renderLabReport()), "image/png")

        assertHonestOutcome(outcome)

        val hemoglobin = outcome.facts.firstOrNull {
            it.type == FactType.LAB_RESULT && it.name.equals("Hemoglobin", ignoreCase = true)
        }
        assertNotNull("lab row must be extracted", hemoglobin)
        assertTrue(hemoglobin!!.value.contains("13.2"))
        assertEquals("g/dl", hemoglobin.unit)
        assertNotNull(hemoglobin.referenceRange)
        // Values are copied verbatim — no interpretation words are invented.
        val forbidden = listOf("normal", "high", "low", "abnormal", "critical")
        outcome.facts.filter { it.type == FactType.LAB_RESULT }.forEach { fact ->
            forbidden.none { word -> fact.value.contains(word, ignoreCase = true) }
                .let { assertTrue("lab value must not interpret: ${fact.value}", it) }
        }

        // Blood pressure pair keeps its numeric form.
        assertTrue(
            outcome.facts.any {
                it.type == FactType.LAB_RESULT && it.value.contains("120/80", ignoreCase = true)
            },
        )
    }

    @Test
    fun rotatedPage_isRecoveredByTheBothCandidatesPolicy() = runBlocking {
        val sideways = rotate(renderPrescription(), 90f)
        val outcome = process(toJpegBytes(sideways, 92), "image/jpeg")

        assertHonestOutcome(outcome)
        assertEquals(ProcessingStatus.REVIEW_REQUIRED, outcome.document.status)
        assertNotNull("rotated page must still be read", outcome.medicationNamed("Metformin"))
    }

    @Test
    fun skewedPage_isDeskewedAndRead() = runBlocking {
        val skewed = rotate(renderPrescription(), 6f)
        val outcome = process(toJpegBytes(skewed, 92), "image/jpeg")

        assertHonestOutcome(outcome)
        assertEquals(ProcessingStatus.REVIEW_REQUIRED, outcome.document.status)
        assertNotNull("skewed page must still be read", outcome.medicationNamed("Metformin"))
    }

    @Test
    fun shadowedPage_isFlattenedAndRead() = runBlocking {
        val shadowed = applyShadow(renderPrescription())
        val outcome = process(toJpegBytes(shadowed, 92), "image/jpeg")

        assertHonestOutcome(outcome)
        assertEquals(ProcessingStatus.REVIEW_REQUIRED, outcome.document.status)
        assertNotNull("shadowed page must still be read", outcome.medicationNamed("Metformin"))
    }

    @Test
    fun lowQualityPhoto_neverCrashesAndNeverLies() {
        runBlocking {
        // Blurred strokes + heavy JPEG compression: a bad phone photo.
        val blurred = blankPage().also { page ->
            val canvas = Canvas(page)
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 64f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                color = Color.rgb(20, 40, 80)
            }
            canvas.drawText("City Hospital", 90f, 150f, title)
            val body = bodyPaint().apply {
                maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
                color = Color.rgb(70, 70, 70)
            }
            listOf(
                "Name: Asha Rao",
                "Diagnosis: Type 2 diabetes",
                "Metformin 500 mg twice daily after food",
                "Allergy: Penicillin",
            ).forEachIndexed { i, text ->
                canvas.drawText(text, 90f, 330f + i * 120f, body)
            }
        }
        val outcome = process(toJpegBytes(blurred, 25), "image/jpeg")

        // The hard guarantee: honest terminal state + everything PENDING.
        assertHonestOutcome(outcome)
        // If any text was read at all, the raw copy is verbatim.
        outcome.document.extractedText?.let { raw ->
            assertFalse(raw.contains("Metforminn")) // no invented letters, no autocorrect
        }
        }
    }

    @Test
    fun multiPagePdf_readsEveryPage() = runBlocking {
        val bytes = renderPdfBytes(
            { canvas ->
                val body = bodyPaint()
                canvas.drawText("Page one prescription", 90f, 150f, bodyPaint(56f))
                canvas.drawText("Name: Asha Rao", 90f, 330f, body)
                canvas.drawText("Metformin 500 mg twice daily after food", 90f, 450f, body)
            },
            { canvas ->
                // Column layout mirrors the proven labReport fixture: label,
                // value and reference in separate columns read reliably.
                val body = bodyPaint(textSize = 42f)
                canvas.drawText("Page two labs", 90f, 150f, bodyPaint(56f))
                canvas.drawText("Hemoglobin", 90f, 360f, body)
                canvas.drawText("13.2 g/dL", 600f, 360f, body)
                canvas.drawText("ref 12.0 - 15.0", 940f, 360f, body)
            },
        )
        val outcome = process(bytes, "application/pdf")

        assertHonestOutcome(outcome)
        assertEquals("both pages must be rendered for OCR", 2, outcome.document.pageCount)
        assertNotNull("page one medication", outcome.medicationNamed("Metformin"))
        assertTrue(
            "page two lab fact missing; facts=${outcome.facts.map { "${it.type}/${it.name ?: "-"}=${it.value}" }} | " +
                "text=${outcome.document.normalizedText}",
            outcome.facts.any {
                it.type == FactType.LAB_RESULT && it.name.equals("Hemoglobin", ignoreCase = true)
            },
        )
    }

    @Test
    fun smallText_isUpscaledAndRead() = runBlocking {
        val smallText = blankPage().also { page ->
            val canvas = Canvas(page)
            canvas.drawText("Small print prescription", 90f, 140f, bodyPaint(40f))
            val body = bodyPaint(textSize = 24f)
            listOf(
                "Name: Asha Rao",
                "Metformin 500 mg twice daily after food",
                "Allergy: Penicillin",
            ).forEachIndexed { i, text ->
                canvas.drawText(text, 90f, 330f + i * 80f, body)
            }
        }
        val outcome = process(toPngBytes(smallText), "image/png")

        assertHonestOutcome(outcome)
        // Honest either way, but a clean upscale should genuinely read it.
        assertEquals(ProcessingStatus.REVIEW_REQUIRED, outcome.document.status)
        assertNotNull("small text must be read after upscaling", outcome.medicationNamed("Metformin"))
    }

    @Test
    fun poorContrast_isStretchedAndRead() = runBlocking {
        val washed = blankPage().also { page ->
            val canvas = Canvas(page)
            canvas.drawColor(Color.rgb(205, 205, 205))
            canvas.drawText("Faded prescription", 90f, 140f, bodyPaint(56f, Color.rgb(125, 125, 125)))
            val body = bodyPaint(48f, Color.rgb(125, 125, 125))
            listOf(
                "Name: Asha Rao",
                "Metformin 500 mg twice daily after food",
                "Allergy: Penicillin",
            ).forEachIndexed { i, text ->
                canvas.drawText(text, 90f, 330f + i * 120f, body)
            }
        }
        val outcome = process(toJpegBytes(washed, 92), "image/jpeg")

        assertHonestOutcome(outcome)
        assertEquals(ProcessingStatus.REVIEW_REQUIRED, outcome.document.status)
        assertNotNull("washed-out page must be read after the stretch", outcome.medicationNamed("Metformin"))
    }

    @Test
    fun commaDecimal_isRepairedOnlyInTheNormalizedCopyAndFlagged() = runBlocking {
        val page = blankPage().also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawText("Blood report", 90f, 150f, bodyPaint(56f))
            val body = bodyPaint(textSize = 46f)
            canvas.drawText("Hemoglobin 13,2 g/dL", 90f, 360f, body)
            canvas.drawText("ref 12.0 - 15.0", 90f, 480f, body)
        }
        val outcome = process(toPngBytes(page), "image/png")

        assertHonestOutcome(outcome)
        val raw = outcome.document.extractedText
        val normalized = outcome.document.normalizedText
        assertNotNull("raw OCR text must be stored", raw)
        assertNotNull("normalized text must be stored", normalized)
        assertTrue("raw keeps the document's comma: $raw", raw!!.contains("13,2"))
        assertTrue("normalized repairs the decimal: $normalized", normalized!!.contains("13.2"))

        val hemoglobin = outcome.facts.firstOrNull {
            it.type == FactType.LAB_RESULT && it.name.equals("Hemoglobin", ignoreCase = true)
        }
        assertNotNull(
            "no Hemoglobin LAB fact. facts=${outcome.facts.map { "${it.type}/${it.name ?: "-"}=${it.value}" }} | " +
                "raw=$raw | normalized=$normalized",
            hemoglobin,
        )
        assertTrue("value carries the repaired reading", hemoglobin!!.value.contains("13.2"))
        assertTrue(
            "a structurally repaired value must be flagged for verification",
            hemoglobin.needsVerification,
        )
        assertEquals(FactStatus.PENDING, hemoglobin.status)
    }

    private fun assertFalse(condition: Boolean) {
        org.junit.Assert.assertFalse(condition)
    }
}
