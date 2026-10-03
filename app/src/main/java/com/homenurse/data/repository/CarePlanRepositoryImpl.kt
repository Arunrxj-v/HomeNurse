package com.homenurse.data.repository

import androidx.room.withTransaction
import com.homenurse.data.local.database.HomeNurseDatabase
import com.homenurse.data.local.database.dao.CarePlanDao
import com.homenurse.data.local.database.entity.CarePlanEntity
import com.homenurse.data.local.database.entity.CareTaskEntity
import com.homenurse.domain.model.CarePlan
import com.homenurse.domain.model.CareTask
import com.homenurse.domain.repository.CarePlanRepository
import com.homenurse.domain.util.Clock
import com.homenurse.domain.util.IdGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

class CarePlanRepositoryImpl(
    private val database: HomeNurseDatabase,
    private val carePlanDao: CarePlanDao,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val defaultTitle: String,
) : CarePlanRepository {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observePlan(): Flow<CarePlan?> =
        carePlanDao.observeLatestPlan().flatMapLatest { plan ->
            if (plan == null) {
                kotlinx.coroutines.flow.flowOf(null)
            } else {
                carePlanDao.observeTasks(plan.id).map { tasks ->
                    plan.withTasks(tasks.map { it.toDomain() })
                }
            }
        }

    override fun observeTasks(planId: String): Flow<List<CareTask>> =
        carePlanDao.observeTasks(planId).map { list -> list.map { it.toDomain() } }

    override suspend fun ensurePlan(): CarePlan {
        carePlanDao.getLatestPlan()?.let { plan ->
            val current = carePlanDao.getAllTasks().filter { it.planId == plan.id }
            return plan.withTasks(current.map { it.toDomain() })
        }
        val plan = CarePlanEntity(
            id = ids.newId(),
            title = defaultTitle,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )
        carePlanDao.upsertPlan(plan)
        return CarePlan(plan.id, plan.title, emptyList())
    }

    override suspend fun replaceGeneratedTasks(planId: String, tasks: List<CareTask>) {
        database.withTransaction {
            carePlanDao.deleteGeneratedTasks(planId)
            carePlanDao.insertTasks(tasks.mapIndexed { index, task -> task.toEntity(planId, index) })
            carePlanDao.getLatestPlan()?.let {
                carePlanDao.upsertPlan(it.copy(updatedAt = clock.now()))
            }
        }
    }

    override suspend fun setCompleted(taskId: String, completed: Boolean) {
        carePlanDao.setCompleted(taskId, completed, if (completed) clock.now() else null)
    }

    override suspend fun setTime(taskId: String, timeOfDayMin: Int) {
        carePlanDao.setTime(taskId, timeOfDayMin)
    }

    override suspend fun addUserTask(title: String, timeOfDayMin: Int?): CareTask {
        val plan = ensurePlan()
        val task = CareTask(
            id = ids.newId(),
            planId = plan.id,
            title = title.trim(),
            kind = com.homenurse.domain.model.TaskKind.USER,
            source = com.homenurse.domain.model.TaskSource.USER,
            timeOfDayMin = timeOfDayMin,
            dueDate = null,
            documentId = null,
            factId = null,
            completed = false,
        )
        carePlanDao.insertTasks(listOf(task.toEntity(plan.id, Int.MAX_VALUE - 1)))
        return task
    }

    override suspend fun updateUserTask(taskId: String, title: String, timeOfDayMin: Int?) {
        carePlanDao.updateUserTask(taskId, title.trim(), timeOfDayMin)
    }

    override suspend fun deleteUserTask(taskId: String) {
        carePlanDao.deleteUserTask(taskId)
    }

    private fun CareTask.toEntity(planId: String, order: Int) = CareTaskEntity(
        id = id,
        planId = planId,
        title = title,
        kind = kind,
        source = source,
        timeOfDayMin = timeOfDayMin,
        dueDate = dueDate,
        documentId = documentId,
        factId = factId,
        completed = completed,
        completedAt = null,
        sortOrder = order,
    )
}
