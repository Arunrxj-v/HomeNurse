package com.homenurse.ui.documents

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.homenurse.HomeNurseApp
import com.homenurse.R
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.model.MedicalFact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Review screen model: confirm / edit-and-confirm / reject extracted facts.
 * Every action goes through [ConfirmFactsUseCase] — the single trust gate
 * after which data becomes "confirmed medical information".
 */
class FactReviewViewModel(
    application: Application,
    val documentId: String,
) : AndroidViewModel(application) {

    private val app: Application = application
    private val container = (application as HomeNurseApp).container

    val facts: StateFlow<List<MedicalFact>> =
        container.factRepository.observeFacts(documentId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val document: StateFlow<MedicalDocument?> =
        container.documentRepository.observeDocument(documentId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    fun confirm(factId: String) = runAction { container.confirmFacts.confirm(factId) }

    fun reject(factId: String) = runAction { container.confirmFacts.reject(factId) }

    /**
     * Manually entered item — the escape hatch when a document could not be
     * read. Goes through the same single trust gate ([ConfirmFactsUseCase
     * .addConfirmed]) as extracted facts, entering as user-asserted
     * CONFIRMED content with clear provenance.
     */
    fun addManualFact(
        type: FactType,
        name: String,
        value: String,
        dose: String,
        frequency: String,
    ) = runAction {
        val trimmedName = name.trim().ifBlank { null }
        val trimmedValue = value.trim().ifBlank { trimmedName }
        if (trimmedValue.isNullOrBlank()) return@runAction
        if (type == FactType.MEDICATION && trimmedName.isNullOrBlank()) return@runAction
        container.confirmFacts.addConfirmed(
            MedicalFact(
                id = container.ids.newId(),
                documentId = documentId,
                type = type,
                status = FactStatus.CONFIRMED,
                name = trimmedName,
                dose = dose.trim().ifBlank { null },
                frequency = frequency.trim().ifBlank { null },
                timing = null,
                duration = null,
                value = trimmedValue,
                sourceText = app.getString(R.string.review_source_manual),
                confidence = 1.0,
            ),
        )
    }

    fun confirmAllPending() = runAction {
        facts.value.filter { it.status == FactStatus.PENDING }
            .forEach { container.confirmFacts.confirm(it.id) }
    }

    fun editAndConfirm(
        factId: String,
        name: String?,
        dose: String?,
        frequency: String?,
        timing: String?,
        duration: String?,
        value: String,
    ) = runAction {
        container.confirmFacts.editAndConfirm(
            factId = factId,
            name = name,
            dose = dose,
            frequency = frequency,
            timing = timing,
            duration = duration,
            value = value,
        )
    }

    private fun runAction(action: suspend () -> Unit) {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                action()
            } finally {
                _working.value = false
            }
        }
    }

    companion object {
        fun factory(documentId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    @Suppress("UNCHECKED_CAST")
                    return FactReviewViewModel(extras[APPLICATION_KEY] as Application, documentId) as T
                }
            }
    }
}
