package com.music.bitchord.playback.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
    fun captions_ride_alongAsAnActiveTextTrack() {
        val request = CastProtocol.LoadRequest(
            "http://p/a/1", "audio/webm", "id1", "T", null, null, null, 0, true,
            captionsUrl = "http://p/l/1",
        )
        val load = json(CastProtocol.load(1, "s1", request))
        assertEquals("1", load["activeTrackIds"]!!.jsonArray.single().jsonPrimitive.content)
        val track = load["media"]!!.jsonObject["tracks"]!!.jsonArray.single().jsonObject
        assertEquals("TEXT", track["type"]!!.jsonPrimitive.content)
        assertEquals("SUBTITLES", track["subtype"]!!.jsonPrimitive.content)
        assertEquals("http://p/l/1", track["trackContentId"]!!.jsonPrimitive.content)
        assertEquals("text/vtt", track["trackContentType"]!!.jsonPrimitive.content)

        val item = json(CastProtocol.queueInsert(2, 3, request, 20))["items"]!!.jsonArray.single().jsonObject
        assertEquals("1", item["activeTrackIds"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(item["media"]!!.jsonObject.containsKey("tracks"))

        val plain = json(CastProtocol.load(1, "s1", request.copy(captionsUrl = null)))
        assertFalse(plain.containsKey("activeTrackIds"))
        assertFalse(plain["media"]!!.jsonObject.containsKey("tracks"))
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
    fun mediaStatus_tracksTheQueue() {
        val first = CastProtocol.parseMediaStatus(
            json(
                """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":2,"playerState":"PLAYING","currentTime":200,
                   "currentItemId":1,"items":[{"itemId":1},{"itemId":2}],
                   "media":{"contentId":"http://p/a/1","duration":210}}]}""",
            ),
            previous = null,
        )!!
        assertEquals(1, first.currentItemId)
        assertEquals(listOf(1, 2), first.itemIds)

        // The receiver ran into the queued track: same session, new item.
        val next = CastProtocol.parseMediaStatus(
            json(
                """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":2,"playerState":"BUFFERING","currentTime":0,
                   "currentItemId":2,"media":{"contentId":"http://p/a/2"}}]}""",
            ),
            previous = first,
        )!!
        assertEquals("http://p/a/2", next.contentId)
        assertEquals(2, next.currentItemId)
        assertEquals(listOf(1, 2), next.itemIds)
        // The previous track's duration is not carried onto this one.
        assertNull(next.durationMs)
    }

    @Test
    fun queueCommands_areWellFormed() {
        val request = CastProtocol.LoadRequest("http://p/a/2", "audio/webm", "id2", "T", null, null, null, 0, true)
        val insert = json(CastProtocol.queueInsert(3, 2, request, 20))
        assertEquals("QUEUE_INSERT", insert["type"]!!.jsonPrimitive.content)
        val item = insert["items"]!!.jsonArray.single().jsonObject
        assertEquals("20", item["preloadTime"]!!.jsonPrimitive.content)
        assertEquals("http://p/a/2", item["media"]!!.jsonObject["contentId"]!!.jsonPrimitive.content)
        val remove = json(CastProtocol.queueRemove(4, 2, listOf(5, 6)))
        assertEquals(listOf("5", "6"), remove["itemIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("1", json(CastProtocol.queueJump(5, 2, 1))["jump"]!!.jsonPrimitive.content)
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
