package com.music.bitchord.playback.cast

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * Just enough multicast DNS (RFC 6762 / DNS-SD, RFC 6763) to find Cast
 * devices without leaning on Android's NsdManager, which is unreliable on a
 * number of phones: a PTR query for `_googlecast._tcp.local`, and a parser for
 * the answers — PTR, SRV, TXT and A records, with name compression.
 *
 * Plain Kotlin, unit-tested.
 */
internal object Mdns {

    const val GROUP = "224.0.0.251"
    const val PORT = 5353
    const val CAST_SERVICE = "_googlecast._tcp.local"

    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33
    private const val CLASS_IN = 1

    /** A PTR question for [service]. [unicastResponse] sets the QU bit. */
    fun query(service: String = CAST_SERVICE, unicastResponse: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write16(0) // id
        out.write16(0) // flags: standard query
        out.write16(1) // one question
        out.write16(0)
        out.write16(0)
        out.write16(0)
        writeName(out, service)
        out.write16(TYPE_PTR)
        out.write16(if (unicastResponse) CLASS_IN or 0x8000 else CLASS_IN)
        return out.toByteArray()
    }

    /** One service instance, assembled from whichever records a response carried. */
    data class Instance(
        val name: String,
        var target: String? = null,
        var port: Int = 0,
        var txt: Map<String, ByteArray?> = emptyMap(),
        var address: String? = null,
    )

    /**
     * The [service] instances described in one response packet, with their
     * host address filled in from any A record for their SRV target — in this
     * packet or in [knownAddresses], gathered from earlier ones.
     */
    fun parse(
        packet: ByteArray,
        service: String = CAST_SERVICE,
        knownAddresses: MutableMap<String, String> = HashMap(),
    ): List<Instance> {
        val reader = Reader(packet)
        if (packet.size < 12) return emptyList()
        reader.position = 4
        val questions = reader.u16()
        val records = reader.u16() + reader.u16() + reader.u16()
        repeat(questions) {
            reader.name()
            reader.position += 4
        }
        val instances = LinkedHashMap<String, Instance>()
        val serviceKey = service.lowercase().trimEnd('.')
        repeat(records) {
            val name = reader.name()
            val type = reader.u16()
            reader.u16() // class
            reader.position += 4 // ttl
            val length = reader.u16()
            val end = reader.position + length
            if (end > packet.size) return instances.values.toList()
            val key = name.lowercase().trimEnd('.')
            when (type) {
                TYPE_PTR -> if (key == serviceKey) {
                    val instance = reader.name()
                    instances.getOrPut(instance.lowercase().trimEnd('.')) { Instance(instance) }
                }
                TYPE_SRV -> if (key.endsWith(".$serviceKey")) {
                    reader.position += 4 // priority, weight
                    val port = reader.u16()
                    val target = reader.name()
                    instances.getOrPut(key) { Instance(name) }.apply {
                        this.port = port
                        this.target = target.lowercase().trimEnd('.')
                    }
                }
                TYPE_TXT -> if (key.endsWith(".$serviceKey")) {
                    instances.getOrPut(key) { Instance(name) }.txt = txt(packet, reader.position, end)
                }
                TYPE_A -> if (length == 4) {
                    val bytes = packet.copyOfRange(reader.position, end)
                    knownAddresses[key] = InetAddress.getByAddress(bytes).hostAddress ?: ""
                }
            }
            reader.position = end
        }
        instances.values.forEach { instance ->
            instance.target?.let { target -> knownAddresses[target]?.takeIf { it.isNotEmpty() } }
                ?.let { instance.address = it }
        }
        return instances.values.toList()
    }

    private fun txt(packet: ByteArray, start: Int, end: Int): Map<String, ByteArray?> {
        val entries = LinkedHashMap<String, ByteArray?>()
        var index = start
        while (index < end) {
            val length = packet[index].toInt() and 0xFF
            index++
            if (length == 0 || index + length > end) break
            val entry = packet.copyOfRange(index, index + length)
            index += length
            val equals = entry.indexOf('='.code.toByte())
            if (equals < 0) {
                entries[String(entry, Charsets.UTF_8)] = null
            } else {
                entries[String(entry, 0, equals, Charsets.UTF_8)] = entry.copyOfRange(equals + 1, entry.size)
            }
        }
        return entries
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        name.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    private fun ByteArrayOutputStream.write16(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private class Reader(private val packet: ByteArray) {
        var position = 0

        fun u16(): Int {
            if (position + 2 > packet.size) throw IndexOutOfBoundsException()
            val value = ((packet[position].toInt() and 0xFF) shl 8) or (packet[position + 1].toInt() and 0xFF)
            position += 2
            return value
        }

        /** A domain name at the cursor, following compression pointers. */
        fun name(): String {
            val labels = ArrayList<String>()
            var index = position
            var jumped = false
            var hops = 0
            while (true) {
                if (index >= packet.size) throw IndexOutOfBoundsException()
                val length = packet[index].toInt() and 0xFF
                when {
                    length == 0 -> {
                        if (!jumped) position = index + 1
                        return labels.joinToString(".")
                    }
                    length and 0xC0 == 0xC0 -> {
                        if (index + 1 >= packet.size || ++hops > 32) throw IndexOutOfBoundsException()
                        val pointer = ((length and 0x3F) shl 8) or (packet[index + 1].toInt() and 0xFF)
                        if (!jumped) position = index + 2
                        jumped = true
                        index = pointer
                    }
                    else -> {
                        if (index + 1 + length > packet.size) throw IndexOutOfBoundsException()
                        labels += String(packet, index + 1, length, Charsets.UTF_8)
                        index += 1 + length
                    }
                }
            }
        }
    }
}
