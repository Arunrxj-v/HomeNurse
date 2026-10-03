package com.homenurse.data.local.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.homenurse.data.local.database.entity.AllergyEntity
import com.homenurse.data.local.database.entity.ConditionEntity
import com.homenurse.data.local.database.entity.LabResultEntity
import com.homenurse.data.local.database.entity.PatientEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {

    @Query("SELECT * FROM patient WHERE id = :id")
    fun observePatient(id: String): Flow<PatientEntity?>

    @Query("SELECT * FROM patient WHERE id = :id")
    suspend fun getPatient(id: String): PatientEntity?

    @Upsert
    suspend fun upsertPatient(patient: PatientEntity)

    @Query("SELECT * FROM conditions ORDER BY createdAt DESC")
    fun observeConditions(): Flow<List<ConditionEntity>>

    @Query("SELECT * FROM conditions")
    suspend fun getAllConditions(): List<ConditionEntity>

    @Upsert
    suspend fun upsertCondition(condition: ConditionEntity)

    @Query("DELETE FROM conditions WHERE factId = :factId")
    suspend fun deleteConditionForFact(factId: String)

    @Query("SELECT * FROM allergies ORDER BY createdAt DESC")
    fun observeAllergies(): Flow<List<AllergyEntity>>

    @Query("SELECT * FROM allergies")
    suspend fun getAllAllergies(): List<AllergyEntity>

    @Upsert
    suspend fun upsertAllergy(allergy: AllergyEntity)

    @Query("DELETE FROM allergies WHERE factId = :factId")
    suspend fun deleteAllergyForFact(factId: String)

    @Query("SELECT * FROM lab_results ORDER BY createdAt DESC")
    fun observeLabResults(): Flow<List<LabResultEntity>>

    @Query("SELECT * FROM lab_results")
    suspend fun getAllLabResults(): List<LabResultEntity>

    @Upsert
    suspend fun upsertLabResult(labResult: LabResultEntity)

    @Query("DELETE FROM lab_results WHERE factId = :factId")
    suspend fun deleteLabResultForFact(factId: String)
}
