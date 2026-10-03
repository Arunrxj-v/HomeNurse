package com.homenurse.ai

import com.homenurse.core.config.ServerConfig
import com.homenurse.core.logging.PrivacyLog
import com.homenurse.core.model.CapabilityReport
import com.homenurse.core.model.DeviceCapabilities
import com.homenurse.core.model.DeviceCapabilityChecker
import com.homenurse.core.network.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Install/initialisation lifecycle of the local Gemma model. */
sealed interface ModelStatus {
    data object NotInstalled : ModelStatus
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : ModelStatus
    /** User paused the download — the partial file is kept for resuming. */
    data class Paused(val bytesRead: Long, val totalBytes: Long) : ModelStatus
    data object Verifying : ModelStatus
    data object Installing : ModelStatus
    data object Initializing : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val failure: ModelFailure) : ModelStatus
    data class UpdateAvailable(val installedVersion: String, val availableVersion: String) :
        ModelStatus
}

enum class ModelFailure {
    NETWORK,
    UNAUTHORIZED,
    HTTP,
    CHECKSUM_MISMATCH,
    INSUFFICIENT_STORAGE,
    UNSUPPORTED_DEVICE,
    /** The server manifest (with a verifiable SHA-256) could not be obtained. */
    MANIFEST,
    INITIALIZATION,
    UNKNOWN,
}

@Serializable
data class InstalledModel(
    val modelId: String,
    val version: String,
    val sha256: String,
    val sizeBytes: Long,
    val installedAt: Long,
)

/** Download transport abstraction so the state machine is unit-testable. */
interface ModelDownloader {
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    )

    suspend fun getText(url: String, headers: Map<String, String>): String

    class HttpStatusException(val code: Int) : IOException("HTTP $code")
}

class HttpModelDownloader(
    private val networkClient: NetworkClient,
) : ModelDownloader {

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        try {
            networkClient.download(url, headers, target, onProgress)
        } catch (error: NetworkClient.DownloadException) {
            throw if (error.httpCode in 400..499) {
                ModelDownloader.HttpStatusException(error.httpCode)
            } else {
                error
            }
        }
    }

    override suspend fun getText(url: String, headers: Map<String, String>): String {
        try {
            return networkClient.getText(url, headers)
        } catch (error: NetworkClient.DownloadException) {
            throw if (error.httpCode in 400..499) {
                ModelDownloader.HttpStatusException(error.httpCode)
            } else {
                error
            }
        }
    }
}

/**
 * ModelManager owns the full model lifecycle:
 *
 *   manifest (HomeNurse model server) → compatibility check →
 *   DOWNLOADING (resumable, pausable, progress) → VERIFYING (SHA-256) →
 *   INSTALLING → INITIALIZING (engine + real inference smoke test) → READY,
 *   with FAILED(reason) at any step and UPDATE_AVAILABLE when the server
 *   recommends a newer version.
 *
 * Distribution rules enforced here:
 *  * The model comes ONLY from the HomeNurse model server — there is no
 *    third-party model host, token or repository anywhere in this class.
 *  * A download never starts without a trusted SHA-256 from the server
 *    manifest, and the digest is verified BEFORE a file is accepted.
 *  * A currently-working model is never deleted before its replacement has
 *    downloaded, passed checksum verification, initialised and passed a real
 *    inference test — only then does the install record switch.
 *  * Requests carry only a non-medical account/session token: no patient,
 *    document, prompt or medical identifier is ever attached.
 *
 * The ONLY component (with [com.homenurse.core.auth.AuthApi]) that touches
 * the network in HomeNurse is this manager (via [ModelDownloader]); no
 * medical data is ever sent.
 */
