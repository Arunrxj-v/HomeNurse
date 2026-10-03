package com.homenurse.testing

import android.net.Uri
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.domain.repository.DocumentRepository
import com.homenurse.domain.repository.FactRepository
import com.homenurse.domain.util.Clock
import com.homenurse.domain.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** Deterministic clock for instrumented pipeline tests. */
class FixedClock(private val value: Long = 1_700_000_000_000L) : Clock {
    override fun now(): Long = value
}

/** Sequential, unique ids. */
class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var counter = 0
    override fun newId(): String = "$prefix-${counter++}"
}

/** In-memory document store: metadata + bytes, records status transitions. */
class InMemDocumentRepository : DocumentRepository {

    private val state = MutableStateFlow<List<MedicalDocument>>(emptyList())
    private val bytes = mutableMapOf<String, ByteArray>()

    /** (status, failureReason) for every setStatus call, in order. */
    val statusLog = mutableListOf<Pair<ProcessingStatus, String?>>()

    fun put(document: MedicalDocument, payload: ByteArray) {
        state.value = state.value.filterNot { it.id == document.id } + document
        bytes[document.id] = payload
    }

    override fun observeDocuments(): Flow<List<MedicalDocument>> = state.asStateFlow()

    override fun observeDocument(id: String): Flow<MedicalDocument?> =
        state.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun importFromUri(uri: Uri): String =
        throw UnsupportedOperationException("not used in instrumented tests")

    override suspend fun storeCapture(title: String, payload: ByteArray): String =
        throw UnsupportedOperationException("not used in instrumented tests")

    override suspend fun openBytes(documentId: String): ByteArray =
        bytes[documentId] ?: throw java.io.FileNotFoundException(documentId)

    override suspend fun setStatus(id: String, status: ProcessingStatus, failureReason: String?) {
        statusLog += status to failureReason
        state.value = state.value.map {
            if (it.id == id) it.copy(status = status, failureReason = failureReason) else it
        }
    }

    override suspend fun updateExtractedText(
        id: String,
        text: String,
        normalizedText: String?,
        pageCount: Int,
    ) {
        state.value = state.value.map {
            if (it.id == id) {
                it.copy(
                    extractedText = text,
                    normalizedText = normalizedText,
                    pageCount = pageCount,
                )
            } else it
        }
    }

    override suspend fun deleteDocument(id: String) {
        state.value = state.value.filterNot { it.id == id }
        bytes.remove(id)
    }

    override suspend fun getDocument(id: String): MedicalDocument? =
        state.value.firstOrNull { it.id == id }
}

/** In-memory fact store. */
class InMemFactRepository : FactRepository {

    private val state = MutableStateFlow<List<MedicalFact>>(emptyList())

    val all: List<MedicalFact> get() = state.value

    override fun observeFacts(documentId: String): Flow<List<MedicalFact>> =
        state.map { list -> list.filter { it.documentId == documentId } }

    override fun observePendingCount(documentId: String): Flow<Int> =
        observeFacts(documentId).map { list -> list.count { it.status == FactStatus.PENDING } }

    override suspend fun pendingCount(documentId: String): Int =
        state.value.count { it.documentId == documentId && it.status == FactStatus.PENDING }

    override suspend fun getFact(id: String): MedicalFact? =
        state.value.firstOrNull { it.id == id }

    override suspend fun addFacts(facts: List<MedicalFact>) {
        val incoming = facts.associateBy { it.id }
        state.value = state.value.filterNot { it.id in incoming.keys } + facts
    }

    override suspend fun setStatus(id: String, status: FactStatus) {
        state.value = state.value.map { if (it.id == id) it.copy(status = status) else it }
    }

    override suspend fun updateFields(
        id: String,
        name: String?,
        dose: String?,
        frequency: String?,
        timing: String?,
        duration: String?,
        value: String,
    ) {
        state.value = state.value.map {
            if (it.id == id) {
                it.copy(
                    name = name, dose = dose, frequency = frequency,
                    timing = timing, duration = duration, value = value,
                )
            } else it
        }
    }

    override suspend fun confirmedFacts(): List<MedicalFact> =
        state.value.filter { it.status == FactStatus.CONFIRMED }

    override suspend fun deletePending(documentId: String) {
        state.value = state.value.filterNot {
            it.documentId == documentId && it.status == FactStatus.PENDING
        }
    }
}
