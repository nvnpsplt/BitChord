package com.music.bitchord.playback.cast

import androidx.media3.common.Player

/**
 * What the cast controller needs from the playback service. Kept apart from
 * [CastController] because that class only exists in builds with Google Cast
 * (the `gms` source set); FOSS builds compile the service against this and a
 * stub controller that never casts.
 */
internal interface CastHost {
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
