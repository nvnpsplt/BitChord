package com.music.bitchord.playback.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter

/**
 * The FOSS build's Google Cast: there is none. Google Cast needs Google Play
 * services' proprietary client library, which this build leaves out, so every
 * answer here is "not available" and the output sheet, settings and service
 * behave exactly as they did before Cast existed.
 *
 * Mirrors the `gms` source set's CastSupport API, which is the one to read.
 */
object CastSupport {
    const val hasCustomReceiver: Boolean = false

    fun isAvailable(context: Context): Boolean = false

    fun initialize(context: Context) = Unit

    fun initFailure(context: Context): String? = null

    fun isCastRoute(route: MediaRouter.RouteInfo): Boolean = false

    val routeSelector: MediaRouteSelector get() = MediaRouteSelector.EMPTY

    fun endSession(context: Context, stopReceiver: Boolean = true) = Unit

    fun deviceVolume(context: Context): Float? = null

    fun setDeviceVolume(context: Context, volume: Float) = Unit
}
