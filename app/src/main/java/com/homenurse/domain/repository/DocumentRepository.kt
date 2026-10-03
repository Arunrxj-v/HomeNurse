package com.homenurse.domain.repository

import android.net.Uri
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.model.ProcessingStatus
import kotlinx.coroutines.flow.Flow

/**
 * Owns document metadata and the encrypted file vault.
 * All imports (camera capture, image, PDF) are processed on-device only.
 */
interface DocumentRepository {

    fun observeDocuments(): Flow<List<MedicalDocument>>
    fun observeDocument(id: String): Flow<MedicalDocument?>

    /** Import an image or PDF picked with the system document picker. */
    suspend fun importFromUri(uri: Uri): String

    /** Store a camera capture (already encoded as JPEG bytes). */
    suspend fun storeCapture(title: String, bytes: ByteArray): String

    /** Decrypted document bytes (viewer / OCR). Never written back in clear. */
    suspend fun openBytes(documentId: String): ByteArray

    suspend fun setStatus(id: String, status: ProcessingStatus, failureReason: String? = null)

    /** Stores raw OCR text plus the separately normalized representation. */
    suspend fun updateExtractedText(id: String, text: String, normalizedText: String?, pageCount: Int)

    /** Deletes the encrypted file and (via FK cascade) all derived rows. */
    suspend fun deleteDocument(id: String)

    suspend fun getDocument(id: String): MedicalDocument?

    /** Thrown when an import exceeds the size limit (see DocumentRepositoryImpl). */
    class ImportTooLargeException : java.io.IOException("Document exceeds the size limit")
}
