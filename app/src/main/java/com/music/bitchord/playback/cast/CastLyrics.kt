package com.music.bitchord.playback.cast

import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lyrics as BitChord's cast receiver reads them (docs/cast-receiver).
 *
 * Sent whole, once per track, rather than line by line: the receiver keeps its
 * own clock — the audio it is playing — so it highlights the right line
 * without the phone telling it when, and nothing drifts if Wi-Fi hiccups.
 */
internal object CastLyrics {

    /** The custom message channel; must match NAMESPACE in the receiver page. */
    const val NAMESPACE = "urn:x-cast:com.music.bitchord"

    /**
     * `{"type":"lyrics","mediaId":…,"synced":…,"lines":[{"t":ms,"text":…,"align":…,"words":[{"s":ms,"text":…}]}]}`.
     * Null [lines] sends an empty list, which clears whatever the TV showed.
     */
    fun message(mediaId: String, lines: List<LyricLine>?): String {
        val synced = lines.orEmpty().any { it.timeMs > 0 }
        val array = JSONArray()
        lines.orEmpty().forEach { line ->
            val json = JSONObject()
                .put("t", line.timeMs)
                .put("text", line.text)
            if (line.alignment == LyricAlignment.End) json.put("align", "end")
            if (line.words.isNotEmpty()) {
                val words = JSONArray()
                line.words.forEach { word ->
                    words.put(JSONObject().put("s", word.startMs).put("text", word.text))
                }
                json.put("words", words)
            }
            array.put(json)
        }
        return JSONObject()
            .put("type", "lyrics")
            .put("mediaId", mediaId)
            .put("synced", synced)
            .put("lines", array)
            .toString()
    }
}
