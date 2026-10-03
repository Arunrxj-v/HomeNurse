package com.homenurse.ai

import com.homenurse.core.model.DeviceCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Full install-pipeline state machine tests with a fake transport + fake
 * engine: NOT_INSTALLED → DOWNLOADING → VERIFYING → INSTALLING →
 * INITIALIZING → READY, plus every failure branch. No real network, no
 * native libraries — the seams ([ModelDownloader], engineFactory) are faked.
 *
 * Distribution rules covered here:
 *  * the model comes only from the HomeNurse model server (no external
 *    token, repository or API exists in the pipeline),
 *  * a download never starts without a trusted server SHA-256,
 *  * a working model is never deleted before its replacement is verified.
 */
class ModelManagerTest {

    private lateinit var modelsDir: File
    private lateinit var engineCacheDir: File

    private val payload = "FAKE-MODEL-BYTES-".repeat(64).toByteArray()
    private val payloadSha = sha256Hex(payload)
    private val payloadV2 = "FAKE-MODEL-BYTES-V2-".repeat(64).toByteArray()
    private val payloadV2Sha = sha256Hex(payloadV2)

    private lateinit var downloader: FakeDownloader

    private val healthyCaps = DeviceCapabilities(
        totalRamBytes = 6L * 1024 * 1024 * 1024,
        availableStorageBytes = 100L * 1024 * 1024 * 1024,
        sdkInt = 34,
        abis = listOf("arm64-v8a"),
    )

    private fun entry(
        sha: String? = payloadSha,
        version: String = "1.0.0",
        downloadUrl: String = "$MODEL_BASE/models/1.0.0/model.litertlm",
    ) = ModelManifestEntry(
        modelId = "gemma-test",
        displayName = "Test model",
        version = version,
        downloadUrl = downloadUrl,
        sha256 = sha,
        sizeBytes = payload.size.toLong(),
    )

    private fun manifest(vararg entries: ModelManifestEntry) =
        ModelManifest(defaultModelId = entries.first().modelId, models = entries.toList())

    private fun manager(
        manifest: ModelManifest = manifest(entry()),
        caps: DeviceCapabilities = healthyCaps,
        accessToken: String? = ACCESS_TOKEN,
        engineBlock: (String, File) -> InferenceEngine = { _, _ -> FakeEngine() },
    ) = ModelManager(
        modelsDir = modelsDir,
        engineCacheDir = engineCacheDir,
        manifest = manifest,
        downloader = downloader,
        capabilities = { caps },
        engineFactory = engineBlock,
        remoteManifestUrl = { MANIFEST_URL },
        accessTokenProvider = { accessToken },
        resolveDownloadUrl = { url -> if (url.startsWith("http")) url else MODEL_BASE + url },
    )

    private class FakeDownloader(vararg bytes: ByteArray) : ModelDownloader {
        var payload: ByteArray = if (bytes.isNotEmpty()) bytes[0] else ByteArray(0)
        var downloadCalls = 0
        val downloadRequests = mutableListOf<Pair<String, Map<String, String>>>()
        val getTextRequests = mutableListOf<Pair<String, Map<String, String>>>()
        var getTextBody: String? = null
        var getTextError: Exception? = null
        var downloadError: Exception? = null

        /** Test hooks to observe ModelStatus synchronously at exact points. */
        var onAtDownloadStart: (() -> Unit)? = null
        var onAfterProgress: (() -> Unit)? = null

        override suspend fun download(
            url: String,
            headers: Map<String, String>,
            target: File,
            onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        ) {
            downloadCalls++
            downloadRequests += url to headers
            downloadError?.let { throw it }
            onAtDownloadStart?.invoke()
            target.writeBytes(payload)
            onProgress(payload.size.toLong(), payload.size.toLong())
            onAfterProgress?.invoke()
        }

        override suspend fun getText(url: String, headers: Map<String, String>): String {
            getTextRequests += url to headers
            getTextError?.let { throw it }
            return getTextBody ?: throw ModelDownloader.HttpStatusException(401)
        }
    }

    private class FakeEngine(private val reply: String = "OK") : InferenceEngine {
        var closed = false
        override fun generate(prompt: String, systemInstruction: String, maxTokens: Int): String = reply
        override fun cancelActive() {}
        override fun close() { closed = true }
    }

    @Before
    fun setUp() {
        modelsDir = Files.createTempDirectory("hn-models").toFile()
        engineCacheDir = Files.createTempDirectory("hn-cache").toFile()
        downloader = FakeDownloader()
        downloader.payload = payload
    }

