package com.homenurse.ui.documents

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homenurse.HomeNurseApp
import com.homenurse.document.DocumentProcessor
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** User-visible, resource-backed error categories (never raw exception text). */
enum class DocumentError { TOO_LARGE, STORAGE, UNKNOWN }

/**
 * Document list operations: import, camera capture, delete — plus kicking
 * off the on-device pipeline (OCR + extraction) after each ingest.
 */
class DocumentsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as HomeNurseApp).container

    val documents: StateFlow<List<MedicalDocument>> =
        container.documentRepository.observeDocuments()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Per-page read progress of the active OCR run (null when idle). */
    val processingProgress: StateFlow<DocumentProcessor.OcrProgress?> =
        container.documentProcessor.progress

    private val _error = MutableStateFlow<DocumentError?>(null)
    val error: StateFlow<DocumentError?> = _error.asStateFlow()

    fun consumeError() {
        _error.value = null
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val id = container.documentRepository.importFromUri(uri)
                container.documentProcessor.process(id)
            } catch (error: DocumentRepository.ImportTooLargeException) {
                _error.value = DocumentError.TOO_LARGE
            } catch (error: Exception) {
                _error.value = DocumentError.STORAGE
            } finally {
                _busy.value = false
            }
        }
    }

    fun storeCapture(title: String, bytes: ByteArray) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val id = container.documentRepository.storeCapture(title, bytes)
                container.documentProcessor.process(id)
            } catch (error: Exception) {
                _error.value = DocumentError.STORAGE
            } finally {
                _busy.value = false
            }
        }
    }

    fun delete(documentId: String) {
        viewModelScope.launch {
            runCatching { container.documentRepository.deleteDocument(documentId) }
                .onFailure { _error.value = DocumentError.STORAGE }
        }
    }
}
