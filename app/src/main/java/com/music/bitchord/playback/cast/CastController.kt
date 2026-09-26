package com.music.bitchord.playback.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.media3.cast.Cast
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.music.bitchord.R
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings

/**
 * Owns a cast session from the service's side: notices one starting, moves
 * playback onto the receiver through [CastBridgePlayer], and moves it back
 * when the session ends — however it ends, whether from the output sheet, the
 * TV, Google Home or the phone simply walking out of Wi-Fi range.
 *
 * Main thread only, like everything else that touches the players.
 */
@UnstableApi
internal class CastController(
    context: Context,
    dataSourceFactory: DataSource.Factory,
    private val host: Host,
) {

    interface Host {
        /** The ExoPlayer that owns the queue right now. */
        val localPlayer: Player?

        /** Points the media session at [player]. */
        fun useSessionPlayer(player: Player)

        /** Why casting cannot start right now, as a string resource, or null when it can. */
        fun castBlockedReason(): Int?

        /** Casting started; the local player is silent from here on. */
        fun onCastStarted()

        /** Casting ended and the local player owns playback again. */
        fun onCastEnded()

        fun onRemoteIsPlayingChanged(isPlaying: Boolean)
        fun onRemoteAdvance(advance: () -> Unit)
        fun onRemoteQueueEnded()
    }

    private val appContext = context.applicationContext
    private val cast = Cast.getSingletonInstance(appContext)
    private val server = CastMediaServer(appContext, dataSourceFactory)
    private val remote = RemoteCastPlayer.Builder(appContext).build()
    private var hostAddress: String? = null

    /** Where the receiver was when its session began to end, before the session is gone. */
    private var endingPositionMs: Long? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val bridge = CastBridgePlayer(
        local = requireNotNull(host.localPlayer) { "CastController needs the local player to exist" },
        remote = remote,
        server = server,
        host = object : CastBridgePlayer.Host {
            override fun hostAddress(): String? = hostAddress
            override fun onRemoteIsPlayingChanged(isPlaying: Boolean) = host.onRemoteIsPlayingChanged(isPlaying)
            override fun onRemoteAdvance(advance: () -> Unit) = host.onRemoteAdvance(advance)
            override fun onRemoteQueueEnded() = host.onRemoteQueueEnded()
        },
    )

    /** Whether the receiver is the one playing. */
    val isCasting: Boolean get() = bridge.active

    /**
     * The player that describes what is audible — the bridge while casting,
     * so its position, duration and isPlaying are the receiver's.
     */
    val playhead: Player? get() = bridge.takeIf { it.active }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            CastStatus.publish(CastStatus.Snapshot(CastStatus.Phase.CONNECTING, session.deviceName()))
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) = onConnected(session)

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            CastStatus.publish(CastStatus.Snapshot(CastStatus.Phase.CONNECTING, session.deviceName()))
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onConnected(session)

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            TrackLog.d(TAG, "cast session failed to start: $error")
            if (!bridge.active) CastStatus.publish(CastStatus.Snapshot())
            CastStatus.showNotice(R.string.cast_connect_failed)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            if (!bridge.active) CastStatus.publish(CastStatus.Snapshot())
        }

        override fun onSessionEnding(session: CastSession) {
            // Last moment the receiver can still be asked where it is.
            if (bridge.active) endingPositionMs = bridge.currentPosition
        }

        override fun onSessionEnded(session: CastSession, error: Int) = stopCasting()

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            // A dropped connection the framework is trying to restore. Playback
            // stays with the receiver until it gives up and ends the session.
        }
    }

    private val availabilityListener = object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() = startCasting()
        override fun onCastSessionUnavailable() = stopCasting()
    }

    init {
        cast.addSessionManagerListener(sessionListener)
        remote.setSessionAvailabilityListener(availabilityListener)
        if (remote.isCastSessionAvailable) startCasting()
    }

    /** The service has moved onto a different ExoPlayer — see [CastBridgePlayer.retarget]. */
    fun onLocalPlayerReplaced(local: Player) {
        bridge.retarget(local)
    }

    /** Hands the session back to the phone, keeping the receiver running only if asked. */
    fun release() {
        val wasCasting = bridge.active
        // The service is going away: the local player is about to be released,
        // so it is handed its position back but not prepared.
        stopCasting(prepareLocal = false)
        cast.removeSessionManagerListener(sessionListener)
        remote.setSessionAvailabilityListener(null)
        bridge.releaseBridge()
        remote.release()
        // Nothing will be serving the receiver its audio once this process is
        // gone, so there is nothing for it to keep playing.
        if (wasCasting) CastSupport.endSession(appContext, stopReceiver = true)
    }

    private fun onConnected(session: CastSession) {
        hostAddress = LocalAddress.pickFor(session.deviceAddress())
        if (bridge.active) {
            CastStatus.publish(CastStatus.Snapshot(CastStatus.Phase.CASTING, session.deviceName()))
        }
    }

    private fun startCasting() {
        if (bridge.active) return
        val blocked = host.castBlockedReason()
        if (blocked != null) {
            CastStatus.showNotice(blocked)
            CastSupport.endSession(appContext, stopReceiver = true)
            return
        }
        val local = host.localPlayer ?: return
        val session = cast.currentCastSession
        hostAddress = LocalAddress.pickFor(session?.deviceAddress())
        if (hostAddress == null || !server.start()) {
            TrackLog.d(TAG, "cannot cast: no LAN address or the media server failed to start")
            CastStatus.showNotice(R.string.cast_connect_failed)
            CastSupport.endSession(appContext, stopReceiver = true)
            return
        }
        acquireLocks()
        endingPositionMs = null
        bridge.activate(local)
        host.useSessionPlayer(bridge)
        host.onCastStarted()
        CastStatus.publish(CastStatus.Snapshot(CastStatus.Phase.CASTING, session?.deviceName()))
        TrackLog.d(TAG, "casting to ${session?.deviceName()} via $hostAddress:${server.port}")
    }

    private fun stopCasting(prepareLocal: Boolean = true) {
        if (!bridge.active) {
            CastStatus.publish(CastStatus.Snapshot())
            return
        }
        val reported = bridge.deactivate()
        val position = endingPositionMs ?: reported
        endingPositionMs = null
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

        fun CastSession.deviceName(): String? = runCatching { castDevice?.friendlyName }.getOrNull()

        fun CastSession.deviceAddress(): java.net.InetAddress? =
            runCatching { castDevice?.inetAddress }.getOrNull()
    }
}
