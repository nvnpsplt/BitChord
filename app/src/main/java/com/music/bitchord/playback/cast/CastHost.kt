package com.music.bitchord.playback.cast

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.data.lyrics.LyricLine

/** What the cast controller needs from the playback service — see [CastController]. */
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

    /**
     * [item]'s synced lyrics, looked up the way the lyrics screen does. Blocks,
     * bounded by a timeout; called off the main thread. Null when there are none.
     */
    fun lyricsFor(item: MediaItem): List<LyricLine>?
}
