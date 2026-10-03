package com.homenurse.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homenurse.HomeNurseApp
import com.homenurse.ai.ModelStatus
import com.homenurse.domain.model.CarePlan
import com.homenurse.domain.model.CareTask
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.model.Medication
import com.homenurse.domain.model.ProcessingStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Read-only aggregates for the home dashboard. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as HomeNurseApp).container

    val documents: StateFlow<List<MedicalDocument>> =
        container.documentRepository.observeDocuments()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingReviewCount: StateFlow<Int> = documents.map { list ->
        list.count { it.status == ProcessingStatus.REVIEW_REQUIRED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val medicationCount: StateFlow<Int> =
        container.medicationRepository.observeMedications()
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Confirmed medicines (for the home medication preview). */
    val medications: StateFlow<List<Medication>> =
        container.medicationRepository.observeMedications()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Current care plan (for the home care-plan preview and progress). */
    val carePlan: StateFlow<CarePlan?> =
        container.carePlanRepository.observePlan()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val openTasks: StateFlow<List<CareTask>> =
        container.carePlanRepository.observePlan()
            .map { plan -> plan?.tasks.orEmpty().filter { !it.completed } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val modelStatus: StateFlow<ModelStatus> = container.modelManager.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelStatus.NotInstalled)

    val patientName: StateFlow<String?> =
        container.profileRepository.observePatient()
            .map { it?.displayName?.takeIf { name -> name.isNotBlank() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
