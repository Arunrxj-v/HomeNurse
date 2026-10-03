package com.homenurse.ui.careplan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homenurse.HomeNurseApp
import com.homenurse.domain.model.CarePlan
import com.homenurse.domain.model.CareTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CarePlanViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as HomeNurseApp).container

    val plan: StateFlow<CarePlan?> =
        container.carePlanRepository.observePlan()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    fun toggle(task: CareTask) = launch { container.carePlanRepository.setCompleted(task.id, !task.completed) }

    fun addUserTask(title: String, timeOfDayMin: Int?) = launch {
        container.carePlanRepository.addUserTask(title.trim(), timeOfDayMin)
    }

    fun deleteUserTask(taskId: String) = launch {
        container.carePlanRepository.deleteUserTask(taskId)
    }

    fun regenerate() = launch { container.generateCarePlan() }

    private fun launch(block: suspend () -> Unit) {
        if (_working.value) return
        viewModelScope.launch {
            try {
                block()
            } finally {
                _working.value = false
            }
        }
    }
}
