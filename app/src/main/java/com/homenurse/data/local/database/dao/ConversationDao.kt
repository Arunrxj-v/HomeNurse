package com.homenurse.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.homenurse.data.local.database.entity.AiAnalysisEntity
import com.homenurse.data.local.database.entity.ConversationEntity
import com.homenurse.data.local.database.entity.ConversationMessageEntity
import com.homenurse.data.local.database.entity.EvidenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations")
    suspend fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversation_messages ORDER BY createdAt ASC")
    suspend fun getAllMessages(): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query(
        """
        UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id
        """,
    )
    suspend fun touch(id: String, updatedAt: Long)

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :id ORDER BY createdAt ASC")
    fun observeMessages(id: String): Flow<List<ConversationMessageEntity>>

    @Insert
    suspend fun insertMessage(message: ConversationMessageEntity)

    @Query("DELETE FROM conversation_messages WHERE conversationId = :id")
    suspend fun deleteMessages(id: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)
}

@Dao
interface AnalysisDao {

    @Query("SELECT * FROM ai_analyses WHERE documentId = :documentId ORDER BY createdAt DESC")
    fun observeForDocument(documentId: String): Flow<List<AiAnalysisEntity>>

    @Query("SELECT * FROM ai_analyses")
    suspend fun getAllAnalyses(): List<AiAnalysisEntity>

    @Query("SELECT * FROM evidence")
    suspend fun getAllEvidence(): List<EvidenceEntity>

    @Query("SELECT * FROM ai_analyses WHERE id = :id")
    suspend fun getAnalysis(id: String): AiAnalysisEntity?

    @Insert
    suspend fun insertAnalysis(analysis: AiAnalysisEntity)

    @Insert
    suspend fun insertEvidence(evidence: List<EvidenceEntity>)

    @Query("SELECT * FROM evidence WHERE analysisId = :analysisId")
    suspend fun getEvidence(analysisId: String): List<EvidenceEntity>

    @Query("DELETE FROM ai_analyses WHERE documentId = :documentId")
    suspend fun deleteForDocument(documentId: String)
}
