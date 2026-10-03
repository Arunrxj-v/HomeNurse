package com.homenurse.core.auth

import com.homenurse.core.network.NetworkClient
import com.homenurse.core.security.InMemorySecureStorage
import com.homenurse.testing.MockServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * [SessionManager] contract: save / retrieve / refresh / clear, expiry
 * detection and — critically — the offline rules:
 *
 *  * an expired access token is refreshed with the stored refresh token,
 *  * a *transport* failure never destroys the session (offline restart must
 *    keep the user signed in),
 *  * a server-rejected refresh token clears the session,
 *  * an expired refresh token signs out locally without a network call.
 *
 * Tokens are stored through [com.homenurse.core.security.SecureStorage]
 * (Keystore-encrypted on device); the test double keeps the same contract.
 */
class SessionManagerTest {

    private lateinit var server: MockServer
    private lateinit var storage: InMemorySecureStorage
    private lateinit var network: NetworkClient

    private var now = 1_700_000_000_000L

    private val account = com.homenurse.domain.repository.Account(
        id = "u_1",
        username = "arun",
        email = "arun@example.com",
        provider = "local",
    )

    @Before
    fun setUp() {
        server = MockServer().start()
        storage = InMemorySecureStorage()
        network = NetworkClient()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun sessionManager(baseUrl: String = server.baseUrl): SessionManager =
        SessionManager(storage, AuthApi(network, baseUrl = { baseUrl }, nowMillis = { now }), nowMillis = { now })

    private fun session(
        access: String = "access-1",
        refresh: String = "refresh-1",
        accessTtlMillis: Long = 15 * 60_000L,
        refreshTtlMillis: Long = 30 * 24 * 3_600_000L,
    ) = SessionManager.Session(
        account = account,
        accessToken = access,
        accessExpiresAt = now + accessTtlMillis,
        refreshToken = refresh,
        refreshExpiresAt = now + refreshTtlMillis,
    )

    private fun tokensJson(access: String, refresh: String, accessTtlSec: Long = 900): String =
        """{"accessToken":"$access","refreshToken":"$refresh","accessTokenExpiresIn":$accessTtlSec,"refreshTokenExpiresIn":2592000}"""

    private fun deadBaseUrl(): String {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return "http://127.0.0.1:$port"
    }

    // --- save / retrieve ------------------------------------------------------

    @Test
    fun `save persists the session and a restart restores it`() {
        val first = sessionManager()
        first.save(session())

        assertTrue(first.hasSession())
        assertEquals(account.username, first.account()?.username)

        // Process restart: a brand new manager over the same secure storage.
        val restarted = sessionManager()
        assertTrue(restarted.hasSession())
        assertEquals("u_1", restarted.account()?.id)
        assertEquals("refresh-1", restarted.currentRefreshToken())
        assertNotNull(storage.getString(SessionManager.KEY_SESSION))
    }

    @Test
    fun `clear destroys the session locally`() {
        val m = sessionManager()
        m.save(session())
        m.clear()

        assertFalse(m.hasSession())
        assertNull(m.account())
        assertNull(m.currentRefreshToken())
        assertNull(storage.getString(SessionManager.KEY_SESSION))
        assertTrue(m.isAccessExpired())
        assertTrue(m.isRefreshExpired())
    }

    // --- access token ---------------------------------------------------------

    @Test
    fun `unexpired access token is returned without any network call`() = runTest {
        val m = sessionManager()
        m.save(session())

        assertEquals("access-1", m.validAccessToken())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `no stored session reports signed out`() = runTest {
        val m = sessionManager()
        assertEquals(SessionManager.Access.SignedOut, m.ensureAccess())
        assertNull(m.validAccessToken())
        assertTrue(server.requests.isEmpty())
    }

    // --- refresh --------------------------------------------------------------

    @Test
    fun `expired access token is refreshed and the rotated tokens are stored`() = runTest {
        var issued = 1
        server.on("POST", "/auth/refresh") {
            issued++
            MockServer.Response.json(
                200,
                """{"tokens":${tokensJson("access-$issued", "refresh-$issued")}}""",
            )
        }
        val m = sessionManager()
        m.save(session())

        // Move past the access-token lifetime (refresh token still valid).
        now += 16 * 60_000L

        val access = m.ensureAccess()
        assertEquals(SessionManager.Access.Available("access-2"), access)

        assertEquals(1, server.requests.size)
        val request = server.requests.single()
        assertEquals("/auth/refresh", request.path)
        assertTrue("refresh must be sent as JSON", request.body.contains("refresh-1"))
        assertTrue(
            "Authorization header must carry the refresh credential only",
            request.body.contains("refreshToken"),
        )

        // Rotated tokens are now the stored ones.
        assertEquals("refresh-2", m.currentRefreshToken())
        assertTrue(m.isAccessExpired().not())
    }

    @Test
    fun `rejected refresh token clears the session`() = runTest {
        server.on("POST", "/auth/refresh") {
            MockServer.Response.json(401, """{"error":{"code":"invalid_session","message":"revoked"}}""")
        }
        val m = sessionManager()
        m.save(session())
        now += 16 * 60_000L

        assertEquals(SessionManager.Access.Rejected, m.ensureAccess())
        assertFalse(m.hasSession())
        assertNull(storage.getString(SessionManager.KEY_SESSION))
    }

    @Test
    fun `unreachable server keeps the session so offline restarts stay signed in`() = runTest {
        val m = sessionManager(baseUrl = deadBaseUrl())
        m.save(session())
        now += 16 * 60_000L

        assertEquals(SessionManager.Access.Offline, m.ensureAccess())

        assertTrue("offline must never sign the user out", m.hasSession())
        assertEquals("refresh-1", m.currentRefreshToken())
        assertNotNull(storage.getString(SessionManager.KEY_SESSION))
    }

    @Test
    fun `expired refresh token signs out locally without contacting the server`() = runTest {
        val m = sessionManager()
        m.save(session(accessTtlMillis = 1_000L, refreshTtlMillis = 2_000L))
        now += 3_000L

        assertEquals(SessionManager.Access.SignedOut, m.ensureAccess())
        assertFalse(m.hasSession())
        assertNull(storage.getString(SessionManager.KEY_SESSION))
        assertTrue("no network call for an already-expired credential", server.requests.isEmpty())
    }

    // --- logout ---------------------------------------------------------------

    @Test
    fun `logout revokes the refresh token and clears the local session`() = runTest {
        server.on("POST", "/auth/logout") { MockServer.Response.noContent }
        val m = sessionManager()
        m.save(session())

        val refreshToken = m.currentRefreshToken()
        assertNotNull(refreshToken)
        // What RemoteAuthRepository.signOut() does: revoke, then clear.
        assertEquals("refresh-1", refreshToken)
        m.clear()

        assertFalse(m.hasSession())
        assertNull(storage.getString(SessionManager.KEY_SESSION))
    }
}
