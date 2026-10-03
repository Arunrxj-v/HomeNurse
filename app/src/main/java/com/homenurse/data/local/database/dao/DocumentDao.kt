package com.homenurse.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.homenurse.data.local.database.entity.MedicalDocumentEntity
import com.homenurse.data.local.database.entity.MedicalFactEntity
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.ProcessingStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM medical_documents ORDER BY createdAt DESC")
    fun observeDocuments(): Flow<List<MedicalDocumentEntity>>

    @Query("SELECT * FROM medical_documents")
    suspend fun getAllDocuments(): List<MedicalDocumentEntity>

    @Query("SELECT * FROM medical_documents WHERE id = :id")
    fun observeDocument(id: String): Flow<MedicalDocumentEntity?>

    @Query("SELECT * FROM medical_documents WHERE id = :id")
    suspend fun getDocument(id: String): MedicalDocumentEntity?

    @Insert
    suspend fun insertDocument(document: MedicalDocumentEntity)

    @Query("DELETE FROM medical_documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Query(
        """
        UPDATE medical_documents
        SET status = :status, failureReason = :failureReason, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(
        id: String,
        status: ProcessingStatus,
        failureReason: String?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE medical_documents
        SET extractedText = :text, normalizedText = :normalizedText,
            pageCount = :pageCount, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateExtractedText(
        id: String,
        text: String,
        normalizedText: String?,
        pageCount: Int,
        updatedAt: Long,
    )

    @Query("UPDATE medical_documents SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)
}

@Dao
interface FactDao {

    @Query("SELECT * FROM medical_facts WHERE documentId = :documentId ORDER BY createdAt ASC")
    fun observeFacts(documentId: String): Flow<List<MedicalFactEntity>>

    @Query("SELECT * FROM medical_facts WHERE id = :id")
    suspend fun getFact(id: String): MedicalFactEntity?

    @Query("SELECT * FROM medical_facts WHERE status = :status")
    suspend fun getFactsByStatus(status: FactStatus): List<MedicalFactEntity>

    @Query("SELECT COUNT(*) FROM medical_facts WHERE documentId = :documentId AND status = 'PENDING'")
    fun observePendingCount(documentId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM medical_facts WHERE documentId = :documentId AND status = 'PENDING'")
    suspend fun countPending(documentId: String): Int

    @Query("SELECT * FROM medical_facts")
    suspend fun getAllFacts(): List<MedicalFactEntity>

    @Query("SELECT COUNT(*) FROM medical_facts WHERE documentId = :documentId AND status = 'CONFIRMED'")
    suspend fun countConfirmed(documentId: String): Int

    @Insert
    suspend fun insertFacts(facts: List<MedicalFactEntity>)

    @Update
    suspend fun updateFact(fact: MedicalFactEntity)

    @Query("DELETE FROM medical_facts WHERE documentId = :documentId AND status = 'PENDING'")
    suspend fun deletePendingFacts(documentId: String)
}
