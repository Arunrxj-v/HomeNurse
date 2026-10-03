package com.homenurse.core.network

import com.homenurse.core.logging.PrivacyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client used ONLY by two components:
 *  * [com.homenurse.ai.ModelManager] — downloads the Gemma model and the
 *    model manifest;
 *  * [com.homenurse.core.auth.AuthApi] — authentication/session endpoints.
 *
 * Medical repositories have no reference to this class — nothing medical is
 * ever sent over the network (enforced by tests).
 *
 * Features: HTTPS-only (cleartext blocked by network security config),
 * manual redirect following (Range header preserved, Authorization header
 * never forwarded off the original host), Range-based resume, progress
 * callbacks and cooperative cancellation.
 */
class NetworkClient(
    private val userAgent: String = "HomeNurse/1.0",
) {

    enum class Failure { NETWORK, HTTP, CANCELLED, STORAGE }

    /** Status + body of a small JSON response. */
    data class HttpResult(val code: Int, val body: String)

    /** Small JSON request (auth + manifest only; never medical data). */
    data class HttpRequest(
        val url: String,
        val method: String = "GET",
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
        /** Access token; sent as `Authorization: Bearer …` on the original host only. */
        val accessToken: String? = null,
    )

    class DownloadException(
        val failure: Failure,
        val httpCode: Int = 0,
        cause: Throwable? = null,
    ) : IOException("Download failed: $failure ($httpCode)", cause)

    /** Append/resume [target] from [url]. Progress reported in bytes. */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        var currentUrl = url
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val existing = if (target.exists()) target.length() else 0L
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", userAgent)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                // Authorization is only ever sent to the original host.
                if (headers.containsKey(HDR_AUTHORIZATION)) {
                    val originalHost = URL(url).host
                    if (URL(currentUrl).host != originalHost) {
                        setRequestProperty(HDR_AUTHORIZATION, "")
                    }
                }
            }
            val code = try {
                connection.responseCode
            } catch (error: IOException) {
                throw DownloadException(Failure.NETWORK, cause = error)
            }
            when {
                code in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw DownloadException(Failure.HTTP, code)
                    connection.disconnect()
                    if (++redirects > MAX_REDIRECTS) throw DownloadException(Failure.HTTP, code)
                    currentUrl = URL(URL(currentUrl), location).toString()
                }
                code == HttpURLConnection.HTTP_OK -> {
                    // Server ignored resume: restart from zero.
                    if (target.exists() && existing > 0) target.delete()
                    streamTo(connection, target, 0L, onProgress)
                    return@withContext
                }
                code == HttpURLConnection.HTTP_PARTIAL -> {
                    streamTo(connection, target, existing, onProgress)
                    return@withContext
                }
                else -> {
                    connection.disconnect()
                    throw DownloadException(Failure.HTTP, code)
                }
            }
        }
    }

    /** Fetch a small text resource; throws [DownloadException] on non-2xx. */
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val result = execute(HttpRequest(url = url, method = "GET", headers = headers))
        if (result.code !in 200..299) throw DownloadException(Failure.HTTP, result.code)
        return result.body
    }

    /** POST a small JSON document; returns status + body (never throws on 4xx/5xx). */
    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult = execute(HttpRequest(url = url, method = "POST", body = body, headers = headers))

    /** Execute a small JSON request with same-host redirect handling. */
    suspend fun execute(request: HttpRequest): HttpResult = withContext(Dispatchers.IO) {
        var currentUrl = request.url
        var redirects = 0
        val payload = request.body?.toByteArray(Charsets.UTF_8)
        while (true) {
            currentCoroutineContext().ensureActive()
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                if (payload != null) doOutput = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                request.accessToken?.let { token ->
                    val originalHost = URL(request.url).host
                    if (URL(currentUrl).host == originalHost) {
                        setRequestProperty(HDR_AUTHORIZATION, "Bearer $token")
                    }
                }
            }
            val code = try {
                if (payload != null) connection.outputStream.use { it.write(payload) }
                connection.responseCode
            } catch (error: IOException) {
                connection.disconnect()
                throw DownloadException(Failure.NETWORK, cause = error)
            }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) return@withContext HttpResult(code, "")
                if (++redirects > MAX_REDIRECTS) throw DownloadException(Failure.HTTP, code)
                currentUrl = URL(URL(currentUrl), location).toString()
                continue
            }
            val text = try {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    .orEmpty()
            } catch (error: IOException) {
                ""
            } finally {
                connection.disconnect()
            }
            return@withContext HttpResult(code, text)
        }
        @Suppress("UNREACHABLE_CODE")
        throw DownloadException(Failure.NETWORK)
    }

    private fun streamTo(
        connection: HttpURLConnection,
        target: File,
        startAt: Long,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val totalHeader = connection.getHeaderField("Content-Range")
            ?.substringAfter("/", "")?.toLongOrNull()
            ?: (connection.contentLengthLong.takeIf { it > 0 }?.plus(startAt) ?: -1L)
        val append = startAt > 0 && target.exists()
        try {
            connection.inputStream.use { input ->
                val fileOut = java.io.FileOutputStream(target, append)
                try {
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    var written = startAt
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        fileOut.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= REPORT_EVERY) {
                            lastReport = written
                            onProgress(written, totalHeader)
                        }
                    }
                    fileOut.flush()
                    onProgress(written, totalHeader)
                } finally {
                    fileOut.close()
                }
            }
        } catch (error: IOException) {
            // Keep the partial file: it will be resumed on the next attempt.
            PrivacyLog.warn("model_download_interrupted")
            throw DownloadException(Failure.NETWORK, cause = error)
        }
    }

    companion object {
        const val HDR_AUTHORIZATION = "Authorization"
        const val HDR_RANGE = "Range"
        private const val MAX_REDIRECTS = 10
        private const val DEFAULT_BUFFER = 64 * 1024
        private const val REPORT_EVERY = 256L * 1024L
    }
}
