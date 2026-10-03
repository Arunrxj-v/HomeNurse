package com.homenurse.domain.repository

import com.homenurse.domain.model.CarePlan
import com.homenurse.domain.model.CareTask
import kotlinx.coroutines.flow.Flow

interface CarePlanRepository {
    fun observePlan(): Flow<CarePlan?>
    fun observeTasks(planId: String): Flow<List<CareTask>>
    suspend fun ensurePlan(): CarePlan

    /** Replaces all machine-generated tasks, keeping user-added tasks. */
    suspend fun replaceGeneratedTasks(planId: String, tasks: List<CareTask>)

    suspend fun setCompleted(taskId: String, completed: Boolean)
    suspend fun setTime(taskId: String, timeOfDayMin: Int)
    suspend fun addUserTask(title: String, timeOfDayMin: Int?): CareTask
    suspend fun updateUserTask(taskId: String, title: String, timeOfDayMin: Int?)
    suspend fun deleteUserTask(taskId: String)
}