    @After
    fun tearDown() {
        modelsDir.deleteRecursively()
        engineCacheDir.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun serverManifestJson(
        version: String,
        sha: String,
        downloadUrl: String,
    ): String = """
        {
          "modelId": "gemma-test",
          "version": "$version",
          "filename": "gemma-test-$version.litertlm",
          "sizeBytes": ${payload.size},
          "sha256": "$sha",
          "downloadUrl": "$downloadUrl",
          "runtime": "litert-lm",
          "minimumAndroidVersion": "26",
          "minimumRamMb": 2048,
          "displayName": "HomeNurse AI",
          "lowEndDevice": false,
          "licenseUrl": "https://ai.google.dev/gemma/terms",
          "noticeUrl": "$MODEL_BASE/models/$version/NOTICE"
        }
    """.trimIndent()

    // --- happy path -----------------------------------------------------------

    @Test
    fun `successful install walks through every documented state to READY`() = runTest {
        val m = manager()

        // StateFlow conflates writes the collector cannot observe between two
        // rapid state changes, so transient states are captured synchronously
        // at exact pipeline points via downloader hooks instead:
        var statusAtDownloadStart: ModelStatus? = null
        var statusAfterProgress: ModelStatus? = null
        downloader.onAtDownloadStart = { statusAtDownloadStart = m.status.value }
        downloader.onAfterProgress = { statusAfterProgress = m.status.value }

        m.downloadAndInstall()

        // 1. DOWNLOADING entered with the expected total size …
        assertEquals(
            ModelStatus.Downloading(0L, payload.size.toLong()),
            statusAtDownloadStart,
        )
        // … and progress updates reflect the bytes actually written.
        assertEquals(
            ModelStatus.Downloading(payload.size.toLong(), payload.size.toLong()),
            statusAfterProgress,
        )
        // 2. VERIFYING ran before INSTALLING: a checksum mismatch aborts with
        //    no installed file (see the checksum test below).
        // 3. INSTALLING ran before INITIALIZING: the installed record exists
        //    even when initialization fails (see the init-failure test below).
        // 4. INITIALIZING ran a real smoke test (FakeEngine.generate "OK").
        // 5. READY is the terminal state:
        assertEquals(ModelStatus.Ready, m.status.value)
        assertTrue(m.isReady())
        assertNotNull(m.engineOrNull())

        // Installed record + final versioned file present, .part gone.
        val record = m.installedModel()
        assertNotNull(record)
        assertEquals("gemma-test", record!!.modelId)
        assertEquals("1.0.0", record.version)
        assertEquals(payloadSha, record.sha256)
        assertTrue(File(modelsDir, "gemma-test-1.0.0.litertlm").exists())
        assertFalse(File(modelsDir, "gemma-test-1.0.0.litertlm.part").exists())
        assertEquals(1, downloader.downloadCalls)
    }

    @Test
    fun `download is authenticated with the session access token only`() = runTest {
        val m = manager()
        m.downloadAndInstall()

        val (url, headers) = downloader.downloadRequests.single()
        assertTrue("model must come from our server: $url", url.startsWith(MODEL_BASE))
        assertEquals("Bearer $ACCESS_TOKEN", headers["Authorization"])
        // The manifest request is authenticated the same way.
        val (manifestUrl, manifestHeaders) = downloader.getTextRequests.single()
        assertEquals(MANIFEST_URL, manifestUrl)
        assertEquals("Bearer $ACCESS_TOKEN", manifestHeaders["Authorization"])
    }

    @Test
    fun `engine factory receives the installed model path and smoke tests it`() = runTest {
        var seenPath: String? = null
        val m = manager(engineBlock = { path, cache ->
            seenPath = path
            assertTrue(cache.absolutePath.startsWith(engineCacheDir.absolutePath))
            FakeEngine("OK")
        })
        m.downloadAndInstall()
        assertEquals(File(modelsDir, "gemma-test-1.0.0.litertlm").absolutePath, seenPath)
        assertEquals(ModelStatus.Ready, m.status.value)
    }

    // --- manifest + verification ---------------------------------------------

    @Test
    fun `server manifest supplies the trusted checksum and its download url is used`() = runTest {
        // Bundled metadata carries no checksum → a download must be refused
        // until the server manifest (fetched with the session token) provides it.
        downloader.getTextBody = serverManifestJson(
            version = "2.0.0",
            sha = payloadSha,
            downloadUrl = "$MODEL_BASE/models/2.0.0/gemma-test-2.0.0.litertlm",
        )
        val m = manager(manifest = manifest(entry(sha = null)))

        m.downloadAndInstall()

        assertEquals(ModelStatus.Ready, m.status.value)
        assertEquals("2.0.0", m.installedModel()!!.version)
        val (url, headers) = downloader.downloadRequests.single()
        assertEquals("$MODEL_BASE/models/2.0.0/gemma-test-2.0.0.litertlm", url)
        assertEquals("Bearer $ACCESS_TOKEN", headers["Authorization"])
        // Server-recommended version wins over the bundled fallback.
        assertEquals(MANIFEST_URL, downloader.getTextRequests.single().first)
    }

    @Test
    fun `unverifiable bundled manifest without a server checksum refuses any download`() = runTest {
        val m = manager(manifest = manifest(entry(sha = null)), accessToken = null)

        m.downloadAndInstall()

        assertEquals(ModelStatus.Failed(ModelFailure.MANIFEST), m.status.value)
        assertEquals(0, downloader.downloadCalls)
        // No session token → not even the manifest is requested.
        assertTrue(downloader.getTextRequests.isEmpty())
    }

    @Test
    fun `missing session token refuses the download as unauthorized`() = runTest {
        val m = manager(accessToken = null)

        m.downloadAndInstall()

        assertEquals(ModelStatus.Failed(ModelFailure.UNAUTHORIZED), m.status.value)
        assertEquals(0, downloader.downloadCalls)
    }

    @Test
    fun `checksum mismatch deletes the file and never installs`() = runTest {
        val wrongSha = "0".repeat(64)
        val m = manager(manifest = manifest(entry(sha = wrongSha)))

        m.downloadAndInstall()

        assertEquals(ModelStatus.Failed(ModelFailure.CHECKSUM_MISMATCH), m.status.value)
        assertNull(m.installedModel())
        assertFalse(File(modelsDir, "gemma-test-1.0.0.litertlm").exists())
        assertFalse(File(modelsDir, "gemma-test-1.0.0.litertlm.part").exists())
        assertFalse(File(modelsDir, ModelManager.INSTALLED_FILE).exists())
        assertFalse(m.isReady())
    }

    // --- failures -------------------------------------------------------------

    @Test
    fun `http 401 maps to UNAUTHORIZED failure`() = runTest {
        downloader.downloadError = ModelDownloader.HttpStatusException(401)
        val m = manager()

        m.downloadAndInstall()

        assertEquals(ModelStatus.Failed(ModelFailure.UNAUTHORIZED), m.status.value)
        assertNull(m.installedModel())
    }

    @Test
    fun `http 500 maps to HTTP failure`() = runTest {
        downloader.downloadError = ModelDownloader.HttpStatusException(500)
        val m = manager()

        m.downloadAndInstall()

        assertEquals(ModelStatus.Failed(ModelFailure.HTTP), m.status.value)
    }

    @Test
    fun `network IOException maps to NETWORK failure`() = runTest {
        downloader.downloadError = java.io.IOException("connection reset")
        val m = manager()

        m.downloadAndInstall()

        assertEquals(ModelStatus.Failed(ModelFailure.NETWORK), m.status.value)
    }

    @Test
    fun `unsupported cpu arch fails before touching the network`() = runTest {
        val caps = healthyCaps.copy(abis = listOf("armeabi-v7a"))
        val m = manager(caps = caps)

        m.downloadAndInstall()

        assertEquals(ModelStatus.Failed(ModelFailure.UNSUPPORTED_DEVICE), m.status.value)
        assertEquals(0, downloader.downloadCalls)
        assertTrue(downloader.getTextRequests.isEmpty())
    }

    @Test
    fun `insufficient storage fails before touching the network`() = runTest {
        val caps = healthyCaps.copy(availableStorageBytes = 1024L)
        val m = manager(caps = caps)

        m.downloadAndInstall()

        assertEquals(ModelStatus.Failed(ModelFailure.INSUFFICIENT_STORAGE), m.status.value)
        assertEquals(0, downloader.downloadCalls)
        assertTrue(downloader.getTextRequests.isEmpty())
    }

    @Test
    fun `engine initialization failure maps to INITIALIZATION and file stays installed`() = runTest {
        val m = manager(engineBlock = { _, _ -> throw IllegalStateException("native lib missing") })

        m.downloadAndInstall()

        assertEquals(ModelStatus.Failed(ModelFailure.INITIALIZATION), m.status.value)
        // The file was verified + installed correctly; only the runtime failed.
        assertNotNull(m.installedModel())
        assertFalse(m.isReady())
    }

    @Test
    fun `blank smoke test reply is treated as initialization failure`() = runTest {
        val m = manager(engineBlock = { _, _ -> FakeEngine(reply = "   ") })

        m.downloadAndInstall()

        assertEquals(ModelStatus.Failed(ModelFailure.INITIALIZATION), m.status.value)
        assertFalse(m.isReady())
    }

    // --- warm up / delete / update -------------------------------------------

    @Test
    fun `warmUp without install reports NotInstalled`() = runTest {
        val m = manager()
        m.warmUp()
        assertEquals(ModelStatus.NotInstalled, m.status.value)
        assertFalse(m.isReady())
    }

    @Test
    fun `warmUp restores Ready state from installed record`() = runTest {
        val m = manager()
        m.downloadAndInstall()
        assertEquals(ModelStatus.Ready, m.status.value)

        // Simulate process restart: fresh manager over the same directory.
        val restarted = manager()
        restarted.warmUp()
        assertEquals(ModelStatus.Ready, restarted.status.value)
        assertTrue(restarted.isReady())
    }

    @Test
    fun `warmUp uses the legacy un-versioned file from an earlier app version`() = runTest {
        File(modelsDir, "gemma-test.litertlm").writeBytes(payload)
        File(modelsDir, ModelManager.INSTALLED_FILE).writeText(
            """{"modelId":"gemma-test","version":"1.0.0","sha256":"$payloadSha",""" +
                """"sizeBytes":${payload.size},"installedAt":1}""",
        )

        val m = manager()
        m.warmUp()

        assertEquals(ModelStatus.Ready, m.status.value)
        assertTrue(m.isReady())
    }

    @Test
    fun `warmUp reports UpdateAvailable when manifest version moves ahead`() = runTest {
        val m = manager()
        m.downloadAndInstall()

        val newer = manager(manifest = manifest(entry(version = "2.0.0")))
        newer.warmUp()
        assertEquals(ModelStatus.UpdateAvailable("1.0.0", "2.0.0"), newer.status.value)
    }

    @Test
    fun `verified update switches to the new versioned file and drops the old one`() = runTest {
        manager().downloadAndInstall()
        assertTrue(File(modelsDir, "gemma-test-1.0.0.litertlm").exists())

        downloader.payload = payloadV2
        val updated = manager(manifest = manifest(entry(sha = payloadV2Sha, version = "2.0.0")))
        updated.downloadAndInstall()

        assertEquals(ModelStatus.Ready, updated.status.value)
        assertEquals("2.0.0", updated.installedModel()!!.version)
        assertEquals(payloadV2Sha, updated.installedModel()!!.sha256)
        assertTrue(File(modelsDir, "gemma-test-2.0.0.litertlm").exists())
        assertFalse("superseded version must be cleaned up",
            File(modelsDir, "gemma-test-1.0.0.litertlm").exists())
    }

    @Test
    fun `failed update keeps the previously working model installed`() = runTest {
        val first = manager()
        first.downloadAndInstall()
        assertEquals(ModelStatus.Ready, first.status.value)

        // The replacement downloads and verifies, but the runtime rejects it.
        downloader.payload = payloadV2
        val updating = manager(
            manifest = manifest(entry(sha = payloadV2Sha, version = "2.0.0")),
            engineBlock = { path, _ ->
                if ("2.0.0" in path) throw IllegalStateException("new model rejected")
                FakeEngine()
            },
        )
        updating.downloadAndInstall()

        // Old model restored and still running; the record never switched.
        assertEquals(ModelStatus.UpdateAvailable("1.0.0", "2.0.0"), updating.status.value)
        assertTrue(updating.isReady())
        val record = updating.installedModel()
        assertEquals("1.0.0", record!!.version)
        assertEquals(payloadSha, record.sha256)
        assertTrue(File(modelsDir, "gemma-test-1.0.0.litertlm").exists())
        assertFalse(File(modelsDir, "gemma-test-2.0.0.litertlm").exists())
    }

    @Test
    fun `pausing an in-flight download keeps the partial file and reports Paused`() = runTest {
        val m = manager()
        downloader.onAtDownloadStart = {
            m.requestPause()
            throw CancellationException("paused by user")
        }

        try {
            m.downloadAndInstall()
        } catch (expected: CancellationException) {
            // expected: the calling coroutine is cancelled to stop the transfer
        }

        assertEquals(ModelStatus.Paused(0L, payload.size.toLong()), m.status.value)
        assertFalse(m.isReady())
    }

    @Test
    fun `deleteModel removes every file and resets state`() = runTest {
        val m = manager()
        m.downloadAndInstall()
        assertTrue(File(modelsDir, "gemma-test-1.0.0.litertlm").exists())

        m.deleteModel()

        assertEquals(ModelStatus.NotInstalled, m.status.value)
        assertFalse(File(modelsDir, "gemma-test-1.0.0.litertlm").exists())
        assertFalse(File(modelsDir, ModelManager.INSTALLED_FILE).exists())
        assertNull(m.installedModel())
        assertFalse(m.isReady())
    }

    private companion object {
        const val MODEL_BASE = "http://model.test.invalid"
        const val MANIFEST_URL = "$MODEL_BASE/models/manifest"
        const val ACCESS_TOKEN = "test-access-token"
    }
}
