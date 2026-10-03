package com.homenurse.ui.documents

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import com.homenurse.HomeNurseApp
import com.homenurse.R
import com.homenurse.document.DocumentProcessor
import com.homenurse.domain.model.AiAnalysis
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.usecase.AiErrorKind
import com.homenurse.domain.usecase.ExplainDocumentUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the document explanation action (never faked). */
sealed interface ExplainState {
    data object Idle : ExplainState
    data object Loading : ExplainState
    data class Error(val messageRes: Int) : ExplainState
    data class SafetyBlocked(val message: String) : ExplainState
    data object Success : ExplainState
}

class DocumentDetailViewModel(
    application: Application,
    val documentId: String,
) : AndroidViewModel(application) {

    private val container = (application as HomeNurseApp).container

    val document: StateFlow<MedicalDocument?> =
        container.documentRepository.observeDocument(documentId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val facts: StateFlow<List<MedicalFact>> =
        container.factRepository.observeFacts(documentId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val analyses: StateFlow<List<AiAnalysis>> =
        container.conversationRepository.observeAnalysisForDocument(documentId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _explainState = MutableStateFlow<ExplainState>(ExplainState.Idle)
    val explainState: StateFlow<ExplainState> = _explainState.asStateFlow()

    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    /** Per-page read progress while the document is being (re)read. */
    val processingProgress: StateFlow<DocumentProcessor.OcrProgress?> =
        container.documentProcessor.progress

    init {
        viewModelScope.launch { loadPreview() }
    }

    /**
     * Re-run the on-device pipeline (preprocess → OCR → extract). Used when
     * the first read failed or was too uncertain to be useful. Previously
     * confirmed facts are never removed by a re-read.
     */
    fun retry() {
        if (_retrying.value) return
        _retrying.value = true
        viewModelScope.launch {
            try {
                container.documentProcessor.process(documentId)
            } finally {
                _retrying.value = false
            }
        }
    }

    private suspend fun loadPreview() {
        val bitmap = runCatching {
            val doc = container.documentRepository.getDocument(documentId) ?: return@runCatching null
            val bytes = container.documentRepository.openBytes(documentId)
            container.documentImporter.renderPage(bytes, doc.mimeType, 0)
        }.getOrNull()
        if (bitmap != null) _preview.value = bitmap
    }

    fun explain() {
        if (_explainState.value is ExplainState.Loading) return
        _explainState.value = ExplainState.Loading
        viewModelScope.launch {
            _explainState.value = when (val outcome = container.explainDocument(documentId)) {
                is ExplainDocumentUseCase.Outcome.Success -> ExplainState.Success
                is ExplainDocumentUseCase.Outcome.SafetyBlocked ->
                    ExplainState.SafetyBlocked(outcome.message)
                is ExplainDocumentUseCase.Outcome.AiError -> ExplainState.Error(
                    when (outcome.kind) {
                        AiErrorKind.MODEL_NOT_READY -> R.string.ai_model_not_ready
                        AiErrorKind.MALFORMED_RESPONSE -> R.string.ai_malformed
                        AiErrorKind.INFERENCE -> R.string.ai_inference_error
                    },
                )
                ExplainDocumentUseCase.Outcome.NotFound -> ExplainState.Error(R.string.explain_not_found)
                ExplainDocumentUseCase.Outcome.NotConfirmed ->
                    ExplainState.Error(R.string.explain_needs_confirmation)
            }
        }
    }

    fun consumeExplainResult() {
        if (_explainState.value !is ExplainState.Loading) {
            _explainState.value = ExplainState.Idle
        }
    }

    companion object {
        fun factory(documentId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    @Suppress("UNCHECKED_CAST")
                    return DocumentDetailViewModel(extras[APPLICATION_KEY] as Application, documentId) as T
                }
            }
    }
}
