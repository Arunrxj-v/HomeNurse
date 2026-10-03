package com.homenurse.core.auth

import com.homenurse.domain.repository.Account
import com.homenurse.domain.repository.AuthError
import com.homenurse.domain.repository.AuthRepository
import com.homenurse.domain.repository.AuthResult
import com.homenurse.domain.repository.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Server-backed authentication against the HomeNurse backend (the `/auth/`
 * family of endpoints).
 *
 * This is the ONLY authentication implementation: there is no local/fake
 * username-password login. Credentials are exchanged for short-lived access
 * tokens + rotating refresh tokens, which [SessionManager] stores in
 * Keystore-encrypted storage.
 *
 * Nothing medical is ever passed in or out of this class — only account
 * identity fields (username, email, provider, ids) and auth tokens.
 */
class RemoteAuthRepository(
    private val sessionManager: SessionManager,
    private val api: AuthApi,
) : AuthRepository {

    private val _state = MutableStateFlow(readState())

    override val state: StateFlow<AuthState> = _state.asStateFlow()

    override suspend fun register(
        username: String,
        email: String,
        password: String,
    ): AuthResult = authenticate { api.register(username.trim(), email.trim(), password) }

    override suspend fun signIn(usernameOrEmail: String, password: String): AuthResult =
        authenticate { api.login(usernameOrEmail.trim(), password) }

    override suspend fun signInWithGoogle(idToken: String, nonce: String): AuthResult =
        authenticate { api.googleSignIn(idToken, nonce) }

    override suspend fun refreshSession(): Boolean = when (val access = sessionManager.ensureAccess()) {
        is SessionManager.Access.Available -> {
            _state.value = sessionManager.account()
                ?.let { AuthState.SignedIn(it) } ?: AuthState.SignedOut
            true
        }
        // Unreachable server: a stored session stays valid — offline must not
        // sign the user out.
        SessionManager.Access.Offline -> sessionManager.hasSession()
        SessionManager.Access.Rejected, SessionManager.Access.SignedOut -> {
            _state.value = AuthState.SignedOut
            false
        }
    }

    override suspend fun forgotPassword(email: String): AuthResult =
        when (val response = api.forgotPassword(email.trim())) {
            is AuthApi.Response.Success -> AuthResult.Success
            is AuthApi.Response.Transport -> AuthResult.Failure(mapTransport(response.kind))
            is AuthApi.Response.Rejected -> AuthResult.Failure(mapRejection(response))
        }

    override suspend fun resetPassword(token: String, newPassword: String): AuthResult =
        when (val response = api.resetPassword(token.trim(), newPassword)) {
            is AuthApi.Response.Success -> AuthResult.Success
            is AuthApi.Response.Transport -> AuthResult.Failure(mapTransport(response.kind))
            is AuthApi.Response.Rejected -> AuthResult.Failure(mapRejection(response))
        }

    override suspend fun deleteAccount(password: String?): AuthResult {
        val accessToken = sessionManager.validAccessToken()
            ?: return AuthResult.Failure(AuthError.SESSION_EXPIRED)
        return when (val response = api.deleteAccount(accessToken, password)) {
            is AuthApi.Response.Success -> {
                // Account gone from the server: local session goes too.
                // Local medical data is intentionally untouched (spec §19).
                sessionManager.clear()
                _state.value = AuthState.SignedOut
                AuthResult.Success
            }
            is AuthApi.Response.Transport -> AuthResult.Failure(mapTransport(response.kind))
            is AuthApi.Response.Rejected -> AuthResult.Failure(mapRejection(response))
        }
    }

    override suspend fun signOut() {
        // Best-effort server-side revocation; local sign-out never depends on it.
        sessionManager.currentRefreshToken()?.let { refreshToken ->
            runCatching { api.logout(refreshToken) }
        }
        sessionManager.clear()
        _state.value = AuthState.SignedOut
    }

    override suspend fun currentAccount(): Account? = sessionManager.account()

    // --- internals -----------------------------------------------------------

    private suspend fun authenticate(call: suspend () -> AuthApi.Response): AuthResult {
        if (sessionManager.isRefreshExpired() && sessionManager.hasSession()) {
            sessionManager.clear()
        }
        return when (val response = call()) {
            is AuthApi.Response.Success -> {
                val account = response.account
                val tokens = response.tokens
                if (account == null || tokens == null) {
                    // 2xx but the body had no usable account/tokens: a payload
                    // problem, not "the server is down" — never a fake success.
                    AuthResult.Failure(AuthError.INVALID_RESPONSE)
                } else {
                    sessionManager.save(
                        SessionManager.Session(
                            account = account,
                            accessToken = tokens.accessToken,
                            accessExpiresAt = tokens.accessExpiresAt,
                            refreshToken = tokens.refreshToken,
                            refreshExpiresAt = tokens.refreshExpiresAt,
                        ),
                    )
                    _state.value = AuthState.SignedIn(account)
                    AuthResult.SignedIn(account)
                }
            }
            is AuthApi.Response.Transport -> AuthResult.Failure(mapTransport(response.kind))
            is AuthApi.Response.Rejected -> AuthResult.Failure(mapRejection(response))
        }
    }

    private fun mapTransport(kind: AuthApi.TransportKind): AuthError = when (kind) {
        AuthApi.TransportKind.OFFLINE -> AuthError.NETWORK
        AuthApi.TransportKind.UNREACHABLE -> AuthError.SERVER_UNREACHABLE
        AuthApi.TransportKind.TIMEOUT -> AuthError.TIMEOUT
        AuthApi.TransportKind.TLS -> AuthError.TLS_ERROR
    }

    private fun mapRejection(response: AuthApi.Response.Rejected): AuthError {
        val status = response.status
        val code = response.code
        return when {
            status == 429 || code == "rate_limited" -> AuthError.RATE_LIMITED
            code == "invalid_credentials" || code == "unauthorized" && status == 401 ->
                AuthError.INVALID_CREDENTIALS
            code == "username_taken" -> AuthError.USERNAME_TAKEN
            code == "email_taken" -> AuthError.EMAIL_TAKEN
            code == "weak_password" -> AuthError.WEAK_PASSWORD
            code == "invalid_input" || code == "invalid_token" -> AuthError.INVALID_INPUT
            code == "google_not_configured" -> AuthError.GOOGLE_NOT_CONFIGURED
            code == "invalid_google_token" -> AuthError.GOOGLE_INVALID
            status == 401 -> AuthError.SESSION_EXPIRED
            // 403/404/405 mean the endpoint refused or is missing — a
            // server-side problem, surfaced with the same simple copy.
            status == 403 || status == 404 || status == 405 || status in 500..599 ->
                AuthError.SERVER
            else -> AuthError.UNKNOWN
        }
    }

    private fun readState(): AuthState =
        sessionManager.account()?.let { AuthState.SignedIn(it) } ?: AuthState.SignedOut
}
