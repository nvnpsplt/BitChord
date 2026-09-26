package com.music.bitchord.playback.cast

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.source.ForwardingTimeline
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.bitchord.R
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * What the media session talks to while a cast device is playing.
 *
 * ## Why the queue stays on the phone
 *
 * The local ExoPlayer keeps the queue — AutoPlay's refill, the user-queue
 * tiers, shuffle, "sleep after this song", scrobbling, history, Discord and the
 * lock-screen lyric line are all written against it and its callbacks — and is
 * held silent in `STATE_IDLE`, where it resolves nothing, holds no audio focus
 * and renders nothing. The receiver is handed one track at a time over a
 * [CastSession], and everything that edits the queue edits the local player
 * exactly as it always has; this reconciles the receiver to it afterwards.
 *
 * ## Who owns what
 *
 * - **What plays** — the local player. Skips, jumps, reorders, AutoPlay
 *   appends, repeat and shuffle all land on it and are mirrored out.
 * - **Whether it plays** — the local player's `playWhenReady`, mirrored both
 *   ways: a pause from the notification reaches the TV, and a pause from the TV
 *   remote (or Google Home, or another phone) comes back.
 * - **Where the playhead is, how long the track is, whether it is buffering, and
 *   the volume** — the receiver. [getState] reports those from it, so the
 *   notification, the lock screen, Android Auto and the app's own scrubber all
 *   follow the TV, and the hardware volume keys drive the TV's volume.
 *
 * When the receiver finishes a track on its own, the local queue is advanced
 * to match — flagged through [Host.onRemoteAdvance] as the automatic advance it
 * is, so the service books the finished track as a listen rather than a skip.
 */
