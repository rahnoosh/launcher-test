package com.launcher.tv.phone

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A deliberately small HTTP/1.1 server for the phone page on the home network.
 * One request per connection, bodies up to 2 MB, no TLS (local network only, PIN-protected).
 */
class PhoneServer(private val handler: (Request) -> Response) {

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        /** Lower-case names. */
        val headers: Map<String, String>,
        val body: ByteArray,
        val remoteIp: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
        val text: String get() = String(body, Charsets.UTF_8)
        fun cookie(name: String): String? =
            header("cookie")?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }

    class Response(
        val status: Int,
        val contentType: String = "application/json; charset=utf-8",
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
    ) {
        companion object {
            fun json(status: Int, json: String, headers: Map<String, String> = emptyMap()) =
                Response(status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8), headers)

            fun text(status: Int, text: String) = Response(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
        }
    }

    @Volatile private var socket: ServerSocket? = null
    private val pool: ExecutorService = Executors.newFixedThreadPool(6) { r -> Thread(r, "phone-page").apply { isDaemon = true } }

    val port: Int get() = socket?.takeIf { !it.isClosed }?.localPort ?: -1
    val isRunning: Boolean get() = socket?.isClosed == false

    /** Binds the first free port of [ports] (0 = any) and starts accepting. Returns the port. */
    @Synchronized
    fun start(ports: List<Int>): Int {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        var bound: ServerSocket? = null
        for (p in ports) {
            try {
                bound = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) }
                break
            } catch (_: IOException) {
            }
        }
        val s = bound ?: throw IOException("No free port for the phone page")
        socket = s
        Thread({ acceptLoop(s) }, "phone-page-accept").apply { isDaemon = true; start() }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: IOException) { break }
            pool.execute { serve(c) }
        }
    }

    private fun serve(c: Socket) {
        c.use { sock ->
            runCatching {
                sock.soTimeout = 15_000
                val input = BufferedInputStream(sock.getInputStream())
                val req = parse(input, sock.inetAddress?.hostAddress.orEmpty())
                val resp = if (req == null) Response.text(400, "Bad request") else try {
                    handler(req)
                } catch (e: Exception) {
                    Response.json(500, """{"error":${quote(e.message ?: "Server error")}}""")
                }
                write(sock.getOutputStream(), resp)
            }
        }
    }

    companion object {
        private const val MAX_LINE = 8 * 1024
        private const val MAX_BODY = 2 * 1024 * 1024

        fun parse(input: InputStream, remoteIp: String): Request? {
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 3) return null
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = HashMap<String, String>()
            repeat(100) {
                val line = readLine(input) ?: return null
                if (line.isEmpty()) {
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (length < 0 || length > MAX_BODY) return null
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n < 0) return null
                        read += n
                    }
                    val path = decode(target.substringBefore('?'))
                    val query = if ('?' in target) {
                        target.substringAfter('?').split('&').filter { it.isNotEmpty() }.associate {
                            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
                        }
                    } else emptyMap()
                    return Request(method, path, query, headers, body, remoteIp)
                }
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            return null
        }

        private fun readLine(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
                if (out.size() > MAX_LINE) return null
            }
            return out.toString(Charsets.UTF_8.name())
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        fun write(out: OutputStream, r: Response) {
            val reason = when (r.status) {
                200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
                404 -> "Not Found"; 429 -> "Too Many Requests"; else -> if (r.status >= 500) "Server Error" else "OK"
            }
            val head = StringBuilder()
                .append("HTTP/1.1 ").append(r.status).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(r.contentType).append("\r\n")
                .append("Content-Length: ").append(r.body.size).append("\r\n")
                .append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
            r.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            out.write(r.body)
            out.flush()
        }

        fun quote(s: String): String = buildString {
            append('"')
            s.forEach { ch ->
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    ch == '\n' -> append("\\n")
                    ch < ' ' -> append(String.format("\\u%04x", ch.code))
                    else -> append(ch)
                }
            }
            append('"')
        }
    }
}
