package com.music.bitchord.playback.cast

/**
 * Synced lyrics as a WebVTT subtitle file, for receivers that cannot run
 * BitChord's own lyrics screen: Google's Default Media Receiver shows subtitle
 * tracks, so the current line appears at the bottom of the TV in time with
 * the song. Plain Kotlin, unit-tested.
 */
internal object CastCaptions {

    /** A subtitle file with nothing in it, for tracks without synced lyrics. */
    const val EMPTY = "WEBVTT\n\n"

    /** How long the last line stays up when nothing follows it. */
    private const val LAST_LINE_MS = 6_000L

    /** A line is not held up longer than this: an instrumental break shows nothing. */
    private const val MAX_LINE_MS = 12_000L

    /** One synced line: when it starts, what it says (blank for an instrumental gap). */
    data class Line(val startMs: Long, val text: String, val endMs: Long? = null)

    /**
     * WebVTT for [lines], each cue lasting until the next line starts. Blank
     * lines end the cue before them and show nothing. Unsynced lyrics — every
     * line at 0 — cannot be timed, and produce a file with no cues.
     */
    fun webVtt(lines: List<Line>): String {
        val out = StringBuilder(EMPTY)
        val sorted = lines.sortedBy { it.startMs }
        if (sorted.none { it.startMs > 0 }) return out.toString()
        sorted.forEachIndexed { index, line ->
            val text = line.text.trim()
            if (text.isEmpty()) return@forEachIndexed
            val next = sorted.getOrNull(index + 1)?.startMs
            val end = listOfNotNull(
                line.endMs,
                next,
                line.startMs + if (next == null) LAST_LINE_MS else MAX_LINE_MS,
            ).filter { it > line.startMs }.minOrNull() ?: return@forEachIndexed
            out.append(timestamp(line.startMs)).append(" --> ").append(timestamp(end)).append('\n')
            out.append(escape(text)).append("\n\n")
        }
        return out.toString()
    }

    private fun timestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val hours = total / 3_600_000
        val minutes = total / 60_000 % 60
        val seconds = total / 1_000 % 60
        val millis = total % 1_000
        return "%02d:%02d:%02d.%03d".format(java.util.Locale.ROOT, hours, minutes, seconds, millis)
    }

    /** Cue text is HTML-ish: `&` and `<` are markup, and "-->" would end the timing line. */
    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('\n', ' ')
}
