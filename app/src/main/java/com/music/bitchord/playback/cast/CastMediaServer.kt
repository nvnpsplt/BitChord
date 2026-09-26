package com.music.bitchord.playback.cast

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.music.bitchord.data.TrackLog
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone as a tiny web server, for the one listener that cannot reach the
 * music on its own: the cast device.
 *
 * A Chromecast plays a URL. None of the URLs this app plays from are ones it
 * can use. A googlevideo URL is minted for the client that asked for it and
 * paced down to playback speed unless it is read in ranges — which is what
 * [com.music.bitchord.playback.ChunkedDataSource] exists for. A download is a
 * `file://` or `content://` URI that means nothing off this phone, an SMB track
 * needs this phone's credentials, and a module source resolves through a
 * sandbox that only runs here. So rather than teach the receiver any of that,
 * the receiver is handed a LAN address on this phone and every byte goes
 * through exactly the chain ExoPlayer reads from: the disk cache, the resolver,
 * the chunker, SMB. Whatever plays on the phone plays on the TV, from the same
 * cache, without resolving anything twice.
 *
 * Served paths are opaque tokens minted per source URI — never the URI itself —
 * so the server answers only for the handful of tracks it was told about, and
 * nobody else on the network can walk it for anything else.
 *
 * Plain blocking sockets on purpose. A cast receiver opens one or two
 * connections at a time; a thread each is simpler than any event loop and costs
 * nothing at that scale.
 */
