package com.music.bitchord.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class CastCaptionsTest {

    @Test
    fun cuesRunUntilTheNextLine() {
        val vtt = CastCaptions.webVtt(
            listOf(
                CastCaptions.Line(1_000, "First line"),
                CastCaptions.Line(4_500, "Second line"),
                CastCaptions.Line(65_250, "Last"),
            ),
        )
        assertEquals(
            "WEBVTT\n\n" +
                "00:00:01.000 --> 00:00:04.500\nFirst line\n\n" +
                // A long gap before the next line: held no longer than 12 s.
                "00:00:04.500 --> 00:00:16.500\nSecond line\n\n" +
                "00:01:05.250 --> 00:01:11.250\nLast\n\n",
            vtt,
        )
    }

    @Test
    fun blankLinesEndTheCueBeforeThem() {
        val vtt = CastCaptions.webVtt(
            listOf(
                CastCaptions.Line(1_000, "Sung"),
                CastCaptions.Line(3_000, ""),
                CastCaptions.Line(9_000, "Again"),
            ),
        )
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nSung\n\n00:00:09.000 --> 00:00:15.000\nAgain\n\n",
            vtt,
        )
    }

    @Test
    fun unsyncedLyrics_haveNoCues() {
        val vtt = CastCaptions.webVtt(listOf(CastCaptions.Line(0, "a"), CastCaptions.Line(0, "b")))
        assertEquals("WEBVTT\n\n", vtt)
    }

    @Test
    fun markupIsEscaped() {
        val vtt = CastCaptions.webVtt(listOf(CastCaptions.Line(500, "Rock & <roll> --> go")))
        assertEquals(
            "WEBVTT\n\n00:00:00.500 --> 00:00:06.500\nRock &amp; &lt;roll&gt; --&gt; go\n\n",
            vtt,
        )
    }
}
