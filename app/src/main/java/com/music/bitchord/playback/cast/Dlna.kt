package com.music.bitchord.playback.cast

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** A DLNA / UPnP AV media renderer found on the network: where to send what. */
data class DlnaRenderer(
    /** The device's UDN — stable across restarts and address changes. */
    val udn: String,
    val friendlyName: String,
    val location: String,
    val avTransportUrl: String,
    val renderingControlUrl: String?,
)

/**
 * The pieces of UPnP AV / DLNA that playing to a renderer needs: SSDP search
 * and replies, the device description, the SOAP actions on AVTransport and
 * RenderingControl, and the DIDL-Lite metadata a renderer shows. Plain Kotlin
 * and the JDK's XML parser, unit-tested; [DlnaSession] does the talking.
 */
internal object Dlna {

    const val SSDP_GROUP = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    /** The part of an SSDP reply or NOTIFY that matters: where the description is. */
    data class Announcement(val location: String, val usn: String?, val alive: Boolean)

    /** An SSDP M-SEARCH for media renderers, answered within [mxSeconds]. */
    fun mSearch(mxSeconds: Int = 2): ByteArray = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_GROUP:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mxSeconds\r\n" +
            "ST: $MEDIA_RENDERER\r\n" +
            "USER-AGENT: Android UPnP/1.1 BitChord/1\r\n" +
            "\r\n"
        ).toByteArray(Charsets.US_ASCII)

    /**
     * A search reply or a NOTIFY about a media renderer, or null for anything
     * else — every UPnP device on the network answers the multicast group.
     */
    fun parseAnnouncement(packet: String): Announcement? {
        val lines = packet.split("\r\n", "\n")
        val start = lines.firstOrNull()?.trim()?.uppercase() ?: return null
        val isReply = start.startsWith("HTTP/1.1 200") || start.startsWith("HTTP/1.0 200")
        val isNotify = start.startsWith("NOTIFY ")
        if (!isReply && !isNotify) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val type = headers[if (isReply) "st" else "nt"] ?: return null
        if (!type.startsWith("urn:schemas-upnp-org:device:MediaRenderer:", ignoreCase = true)) return null
        val alive = !isNotify || headers["nts"]?.equals("ssdp:byebye", ignoreCase = true) != true
        val location = headers["location"]
        if (location.isNullOrBlank() && alive) return null
        return Announcement(location.orEmpty(), headers["usn"], alive)
    }

    /**
     * The renderer described at [location] by [xml], or null when it has no
     * AVTransport to play through.
     */
    fun parseDescription(xml: ByteArray, location: String): DlnaRenderer? {
        val document = parse(xml) ?: return null
        val root = document.documentElement
        val base = child(root, "URLBase")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
        val device = findRenderer(child(root, "device") ?: return null) ?: return null
        val services = descendants(device, "service")
        fun controlUrlOf(type: String): String? {
            val service = services.firstOrNull {
                child(it, "serviceType")?.textContent?.trim()?.startsWith(type.substringBeforeLast(':')) == true
            } ?: return null
            val path = child(service, "controlURL")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return resolve(base, path)
        }
        val avTransport = controlUrlOf(AV_TRANSPORT) ?: return null
        val name = child(device, "friendlyName")?.textContent?.trim().orEmpty()
        val udn = child(device, "UDN")?.textContent?.trim().orEmpty()
        return DlnaRenderer(
            udn = udn.ifEmpty { location },
            friendlyName = name.ifEmpty { URI(location).host ?: "DLNA" },
            location = location,
            avTransportUrl = avTransport,
            renderingControlUrl = controlUrlOf(RENDERING_CONTROL),
        )
    }

    /** The MediaRenderer device: the root one, or one embedded in it (AV receivers nest theirs). */
    private fun findRenderer(device: Element): Element? {
        val type = child(device, "deviceType")?.textContent?.trim().orEmpty()
        if (type.startsWith("urn:schemas-upnp-org:device:MediaRenderer:")) return device
        val embedded = child(device, "deviceList") ?: return null
        return elements(embedded).filter { it.name() == "device" }.firstNotNullOfOrNull(::findRenderer)
    }

    /** A SOAP request body for [action] on [serviceType]. Argument order matters to some renderers. */
    fun soap(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
        for ((name, value) in args) {
            append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>')
        }
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    /** The `SOAPACTION` header for [action]. */
    fun soapAction(serviceType: String, action: String): String = "\"$serviceType#$action\""

    /** The out-arguments of a SOAP response, by name; null when [xml] is not one. */
    fun parseResponse(xml: ByteArray): Map<String, String>? {
        val document = parse(xml) ?: return null
        val body = descendants(document.documentElement, "Body").firstOrNull() ?: return null
        val response = elements(body).firstOrNull() ?: return null
        if (response.name() == "Fault") return null
        return elements(response).associate { it.name() to it.textContent.orEmpty() }
    }

    /** The UPnP error in a SOAP fault, e.g. "714 Illegal MIME-type", or null. */
    fun parseFault(xml: ByteArray): String? {
        val document = parse(xml) ?: return null
        val error = descendants(document.documentElement, "UPnPError").firstOrNull()
        val code = error?.let { child(it, "errorCode")?.textContent?.trim() }
        val description = error?.let { child(it, "errorDescription")?.textContent?.trim() }
        if (code == null && description == null) {
            return descendants(document.documentElement, "faultstring").firstOrNull()?.textContent?.trim()
        }
        return listOfNotNull(code, description).joinToString(" ")
    }

    /** What a renderer shows about a track, as DIDL-Lite (the caller escapes it into SOAP). */
    fun didl(request: CastProtocol.LoadRequest): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
        append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
        append("<item id=\"").append(escape(request.mediaId)).append("\" parentID=\"0\" restricted=\"1\">")
        append("<dc:title>").append(escape(request.title ?: request.mediaId)).append("</dc:title>")
        request.artist?.let {
            append("<upnp:artist>").append(escape(it)).append("</upnp:artist>")
            append("<dc:creator>").append(escape(it)).append("</dc:creator>")
        }
        request.album?.let { append("<upnp:album>").append(escape(it)).append("</upnp:album>") }
        request.artworkUrl?.let { append("<upnp:albumArtURI>").append(escape(it)).append("</upnp:albumArtURI>") }
        append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        append("<res protocolInfo=\"").append(escape(protocolInfo(request.contentType))).append("\">")
        append(escape(request.url))
        append("</res></item></DIDL-Lite>")
    }

    /** The `res@protocolInfo` for a stream of [mimeType]: any network, streamed, seekable by range. */
    fun protocolInfo(mimeType: String): String = "http-get:*:$mimeType:$CONTENT_FEATURES"

    /** The DLNA flags this phone's server offers: range seeks, streaming transfer. */
    const val CONTENT_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    /** "H:MM:SS", with optional fractions, to milliseconds; null for NOT_IMPLEMENTED and the like. */
    fun parseTime(text: String?): Long? {
        val parts = text?.trim()?.split(':')?.takeIf { it.size == 3 } ?: return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].substringBefore('/').toDoubleOrNull() ?: return null
        return hours * 3_600_000 + minutes * 60_000 + (seconds * 1000).toLong()
    }

    /** Milliseconds as the "H:MM:SS" a REL_TIME seek takes. */
    fun formatTime(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        return "%d:%02d:%02d".format(java.util.Locale.ROOT, total / 3600, total / 60 % 60, total % 60)
    }

    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun resolve(base: String, path: String): String =
        runCatching { URI(base).resolve(path).toString() }.getOrDefault(path)

    private fun parse(xml: ByteArray): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // A description comes from any device on the network: no DTDs, no
            // external entities. Not every parser knows every feature.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder()
        // Malformed XML is an answer (null), not something to print.
        builder.setErrorHandler(object : org.xml.sax.ErrorHandler {
            override fun warning(e: org.xml.sax.SAXParseException) = Unit
            override fun error(e: org.xml.sax.SAXParseException) = throw e
            override fun fatalError(e: org.xml.sax.SAXParseException) = throw e
        })
        builder.parse(ByteArrayInputStream(xml))
    }.getOrNull()

    private fun elements(parent: Node): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.name(): String = localName ?: nodeName.substringAfter(':')

    private fun child(parent: Element, name: String): Element? = elements(parent).firstOrNull { it.name() == name }

    private fun descendants(parent: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(element: Element) {
            for (child in elements(element)) {
                if (child.name() == name) out += child
                walk(child)
            }
        }
        walk(parent)
        return out
    }
}
