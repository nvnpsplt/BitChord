package com.music.bitchord.playback.cast

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import com.music.bitchord.data.lyrics.LyricLine

/**
 * The FOSS build's cast controller: never constructed in practice, because
 * [CastSupport.isAvailable] is always false here, and inert if it were.
 *
 * Mirrors the `gms` source set's CastController API, which is the one to read.
 */
@Suppress("UNUSED_PARAMETER")
internal class CastController(
    context: Context,
    dataSourceFactory: DataSource.Factory,
    host: CastHost,
) {
    val isCasting: Boolean get() = false

    val playhead: Player? get() = null

    fun onLocalPlayerReplaced(local: Player) = Unit

    fun sendLyrics(mediaId: String, lines: List<LyricLine>?) = Unit

    fun release() = Unit
}
