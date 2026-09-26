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
     * Whether this build carries BitChord's own receiver (docs/cast-receiver),
     * which shows BitChord's screen and synced lyrics on the TV. Builds without
     * one fall back to Media3's stock receiver, which plays everything the same
     * but only shows the artwork and titles — and calls itself "ExoPlayer".
     */
    val hasCustomReceiver: Boolean = com.music.bitchord.BuildConfig.CAST_RECEIVER_APP_ID.isNotBlank()

    /** The receiver app the TV launches — see [hasCustomReceiver]. */
    val RECEIVER_APP_ID: String = com.music.bitchord.BuildConfig.CAST_RECEIVER_APP_ID
        .ifBlank { DefaultCastOptionsProvider.APP_ID_DEFAULT_RECEIVER_WITH_DRM }

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
            if (cast.needsInitialization()) {
                cast.initialize(
                    androidx.media3.cast.CastParams.Builder()
                        .setReceiverApplicationId(RECEIVER_APP_ID)
                        .build(),
                )
            }
        }.onFailure {
            TrackLog.d("BitChordCast", "cast initialisation failed: ${it.message}")
            availability = false
        }
    }

    /**
     * Why the Cast framework could not start on this phone, or null while it
     * is fine (or still starting). Shown in the output sheet, because an
     * empty device list otherwise looks identical to "no TV on this Wi-Fi".
     */
    fun initFailure(context: Context): String? {
        if (!isAvailable(context)) return "Google Play services unavailable"
        return runCatching {
            Cast.getSingletonInstance(context.applicationContext).castContextLoadFailure
                ?.let { it.message ?: it.javaClass.simpleName }
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    /**
     * Whether [route] is a Google Cast device. Matched on the Cast control
     * category family rather than only the exact receiver category: the route
     * a Cast device publishes carries whichever category the discovery request
     * that found it asked for, and requests from other apps' sessions or the
     * system can reach this process's MediaRouter too.
     */
    fun isCastRoute(route: androidx.mediarouter.media.MediaRouter.RouteInfo): Boolean {
        if (route.isDefaultOrBluetooth || !route.isEnabled) return false
        if (route.matchesSelector(routeSelector)) return true
        return route.controlFilters.any { filter ->
            (0 until filter.countCategories()).any { filter.getCategory(it).startsWith(CAST_CATEGORY_PREFIX) }
        }
    }

    private const val CAST_CATEGORY_PREFIX = "com.google.android.gms.cast.CATEGORY_CAST"

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
