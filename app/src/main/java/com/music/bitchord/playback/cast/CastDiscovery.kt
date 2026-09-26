package com.music.bitchord.playback.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.net.wifi.WifiManager
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A Cast device on this network, as mDNS announced it. */
data class CastDevice(
    /** Stable across restarts: the device's own id, else its service name. */
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val hasScreen: Boolean,
    val isGroup: Boolean,
    /** Set for a DLNA / UPnP renderer rather than a Cast device. */
    val dlna: DlnaRenderer? = null,
)

/**
 * Finds Cast devices the way Google's library does underneath: they announce
 * themselves over mDNS as `_googlecast._tcp`. Two searches run side by side
 * and their results are merged: Android's own [NsdManager], and a direct mDNS
 * query of our own ([RawBrowser]) for the phones on which NsdManager finds
 * nothing — a known problem on several manufacturers' builds.
 *
 * Reference-counted: discovery runs while anything has [start]ed it (the
 * output sheet, while open) and stops with the last [stop] — browsing the
 * network costs battery and multicast traffic.
 */
object CastDiscovery {

    private const val SERVICE_TYPE = "_googlecast._tcp"
    private const val TAG = "BitChordCast"

    private val main = Handler(Looper.getMainLooper())
    private val found = MutableStateFlow<List<CastDevice>>(emptyList())

    /** What is on the network right now, sorted by name. */
    val devices: StateFlow<List<CastDevice>> = found.asStateFlow()

    private var users = 0
    private var raw: RawBrowser? = null
    private var ssdp: SsdpBrowser? = null
    private var manager: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val byServiceName = LinkedHashMap<String, CastDevice>()

    /** NsdManager resolves one service at a time on older Android; the rest wait here. */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start(context: Context) {
        main.post {
            users++
            if (users == 1) begin(context.applicationContext)
        }
    }

    fun stop() {
        main.post {
            users = (users - 1).coerceAtLeast(0)
            if (users == 0) end()
        }
    }

    /** Looks a device up by id among those currently seen. */
    fun find(id: String): CastDevice? = found.value.firstOrNull { it.id == id }

