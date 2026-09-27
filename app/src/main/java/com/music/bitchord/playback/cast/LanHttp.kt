package com.music.bitchord.playback.cast

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * A minimal HTTP/1.1 client for talking to devices on the local network — a
 * DLNA renderer's description and its SOAP control URLs.
 *
 * UPnP is plain HTTP with no encrypted form, and the app forbids cleartext
 * HTTP (`android:usesCleartextTraffic` is false), which blocks
 * HttpURLConnection outright. Rather than lift that for the whole app, this
 * speaks HTTP over a plain socket and refuses anything that is not a private,
 * link-local or loopback address: the policy still covers everything on the
 * internet, and only the LAN devices DLNA needs are reachable in the clear.
 */
internal object LanHttp {

    class Response(val code: Int, val body: ByteArray)

    /** Whether [address] is on the local network rather than the internet. */
    fun isLocal(address: InetAddress): Boolean {
        if (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress) return true
        // IPv6 unique local addresses, fc00::/7.
        return address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
    }

    fun get(url: String, timeoutMs: Int, maxBytes: Int = MAX_BODY_BYTES): Response =
        request("GET", url, emptyMap(), null, timeoutMs, maxBytes)

    fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): Response =
        request("POST", url, headers, body, timeoutMs, MAX_BODY_BYTES)

    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Int,
        maxBytes: Int,
    ): Response {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IOException("bad URL $url")
        }
        if (!uri.scheme.equals("http", ignoreCase = true)) throw IOException("not an http URL: $url")
        val host = uri.host ?: throw IOException("no host in $url")
        val address = InetAddress.getByName(host)
        if (!isLocal(address)) throw IOException("$host is not on the local network")
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            val head = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                val hostHeader = if (host.contains(':')) "[$host]" else host
                append("Host: ").append(hostHeader).append(':').append(port).append("\r\n")
                append("Connection: close\r\n")
                append("User-Agent: Android UPnP/1.1 BitChord/1\r\n")
                for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
                if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
                append("\r\n")
            }
            val output = socket.getOutputStream()
            output.write(head.toByteArray(Charsets.UTF_8))
            if (body != null) output.write(body)
            output.flush()
            return readResponse(BufferedInputStream(socket.getInputStream()), maxBytes)
        }
    }

    /** Reads a status line, headers and a body (sized, chunked, or to the end of the stream). */
    fun readResponse(input: InputStream, maxBytes: Int): Response {
        var status = readLine(input) ?: throw IOException("no response")
        var code = parseStatus(status)
        var headers = readHeaders(input)
        // A 100 Continue is followed by the real response.
        while (code == 100) {
            status = readLine(input) ?: throw IOException("no response after 100 Continue")
            code = parseStatus(status)
            headers = readHeaders(input)
        }
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val length = headers["content-length"]?.trim()?.toLongOrNull()
        val body = when {
            chunked -> readChunked(input, maxBytes)
            length != null -> {
                if (length > maxBytes) throw IOException("response too large")
                readExactly(input, length.toInt())
            }
            else -> readToEnd(input, maxBytes)
        }
        return Response(code, body)
    }

    private fun parseStatus(line: String): Int {
        val parts = line.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("bad status line: $line")
        return parts[1].toIntOrNull() ?: throw IOException("bad status line: $line")
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = HashMap<String, String>()
        repeat(MAX_HEADERS) {
            val line = readLine(input) ?: return headers
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        throw IOException("too many headers")
    }

    private fun readChunked(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) {
                // Trailers, up to the blank line.
                while (readLine(input)?.isNotEmpty() == true) Unit
                break
            }
            if (out.size() + size > maxBytes) throw IOException("response too large")
            out.write(readExactly(input, size))
            readLine(input) // the CRLF after the chunk
        }
        return out.toByteArray()
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = input.read(bytes, filled, length - filled)
            if (read < 0) throw IOException("response ended early")
            filled += read
        }
        return bytes
    }

    private fun readToEnd(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (out.size() + read > maxBytes) throw IOException("response too large")
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_LINE) {
            val c = input.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
        throw IOException("header line too long")
    }

    private const val MAX_LINE = 8 * 1024
    private const val MAX_HEADERS = 100
    private const val MAX_BODY_BYTES = 1024 * 1024
}
