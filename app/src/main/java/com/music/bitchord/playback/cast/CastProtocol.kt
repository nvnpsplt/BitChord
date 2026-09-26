package com.music.bitchord.playback.cast

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The Google Cast wire protocol ("CASTV2"), spoken directly.
 *
 * This is what lets BitChord cast without Google Play services' proprietary
 * client library: a Cast device listens on TLS port 8009 and exchanges small
 * protobuf envelopes carrying JSON, on a handful of well-known namespaces. The
 * protocol has been documented by reimplementation for a decade — pychromecast
 * (Home Assistant), node-castv2, VLC — and is what Google's own library speaks
 * underneath.
 *
 * Everything in this file is plain Kotlin with no Android dependency, so the
 * framing and the parsing are unit-tested on the JVM.
 */
internal object CastProtocol {

    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

    /** The device-side endpoint that launches and stops receiver apps. */
    const val PLATFORM_ID = "receiver-0"

    /**
     * Google's built-in Default Media Receiver. Needs no registration and
     * shows the artwork, title and artist of whatever it plays.
     */
    const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"

    /** Largest message the protocol allows; a longer length prefix means a broken stream. */
    const val MAX_MESSAGE_BYTES = 64 * 1024

    /**
     * One protocol envelope. Everything is a JSON string except device
     * authentication, which carries a protobuf in [payloadBinary].
     */
    data class Message(
        val sourceId: String,
        val destinationId: String,
        val namespace: String,
        val payload: String,
        val payloadBinary: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean = other is Message &&
            sourceId == other.sourceId && destinationId == other.destinationId &&
            namespace == other.namespace && payload == other.payload &&
            payloadBinary.contentEquals(other.payloadBinary)

        override fun hashCode(): Int =
            listOf(sourceId, destinationId, namespace, payload).hashCode() * 31 + payloadBinary.contentHashCode()
    }

    // --- Framing: a 4-byte big-endian length, then a protobuf CastMessage -----

