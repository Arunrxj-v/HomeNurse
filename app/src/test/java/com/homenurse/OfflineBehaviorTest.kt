package com.homenurse

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Offline-behavior guarantee, enforced by source scan:
 *
 * All medical functionality (data layer, domain/use cases, document pipeline,
 * safety engine, reminders, UI) must be free of networking imports. The ONLY
 * places allowed to touch HTTP are the auth client, the model-download path
 * (`core.network.NetworkClient`, `core.auth.AuthApi`, `ai.ModelManager`) and
 * the DI wiring in `app.AppContainer` — exactly what the product requires:
 * after the model is installed, airplane mode must support every medical
 * feature.
 *
 * It also enforces the "no third-party model/token service" rule: no source
 * file in the app may mention an external model host or token service.
 */
class OfflineBehaviorTest {

    private val forbiddenTokens = listOf(
        "com.homenurse.core.network",
        "java.net.",
        "javax.net.",
        "okhttp3",
        "HttpURLConnection",
        "URLConnection",
    )

    /** Files permitted to reference networking (module-relative paths). */
    private val networkAllowed = listOf(
        "core/network/NetworkClient.kt",
        "core/auth/AuthApi.kt",     // account/session endpoints only
        "ai/ModelManager.kt",       // model manifest + model download
        "app/AppContainer.kt",      // DI wiring of client + downloader
    )

    /**
     * Markers of external model hosts / token services. The product rule is
     * absolute: models come from the HomeNurse server only, and no user-facing
     * or code reference to a third-party model/token service may exist.
     */
    private val externalServiceMarkers = listOf(
        "huggingface",
        "hugging face",
        "hf_token",
        "hf_access_token",
        "hf.co/",
        "hfapi",
        "arogyasaathi",
    )

    /** Source/asset file extensions scanned (binaries are never scanned). */
    private val scannedExtensions = setOf(
        "kt", "java", "xml", "json", "gradle", "kts", "properties", "txt", "md", "pro", "cfg",
    )

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/homenurse"),
            File("app/src/main/java/com/homenurse"),
        )
        candidates.firstOrNull { it.isDirectory }?.let { return it }
        fail("could not locate main source root (cwd=${File(".").absolutePath})")
        throw IllegalStateException("unreachable")
    }

    /** The app module's `src/` directory (main + test + androidTest + res). */
    private fun moduleSrcRoot(): File {
        val main = sourceRoot()
        // .../app/src/main/java/com/homenurse → .../app/src
        var src = main
        repeat(4) { src = src.parentFile }
        assertTrue("unexpected source layout: $src", src.name == "src" && src.isDirectory)
        return src
    }

    private fun mainSources(): List<File> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun moduleRelative(file: File): String =
        file.absolutePath.substringAfter("com/homenurse" + File.separator)

    @Test
    fun `no medical, domain or UI source references networking`() {
        val violations = mainSources().filter { file ->
            val relative = moduleRelative(file)
            val allowed = networkAllowed.any { relative == it } ||
                relative.startsWith("core/network/")
            if (allowed) return@filter false
            val text = file.readText()
            forbiddenTokens.any { token -> token in text }
        }
        if (violations.isNotEmpty()) {
            fail(
                "Files outside the auth/model-download path reference networking:\n" +
                    violations.joinToString("\n") { moduleRelative(it) },
            )
        }
    }

    @Test
    fun `no source file references an external model host or token service`() {
        val offenders = moduleSrcRoot().walkTopDown()
            .filter { it.isFile && it.extension in scannedExtensions }
            // This test file itself holds the marker list (it is the detector).
            .filter { it.name != "OfflineBehaviorTest.kt" }
            .filter { file ->
                val text = file.readText().lowercase()
                externalServiceMarkers.any { marker -> marker in text }
            }
            .toList()
        if (offenders.isNotEmpty()) {
            fail(
                "External model host / token service reference found:\n" +
                    offenders.joinToString("\n") { it.relativeTo(moduleSrcRoot()).path },
            )
        }
    }

    @Test
    fun `network client is referenced only from allowed paths`() {
        val offenders = mainSources().filter { file ->
            val relative = moduleRelative(file)
            val allowed = networkAllowed.any { relative == it } ||
                relative.startsWith("core/network/")
            !allowed && "NetworkClient" in file.readText()
        }
        if (offenders.isNotEmpty()) {
            fail(
                "Unexpected NetworkClient references:\n" +
                    offenders.joinToString("\n") { moduleRelative(it) },
            )
        }
    }

    @Test
    fun `server urls are assembled only in the auth and model paths`() {
        // ServerConfig is the single configuration abstraction; only the auth
        // client and the model manager may turn it into a concrete URL —
        // no other component (especially not a medical one) may build one.
        val urlMarkers = listOf(
            "apiUrl(", "modelUrl(", "manifestUrl", "resolveDownloadUrl(",
            "API_BASE_URL", "MODEL_BASE_URL",
        )
        val offenders = mainSources().filter { file ->
            val relative = moduleRelative(file)
            val allowed = relative == "core/config/ServerConfig.kt" ||
                relative in networkAllowed ||
                relative.startsWith("core/network/")
            if (allowed) return@filter false
            val text = file.readText()
            urlMarkers.any { marker -> marker in text }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "Server URL assembly outside the auth/model paths:\n" +
                    offenders.joinToString("\n") { moduleRelative(it) },
            )
        }
    }

    @Test
    fun `core medical layers exist and are covered by the scan`() {
        // Sanity: prove the scan actually sees the medical packages.
        val root = sourceRoot()
        listOf("data", "domain", "document", "safety", "reminder", "ui").forEach { pkg ->
            assertTrue(
                "package $pkg not found — scan would be vacuous",
                File(root, pkg).isDirectory,
            )
        }
        assertTrue(mainSources().size > 50)
    }
}
