package com.homenurse.domain.repository

import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.MedicalFact
import kotlinx.coroutines.flow.Flow

/**
 * Extracted medical facts. Facts enter as PENDING candidates from OCR
 * extraction and only become trusted after explicit user confirmation.
 */
interface FactRepository {
    fun observeFacts(documentId: String): Flow<List<MedicalFact>>
    fun observePendingCount(documentId: String): Flow<Int>
    suspend fun pendingCount(documentId: String): Int
    suspend fun getFact(id: String): MedicalFact?
    suspend fun addFacts(facts: List<MedicalFact>)
    suspend fun setStatus(id: String, status: FactStatus)
    suspend fun updateFields(
        id: String,
        name: String?,
        dose: String?,
        frequency: String?,
        timing: String?,
        duration: String?,
        value: String,
    )
    suspend fun confirmedFacts(): List<MedicalFact>

    /**
     * Remove not-yet-confirmed candidates (used when a document is re-read).
     * Confirmed facts are never touched — user decisions survive a re-read.
     */
    suspend fun deletePending(documentId: String)
}