    private fun begin(context: Context) {
        raw = RawBrowser(context).also { it.start() }
        if (AppSettings.castDlna.value) ssdp = SsdpBrowser(context).also { it.start() }
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        manager = nsd
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                TrackLog.d(TAG, "cast discovery failed to start: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                main.post { enqueueResolve(service) }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                main.post {
                    if (byServiceName.remove(service.serviceName) != null) publish()
                }
            }
        }
        listener = discovery
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { TrackLog.d(TAG, "cast discovery unavailable: ${it.message}") }
    }

    private fun end() {
        raw?.stop()
        raw = null
        ssdp?.stop()
        ssdp = null
        val nsd = manager
        val discovery = listener
        if (nsd != null && discovery != null) runCatching { nsd.stopServiceDiscovery(discovery) }
        manager = null
        listener = null
        resolveQueue.clear()
        resolving = false
        // What was found stays listed: a device the sheet saw a moment ago is
        // still there to connect to, and a fresh browse re-confirms the list.
    }

    private fun enqueueResolve(service: NsdServiceInfo) {
        if (resolveQueue.none { it.serviceName == service.serviceName }) resolveQueue.addLast(service)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val nsd = manager ?: return
        if (resolving) return
        val service = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val done = {
            main.post {
                resolving = false
                resolveNext()
            }
        }
        runCatching {
            nsd.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        done()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        if (host != null) {
                            val attributes = serviceInfo.attributes.orEmpty()
                            val device = CastDevice(
                                id = CastTxt.id(attributes) ?: serviceInfo.serviceName,
                                name = CastTxt.friendlyName(attributes) ?: serviceInfo.serviceName,
                                host = host,
                                port = serviceInfo.port,
                                hasScreen = CastTxt.hasScreen(attributes),
                                isGroup = CastTxt.isGroup(attributes),
                            )
                            main.post {
                                byServiceName[serviceInfo.serviceName] = device
                                publish()
                            }
                        }
                        done()
                    }
                },
            )
        }.onFailure { done() }
    }

    private fun publish() {
        val dlnaOn = AppSettings.castDlna.value
        found.value = byServiceName.values
            .filter { dlnaOn || it.dlna == null }
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
    }

    /** A renderer seen by [SsdpBrowser]; main thread. */
    private fun onRenderer(renderer: DlnaRenderer) {
        val host = runCatching { java.net.URI(renderer.location).host }.getOrNull() ?: return
        val port = runCatching { java.net.URI(renderer.location).port }.getOrDefault(-1)
        val device = CastDevice(
            id = "dlna:" + renderer.udn,
            name = renderer.friendlyName,
            host = host,
            port = port,
            // UPnP says nothing about a screen; TVs almost always say so in their name.
            hasScreen = Regex("\\b(tv|television|bravia|viera|aquos)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(renderer.friendlyName),
            isGroup = false,
            dlna = renderer,
        )
        val key = "dlna:" + renderer.udn
        if (byServiceName[key] != device) {
            byServiceName[key] = device
            publish()
        }
    }

    private fun onRendererGone(usn: String?) {
        val udn = usn?.substringBefore("::") ?: return
        if (byServiceName.remove("dlna:$udn") != null) publish()
    }

    /**
     * Finds DLNA / UPnP media renderers: an SSDP search on the multicast group
     * every few seconds, answered straight back to our socket, then each new
     * renderer's description fetched for its name and control URLs.
     */
    private class SsdpBrowser(context: Context) {
        private val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        private var lock: WifiManager.MulticastLock? = null
        @Volatile private var running = false
        private var socket: DatagramSocket? = null
        private val described = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun start() {
            running = true
            lock = runCatching {
                wifi?.createMulticastLock("BitChord:dlna-discovery")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
            Thread({ run() }, "dlna-ssdp").apply { isDaemon = true }.start()
        }

        fun stop() {
            running = false
            runCatching { socket?.close() }
            runCatching { lock?.takeIf { it.isHeld }?.release() }
            lock = null
        }

        private fun run() {
            val group = runCatching { InetAddress.getByName(Dlna.SSDP_GROUP) }.getOrNull() ?: return
            val search = Dlna.mSearch()
            val direct = runCatching { DatagramSocket().apply { soTimeout = RECEIVE_TIMEOUT_MS } }
                .onFailure { TrackLog.d(TAG, "SSDP socket unavailable: ${it.message}") }
                .getOrNull() ?: return
            socket = direct
            var lastQuery = 0L
            val buffer = ByteArray(4096)
            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastQuery >= QUERY_INTERVAL_MS) {
                    lastQuery = now
                    runCatching { direct.send(DatagramPacket(search, search.size, group, Dlna.SSDP_PORT)) }
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    direct.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val announcement = Dlna.parseAnnouncement(text) ?: continue
                    if (!announcement.alive) {
                        main.post { onRendererGone(announcement.usn) }
                        continue
                    }
                    // Only the device that answered is asked for its description.
                    val locationHost = runCatching { java.net.URI(announcement.location).host }.getOrNull()
                    if (locationHost == null || InetAddress.getByName(locationHost) != packet.address) continue
                    val seen = described[announcement.location]
                    if (seen != null && now - seen < REDESCRIBE_MS) continue
                    described[announcement.location] = now
                    describe(announcement.location)
                } catch (_: SocketTimeoutException) {
                    // Nothing this round; ask again.
                } catch (e: Exception) {
                    if (running) TrackLog.d(TAG, "SSDP receive failed: ${e.message}")
                    if (direct.isClosed) running = false
                }
            }
        }

        private fun describe(location: String) {
            Thread({
                val renderer = runCatching {
                    val url = java.net.URL(location)
                    if (url.protocol != "http") return@runCatching null
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    try {
                        connection.connectTimeout = DESCRIBE_TIMEOUT_MS
                        connection.readTimeout = DESCRIBE_TIMEOUT_MS
                        if (connection.responseCode != 200) return@runCatching null
                        val bytes = connection.inputStream.use { it.readNBytesCompat(MAX_DESCRIPTION_BYTES) }
                        Dlna.parseDescription(bytes, location)
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
                if (renderer != null) main.post { onRenderer(renderer) }
            }, "dlna-describe").apply { isDaemon = true }.start()
        }

        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (out.size() < limit) {
                val read = read(chunk, 0, minOf(chunk.size, limit - out.size()))
                if (read < 0) break
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        }

        private companion object {
            const val QUERY_INTERVAL_MS = 5_000L
            const val RECEIVE_TIMEOUT_MS = 1_000
            const val REDESCRIBE_MS = 60_000L
            const val DESCRIBE_TIMEOUT_MS = 3_000
            const val MAX_DESCRIPTION_BYTES = 256 * 1024
        }
    }

    /** A device seen by [RawBrowser]; main thread. */
    private fun onRawInstance(instance: Mdns.Instance) {
        val host = instance.address ?: return
        if (instance.port <= 0) return
        val device = CastDevice(
            id = CastTxt.id(instance.txt) ?: instance.name,
            name = CastTxt.friendlyName(instance.txt) ?: instance.name.substringBefore("._googlecast"),
            host = host,
            port = instance.port,
            hasScreen = CastTxt.hasScreen(instance.txt),
            isGroup = CastTxt.isGroup(instance.txt),
        )
        val key = "mdns:" + instance.name.lowercase()
        if (byServiceName[key] != device) {
            byServiceName[key] = device
            publish()
        }
    }

    /**
     * Our own mDNS browser. Asks for `_googlecast._tcp.local` every few
     * seconds, both as a legacy unicast query (answered straight back to our
     * port) and on the multicast group, and listens on both.
     */
    private class RawBrowser(context: Context) {
        private val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        private var lock: WifiManager.MulticastLock? = null
        @Volatile private var running = false
        private var unicast: DatagramSocket? = null
        private var multicast: MulticastSocket? = null
        private val addresses = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val partial = java.util.concurrent.ConcurrentHashMap<String, Mdns.Instance>()

        fun start() {
            running = true
            lock = runCatching {
                wifi?.createMulticastLock("BitChord:cast-discovery")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
            Thread({ run() }, "cast-mdns").apply { isDaemon = true }.start()
        }

        fun stop() {
            running = false
            runCatching { unicast?.close() }
            runCatching { multicast?.close() }
            runCatching { lock?.takeIf { it.isHeld }?.release() }
            lock = null
        }

        private fun run() {
            val group = InetAddress.getByName(Mdns.GROUP)
            val direct = runCatching { DatagramSocket().apply { soTimeout = RECEIVE_TIMEOUT_MS } }.getOrNull()
            unicast = direct
            val shared = runCatching {
                MulticastSocket(null as java.net.SocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Mdns.PORT))
                    @Suppress("DEPRECATION")
                    joinGroup(group)
                    soTimeout = RECEIVE_TIMEOUT_MS
                }
            }.onFailure { TrackLog.d(TAG, "mDNS multicast socket unavailable: ${it.message}") }.getOrNull()
            multicast = shared
            shared?.let { socket -> Thread({ listen(socket) }, "cast-mdns-listen").apply { isDaemon = true }.start() }

            var lastQuery = 0L
            val buffer = ByteArray(9000)
            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastQuery >= QUERY_INTERVAL_MS) {
                    lastQuery = now
                    val legacy = Mdns.query()
                    runCatching { direct?.send(DatagramPacket(legacy, legacy.size, group, Mdns.PORT)) }
                    val multicastQuery = Mdns.query(unicastResponse = false)
                    runCatching { shared?.send(DatagramPacket(multicastQuery, multicastQuery.size, group, Mdns.PORT)) }
                }
                if (direct == null) {
                    Thread.sleep(RECEIVE_TIMEOUT_MS.toLong())
                    continue
                }
                receiveOnce(direct, buffer)
            }
        }

        private fun listen(socket: DatagramSocket) {
            val buffer = ByteArray(9000)
            while (running) receiveOnce(socket, buffer)
        }

        private fun receiveOnce(socket: DatagramSocket, buffer: ByteArray) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                val instances = runCatching { Mdns.parse(bytes, knownAddresses = addresses) }.getOrDefault(emptyList())
                for (instance in instances) {
                    // Records for one device can arrive over several packets.
                    val merged = partial.merge(instance.name.lowercase(), instance) { old, new ->
                        old.apply {
                            if (new.port > 0) port = new.port
                            new.target?.let { target = it }
                            if (new.txt.isNotEmpty()) txt = new.txt
                            new.address?.let { address = it }
                        }
                    } ?: instance
                    if (merged.address == null) merged.target?.let { addresses[it] }?.let { merged.address = it }
                    val snapshot = merged.copy()
                    main.post { onRawInstance(snapshot) }
                }
            } catch (_: SocketTimeoutException) {
                // Nothing this round; ask again.
            } catch (e: Exception) {
                if (running) TrackLog.d(TAG, "mDNS receive failed: ${e.message}")
                if (socket.isClosed) running = false
            }
        }

        private companion object {
            const val QUERY_INTERVAL_MS = 3_000L
            const val RECEIVE_TIMEOUT_MS = 1_000
        }
    }
}
