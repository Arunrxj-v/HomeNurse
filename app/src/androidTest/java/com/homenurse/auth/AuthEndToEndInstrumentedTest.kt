package com.homenurse.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homenurse.HomeNurseApp
import com.homenurse.core.auth.AuthApi
import com.homenurse.core.auth.SessionManager
import com.homenurse.core.config.ServerConfig
import com.homenurse.core.network.NetworkClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end authentication test against the **real** HomeNurse
 * server through the exact public HTTPS endpoint the app uses
 * ([ServerConfig.API_BASE_URL] — Tailscale MagicDNS + stock TLS validation).
 *
 * Uses the production wiring (HomeNurseApp.container): real NetworkClient,
 * real AuthApi, real SessionManager backed by Keystore-encrypted storage.
 * Nothing is faked — every assertion observes a real server response or a
 * real transport failure, and failure messages never contain tokens.
 *
 * Covered scenarios (see the auth test plan):
 *  A server health over public HTTPS,
 *  B/D register + login, E/F wrong password,
 *  /auth/me, H token refresh + rotation reuse rejection, I logout,
 *  L server temporarily unreachable (connection refused/timeout),
 *  M no-internet classification (unresolvable host → OFFLINE),
 *  G session survives an app restart (fresh SessionManager over storage).
 *
 * The device is always left signed out unless the run passes
 * `-e leaveSignedIn true` for the app-restart check; a subsequent run of
 * `clearSession` puts it back.
 */
@RunWith(AndroidJUnit4::class)
class AuthEndToEndInstrumentedTest {

    private lateinit var app: HomeNurseApp
    private lateinit var api: AuthApi
    private lateinit var network: NetworkClient

