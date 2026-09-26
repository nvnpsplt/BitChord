package com.music.bitchord.playback.cast

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.music.bitchord.data.TrackLog
import java.io.IOException
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
 * reads. Where the renderer takes one (SetNextAVTransportURI), the next track
 * is handed over ahead so it runs straight on; where it does not, the next
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
    /**
     * Whether the renderer takes a next track ahead of time
     * (SetNextAVTransportURI, optional in UPnP AV). Assumed until it refuses one.
     */
    override val supportsQueue: Boolean get() = nextSupported

    @Volatile private var nextSupported = true
    override val supportsCustomMessages: Boolean get() = false

    override var volumeLevel: Double = 1.0
        private set
    override var muted: Boolean = false
        private set
    override val isReady: Boolean get() = ready && !closed

    // What was last loaded, and how it has gone since. Main thread.
    private var loadedUrl: String? = null

    /** The track handed over with SetNextAVTransportURI, to start when this one ends. Main thread. */
    private var nextUrl: String? = null
    private var nextRequest: CastProtocol.LoadRequest? = null
    private var lastPositionMs: Long? = null
    private var lastDurationMs: Long? = null
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
                try {
                    call(Dlna.AV_TRANSPORT, renderer.avTransportUrl, "GetTransportInfo", instance())
                } catch (fault: SoapFault) {
                    // It is there and answering; some renderers refuse status
                    // queries while they have nothing loaded.
                    TrackLog.d(TAG, "DLNA renderer answered GetTransportInfo with ${fault.message}")
                }
                val volume = readVolume()
                main.post {
                    if (closed) return@post
                    volume?.let { (level, mute) -> applyVolume(level, mute) }
                    ready = true
                    listener.onReady()
                }
                worker.scheduleWithFixedDelay({ poll() }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                TrackLog.d(TAG, "DLNA renderer unreachable at ${renderer.avTransportUrl}: ${e.javaClass.simpleName}: ${e.message}")
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
        // A new transport URI replaces whatever was set to follow the old one.
        nextUrl = null
        nextRequest = null
        lastPositionMs = null
        lastDurationMs = null
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

    override fun queueNext(request: CastProtocol.LoadRequest) {
        if (!nextSupported) return
        nextUrl = request.url
        nextRequest = request
        submit {
            if (closed) return@submit
            try {
                call(
                    Dlna.AV_TRANSPORT,
                    renderer.avTransportUrl,
                    "SetNextAVTransportURI",
                    instance() + listOf("NextURI" to request.url, "NextURIMetaData" to Dlna.didl(request)),
                )
            } catch (e: Exception) {
                // Not every renderer has it: from now on the next track is
                // loaded when this one finishes, as the bridge does without a queue.
                TrackLog.d(TAG, "DLNA renderer takes no next track: ${e.message}")
                nextSupported = false
                main.post { if (nextUrl == request.url) nextUrl = null }
            }
        }
    }

    /** Clears the next track: the bridge only removes items to replace or drop what follows. */
    override fun queueRemove(itemIds: List<Int>) {
        if (!nextSupported || nextUrl == null) return
        nextUrl = null
        nextRequest = null
        submit {
            if (closed) return@submit
            runCatching {
                call(
                    Dlna.AV_TRANSPORT,
                    renderer.avTransportUrl,
                    "SetNextAVTransportURI",
                    instance() + listOf("NextURI" to "", "NextURIMetaData" to ""),
                )
            }
        }
    }

    /**
     * Starts the next track now — as a plain load: UPnP's Next action means
     * "next in the renderer's playlist", which not every renderer applies to
     * a next URI.
     */
    override fun queueSkip() {
        val request = nextRequest?.takeIf { it.url == nextUrl } ?: return
        load(request.copy(startPositionMs = 0, autoplay = true))
    }

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
        // Reflected straight away: the next poll confirms it. Also taken as the
        // last known playhead, so a seek back to the start is not read as the
        // renderer running on into the next track.
        lastPositionMs = target
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
            val trackUri = position["TrackURI"]?.trim()?.takeIf { it.isNotEmpty() && it != "NOT_IMPLEMENTED" }
            val positionMs = Dlna.parseTime(position["RelTime"])
            val durationMs = Dlna.parseTime(position["TrackDuration"])?.takeIf { it > 0 }
            main.post {
                if (closed) return@post
                pollFailures = 0
                onPolled(state, positionMs, durationMs, trackUri)
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
    private fun onPolled(state: String, positionMs: Long?, durationMs: Long?, trackUri: String?) {
        if (movedOnToNext(state, positionMs, trackUri)) {
            loadedUrl = nextUrl
            nextUrl = null
            nextRequest = null
            loadedAt = SystemClock.elapsedRealtime()
            sawPlaying = false
            stopRequested = false
            lastDurationMs = null
        }
        lastPositionMs = positionMs
        if (durationMs != null) lastDurationMs = durationMs
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

    /**
     * Whether the renderer has run on into [nextUrl]. Said outright where it
     * reports the track's URI; otherwise read from the playhead jumping back to
     * the start just as the old track was ending.
     */
    private fun movedOnToNext(state: String, positionMs: Long?, trackUri: String?): Boolean {
        val next = nextUrl ?: return false
        if (trackUri == next) return true
        if (trackUri == loadedUrl) return false
        // No URI, or one the renderer has rewritten: go by the playhead.
        if (!state.equals("PLAYING", ignoreCase = true) && !state.equals("TRANSITIONING", ignoreCase = true)) return false
        val before = lastPositionMs ?: return false
        val length = lastDurationMs ?: return false
        return before >= length - END_WINDOW_MS && (positionMs ?: return false) < START_WINDOW_MS
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
        val response = LanHttp.post(
            url,
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                // The spelling most control points send; some renderers match it exactly.
                "SOAPAction" to Dlna.soapAction(serviceType, action),
            ),
            body,
            TIMEOUT_MS,
        )
        if (response.code in 200..299) return Dlna.parseResponse(response.body) ?: emptyMap()
        val fault = Dlna.parseFault(response.body)
        throw SoapFault("$action: ${fault ?: "HTTP ${response.code}"}")
    }

    /** The renderer answered, and said no — as opposed to not answering at all. */
    private class SoapFault(message: String) : IOException(message)

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

        /** A playhead this near the end, then this near the start, is the next track begun. */
        const val END_WINDOW_MS = 5_000L
        const val START_WINDOW_MS = 4_000L
    }
}