    /**
     * ```
     * message CastMessage {
     *   required ProtocolVersion protocol_version = 1;  // CASTV2_1_0 = 0
     *   required string source_id = 2;
     *   required string destination_id = 3;
     *   required string namespace = 4;
     *   required PayloadType payload_type = 5;          // STRING = 0
     *   optional string payload_utf8 = 6;
     *   optional bytes payload_binary = 7;
     * }
     * ```
     */
    fun encode(message: Message): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeVarintField(1, 0)
        body.writeStringField(2, message.sourceId)
        body.writeStringField(3, message.destinationId)
        body.writeStringField(4, message.namespace)
        val binary = message.payloadBinary
        if (binary != null) {
            body.writeVarintField(5, 1)
            body.writeBytesField(7, binary)
        } else {
            body.writeVarintField(5, 0)
            body.writeStringField(6, message.payload)
        }
        val bytes = body.toByteArray()
        val framed = ByteArray(4 + bytes.size)
        framed[0] = (bytes.size ushr 24).toByte()
        framed[1] = (bytes.size ushr 16).toByte()
        framed[2] = (bytes.size ushr 8).toByte()
        framed[3] = bytes.size.toByte()
        System.arraycopy(bytes, 0, framed, 4, bytes.size)
        return framed
    }

    /** Decodes one CastMessage body (without its length prefix). */
    fun decode(bytes: ByteArray): Message {
        var source = ""
        var destination = ""
        var namespace = ""
        var payload = ""
        var binary: ByteArray? = null
        var index = 0
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (index >= bytes.size) throw IOException("truncated varint")
                val b = bytes[index++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("varint too long")
            }
        }
        while (index < bytes.size) {
            val key = varint().toInt()
            val field = key ushr 3
            when (key and 0x7) {
                0 -> varint()
                2 -> {
                    val length = varint().toInt()
                    if (length < 0 || index + length > bytes.size) throw IOException("truncated field $field")
                    if (field == 7) {
                        binary = bytes.copyOfRange(index, index + length)
                    } else {
                        val value = String(bytes, index, length, Charsets.UTF_8)
                        when (field) {
                            2 -> source = value
                            3 -> destination = value
                            4 -> namespace = value
                            6 -> payload = value
                        }
                    }
                    index += length
                }
                1 -> index += 8
                5 -> index += 4
                else -> throw IOException("unsupported wire type in field $field")
            }
        }
        return Message(source, destination, namespace, payload, binary)
    }

    internal fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                write(v.toInt())
                return
            }
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    internal fun ByteArrayOutputStream.writeVarintField(field: Int, value: Long) {
        writeVarint((field shl 3).toLong())
        writeVarint(value)
    }

    private fun ByteArrayOutputStream.writeStringField(field: Int, value: String) =
        writeBytesField(field, value.toByteArray(Charsets.UTF_8))

    internal fun ByteArrayOutputStream.writeBytesField(field: Int, bytes: ByteArray) {
        writeVarint(((field shl 3) or 2).toLong())
        writeVarint(bytes.size.toLong())
        write(bytes)
    }

    // --- Requests -------------------------------------------------------------

    fun connect(): String = buildJsonObject {
        put("type", "CONNECT")
        put("userAgent", "BitChord")
        put("origin", JsonObject(emptyMap()))
    }.toString()

    fun close(): String = """{"type":"CLOSE"}"""
    fun ping(): String = """{"type":"PING"}"""
    fun pong(): String = """{"type":"PONG"}"""

    fun getStatus(requestId: Int): String = buildJsonObject {
        put("type", "GET_STATUS")
        put("requestId", requestId)
    }.toString()

    fun launch(requestId: Int, appId: String): String = buildJsonObject {
        put("type", "LAUNCH")
        put("requestId", requestId)
        put("appId", appId)
    }.toString()

    fun stopApp(requestId: Int, sessionId: String): String = buildJsonObject {
        put("type", "STOP")
        put("requestId", requestId)
        put("sessionId", sessionId)
    }.toString()

    fun setVolume(requestId: Int, level: Double? = null, muted: Boolean? = null): String = buildJsonObject {
        put("type", "SET_VOLUME")
        put("requestId", requestId)
        put(
            "volume",
            buildJsonObject {
                level?.let { put("level", it.coerceIn(0.0, 1.0)) }
                muted?.let { put("muted", it) }
            },
        )
    }.toString()

    /** What the receiver is told about one track. */
    data class LoadRequest(
        val url: String,
        val contentType: String,
        val mediaId: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val artworkUrl: String?,
        val startPositionMs: Long,
        val autoplay: Boolean,
    )

    fun load(requestId: Int, sessionId: String, request: LoadRequest): String = buildJsonObject {
        put("type", "LOAD")
        put("requestId", requestId)
        put("sessionId", sessionId)
        put("autoplay", request.autoplay)
        put("currentTime", request.startPositionMs / 1000.0)
        put("media", media(request))
    }.toString()

    /**
     * Appends [request] to the receiver's queue, preloaded [preloadSeconds]
     * before the current track ends so the receiver runs straight into it.
     */
    fun queueInsert(requestId: Int, mediaSessionId: Int, request: LoadRequest, preloadSeconds: Int): String =
        buildJsonObject {
            put("type", "QUEUE_INSERT")
            put("requestId", requestId)
            put("mediaSessionId", mediaSessionId)
            put(
                "items",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("media", media(request))
                            put("autoplay", true)
                            put("startTime", 0)
                            put("preloadTime", preloadSeconds)
                        },
                    )
                },
            )
        }.toString()

    fun queueRemove(requestId: Int, mediaSessionId: Int, itemIds: List<Int>): String = buildJsonObject {
        put("type", "QUEUE_REMOVE")
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
        put("itemIds", buildJsonArray { itemIds.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    fun queueGetItemIds(requestId: Int, mediaSessionId: Int): String = buildJsonObject {
        put("type", "QUEUE_GET_ITEM_IDS")
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
    }.toString()

    /** The item ids of a QUEUE_ITEM_IDS reply, in queue order. */
    fun parseItemIds(payload: JsonObject): List<Int> =
        (payload["itemIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()

    /** Moves the receiver [jump] items along its queue — a skip to an already preloaded track. */
    fun queueJump(requestId: Int, mediaSessionId: Int, jump: Int): String = buildJsonObject {
        put("type", "QUEUE_UPDATE")
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
        put("jump", jump)
    }.toString()

    private fun media(request: LoadRequest): JsonObject = buildJsonObject {
        // The URL doubles as the content id: it is unique per source, so a
        // status report says unambiguously which track is loaded.
        put("contentId", request.url)
        put("contentUrl", request.url)
        put("contentType", request.contentType)
        put("streamType", "BUFFERED")
        put(
            "metadata",
            buildJsonObject {
                put("type", 0)
                put("metadataType", 3) // MUSIC_TRACK
                request.title?.let { put("title", it) }
                request.artist?.let {
                    put("artist", it)
                    put("subtitle", it)
                }
                request.album?.let { put("albumName", it) }
                request.artworkUrl?.let { url ->
                    put("images", buildJsonArray { add(buildJsonObject { put("url", url) }) })
                }
            },
        )
        put("customData", buildJsonObject { put("mediaId", request.mediaId) })
    }

    /** PLAY, PAUSE and STOP — the media commands that carry nothing but the session. */
    fun mediaCommand(type: String, requestId: Int, mediaSessionId: Int): String = buildJsonObject {
        put("type", type)
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
    }.toString()

    fun seek(requestId: Int, mediaSessionId: Int, positionMs: Long): String = buildJsonObject {
        put("type", "SEEK")
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
        put("currentTime", positionMs / 1000.0)
    }.toString()

    fun setPlaybackRate(requestId: Int, mediaSessionId: Int, rate: Double): String = buildJsonObject {
        put("type", "SET_PLAYBACK_RATE")
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
        put("playbackRate", rate)
    }.toString()

    // --- Responses ------------------------------------------------------------

    /** The fields of a RECEIVER_STATUS BitChord acts on. */
    data class ReceiverStatus(
        /** Null when the app is not (or no longer) running on the device. */
        val app: RunningApp?,
        val volumeLevel: Double?,
        val muted: Boolean?,
    )

    data class RunningApp(val appId: String, val sessionId: String, val transportId: String)

    fun parseReceiverStatus(payload: JsonObject, appId: String): ReceiverStatus {
        val status = payload["status"]?.jsonObjectOrNull()
        val app = (status?.get("applications") as? JsonArray)
            ?.mapNotNull { it.jsonObjectOrNull() }
            ?.firstOrNull { it.string("appId") == appId }
            ?.let { json ->
                val sessionId = json.string("sessionId") ?: return@let null
                val transportId = json.string("transportId") ?: return@let null
                RunningApp(appId, sessionId, transportId)
            }
        val volume = status?.get("volume")?.jsonObjectOrNull()
        return ReceiverStatus(
            app = app,
            volumeLevel = volume?.get("level")?.jsonPrimitiveOrNull()?.doubleOrNull,
            muted = volume?.get("muted")?.jsonPrimitiveOrNull()?.booleanOrNull,
        )
    }

    enum class PlayerState { IDLE, BUFFERING, PLAYING, PAUSED }

    /** What a MEDIA_STATUS says about the one media session BitChord runs. */
    data class MediaStatus(
        val mediaSessionId: Int,
        val playerState: PlayerState,
        /** FINISHED, ERROR, CANCELLED, INTERRUPTED — only while [playerState] is IDLE. */
        val idleReason: String?,
        val positionMs: Long,
        /** Carried over from the last status that included the media: later ones omit it. */
        val contentId: String?,
        val durationMs: Long?,
        val playbackRate: Double,
        /** The queue item playing, and every item in the receiver's queue, in order. */
        val currentItemId: Int? = null,
        val itemIds: List<Int> = emptyList(),
    )

    /**
     * The media session in a MEDIA_STATUS, merged onto [previous] for the
     * fields a status leaves out once they stop changing. Null when the status
     * lists no session at all — the receiver has nothing loaded.
     */
    fun parseMediaStatus(payload: JsonObject, previous: MediaStatus?): MediaStatus? {
        val entry = (payload["status"] as? JsonArray)?.firstOrNull()?.jsonObjectOrNull() ?: return null
        val sessionId = entry["mediaSessionId"]?.jsonPrimitiveOrNull()?.intOrNull ?: return null
        val currentItemId = entry["currentItemId"]?.jsonPrimitiveOrNull()?.intOrNull
        // "Same" means the same track, not merely the same session: a queue
        // runs several tracks through one media session.
        val sameSession = previous?.mediaSessionId == sessionId &&
            (currentItemId == null || previous?.currentItemId == null || previous.currentItemId == currentItemId)
        val media = entry["media"]?.jsonObjectOrNull()
        val items = (entry["items"] as? JsonArray)?.mapNotNull {
            (it as? JsonObject)?.get("itemId")?.jsonPrimitiveOrNull()?.intOrNull
        }
        val state = when (entry.string("playerState")) {
            "PLAYING" -> PlayerState.PLAYING
            "PAUSED" -> PlayerState.PAUSED
            "BUFFERING" -> PlayerState.BUFFERING
            else -> PlayerState.IDLE
        }
        val durationSec = media?.get("duration")?.jsonPrimitiveOrNull()?.doubleOrNull
        return MediaStatus(
            mediaSessionId = sessionId,
            playerState = state,
            idleReason = entry.string("idleReason"),
            positionMs = ((entry["currentTime"]?.jsonPrimitiveOrNull()?.doubleOrNull ?: 0.0) * 1000).toLong(),
            contentId = media?.string("contentId") ?: previous?.contentId?.takeIf { sameSession },
            durationMs = durationSec?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
                ?: previous?.durationMs?.takeIf { sameSession },
            playbackRate = entry["playbackRate"]?.jsonPrimitiveOrNull()?.doubleOrNull ?: 1.0,
            currentItemId = currentItemId ?: previous?.let { p -> p.currentItemId.takeIf { p.mediaSessionId == sessionId } },
            itemIds = items ?: previous?.let { p -> p.itemIds.takeIf { p.mediaSessionId == sessionId } }.orEmpty(),
        )
    }

    fun type(payload: JsonObject): String? = payload.string("type")

    fun requestId(payload: JsonObject): Int? = payload["requestId"]?.jsonPrimitiveOrNull()?.intOrNull

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** TXT-record facts about a Cast device found on the network. */
internal object CastTxt {

    /** Bit 0 of `ca`: the device has a screen. */
    private const val CAPABILITY_VIDEO_OUT = 1

    fun friendlyName(attributes: Map<String, ByteArray?>): String? = attributes.text("fn")
    fun model(attributes: Map<String, ByteArray?>): String? = attributes.text("md")
    fun id(attributes: Map<String, ByteArray?>): String? = attributes.text("id")

    fun hasScreen(attributes: Map<String, ByteArray?>): Boolean =
        (attributes.text("ca")?.toIntOrNull() ?: 0) and CAPABILITY_VIDEO_OUT != 0

    fun isGroup(attributes: Map<String, ByteArray?>): Boolean =
        model(attributes)?.equals("Google Cast Group", ignoreCase = true) == true

    private fun Map<String, ByteArray?>.text(key: String): String? =
        this[key]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
}
