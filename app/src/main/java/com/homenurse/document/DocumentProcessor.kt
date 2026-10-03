package com.homenurse.document

import android.graphics.Bitmap
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.domain.repository.DocumentRepository
import com.homenurse.domain.repository.FactRepository
import com.homenurse.domain.util.Clock
import com.homenurse.domain.util.IdGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Document pipeline: stored bytes → page bitmaps → adaptive preprocessing →
 * local OCR → conservative normalization → deterministic fact extraction →
 * PENDING facts awaiting user review.
 *
 * Status transitions:
 *   CAPTURED → PROCESSING → REVIEW_REQUIRED   (text found, facts created)
 *   CAPTURED → PROCESSING → REVIEW_REQUIRED + "low_ocr_quality"
 *                          (readable but thin/uncertain text — honest
 *                           warning, review and retry stay available)
 *   CAPTURED → PROCESSING → FAILED            (unreadable / too large /
 *                          no readable text / OCR error)
 *
 * Raw OCR output and its normalized form are stored only inside the
 * encrypted database and are NEVER auto-trusted: extracted facts stay
 * PENDING until the user confirms them. No document content is ever logged.
 * One document is processed at a time; multi-page runs report progress.
 */
class DocumentProcessor(
    private val documents: DocumentRepository,
    private val facts: FactRepository,
    private val ocrProvider: OcrProvider,
    private val renderer: DocumentRenderer,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val preprocessor: DocumentImagePreprocessor = DocumentImagePreprocessor(),
) {

    /** Page progress of the active run; null when nothing is running. */
    data class OcrProgress(val page: Int, val total: Int) {
        val fraction: Float get() = if (total <= 0) 0f else page.toFloat() / total
    }

    private val _progress = MutableStateFlow<OcrProgress?>(null)
    val progress: StateFlow<OcrProgress?> = _progress.asStateFlow()

    private val mutex = Mutex()

    suspend fun process(documentId: String) {
        mutex.withLock {
            // Preprocessing and extraction are CPU-heavy — off the main thread.
            withContext(Dispatchers.Default) {
                try {
                    run(documentId)
                } finally {
                    _progress.value = null
                }
            }
        }
    }

    private suspend fun run(documentId: String) {
        try {
            val document = documents.getDocument(documentId) ?: run {
                documents.setStatus(documentId, ProcessingStatus.FAILED, "not_found")
                return
            }
            documents.setStatus(documentId, ProcessingStatus.PROCESSING)

            val bytes = documents.openBytes(documentId)
            val bitmaps = renderer.renderForOcr(bytes, document.mimeType)
            if (bitmaps.isEmpty()) {
                documents.setStatus(documentId, ProcessingStatus.FAILED, "unreadable")
                return
            }

            // Page-by-page: preprocessing + recognition off the main thread,
            // cooperative cancellation between pages, progress per page.
            val ocrLines = ArrayList<OcrLine>(bitmaps.size * 32)
            bitmaps.forEachIndexed { index, bitmap ->
                currentCoroutineContext().ensureActive()
                ocrLines += recognizePage(bitmap, index + 1)
                _progress.value = OcrProgress(index + 1, bitmaps.size)
            }

            val totalChars = ocrLines.sumOf { it.text.length }
            val confidences = ocrLines.mapNotNull { it.confidence }
            val averageConfidence = confidences.takeIf { it.isNotEmpty() }?.average()

            // Raw text stays verbatim; extraction runs against the
            // conservative normalized form — both are persisted.
            val normalized = OcrTextNormalizer.normalize(ocrLines)
            documents.updateExtractedText(
                id = documentId,
                text = rawTextOf(ocrLines),
                normalizedText = normalized.normalizedText,
                pageCount = bitmaps.size,
            )

            // Re-read replaces old candidates; confirmed facts survive.
            facts.deletePending(documentId)
            val extracted = MedicalFactExtractor.extract(documentId, normalized.lines, ids, clock)
            if (extracted.isNotEmpty()) facts.addFacts(extracted)

            val lowYield = totalChars < MIN_CHARS_PER_PAGE * bitmaps.size
            val lowConfidence = averageConfidence != null && averageConfidence < LOW_CONFIDENCE
            when {
                totalChars <= 0 ->
                    documents.setStatus(documentId, ProcessingStatus.FAILED, "no_text")
                lowYield || lowConfidence ->
                    documents.setStatus(documentId, ProcessingStatus.REVIEW_REQUIRED, "low_ocr_quality")
                else ->
                    documents.setStatus(documentId, ProcessingStatus.REVIEW_REQUIRED)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: OcrUnavailableException) {
            documents.setStatus(documentId, ProcessingStatus.FAILED, "ocr_unavailable")
        } catch (error: DocumentRepository.ImportTooLargeException) {
            documents.setStatus(documentId, ProcessingStatus.FAILED, "too_large")
        } catch (error: Exception) {
            documents.setStatus(documentId, ProcessingStatus.FAILED, "processing_failed")
        }
    }

    /**
     * Preprocess → recognize each candidate until one reads well (for
     * sideways pages both 90° turns compete and real OCR picks the winner —
     * orientation is never guessed blind).
     */
    private suspend fun recognizePage(bitmap: Bitmap, page: Int): List<OcrLine> {
        val candidates = preprocessor.preprocess(bitmap.toGrayImage()).candidates
        var best: OcrResult? = null
        var bestScore = 0.0
        for (candidate in candidates) {
            val ocr = ocrProvider.recognize(listOf(candidate.toBitmap()))
            val score = score(ocr)
            if (score > bestScore) {
                bestScore = score
                best = ocr
            }
            if (readsWell(ocr)) break
        }
        return (best ?: OcrResult(emptyList())).lines.map { it.copy(page = page) }
    }

    /** Text yield dominates; confidence only breaks/ties near-equal yields. */
    private fun score(result: OcrResult): Double {
        val chars = result.allLines.sumOf { it.length }
        if (chars == 0) return 0.0
        val confidence = result.lines.mapNotNull { it.confidence }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.5
        return chars * (0.5 + 0.5 * confidence.coerceIn(0.0, 1.0))
    }

    private fun readsWell(result: OcrResult): Boolean {
        val chars = result.allLines.sumOf { it.length }
        if (chars < GOOD_PAGE_CHARS) return false
        val confidences = result.lines.mapNotNull { it.confidence }
        return confidences.isEmpty() || confidences.average() >= 0.5
    }

    private fun rawTextOf(lines: List<OcrLine>): String =
        lines.groupBy { it.page }.toSortedMap().values
            .joinToString("\n\n") { page -> page.joinToString("\n") { it.text } }

    companion object {
        /**
         * Below this many characters per page (excluding a fully empty read,
         * which fails outright) the read is reported as poor — the document
         * stays reviewable with an honest warning and a retry button.
         */
        const val MIN_CHARS_PER_PAGE = 20

        /** Average line confidence under this is an honest low-quality signal. */
        const val LOW_CONFIDENCE = 0.45

        /** A candidate reading this thin is worth trying the next candidate. */
        const val GOOD_PAGE_CHARS = 160
    }
}
