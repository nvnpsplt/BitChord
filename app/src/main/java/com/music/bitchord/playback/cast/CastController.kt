package com.music.bitchord.playback.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.music.bitchord.R
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.settings.AppSettings
import java.net.InetAddress

/**
 * Owns a cast session from the service's side: connects to the device the
 * listener picked, moves playback onto it through [CastBridgePlayer], and moves
 * it back when the session ends — however it ends, whether from the output
 * sheet, the TV, Google Home or the phone simply walking out of Wi-Fi range.
 *
 * Main thread only, like everything else that touches the players.
 */
@UnstableApi
internal class CastController(
    context: Context,
    dataSourceFactory: DataSource.Factory,
    private val host: CastHost,
) {

    private val appContext = context.applicationContext
    private val server = CastMediaServer(appContext, dataSourceFactory)
    private var session: RemoteSession? = null
    private var device: CastDevice? = null
    private var hostAddress: String? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val bridge = CastBridgePlayer(
        local = requireNotNull(host.localPlayer) { "CastController needs the local player to exist" },
        server = server,
        host = object : CastBridgePlayer.Host {
            override fun hostAddress(): String? = hostAddress
            override fun onRemoteIsPlayingChanged(isPlaying: Boolean) = host.onRemoteIsPlayingChanged(isPlaying)
            override fun onRemoteAdvance(advance: () -> Unit) = host.onRemoteAdvance(advance)
            override fun onRemoteQueueEnded() = host.onRemoteQueueEnded()
            override fun onRemoteTrackLoaded(mediaId: String) {
                lyricsMessages[mediaId]?.let { deliverLyrics(it) }
            }
        },
    )

    /** Whether the receiver is the one playing. */
    val isCasting: Boolean get() = bridge.active

    /**
     * The player that describes what is audible — the bridge while casting,
     * so its position, duration and isPlaying are the receiver's.
     */
    val playhead: Player? get() = bridge.takeIf { it.active }

    /** The receiver's volume, 0..1, while connected. */
    val volume: Float? get() = session?.takeIf { it.isReady }?.volumeLevel?.toFloat()

    /**
     * The last few tracks' lyrics, as sent. Kept because the TV only shows the
     * current track's, and a track reaches the TV after its lyrics were found:
     * they are sent again whenever the receiver moves on to one of these.
     */
    private val lyricsMessages = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 8
    }

    /**
     * The last few tracks' lyrics as found for the phone's own lyrics screen,
     * so captions for the current track need no second lookup. Read from the
     * media server's threads, hence the lock.
     */
    private val captionLines = object : LinkedHashMap<String, List<LyricLine>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>>?): Boolean = size > 8
    }

    init {
        CastConnector.controller = this
        server.captions = { item ->
            val lines = synchronized(captionLines) { captionLines[item.mediaId] } ?: host.lyricsFor(item)
            CastCaptions.webVtt(lines.orEmpty().map { CastCaptions.Line(it.timeMs, it.text, it.sungUntilMs) })
        }
    }

    /** Connects to [target] and, once its receiver is up, moves playback there. */
    fun connect(target: CastDevice) {
        if (device?.id == target.id && session != null) return
        val blocked = host.castBlockedReason()
        if (blocked != null) {
            CastStatus.showNotice(blocked)
            return
        }
        // Switching straight from one device to another.
        if (session != null) disconnect(stopReceiver = true)

        hostAddress = LocalAddress.pickFor(runCatching { InetAddress.getByName(target.host) }.getOrNull())
        if (hostAddress == null || !server.start()) {
            TrackLog.d(TAG, "cannot cast: no LAN address or the media server failed to start")
            CastStatus.showNotice(R.string.cast_connect_failed)
            return
        }
        device = target
        CastStatus.publish(CastStatus.Snapshot(CastStatus.Phase.CONNECTING, target.name, target.id))
        val renderer = target.dlna
        val newSession: RemoteSession = if (renderer != null) {
            DlnaSession(renderer, SessionEvents())
        } else {
            CastSession(target.host, target.port, CastSupport.RECEIVER_APP_ID, SessionEvents())
        }
        session = newSession
        newSession.open()
    }

    /** Hands playback back to the phone. [stopReceiver] also closes the app on the TV. */
    fun disconnect(stopReceiver: Boolean = true) {
        val current = session ?: return
        session = null
        current.close(stopApp = stopReceiver)
        stopCasting(prepareLocal = true)
    }

    fun setVolume(level: Float) {
        session?.takeIf { it.isReady }?.setVolume(level.toDouble())
        bridge.onVolumeChanged()
    }

    /** The service has moved onto a different ExoPlayer — see [CastBridgePlayer.retarget]. */
    fun onLocalPlayerReplaced(local: Player) {
        bridge.retarget(local)
    }

    /**
     * Hands [lines] for [mediaId] to BitChord's receiver, or remembers them for
     * when that track reaches the TV. Does nothing on the stock receiver, which
     * has no lyrics screen — there they may become subtitles instead, see
     * [CastCaptions].
     */
    fun sendLyrics(mediaId: String, lines: List<LyricLine>?) {
        if (lines != null) synchronized(captionLines) { captionLines[mediaId] = lines }
        if (!CastSupport.hasCustomReceiver) return
        val message = CastLyrics.message(mediaId, lines)
        lyricsMessages[mediaId] = message
        if (bridge.active && host.localPlayer?.currentMediaItem?.mediaId == mediaId) deliverLyrics(message)
    }

    /** The service is going away. The receiver cannot go on without this process serving it. */
    fun release() {
        val current = session
        session = null
        current?.close(stopApp = true)
        stopCasting(prepareLocal = false)
        if (CastConnector.controller === this) CastConnector.controller = null
    }

    private fun deliverLyrics(message: String) {
        session?.sendCustom(CastLyrics.NAMESPACE, message)
    }

    private inner class SessionEvents : RemoteSession.Listener {
        private fun isCurrent(): Boolean = session != null

        override fun onReady() {
            if (!isCurrent()) return
            startCasting()
        }

        override fun onMediaStatus(status: CastProtocol.MediaStatus?) {
            if (isCurrent()) bridge.onMediaStatus(status)
        }

        override fun onVolume(level: Double, muted: Boolean) {
            if (isCurrent()) bridge.onVolumeChanged()
        }

        override fun onLoadFailed(reason: String) {
            TrackLog.d(TAG, "receiver refused the track: $reason")
            if (isCurrent()) bridge.onLoadFailed()
        }

        override fun onClosed(error: String?) {
            val wasCasting = bridge.active
            TrackLog.d(TAG, "cast session closed: ${error ?: "normally"}")
            if (!wasCasting && error != null) CastStatus.showNotice(R.string.cast_connect_failed)
            session = null
            stopCasting(prepareLocal = true)
        }
    }

    private fun startCasting() {
        val current = session ?: return
        if (bridge.active) return
        val local = host.localPlayer ?: return
        acquireLocks()
        bridge.activate(local, current)
        host.useSessionPlayer(bridge)
        host.onCastStarted()
        val target = device
        CastStatus.publish(CastStatus.Snapshot(CastStatus.Phase.CASTING, target?.name, target?.id))
        TrackLog.d(TAG, "casting to ${target?.name} via $hostAddress:${server.port}")
    }

    private fun stopCasting(prepareLocal: Boolean) {
        device = null
        if (!bridge.active) {
            server.stop()
            releaseLocks()
            CastStatus.publish(CastStatus.Snapshot())
            return
        }
        val position = bridge.deactivate()
        val local = host.localPlayer
        if (local != null) {
            val wasPlaying = local.playWhenReady
            host.useSessionPlayer(local)
            val index = local.currentMediaItemIndex
            if (index != C.INDEX_UNSET && local.mediaItemCount > 0) local.seekTo(index, position)
            local.playWhenReady = prepareLocal && wasPlaying && AppSettings.castContinueOnPhone.value
            if (prepareLocal) local.prepare()
        }
        server.stop()
        releaseLocks()
        CastStatus.publish(CastStatus.Snapshot())
        if (prepareLocal) host.onCastEnded()
        TrackLog.d(TAG, "cast session over; back on the phone at ${position}ms")
    }

    /**
     * The phone is the receiver's file server now, and has to stay awake and on
     * Wi-Fi with the screen off to be one. ExoPlayer held these while it was
     * playing; it is idle for as long as this lasts.
     */
    private fun acquireLocks() {
        runCatching {
            val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitChord:cast").apply {
                setReferenceCounted(false)
                acquire(MAX_LOCK_MS)
            }
        }
        runCatching {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BitChord:cast").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private companion object {
        const val TAG = "BitChordCast"

        /** A backstop, not a budget: released as soon as casting ends. */
        const val MAX_LOCK_MS = 6 * 60 * 60 * 1000L
    }
}
