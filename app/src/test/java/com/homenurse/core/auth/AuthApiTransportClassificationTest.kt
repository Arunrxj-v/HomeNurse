package com.homenurse.core.auth

import com.homenurse.core.network.NetworkClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * Transport failures are classified so the UI can tell "device offline"
 * apart from "server unreachable" instead of showing one generic connection
 * error (the classification drives AuthError.NETWORK vs
 * AuthError.SERVER_UNREACHABLE/TIMEOUT/TLS_ERROR).
 *
 * Timeout and TLS classification need a middlebox-style setup and are
 * exercised on the real device (AuthEndToEndInstrumentedTest); here we pin
 * the two deterministic cases: name resolution failure and refusal.
 */
class AuthApiTransportClassificationTest {

    @Test
    fun unresolvableHostClassifiesAsOffline() = runBlocking {
        // .invalid is guaranteed NXDOMAIN (RFC 2606): no connectivity path.
        val api = AuthApi(NetworkClient(), baseUrl = { "https://server.invalid" })
        val response = api.login("someone", "password")
        assertTrue("expected Transport, got $response",
            response is AuthApi.Response.Transport)
        assertEquals(
            "resolution failure must read as offline",
            AuthApi.TransportKind.OFFLINE,
            (response as AuthApi.Response.Transport).kind,
        )
    }

    @Test
    fun refusedConnectionClassifiesAsUnreachable() = runBlocking {
        // Loopback resolves, but nothing listens: connection refused.
        val port = ServerSocket(0).use { it.localPort }
        val api = AuthApi(NetworkClient(), baseUrl = { "http://127.0.0.1:$port" })
        val response = api.login("someone", "password")
        assertTrue("expected Transport, got $response",
            response is AuthApi.Response.Transport)
        assertEquals(
            "refused connection must read as server-unreachable",
            AuthApi.TransportKind.UNREACHABLE,
            (response as AuthApi.Response.Transport).kind,
        )
    }
}
