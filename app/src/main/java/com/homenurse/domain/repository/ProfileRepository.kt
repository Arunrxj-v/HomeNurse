package com.homenurse.domain.repository

import com.homenurse.domain.model.Allergy
import com.homenurse.domain.model.Condition
import com.homenurse.domain.model.LabResult
import com.homenurse.domain.model.Patient
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    fun observePatient(): Flow<Patient?>
    suspend fun saveProfile(
        displayName: String,
        dateOfBirth: String?,
        sex: String?,
        emergencyRegion: String?,
    )

    fun observeConditions(): Flow<List<Condition>>
    fun observeAllergies(): Flow<List<Allergy>>
    fun observeLabResults(): Flow<List<LabResult>>

    suspend fun upsertConditionFromFact(factId: String, name: String, sourceDocumentId: String)
    suspend fun upsertAllergyFromFact(factId: String, name: String, sourceDocumentId: String)
    suspend fun upsertLabFromFact(
        factId: String,
        testName: String,
        value: String,
        unit: String?,
        referenceRange: String?,
        sourceDocumentId: String,
    )
    suspend fun removeDerived(factId: String)
}
