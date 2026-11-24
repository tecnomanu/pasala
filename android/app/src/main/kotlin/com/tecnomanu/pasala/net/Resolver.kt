package com.tecnomanu.pasala.net

import android.util.Log
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.data.HelloRequest
import com.tecnomanu.pasala.data.HelloResponse
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Finds a working address for a peer (protocol § Elegir dirección). */
object Resolver {
    private const val TAG = "Pasala"

    data class Route(val host: String, val port: Int)

    fun me(): HelloRequest = HelloRequest(
        name = Store.identity.name,
        port = Live.advertisedPort,
        addrs = Addresses.local().all,
    )

    fun candidates(peer: Peer): List<Route> {
        val list = mutableListOf<Route>()
        peer.lastGoodAddr?.let { list += Route(it, peer.port) }
        Live.discovered.value[peer.id]?.let { list += Route(it.host, it.port) }
        peer.addrs.forEach { list += Route(it, peer.port) }
        return list.distinct()
    }

    private fun tryHello(peer: Peer, r: Route): HelloResponse? = try {
        PasalaClient.hello(r.host, r.port, peer, me())
    } catch (e: Exception) {
        Log.d(TAG, "hello ${peer.displayName} @ ${r.host}:${r.port} failed: ${e.message}")
        null
    }

    private fun remember(peer: Peer, r: Route, resp: HelloResponse) {
        Store.updatePeer(peer.id) {
            it.copy(
                name = resp.name.ifBlank { it.name },
                platform = resp.platform.ifBlank { it.platform },
                port = if (resp.port > 0) resp.port else it.port,
                addrs = resp.addrs.ifEmpty { it.addrs },
                lastSeen = System.currentTimeMillis(),
                lastGoodAddr = r.host,
                collections = resp.collections,
            )
        }
    }

    /** Returns the first route that answers hello, or null. Updates online state. */
    suspend fun reach(peer: Peer): Route? = withContext(Dispatchers.IO) {
        val all = candidates(peer)
        if (all.isEmpty()) { Live.setOnline(peer.id, false); return@withContext null }
        // The last address that worked usually still works: try it alone first.
        val first = all.first()
        if (peer.lastGoodAddr != null) {
            tryHello(peer, first)?.let { remember(peer, first, it); Live.setOnline(peer.id, true); return@withContext first }
        }
        val rest = if (peer.lastGoodAddr != null) all.drop(1) else all
        // Race the rest; blocking calls run detached so a slow loser doesn't hold us up.
        val results = Channel<Pair<Route, HelloResponse?>>(rest.size.coerceAtLeast(1))
        rest.forEach { r -> Live.appScope.launch(Dispatchers.IO) { results.trySend(r to tryHello(peer, r)) } }
        var winner: Route? = null
        for (i in rest.indices) {
            val (r, resp) = results.receive()
            if (resp != null) { remember(peer, r, resp); winner = r; break }
        }
        Live.setOnline(peer.id, winner != null)
        winner
    }

    suspend fun refreshAll() = coroutineScope {
        Live.setChecking(true)
        try {
            Store.state.value.peers.map { p -> async { reach(p) } }.awaitAll()
        } finally {
            Live.setChecking(false)
        }
    }
}