@UnstableApi
internal class CastMediaServer(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory,
) {

    private enum class Kind { AUDIO, IMAGE, CAPTIONS }

    /** [item] is set for [Kind.CAPTIONS]: the track whose lyrics are asked for. */
    private class Entry(val kind: Kind, val uri: Uri, val item: MediaItem? = null) {
        /** Learned from the bytes on first read — see [CastMime.sniff]. */
        @Volatile var mimeType: String? = null

        /** The full length in bytes, once any read has been able to say. */
        @Volatile var length: Long = C.LENGTH_UNSET.toLong()
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val tokensBySource = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()
    private val threadCount = AtomicInteger()

    /**
     * Turns a track into the WebVTT the receiver shows as subtitles — see
     * [CastCaptions]. Called on a server thread and allowed to block while the
     * lyrics are looked up; set while lyrics captions are on.
     */
    @Volatile var captions: ((MediaItem) -> String)? = null

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var workers: ExecutorService? = null

    /** The port the receiver should call back on, or null while stopped. */
    val port: Int?
        get() = serverSocket?.takeUnless { it.isClosed }?.localPort

    /** Starts listening on every interface. Idempotent. */
    @Synchronized
    fun start(): Boolean {
        if (serverSocket?.isClosed == false) return true
        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0), BACKLOG)
            }
            val pool = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "cast-http-${threadCount.incrementAndGet()}").apply { isDaemon = true }
            }
            serverSocket = socket
            workers = pool
            Thread({ acceptLoop(socket, pool) }, "cast-http-accept").apply {
                isDaemon = true
                start()
            }
            TrackLog.d(TAG, "cast media server listening on port ${socket.localPort}")
            true
        } catch (e: IOException) {
            TrackLog.d(TAG, "cast media server failed to start: ${e.message}")
            false
        }
    }

    /** Stops listening, drops every open connection and forgets every token. */
    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
        entries.clear()
        tokensBySource.clear()
    }

    /**
     * Where the receiver fetches [sourceUri]'s audio from, reached at [host].
     * The same source always gets the same token, so an item already loaded on
     * the receiver can be recognised by its URL alone.
     */
    fun audioUrl(host: String, sourceUri: Uri): String? {
        val port = port ?: return null
        return urlFor(host, port, AUDIO_PATH, token(Kind.AUDIO, sourceUri))
    }

    /**
     * A URL the receiver can load [artworkUri] from. Web artwork is handed over
     * as it is — the receiver can reach it as well as the phone can — and only
     * artwork that lives on this phone is served from here.
     */
    fun artworkUrl(host: String, artworkUri: Uri?): Uri? {
        artworkUri ?: return null
        val scheme = artworkUri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return artworkUri
        if (scheme != "content" && scheme != "file" && scheme != "android.resource") return null
        val port = port ?: return null
        return Uri.parse(urlFor(host, port, IMAGE_PATH, token(Kind.IMAGE, artworkUri)))
    }

    /** Where the receiver fetches [item]'s lyrics as a subtitle file, reached at [host]. */
    fun captionsUrl(host: String, item: MediaItem): String? {
        val port = port ?: return null
        val key = sourceKey(Kind.CAPTIONS, Uri.fromParts(CAPTIONS_SCHEME, item.mediaId, null))
        val token = tokensBySource.getOrPut(key) {
            newToken().also { entries[it] = Entry(Kind.CAPTIONS, Uri.EMPTY, item) }
        }
        return urlFor(host, port, CAPTIONS_PATH, token)
    }

    /**
     * What the receiver will be told [sourceUri] is, found by opening it.
     *
     * The receiver needs a content type up front and this app rarely knows one:
     * what is on the end of a `bitchord://` URI is the resolver's choice, made
     * when it is opened. So it is opened — which also means the resolve happens
     * here, off the main thread, ahead of the receiver's own request.
     *
     * Blocking; call off the main thread. Null when nothing playable is there.
     */
    fun probe(sourceUri: Uri): String? {
        val entry = entries[token(Kind.AUDIO, sourceUri)] ?: return null
        return try {
            ensureMimeType(entry)
        } catch (e: IOException) {
            TrackLog.d(TAG, "cast probe failed: ${e.message}")
            null
        }
    }

    /**
     * Forgets every audio source except [keep], so the set of URLs this phone
     * answers for stays the few tracks around the playhead rather than growing
     * with every song of an evening.
     */
    fun retainOnly(keep: Collection<Uri>) {
        val keepKeys = keep.mapTo(HashSet()) { sourceKey(Kind.AUDIO, it) }
        val stale = tokensBySource.keys.filter { it.startsWith(AUDIO_PREFIX) && it !in keepKeys }
        for (key in stale) {
            tokensBySource.remove(key)?.let(entries::remove)
        }
    }

    private fun token(kind: Kind, uri: Uri): String {
        val key = sourceKey(kind, uri)
        return tokensBySource.getOrPut(key) {
            newToken().also { entries[it] = Entry(kind, uri) }
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            try {
                pool.execute { serve(client) }
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = READ_TIMEOUT_MS
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val request = HttpRequest.read(input) ?: return
                val output = socket.getOutputStream()
                respond(request, output)
                output.flush()
            } catch (_: SocketException) {
                // The receiver hung up — which it does every time it seeks, so
                // this is the ordinary end of a connection rather than an error.
            } catch (e: IOException) {
                TrackLog.d(TAG, "cast request failed: ${e.message}")
            }
        }
    }

    private fun respond(request: HttpRequest, output: OutputStream) {
        if (request.method != "GET" && request.method != "HEAD") {
            writeStatus(output, 405, "Method Not Allowed")
            return
        }
        val segments = request.path.trim('/').split('/')
        val entry = segments.takeIf { it.size == 2 }
            ?.let { (prefix, token) ->
                entries[token]?.takeIf {
                    (prefix == AUDIO_PATH && it.kind == Kind.AUDIO) ||
                        (prefix == IMAGE_PATH && it.kind == Kind.IMAGE) ||
                        (prefix == CAPTIONS_PATH && it.kind == Kind.CAPTIONS)
                }
            }
        if (entry == null) {
            writeStatus(output, 404, "Not Found")
            return
        }
        when (entry.kind) {
            Kind.AUDIO -> serveAudio(entry, request, output)
            Kind.IMAGE -> serveImage(entry, request, output)
            Kind.CAPTIONS -> serveCaptions(entry, request, output)
        }
    }

    private fun serveCaptions(entry: Entry, request: HttpRequest, output: OutputStream) {
        val item = entry.item
        // An empty file rather than an error when there is nothing to show: a
        // failed text track can fail the whole load on some receivers.
        val vtt = item?.let { target ->
            runCatching { captions?.invoke(target) }
                .onFailure { TrackLog.d(TAG, "cast captions failed: ${it.message}") }
                .getOrNull()
        } ?: CastCaptions.EMPTY
        val body = vtt.toByteArray(Charsets.UTF_8)
        writeHead(
            output,
            200,
            "OK",
            mapOf("Content-Type" to "text/vtt; charset=utf-8", "Content-Length" to body.size.toString()),
        )
        if (request.method != "HEAD") output.write(body)
    }

    private fun serveAudio(entry: Entry, request: HttpRequest, output: OutputStream) {
        val mimeType = ensureMimeType(entry)
        if (mimeType == null) {
            // A manifest, or something the receiver cannot decode. Refused
            // outright so the receiver reports an error the bridge can skip
            // past, rather than choking halfway through a stream of XML.
            writeStatus(output, 415, "Unsupported Media Type")
            return
        }
        val range = request.header("range")?.let { HttpRange.parse(it) }
        val known = entry.length.takeIf { it != C.LENGTH_UNSET.toLong() }
        val resolved = range?.resolve(known)
        if (range != null && resolved == null) {
            val headers = buildMap {
                if (known != null) put("Content-Range", "bytes */$known")
            }
            writeStatus(output, 416, "Range Not Satisfiable", headers)
            return
        }
        val start = resolved?.start ?: 0L
        val requested = resolved?.length ?: C.LENGTH_UNSET.toLong()

        val source = dataSourceFactory.createDataSource()
        try {
            val opened = source.open(
                DataSpec.Builder()
                    .setUri(entry.uri)
                    .setPosition(start)
                    .setLength(requested)
                    .build(),
            )
            if (start == 0L && requested == C.LENGTH_UNSET.toLong() && opened != C.LENGTH_UNSET.toLong()) {
                entry.length = opened
            }
            val total = entry.length.takeIf { it != C.LENGTH_UNSET.toLong() }
            val bodyLength = when {
                requested != C.LENGTH_UNSET.toLong() -> requested
                opened != C.LENGTH_UNSET.toLong() -> opened
                total != null -> total - start
                else -> null
            }
            val headers = linkedMapOf(
                "Content-Type" to mimeType,
                "Accept-Ranges" to "bytes",
            )
            if (bodyLength != null) headers["Content-Length"] = bodyLength.toString()
            val partial = range != null
            if (partial) {
                val end = bodyLength?.let { start + it - 1 }
                headers["Content-Range"] = if (end != null) {
                    "bytes $start-$end/${total ?: "*"}"
                } else {
                    "bytes $start-*/${total ?: "*"}"
                }
            }
            writeHead(output, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", headers)
            if (request.method == "HEAD") return
            copy(source, output, bodyLength)
        } finally {
            runCatching { source.close() }
        }
    }

    private fun serveImage(entry: Entry, request: HttpRequest, output: OutputStream) {
        val stream: InputStream = try {
            context.contentResolver.openInputStream(entry.uri)
        } catch (_: Exception) {
            null
        } ?: run {
            writeStatus(output, 404, "Not Found")
            return
        }
        stream.use {
            val bytes = it.readBytes()
            val mimeType = context.contentResolver.getType(entry.uri)
                ?: CastMime.sniffImage(bytes)
                ?: "image/jpeg"
            writeHead(
                output,
                200,
                "OK",
                mapOf("Content-Type" to mimeType, "Content-Length" to bytes.size.toString()),
            )
            if (request.method != "HEAD") output.write(bytes)
        }
    }

    /** Opens the head of the stream once and remembers what it turned out to be. */
    private fun ensureMimeType(entry: Entry): String? {
        entry.mimeType?.let { return it }
        val source = dataSourceFactory.createDataSource()
        try {
            val opened = source.open(DataSpec.Builder().setUri(entry.uri).build())
            if (opened != C.LENGTH_UNSET.toLong()) entry.length = opened
            val head = ByteArray(SNIFF_BYTES)
            var filled = 0
            while (filled < head.size) {
                val read = source.read(head, filled, head.size - filled)
                if (read == C.RESULT_END_OF_INPUT) break
                filled += read
            }
            val mimeType = CastMime.sniff(head.copyOf(filled))
            entry.mimeType = mimeType
            return mimeType
        } finally {
            runCatching { source.close() }
        }
    }

    private fun copy(source: DataSource, output: OutputStream, length: Long?) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var remaining = length ?: Long.MAX_VALUE
        while (remaining > 0) {
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun writeStatus(
        output: OutputStream,
        code: Int,
        reason: String,
        extra: Map<String, String> = emptyMap(),
    ) = writeHead(output, code, reason, extra + ("Content-Length" to "0"))

    private fun writeHead(output: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            // The receiver is a web page on another origin; <audio> does not
            // need this, but a receiver that probes with fetch() does.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
    }

    private companion object {
        const val TAG = "BitChordCast"
        const val AUDIO_PATH = "a"
        const val IMAGE_PATH = "i"
        const val CAPTIONS_PATH = "l"
        const val CAPTIONS_SCHEME = "bitchord-lyrics"
        const val AUDIO_PREFIX = "AUDIO|"
        const val BACKLOG = 16
        const val TOKEN_BYTES = 16
        const val READ_TIMEOUT_MS = 30_000
        const val SNIFF_BYTES = 64
        const val COPY_BUFFER_BYTES = 64 * 1024

        fun sourceKey(kind: Kind, uri: Uri): String = "${kind.name}|$uri"

        fun urlFor(host: String, port: Int, path: String, token: String): String {
            val authority = if (host.contains(':')) "[$host]" else host
            return "http://$authority:$port/$path/$token"
        }
    }
}

/** The request line and headers of one HTTP/1.x request. */
internal class HttpRequest(
    val method: String,
    val path: String,
    private val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    companion object {
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 64

        /** Null when the connection closed before a request line arrived. */
        fun read(input: InputStream): HttpRequest? {
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 2) return null
            val headers = HashMap<String, String>()
            repeat(MAX_HEADERS) {
                val line = readLine(input) ?: return@repeat
                if (line.isEmpty()) return HttpRequest(parts[0].uppercase(), parts[1].substringBefore('?'), headers)
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            return HttpRequest(parts[0].uppercase(), parts[1].substringBefore('?'), headers)
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
    }
}

/**
 * A single `Range: bytes=` request. Multi-range requests are answered with
 * their first range, which is all a media element ever asks for.
 */
internal data class HttpRange(val start: Long?, val end: Long?) {

    /** An inclusive span of bytes; [endInclusive] is null for "to the end". */
    data class Span(val start: Long, val endInclusive: Long?) {
        val length: Long? get() = endInclusive?.let { it - start + 1 }
    }

    /**
     * The bytes this range covers in a resource of [length] bytes, or null when
     * it cannot be satisfied. With the length unknown an open-ended or bounded
     * range is still served from its start; only a suffix range ("the last N
     * bytes") needs the length to mean anything.
     */
    fun resolve(length: Long?): Span? {
        if (start == null) {
            val suffix = end ?: return null
            if (length == null || suffix <= 0 || length == 0L) return null
            return Span((length - suffix).coerceAtLeast(0), length - 1)
        }
        if (length != null && start >= length) return null
        val last = when {
            end == null -> length?.minus(1)
            length != null -> minOf(end, length - 1)
            else -> end
        }
        if (last != null && last < start) return null
        return Span(start, last)
    }

    companion object {
        fun parse(header: String): HttpRange? {
            val spec = header.trim()
            if (!spec.startsWith("bytes=", ignoreCase = true)) return null
            val first = spec.substring(6).split(',').first().trim()
            val dash = first.indexOf('-')
            if (dash < 0) return null
            val startText = first.substring(0, dash).trim()
            val endText = first.substring(dash + 1).trim()
            val start = if (startText.isEmpty()) null else startText.toLongOrNull() ?: return null
            val end = if (endText.isEmpty()) null else endText.toLongOrNull() ?: return null
            if (start == null && end == null) return null
            if (start != null && start < 0) return null
            return HttpRange(start, end)
        }
    }
}

/** Content types the Default Media Receiver is known to play, told apart by their first bytes. */
internal object CastMime {

    fun sniff(head: ByteArray): String? {
        if (head.size < 4) return null
        fun at(offset: Int, vararg bytes: Int): Boolean =
            head.size >= offset + bytes.size && bytes.indices.all { head[offset + it].toInt() and 0xFF == bytes[it] }
        fun ascii(offset: Int, text: String): Boolean =
            at(offset, *text.map { it.code }.toIntArray())
        return when {
            ascii(0, "fLaC") -> "audio/flac"
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "audio/webm"
            ascii(4, "ftyp") -> "audio/mp4"
            ascii(0, "OggS") -> "audio/ogg"
            ascii(0, "ID3") -> "audio/mpeg"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
            // ADTS AAC: 12-bit sync word, layer bits 00.
            at(0, 0xFF) && (head[1].toInt() and 0xF6) == 0xF0 -> "audio/aac"
            // MPEG audio frame sync.
            at(0, 0xFF) && (head[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
            else -> null
        }
    }

    fun sniffImage(head: ByteArray): String? = when {
        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "image/jpeg"
        head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "image/png"
        head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}

/** Which of this phone's addresses a device at [remote] can reach it on. */
internal object LocalAddress {

    fun pickFor(remote: InetAddress?): String? {
        val candidates = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { nif -> nif.interfaceAddresses.map { nif to it } }
                .filter { (_, address) ->
                    val inet = address.address
                    inet is java.net.Inet4Address && !inet.isLoopbackAddress && !inet.isLinkLocalAddress
                }
        }.getOrDefault(emptyList())
        if (remote is java.net.Inet4Address) {
            candidates.firstOrNull { (_, address) ->
                sameSubnet(address.address, remote, address.networkPrefixLength.toInt())
            }?.let { return it.second.address.hostAddress }
        }
        // No cast device address to match against: the Wi-Fi or Ethernet
        // interface is where a receiver is, never the cellular one.
        return candidates
            .sortedBy { (nif, _) ->
                val name = nif.name.lowercase()
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") || name.startsWith("swlan") -> 2
                    name.startsWith("rmnet") || name.startsWith("ccmni") -> 9
                    else -> 5
                }
            }
            .firstOrNull()
            ?.second?.address?.hostAddress
    }

    fun sameSubnet(a: InetAddress, b: InetAddress, prefixLength: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size || prefixLength !in 0..(x.size * 8)) return false
        var bits = prefixLength
        for (i in x.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }
}
