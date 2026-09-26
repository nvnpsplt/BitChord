package com.music.bitchord.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MdnsTest {

    private class Packet {
        val out = ByteArrayOutputStream()
        val offsets = HashMap<String, Int>()

        fun u16(v: Int) = apply { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Long) = apply { u16((v ushr 16).toInt()); u16((v and 0xFFFF).toInt()) }

        /** Writes [name], compressing any suffix already written. */
        fun name(name: String): Packet {
            val labels = name.split('.')
            for (i in labels.indices) {
                val suffix = labels.drop(i).joinToString(".")
                val known = offsets[suffix]
                if (known != null) return u16(0xC000 or known)
                offsets[suffix] = out.size()
                val bytes = labels[i].toByteArray()
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0)
            return this
        }

        fun record(name: String, type: Int, body: Packet.() -> Unit): Packet {
            name(name).u16(type).u16(1).u32(120)
            val lengthAt = out.size()
            u16(0)
            val start = out.size()
            body()
            val bytes = out.toByteArray()
            val length = bytes.size - start
            bytes[lengthAt] = (length ushr 8).toByte()
            bytes[lengthAt + 1] = length.toByte()
            out.reset()
            out.write(bytes)
            return this
        }
    }

    @Test
    fun query_asksForCastDevices() {
        val query = Mdns.query()
        // Header: one question, nothing else.
        assertEquals(1, query[5].toInt())
        val name = "_googlecast._tcp.local"
        val encoded = name.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() } + 0.toByte()
        assertEquals(encoded, query.copyOfRange(12, 12 + encoded.size).toList())
        // Type PTR, class IN.
        assertEquals(listOf<Byte>(0, 12, 0, 1), query.copyOfRange(12 + encoded.size, query.size).toList())
        assertEquals(0x80.toByte(), Mdns.query(unicastResponse = true)[query.size - 2])
    }

    @Test
    fun parse_assemblesAnInstanceFromCompressedRecords() {
        val instance = "Chromecast-1a2b._googlecast._tcp.local"
        val packet = Packet()
            .u16(0).u16(0x8400).u16(0).u16(1).u16(0).u16(3)
            .record("_googlecast._tcp.local", 12) { name(instance) }
            .record(instance, 16) {
                for (entry in listOf("id=1a2b", "md=Chromecast", "fn=Living Room TV", "ca=463365")) {
                    out.write(entry.length)
                    out.write(entry.toByteArray())
                }
            }
            .record(instance, 33) { u16(0).u16(0).u16(8009).name("1a2b.local") }
            .record("1a2b.local", 1) { out.write(byteArrayOf(192.toByte(), 168.toByte(), 1, 42)) }
            .out.toByteArray()

        val found = Mdns.parse(packet)
        assertEquals(1, found.size)
        val device = found.single()
        assertEquals(8009, device.port)
        assertEquals("192.168.1.42", device.address)
        assertEquals("Living Room TV", CastTxt.friendlyName(device.txt))
        assertTrue(CastTxt.hasScreen(device.txt))
    }

    @Test
    fun parse_usesAddressesFromEarlierPackets() {
        val known = hashMapOf("1a2b.local" to "10.0.0.7")
        val instance = "Speaker._googlecast._tcp.local"
        val packet = Packet()
            .u16(0).u16(0x8400).u16(0).u16(2).u16(0).u16(0)
            .record("_googlecast._tcp.local", 12) { name(instance) }
            .record(instance, 33) { u16(0).u16(0).u16(32187).name("1a2b.local") }
            .out.toByteArray()
        val device = Mdns.parse(packet, knownAddresses = known).single()
        assertEquals("10.0.0.7", device.address)
        assertEquals(32187, device.port)
    }

    @Test
    fun parse_ignoresOtherServices() {
        val packet = Packet()
            .u16(0).u16(0x8400).u16(0).u16(1).u16(0).u16(0)
            .record("_airplay._tcp.local", 12) { name("TV._airplay._tcp.local") }
            .out.toByteArray()
        assertTrue(Mdns.parse(packet).isEmpty())
    }
}
