package com.homenurse

import com.homenurse.ai.HttpModelDownloader
import com.homenurse.ai.InferenceEngine
import com.homenurse.ai.ModelManager
import com.homenurse.ai.ModelManifest
import com.homenurse.core.auth.AuthApi
import com.homenurse.core.auth.RemoteAuthRepository
import com.homenurse.core.auth.SessionManager
import com.homenurse.core.model.DeviceCapabilities
import com.homenurse.core.network.NetworkClient
import com.homenurse.core.security.InMemorySecureStorage
import com.homenurse.domain.repository.AuthResult
import com.homenurse.testing.MockServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Network isolation, proven end-to-end over real loopback HTTP.
 *
 * The HomeNurse product boundary:
 *  * the auth server handles ACCOUNT/SESSION only,
 *  * the model server distributes the model file only,
 *  * ALL medical data stays on the device — nothing medical is ever part of
 *    a request URL, header or body.
 *
 * These tests drive the app's real network path (AuthApi → NetworkClient,
 * ModelManager → HttpModelDownloader → NetworkClient) against an in-process
 * server that records every request, then assert:
 *  1. only allowlisted `/auth/` and `/models/` endpoints are contacted,
 *  2. a planted medical corpus never appears in any request,
 *  3. bodies carry only account/session fields,
 *  4. model endpoints are authenticated with the session token only.
 */
class NetworkIsolationTest {

    private lateinit var server: MockServer
    private lateinit var storage: InMemorySecureStorage
    private lateinit var network: NetworkClient
    private lateinit var authApi: AuthApi
    private lateinit var sessionManager: SessionManager
    private lateinit var repository: RemoteAuthRepository

    private lateinit var modelsDir: File
    private lateinit var engineCacheDir: File

    private var now = 1_700_000_000_000L

    /** Model bytes served by our own model server. */
    private val modelBytes = "HOMENURSE-MODEL-BYTES-".repeat(512).toByteArray()
    private val modelSha = MessageDigest.getInstance("SHA-256")
        .digest(modelBytes).joinToString("") { "%02x".format(it) }

    /**
     * Stand-in for everything medical on the device: patient details,
     * diagnoses, medicines, care-plan notes, document/OCR text and a chat
     * prompt. It is deliberately planted in local storage right next to the
     * session so a "proximity leak" (serializing the wrong object) would be
     * caught by the assertions below.
     */
    private val medicalCorpus = listOf(
        "Patient Maharaj Gopal Rao",
        "Type 2 diabetes mellitus with peripheral neuropathy",
        "Metformin 500 mg twice daily after meals",
        "HbA1c 7.8 percent recorded on 12 March",
        "discharge summary: chest pain radiating to the left arm",
        "allergy: penicillin causes anaphylaxis",
        "blood pressure 160/95 mmHg, heart rate 96 bpm",
        "care plan: physiotherapy twice daily, wound care every morning",
        "OCR text: creatinine 1.4 mg/dL, urea 42 mg/dL",
        "chat prompt: should I stop the aspirin before surgery?",
    )

    /** JSON keys any request body is allowed to contain. */
    private val allowedBodyKeys = setOf(
        "username", "email", "password", "usernameOrEmail",
        "refreshToken", "idToken", "nonce", "token", "newPassword",
    )

    private val allowedPaths = { path: String ->
        path.startsWith("/auth/") || path.startsWith("/models/")
    }

    @Before
    fun setUp() {
        server = MockServer().start()
        storage = InMemorySecureStorage()
        network = NetworkClient()
        authApi = AuthApi(network, baseUrl = { server.baseUrl }, nowMillis = { now })
        sessionManager = SessionManager(storage, authApi, nowMillis = { now })
        repository = RemoteAuthRepository(sessionManager, authApi)
        modelsDir = Files.createTempDirectory("hn-iso-models").toFile()
        engineCacheDir = Files.createTempDirectory("hn-iso-cache").toFile()

        // Plant the medical corpus locally — it must never leave the device.
        medicalCorpus.forEachIndexed { index, value ->
            storage.putString("medical.record.$index", value)
        }
        File(modelsDir, "local-medical-note.txt")
            .writeText(medicalCorpus.joinToString("\n"))
    }

    @After
    fun tearDown() {
        server.close()
        modelsDir.deleteRecursively()
        engineCacheDir.deleteRecursively()
    }

    // --- routes ---------------------------------------------------------------

