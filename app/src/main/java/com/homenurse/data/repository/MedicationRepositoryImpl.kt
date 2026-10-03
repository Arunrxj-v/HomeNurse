package com.homenurse.data.repository

import com.homenurse.data.local.database.dao.FactDao
import com.homenurse.data.local.database.dao.MedicationDao
import com.homenurse.data.local.database.entity.MedicationEntity
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.Medication
import com.homenurse.domain.repository.MedicationRepository
import com.homenurse.domain.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Creates medication rows ONLY from user-confirmed medication facts.
 * Every field comes verbatim from the fact — nothing is inferred here.
 */
class MedicationRepositoryImpl(
    private val medicationDao: MedicationDao,
    private val factDao: FactDao,
    private val clock: Clock,
) : MedicationRepository {

    override fun observeMedications(): Flow<List<Medication>> =
        medicationDao.observeMedications().map { list -> list.map { it.toDomain() } }

    override suspend fun all(): List<Medication> = medicationDao.getAll().map { it.toDomain() }

    override suspend fun upsertFromFact(factId: String) {
        val fact = factDao.getFact(factId) ?: return
        if (fact.type != FactType.MEDICATION || fact.status != FactStatus.CONFIRMED) return
        val name = fact.name?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val existing = medicationDao.getByFact(factId)
        medicationDao.upsertMedication(
            MedicationEntity(
                id = existing?.id ?: "med:$factId",
                factId = factId,
                documentId = fact.documentId,
                name = name,
                dose = fact.dose,
                frequency = fact.frequency,
                timing = fact.timing,
                duration = fact.duration,
                confirmedByUser = true,
                createdAt = existing?.createdAt ?: clock.now(),
                updatedAt = clock.now(),
            ),
        )
    }

    override suspend fun removeForFact(factId: String) {
        medicationDao.deleteByFact(factId)
    }
}
