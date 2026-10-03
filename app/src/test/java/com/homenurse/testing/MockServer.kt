package com.homenurse.testing

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal in-process HTTP/1.1 server for network-isolation and session tests.
 *
 * It stands in for the HomeNurse backend (auth + model distribution) so tests
 * can observe EVERY request the app makes: method, path, query, headers and
 * body. Nothing here mocks [com.homenurse.core.network.NetworkClient] — real
 * HTTP goes over the loopback interface, which is exactly what the isolation
 * assertions need to prove.
 *
 * Implemented on a plain [ServerSocket] (the JDK's `com.sun.net.httpserver`
 * is not on the Android unit-test compile classpath). One connection per
 * request with `Connection: close`, which is all [java.net.HttpURLConnection]
 * needs.
 */
class MockServer : AutoCloseable {

    /** A response the test server returns. [bytes] wins over [body] when set. */
    data class Response(
        val code: Int,
        val body: String = "",
        val bytes: ByteArray? = null,
        val contentType: String = "application/json",
        val headers: Map<String, String> = emptyMap(),
    ) {
        companion object {
            fun json(code: Int, body: String) = Response(code = code, body = body)
            val noContent = Response(code = 204)
        }
    }

    /** Everything observed about one inbound request. */
    data class Recorded(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        /** "METHOD /path?query" — used by allowlist assertions. */
        val target: String get() = if (query.isEmpty()) path else "$path?$query"
    }

    private val routes = mutableListOf<Pair<(Recorded) -> Boolean, (Recorded) -> Response>>()

    val requests = CopyOnWriteArrayList<Recorded>()

    /** "METHOD /path → HTTP status" for every served request (debug aid). */
    val responses = CopyOnWriteArrayList<Pair<String, Int>>()

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var port: Int = 0

    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** Register a handler matched by method + exact path. */
    fun on(method: String, path: String, handler: (Recorded) -> Response) {
        routes += ({ r: Recorded -> r.method.equals(method, true) && r.path == path }) to handler
    }

    /** Register a handler matched by method + predicate (e.g. any /models/ file). */
    fun onMatch(method: String, predicate: (Recorded) -> Boolean, handler: (Recorded) -> Response) {
        routes += ({ r: Recorded -> r.method.equals(method, true) && predicate(r) }) to handler
    }

    fun start(): MockServer {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", 0))
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        acceptThread = Thread(::acceptLoop, "homenurse-mock-server").apply {
            isDaemon = true
            start()
        }
        return this
    }

    /** Drop all recorded requests (between test phases). */
    fun reset() {
        requests.clear()
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        acceptThread?.join(2_000)
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = try {
                serverSocket?.accept()
            } catch (error: IOException) {
                null
            } ?: break
            Thread({
                runCatching { handle(client) }
                runCatching { client.close() }
            }, "homenurse-mock-handler").apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val uri = URI(parts[1])

        val headers = linkedMapOf<String, MutableList<String>>()
        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            headers.getOrPut(name) { mutableListOf() }.add(value)
            if (name.equals("Content-Length", true)) contentLength = value.toIntOrNull() ?: 0
        }
        val bodyBytes = if (contentLength > 0) input.readNBytes(contentLength) else ByteArray(0)

        val recorded = Recorded(
            method = method,
            path = uri.path ?: "/",
            query = uri.query.orEmpty(),
            headers = headers,
            body = String(bodyBytes, Charsets.UTF_8),
        )
        requests += recorded

        val response = routes.firstOrNull { (match, _) -> match(recorded) }
            ?.second
            ?.invoke(recorded)
            ?: Response.json(404, """{"error":{"code":"not_found","message":"no route"}}""")

        responses += recorded.target to response.code
        write(socket, response)
    }

    private fun write(socket: Socket, response: Response) {
        val payload = response.bytes ?: response.body.toByteArray(Charsets.UTF_8)
        val text = buildString {
            append("HTTP/1.1 ").append(response.code).append(' ').append(reason(response.code))
                .append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            response.headers.forEach { (name, value) ->
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)

        try {
            val out = socket.getOutputStream()
            out.write(text)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        } catch (error: IOException) {
            // Client vanished (cancelled download) — nothing to do in a test.
        }
    }

    /** Read one CRLF/LF-terminated line as ISO-8859-1 bytes (headers are ASCII). */
    private fun readLine(input: java.io.InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (byte == '\n'.code) break
            if (byte != '\r'.code) buffer.append(byte.toChar())
        }
        return buffer.toString()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "Status"
    }
}
