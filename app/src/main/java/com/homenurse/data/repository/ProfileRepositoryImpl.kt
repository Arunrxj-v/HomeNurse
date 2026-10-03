package com.homenurse.data.repository

import com.homenurse.data.local.database.dao.PatientDao
import com.homenurse.data.local.database.entity.AllergyEntity
import com.homenurse.data.local.database.entity.ConditionEntity
import com.homenurse.data.local.database.entity.LabResultEntity
import com.homenurse.data.local.database.entity.PatientEntity
import com.homenurse.domain.model.Allergy
import com.homenurse.domain.model.Condition
import com.homenurse.domain.model.LabResult
import com.homenurse.domain.model.Patient
import com.homenurse.domain.repository.ProfileRepository
import com.homenurse.domain.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProfileRepositoryImpl(
    private val patientDao: PatientDao,
    private val clock: Clock,
) : ProfileRepository {

    override fun observePatient(): Flow<Patient?> =
        patientDao.observePatient(PATIENT_ID).map { it?.toDomain() }

    override suspend fun saveProfile(
        displayName: String,
        dateOfBirth: String?,
        sex: String?,
        emergencyRegion: String?,
    ) {
        val existing = patientDao.getPatient(PATIENT_ID)
        patientDao.upsertPatient(
            PatientEntity(
                id = PATIENT_ID,
                displayName = displayName.trim(),
                dateOfBirth = dateOfBirth,
                sex = sex,
                emergencyRegion = emergencyRegion,
                createdAt = existing?.createdAt ?: clock.now(),
                updatedAt = clock.now(),
            ),
        )
    }

    override fun observeConditions(): Flow<List<Condition>> =
        patientDao.observeConditions().map { list -> list.map { it.toDomain() } }

    override fun observeAllergies(): Flow<List<Allergy>> =
        patientDao.observeAllergies().map { list -> list.map { it.toDomain() } }

    override fun observeLabResults(): Flow<List<LabResult>> =
        patientDao.observeLabResults().map { list -> list.map { it.toDomain() } }

    override suspend fun upsertConditionFromFact(
        factId: String,
        name: String,
        sourceDocumentId: String,
    ) {
        patientDao.upsertCondition(
            ConditionEntity(
                id = "condition:$factId",
                factId = factId,
                name = name,
                sourceDocumentId = sourceDocumentId,
                confirmedByUser = true,
                createdAt = clock.now(),
            ),
        )
    }

    override suspend fun upsertAllergyFromFact(
        factId: String,
        name: String,
        sourceDocumentId: String,
    ) {
        patientDao.upsertAllergy(
            AllergyEntity(
                id = "allergy:$factId",
                factId = factId,
                name = name,
                sourceDocumentId = sourceDocumentId,
                confirmedByUser = true,
                createdAt = clock.now(),
            ),
        )
    }

    override suspend fun upsertLabFromFact(
        factId: String,
        testName: String,
        value: String,
        unit: String?,
        referenceRange: String?,
        sourceDocumentId: String,
    ) {
        patientDao.upsertLabResult(
            LabResultEntity(
                id = "lab:$factId",
                factId = factId,
                testName = testName,
                value = value,
                unit = unit,
                referenceRange = referenceRange,
                sourceDocumentId = sourceDocumentId,
                confirmedByUser = true,
                createdAt = clock.now(),
            ),
        )
    }

    override suspend fun removeDerived(factId: String) {
        patientDao.deleteConditionForFact(factId)
        patientDao.deleteAllergyForFact(factId)
        patientDao.deleteLabResultForFact(factId)
    }

    companion object {
        const val PATIENT_ID = "local"
    }
}