    private fun installRoutes() {
        server.on("POST", "/auth/register") {
            MockServer.Response.json(
                201,
                """{"account":{"id":"u_1","username":"arun","email":"arun@example.com","provider":"local"},"tokens":${tokens("access-1", "refresh-1")}}""",
            )
        }
        server.on("POST", "/auth/login") {
            MockServer.Response.json(
                200,
                """{"account":{"id":"u_1","username":"arun","email":"arun@example.com","provider":"local"},"tokens":${tokens("access-3", "refresh-3")}}""",
            )
        }
        server.on("POST", "/auth/refresh") {
            MockServer.Response.json(200, """{"tokens":${tokens("access-2", "refresh-2")}}""")
        }
        server.on("POST", "/auth/forgot-password") { MockServer.Response(code = 202) }
        server.on("POST", "/auth/logout") { MockServer.Response.noContent }
        server.on("POST", "/auth/delete-account") { MockServer.Response.noContent }
        server.on("GET", "/models/manifest") {
            MockServer.Response.json(
                200,
                """
                {"modelId":"homenurse-gemma","version":"1.0.0",
                 "filename":"homenurse-gemma-1.0.0.litertlm","sizeBytes":${modelBytes.size},
                 "sha256":"$modelSha","downloadUrl":"${server.baseUrl}/models/1.0.0/homenurse-gemma-1.0.0.litertlm",
                 "runtime":"litert-lm","minimumAndroidVersion":"26","minimumRamMb":3072,
                 "displayName":"HomeNurse AI (Gemma)","lowEndDevice":false,
                 "licenseUrl":"https://ai.google.dev/gemma/terms",
                 "noticeUrl":"${server.baseUrl}/models/1.0.0/NOTICE"}
                """.trimIndent(),
            )
        }
        server.on("GET", "/models/1.0.0/homenurse-gemma-1.0.0.litertlm") {
            MockServer.Response(
                code = 200,
                body = "",
                bytes = modelBytes,
                contentType = "application/octet-stream",
            )
        }
    }

    private fun tokens(access: String, refresh: String): String =
        """{"accessToken":"$access","refreshToken":"$refresh","accessTokenExpiresIn":900,"refreshTokenExpiresIn":2592000}"""

    private fun modelManager() = ModelManager(
        modelsDir = modelsDir,
        engineCacheDir = engineCacheDir,
        // Bundled fallback: compatibility metadata only — no checksum, so a
        // download is impossible until the authenticated server manifest
        // supplies one (asserted by the MANIFEST rule in ModelManagerTest).
        manifest = ModelManifest(
            defaultModelId = "homenurse-gemma",
            models = listOf(
                com.homenurse.ai.ModelManifestEntry(
                    modelId = "homenurse-gemma",
                    displayName = "HomeNurse AI (Gemma)",
                    version = "1.0.0",
                    downloadUrl = "/models/1.0.0/homenurse-gemma-1.0.0.litertlm",
                    sha256 = null,
                    sizeBytes = modelBytes.size.toLong(),
                ),
            ),
        ),
        downloader = HttpModelDownloader(network),
        capabilities = {
            DeviceCapabilities(
                totalRamBytes = 8L * 1024 * 1024 * 1024,
                availableStorageBytes = 50L * 1024 * 1024 * 1024,
                sdkInt = 34,
                abis = listOf("arm64-v8a"),
            )
        },
        engineFactory = { _, _ -> FakeInferenceEngine() },
        remoteManifestUrl = { server.baseUrl + "/models/manifest" },
        accessTokenProvider = { sessionManager.validAccessToken() },
        resolveDownloadUrl = { url -> if (url.startsWith("http")) url else server.baseUrl + url },
    )

    private class FakeInferenceEngine : InferenceEngine {
        override fun generate(prompt: String, systemInstruction: String, maxTokens: Int): String = "OK"
        override fun cancelActive() {}
        override fun close() {}
    }

    /**
     * Drives every network feature the app has: register, token refresh,
     * forgot-password, manifest fetch, model download, delete-account,
     * login and logout.
     */
    private suspend fun driveAllFlows() {
        installRoutes()

        // 1. Register → session established.
        val registered = repository.register("arun", "arun@example.com", "Sup3r-Secret-1!")
        assertTrue("register failed: $registered", registered is AuthResult.SignedIn)

        // 2. Access token expires → refresh on launch/next call.
        now += 20 * 60_000L
        assertTrue(repository.refreshSession())

        // 3. Forgot password (server-side email; no token ever displayed).
        val forgot = repository.forgotPassword("arun@example.com")
        assertTrue("forgot-password failed: $forgot", forgot is AuthResult.Success)

        // 4. Model manifest + authenticated download + verify + init.
        val status = modelManager()
        status.downloadAndInstall()
        assertTrue(
            "model install did not reach READY: ${status.status.value}",
            status.isReady(),
        )

        // 5. Delete account (session only — local medical data untouched).
        val deleted = repository.deleteAccount("Sup3r-Secret-1!")
        assertTrue("delete-account failed: $deleted", deleted is AuthResult.Success)

        // 6. Sign in again and sign out (server-side revocation).
        val signedIn = repository.signIn("arun", "Sup3r-Secret-1!")
        assertTrue("login failed: $signedIn", signedIn is AuthResult.SignedIn)
        repository.signOut()
    }

    // --- assertions -----------------------------------------------------------

