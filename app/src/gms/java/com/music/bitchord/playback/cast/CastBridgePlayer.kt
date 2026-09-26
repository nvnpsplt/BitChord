package com.music.bitchord.playback.cast

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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

/**
 * What the media session talks to while a cast device is playing.
 *
 * ## Why the queue stays on the phone
 *
 * The obvious design — hand the whole queue to the receiver and point the
 * session at [RemoteCastPlayer] — would switch off most of this app. AutoPlay's
 * refill, the user-queue tiers, shuffle, "sleep after this song", scrobbling,
 * history, Discord, lyrics on the lock screen: every one of them is written
 * against the local ExoPlayer's queue and its callbacks, in
 * [com.music.bitchord.playback.PlaybackService] and the classes around it.
 *
 * So the local player keeps the queue — it stays the single source of truth for
 * *what* plays — and is simply kept silent, in `STATE_IDLE`, where it resolves
 * nothing, holds no audio focus and renders nothing. The receiver is told about
 * only the few tracks around the playhead: the current one and the next, so it
 * can preload and run straight into it. Everything that edits the queue edits
 * the local player exactly as it always has, and this reconciles the receiver
 * to it afterwards.
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
    private val remote: RemoteCastPlayer,
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
    }

    /** Whether this is bridging to a receiver right now. */
    var active: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope = newScope()
    private var syncJob: Job? = null
    private var syncScheduled = false

    /** True while the receiver is being handed a new current track. */
    private var loading = false

    /** Set while this class moves the local player itself, so it does not echo the move back. */
    private var applyingOwnChange = 0

    /** An explicit seek happened since the last reconcile: the local position is authoritative. */
    private var seekedSinceSync = false

    /**
     * The URL just handed to the receiver, until the receiver reports on it.
     * Between the two it still reads as idle, and without this a queue edit in
     * that moment would load the same track a second time from the top.
     */
    private var pendingLoadUrl: String? = null

    private var lastReportedPlaying = false
    private var consecutiveFailures = 0
    private var lastWindow: List<Uri> = emptyList()

    private val localListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!active) return
            if (remote.playWhenReady != playWhenReady) remote.playWhenReady = playWhenReady
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
                remote.seekTo(newPosition.positionMs.coerceAtLeast(0L))
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

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (active) requestSync()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
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

    private val remoteListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!active) return
            if (remoteIsLive() || remote.playbackState == Player.STATE_ENDED) pendingLoadUrl = null
            invalidateState()
            reportPlaying()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!active || loading) return
            // Paused or resumed from somewhere other than this phone: the TV's
            // remote, Google Home, another phone on the same session.
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE &&
                localPlayer().playWhenReady != playWhenReady
            ) {
                localPlayer().playWhenReady = playWhenReady
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!active || reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            val local = localPlayer()
            val next = local.nextMediaItemIndex
            if (next == C.INDEX_UNSET) return
            val expected = urlFor(local.getMediaItemAt(next)) ?: return
            if (mediaItem?.localConfiguration?.uri?.toString() != expected) return
            advanceLocal(next)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!active || loading || playbackState != Player.STATE_ENDED) return
            val local = localPlayer()
            if (remoteCurrentUrl() != urlFor(local.currentMediaItem)) return
            val next = local.nextMediaItemIndex
            if (next != C.INDEX_UNSET) {
                // The receiver ran off the end of what it was given — the next
                // track was not known yet when this one was loaded.
                advanceLocal(next)
            } else {
                host.onRemoteQueueEnded()
                invalidateState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!active || loading) return
            pendingLoadUrl = null
            TrackLog.d(TAG, "receiver failed: ${error.errorCodeName} ${error.message}")
            onCurrentTrackFailed()
        }
    }

    init {
        remote.addListener(remoteListener)
    }

    /**
     * Starts bridging [local] to the receiver: silences it, points the session
     * state at the receiver and hands the receiver the current track, from
     * where the local player had got to.
     */
    fun activate(local: Player) {
        if (active) return
        if (local !== player) setPlayer(local)
        scope = newScope()
        active = true
        loading = false
        seekedSinceSync = true
        consecutiveFailures = 0
        lastReportedPlaying = false
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
        val position = currentRemotePositionOr(player.currentPosition)
        if (!active) return position
        active = false
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

    fun releaseBridge() {
        deactivate()
        remote.removeListener(remoteListener)
    }

    // --- Reported state ------------------------------------------------------

    override fun getState(): State {
        // Nothing listens to this player while it is not casting — the
        // session is on the local player then — so it does not spend a state
        // diff on every local event just to be ignored.
        if (!active) return INACTIVE_STATE
        val base = super.getState()
        val local = player
        val empty = local.currentTimeline.isEmpty
        val live = !loading && remoteHasCurrent(allowEnded = true)
        val remoteState = remote.playbackState
        val playbackState = when {
            empty -> Player.STATE_IDLE
            !live -> if (loading || local.playWhenReady) Player.STATE_BUFFERING else Player.STATE_READY
            remoteState == Player.STATE_ENDED ->
                if (local.nextMediaItemIndex != C.INDEX_UNSET) Player.STATE_BUFFERING else Player.STATE_ENDED
            remoteState == Player.STATE_IDLE ->
                if (local.playWhenReady) Player.STATE_BUFFERING else Player.STATE_READY
            else -> remoteState
        }
        val fallbackPosition = local.currentPosition.coerceAtLeast(0L)
        val position: PositionSupplier = if (live) {
            PositionSupplier { remote.currentPosition.coerceAtLeast(0L) }
        } else {
            PositionSupplier.getConstant(fallbackPosition)
        }
        val buffered: PositionSupplier = if (live) {
            PositionSupplier { maxOf(remote.bufferedPosition, remote.currentPosition).coerceAtLeast(0L) }
        } else {
            PositionSupplier.getConstant(fallbackPosition)
        }
        val totalBuffered: PositionSupplier = if (live) {
            PositionSupplier { (remote.bufferedPosition - remote.currentPosition).coerceAtLeast(0L) }
        } else {
            PositionSupplier.ZERO
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
            .setTotalBufferedDurationMs(totalBuffered)
            .setDeviceInfo(remote.deviceInfo)
            .setDeviceVolume(remote.deviceVolume)
            .setIsDeviceMuted(remote.isDeviceMuted)
        if (!empty) {
            val remoteDurationUs = if (live) {
                remote.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let(Util::msToUs)
            } else {
                null
            }
            builder.setPlaylist(
                RemoteTimeline(local.currentTimeline, local.currentMediaItemIndex, remoteDurationUs),
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
        remote.stop()
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
        if (active) {
            val speed = playbackParameters.speed.coerceIn(
                RemoteCastPlayer.MIN_SPEED_SUPPORTED,
                RemoteCastPlayer.MAX_SPEED_SUPPORTED,
            )
            remote.playbackParameters = PlaybackParameters(speed)
        }
        return result
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        if (!active) return super.handleSetDeviceVolume(deviceVolume, flags)
        remote.setDeviceVolume(deviceVolume, flags)
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        if (!active) return super.handleIncreaseDeviceVolume(flags)
        remote.increaseDeviceVolume(flags)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        if (!active) return super.handleDecreaseDeviceVolume(flags)
        remote.decreaseDeviceVolume(flags)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        if (!active) return super.handleSetDeviceMuted(muted, flags)
        remote.setDeviceMuted(muted, flags)
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
        val local = player
        val index = local.currentMediaItemIndex
        val current = local.currentMediaItem
        if (local.mediaItemCount == 0 || index == C.INDEX_UNSET || current == null) {
            if (remote.mediaItemCount > 0) remote.clearMediaItems()
            invalidateState()
            return
        }
        val address = host.hostAddress()
        val currentSource = current.localConfiguration?.uri
        if (address == null || currentSource == null) {
            TrackLog.d(TAG, "nothing to cast from: address=$address source=$currentSource")
            return
        }
        val repeatOne = local.repeatMode == Player.REPEAT_MODE_ONE
        val wantedRepeat = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (remote.repeatMode != wantedRepeat) remote.repeatMode = wantedRepeat
        val next = if (repeatOne) {
            null
        } else {
            local.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let(local::getMediaItemAt)
        }
        val nextSource = next?.localConfiguration?.uri

        // The window just left stays served too: the receiver may still be
        // reading the track it is moving away from when this runs.
        val window = listOfNotNull(currentSource, nextSource)
        server.retainOnly((window + lastWindow).distinct())
        lastWindow = window

        val currentUrl = server.audioUrl(address, currentSource) ?: return
        val nextUrl = nextSource?.let { server.audioUrl(address, it) }

        if (remoteHasCurrent(currentUrl)) {
            reconcileTail(next, nextSource, nextUrl, address)
            seekedSinceSync = false
            invalidateState()
            return
        }

        // The receiver finished this track with nothing queued after it, and
        // nobody has asked to hear it again. A track arriving now — AutoPlay
        // topping the queue up after it ran dry, or the listener adding one —
        // is simply what plays next.
        if (!seekedSinceSync && remote.playbackState == Player.STATE_ENDED && remoteCurrentUrl() == currentUrl) {
            if (next != null) advanceLocal(local.nextMediaItemIndex)
            return
        }

        // A different track, or the receiver has nothing live. Where to start:
        // from an explicit seek (a skip lands at 0), otherwise from wherever
        // the receiver had got to in this same song — a version swap replaces
        // the item without moving the playhead.
        val sameSong = remote.currentMediaItem?.mediaId == current.mediaId && remoteIsLive()
        val startPosition = if (!seekedSinceSync && sameSong) {
            remote.currentPosition
        } else {
            local.currentPosition
        }.coerceAtLeast(0L)

        loading = true
        invalidateState()
        val currentItem = remoteItemFor(current, currentSource, currentUrl, address)
        if (currentItem == null) {
            loading = false
            invalidateState()
            onCurrentTrackFailed()
            return
        }
        val nextItem = if (next != null && nextSource != null && nextUrl != null) {
            remoteItemFor(next, nextSource, nextUrl, address)
        } else {
            null
        }
        remote.playWhenReady = local.playWhenReady
        pendingLoadUrl = currentUrl
        remote.setMediaItems(listOfNotNull(currentItem, nextItem), 0, startPosition)
        remote.prepare()
        loading = false
        seekedSinceSync = false
        invalidateState()
    }

    /** The receiver already has the current track; make what follows it match. */
    private suspend fun reconcileTail(next: MediaItem?, nextSource: Uri?, nextUrl: String?, address: String) {
        val currentIndex = remote.currentMediaItemIndex
        if (currentIndex > 0) remote.removeMediaItems(0, currentIndex)
        val tail = (1 until remote.mediaItemCount).map {
            remote.getMediaItemAt(it).localConfiguration?.uri?.toString()
        }
        if (tail == listOfNotNull(nextUrl)) return
        val nextItem = if (next != null && nextSource != null && nextUrl != null) {
            remoteItemFor(next, nextSource, nextUrl, address)
        } else {
            null
        }
        if (!active) return
        if (remote.mediaItemCount > 1) remote.removeMediaItems(1, remote.mediaItemCount)
        if (nextItem != null) remote.addMediaItem(nextItem)
    }

    /**
     * The receiver's copy of [item]: the proxy URL, the content type the bytes
     * turned out to be, and the metadata the TV shows. Null when the track
     * cannot be played there — nothing resolved, or a format the receiver
     * cannot decode.
     */
    private suspend fun remoteItemFor(item: MediaItem, source: Uri, url: String, address: String): MediaItem? {
        val mimeType = withContext(Dispatchers.IO) { server.probe(source) } ?: return null
        val metadata = item.mediaMetadata.buildUpon()
            .setArtworkUri(server.artworkUrl(address, item.mediaMetadata.artworkUri))
            .setArtworkData(null, null)
            .build()
        return MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(url)
            .setMimeType(mimeType)
            .setMediaMetadata(metadata)
            .build()
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
        requestSync()
    }

    /**
     * The receiver could not play the current track. Moves on to the next one,
     * as a failed stream does on the phone, but gives up after a few in a row —
     * a receiver that can play nothing should stop, not race through the queue.
     */
    private fun onCurrentTrackFailed() {
        consecutiveFailures++
        pendingLoadUrl = null
        CastStatus.showNotice(R.string.cast_track_skipped)
        val next = player.nextMediaItemIndex
        if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES && next != C.INDEX_UNSET) {
            player.seekTo(next, 0L)
        } else {
            player.playWhenReady = false
            consecutiveFailures = 0
        }
    }

    private fun reportPlaying() {
        val playing = remote.isPlaying && !loading && remoteHasCurrent()
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

    private fun remoteCurrentUrl(): String? = remote.currentMediaItem?.localConfiguration?.uri?.toString()

    private fun remoteIsLive(): Boolean =
        remote.playbackState == Player.STATE_BUFFERING || remote.playbackState == Player.STATE_READY

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
        val state = remote.playbackState
        if (state == Player.STATE_IDLE) {
            return pendingLoadUrl == expectedUrl && remoteCurrentUrl() == expectedUrl
        }
        if (state == Player.STATE_ENDED && !allowEnded) return false
        return remoteCurrentUrl() == expectedUrl
    }

    private fun currentRemotePositionOr(fallback: Long): Long =
        if (active && !loading && remoteHasCurrent(allowEnded = true)) remote.currentPosition else fallback

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

        /** What an idle bridge reports: no queue, not playing. */
        val INACTIVE_STATE: State = State.Builder()
            .setAvailableCommands(Player.Commands.EMPTY)
            .build()
    }
}
