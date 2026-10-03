package com.homenurse.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Model manifest.
 *
 * Two sources, one shape:
 *  1. The HomeNurse backend (`GET /models/manifest`, retrieved after
 *     authentication) is authoritative — it carries the current recommended
 *     version and the SHA-256 required to verify any download.
 *  2. The bundled `assets/models.json` fallback (device-compatibility
 *     metadata only).
 *
 * There is NO third-party model host and no external token: the model is
 * always distributed from the HomeNurse model server, and a download only
 * ever starts when a trusted SHA-256 is available (from the server manifest)
 * so the file can be verified BEFORE it is installed.
 */
@Serializable
data class ModelManifestEntry(
    val modelId: String,
    val displayName: String,
    val version: String,
    /** Absolute or server-relative (`/models/<version>/<file>`) download URL. */
    val downloadUrl: String,
    /** Published file name on the model server (used for relative URLs). */
    val filename: String? = null,
    /** Expected SHA-256 (lowercase hex) — mandatory before any download. */
    val sha256: String? = null,
    val sizeBytes: Long,
    /** Currently only "litert-lm" (Google AI Edge LiteRT-LM runtime). */
    val runtime: String = "litert-lm",
    val minSdk: Int = 26,
    /** Physical recommendation (see DeviceCapabilityChecker docs). */
    val recommendedRamBytes: Long = 2L * 1024 * 1024 * 1024,
    /** Suitable for low-end devices (smaller, less capable). */
    val lowEndDevice: Boolean = false,
    /** Gemma terms link shown in the in-app license screen. */
    val licenseUrl: String? = null,
    /** Required Gemma notice accompanying the distribution. */
    val noticeUrl: String? = null,
) {
    /** True when the server gave us everything needed to verify a download. */
    val verifiable: Boolean
        get() = !sha256.isNullOrBlank() && sha256!!.length == 64 && downloadUrl.isNotBlank()
}

@Serializable
data class ModelManifest(
    val schemaVersion: Int = 1,
    val defaultModelId: String,
    val models: List<ModelManifestEntry>,
) {
    fun entry(modelId: String): ModelManifestEntry? = models.firstOrNull { it.modelId == modelId }

    val default: ModelManifestEntry
        get() = entry(defaultModelId) ?: models.first()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse a manifest response. Accepts both the backend's single-model
         * document (`modelId`, `version`, `filename`, `sizeBytes`, `sha256`,
         * `downloadUrl`, `runtime`, `minimumAndroidVersion`, `minimumRamMb`,
         * `licenseUrl`, …) and the bundled multi-model document
         * (`{defaultModelId, models: [...]}`).
         *
         * Returns null when the document is unusable (missing identity /
         * size / URL) — callers must then refuse to download.
         */
        fun parse(text: String): ModelManifest? = runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val models = root["models"]
            if (models != null && models.jsonArray.isNotEmpty()) {
                val bundled = json.decodeFromJsonElement(Bundled.serializer(), root)
                val entries = bundled.models
                return@runCatching ModelManifest(
                    schemaVersion = bundled.schemaVersion,
                    defaultModelId = bundled.defaultModelId
                        ?: entries.first().modelId,
                    models = entries,
                )
            }
            val single = parseSingle(root) ?: return@runCatching null
            ModelManifest(defaultModelId = single.modelId, models = listOf(single))
        }.getOrNull()

        private fun parseSingle(obj: JsonObject): ModelManifestEntry? {
            fun str(key: String): String? =
                obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val modelId = str("modelId") ?: return null
            val version = str("version") ?: return null
            val downloadUrl = str("downloadUrl") ?: return null
            val sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.longOrNull ?: return null
            return ModelManifestEntry(
                modelId = modelId,
                displayName = str("displayName") ?: "HomeNurse AI",
                version = version,
                downloadUrl = downloadUrl,
                filename = str("filename"),
                sha256 = str("sha256")?.lowercase(),
                sizeBytes = sizeBytes,
                runtime = str("runtime") ?: "litert-lm",
                minSdk = str("minimumAndroidVersion")?.toIntOrNull()
                    ?: obj["minSdk"]?.jsonPrimitive?.longOrNull?.toInt()
                    ?: 26,
                recommendedRamBytes = (obj["minimumRamMb"]?.jsonPrimitive?.longOrNull
                    ?: (recommendedRamDefault() / (1024L * 1024L))) * 1024L * 1024L,
                lowEndDevice = obj["lowEndDevice"]?.jsonPrimitive?.booleanOrNull ?: false,
                licenseUrl = str("licenseUrl"),
                noticeUrl = str("noticeUrl"),
            )
        }

        private fun recommendedRamDefault(): Long = 3L * 1024 * 1024 * 1024

        @Serializable
        private data class Bundled(
            val schemaVersion: Int = 1,
            val defaultModelId: String? = null,
            val models: List<ModelManifestEntry>,
        )
    }
}