class ModelManager(
    private val modelsDir: File,
    private val engineCacheDir: File,
    private val manifest: ModelManifest,
    private val downloader: ModelDownloader,
    private val capabilities: () -> DeviceCapabilities = {
        DeviceCapabilities(
            totalRamBytes = 0L,
            availableStorageBytes = Long.MAX_VALUE,
            sdkInt = 0,
            abis = emptyList(),
        )
    },
    private val engineFactory: (modelPath: String, cacheDir: File) -> InferenceEngine =
        { path, cache -> LiteRtLmEngineAdapter(path, cache) },
    private val remoteManifestUrl: () -> String = { ServerConfig.manifestUrl },
    private val accessTokenProvider: suspend () -> String? = { null },
    private val resolveDownloadUrl: (String) -> String = { ServerConfig.resolveDownloadUrl(it) },
) {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotInstalled)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    @Volatile
    private var engine: InferenceEngine? = null

    /** Authoritative manifest from the HomeNurse backend (cached on disk). */
    @Volatile
    private var remoteManifest: ModelManifest? = loadCachedRemoteManifest()

    @Volatile
    private var pauseRequested: Boolean = false

    fun isReady(): Boolean = engine != null

    fun engineOrNull(): InferenceEngine? = engine

    /** Best available manifest: server-recommended, else bundled metadata. */
    fun effectiveManifest(): ModelManifest = remoteManifest ?: manifest

    fun manifestEntry(modelId: String): ModelManifestEntry? =
        effectiveManifest().entry(modelId)

    fun activeEntry(): ModelManifestEntry =
        installedModel()?.let { effectiveManifest().entry(it.modelId) }
            ?: effectiveManifest().default

    fun installedModel(): InstalledModel? = runCatching {
        val file = File(modelsDir, INSTALLED_FILE)
        if (!file.exists()) return null
        json.decodeFromString(InstalledModel.serializer(), file.readText())
    }.getOrNull()

    /**
     * Fetch the current recommended model from the HomeNurse backend
     * (`GET /models/manifest`, authenticated with the session's access
     * token). Called before downloading; a failure simply leaves the bundled
     * metadata in place, in which case a download is refused unless a trusted
     * SHA-256 is available.
     */
    suspend fun refreshRemoteManifest(): Boolean {
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() } ?: return false
        val body = try {
            downloader.getText(
                url = remoteManifestUrl(),
                headers = mapOf(NetworkClient.HDR_AUTHORIZATION to "Bearer $token"),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PrivacyLog.failure("model_manifest_fetch_failed", error)
            return false
        }
        val parsed = ModelManifest.parse(body)
        if (parsed == null) {
            PrivacyLog.warn("model_manifest_invalid")
            return false
        }
        remoteManifest = parsed
        runCatching {
            modelsDir.mkdirs()
            File(modelsDir, REMOTE_MANIFEST_FILE).writeText(body)
        }
        PrivacyLog.event("model_manifest_refreshed")
        return true
    }

    /**
     * Called at app start: restores installed state, checks for updates and
     * initializes + smoke-tests the engine if the model file exists.
     * Deliberately performs NO network I/O — offline launch must work.
     */
    suspend fun warmUp() {
        val record = installedModel()
        val modelFile = record?.let { resolveModelFile(it) }
        if (record == null || modelFile == null || !modelFile.exists()) {
            _status.value = ModelStatus.NotInstalled
            return
        }
        val entry = effectiveManifest().entry(record.modelId)
        val updateAvailable = entry != null && entry.version != record.version

        _status.value = ModelStatus.Initializing
        try {
            initializeEngine(modelFile.absolutePath)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PrivacyLog.failure("model_init_failed", error)
            _status.value = ModelStatus.Failed(ModelFailure.INITIALIZATION)
            return
        }
        _status.value = if (updateAvailable && entry != null) {
            ModelStatus.UpdateAvailable(record.version, entry.version)
        } else {
            ModelStatus.Ready
        }
    }

    /** Full install pipeline (manifest → download → verify → init → test). */
    suspend fun downloadAndInstall(entry: ModelManifestEntry? = null) {
        pauseRequested = false
        val previous = _status.value

        // 1. Candidate model: explicit, else server-recommended (cached or
        //    bundled metadata).
        var target = entry ?: effectiveManifest().default

        // 2. Device compatibility — checked BEFORE any network I/O.
        var report = DeviceCapabilityChecker.check(capabilities(), target)
        if (!report.isSupported) {
            _status.value = ModelStatus.Failed(failureFor(report))
            return
        }

        // 3. Ask the HomeNurse backend for the current recommended model
        //    (authenticated, account-identity only), then re-validate.
        if (entry == null) refreshRemoteManifest()
        target = entry ?: effectiveManifest().default
        report = DeviceCapabilityChecker.check(capabilities(), target)
        if (!report.isSupported) {
            _status.value = ModelStatus.Failed(failureFor(report))
            return
        }

        // 3. Trusted checksum is mandatory: refuse to download anything we
        //    could not verify afterwards.
        val expectedSha = target.sha256?.lowercase()
        if (!target.verifiable || expectedSha == null) {
            _status.value = ModelStatus.Failed(ModelFailure.MANIFEST)
            return
        }

        // 4. Download authorization: a valid session token (account/device
        //    identity only — never medical identifiers).
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
        if (token == null) {
            _status.value = ModelStatus.Failed(ModelFailure.UNAUTHORIZED)
            return
        }

        val partFile = File(modelsDir, partFileName(target))
        try {
            modelsDir.mkdirs()
            val headers = mapOf(NetworkClient.HDR_AUTHORIZATION to "Bearer $token")
            val url = resolveDownloadUrl(target.downloadUrl)
            _status.value = ModelStatus.Downloading(partFile.length(), target.sizeBytes)
            downloader.download(url, headers, partFile) { read, total ->
                _status.value =
                    ModelStatus.Downloading(read, if (total > 0) total else target.sizeBytes)
            }

            // 5. Verify BEFORE the file is ever accepted.
            _status.value = ModelStatus.Verifying
            val actualSha = sha256Of(partFile)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                partFile.delete()
                PrivacyLog.warn("model_checksum_mismatch")
                _status.value = ModelStatus.Failed(ModelFailure.CHECKSUM_MISMATCH)
                return
            }

            // 6. Install to a versioned file — the previously installed,
            //    working model file is NOT touched yet.
            _status.value = ModelStatus.Installing
            val newFile = File(modelsDir, modelFileName(target))
            if (newFile.exists()) newFile.delete()
            if (!partFile.renameTo(newFile)) {
                partFile.copyTo(newFile, overwrite = true)
                partFile.delete()
            }

            val previousRecord = installedModel()
            val previousFile = previousRecord
                ?.let { resolveModelFile(it) }
                ?.takeIf { it.exists() && it.absolutePath != newFile.absolutePath }

            // 7. Initialize + real inference test on the NEW file. Only a
            //    fully verified replacement is allowed to take over.
            _status.value = ModelStatus.Initializing
            engine?.close()
            engine = null
            try {
                initializeEngine(newFile.absolutePath)
            } catch (initError: CancellationException) {
                throw initError
            } catch (initError: Exception) {
                PrivacyLog.failure("model_init_failed", initError)
                if (previousRecord == null || previousFile == null) {
                    // Fresh install: the bytes are verified; keep the file so
                    // a retry only has to re-initialise.
                    writeInstalled(
                        InstalledModel(
                            modelId = target.modelId,
                            version = target.version,
                            sha256 = actualSha,
                            sizeBytes = newFile.length(),
                            installedAt = System.currentTimeMillis(),
                        ),
                    )
                    _status.value = ModelStatus.Failed(ModelFailure.INITIALIZATION)
                } else {
                    // Update failed: discard the replacement and restore the
                    // previously working model (never lose a working model).
                    newFile.delete()
                    val restored = runCatching { initializeEngine(previousFile.absolutePath) }
                        .isSuccess
                    _status.value = if (restored) {
                        ModelStatus.UpdateAvailable(previousRecord.version, target.version)
                    } else {
                        ModelStatus.Failed(ModelFailure.INITIALIZATION)
                    }
                }
                return
            }

            // 8. Verified end-to-end → switch to the new model, then clean up
            //    superseded versions.
            writeInstalled(
                InstalledModel(
                    modelId = target.modelId,
                    version = target.version,
                    sha256 = actualSha,
                    sizeBytes = newFile.length(),
                    installedAt = System.currentTimeMillis(),
                ),
            )
            deleteSupersededModels(keep = newFile)
            _status.value = ModelStatus.Ready
            PrivacyLog.event("model_ready")
        } catch (error: CancellationException) {
            _status.value = when {
                pauseRequested -> ModelStatus.Paused(partFile.length(), target.sizeBytes)
                else -> fallback(previous)
            }
            throw error
        } catch (error: ModelDownloader.HttpStatusException) {
            _status.value = ModelStatus.Failed(
                when (error.code) {
                    401, 403 -> ModelFailure.UNAUTHORIZED
                    else -> ModelFailure.HTTP
                },
            )
        } catch (error: NetworkClient.DownloadException) {
            _status.value = ModelStatus.Failed(
                when (error.failure) {
                    NetworkClient.Failure.STORAGE -> ModelFailure.INSUFFICIENT_STORAGE
                    else -> ModelFailure.NETWORK
                },
            )
        } catch (error: AiProviderException.InitializationFailed) {
            PrivacyLog.failure("model_init_failed", error)
            _status.value = ModelStatus.Failed(ModelFailure.INITIALIZATION)
        } catch (error: IOException) {
            _status.value = ModelStatus.Failed(
                if (isStorageExhausted(error)) ModelFailure.INSUFFICIENT_STORAGE
                else ModelFailure.NETWORK,
            )
        } catch (error: Exception) {
            PrivacyLog.failure("model_install_failed", error)
            _status.value = ModelStatus.Failed(ModelFailure.UNKNOWN)
        }
    }

    /**
     * Pause an in-flight download. Cancelling the calling coroutine stops the
     * transfer; the partial file is kept so the next attempt resumes with a
     * Range request instead of starting over.
     */
    fun requestPause() {
        pauseRequested = true
    }

    /** Re-run engine initialization + smoke test (from FAILED(INITIALIZATION)). */
    suspend fun retryInitialize() {
        val record = installedModel() ?: run {
            _status.value = ModelStatus.NotInstalled
            return
        }
        val modelFile = resolveModelFile(record) ?: run {
            _status.value = ModelStatus.NotInstalled
            return
        }
        if (!modelFile.exists()) {
            _status.value = ModelStatus.NotInstalled
            return
        }
        _status.value = ModelStatus.Initializing
        try {
            initializeEngine(modelFile.absolutePath)
            _status.value = ModelStatus.Ready
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PrivacyLog.failure("model_init_retry_failed", error)
            _status.value = ModelStatus.Failed(ModelFailure.INITIALIZATION)
        }
    }

    /** Removes every model file, install record and engine (frees storage). */
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        engine?.close()
        engine = null
        modelsDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".litertlm") ||
                file.name.endsWith(".litertlm.part") ||
                file.name == INSTALLED_FILE
            ) {
                file.delete()
            }
        }
        engineCacheDir.deleteRecursively()
        _status.value = ModelStatus.NotInstalled
        PrivacyLog.event("model_deleted")
    }

    // --- internals ---------------------------------------------------------------

    private fun loadCachedRemoteManifest(): ModelManifest? = runCatching {
        val file = File(modelsDir, REMOTE_MANIFEST_FILE)
        if (!file.exists()) return null
        ModelManifest.parse(file.readText())
    }.getOrNull()

    private suspend fun initializeEngine(modelPath: String) = withContext(Dispatchers.Default) {
        val created = try {
            engineFactory(modelPath, engineCacheDir)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw AiProviderException.InitializationFailed(error)
        }
        try {
            // Real inference smoke test — proves the runtime can generate text.
            val reply = created.generate(
                prompt = "Reply with the single word OK.",
                systemInstruction = "You answer with one word only.",
                maxTokens = 16,
            )
            if (reply.isBlank()) {
                created.close()
                throw AiProviderException.InitializationFailed()
            }
        } catch (error: CancellationException) {
            created.close()
            throw error
        } catch (error: AiProviderException) {
            throw error
        } catch (error: Exception) {
            created.close()
            throw AiProviderException.InitializationFailed(error)
        }
        engine?.close()
        engine = created
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeInstalled(record: InstalledModel) {
        val file = File(modelsDir, INSTALLED_FILE)
        val tmp = File(modelsDir, "$INSTALLED_FILE.tmp")
        tmp.writeText(json.encodeToString(InstalledModel.serializer(), record))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Delete model files other than the verified, currently active one. */
    private fun deleteSupersededModels(keep: File) {
        modelsDir.listFiles()?.forEach { file ->
            if (file.name == keep.name) return@forEach
            val isModelArtifact =
                file.name.endsWith(".litertlm") || file.name.endsWith(".litertlm.part")
            if (isModelArtifact) file.delete()
        }
    }

    /** Versioned file name: a new version never overwrites the working file. */
    private fun modelFileName(entry: ModelManifestEntry): String =
        modelFileName(entry.modelId, entry.version)

    private fun modelFileName(modelId: String, version: String): String =
        "$modelId-$version.litertlm"

    private fun partFileName(entry: ModelManifestEntry): String =
        "${modelFileName(entry)}.part"

    /**
     * Locate the installed file. Prefers the versioned name; falls back to
     * the legacy un-versioned name so an already-working model survives an
     * app update.
     */
    private fun resolveModelFile(record: InstalledModel): File? {
        val versioned = File(modelsDir, modelFileName(record.modelId, record.version))
        if (versioned.exists()) return versioned
        val legacy = File(modelsDir, "${record.modelId}.litertlm")
        return if (legacy.exists()) legacy else versioned
    }

    private fun failureFor(report: CapabilityReport): ModelFailure = when {
        "storage" in report.blockers -> ModelFailure.INSUFFICIENT_STORAGE
        else -> ModelFailure.UNSUPPORTED_DEVICE
    }

    private fun fallback(previous: ModelStatus): ModelStatus = when {
        previous is ModelStatus.UpdateAvailable -> previous
        engine != null -> ModelStatus.Ready
        else -> ModelStatus.NotInstalled
    }

    private fun isStorageExhausted(error: IOException): Boolean =
        error.message?.contains("ENOSPC", ignoreCase = true) == true ||
            error is java.io.FileNotFoundException

    companion object {
        const val INSTALLED_FILE = "installed.json"
        const val REMOTE_MANIFEST_FILE = "manifest.remote.json"
    }
}
