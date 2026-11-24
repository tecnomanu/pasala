package com.tecnomanu.pasala.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.data.Store
import java.net.Inet4Address
import java.util.ArrayDeque

/**
 * Announces `_pasala._tcp` with TXT id=<our id>, and browses for the same service to
 * learn the current LAN address of peers we already know. Never pairs by itself.
 */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var browsing: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun register(port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = Store.identity.name.take(60)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", Store.identity.id)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "announced as ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "announce failed $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        registration = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { Log.w(TAG, "register", it) }
    }

    @Synchronized
    fun unregister() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    @Synchronized
    fun startBrowsing() {
        if (browsing != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "browse failed $code"); synchronized(this@Discovery) { browsing = null } }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueueResolve(info)
        }
        browsing = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "discover", it); browsing = null }
    }

    @Synchronized
    fun stopBrowsing() {
        browsing?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        browsing = null
    }

    // Older NsdManager versions allow only one resolve at a time: queue them.
    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.add(info)
        if (!resolving) resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        val next = resolveQueue.poll()
        if (next == null) { resolving = false; return }
        resolving = true
        @Suppress("DEPRECATION")
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) { resolveNext() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    handleResolved(i)
                    resolveNext()
                }
            })
        }.onFailure { resolving = false }
    }

    private fun handleResolved(info: NsdServiceInfo) {
        val id = info.attributes["id"]?.let { String(it, Charsets.UTF_8) } ?: return
        if (id == Store.identity.id || Store.peer(id) == null) return
        val host = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            (info.host as? Inet4Address)?.hostAddress
        } ?: return
        Log.d(TAG, "found ${Store.peer(id)?.displayName} at $host:${info.port}")
        Live.setDiscovered(id, Live.Found(host, info.port))
    }

    companion object {
        private const val TAG = "Pasala"
        const val SERVICE_TYPE = "_pasala._tcp"
    }
}
