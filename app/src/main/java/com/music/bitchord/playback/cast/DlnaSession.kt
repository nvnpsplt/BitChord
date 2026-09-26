package com.music.bitchord.playback.cast

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.music.bitchord.data.TrackLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Plays to a DLNA / UPnP AV media renderer — the TVs, AV receivers and
 * speakers that are not Cast devices — through its AVTransport and
 * RenderingControl services.
 *
 * A renderer does not report its state; it is asked. So this polls transport
 * state and position once a second and turns the answers into the same
 * [CastProtocol.MediaStatus] a Cast receiver sends, which is all the bridge
 * reads. A renderer plays one track at a time: there is no queue, so the next
 * track is loaded when the current one finishes.
 *
 * Commands are called on the main thread and run in order on a worker thread;
 * listener calls arrive on the main thread.
 */
internal class DlnaSession(
    private val renderer: DlnaRenderer,
    private val listener: RemoteSession.Listener,
) : RemoteSession {

    private val main = Handler(Looper.getMainLooper())

    /** One thread for commands and polls alike, so a poll never overtakes a command. */
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dlna-session").apply { isDaemon = true }
    }

    @Volatile private var closed = false
    private var ready = false

    override var mediaStatus: CastProtocol.MediaStatus? = null
        private set
    private var mediaStatusAt = 0L

    override val queueItemIds: List<Int> get() = emptyList()
    override val supportsQueue: Boolean get() = false
    override val supportsCustomMessages: Boolean get() = false

    override var volumeLevel: Double = 1.0
        private set
    override var muted: Boolean = false
        private set
    override val isReady: Boolean get() = ready && !closed

    // What was last loaded, and how it has gone since. Main thread.
    private var loadedUrl: String? = null
    private var mediaSessionId = 0
    private var loadedAt = 0L
    private var sawPlaying = false
    private var stopRequested = false
    private var pollFailures = 0

    // Worker thread.
    private var pollCount = 0

    override fun open() {
        submit {
            try {
                call(Dlna.AV_TRANSPORT, renderer.avTransportUrl, "GetTransportInfo", instance())
                val volume = readVolume()
                main.post {
                    if (closed) return@post
                    volume?.let { (level, mute) -> applyVolume(level, mute) }
                    ready = true
                    listener.onReady()
                }
                worker.scheduleWithFixedDelay({ poll() }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                TrackLog.d(TAG, "DLNA renderer unreachable: ${e.message}")
                main.post { shutDown(e.message ?: "unreachable") }
            }
        }
    }

    override fun close(stopApp: Boolean) {
        if (closed) return
        closed = true
        val stop = stopApp && loadedUrl != null
        submit {
            if (stop) runCatching { call(Dlna.AV_TRANSPORT, renderer.avTransportUrl, "Stop", instance()) }
            worker.shutdown()
        }
    }

    // --- Commands (main thread) ----------------------------------------------

    override fun load(request: CastProtocol.LoadRequest) {
        mediaSessionId++
        loadedUrl = request.url
        loadedAt = SystemClock.elapsedRealtime()
        sawPlaying = false
        stopRequested = false
        publish(
            CastProtocol.MediaStatus(
                mediaSessionId = mediaSessionId,
                playerState = CastProtocol.PlayerState.BUFFERING,
                idleReason = null,
                positionMs = request.startPositionMs,
                contentId = request.url,
                durationMs = null,
                playbackRate = 1.0,
            ),
        )
        val session = mediaSessionId
        submit {
            if (closed) return@submit
            // Some renderers refuse a new URI while playing the old one.
            runCatching { call(Dlna.AV_TRANSPORT, renderer.avTransportUrl, "Stop", instance()) }
            try {
                call(
                    Dlna.AV_TRANSPORT,
                    renderer.avTransportUrl,
                    "SetAVTransportURI",
                    instance() + listOf("CurrentURI" to request.url, "CurrentURIMetaData" to Dlna.didl(request)),
                )
                call(Dlna.AV_TRANSPORT, renderer.avTransportUrl, "Play", instance() + ("Speed" to "1"))
            } catch (e: Exception) {
                val reason = e.message ?: "load failed"
                TrackLog.d(TAG, "DLNA renderer refused the track: $reason")
                main.post { if (!closed && session == mediaSessionId) listener.onLoadFailed(reason) }
                return@submit
            }
            if (request.startPositionMs >= MIN_SEEK_MS) {
                // Seeking needs the renderer to have opened the stream first.
                waitForTransport(setOf("PLAYING", "PAUSED_PLAYBACK"))
                runCatching { seekNow(request.startPositionMs) }
            }
            if (!request.autoplay) runCatching { call(Dlna.AV_TRANSPORT, renderer.avTransportUrl, "Pause", instance()) }
        }
    }

    override fun queueNext(request: CastProtocol.LoadRequest) = Unit
    override fun queueRemove(itemIds: List<Int>) = Unit
    override fun queueSkip() = Unit

    override fun play() {
        command(Dlna.AV_TRANSPORT, "Play", instance() + ("Speed" to "1"))
    }

    override fun pause() {
        command(Dlna.AV_TRANSPORT, "Pause", instance())
    }

    override fun stopMedia() {
        stopRequested = true
        command(Dlna.AV_TRANSPORT, "Stop", instance())
    }

    override fun seek(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        submit { if (!closed) runCatching { seekNow(target) } }
        // Reflected straight away: the next poll confirms it.
        mediaStatus = mediaStatus?.copy(positionMs = target)
        mediaStatusAt = SystemClock.elapsedRealtime()
    }

    /** Renderers play at normal speed only. */
    override fun setPlaybackRate(rate: Double) = Unit

    override fun setVolume(level: Double) {
        volumeLevel = level.coerceIn(0.0, 1.0)
        val control = renderer.renderingControlUrl ?: return
        val desired = (volumeLevel * 100).roundToInt().toString()
        submit {
            if (!closed) {
                runCatching {
                    call(Dlna.RENDERING_CONTROL, control, "SetVolume", instance() + master() + ("DesiredVolume" to desired))
                }
            }
        }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        val control = renderer.renderingControlUrl ?: return
        submit {
            if (!closed) {
                runCatching {
                    call(Dlna.RENDERING_CONTROL, control, "SetMute", instance() + master() + ("DesiredMute" to if (muted) "1" else "0"))
                }
            }
        }
    }

    /** A renderer runs no app of ours to message. */
    override fun sendCustom(namespace: String, payload: String) = Unit

    override fun currentPositionMs(): Long {
        val status = mediaStatus ?: return 0L
        if (status.playerState != CastProtocol.PlayerState.PLAYING) return status.positionMs
        val position = status.positionMs + (SystemClock.elapsedRealtime() - mediaStatusAt)
        return status.durationMs?.let { position.coerceAtMost(it) } ?: position
    }

    // --- Polling (worker thread) ---------------------------------------------

    private fun poll() {
        if (closed) return
        try {
            val transport = call(Dlna.AV_TRANSPORT, renderer.avTransportUrl, "GetTransportInfo", instance())
            val position = call(Dlna.AV_TRANSPORT, renderer.avTransportUrl, "GetPositionInfo", instance())
            val volume = if (pollCount++ % VOLUME_EVERY_POLLS == 0) readVolume() else null
            val state = transport["CurrentTransportState"]?.trim().orEmpty()
            val positionMs = Dlna.parseTime(position["RelTime"])
            val durationMs = Dlna.parseTime(position["TrackDuration"])?.takeIf { it > 0 }
            main.post {
                if (closed) return@post
                pollFailures = 0
                onPolled(state, positionMs, durationMs)
                volume?.let { (level, mute) -> applyVolume(level, mute) }
            }
        } catch (e: Exception) {
            main.post {
                if (closed) return@post
                if (++pollFailures >= MAX_POLL_FAILURES) {
                    TrackLog.d(TAG, "DLNA renderer stopped answering: ${e.message}")
                    shutDown(e.message ?: "renderer stopped answering")
                }
            }
        }
    }

    /** Main thread: one poll's answers, as the status a Cast receiver would have sent. */
    private fun onPolled(state: String, positionMs: Long?, durationMs: Long?) {
        val url = loadedUrl ?: return
        val previous = mediaStatus
        val sinceLoad = SystemClock.elapsedRealtime() - loadedAt
        val playerState: CastProtocol.PlayerState
        var idleReason: String? = null
        when (state.uppercase()) {
            "PLAYING" -> {
                playerState = CastProtocol.PlayerState.PLAYING
                sawPlaying = true
            }
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> playerState = CastProtocol.PlayerState.PAUSED
            "TRANSITIONING" -> playerState = CastProtocol.PlayerState.BUFFERING
            else -> {
                // STOPPED or NO_MEDIA_PRESENT. Straight after a load that is
                // the renderer still opening the stream, not the end of it.
                if (!sawPlaying && !stopRequested && sinceLoad < LOAD_GRACE_MS) {
                    playerState = CastProtocol.PlayerState.BUFFERING
                } else {
                    playerState = CastProtocol.PlayerState.IDLE
                    idleReason = when {
                        stopRequested -> "CANCELLED"
                        sawPlaying -> "FINISHED"
                        else -> "ERROR"
                    }
                }
            }
        }
        val position = when {
            idleReason == "FINISHED" -> durationMs ?: previous?.durationMs ?: positionMs ?: 0L
            else -> positionMs ?: previous?.positionMs ?: 0L
        }
        publish(
            CastProtocol.MediaStatus(
                mediaSessionId = mediaSessionId,
                playerState = playerState,
                idleReason = idleReason,
                positionMs = position,
                contentId = url,
                durationMs = durationMs ?: previous?.durationMs,
                playbackRate = 1.0,
            ),
        )
    }

    private fun publish(status: CastProtocol.MediaStatus) {
        mediaStatus = status
        mediaStatusAt = SystemClock.elapsedRealtime()
        listener.onMediaStatus(status)
    }

    private fun applyVolume(level: Double, mute: Boolean) {
        if (level == volumeLevel && mute == muted) return
        volumeLevel = level
        muted = mute
        listener.onVolume(level, mute)
    }

    private fun shutDown(error: String?) {
        if (closed) return
        closed = true
        worker.shutdownNow()
        listener.onClosed(error)
    }

    // --- SOAP (worker thread) ------------------------------------------------

    private fun seekNow(positionMs: Long) {
        call(
            Dlna.AV_TRANSPORT,
            renderer.avTransportUrl,
            "Seek",
            instance() + listOf("Unit" to "REL_TIME", "Target" to Dlna.formatTime(positionMs)),
        )
    }

    private fun waitForTransport(states: Set<String>) {
        val deadline = SystemClock.elapsedRealtime() + LOAD_GRACE_MS
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            val state = runCatching {
                call(Dlna.AV_TRANSPORT, renderer.avTransportUrl, "GetTransportInfo", instance())["CurrentTransportState"]
            }.getOrNull()
            if (state != null && state.trim().uppercase() in states) return
            Thread.sleep(POLL_MS / 2)
        }
    }

    /** Volume 0..1 and mute, or null where the renderer has no RenderingControl. */
    private fun readVolume(): Pair<Double, Boolean>? {
        val control = renderer.renderingControlUrl ?: return null
        return runCatching {
            val volume = call(Dlna.RENDERING_CONTROL, control, "GetVolume", instance() + master())["CurrentVolume"]
                ?.trim()?.toIntOrNull() ?: return null
            val mute = runCatching {
                call(Dlna.RENDERING_CONTROL, control, "GetMute", instance() + master())["CurrentMute"]?.trim()
            }.getOrNull()
            (volume.coerceIn(0, 100) / 100.0) to (mute == "1" || mute.equals("true", ignoreCase = true))
        }.getOrNull()
    }

    private fun command(serviceType: String, action: String, args: List<Pair<String, String>>) {
        val url = if (serviceType == Dlna.AV_TRANSPORT) renderer.avTransportUrl else renderer.renderingControlUrl ?: return
        submit {
            if (closed) return@submit
            runCatching { call(serviceType, url, action, args) }
                .onFailure { TrackLog.d(TAG, "DLNA $action failed: ${it.message}") }
        }
    }

    private fun call(serviceType: String, url: String, action: String, args: List<Pair<String, String>>): Map<String, String> {
        val body = Dlna.soap(serviceType, action, args).toByteArray(Charsets.UTF_8)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", Dlna.soapAction(serviceType, action))
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code in 200..299) {
                val reply = connection.inputStream.use { it.readBytes() }
                return Dlna.parseResponse(reply) ?: emptyMap()
            }
            val fault = connection.errorStream?.use { it.readBytes() }?.let(Dlna::parseFault)
            throw IOException("$action: ${fault ?: "HTTP $code"}")
        } finally {
            connection.disconnect()
        }
    }

    /** Runs [task] on the worker; a no-op once the session has shut down. */
    private fun submit(task: () -> Unit) {
        try {
            worker.execute(task)
        } catch (_: RejectedExecutionException) {
            // Closed: nothing left to tell the renderer.
        }
    }

    private fun instance(): List<Pair<String, String>> = listOf("InstanceID" to "0")
    private fun master(): Pair<String, String> = "Channel" to "Master"

    private companion object {
        const val TAG = "BitChordCast"
        const val POLL_MS = 1_000L
        const val TIMEOUT_MS = 5_000
        const val VOLUME_EVERY_POLLS = 5
        const val MAX_POLL_FAILURES = 10

        /** How long a renderer may take to start a track before STOPPED means it could not. */
        const val LOAD_GRACE_MS = 10_000L

        /** A resume point this close to the start is not worth a seek some renderers fumble. */
        const val MIN_SEEK_MS = 2_000L
    }
}
