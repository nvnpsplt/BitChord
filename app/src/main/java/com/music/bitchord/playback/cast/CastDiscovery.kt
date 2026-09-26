package com.music.bitchord.playback.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.music.bitchord.data.TrackLog
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
)

/**
 * Finds Cast devices the way Google's library does underneath: they announce
 * themselves over mDNS as `_googlecast._tcp`. Uses Android's own [NsdManager],
 * so there is no dependency and no Play services involved.
 *
 * Reference-counted: discovery runs while anything has [start]ed it (the
 * output sheet, while open) and stops with the last [stop] — browsing the
 * network costs battery and multicast traffic.
 */
object CastDiscovery {

    private const val SERVICE_TYPE = "_googlecast._tcp."
    private const val TAG = "BitChordCast"

    private val main = Handler(Looper.getMainLooper())
    private val found = MutableStateFlow<List<CastDevice>>(emptyList())

    /** What is on the network right now, sorted by name. */
    val devices: StateFlow<List<CastDevice>> = found.asStateFlow()

    private var users = 0
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
        found.value = byServiceName.values.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }
}
