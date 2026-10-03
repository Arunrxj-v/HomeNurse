package com.homenurse.domain.repository

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Authentication abstraction.
 *
 * Authentication exists ONLY for account identity and session management
 * (sign-in, model entitlement, device/session security). It is deliberately
 * decoupled from medical storage, which is always device-local: the auth
 * server receives account fields (id, username, email, provider) and never
 * any medical data — see the network isolation tests.
 *
 * Implementations talk to the HomeNurse backend (the `/auth/` family); there is no
 * local/fake username-password authentication.
 */

/** Authenticated account identity — no medical fields, ever. */
@Serializable
data class Account(
    val id: String,
    val username: String,
    val email: String,
    /** Authentication provider: "local" (username+password) or "google". */
    val provider: String,
) {
    /** Display name for greetings: username (never an email unless that is all there is). */
    val displayName: String get() = username.ifBlank { email }
}

sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val account: Account) : AuthState
}

/** Normalised authentication failures surfaced to the UI. */
enum class AuthError {
    INVALID_CREDENTIALS,
    USERNAME_TAKEN,
    EMAIL_TAKEN,
    WEAK_PASSWORD,
    INVALID_INPUT,
    RATE_LIMITED,
    SESSION_EXPIRED,
    /** Device has no connectivity (offline / cannot resolve the server). */
    NETWORK,
    /** Connected, but the server could not be reached (down, refused, reset). */
    SERVER_UNREACHABLE,
    /** The server did not answer in time. */
    TIMEOUT,
    /** TLS handshake/certificate failure (details stay in debug logs only). */
    TLS_ERROR,
    /** The server answered with an error status. */
    SERVER,
    /** The server answered 2xx but the payload could not be used. */
    INVALID_RESPONSE,
    GOOGLE_NOT_CONFIGURED,
    GOOGLE_UNAVAILABLE,
    GOOGLE_INVALID,
    UNKNOWN,
}

sealed interface AuthResult {
    /** Operation completed (forgot/reset/delete/logout-style calls). */
    data object Success : AuthResult
    /** Authentication completed: [account] is now the signed-in account. */
    data class SignedIn(val account: Account) : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}

interface AuthRepository {
    /** Current session state; [AuthState.SignedIn] whenever a session exists. */
    val state: StateFlow<AuthState>

    /** POST /auth/register */
    suspend fun register(username: String, email: String, password: String): AuthResult

    /** POST /auth/login */
    suspend fun signIn(usernameOrEmail: String, password: String): AuthResult

    /** POST /auth/google — [idToken] comes from Credential Manager, [nonce] is the raw nonce. */
    suspend fun signInWithGoogle(idToken: String, nonce: String): AuthResult

    /**
     * POST /auth/refresh when the access token has expired.
     * Returns true when a valid session is (still) available. Network
     * failures keep the existing session (offline must not log the user out);
     * a rejected refresh token clears it.
     */
    suspend fun refreshSession(): Boolean

    /** POST /auth/forgot-password — always reports success (no user enumeration). */
    suspend fun forgotPassword(email: String): AuthResult

    /** POST /auth/reset-password with the emailed single-use token. */
    suspend fun resetPassword(token: String, newPassword: String): AuthResult

    /**
     * POST /auth/delete-account. [password] is required for local accounts.
     * NEVER deletes local medical data — that is a separate, explicit action.
     */
    suspend fun deleteAccount(password: String?): AuthResult

    /**
     * Clears the local session immediately and revokes the refresh token
     * server-side when possible. Medical data on the device is untouched.
     */
    suspend fun signOut()

    /** The signed-in account, restored from the secure session store. */
    suspend fun currentAccount(): Account?
}
