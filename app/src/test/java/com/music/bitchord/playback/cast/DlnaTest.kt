package com.music.bitchord.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaTest {

    @Test
    fun searchReply_forARenderer_givesItsLocation() {
        val reply = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "Location: http://192.168.1.20:49152/description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val announcement = Dlna.parseAnnouncement(reply)!!
        assertEquals("http://192.168.1.20:49152/description.xml", announcement.location)
        assertTrue(announcement.alive)
    }

    @Test
    fun otherDevices_andByebyes_areTold() {
        val server = "HTTP/1.1 200 OK\r\nLOCATION: http://x/d.xml\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"
        assertNull(Dlna.parseAnnouncement(server))
        assertNull(Dlna.parseAnnouncement("M-SEARCH * HTTP/1.1\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"))
        val byebye = "NOTIFY * HTTP/1.1\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "NTS: ssdp:byebye\r\nUSN: uuid:abc\r\n\r\n"
        assertFalse(Dlna.parseAnnouncement(byebye)!!.alive)
    }

    @Test
    fun description_findsAnEmbeddedRenderer_andResolvesControlUrls() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>
                <friendlyName>Receiver</friendlyName>
                <deviceList>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                    <friendlyName>Living Room TV</friendlyName>
                    <UDN>uuid:1234</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                        <controlURL>/upnp/control/rc</controlURL>
                      </service>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>upnp/control/avt</controlURL>
                      </service>
                    </serviceList>
                  </device>
                </deviceList>
              </device>
            </root>
        """.trimIndent().toByteArray()
        val renderer = Dlna.parseDescription(xml, "http://192.168.1.20:49152/dev/description.xml")!!
        assertEquals("Living Room TV", renderer.friendlyName)
        assertEquals("uuid:1234", renderer.udn)
        assertEquals("http://192.168.1.20:49152/dev/upnp/control/avt", renderer.avTransportUrl)
        assertEquals("http://192.168.1.20:49152/upnp/control/rc", renderer.renderingControlUrl)
    }

    @Test
    fun description_withoutAvTransport_isNoRenderer() {
        val xml = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Odd</friendlyName><serviceList/></device></root>""".toByteArray()
        assertNull(Dlna.parseDescription(xml, "http://h/d.xml"))
    }

    @Test
    fun description_withADoctype_isRefused() {
        val xml = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>&x;</friendlyName></device></root>""".toByteArray()
        assertNull(Dlna.parseDescription(xml, "http://h/d.xml"))
    }

    @Test
    fun soap_escapesArguments_inOrder() {
        val body = Dlna.soap(Dlna.AV_TRANSPORT, "Seek", listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "a<b"))
        assertTrue(body.contains("<u:Seek xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(body.contains("<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>a&lt;b</Target>"))
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Seek\"", Dlna.soapAction(Dlna.AV_TRANSPORT, "Seek"))
    }

    @Test
    fun didl_isEscapedOnceInsideTheSoapArgument() {
        val request = CastProtocol.LoadRequest(
            url = "http://192.168.1.5:4000/a/tok",
            contentType = "audio/mp4",
            mediaId = "id1",
            title = "Rock & Roll",
            artist = "A",
            album = null,
            artworkUrl = null,
            startPositionMs = 0,
            autoplay = true,
        )
        val didl = Dlna.didl(request)
        assertTrue(didl.contains("<dc:title>Rock &amp; Roll</dc:title>"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:audio/mp4:DLNA.ORG_OP=01;"))
        assertTrue(didl.contains(">http://192.168.1.5:4000/a/tok</res>"))
        val soap = Dlna.soap(Dlna.AV_TRANSPORT, "SetAVTransportURI", listOf("CurrentURIMetaData" to didl))
        assertTrue(soap.contains("&lt;dc:title&gt;Rock &amp;amp; Roll&lt;/dc:title&gt;"))
    }

    @Test
    fun responses_andFaults() {
        val ok = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <Track>1</Track><TrackDuration>0:03:25</TrackDuration><RelTime>0:01:02.500</RelTime>
            </u:GetPositionInfoResponse></s:Body></s:Envelope>""".toByteArray()
        val out = Dlna.parseResponse(ok)!!
        assertEquals(205_000L, Dlna.parseTime(out["TrackDuration"]))
        assertEquals(62_500L, Dlna.parseTime(out["RelTime"]))

        val fault = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
            <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>
            <UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode>
            <errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail>
            </s:Fault></s:Body></s:Envelope>""".toByteArray()
        assertNull(Dlna.parseResponse(fault))
        assertEquals("714 Illegal MIME-type", Dlna.parseFault(fault))
    }

    @Test
    fun times() {
        assertNull(Dlna.parseTime("NOT_IMPLEMENTED"))
        assertNull(Dlna.parseTime(""))
        assertNotNull(Dlna.parseTime("00:00:00"))
        assertEquals("1:02:03", Dlna.formatTime(3_723_900))
        assertEquals("0:00:00", Dlna.formatTime(-5))
    }
}