    @Test
    fun `only allowlisted auth and model endpoints are ever contacted`() = runTest {
        driveAllFlows()

        assertTrue("no request was recorded", server.requests.isNotEmpty())
        val offenders = server.requests.filterNot { allowedPaths(it.path) }
        if (offenders.isNotEmpty()) {
            fail(
                "Non-allowlisted endpoints contacted:\n" +
                    offenders.joinToString("\n") { it.target },
            )
        }
        // Sanity: the flows really happened (all 8 destinations).
        val paths = server.requests.map { it.path }.toSet()
        listOf(
            "/auth/register",
            "/auth/refresh",
            "/auth/forgot-password",
            "/auth/delete-account",
            "/auth/login",
            "/auth/logout",
            "/models/manifest",
            "/models/1.0.0/homenurse-gemma-1.0.0.litertlm",
        ).forEach { expected ->
            assertTrue("expected a request to $expected (saw: $paths)", expected in paths)
        }
    }

    @Test
    fun `medical data never appears in any request body header or url`() = runTest {
        driveAllFlows()

        val needles = medicalCorpus.map { it.lowercase() }
        val medicalKeys = listOf(
            "patient", "diagnosis", "symptom", "medication", "medicine", "prescription",
            "careplan", "care_plan", "document", "ocr", "lab", "report", "prompt",
            "conversation", "medical", "allergy", "hba1c",
        )
        server.requests.forEach { request ->
            val haystacks = buildList {
                add(request.target.lowercase())
                add(request.body.lowercase())
                request.headers.forEach { (name, values) ->
                    add(name.lowercase())
                    values.forEach { add(it.lowercase()) }
                }
            }
            needles.forEach { needle ->
                haystacks.forEach { haystack ->
                    if (needle in haystack) {
                        fail("medical data leaked into ${request.method} ${request.target}: '$needle'")
                    }
                }
            }
            // Field NAMES must never be medical either (body + query).
            medicalKeys.forEach { key ->
                if (request.body.contains("\"$key", ignoreCase = true) ||
                    request.query.contains(key, ignoreCase = true)
                ) {
                    fail("medical field '$key' sent in ${request.method} ${request.target}")
                }
            }
        }
        // The corpus is really there locally — the scan is not vacuous.
        assertTrue(storage.getString("medical.record.0") == medicalCorpus.first())
        assertTrue(
            File(modelsDir, "local-medical-note.txt").readText()
                .contains(medicalCorpus.last()),
        )
    }

    @Test
    fun `request bodies carry only account and session fields`() = runTest {
        driveAllFlows()

        server.requests.forEach { request ->
            if (request.body.isBlank()) return@forEach
            val keys = kotlinx.serialization.json.Json
                .parseToJsonElement(request.body).jsonObject.keys
            val unexpected = keys - allowedBodyKeys
            if (unexpected.isNotEmpty()) {
                fail(
                    "Unexpected fields in ${request.method} ${request.target}: $unexpected " +
                        "(bodies may only carry account/session fields)",
                )
            }
        }
    }

    @Test
    fun `model manifest and model download are authenticated with the session token`() = runTest {
        driveAllFlows()

        val manifestRequest = server.requests.first { it.path == "/models/manifest" }
        val modelRequest =
            server.requests.first { it.path == "/models/1.0.0/homenurse-gemma-1.0.0.litertlm" }

        // Both model endpoints must be authenticated — and only with the
        // account's session token (no medical identifier of any kind).
        val manifestAuth = manifestRequest.headers.entries
            .firstOrNull { it.key.equals("Authorization", true) }
        val modelAuth = modelRequest.headers.entries
            .firstOrNull { it.key.equals("Authorization", true) }

        assertTrue("manifest must be authenticated", manifestAuth != null)
        assertTrue("model download must be authenticated", modelAuth != null)
        val token = manifestAuth!!.value.single()
        assertTrue("expected a bearer token, was: $token", token.startsWith("Bearer "))
        assertEquals(token, modelAuth!!.value.single())
        assertEquals("Bearer access-2", token)

        // A download response body is model bytes — never a request body.
        assertTrue(modelRequest.body.isEmpty())
    }

    @Test
    fun `session tokens are never logged into request bodies besides refresh and logout`() = runTest {
        driveAllFlows()

        val tokenBearing = server.requests.filter {
            it.body.contains("accessToken") || it.body.contains("refreshToken")
        }.map { it.path }.toSet()
        // Access tokens are sent as Authorization headers, never as bodies;
        // the refresh credential may only appear on refresh/logout.
        assertTrue("unexpected token in bodies: $tokenBearing", tokenBearing.isEmpty() ||
            tokenBearing.all { it == "/auth/refresh" || it == "/auth/logout" })
        assertTrue("/auth/login" !in tokenBearing)
        assertTrue("/auth/register" !in tokenBearing)
        server.requests.filter { it.path == "/auth/refresh" || it.path == "/auth/logout" }
            .forEach { assertTrue(it.body.contains("refreshToken")) }
    }
}
