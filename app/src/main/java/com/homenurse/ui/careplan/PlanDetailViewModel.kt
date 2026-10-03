package com.homenurse.ui.careplan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.homenurse.HomeNurseApp
import com.homenurse.domain.model.CareTask
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.Medication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Detail state for a single care-plan step. The task itself is observed
 * live from the plan; the medication and fact behind it are loaded once
 * from Room (both stay on this device).
 */
class PlanDetailViewModel(
    application: Application,
    private val taskId: String,
) : AndroidViewModel(application) {

    private val container = (application as HomeNurseApp).container

    val task: StateFlow<CareTask?> =
        container.carePlanRepository.observePlan()
            .map { plan -> plan?.tasks?.firstOrNull { it.id == taskId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _medication = MutableStateFlow<Medication?>(null)
    val medication: StateFlow<Medication?> = _medication.asStateFlow()

    private val _fact = MutableStateFlow<MedicalFact?>(null)
    val fact: StateFlow<MedicalFact?> = _fact.asStateFlow()

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    init {
        viewModelScope.launch {
            val current = task.value
                ?: container.carePlanRepository.observePlan()
                    .firstOrNull { plan -> plan != null && plan.tasks.any { it.id == taskId } }
                    ?.tasks?.firstOrNull { it.id == taskId }
            val factId = current?.factId ?: return@launch
            _fact.value = container.factRepository.getFact(factId)
            _medication.value = container.medicationRepository.all()
                .firstOrNull { it.factId == factId }
        }
    }

    fun toggle() {
        val current = task.value ?: return
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                container.carePlanRepository.setCompleted(current.id, !current.completed)
            } finally {
                _working.value = false
            }
        }
    }

    fun deleteSelf() {
        val current = task.value ?: return
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                container.carePlanRepository.deleteUserTask(current.id)
            } finally {
                _working.value = false
            }
        }
    }

    companion object {
        fun factory(taskId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    @Suppress("UNCHECKED_CAST")
                    return PlanDetailViewModel(extras[APPLICATION_KEY] as Application, taskId) as T
                }
            }
    }
}
