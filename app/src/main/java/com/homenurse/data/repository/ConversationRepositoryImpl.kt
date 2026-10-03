package com.homenurse.data.repository

import com.homenurse.data.local.database.dao.AnalysisDao
import com.homenurse.data.local.database.dao.ConversationDao
import com.homenurse.data.local.database.entity.AiAnalysisEntity
import com.homenurse.data.local.database.entity.ConversationEntity
import com.homenurse.data.local.database.entity.ConversationMessageEntity
import com.homenurse.data.local.database.entity.EvidenceEntity
import com.homenurse.domain.model.AiAnalysis
import com.homenurse.domain.model.Conversation
import com.homenurse.domain.model.ConversationMessage
import com.homenurse.domain.model.ConversationRole
import com.homenurse.domain.model.SafetyLevel
import com.homenurse.domain.repository.ConversationRepository
import com.homenurse.domain.util.Clock
import com.homenurse.domain.util.IdGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

class ConversationRepositoryImpl(
    private val conversationDao: ConversationDao,
    private val analysisDao: AnalysisDao,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val defaultTitle: String,
) : ConversationRepository {

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeConversations().map { list -> list.map { it.toDomain() } }

    override fun observeMessages(conversationId: String): Flow<List<ConversationMessage>> =
        conversationDao.observeMessages(conversationId).map { list -> list.map { it.toDomain() } }

    override suspend fun getOrCreateActive(): Conversation {
        // Deterministic single-thread chat: reuse the canonical conversation.
        conversationDao.getConversation(CHAT_ID)?.let {
            return Conversation(it.id, it.title, it.updatedAt)
        }
        val now = clock.now()
        val conversation = ConversationEntity(
            id = CHAT_ID,
            title = defaultTitle,
            createdAt = now,
            updatedAt = now,
        )
        conversationDao.upsertConversation(conversation)
        return Conversation(conversation.id, conversation.title, conversation.updatedAt)
    }

    override suspend fun addMessage(
        conversationId: String,
        role: ConversationRole,
        content: String,
        safetyLevel: SafetyLevel?,
    ): ConversationMessage {
        val now = clock.now()
        val entity = ConversationMessageEntity(
            id = ids.newId(),
            conversationId = conversationId,
            role = role,
            content = content,
            safetyLevel = safetyLevel,
            createdAt = now,
        )
        conversationDao.insertMessage(entity)
        conversationDao.touch(conversationId, now)
        return entity.toDomain()
    }

    override suspend fun saveAnalysis(analysis: AiAnalysis) {
        analysisDao.insertAnalysis(
            AiAnalysisEntity(
                id = analysis.id,
                kind = analysis.kind,
                question = analysis.question,
                documentId = analysis.documentId,
                summary = analysis.summary,
                explanation = analysis.explanation,
                warnings = analysis.warnings,
                suggestedNextSteps = analysis.suggestedNextSteps,
                uncertainty = analysis.uncertainty,
                requiresClinician = analysis.requiresClinician,
                safetyLevel = analysis.safetyLevel,
                createdAt = analysis.createdAt,
            ),
        )
        if (analysis.evidence.isNotEmpty()) {
            analysisDao.insertEvidence(
                analysis.evidence.map {
                    EvidenceEntity(
                        id = it.id,
                        analysisId = analysis.id,
                        factId = it.factId,
                        documentId = it.documentId,
                        label = it.label,
                        sourceText = it.sourceText,
                    )
                },
            )
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeAnalysisForDocument(documentId: String): Flow<List<AiAnalysis>> =
        analysisDao.observeForDocument(documentId).flatMapLatest { list ->
            kotlinx.coroutines.flow.flow {
                val withEvidence = list.map { entity ->
                    entity.toDomainWith(analysisDao.getEvidence(entity.id))
                }
                emit(withEvidence)
            }
        }

    companion object {
        const val CHAT_ID = "main-chat"
    }
}
