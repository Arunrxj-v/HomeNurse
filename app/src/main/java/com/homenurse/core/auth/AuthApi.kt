package com.homenurse.core.auth

import com.homenurse.core.config.ServerConfig
import com.homenurse.core.network.NetworkClient
import com.homenurse.domain.repository.Account
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Thin typed client for the HomeNurse authentication API.
 *
 * It is one of only two components allowed to touch [NetworkClient]
 * (the other is the model downloader). It sends ONLY account/session fields:
 * username, email, password, tokens, ids. Request bodies are built from
 * fixed request DTOs — no medical field can ever be attached, which is
 * asserted by the network isolation tests.
 *
 * Endpoints: /auth/register, /auth/login, /auth/google, /auth/refresh,
 * /auth/logout, /auth/forgot-password, /auth/reset-password, /auth/me,
 * /auth/delete-account.
 */
class AuthApi(
    private val network: NetworkClient,
    private val baseUrl: () -> String = { ServerConfig.API_BASE_URL },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // --- wire types (fixed shapes: nothing but account/session data) --------

    @Serializable
    data class AccountDto(
        val id: String? = null,
        val username: String? = null,
        val email: String? = null,
        val provider: String? = null,
    )

    @Serializable
    data class TokensDto(
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val accessTokenExpiresIn: Long? = null,
        val refreshTokenExpiresIn: Long? = null,
    )

    @Serializable
    data class AuthPayload(
        val account: AccountDto? = null,
        val tokens: TokensDto? = null,
    )

    @Serializable
    private data class ErrorEnvelope(val error: ApiError? = null)

    @Serializable
    data class ApiError(val code: String? = null, val message: String? = null)

    // --- request DTOs (the complete set of fields this app can ever send) ---

    @Serializable
    data class RegisterRequest(val username: String, val email: String, val password: String)

    @Serializable
    data class LoginRequest(val usernameOrEmail: String, val password: String)

    @Serializable
    data class GoogleRequest(val idToken: String, val nonce: String)

    @Serializable
    data class RefreshRequest(val refreshToken: String)

    @Serializable
    data class ForgotPasswordRequest(val email: String)

    @Serializable
    data class ResetPasswordRequest(val token: String, val newPassword: String)

    @Serializable
    data class DeleteAccountRequest(val password: String? = null)

    // --- results ------------------------------------------------------------

    data class SessionTokens(
        val accessToken: String,
        val refreshToken: String,
        val accessExpiresAt: Long,
        val refreshExpiresAt: Long,
    )

    sealed interface Response {
        /** Authenticated payload: account and/or tokens depending on endpoint. */
        data class Success(
            val account: Account?,
            val tokens: SessionTokens?,
        ) : Response

        /** Non-2xx with a parseable/known status. */
        data class Rejected(val status: Int, val code: String?) : Response

        /**
         * Transport failure — never a reason to drop a stored session.
         * The [kind] tells "device is offline" apart from "server unreachable",
         * "timed out" and "TLS failed", so the UI can show an honest message
         * instead of one generic connection error.
         */
        data class Transport(val kind: TransportKind) : Response
    }

    /** Classified transport failure (see [Response.Transport]). */
    enum class TransportKind {
        /** No connectivity: the server name cannot even be resolved. */
        OFFLINE,
        /** Name resolved but the connection failed: refused, no route, reset. */
        UNREACHABLE,
        /** Connect or read timed out. */
        TIMEOUT,
        /** TLS handshake / certificate validation failure. */
        TLS,
    }

    // --- endpoints ----------------------------------------------------------

    suspend fun register(username: String, email: String, password: String): Response =
        post("/auth/register", RegisterRequest(username, email, password))

    suspend fun login(usernameOrEmail: String, password: String): Response =
        post("/auth/login", LoginRequest(usernameOrEmail, password))

    suspend fun googleSignIn(idToken: String, nonce: String): Response =
        post("/auth/google", GoogleRequest(idToken, nonce))

    suspend fun refresh(refreshToken: String): Response =
        post("/auth/refresh", RefreshRequest(refreshToken))

    suspend fun logout(refreshToken: String): Response =
        post("/auth/logout", RefreshRequest(refreshToken))

    suspend fun forgotPassword(email: String): Response =
        post("/auth/forgot-password", ForgotPasswordRequest(email))

    suspend fun resetPassword(token: String, newPassword: String): Response =
        post("/auth/reset-password", ResetPasswordRequest(token, newPassword))

    suspend fun me(accessToken: String): Response = get("/auth/me", accessToken)

    suspend fun deleteAccount(accessToken: String, password: String?): Response =
        post("/auth/delete-account", DeleteAccountRequest(password), accessToken)

    // --- plumbing -----------------------------------------------------------

    private suspend fun post(path: String, body: Any, accessToken: String? = null): Response {
        val encoded = when (body) {
            is RegisterRequest -> json.encodeToString(RegisterRequest.serializer(), body)
            is LoginRequest -> json.encodeToString(LoginRequest.serializer(), body)
            is GoogleRequest -> json.encodeToString(GoogleRequest.serializer(), body)
            is RefreshRequest -> json.encodeToString(RefreshRequest.serializer(), body)
            is ForgotPasswordRequest -> json.encodeToString(ForgotPasswordRequest.serializer(), body)
            is ResetPasswordRequest -> json.encodeToString(ResetPasswordRequest.serializer(), body)
            is DeleteAccountRequest -> json.encodeToString(DeleteAccountRequest.serializer(), body)
            else -> error("unsupported request type")
        }
        return request(NetworkClient.HttpRequest(
            url = baseUrl() + path,
            method = "POST",
            body = encoded,
            accessToken = accessToken,
        ))
    }

    private suspend fun get(path: String, accessToken: String): Response =
        request(NetworkClient.HttpRequest(
            url = baseUrl() + path,
            method = "GET",
            body = null,
            accessToken = accessToken,
        ))

    private suspend fun request(req: NetworkClient.HttpRequest): Response {
        val result = try {
            network.execute(req)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Response.Transport(classifyTransport(error))
        }
        if (req.method == "POST" && result.code == 204) return Response.Success(null, null)
        if (result.code !in 200..299) {
            return Response.Rejected(result.code, parseErrorCode(result.body))
        }
        return when (val payload = parsePayload(result.body)) {
            null -> Response.Success(null, null)
            else -> Response.Success(payload.account?.toDomain(), payload.tokens?.toDomain())
        }
    }

    /**
     * Maps an IOException family member to a transport category. The original
     * cause is inspected through NetworkClient's wrapper so that "offline",
     * "server down", "timeout" and "TLS" stay distinguishable — while the
     * user-facing copy stays simple and never exposes exception details.
     */
    private fun classifyTransport(error: Throwable): TransportKind {
        val cause = (error as? NetworkClient.DownloadException)?.cause ?: error
        return when (cause) {
            is java.net.UnknownHostException -> TransportKind.OFFLINE
            is java.net.SocketTimeoutException -> TransportKind.TIMEOUT
            is javax.net.ssl.SSLException -> TransportKind.TLS
            is java.io.InterruptedIOException -> TransportKind.TIMEOUT
            // ConnectException / NoRouteToHostException / PortUnreachable /
            // SocketException ("connection reset") — the host was known but
            // the connection could not be made or kept.
            else -> TransportKind.UNREACHABLE
        }
    }

    private fun parsePayload(body: String): AuthPayload? = runCatching {
        if (body.isBlank()) null else json.decodeFromString(AuthPayload.serializer(), body)
    }.getOrNull()

    private fun parseErrorCode(body: String): String? = runCatching {
        json.decodeFromString(ErrorEnvelope.serializer(), body).error?.code
    }.getOrNull()

    private fun AccountDto.toDomain(): Account? {
        val id = id ?: return null
        return Account(
            id = id,
            username = username.orEmpty(),
            email = email.orEmpty(),
            provider = provider ?: "local",
        )
    }

    private fun TokensDto.toDomain(): SessionTokens? {
        val access = accessToken ?: return null
        val refresh = refreshToken ?: return null
        val now = nowMillis()
        return SessionTokens(
            accessToken = access,
            refreshToken = refresh,
            accessExpiresAt = now + (accessTokenExpiresIn ?: DEFAULT_ACCESS_TTL_SEC) * 1000L,
            refreshExpiresAt = now + (refreshTokenExpiresIn ?: DEFAULT_REFRESH_TTL_SEC) * 1000L,
        )
    }

    private companion object {
        const val DEFAULT_ACCESS_TTL_SEC = 15 * 60L
        const val DEFAULT_REFRESH_TTL_SEC = 30 * 24 * 60 * 60L
    }
}
