package com.homenurse.ui.medicines

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homenurse.HomeNurseApp
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.Medication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Confirmed medications — read-only; changes happen via document review.
 * Opening a medication loads its source fact (verbatim doctor text) and
 * source document title, both read locally from Room.
 */
class MedicinesViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as HomeNurseApp).container

    val medications: StateFlow<List<Medication>> =
        container.medicationRepository.observeMedications()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _detail = MutableStateFlow<Medication?>(null)
    val detail: StateFlow<Medication?> = _detail.asStateFlow()

    private val _detailFact = MutableStateFlow<MedicalFact?>(null)
    val detailFact: StateFlow<MedicalFact?> = _detailFact.asStateFlow()

    private val _detailDocumentTitle = MutableStateFlow<String?>(null)
    val detailDocumentTitle: StateFlow<String?> = _detailDocumentTitle.asStateFlow()

    fun openDetail(medication: Medication) {
        _detail.value = medication
        _detailFact.value = null
        _detailDocumentTitle.value = null
        viewModelScope.launch {
            _detailFact.value = container.factRepository.getFact(medication.factId)
            _detailDocumentTitle.value = container.documentRepository
                .getDocument(medication.documentId)?.title
        }
    }

    fun closeDetail() {
        _detail.value = null
        _detailFact.value = null
        _detailDocumentTitle.value = null
    }
}