    // One fixed account, reused across runs: the server's register rate limit
    // (5/hour) is only consumed the first time this suite ever runs.
    private val username = "e2edev1"
    private val email = "e2edev1@homenurse.test"
    private val password = "E2eTest!Pass42"

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as HomeNurseApp
        api = app.container.authApi
        network = NetworkClient()
    }

    @After
    fun tearDown() {
        val leave = InstrumentationRegistry.getArguments().getString("leaveSignedIn") == "true"
        if (!leave) runCatching { app.container.sessionManager.clear() }
    }

    // --- A: server health through the public endpoint -----------------------------

    @Test
    fun serverHealth() = runBlocking {
        val body = network.getText("${ServerConfig.API_BASE_URL}/health")
        assertTrue("health body: $body", body.contains("\"status\""))
        assertTrue("health body: $body", body.contains("ok"))
    }

    // --- B/D/E/F/me/H/I: the complete auth flow -----------------------------------

    @Test
    fun fullAuthFlow() = runBlocking {
        // Sign in; register on first ever run (or when the account is gone).
        var tokens = loginExpectSuccess()
        if (tokens == null) {
            when (val reg = api.register(username, email, password)) {
                is AuthApi.Response.Success ->
                    tokens = requireNotNull(reg.tokens) { "register returned no tokens" }
                is AuthApi.Response.Rejected -> assertTrue(
                    "unexpected register rejection: ${reg.status} ${reg.code}",
                    reg.code == "username_taken" || reg.status == 429,
                )
                is AuthApi.Response.Transport ->
                    error("register unreachable (${reg.kind})")
            }
            if (tokens == null) tokens = loginExpectSuccess()
        }
        val session = requireNotNull(tokens) { "could not obtain tokens" }

        // E: wrong password is rejected — never a fake success.
        val wrong = api.login(username, "WrongPass!999")
        assertTrue("wrong password must be 401, got $wrong",
            wrong is AuthApi.Response.Rejected && wrong.status == 401 &&
                wrong.code == "invalid_credentials")

        // /auth/me with the stored access token.
        val me = api.me(session.accessToken)
        assertTrue("me failed: $me",
            me is AuthApi.Response.Success && me.account?.username == username)

        // H: refresh rotates the refresh token…
        val refreshed = api.refresh(session.refreshToken)
        assertTrue("refresh failed: $refreshed",
            refreshed is AuthApi.Response.Success && refreshed.tokens != null)
        val newTokens = (refreshed as AuthApi.Response.Success).tokens!!
        // The security-relevant rotation is the refresh token (the access JWT
        // may legitimately be byte-identical when issued within the same
        // second — it carries no jti).
        assertTrue("refresh must rotate the refresh token",
            newTokens.refreshToken != session.refreshToken)
        assertTrue("refresh must return an access token",
            newTokens.accessToken.isNotBlank())

        // …and the old refresh token is rejected (reuse detection).
        val reuse = api.refresh(session.refreshToken)
        assertTrue("refresh reuse must be rejected: $reuse",
            reuse is AuthApi.Response.Rejected && reuse.status == 401)

        // I: logout with the current refresh token.
        val out = api.logout(newTokens.refreshToken)
        assertTrue("logout failed: $out", out is AuthApi.Response.Success)
    }

    // --- L: server temporarily unavailable -----------------------------------------

    @Test
    fun serverUnreachableIsClassified() = runBlocking {
        // Same real host, closed port: the transport layer must report
        // "server unreachable / timeout", not a generic failure.
        val downApi = AuthApi(network, baseUrl = { "https://server.tailda589d.ts.net:9" })
        val response = downApi.login(username, password)
        assertTrue("expected Transport, got $response",
            response is AuthApi.Response.Transport)
        val kind = (response as AuthApi.Response.Transport).kind
        assertTrue("kind=$kind", kind == AuthApi.TransportKind.UNREACHABLE ||
            kind == AuthApi.TransportKind.TIMEOUT)
    }

    // --- M: device without internet ------------------------------------------------

    /**
     * On-device proof of the OFFLINE classification — the exact branch a
     * device with no connectivity takes: the host name cannot be resolved,
     * so the transport must report OFFLINE (what the UI shows as
     * "Check your internet connection and try again.").
     *
     * `.invalid` is guaranteed NXDOMAIN (RFC 2606), so this exercises the
     * real Android DNS/resolution failure path without touching the radios —
     * a real airplane-mode window cannot be driven safely here because
     * dropping Wi-Fi also kills the adb transport that supervises the run.
     */
    @Test
    fun unresolvableHostClassifiesAsOffline() = runBlocking {
        val offlineApi = AuthApi(network, baseUrl = { "https://homenurse-offline-test.invalid" })
        val response = offlineApi.login(username, password)
        assertTrue("expected Transport, got $response",
            response is AuthApi.Response.Transport)
        assertEquals("resolution failure must classify as OFFLINE",
            AuthApi.TransportKind.OFFLINE,
            (response as AuthApi.Response.Transport).kind)
    }

    // --- G: session survives an app restart ----------------------------------------

    @Test
    fun sessionSurvivesRestart() = runBlocking {
        val response = api.login(username, password)
        val success = response as? AuthApi.Response.Success
            ?: error("login failed: $response")
        val tokens = requireNotNull(success.tokens) { "login returned no tokens" }
        val account = requireNotNull(success.account) { "login returned no account" }

        app.container.sessionManager.save(
            SessionManager.Session(
                account = account,
                accessToken = tokens.accessToken,
                accessExpiresAt = tokens.accessExpiresAt,
                refreshToken = tokens.refreshToken,
                refreshExpiresAt = tokens.refreshExpiresAt,
            ),
        )

        // A new SessionManager over the same Keystore storage behaves exactly
        // like a fresh app process reading the stored session.
        val restarted = SessionManager(app.container.secureStorage, app.container.authApi)
        assertNotNull("session must survive a restart", restarted.account())
        assertEquals(username, restarted.account()?.username)
        assertTrue("access token must still be valid offline",
            restarted.validAccessToken() != null)
    }

    /** Manual step after the app-restart check: put the device back to signed out. */
    @Test
    fun clearSession() {
        app.container.sessionManager.clear()
        assertNull(app.container.sessionManager.account())
    }

    // --- helpers --------------------------------------------------------------------

    /** Real login for the fixed account; null when the account does not exist yet. */
    private suspend fun loginExpectSuccess(): AuthApi.SessionTokens? {
        val response = api.login(username, password)
        return when {
            response is AuthApi.Response.Success -> {
                requireNotNull(response.tokens) { "login returned no tokens" }
            }
            response is AuthApi.Response.Rejected &&
                response.status == 401 &&
                response.code == "invalid_credentials" -> null
            else -> error("login transport/server failure: $response")
        }
    }
}
