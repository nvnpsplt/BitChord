package com.music.bitchord.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

class CastMediaServerTest {

    // --- Range ---------------------------------------------------------------

    @Test
    fun range_openEnded_withKnownLength_endsAtLastByte() {
        val span = HttpRange.parse("bytes=100-")!!.resolve(1_000)!!
        assertEquals(100L, span.start)
        assertEquals(999L, span.endInclusive)
        assertEquals(900L, span.length)
    }

    @Test
    fun range_openEnded_withUnknownLength_isServedFromItsStart() {
        val span = HttpRange.parse("bytes=0-")!!.resolve(null)!!
        assertEquals(0L, span.start)
        assertNull(span.endInclusive)
        assertNull(span.length)
    }

    @Test
    fun range_bounded_isClampedToTheResource() {
        val span = HttpRange.parse("bytes=500-5000")!!.resolve(1_000)!!
        assertEquals(500L, span.start)
        assertEquals(999L, span.endInclusive)
    }

    @Test
    fun range_suffix_needsTheLength() {
        val range = HttpRange.parse("bytes=-200")!!
        assertNull(range.resolve(null))
        val span = range.resolve(1_000)!!
        assertEquals(800L, span.start)
        assertEquals(999L, span.endInclusive)
    }

    @Test
    fun range_pastTheEnd_isUnsatisfiable() {
        assertNull(HttpRange.parse("bytes=1000-")!!.resolve(1_000))
        assertNull(HttpRange.parse("bytes=50-10")!!.resolve(1_000))
    }

    @Test
    fun range_multipleRanges_answersTheFirst() {
        val span = HttpRange.parse("bytes=0-99, 200-299")!!.resolve(1_000)!!
        assertEquals(0L, span.start)
        assertEquals(99L, span.endInclusive)
    }

    @Test
    fun range_malformed_isIgnored() {
        assertNull(HttpRange.parse("items=0-10"))
        assertNull(HttpRange.parse("bytes=abc-"))
        assertNull(HttpRange.parse("bytes=-"))
        assertNull(HttpRange.parse("bytes=5"))
    }

    // --- Content type ---------------------------------------------------------

    @Test
    fun sniff_recognisesWhatTheReceiverPlays() {
        assertEquals("audio/flac", CastMime.sniff("fLaC".toByteArray() + bytes(0x00, 0x00, 0x00, 0x22)))
        assertEquals("audio/webm", CastMime.sniff(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01, 0x00)))
        assertEquals("audio/mp4", CastMime.sniff("\u0000\u0000\u0000\u0018ftypM4A ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("audio/ogg", CastMime.sniff("OggS\u0000\u0002".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("audio/mpeg", CastMime.sniff("ID3\u0004\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("audio/wav", CastMime.sniff("RIFF$\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("audio/aac", CastMime.sniff(bytes(0xFF, 0xF1, 0x50, 0x80)))
        assertEquals("audio/mpeg", CastMime.sniff(bytes(0xFF, 0xFB, 0x90, 0x64)))
    }

    @Test
    fun sniff_refusesManifestsAndText() {
        assertNull(CastMime.sniff("<?xml version=\"1.0\"?><MPD".toByteArray()))
        assertNull(CastMime.sniff("#EXTM3U\n#EXT-X".toByteArray()))
        assertNull(CastMime.sniff(bytes(0x00, 0x01)))
    }

    @Test
    fun sniffImage_recognisesArtwork() {
        assertEquals("image/jpeg", CastMime.sniffImage(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/png", CastMime.sniffImage(bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("image/webp", CastMime.sniffImage("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(CastMime.sniffImage(bytes(0x00)))
    }

    // --- Address --------------------------------------------------------------

    @Test
    fun sameSubnet_matchesOnThePrefixOnly() {
        val phone = InetAddress.getByName("192.168.1.23")
        assertTrue(LocalAddress.sameSubnet(phone, InetAddress.getByName("192.168.1.200"), 24))
        assertFalse(LocalAddress.sameSubnet(phone, InetAddress.getByName("192.168.2.200"), 24))
        assertTrue(LocalAddress.sameSubnet(phone, InetAddress.getByName("192.168.2.200"), 16))
        assertTrue(LocalAddress.sameSubnet(phone, InetAddress.getByName("192.168.1.30"), 27))
        assertFalse(LocalAddress.sameSubnet(phone, InetAddress.getByName("192.168.1.40"), 27))
    }

    // --- Request --------------------------------------------------------------

    @Test
    fun request_readsMethodPathAndHeaders() {
        val raw = "GET /a/abc123?x=1 HTTP/1.1\r\nHost: 192.168.1.23:4000\r\nRange: bytes=10-\r\n\r\n"
        val request = HttpRequest.read(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("GET", request.method)
        assertEquals("/a/abc123", request.path)
        assertEquals("bytes=10-", request.header("range"))
        assertEquals("bytes=10-", request.header("Range"))
    }

    @Test
    fun request_closedConnection_isNull() {
        assertNull(HttpRequest.read(ByteArrayInputStream(ByteArray(0))))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