@UnstableApi
internal class CastBridgePlayer(
    local: Player,
    private val server: CastMediaServer,
    private val host: Host,
) : ForwardingSimpleBasePlayer(local) {

    interface Host {
        /** The address the receiver can reach this phone on, or null off Wi-Fi. */
        fun hostAddress(): String?

        /** The receiver started or stopped making sound. */
        fun onRemoteIsPlayingChanged(isPlaying: Boolean)

        /**
         * Runs [advance], which moves the local queue on, as an automatic
         * transition: the receiver played the previous track to its end.
         */
        fun onRemoteAdvance(advance: () -> Unit)

        /** The receiver played the last track of the queue to its end. */
        fun onRemoteQueueEnded()

        /** A new track was handed to the receiver — the moment to send it that track's lyrics. */
        fun onRemoteTrackLoaded(mediaId: String)
    }

    /** Whether this is bridging to a receiver right now. */
    val active: Boolean get() = session != null

    private var session: CastSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope = newScope()
    private var syncJob: Job? = null
    private var syncScheduled = false

    /** True while the receiver is being handed a new current track. */
    private var loading = false

    /** The URL just handed to the receiver, until the receiver reports on it. */
    private var pendingLoadUrl: String? = null

    /**
     * The track queued on the receiver after the current one, preloaded so it
     * starts without a gap — see [reconcileQueue].
     */
    private var queuedNextUrl: String? = null
    private var queuedNextMediaId: String? = null

    /** A next track that could not be queued (nothing playable resolved); not retried on every status. */
    private var unqueueableUrl: String? = null

    /** Set while this class moves the local player itself, so it does not echo the move back. */
    private var applyingOwnChange = 0

    /** An explicit seek happened since the last reconcile: the local position is authoritative. */
    private var seekedSinceSync = false

    /**
     * When this phone last told the receiver to play or pause. A receiver
     * state that disagrees with the local intent long after that is somebody
     * else's doing — the TV remote, Google Home — and is mirrored back.
     */
    private var lastTransportCommandAt = 0L

    /** The receiver's last reported state and track, to see what changed in a status. */
    private var lastState: CastProtocol.PlayerState? = null
    private var lastContentId: String? = null

    private var lastReportedPlaying = false
    private var consecutiveFailures = 0
    private var lastWindow: List<Uri> = emptyList()

    private val localListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!active) return
            applyTransport(playWhenReady)
            if (playWhenReady && !remoteHasCurrent()) requestSync()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (!active || applyingOwnChange > 0) return
            if (reason != Player.DISCONTINUITY_REASON_SEEK &&
                reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                return
            }
            seekedSinceSync = true
            // A seek within the track the receiver is already playing is just a
            // seek there too; anything else is a new track and a full reload.
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex && remoteHasCurrent()) {
                session?.seek(newPosition.positionMs.coerceAtLeast(0L))
                invalidateState()
            } else {
                requestSync()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (active) requestSync()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (active) requestSync()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Something prepared the local player behind the bridge's back — a
            // recovery path, a stray prepare(). It must not play over the TV.
            if (active && (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY)) {
                TrackLog.d(TAG, "local player was prepared while casting; stopping it")
                localPlayer().stop()
            }
        }
    }

    /**
     * Starts bridging [local] to the receiver behind [session]: silences it,
     * points the session state at the receiver and hands the receiver the
     * current track, from where the local player had got to.
     */
    fun activate(local: Player, session: CastSession) {
        if (active) return
        if (local !== player) setPlayer(local)
        scope = newScope()
        this.session = session
        loading = false
        pendingLoadUrl = null
        queuedNextUrl = null
        queuedNextMediaId = null
        unqueueableUrl = null
        seekedSinceSync = true
        consecutiveFailures = 0
        lastReportedPlaying = false
        lastState = null
        lastContentId = null
        lastWindow = emptyList()
        player.addListener(localListener)
        applyingOwnChange++
        try {
            player.stop()
        } finally {
            applyingOwnChange--
        }
        invalidateState()
        requestSync()
    }

    /**
     * Stops bridging. Returns where the receiver had got to in the current
     * track, for the local player to carry on from.
     */
    fun deactivate(): Long {
        val position = if (active && !loading && remoteHasCurrent(allowEnded = true)) {
            session?.currentPositionMs() ?: player.currentPosition
        } else {
            player.currentPosition
        }
        if (!active) return position
        session = null
        scope.cancel()
        syncJob = null
        syncScheduled = false
        loading = false
        pendingLoadUrl = null
        player.removeListener(localListener)
        if (lastReportedPlaying) {
            lastReportedPlaying = false
            host.onRemoteIsPlayingChanged(false)
        }
        server.retainOnly(emptyList())
        // No invalidateState(): the session is still pointed here until the
        // caller moves it back, and must not see an empty queue in between.
        return position
    }

    /** Follows the service onto a replacement local player (an output rebuild). */
    fun retarget(local: Player) {
        if (local === player) return
        if (active) player.removeListener(localListener)
        setPlayer(local)
        if (active) {
            local.addListener(localListener)
            applyingOwnChange++
            try {
                local.stop()
            } finally {
                applyingOwnChange--
            }
            requestSync()
        }
    }

    // --- Receiver reports (from the controller, main thread) -----------------

    fun onMediaStatus(status: CastProtocol.MediaStatus?) {
        if (!active) return
        val previousState = lastState
        val previousContent = lastContentId
        lastState = status?.playerState
        lastContentId = status?.contentId
        if (status != null && status.playerState != CastProtocol.PlayerState.IDLE) pendingLoadUrl = null

        val expected = urlFor(player.currentMediaItem)
        val aboutCurrent = status?.contentId != null && status?.contentId == expected

        // The receiver ran on into the track queued after this one.
        if (status != null && !aboutCurrent && !loading && status.contentId != null &&
            status.contentId == queuedNextUrl
        ) {
            val next = localPlayer().nextMediaItemIndex
            val mediaId = queuedNextMediaId
            queuedNextUrl = null
            queuedNextMediaId = null
            if (next != C.INDEX_UNSET) {
                lastLoadedMediaId = mediaId
                advanceLocal(next)
                mediaId?.let(host::onRemoteTrackLoaded)
            }
            invalidateState()
            reportPlaying()
            return
        }

        if (status != null && aboutCurrent && !loading) {
            when {
                status.playerState == CastProtocol.PlayerState.IDLE &&
                    status.idleReason == "FINISHED" &&
                    (previousState != CastProtocol.PlayerState.IDLE || previousContent != status.contentId) -> {
                    if (queuedNextUrl == null) {
                        onRemoteFinished()
                    } else {
                        // A queued track should take over by itself; if the
                        // receiver has not moved on shortly, move it on.
                        val finished = status.contentId
                        mainHandler.postDelayed({
                            if (active && lastState == CastProtocol.PlayerState.IDLE && lastContentId == finished) {
                                queuedNextUrl = null
                                onRemoteFinished()
                            }
                        }, QUEUE_HANDOVER_GRACE_MS)
                    }
                }
                status.playerState == CastProtocol.PlayerState.IDLE && status.idleReason == "ERROR" -> {
                    pendingLoadUrl = null
                    TrackLog.d(TAG, "receiver could not play ${status.contentId}")
                    onCurrentTrackFailed()
                }
                else -> {
                    mirrorRemoteTransport(status.playerState)
                    // Playing the current track: make sure the next one waits behind it.
                    val desired = desiredNextUrl()
                    if (queuedNextUrl != desired && desired != unqueueableUrl) requestSync()
                }
            }
        }
        invalidateState()
        reportPlaying()
    }

    /** What should be queued after the current track: the local queue's next, unless repeating one. */
    private fun desiredNextUrl(): String? {
        val local = localPlayer()
        if (local.repeatMode == Player.REPEAT_MODE_ONE) return null
        val next = local.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return null
        return urlFor(local.getMediaItemAt(next))
    }

    fun onVolumeChanged() {
        if (active) invalidateState()
    }

    fun onLoadFailed() {
        if (!active || loading) return
        pendingLoadUrl = null
        onCurrentTrackFailed()
    }

    // --- Reported state ------------------------------------------------------

    override fun getState(): State {
        // Nothing listens to this player while it is not casting — the
        // session is on the local player then — so it does not spend a state
        // diff on every local event just to be ignored.
        val cast = session ?: return INACTIVE_STATE
        val base = super.getState()
        val local = player
        val empty = local.currentTimeline.isEmpty
        val live = !loading && remoteHasCurrent(allowEnded = true)
        val status = cast.mediaStatus
        val finished = status?.playerState == CastProtocol.PlayerState.IDLE && status?.idleReason == "FINISHED"
        val playbackState = when {
            empty -> Player.STATE_IDLE
            !live || status == null -> if (loading || local.playWhenReady) Player.STATE_BUFFERING else Player.STATE_READY
            finished ->
                if (local.nextMediaItemIndex != C.INDEX_UNSET) Player.STATE_BUFFERING else Player.STATE_ENDED
            status?.playerState == CastProtocol.PlayerState.BUFFERING -> Player.STATE_BUFFERING
            status?.playerState == CastProtocol.PlayerState.IDLE ->
                if (local.playWhenReady) Player.STATE_BUFFERING else Player.STATE_READY
            else -> Player.STATE_READY
        }
        val fallbackPosition = local.currentPosition.coerceAtLeast(0L)
        val position: PositionSupplier = if (live) {
            PositionSupplier { cast.currentPositionMs().coerceAtLeast(0L) }
        } else {
            PositionSupplier.getConstant(fallbackPosition)
        }
        // The receiver does not report how far ahead it has buffered; the
        // audio comes over the LAN from this phone's own cache, so the whole
        // track is as good as there.
        val durationMs = if (live) status?.durationMs else null
        val buffered: PositionSupplier = if (live && durationMs != null) {
            PositionSupplier.getConstant(durationMs)
        } else {
            position
        }
        val commands = base.availableCommands.buildUpon()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
            .build()
        val builder = base.buildUpon()
            .setAvailableCommands(commands)
            .setPlaybackState(playbackState)
            .setIsLoading(playbackState == Player.STATE_BUFFERING)
            .setPlayerError(null)
            .setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setContentPositionMs(position)
            .setAdPositionMs(position)
            .setContentBufferedPositionMs(buffered)
            .setAdBufferedPositionMs(buffered)
            .setTotalBufferedDurationMs(PositionSupplier.ZERO)
            .setDeviceInfo(REMOTE_DEVICE)
            .setDeviceVolume((cast.volumeLevel * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME))
            .setIsDeviceMuted(cast.muted)
        if (!empty) {
            builder.setPlaylist(
                RemoteTimeline(local.currentTimeline, local.currentMediaItemIndex, durationMs?.let(Util::msToUs)),
                Tracks.EMPTY,
                local.mediaMetadata,
            )
        }
        return builder.build()
    }

    // --- Commands ------------------------------------------------------------

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!active) return super.handleSetPlayWhenReady(playWhenReady)
        // Onto the local player, which stays the owner of the intent; its
        // listener mirrors it to the receiver.
        player.playWhenReady = playWhenReady
        if (playWhenReady && !remoteHasCurrent()) requestSync()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (!active) return super.handlePrepare()
        requestSync()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        if (!active) return super.handleStop()
        session?.stopMedia()
        return Futures.immediateVoidFuture()
    }

    /** The local player belongs to the service; it is never released from here. */
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (!active) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        // Relative commands arrive here already resolved against *this*
        // player's state — the receiver's playhead — so every one of them is
        // an absolute seek by now. Handed to the local player as that, since
        // its own seekBack/seekToPrevious would work from a playhead that has
        // not moved since casting began.
        if (mediaItemIndex != C.INDEX_UNSET) player.seekTo(mediaItemIndex, positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        val result = super.handleSetPlaybackParameters(playbackParameters)
        if (active) session?.setPlaybackRate(playbackParameters.speed.coerceIn(MIN_SPEED, MAX_SPEED).toDouble())
        return result
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        val cast = session ?: return super.handleSetDeviceVolume(deviceVolume, flags)
        cast.setVolume(deviceVolume.toDouble() / MAX_VOLUME)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        val cast = session ?: return super.handleIncreaseDeviceVolume(flags)
        cast.setVolume(cast.volumeLevel + 1.0 / MAX_VOLUME)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        val cast = session ?: return super.handleDecreaseDeviceVolume(flags)
        cast.setVolume(cast.volumeLevel - 1.0 / MAX_VOLUME)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        val cast = session ?: return super.handleSetDeviceMuted(muted, flags)
        cast.setMuted(muted)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    // --- Reconciling the receiver with the local queue -----------------------

    /**
     * Asks for the receiver to be brought in line with the local queue.
     * Coalesced: one queue edit typically fires a timeline change, a
     * transition and a discontinuity in the same breath, and they all want the
     * same single reconcile.
     */
    private fun requestSync() {
        if (!active || syncScheduled) return
        syncScheduled = true
        mainHandler.post {
            syncScheduled = false
            if (!active) return@post
            syncJob?.cancel()
            syncJob = scope.launch { reconcile() }
        }
    }

    private suspend fun reconcile() {
        val cast = session ?: return
        val local = localPlayer()
        val index = local.currentMediaItemIndex
        val current = local.currentMediaItem
        if (local.mediaItemCount == 0 || index == C.INDEX_UNSET || current == null) {
            cast.stopMedia()
            invalidateState()
            return
        }
        val address = host.hostAddress()
        val currentSource = current.localConfiguration?.uri
        if (address == null || currentSource == null) {
            TrackLog.d(TAG, "nothing to cast from: address=$address source=$currentSource")
            return
        }
        val next = local.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let(local::getMediaItemAt)
        val nextSource = next?.localConfiguration?.uri

        // Next track's URL is kept served and resolved ahead, so the receiver
        // starts it quickly; the window just left stays served too, since the
        // receiver may still be reading it when this runs.
        val window = listOfNotNull(currentSource, nextSource)
        server.retainOnly((window + lastWindow).distinct())
        lastWindow = window

        val currentUrl = server.audioUrl(address, currentSource) ?: return
        if (remoteHasCurrent(currentUrl)) {
            seekedSinceSync = false
            invalidateState()
            val queueNext = if (local.repeatMode == Player.REPEAT_MODE_ONE) null else next
            reconcileQueue(cast, queueNext, address)
            return
        }

        // Skipped forward onto the track already preloaded behind this one:
        // a jump along the receiver's queue starts it at once.
        if (currentUrl == queuedNextUrl && remoteIsLive() && local.currentPosition < SKIP_TO_QUEUED_WITHIN_MS) {
            queuedNextUrl = null
            queuedNextMediaId = null
            pendingLoadUrl = currentUrl
            lastLoadedMediaId = current.mediaId
            lastTransportCommandAt = SystemClock.elapsedRealtime()
            cast.queueSkip()
            if (!local.playWhenReady) cast.pause()
            host.onRemoteTrackLoaded(current.mediaId)
            seekedSinceSync = false
            invalidateState()
            return
        }

        // The receiver finished this track with nothing queued after it, and
        // nobody has asked to hear it again. A track arriving now — AutoPlay
        // topping the queue up after it ran dry, or the listener adding one —
        // is simply what plays next.
        val status = cast.mediaStatus
        if (!seekedSinceSync && status?.playerState == CastProtocol.PlayerState.IDLE &&
            status?.idleReason == "FINISHED" && status?.contentId == currentUrl
        ) {
            if (next != null) advanceLocal(local.nextMediaItemIndex)
            return
        }

        // A different track, or the receiver has nothing live. Where to start:
        // from an explicit seek (a skip lands at 0), otherwise from wherever
        // the receiver had got to in this same song — a version swap replaces
        // the item without moving the playhead.
        val sameSong = lastLoadedMediaId == current.mediaId && remoteIsLive()
        val startPosition = if (!seekedSinceSync && sameSong) {
            cast.currentPositionMs()
        } else {
            local.currentPosition
        }.coerceAtLeast(0L)

        loading = true
        invalidateState()
        val mimeType = withContext(Dispatchers.IO) { server.probe(currentSource) }
        if (session !== cast) return
        if (mimeType == null) {
            loading = false
            invalidateState()
            onCurrentTrackFailed()
            return
        }
        val metadata = current.mediaMetadata
        pendingLoadUrl = currentUrl
        lastLoadedMediaId = current.mediaId
        lastTransportCommandAt = SystemClock.elapsedRealtime()
        // A LOAD replaces the receiver's whole queue.
        queuedNextUrl = null
        queuedNextMediaId = null
        cast.load(
            CastProtocol.LoadRequest(
                url = currentUrl,
                contentType = mimeType,
                mediaId = current.mediaId,
                title = metadata.title?.toString(),
                artist = metadata.artist?.toString(),
                album = metadata.albumTitle?.toString(),
                artworkUrl = server.artworkUrl(address, metadata.artworkUri)?.toString(),
                startPositionMs = startPosition,
                autoplay = local.playWhenReady,
            ),
        )
        host.onRemoteTrackLoaded(current.mediaId)
        loading = false
        seekedSinceSync = false
        invalidateState()
        // Resolve the next track now, while this one plays, so the hop to it
        // is a LAN fetch rather than a stream lookup.
        nextSource?.let { source -> scope.launch(Dispatchers.IO) { server.probe(source) } }
    }

    private var lastLoadedMediaId: String? = null

    /**
     * Keeps exactly the local queue's next track queued behind the current one
     * on the receiver, so the receiver preloads it and plays straight on.
     */
    private suspend fun reconcileQueue(cast: CastSession, next: MediaItem?, address: String) {
        val nextSource = next?.localConfiguration?.uri
        val nextUrl = nextSource?.let { server.audioUrl(address, it) }
        if (nextUrl == queuedNextUrl) return
        val status = cast.mediaStatus ?: return
        val current = status.currentItemId
        cast.queueRemove(cast.queueItemIds.filter { it != current })
        queuedNextUrl = null
        queuedNextMediaId = null
        if (next == null || nextSource == null || nextUrl == null) return
        val mimeType = withContext(Dispatchers.IO) { server.probe(nextSource) }
        if (session !== cast) return
        if (mimeType == null) {
            // Left to play the old way: loaded when its turn comes.
            unqueueableUrl = nextUrl
            return
        }
        val metadata = next.mediaMetadata
        cast.queueNext(
            CastProtocol.LoadRequest(
                url = nextUrl,
                contentType = mimeType,
                mediaId = next.mediaId,
                title = metadata.title?.toString(),
                artist = metadata.artist?.toString(),
                album = metadata.albumTitle?.toString(),
                artworkUrl = server.artworkUrl(address, metadata.artworkUri)?.toString(),
                startPositionMs = 0,
                autoplay = true,
            ),
        )
        queuedNextUrl = nextUrl
        queuedNextMediaId = next.mediaId
    }

    private fun onRemoteFinished() {
        val local = localPlayer()
        if (local.repeatMode == Player.REPEAT_MODE_ONE) {
            // Same track again, from the top: a seek on the local player
            // reloads it on the receiver.
            local.seekTo(local.currentMediaItemIndex, 0L)
            return
        }
        val next = local.nextMediaItemIndex
        if (next != C.INDEX_UNSET) {
            advanceLocal(next)
        } else {
            host.onRemoteQueueEnded()
        }
    }

    private fun advanceLocal(index: Int) {
        host.onRemoteAdvance {
            applyingOwnChange++
            try {
                localPlayer().seekTo(index, 0L)
            } finally {
                applyingOwnChange--
            }
        }
        consecutiveFailures = 0
        seekedSinceSync = true
        requestSync()
    }

    /**
     * The receiver could not play the current track. Moves on to the next one,
     * as a failed stream does on the phone, but gives up after a few in a row —
     * a receiver that can play nothing should stop, not race through the queue.
     */
    private fun onCurrentTrackFailed() {
        consecutiveFailures++
        CastStatus.showNotice(R.string.cast_track_skipped)
        val local = localPlayer()
        val next = local.nextMediaItemIndex
        if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES && next != C.INDEX_UNSET) {
            local.seekTo(next, 0L)
        } else {
            local.playWhenReady = false
            consecutiveFailures = 0
        }
    }

    /** The local intent, sent to the receiver. */
    private fun applyTransport(playWhenReady: Boolean) {
        val cast = session ?: return
        val state = cast.mediaStatus?.playerState ?: return
        if (playWhenReady && state == CastProtocol.PlayerState.PAUSED) {
            lastTransportCommandAt = SystemClock.elapsedRealtime()
            cast.play()
        } else if (!playWhenReady &&
            (state == CastProtocol.PlayerState.PLAYING || state == CastProtocol.PlayerState.BUFFERING)
        ) {
            lastTransportCommandAt = SystemClock.elapsedRealtime()
            cast.pause()
        }
    }

    /** A pause or resume that did not come from this phone, reflected back onto it. */
    private fun mirrorRemoteTransport(state: CastProtocol.PlayerState) {
        if (SystemClock.elapsedRealtime() - lastTransportCommandAt < OWN_COMMAND_GRACE_MS) return
        val local = localPlayer()
        when {
            state == CastProtocol.PlayerState.PAUSED && local.playWhenReady -> local.playWhenReady = false
            state == CastProtocol.PlayerState.PLAYING && !local.playWhenReady -> local.playWhenReady = true
        }
    }

    private fun reportPlaying() {
        val playing = session?.mediaStatus?.playerState == CastProtocol.PlayerState.PLAYING &&
            !loading && remoteHasCurrent()
        if (playing == lastReportedPlaying) return
        lastReportedPlaying = playing
        if (playing) consecutiveFailures = 0
        host.onRemoteIsPlayingChanged(playing)
    }

    private fun urlFor(item: MediaItem?): String? {
        val source = item?.localConfiguration?.uri ?: return null
        val address = host.hostAddress() ?: return null
        return server.audioUrl(address, source)
    }

    private fun remoteIsLive(): Boolean {
        val state = session?.mediaStatus?.playerState ?: return false
        return state != CastProtocol.PlayerState.IDLE
    }

    /**
     * Whether the receiver has the local player's current track loaded.
     * [allowEnded] also counts one it has played to the end — still the track
     * on screen, but not one a seek can be sent to.
     */
    private fun remoteHasCurrent(
        expectedUrl: String? = urlFor(player.currentMediaItem),
        allowEnded: Boolean = false,
    ): Boolean {
        expectedUrl ?: return false
        if (pendingLoadUrl == expectedUrl) return true
        val status = session?.mediaStatus ?: return false
        if (status.contentId != expectedUrl) return false
        if (status.playerState != CastProtocol.PlayerState.IDLE) return true
        return allowEnded && status.idleReason == "FINISHED"
    }

    /** The wrapped local player, for code outside this class's own body (listeners, lambdas). */
    private fun localPlayer(): Player = player

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The local queue as the receiver sees it: every entry seekable, and the
     * current one as long as the receiver says it is. The local player never
     * prepares while casting, so its own windows are placeholders that know
     * neither.
     */
    private class RemoteTimeline(
        timeline: Timeline,
        private val currentIndex: Int,
        private val currentDurationUs: Long?,
    ) : ForwardingTimeline(timeline) {

        override fun getWindow(
            windowIndex: Int,
            window: Timeline.Window,
            defaultPositionProjectionUs: Long,
        ): Timeline.Window {
            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
            window.isSeekable = true
            window.isDynamic = false
            window.isPlaceholder = false
            if (windowIndex == currentIndex && currentDurationUs != null) window.durationUs = currentDurationUs
            return window
        }

        override fun getPeriod(periodIndex: Int, period: Timeline.Period, setIds: Boolean): Timeline.Period {
            super.getPeriod(periodIndex, period, setIds)
            period.isPlaceholder = false
            if (period.windowIndex == currentIndex && currentDurationUs != null) period.durationUs = currentDurationUs
            return period
        }
    }

    private companion object {
        const val TAG = "BitChordCast"
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val MAX_VOLUME = 20
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f

        /** How long a receiver state that contradicts our own last command is put down to latency. */
        const val OWN_COMMAND_GRACE_MS = 2_500L

        /** How long a finished track may wait for the queued one to take over by itself. */
        const val QUEUE_HANDOVER_GRACE_MS = 4_000L

        /** A skip lands at the start of a track; later than this it is a seek into it, not a skip. */
        const val SKIP_TO_QUEUED_WITHIN_MS = 1_000L

        val REMOTE_DEVICE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMaxVolume(MAX_VOLUME)
            .build()

        /** What an idle bridge reports: no queue, not playing. */
        val INACTIVE_STATE: State = State.Builder()
            .setAvailableCommands(Player.Commands.EMPTY)
            .build()
    }
}
