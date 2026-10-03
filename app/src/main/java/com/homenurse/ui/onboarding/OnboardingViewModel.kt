package com.homenurse.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homenurse.HomeNurseApp
import com.homenurse.ai.ModelManifestEntry
import com.homenurse.ai.ModelStatus
import com.homenurse.domain.repository.AuthState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The nine-step product flow, grouped into six screens. */
enum class OnboardingStep { WELCOME, PRIVACY, LOCAL_AI, SIGN_IN, MODEL, READY }

/**
 * Drives onboarding. Every statement shown to the user (privacy, local AI,
 * download progress, verification, inference test) reflects real state — the
 * model step mirrors [com.homenurse.ai.ModelManager.status] directly; nothing
 * is simulated.
 *
 * Sign-in uses the real backend authentication form ([com.homenurse.ui.auth.AuthForm]);
 * the model download comes from the HomeNurse model server with no
 * third-party token prompt of any kind.
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as HomeNurseApp).container

    private val _step = MutableStateFlow(OnboardingStep.WELCOME)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    val authState: StateFlow<AuthState> = container.authRepository.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.SignedOut)

    val modelStatus: StateFlow<ModelStatus> = container.modelManager.status
        .stateIn(viewModelScope, SharingStarted.Eagerly, ModelStatus.NotInstalled)

    /** Model offered on this device (server-recommended when available). */
    val downloadableModel: ModelManifestEntry?
        get() = runCatching { container.modelManager.activeEntry() }.getOrNull()

    private var downloadJob: Job? = null

    fun onStepChange(step: OnboardingStep) {
        _step.value = step
    }

    fun next() {
        val order = OnboardingStep.entries
        val index = order.indexOf(_step.value)
        if (index < order.lastIndex) _step.value = order[index + 1]
    }

    fun back() {
        val order = OnboardingStep.entries
        val index = order.indexOf(_step.value)
        if (index > 0) _step.value = order[index - 1]
    }

    /** Download (or resume) the HomeNurse AI model from our model server. */
    fun startModelDownload() {
        _downloadError.value = null
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            container.modelManager.downloadAndInstall()
            val status = container.modelManager.status.value
            if (status is ModelStatus.Failed) {
                _downloadError.value = status.failure.name
            }
        }
    }

    /**
     * Pause the download: the partial file is kept and the next start
     * resumes from where it stopped (HTTP Range).
     */
    fun pauseModelDownload() {
        container.modelManager.requestPause()
        downloadJob?.cancel()
        downloadJob = null
    }

    fun retryModel() {
        _downloadError.value = null
        val status = container.modelManager.status.value
        viewModelScope.launch {
            when {
                status is ModelStatus.Failed &&
                    (status.failure == com.homenurse.ai.ModelFailure.INITIALIZATION ||
                        status.failure == com.homenurse.ai.ModelFailure.UNSUPPORTED_DEVICE) ->
                    container.modelManager.retryInitialize()
                status is ModelStatus.Paused -> container.modelManager.downloadAndInstall()
                status is ModelStatus.Failed ->
                    container.modelManager.downloadAndInstall()
                else -> Unit
            }
        }
    }

    fun finish() {
        container.secureStorage.putString(KEY_COMPLETED, "true")
    }

    val alreadyCompleted: Boolean
        get() = container.secureStorage.getString(KEY_COMPLETED) == "true"

    companion object {
        const val KEY_COMPLETED = "onboarding.completed"
    }
}
