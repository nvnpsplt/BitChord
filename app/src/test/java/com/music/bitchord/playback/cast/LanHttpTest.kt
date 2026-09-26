package com.music.bitchord.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class LanHttpTest {

    @Test
    fun onlyLocalAddressesAreLocal() {
        assertTrue(LanHttp.isLocal(InetAddress.getByName("192.168.1.4")))
        assertTrue(LanHttp.isLocal(InetAddress.getByName("10.0.0.2")))
        assertTrue(LanHttp.isLocal(InetAddress.getByName("172.20.1.1")))
        assertTrue(LanHttp.isLocal(InetAddress.getByName("169.254.3.3")))
        assertTrue(LanHttp.isLocal(InetAddress.getByName("fd12::1")))
        assertFalse(LanHttp.isLocal(InetAddress.getByName("8.8.8.8")))
        assertFalse(LanHttp.isLocal(InetAddress.getByName("2001:4860::8888")))
    }

    @Test(expected = IOException::class)
    fun internetHosts_areRefused() {
        LanHttp.get("http://8.8.8.8/description.xml", 1_000)
    }

    @Test
    fun sizedAndChunkedBodies() {
        val sized = LanHttp.readResponse(
            ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhelloEXTRA".toByteArray()),
            1024,
        )
        assertEquals(200, sized.code)
        assertEquals("hello", String(sized.body))

        val chunked = LanHttp.readResponse(
            ByteArrayInputStream(
                "HTTP/1.1 500 Internal Server Error\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5;x=y\r\npedia\r\n0\r\n\r\n"
                    .toByteArray(),
            ),
            1024,
        )
        assertEquals(500, chunked.code)
        assertEquals("Wikipedia", String(chunked.body))

        val continued = LanHttp.readResponse(
            ByteArrayInputStream("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.0 200 OK\r\n\r\nto the end".toByteArray()),
            1024,
        )
        assertEquals(200, continued.code)
        assertEquals("to the end", String(continued.body))
    }

    @Test
    fun postsToALoopbackServer() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var request = ""
            val worker = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val lines = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val length = lines.first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
                    val body = CharArray(length).also { input.read(it) }
                    request = lines.joinToString("\n") + "\n\n" + String(body)
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                }
            }
            val response = LanHttp.post(
                "http://127.0.0.1:${server.localPort}/ctl/AVT?x=1",
                mapOf("SOAPAction" to "\"urn:x#Play\""),
                "<xml/>".toByteArray(),
                2_000,
            )
            worker.join(2_000)
            assertEquals(200, response.code)
            assertEquals("ok", String(response.body))
            assertTrue(request, request.startsWith("POST /ctl/AVT?x=1 HTTP/1.1\n"))
            assertTrue(request, request.contains("Host: 127.0.0.1:${server.localPort}"))
            assertTrue(request, request.contains("SOAPAction: \"urn:x#Play\""))
            assertTrue(request, request.endsWith("<xml/>"))
        }
    }
}
