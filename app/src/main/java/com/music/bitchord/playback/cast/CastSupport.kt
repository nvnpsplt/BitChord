package com.music.bitchord.playback.cast

import android.content.Context
import android.os.Looper
import androidx.media3.cast.Cast
import androidx.media3.cast.DefaultCastOptionsProvider
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where playback is going, as far as Google Cast is concerned — read by the
 * player's output line and the output sheet, written by the service.
 *
 * In-process state rather than something carried over the session: the UI and
 * [com.music.bitchord.playback.PlaybackService] share a process, the same way
 * [com.music.bitchord.playback.AudioOutputStatus] is shared.
 */
object CastStatus {

    enum class Phase {
        /** Playing on this phone. */
        IDLE,

        /** A cast device was picked and the session is being set up. */
        CONNECTING,

        /** The cast device is playing; this phone is the remote. */
        CASTING,
    }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        /** The receiver's own name, e.g. "Living Room TV". */
        val deviceName: String? = null,
    ) {
        val isCasting: Boolean get() = phase == Phase.CASTING
        val isActive: Boolean get() = phase != Phase.IDLE
    }

    private val state = MutableStateFlow(Snapshot())
    val current: StateFlow<Snapshot> = state.asStateFlow()

    internal fun publish(snapshot: Snapshot) {
        state.value = snapshot
    }

    /**
     * A one-line message for the listener, e.g. why a track was skipped on the
     * receiver. Consumed by the UI with [consumeNotice].
     */
    private val notice = MutableStateFlow<Int?>(null)
    val pendingNotice: StateFlow<Int?> = notice.asStateFlow()

    internal fun showNotice(messageRes: Int) {
        notice.value = messageRes
    }

    fun consumeNotice() {
        notice.value = null
    }
}

/**
 * Setting up Google Cast, and the one fact everything else asks first: whether
 * it can work on this phone at all.
 *
 * Cast rides on Google Play services. A phone without them — a de-Googled ROM,
 * a Huawei, an emulator image without Play — simply has no Cast: the output
 * sheet does not offer it and nothing is initialised. That is checked, not
 * assumed, so the same APK behaves correctly on both.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object CastSupport {

    /**
     * The Default Media Receiver. Chosen over registering a receiver of our own
     * because it needs no Cast Console entry to sideload and play: it plays any
     * plain audio URL, shows the artwork and titles it is given, and is the same
     * receiver every Media3 app uses.
     */
    const val RECEIVER_APP_ID: String = DefaultCastOptionsProvider.APP_ID_DEFAULT_RECEIVER_WITH_DRM

    @Volatile private var availability: Boolean? = null

    /** Whether Google Play services are present and current enough for Cast. */
    fun isAvailable(context: Context): Boolean {
        availability?.let { return it }
        val result = runCatching {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context.applicationContext) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
        availability = result
        return result
    }

    /**
     * Starts the Cast framework in this process. Main thread only, and cheap to
     * call again: the framework initialises once and later calls are no-ops.
     * Does nothing where [isAvailable] says Cast cannot work.
     */
    fun initialize(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Cast must be initialised on the main thread" }
        if (!isAvailable(context)) return
        runCatching {
            val cast = Cast.getSingletonInstance(context.applicationContext)
            if (cast.needsInitialization()) cast.initialize()
        }.onFailure {
            TrackLog.d("BitChordCast", "cast initialisation failed: ${it.message}")
            availability = false
        }
    }

    /** What the output sheet asks MediaRouter to discover: devices that can run our receiver. */
    val routeSelector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(RECEIVER_APP_ID))
            .build()
    }

    /**
     * Ends the cast session. [stopReceiver] also closes the receiver app on the
     * TV — what "Stop casting" means — rather than merely letting go of it.
     */
    fun endSession(context: Context, stopReceiver: Boolean = true) {
        if (!isAvailable(context)) return
        runCatching {
            Cast.getSingletonInstance(context.applicationContext).endCurrentSession(stopReceiver)
        }
    }

    /** The receiver's volume, 0..1, or null when nothing is being cast to. */
    fun deviceVolume(context: Context): Float? {
        if (!isAvailable(context)) return null
        return runCatching {
            Cast.getSingletonInstance(context.applicationContext)
                .currentCastSession
                ?.takeIf { it.isConnected }
                ?.volume
                ?.toFloat()
        }.getOrNull()
    }

    fun setDeviceVolume(context: Context, volume: Float) {
        if (!isAvailable(context)) return
        runCatching {
            Cast.getSingletonInstance(context.applicationContext)
                .currentCastSession
                ?.takeIf { it.isConnected }
                ?.volume = volume.coerceIn(0f, 1f).toDouble()
        }
    }
}
