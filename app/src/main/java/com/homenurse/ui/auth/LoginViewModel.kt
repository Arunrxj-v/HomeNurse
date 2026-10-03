package com.homenurse.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homenurse.HomeNurseApp
import com.homenurse.core.config.ServerConfig
import com.homenurse.domain.repository.AuthError
import com.homenurse.domain.repository.AuthResult
import com.homenurse.domain.repository.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which authentication form is on screen. */
enum class AuthMode { SIGN_IN, REGISTER, FORGOT }

/** One-shot informational notices (never error text, never secrets). */
enum class AuthNotice { RESET_CODE_SENT, RESET_COMPLETED }

/**
 * Drives sign-in (username/email + password), account creation, forgot/reset
 * password and "Continue with Google".
 *
 * All credential handling happens against the HomeNurse backend via
 * [com.homenurse.domain.repository.AuthRepository]; passwords are never
 * stored locally and no medical data is involved anywhere in this flow.
 */
class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as HomeNurseApp).container

    private val _mode = MutableStateFlow(AuthMode.SIGN_IN)
    val mode: StateFlow<AuthMode> = _mode.asStateFlow()

    private val _identifier = MutableStateFlow("")
    val identifier: StateFlow<String> = _identifier.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _resetEmail = MutableStateFlow("")
    val resetEmail: StateFlow<String> = _resetEmail.asStateFlow()

    private val _resetCode = MutableStateFlow("")
    val resetCode: StateFlow<String> = _resetCode.asStateFlow()

    private val _newPassword = MutableStateFlow("")
    val newPassword: StateFlow<String> = _newPassword.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<AuthError?>(null)
    val error: StateFlow<AuthError?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<AuthNotice?>(null)
    val notice: StateFlow<AuthNotice?> = _notice.asStateFlow()

    val authState: StateFlow<AuthState> = container.authRepository.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.SignedOut)

    // --- field updates -------------------------------------------------------

    fun setMode(mode: AuthMode) {
        _mode.value = mode
        _error.value = null
        _notice.value = null
    }

    fun onIdentifierChange(value: String) {
        _identifier.value = value
        clearError()
        _notice.value = null
    }
    fun onUsernameChange(value: String) { _username.value = value; clearError() }
    fun onEmailChange(value: String) { _email.value = value; clearError() }
    fun onPasswordChange(value: String) { _password.value = value; clearError() }
    fun onResetEmailChange(value: String) { _resetEmail.value = value; clearError() }
    fun onResetCodeChange(value: String) { _resetCode.value = value; clearError() }
    fun onNewPasswordChange(value: String) { _newPassword.value = value; clearError() }

    fun clearError() { _error.value = null }

    fun consumeNotice() { _notice.value = null }

    // --- actions -------------------------------------------------------------

    fun signIn(onSuccess: () -> Unit) {
        val identifier = _identifier.value.trim()
        val password = _password.value
        if (identifier.isEmpty() || password.isEmpty()) return
        launchAuth(onSuccess) { container.authRepository.signIn(identifier, password) }
    }

    fun register(onSuccess: () -> Unit) {
        val username = _username.value.trim()
        val email = _email.value.trim()
        val password = _password.value
        if (username.isEmpty() || email.isEmpty() || password.isEmpty()) return
        launchAuth(onSuccess) { container.authRepository.register(username, email, password) }
    }

    fun signInWithGoogle(onSuccess: () -> Unit) {
        viewModelScope.launch {
            if (_busy.value) return@launch
            _busy.value = true
            _error.value = null
            _notice.value = null
            try {
                when (
                    val google = container.googleIdentityProvider
                        .signIn(ServerConfig.GOOGLE_WEB_CLIENT_ID)
                ) {
                    is com.homenurse.core.auth.GoogleIdentityProvider.Result.Success -> {
                        when (
                            val result = container.authRepository
                                .signInWithGoogle(google.idToken, google.nonce)
                        ) {
                            is AuthResult.SignedIn -> onSuccess()
                            is AuthResult.Failure -> _error.value = result.error
                            AuthResult.Success -> _error.value = AuthError.UNKNOWN
                        }
                    }
                    com.homenurse.core.auth.GoogleIdentityProvider.Result.Cancelled ->
                        _notice.value = null
                    is com.homenurse.core.auth.GoogleIdentityProvider.Result.Unavailable ->
                        _error.value = AuthError.GOOGLE_UNAVAILABLE
                    com.homenurse.core.auth.GoogleIdentityProvider.Result.Failed ->
                        _error.value = AuthError.GOOGLE_UNAVAILABLE
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun sendResetCode() {
        val email = _resetEmail.value.trim()
        if (email.isEmpty()) return
        viewModelScope.launch {
            if (_busy.value) return@launch
            _busy.value = true
            _error.value = null
            try {
                when (val result = container.authRepository.forgotPassword(email)) {
                    AuthResult.Success, is AuthResult.SignedIn ->
                        _notice.value = AuthNotice.RESET_CODE_SENT
                    is AuthResult.Failure -> _error.value = result.error
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun completeReset() {
        val code = _resetCode.value.trim()
        val newPassword = _newPassword.value
        if (code.isEmpty() || newPassword.isEmpty()) return
        viewModelScope.launch {
            if (_busy.value) return@launch
            _busy.value = true
            _error.value = null
            try {
                when (val result = container.authRepository.resetPassword(code, newPassword)) {
                    AuthResult.Success -> {
                        _notice.value = AuthNotice.RESET_COMPLETED
                        _mode.value = AuthMode.SIGN_IN
                        _resetCode.value = ""
                        _newPassword.value = ""
                        _password.value = ""
                    }
                    is AuthResult.Failure -> _error.value = result.error
                    is AuthResult.SignedIn -> _mode.value = AuthMode.SIGN_IN
                }
            } finally {
                _busy.value = false
            }
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun launchAuth(onSuccess: () -> Unit, call: suspend () -> AuthResult) {
        viewModelScope.launch {
            if (_busy.value) return@launch
            _busy.value = true
            _error.value = null
            try {
                when (val result = call()) {
                    AuthResult.Success, is AuthResult.SignedIn -> onSuccess()
                    is AuthResult.Failure -> _error.value = result.error
                }
            } finally {
                _busy.value = false
            }
        }
    }
}
