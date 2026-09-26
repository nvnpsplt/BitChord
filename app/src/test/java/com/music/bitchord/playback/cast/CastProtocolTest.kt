package com.music.bitchord.playback.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastProtocolTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun encode_thenDecode_roundTrips() {
        val message = CastProtocol.Message(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = CastProtocol.NS_RECEIVER,
            payload = """{"type":"LAUNCH","appId":"CC1AD845","requestId":1,"title":"ரத்தினமோ"}""",
        )
        val framed = CastProtocol.encode(message)
        val length = ((framed[0].toInt() and 0xFF) shl 24) or ((framed[1].toInt() and 0xFF) shl 16) or
            ((framed[2].toInt() and 0xFF) shl 8) or (framed[3].toInt() and 0xFF)
        assertEquals(framed.size - 4, length)
        assertEquals(message, CastProtocol.decode(framed.copyOfRange(4, framed.size)))
    }

    @Test
    fun encode_matchesTheKnownWireBytes() {
        // A PING on the heartbeat channel, as pychromecast sends it.
        val framed = CastProtocol.encode(
            CastProtocol.Message("sender-0", "receiver-0", CastProtocol.NS_HEARTBEAT, """{"type":"PING"}"""),
        )
        val body = framed.copyOfRange(4, framed.size)
        // protocol_version = 0, then source_id "sender-0".
        assertEquals(0x08, body[0].toInt())
        assertEquals(0x00, body[1].toInt())
        assertEquals(0x12, body[2].toInt())
        assertEquals(8, body[3].toInt())
        assertEquals("sender-0", String(body, 4, 8, Charsets.UTF_8))
    }

    @Test
    fun decode_readsBinaryAndSkipsUnknownFields() {
        // payload_binary (field 7) is kept; an unknown fixed64 field is skipped.
        val base = CastProtocol.encode(CastProtocol.Message("a", "b", "ns", "{}"))
        val body = base.copyOfRange(4, base.size) +
            byteArrayOf(0x3A, 0x02, 0x01, 0x02) +
            byteArrayOf(0x49, 1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(CastProtocol.Message("a", "b", "ns", "{}", byteArrayOf(1, 2)), CastProtocol.decode(body))
    }

    @Test
    fun binaryPayload_roundTrips() {
        val message = CastProtocol.Message("s", "d", CastDeviceAuth.NAMESPACE, "", byteArrayOf(0x0A, 0x00))
        val framed = CastProtocol.encode(message)
        assertEquals(message, CastProtocol.decode(framed.copyOfRange(4, framed.size)))
    }

    @Test
    fun load_describesAMusicTrack() {
        val payload = json(
            CastProtocol.load(
                requestId = 7,
                sessionId = "s1",
                request = CastProtocol.LoadRequest(
                    url = "http://192.168.1.5:4000/a/abc",
                    contentType = "audio/webm",
                    mediaId = "dQw4w9WgXcQ",
                    title = "Song",
                    artist = "Artist",
                    album = null,
                    artworkUrl = "https://lh3.googleusercontent.com/x",
                    startPositionMs = 61_500,
                    autoplay = true,
                ),
            ),
        )
        assertEquals("LOAD", payload["type"]!!.jsonPrimitive.content)
        assertEquals("61.5", payload["currentTime"]!!.jsonPrimitive.content)
        val media = payload["media"]!!.jsonObject
        assertEquals("http://192.168.1.5:4000/a/abc", media["contentId"]!!.jsonPrimitive.content)
        assertEquals("audio/webm", media["contentType"]!!.jsonPrimitive.content)
        assertEquals("3", media["metadata"]!!.jsonObject["metadataType"]!!.jsonPrimitive.content)
        assertEquals("dQw4w9WgXcQ", media["customData"]!!.jsonObject["mediaId"]!!.jsonPrimitive.content)
        assertFalse(media["metadata"]!!.jsonObject.containsKey("albumName"))
    }

    @Test
    fun receiverStatus_findsTheApp() {
        val status = CastProtocol.parseReceiverStatus(
            json(
                """{"type":"RECEIVER_STATUS","requestId":2,"status":{"applications":[
                   {"appId":"E8C28D3C","sessionId":"x","transportId":"y"},
                   {"appId":"CC1AD845","displayName":"Default Media Receiver","sessionId":"s-1","transportId":"t-1"}],
                   "volume":{"level":0.35,"muted":false}}}""",
            ),
            appId = "CC1AD845",
        )
        assertEquals(CastProtocol.RunningApp("CC1AD845", "s-1", "t-1"), status.app)
        assertEquals(0.35, status.volumeLevel!!, 1e-9)
        assertEquals(false, status.muted)
    }

    @Test
    fun receiverStatus_withoutTheApp_hasNone() {
        val status = CastProtocol.parseReceiverStatus(
            json("""{"type":"RECEIVER_STATUS","status":{"volume":{"level":1.0,"muted":true}}}"""),
            appId = "CC1AD845",
        )
        assertNull(status.app)
        assertEquals(true, status.muted)
    }

    @Test
    fun mediaStatus_keepsMediaFieldsTheReceiverStopsRepeating() {
        val first = CastProtocol.parseMediaStatus(
            json(
                """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":4,"playbackRate":1,"playerState":"BUFFERING",
                   "currentTime":0,"media":{"contentId":"http://p/a/1","duration":215.2}}]}""",
            ),
            previous = null,
        )!!
        assertEquals(CastProtocol.PlayerState.BUFFERING, first.playerState)
        assertEquals(215_200L, first.durationMs)

        val later = CastProtocol.parseMediaStatus(
            json("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":4,"playerState":"PLAYING","currentTime":12.25}]}"""),
            previous = first,
        )!!
        assertEquals(CastProtocol.PlayerState.PLAYING, later.playerState)
        assertEquals(12_250L, later.positionMs)
        assertEquals("http://p/a/1", later.contentId)
        assertEquals(215_200L, later.durationMs)
    }

    @Test
    fun mediaStatus_newSession_doesNotInheritTheOldMedia() {
        val old = CastProtocol.MediaStatus(4, CastProtocol.PlayerState.PLAYING, null, 0, "http://p/a/1", 100_000, 1.0)
        val next = CastProtocol.parseMediaStatus(
            json("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":5,"playerState":"IDLE","idleReason":"ERROR"}]}"""),
            previous = old,
        )!!
        assertNull(next.contentId)
        assertNull(next.durationMs)
        assertEquals("ERROR", next.idleReason)
    }

    @Test
    fun mediaStatus_empty_isNull() {
        assertNull(CastProtocol.parseMediaStatus(json("""{"type":"MEDIA_STATUS","status":[]}"""), null))
    }

    @Test
    fun txt_readsDeviceFacts() {
        val tv = mapOf(
            "fn" to "Living Room TV".toByteArray(),
            "md" to "Chromecast".toByteArray(),
            "ca" to "463365".toByteArray(),
            "id" to "abc123".toByteArray(),
        )
        assertEquals("Living Room TV", CastTxt.friendlyName(tv))
        assertTrue(CastTxt.hasScreen(tv))
        assertFalse(CastTxt.isGroup(tv))
        val group = mapOf("md" to "Google Cast Group".toByteArray(), "ca" to "2084".toByteArray())
        assertTrue(CastTxt.isGroup(group))
        assertFalse(CastTxt.hasScreen(group))
        assertNull(CastTxt.friendlyName(mapOf("fn" to null)))
    }
}
