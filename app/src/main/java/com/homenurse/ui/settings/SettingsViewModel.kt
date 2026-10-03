package com.homenurse.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homenurse.HomeNurseApp
import com.homenurse.R
import com.homenurse.ai.ModelStatus
import com.homenurse.domain.repository.AuthResult
import com.homenurse.domain.repository.AuthState
import com.homenurse.reminder.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class StorageUsage(val documentBytes: Long, val modelBytes: Long)

/**
 * Settings state: real model status/version, real storage usage (file
 * sizes), reminder toggle, export, the genuine delete-all-medical-data
 * operation, and account/session actions (sign out, delete account).
 *
 * Signing out and deleting the account only affect authentication state —
 * local medical data is a separate, explicitly-chosen action.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Application = application
    private val container = (application as HomeNurseApp).container

    val modelStatus: StateFlow<ModelStatus> = container.modelManager.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelStatus.NotInstalled)

    val authState: StateFlow<AuthState> = container.authRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthState.SignedOut)

    private val _storage = MutableStateFlow(StorageUsage(0L, 0L))
    val storage: StateFlow<StorageUsage> = _storage.asStateFlow()

    private val _remindersEnabled = MutableStateFlow(
        container.secureStorage.getString(ReminderScheduler.KEY_ENABLED)?.toBoolean() ?: true,
    )
    val remindersEnabled: StateFlow<Boolean> = _remindersEnabled.asStateFlow()

    private val _dataDeleted = MutableStateFlow(false)
    val dataDeleted: StateFlow<Boolean> = _dataDeleted.asStateFlow()

    private val _accountActionMessage = MutableStateFlow<Int?>(null)
    val accountActionMessage: StateFlow<Int?> = _accountActionMessage.asStateFlow()

    val installedVersion: String? get() = container.modelManager.installedModel()?.version

    val appVersion: String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "1.0.0"

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val docs = runCatching { container.documentStorage.totalBytes() }.getOrDefault(0L)
            val models = File(appContext.filesDir, "models")
                .walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
            _storage.value = StorageUsage(docs, models)
        }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        container.secureStorage.putString(
            ReminderScheduler.KEY_ENABLED,
            enabled.toString(),
        )
        _remindersEnabled.value = enabled
    }

    fun deleteModel() {
        viewModelScope.launch {
            container.modelManager.deleteModel()
            refreshStorage()
        }
    }

    fun retryModelInit() {
        viewModelScope.launch { container.modelManager.retryInitialize() }
    }

    /** Continues a paused model download (partial file is resumed). */
    fun resumeModelDownload() {
        viewModelScope.launch { container.modelManager.downloadAndInstall() }
    }

    fun export(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val json = runCatching { container.exportMedicalData.exportJson() }
                .getOrDefault("")
            onResult(json)
        }
    }

    /** Deletes local medical data only. Never touches the account/session. */
    fun deleteAllMedicalData() {
        viewModelScope.launch {
            container.deleteAllMedicalData()
            _dataDeleted.value = true
            refreshStorage()
        }
    }

    fun consumeDeleted() {
        _dataDeleted.value = false
    }

    // --- account / session ---------------------------------------------------

    /**
     * Signs out: clears the app session and auth tokens (and revokes the
     * refresh token server-side when reachable). Local medical data is NOT
     * deleted — it belongs to the device vault, not the session.
     */
    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            container.googleIdentityProvider.clearCredentialState()
            container.authRepository.signOut()
            _accountActionMessage.value = R.string.settings_signed_out
            onSignedOut()
        }
    }

    /**
     * Deletes the HomeNurse account on the server. [password] confirms the
     * action for username/password accounts (Google accounts may omit it).
     * Explicitly does NOT delete local medical data.
     */
    fun deleteAccount(password: String?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            when (container.authRepository.deleteAccount(password)) {
                AuthResult.Success, is AuthResult.SignedIn -> {
                    container.googleIdentityProvider.clearCredentialState()
                    _accountActionMessage.value = R.string.settings_delete_account_done
                    onDone(true)
                }
                is AuthResult.Failure -> onDone(false)
            }
        }
    }

    fun consumeAccountMessage() {
        _accountActionMessage.value = null
    }
}
