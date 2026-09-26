package com.music.bitchord.playback.cast

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.BuildConfig

/**
 * The receiver BitChord launches on a Cast device, and the calls the UI makes
 * on a cast session.
 *
 * Casting is spoken directly over the Cast protocol — see [CastProtocol] — so
 * it needs neither Google Play services on the phone nor any proprietary
 * library in the app, and works on every phone with Wi-Fi.
 */
object CastSupport {

    /**
     * Whether this build carries BitChord's own receiver (docs/cast-receiver),
     * which shows BitChord's screen and synced lyrics on the TV. Builds without
     * one use Google's Default Media Receiver, which plays everything the same
     * and shows the artwork, title and artist.
     */
    val hasCustomReceiver: Boolean = BuildConfig.CAST_RECEIVER_APP_ID.isNotBlank()

    /** The receiver app the TV launches — see [hasCustomReceiver]. */
    val RECEIVER_APP_ID: String = BuildConfig.CAST_RECEIVER_APP_ID
        .ifBlank { CastProtocol.DEFAULT_MEDIA_RECEIVER }

    /** Casting needs nothing beyond Wi-Fi, which the device list itself reflects. */
    @Suppress("UNUSED_PARAMETER")
    fun isAvailable(context: Context): Boolean = true

    fun connect(device: CastDevice) = CastConnector.connect(device)

    /** Ends the cast session. [stopReceiver] also closes the receiver app on the TV. */
    @Suppress("UNUSED_PARAMETER")
    fun endSession(context: Context, stopReceiver: Boolean = true) = CastConnector.disconnect(stopReceiver)

    /** The receiver's volume, 0..1, or null when nothing is being cast to. */
    @Suppress("UNUSED_PARAMETER")
    fun deviceVolume(context: Context): Float? = CastConnector.volume()

    @Suppress("UNUSED_PARAMETER")
    fun setDeviceVolume(context: Context, volume: Float) = CastConnector.setVolume(volume)
}

/**
 * Where the UI's cast requests reach the service's [CastController]. Both live
 * in one process, so a plain reference does; the controller registers itself
 * while the playback service is up.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object CastConnector {

    @Volatile var controller: CastController? = null

    fun connect(device: CastDevice) {
        val target = controller
        if (target == null) {
            CastStatus.showNotice(com.music.bitchord.R.string.cast_connect_failed)
            return
        }
        target.connect(device)
    }

    fun disconnect(stopReceiver: Boolean) {
        controller?.disconnect(stopReceiver)
    }

    fun volume(): Float? = controller?.volume

    fun setVolume(volume: Float) {
        controller?.setVolume(volume.coerceIn(0f, 1f))
    }
}
