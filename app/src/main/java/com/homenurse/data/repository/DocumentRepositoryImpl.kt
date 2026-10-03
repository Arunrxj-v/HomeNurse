package com.homenurse.data.repository

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.homenurse.core.logging.PrivacyLog
import com.homenurse.data.local.database.HomeNurseDatabase
import com.homenurse.data.local.database.dao.DocumentDao
import com.homenurse.data.local.database.entity.MedicalDocumentEntity
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.document.DocumentStorage
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.repository.DocumentRepository
import com.homenurse.domain.util.Clock
import com.homenurse.domain.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream
import java.io.IOException

class DocumentRepositoryImpl(
    private val database: HomeNurseDatabase,
    private val documentDao: DocumentDao,
    private val documentStorage: DocumentStorage,
    private val contentResolver: ContentResolver,
    private val clock: Clock,
    private val ids: IdGenerator,
) : DocumentRepository {

    override fun observeDocuments(): Flow<List<MedicalDocument>> =
        documentDao.observeDocuments().map { list -> list.map { it.toDomain() } }

    override fun observeDocument(id: String): Flow<MedicalDocument?> =
        documentDao.observeDocument(id).map { it?.toDomain() }

    override suspend fun importFromUri(uri: Uri): String {
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val bytes = readBounded(contentResolver.openInputStream(uri))
        return store(mime = mime, title = titleFromUri(uri), bytes = bytes)
    }

    override suspend fun storeCapture(title: String, bytes: ByteArray): String =
        store(mime = "image/jpeg", title = title, bytes = bytes)

    override suspend fun openBytes(documentId: String): ByteArray {
        val document = documentDao.getDocument(documentId)
            ?: throw IOException("Document not found")
        return documentStorage.read(document.storagePath)
    }

    override suspend fun setStatus(id: String, status: ProcessingStatus, failureReason: String?) {
        documentDao.updateStatus(id, status, failureReason, clock.now())
    }

    override suspend fun updateExtractedText(
        id: String,
        text: String,
        normalizedText: String?,
        pageCount: Int,
    ) {
        documentDao.updateExtractedText(id, text, normalizedText, pageCount, clock.now())
    }

    override suspend fun deleteDocument(id: String) {
        val document = documentDao.getDocument(id)
        database.withTransaction {
            documentDao.deleteDocument(id)
        }
        document?.let { runCatching { documentStorage.delete(it.storagePath) } }
        PrivacyLog.event("document_deleted")
    }

    override suspend fun getDocument(id: String): MedicalDocument? =
        documentDao.getDocument(id)?.toDomain()

    private suspend fun store(mime: String, title: String, bytes: ByteArray): String {
        val documentId = ids.newId()
        val stored = documentStorage.save(documentId, bytes)
        val now = clock.now()
        documentDao.insertDocument(
            MedicalDocumentEntity(
                id = documentId,
                title = title.ifBlank { defaultTitle(mime) },
                mimeType = normalizeMime(mime),
                storagePath = stored.fileName,
                sizeBytes = stored.sizeBytes,
                pageCount = if (isPdf(mime)) 0 else 1,
                status = ProcessingStatus.CAPTURED,
                extractedText = null,
                failureReason = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        PrivacyLog.event("document_saved")
        return documentId
    }

    private fun readBounded(stream: java.io.InputStream?): ByteArray {
        requireNotNull(stream) { "Cannot open document" }
        stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read == -1) break
                total += read
                if (total > MAX_DOCUMENT_BYTES) {
                    throw DocumentRepository.ImportTooLargeException()
                }
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }

    private fun titleFromUri(uri: Uri): String {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
        }
        return name ?: defaultTitle(contentResolver.getType(uri) ?: "")
    }

    private fun defaultTitle(mime: String): String =
        if (isPdf(mime)) "Document.pdf" else "Document.jpg"

    private fun isPdf(mime: String) = mime.equals("application/pdf", ignoreCase = true)

    private fun normalizeMime(mime: String): String = when {
        mime.equals("image/jpg", ignoreCase = true) -> "image/jpeg"
        else -> mime
    }

    companion object {
        /** 50 MB: larger files are refused with a clear message. */
        const val MAX_DOCUMENT_BYTES = 50L * 1024 * 1024
    }
}
