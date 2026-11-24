package com.tecnomanu.pasala.service

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.AndroidPlatform
import com.tecnomanu.pasala.core.Outgoing
import com.tecnomanu.pasala.core.Sender
import com.tecnomanu.pasala.net.Addresses
import com.tecnomanu.pasala.data.Store
import android.net.Uri
import com.tecnomanu.pasala.net.Discovery
import com.tecnomanu.pasala.net.PasalaServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps Pasala reachable: listens for peers, announces itself on the LAN, and runs
 * outgoing transfers (holding the read grant of shared files while they upload).
 */
class PasalaService : Service() {
    private val server = PasalaServer()
    private lateinit var discovery: Discovery
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var reannounce: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, Notifications.ID_SERVICE, Notifications.ready(this),
            // See HISTORY.md#fgs-type
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        discovery = Discovery(this)
        try {
            val port = server.start()
            discovery.register(port)
            announced = "${Store.identity.name}|$port|${Addresses.local().all}"
        } catch (e: Exception) {
            Log.e(TAG, "could not start listening", e)
        }
        watchNetwork()
        instance = this
        if (wantBrowsing) discovery.startBrowsing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND -> handleSend(intent)
        }
        return START_STICKY
    }

    private fun handleSend(intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB) ?: return
        val peerId = intent.getStringExtra(EXTRA_PEER) ?: return
        val clip = intent.clipData ?: return
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        val files = uris.mapNotNull { AndroidPlatform.describe(this, it) }
        if (files.isEmpty()) return
        Sender.start(jobId, peerId, files, intent.getStringExtra(EXTRA_COLLECTION))
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            // A network coming or going can drop the announcement even with the same address: always redo it.
            override fun onAvailable(network: Network) = scheduleReannounce(force = true)
            override fun onLost(network: Network) = scheduleReannounce(force = true)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = scheduleReannounce()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    /**
     * Announce again only when what we announce changed (addresses, name, port): link-property callbacks
     * fire often (VPN, IPv6, DNS) and each re-register means mDNS traffic. See HISTORY.md#idle-wakeups
     */
    private fun scheduleReannounce(force: Boolean = false) {
        reannounce?.cancel()
        reannounce = Live.appScope.launch {
            delay(1500)
            val p = Live.port.value
            if (p <= 0) return@launch
            val key = "${Store.identity.name}|$p|${Addresses.local().all}"
            if (!force && key == announced) return@launch
            announced = key
            discovery.register(p)
        }
    }

    @Volatile private var announced: String? = null

    // Swiping Pasala away from recents must not stop receiving. See HISTORY.md#survive-swipe
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "task removed; still listening on ${Live.port.value}")
        runCatching {
            ServiceCompat.startForeground(
                this, Notifications.ID_SERVICE, Notifications.ready(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }
    }

    override fun onDestroy() {
        instance = null
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        discovery.stopBrowsing()
        discovery.unregister()
        server.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Pasala"
        const val ACTION_SEND = "com.tecnomanu.pasala.SEND"
        const val EXTRA_JOB = "job"
        const val EXTRA_PEER = "peer"
        const val EXTRA_COLLECTION = "collection"

        @Volatile private var instance: PasalaService? = null
        @Volatile private var wantBrowsing = false

        /** Name changed: announce again on the LAN. */
        fun reannounce() { instance?.scheduleReannounce(force = true) }

        /** LAN browsing only while the app is on screen (saves battery). */
        fun setBrowsing(on: Boolean) {
            wantBrowsing = on
            instance?.let { if (on) it.discovery.startBrowsing() else it.discovery.stopBrowsing() }
        }

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, PasalaService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, i) }
                .onFailure { Log.w(TAG, "could not start service: ${it.message}") }
        }

        /** Hands the files (and their read grant) to the service, which sends them. */
        fun send(context: Context, jobId: String, peerId: String, files: List<Outgoing>, collectionId: String? = null) {
            if (files.isEmpty()) return
            val clip = ClipData.newRawUri("files", Uri.parse(files.first().uri))
            files.drop(1).forEach { clip.addItem(ClipData.Item(Uri.parse(it.uri))) }
            val i = Intent(context, PasalaService::class.java)
                .setAction(ACTION_SEND)
                .putExtra(EXTRA_JOB, jobId)
                .putExtra(EXTRA_PEER, peerId)
                .putExtra(EXTRA_COLLECTION, collectionId)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = clip
            ContextCompat.startForegroundService(context, i)
        }
    }
}
