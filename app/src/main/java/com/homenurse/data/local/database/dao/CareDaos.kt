package com.homenurse.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.homenurse.data.local.database.entity.CarePlanEntity
import com.homenurse.data.local.database.entity.CareTaskEntity
import com.homenurse.data.local.database.entity.MedicationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationDao {

    @Query("SELECT * FROM medications ORDER BY name COLLATE NOCASE ASC")
    fun observeMedications(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getMedication(id: String): MedicationEntity?

    @Query("SELECT * FROM medications WHERE factId = :factId")
    suspend fun getByFact(factId: String): MedicationEntity?

    @Upsert
    suspend fun upsertMedication(medication: MedicationEntity)

    @Query("DELETE FROM medications WHERE factId = :factId")
    suspend fun deleteByFact(factId: String)

    @Query("SELECT * FROM medications")
    suspend fun getAll(): List<MedicationEntity>
}

@Dao
interface CarePlanDao {

    @Query("SELECT * FROM care_plans ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestPlan(): Flow<CarePlanEntity?>

    @Query("SELECT * FROM care_plans ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestPlan(): CarePlanEntity?

    @Upsert
    suspend fun upsertPlan(plan: CarePlanEntity)

    @Query("SELECT * FROM care_tasks WHERE planId = :planId ORDER BY sortOrder ASC, id ASC")
    fun observeTasks(planId: String): Flow<List<CareTaskEntity>>

    @Query("SELECT * FROM care_tasks ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllTasks(): List<CareTaskEntity>

    @Insert
    suspend fun insertTasks(tasks: List<CareTaskEntity>)

    @Query("DELETE FROM care_tasks WHERE planId = :planId AND source != 'USER'")
    suspend fun deleteGeneratedTasks(planId: String)

    @Query("DELETE FROM care_tasks WHERE factId = :factId")
    suspend fun deleteTasksForFact(factId: String)

    @Query(
        """
        UPDATE care_tasks
        SET completed = :completed, completedAt = :completedAt
        WHERE id = :taskId
        """,
    )
    suspend fun setCompleted(taskId: String, completed: Boolean, completedAt: Long?)

    @Query("UPDATE care_tasks SET timeOfDayMin = :timeOfDayMin WHERE id = :taskId")
    suspend fun setTime(taskId: String, timeOfDayMin: Int)

    @Query(
        """
        UPDATE care_tasks
        SET title = :title, timeOfDayMin = :timeOfDayMin
        WHERE id = :taskId AND source = 'USER'
        """,
    )
    suspend fun updateUserTask(taskId: String, title: String, timeOfDayMin: Int?)

    @Query("DELETE FROM care_tasks WHERE id = :taskId AND source = 'USER'")
    suspend fun deleteUserTask(taskId: String)

    @Query("DELETE FROM care_tasks WHERE planId = :planId AND source = 'USER'")
    suspend fun deleteUserTasks(planId: String)
}
