package com.homenurse.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homenurse.HomeNurseApp
import com.homenurse.R
import com.homenurse.domain.model.ConversationMessage
import com.homenurse.domain.usecase.AiErrorKind
import com.homenurse.domain.usecase.AskHomeNurseUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Ask HomeNurse chat: single canonical thread stored in the encrypted local
 * database. Sending runs the full pipeline (safety gate → local model →
 * safety gate → persist); a running generation can be cancelled, which also
 * aborts the native inference call.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as HomeNurseApp).container

    private val conversationId = MutableStateFlow<String?>(null)

    val messages: StateFlow<List<ConversationMessage>> = conversationId
        .filterNotNull()
        .flatMapLatest { id -> container.conversationRepository.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _errorRes = MutableStateFlow<Int?>(null)
    val errorRes: StateFlow<Int?> = _errorRes.asStateFlow()

    private var askJob: Job? = null

    init {
        viewModelScope.launch {
            conversationId.value = container.conversationRepository.getOrCreateActive().id
        }
    }

    fun updateInput(value: String) {
        _input.value = value
    }

    fun consumeError() {
        _errorRes.value = null
    }

    fun send() {
        val question = _input.value.trim()
        if (question.isEmpty() || _sending.value) return
        _input.value = ""
        _errorRes.value = null

        askJob = viewModelScope.launch {
            _sending.value = true
            try {
                when (val outcome = container.askHomeNurse(question)) {
                    is AskHomeNurseUseCase.Outcome.Reply,
                    is AskHomeNurseUseCase.Outcome.Safety,
                    -> Unit // Persisted — the message flow updates the UI.

                    is AskHomeNurseUseCase.Outcome.AiError -> _errorRes.value =
                        when (outcome.kind) {
                            AiErrorKind.MODEL_NOT_READY -> R.string.ai_model_not_ready
                            AiErrorKind.MALFORMED_RESPONSE -> R.string.ai_malformed
                            AiErrorKind.INFERENCE -> R.string.ai_inference_error
                        }
                }
            } finally {
                _sending.value = false
            }
        }
    }

    fun stop() {
        askJob?.cancel()
    }
}
