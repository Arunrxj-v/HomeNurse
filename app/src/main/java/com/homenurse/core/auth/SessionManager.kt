package com.homenurse.core.auth

import com.homenurse.core.logging.PrivacyLog
import com.homenurse.core.security.SecureStorage
import com.homenurse.domain.repository.Account
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Owns the authenticated session on the device.
 *
 * Responsibilities: save / retrieve / refresh / clear the session, detect
 * expiry, and expose a currently-valid access token.
 *
 * Storage: the session blob is written through [SecureStorage], whose values
 * are encrypted with an Android Keystore-held master key — tokens are never
 * stored in plaintext SharedPreferences. The blob itself contains only
 * account identity + tokens (no medical data of any kind).
 *
 * Refresh policy: an expired access token triggers a refresh with the stored
 * refresh token. A *transport* failure never destroys the session (offline
 * restarts must keep the user signed in); a rejected refresh token (HTTP 401)
 * clears the session because the credential is genuinely invalid.
 */
class SessionManager(
    private val storage: SecureStorage,
    private val api: AuthApi,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    @Serializable
    data class Session(
        val account: Account,
        val accessToken: String,
        val accessExpiresAt: Long,
        val refreshToken: String,
        val refreshExpiresAt: Long,
    )

    /** Outcome of "give me a usable access token". */
    sealed interface Access {
        data class Available(val token: String) : Access
        /** Server unreachable — the stored session is kept as-is. */
        data object Offline : Access
        /** Server rejected the refresh token — session cleared. */
        data object Rejected : Access
        /** No stored session at all. */
        data object SignedOut : Access
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var session: Session? = null

    init {
        session = readStored()
    }

    /** Restore the stored session blob; a corrupt/unreadable blob means signed out. */
    private fun readStored(): Session? = runCatching {
        val raw = storage.getString(KEY_SESSION) ?: return@runCatching null
        json.decodeFromString(Session.serializer(), raw)
    }.getOrNull()

    fun hasSession(): Boolean = session != null

    fun account(): Account? = session?.account

    /** Raw refresh token for logout/revocation (never leaves the auth client). */
    fun currentRefreshToken(): String? = session?.refreshToken

    fun isRefreshExpired(): Boolean {
        val current = session ?: return true
        return nowMillis() >= current.refreshExpiresAt
    }

    fun isAccessExpired(): Boolean {
        val current = session ?: return true
        return nowMillis() >= current.accessExpiresAt
    }

    fun save(newSession: Session) {
        session = newSession
        storage.putString(KEY_SESSION, json.encodeToString(Session.serializer(), newSession))
    }

    /** Locally destroy the session (logout, or a server-rejected refresh). */
    fun clear() {
        session = null
        storage.remove(KEY_SESSION)
    }

    /** A usable access token, refreshing if expired (null when unavailable). */
    suspend fun validAccessToken(): String? =
        (ensureAccess() as? Access.Available)?.token

    /** Refresh if needed and report what happened (never throws on 4xx/5xx). */
    suspend fun ensureAccess(): Access = mutex.withLock {
        val current = session ?: return Access.SignedOut
        if (nowMillis() < current.accessExpiresAt) return Access.Available(current.accessToken)
        if (nowMillis() >= current.refreshExpiresAt) {
            clear()
            return Access.SignedOut
        }
        return when (val response = api.refresh(current.refreshToken)) {
            is AuthApi.Response.Success -> {
                val tokens = response.tokens
                if (tokens == null) {
                    clear()
                    Access.Rejected
                } else {
                    val refreshed = Session(
                        account = response.account ?: current.account,
                        accessToken = tokens.accessToken,
                        accessExpiresAt = tokens.accessExpiresAt,
                        refreshToken = tokens.refreshToken,
                        refreshExpiresAt = tokens.refreshExpiresAt,
                    )
                    save(refreshed)
                    Access.Available(refreshed.accessToken)
                }
            }
            is AuthApi.Response.Rejected -> {
                PrivacyLog.event("session_refresh_rejected")
                clear()
                Access.Rejected
            }
            is AuthApi.Response.Transport -> Access.Offline
        }
    }

    companion object {
        const val KEY_SESSION = "auth.session"
    }
}
