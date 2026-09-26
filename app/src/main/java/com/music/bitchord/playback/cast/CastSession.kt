package com.music.bitchord.playback.cast

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.music.bitchord.data.TrackLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * One connection to one Cast device, running one receiver app: the whole of
 * what BitChord needs from Google Cast, spoken over [CastProtocol].
 *
 * Lifecycle: [open] connects over TLS, launches (or joins) [appId] and reports
 * [Listener.onReady]; from then on [load] and the transport commands drive the
 * receiver, and every status the receiver sends comes back through
 * [Listener.onMediaStatus] / [Listener.onVolume]. [close] ends it, optionally
 * stopping the app on the TV. A lost connection, a stopped app, or another
 * sender replacing it all end in [Listener.onClosed].
 *
 * Socket work runs on its own threads; every listener call arrives on the main
 * thread, like everything else that touches the players.
 */
internal class CastSession(
    private val host: String,
    private val port: Int,
    private val appId: String,
    private val listener: Listener,
) {

    interface Listener {
        fun onReady()
        fun onMediaStatus(status: CastProtocol.MediaStatus?)
        fun onVolume(level: Double, muted: Boolean)
        fun onLoadFailed(reason: String)
        fun onCustomMessage(namespace: String, payload: String) = Unit
        fun onClosed(error: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val requestIds = AtomicInteger(1)
    private val senderId = "sender-" + (SecureRandom().nextInt(900_000) + 100_000)

    /** Every write goes through here: sockets must not be written from the main thread. */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cast-writer").apply { isDaemon = true }
    }

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closed = false
    @Volatile private var lastInboundAt = 0L

    /** The running receiver app, once launched. Main thread. */
    private var app: CastProtocol.RunningApp? = null
    private var readyReported = false

    /** The latest media status, and when it arrived, for extrapolating the playhead. Main thread. */
    var mediaStatus: CastProtocol.MediaStatus? = null
        private set
    private var mediaStatusAt = 0L

    /** The receiver's queue, in order. Main thread. */
    var queueItemIds: List<Int> = emptyList()
        private set

    var volumeLevel: Double = 1.0
        private set
    var muted: Boolean = false
        private set

    val isReady: Boolean get() = readyReported && !closed

    fun open() {
        Thread({ run() }, "cast-session").apply { isDaemon = true }.start()
    }

    /** Ends the session. [stopApp] also closes the receiver app on the TV. */
    fun close(stopApp: Boolean) {
        if (closed) return
        val running = app
        if (stopApp && running != null) {
            send(CastProtocol.NS_RECEIVER, CastProtocol.PLATFORM_ID, CastProtocol.stopApp(nextId(), running.sessionId))
        }
        running?.let { send(CastProtocol.NS_CONNECTION, it.transportId, CastProtocol.close()) }
        shutDown(null, notify = false)
    }

    // --- Commands (main thread) ----------------------------------------------

    fun load(request: CastProtocol.LoadRequest) {
        val running = app ?: return
        send(CastProtocol.NS_MEDIA, running.transportId, CastProtocol.load(nextId(), running.sessionId, request))
    }

    /** Queues [request] after the current track, preloaded ahead of the handover. */
    fun queueNext(request: CastProtocol.LoadRequest) {
        val running = app ?: return
        val session = mediaStatus?.mediaSessionId ?: return
        send(
            CastProtocol.NS_MEDIA,
            running.transportId,
            CastProtocol.queueInsert(nextId(), session, request, PRELOAD_SECONDS),
        )
        requestQueueItemIds()
    }

    private fun requestQueueItemIds() {
        val running = app ?: return
        val session = mediaStatus?.mediaSessionId ?: return
        send(CastProtocol.NS_MEDIA, running.transportId, CastProtocol.queueGetItemIds(nextId(), session))
    }

    fun queueRemove(itemIds: List<Int>) {
        if (itemIds.isEmpty()) return
        val running = app ?: return
        val session = mediaStatus?.mediaSessionId ?: return
        send(CastProtocol.NS_MEDIA, running.transportId, CastProtocol.queueRemove(nextId(), session, itemIds))
    }

    /** Skips to the next queued track, which the receiver has already preloaded. */
    fun queueSkip() {
        val running = app ?: return
        val session = mediaStatus?.mediaSessionId ?: return
        send(CastProtocol.NS_MEDIA, running.transportId, CastProtocol.queueJump(nextId(), session, 1))
    }

    fun play() = mediaCommand("PLAY")
    fun pause() = mediaCommand("PAUSE")
    fun stopMedia() = mediaCommand("STOP")

    fun seek(positionMs: Long) {
        val running = app ?: return
        val session = mediaStatus?.mediaSessionId ?: return
        send(CastProtocol.NS_MEDIA, running.transportId, CastProtocol.seek(nextId(), session, positionMs))
        // Reflected straight away: the receiver's confirmation takes a round trip.
        mediaStatus = mediaStatus?.copy(positionMs = positionMs.coerceAtLeast(0))
        mediaStatusAt = SystemClock.elapsedRealtime()
    }

    fun setPlaybackRate(rate: Double) {
        val running = app ?: return
        val session = mediaStatus?.mediaSessionId ?: return
        send(CastProtocol.NS_MEDIA, running.transportId, CastProtocol.setPlaybackRate(nextId(), session, rate))
    }

    fun setVolume(level: Double) {
        volumeLevel = level.coerceIn(0.0, 1.0)
        send(CastProtocol.NS_RECEIVER, CastProtocol.PLATFORM_ID, CastProtocol.setVolume(nextId(), level = volumeLevel))
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        send(CastProtocol.NS_RECEIVER, CastProtocol.PLATFORM_ID, CastProtocol.setVolume(nextId(), muted = muted))
    }

    /** A message on the receiver app's own channel — BitChord's lyrics, for one. */
    fun sendCustom(namespace: String, payload: String) {
        val running = app ?: return
        send(namespace, running.transportId, payload)
    }

    /** Where the receiver is now: its last report, run forward while it plays. */
    fun currentPositionMs(): Long {
        val status = mediaStatus ?: return 0L
        if (status.playerState != CastProtocol.PlayerState.PLAYING) return status.positionMs
        val elapsed = SystemClock.elapsedRealtime() - mediaStatusAt
        val position = status.positionMs + (elapsed * status.playbackRate).toLong()
        return status.durationMs?.let { position.coerceAtMost(it) } ?: position
    }

    private fun mediaCommand(type: String) {
        val running = app ?: return
        val session = mediaStatus?.mediaSessionId ?: return
        send(CastProtocol.NS_MEDIA, running.transportId, CastProtocol.mediaCommand(type, nextId(), session))
    }

    // --- Connection ----------------------------------------------------------

    private fun run() {
        try {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(AcceptDeviceCertificate), SecureRandom())
            val raw = context.socketFactory.createSocket() as SSLSocket
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            raw.startHandshake()
            if (closed) {
                // Closed while still connecting.
                runCatching { raw.close() }
                return
            }
            socket = raw
            output = raw.outputStream
            lastInboundAt = SystemClock.elapsedRealtime()
            val input = DataInputStream(raw.inputStream.buffered())

            // Before anything else crosses the connection: the device proves it
            // is a genuine Cast device, bound to this very TLS certificate.
            raw.soTimeout = AUTH_TIMEOUT_MS.toInt()
            authenticate(raw, input)
            raw.soTimeout = READ_TIMEOUT_MS

            send(CastProtocol.NS_CONNECTION, CastProtocol.PLATFORM_ID, CastProtocol.connect())
            send(CastProtocol.NS_RECEIVER, CastProtocol.PLATFORM_ID, CastProtocol.launch(nextId(), appId))
            startHeartbeat()

            while (!closed) {
                val message = readMessage(input)
                main.post { handle(message) }
            }
        } catch (e: Exception) {
            if (!closed) {
                if (e is CastDeviceAuth.AuthException) {
                    TrackLog.d(TAG, "cast device $host failed authentication: ${e.message}")
                }
                TrackLog.d(TAG, "cast connection to $host lost: ${e.javaClass.simpleName} ${e.message}")
                main.post { shutDown(e.message ?: e.javaClass.simpleName, notify = true) }
            }
        }
    }

    private fun readMessage(input: DataInputStream): CastProtocol.Message {
        val length = input.readInt()
        if (length <= 0 || length > CastProtocol.MAX_MESSAGE_BYTES) throw IOException("bad frame length $length")
        val body = ByteArray(length)
        input.readFully(body)
        lastInboundAt = SystemClock.elapsedRealtime()
        return CastProtocol.decode(body)
    }

    /**
     * Challenges the device and verifies its answer — see [CastDeviceAuth].
     * Runs on the connection thread before any other message is sent; throws
     * when the device cannot prove itself, which ends the session.
     */
    private fun authenticate(socket: SSLSocket, input: DataInputStream) {
        val peerCertificate = socket.session.peerCertificates.firstOrNull()?.encoded
            ?: throw CastDeviceAuth.AuthException("no TLS certificate")
        val nonce = CastDeviceAuth.newNonce()
        writeNow(
            CastProtocol.Message(
                senderId,
                CastProtocol.PLATFORM_ID,
                CastDeviceAuth.NAMESPACE,
                payload = "",
                payloadBinary = CastDeviceAuth.challenge(nonce),
            ),
        )
        val deadline = SystemClock.elapsedRealtime() + AUTH_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val message = readMessage(input)
            if (message.namespace != CastDeviceAuth.NAMESPACE) continue
            val reply = message.payloadBinary ?: throw CastDeviceAuth.AuthException("empty authentication reply")
            CastDeviceAuth.verify(CastDeviceAuth.parseResponse(reply), peerCertificate, nonce)
            TrackLog.d(TAG, "cast device $host authenticated")
            return
        }
        throw CastDeviceAuth.AuthException("the device did not answer the authentication challenge")
    }

    /** A write on the connection thread itself, ahead of the writer queue — the auth challenge. */
    private fun writeNow(message: CastProtocol.Message) {
        val out = output ?: throw IOException("not connected")
        out.write(CastProtocol.encode(message))
        out.flush()
    }

    private fun startHeartbeat() {
        Thread({
            while (!closed) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (closed) return@Thread
                if (SystemClock.elapsedRealtime() - lastInboundAt > DEAD_AFTER_MS) {
                    main.post { shutDown("device stopped answering", notify = true) }
                    return@Thread
                }
                send(CastProtocol.NS_HEARTBEAT, CastProtocol.PLATFORM_ID, CastProtocol.ping())
            }
        }, "cast-heartbeat").apply { isDaemon = true }.start()
    }

    private fun handle(message: CastProtocol.Message) {
        if (closed) return
        if (message.namespace == CastProtocol.NS_HEARTBEAT) {
            if (message.payload.contains("\"PING\"")) {
                send(CastProtocol.NS_HEARTBEAT, message.sourceId, CastProtocol.pong())
            }
            return
        }
        if (message.namespace != CastProtocol.NS_CONNECTION &&
            message.namespace != CastProtocol.NS_RECEIVER &&
            message.namespace != CastProtocol.NS_MEDIA
        ) {
            listener.onCustomMessage(message.namespace, message.payload)
            return
        }
        val payload = runCatching { Json.parseToJsonElement(message.payload) as JsonObject }.getOrNull() ?: return
        when (message.namespace) {
            CastProtocol.NS_CONNECTION ->
                if (CastProtocol.type(payload) == "CLOSE" && message.sourceId == app?.transportId) {
                    shutDown("receiver app closed", notify = true)
                }
            CastProtocol.NS_RECEIVER -> onReceiverMessage(payload)
            CastProtocol.NS_MEDIA -> onMediaMessage(payload)
        }
    }

    private fun onReceiverMessage(payload: JsonObject) {
        when (CastProtocol.type(payload)) {
            "RECEIVER_STATUS" -> {
                val status = CastProtocol.parseReceiverStatus(payload, appId)
                status.volumeLevel?.let { volumeLevel = it }
                status.muted?.let { muted = it }
                if (status.volumeLevel != null || status.muted != null) listener.onVolume(volumeLevel, muted)
                val running = status.app
                val current = app
                when {
                    running == null && current != null ->
                        // Our app is gone from the TV: stopped there, or replaced
                        // by another app someone cast.
                        shutDown("receiver app stopped", notify = true)
                    running != null && current?.transportId != running.transportId -> {
                        app = running
                        send(CastProtocol.NS_CONNECTION, running.transportId, CastProtocol.connect())
                        send(CastProtocol.NS_MEDIA, running.transportId, CastProtocol.getStatus(nextId()))
                        if (!readyReported) {
                            readyReported = true
                            listener.onReady()
                        }
                    }
                }
            }
            "LAUNCH_ERROR" -> shutDown("the TV refused to start the receiver", notify = true)
        }
    }

    private fun onMediaMessage(payload: JsonObject) {
        when (CastProtocol.type(payload)) {
            "MEDIA_STATUS" -> {
                val previousSession = mediaStatus?.mediaSessionId
                mediaStatus = CastProtocol.parseMediaStatus(payload, mediaStatus)
                mediaStatusAt = SystemClock.elapsedRealtime()
                val status = mediaStatus
                when {
                    status == null -> queueItemIds = emptyList()
                    status.mediaSessionId != previousSession -> queueItemIds = status.itemIds
                    (payload["status"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                        ?.let { it as? JsonObject }?.containsKey("items") == true -> queueItemIds = status.itemIds
                }
                listener.onMediaStatus(mediaStatus)
            }
            // The queue changed shape; its status may not list the items, so ask.
            "QUEUE_CHANGE" -> requestQueueItemIds()
            "QUEUE_ITEM_IDS" -> queueItemIds = CastProtocol.parseItemIds(payload)
            "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "INVALID_PLAYER_STATE" ->
                listener.onLoadFailed(CastProtocol.type(payload).orEmpty())
        }
    }

    private fun shutDown(error: String?, notify: Boolean) {
        if (closed) return
        closed = true
        // Behind whatever is still queued to send — a STOP or CLOSE asked for
        // on the way out gets written before the socket goes.
        runCatching {
            writer.execute {
                runCatching { socket?.close() }
                socket = null
                output = null
            }
            writer.shutdown()
        }
        if (notify) listener.onClosed(error)
    }

    private fun send(namespace: String, destination: String, payload: String) {
        if (closed) return
        val bytes = CastProtocol.encode(CastProtocol.Message(senderId, destination, namespace, payload))
        runCatching {
            writer.execute {
                val out = output ?: return@execute
                try {
                    out.write(bytes)
                    out.flush()
                } catch (e: IOException) {
                    main.post { shutDown(e.message, notify = true) }
                }
            }
        }
    }

    private fun nextId(): Int = requestIds.getAndIncrement()

    /**
     * Admits the device's TLS certificate for the handshake only. Cast devices
     * serve short-lived self-signed certificates that no public CA can vouch
     * for, so a CA check here would reject every real device. They are
     * authenticated instead, before a single message is exchanged, by
     * [authenticate]: the device signs this exact certificate with its
     * Google-issued device key, and the chain is verified to Google's Cast
     * roots — the same check Google's own Cast library makes. A connection
     * whose certificate is not so signed is closed.
     */
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AcceptDeviceCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("no server certificate")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val TAG = "BitChordCast"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 30_000
        const val HEARTBEAT_MS = 5_000L
        const val DEAD_AFTER_MS = 20_000L
        const val AUTH_TIMEOUT_MS = 8_000L

        /** How long before a track ends the receiver starts fetching the next one. */
        const val PRELOAD_SECONDS = 20
    }
}
