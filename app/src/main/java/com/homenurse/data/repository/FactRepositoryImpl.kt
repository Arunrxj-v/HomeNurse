package com.homenurse.data.repository

import com.homenurse.data.local.database.dao.FactDao
import com.homenurse.data.local.database.entity.MedicalFactEntity
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.repository.FactRepository
import com.homenurse.domain.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FactRepositoryImpl(
    private val factDao: FactDao,
    private val clock: Clock,
) : FactRepository {

    override fun observeFacts(documentId: String): Flow<List<MedicalFact>> =
        factDao.observeFacts(documentId).map { list -> list.map { it.toDomain() } }

    override fun observePendingCount(documentId: String): Flow<Int> =
        factDao.observePendingCount(documentId)

    override suspend fun pendingCount(documentId: String): Int =
        factDao.countPending(documentId)

    override suspend fun getFact(id: String): MedicalFact? = factDao.getFact(id)?.toDomain()

    override suspend fun addFacts(facts: List<MedicalFact>) {
        if (facts.isEmpty()) return
        factDao.insertFacts(facts.map { it.toEntity() })
    }

    override suspend fun setStatus(id: String, status: FactStatus) {
        val fact = factDao.getFact(id) ?: return
        factDao.updateFact(fact.copy(status = status, updatedAt = clock.now()))
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
        val fact = factDao.getFact(id) ?: return
        factDao.updateFact(
            fact.copy(
                name = name,
                dose = dose,
                frequency = frequency,
                timing = timing,
                duration = duration,
                value = value,
                updatedAt = clock.now(),
            ),
        )
    }

    override suspend fun confirmedFacts(): List<MedicalFact> =
        factDao.getFactsByStatus(FactStatus.CONFIRMED).map { it.toDomain() }

    override suspend fun deletePending(documentId: String) =
        factDao.deletePendingFacts(documentId)

    private fun MedicalFact.toEntity() = MedicalFactEntity(
        id = id,
        documentId = documentId,
        type = type,
        status = status,
        name = name,
        dose = dose,
        frequency = frequency,
        timing = timing,
        duration = duration,
        value = value,
        sourceText = sourceText,
        confidence = confidence,
        createdAt = clock.now(),
        updatedAt = clock.now(),
        route = route,
        instructions = instructions,
        unit = unit,
        referenceRange = referenceRange,
    )
}
