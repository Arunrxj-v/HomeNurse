package com.homenurse.domain.repository

import com.homenurse.domain.model.AiAnalysis
import com.homenurse.domain.model.Conversation
import com.homenurse.domain.model.ConversationMessage
import com.homenurse.domain.model.ConversationRole
import com.homenurse.domain.model.SafetyLevel
import kotlinx.coroutines.flow.Flow

/** Chat history lives entirely in the local encrypted database. */
interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<ConversationMessage>>
    suspend fun getOrCreateActive(): Conversation
    suspend fun addMessage(
        conversationId: String,
        role: ConversationRole,
        content: String,
        safetyLevel: SafetyLevel? = null,
    ): ConversationMessage
    suspend fun saveAnalysis(analysis: AiAnalysis)
    fun observeAnalysisForDocument(documentId: String): Flow<List<AiAnalysis>>
}
