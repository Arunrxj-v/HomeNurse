package com.homenurse.domain.repository

import com.homenurse.domain.model.Medication
import kotlinx.coroutines.flow.Flow

/**
 * Medications derived from user-CONFIRMED medication facts.
 * Nothing here is ever inferred or invented — every row traces to a fact.
 */
interface MedicationRepository {
    fun observeMedications(): Flow<List<Medication>>
    suspend fun all(): List<Medication>
    suspend fun upsertFromFact(factId: String)
    suspend fun removeForFact(factId: String)
}
