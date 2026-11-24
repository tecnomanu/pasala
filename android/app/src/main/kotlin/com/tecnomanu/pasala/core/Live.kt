package com.tecnomanu.pasala.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory, non-persisted state shared by the service and the UI (same process). */
object Live {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    const val DEFAULT_PORT = 47820

    private val _port = MutableStateFlow(0)
    /** 0 while not listening. */
    val port: StateFlow<Int> = _port.asStateFlow()
    fun setPort(p: Int) { _port.value = p }

    /** Port to advertise even before the listener is up. */
    val advertisedPort: Int get() = _port.value.takeIf { it > 0 } ?: DEFAULT_PORT

    private val _online = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val online: StateFlow<Map<String, Boolean>> = _online.asStateFlow()
    fun setOnline(peerId: String, on: Boolean) = _online.update { it + (peerId to on) }

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    fun setChecking(v: Boolean) { _checking.value = v }

    /** Peer id -> host:port seen on the LAN. */
    data class Found(val host: String, val port: Int)
    private val _discovered = MutableStateFlow<Map<String, Found>>(emptyMap())
    val discovered: StateFlow<Map<String, Found>> = _discovered.asStateFlow()
    fun setDiscovered(id: String, f: Found) = _discovered.update { it + (id to f) }

    /** A new peer was paired (either direction). */
    private val _paired = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val paired: SharedFlow<String> = _paired.asSharedFlow()
    fun emitPaired(peerId: String) { _paired.tryEmit(peerId) }

    /** Files arriving right now, for the home screen. */
    data class Incoming(
        val key: String,
        val peerName: String,
        val name: String,
        val mime: String,
        val size: Long,
        val received: Long,
        val index: Int,
        val count: Int,
    )
    private val _incoming = MutableStateFlow<List<Incoming>>(emptyList())
    val incoming: StateFlow<List<Incoming>> = _incoming.asStateFlow()
    fun putIncoming(i: Incoming) = _incoming.update { list -> list.filterNot { it.key == i.key } + i }
    fun removeIncoming(key: String) = _incoming.update { list -> list.filterNot { it.key == key } }

    /** Offers waiting for the person to accept, shown as a dialog when the app is open. */
    data class PendingOffer(val batch: String, val peerName: String, val count: Int, val totalSize: Long)
    private val _pendingOffers = MutableStateFlow<List<PendingOffer>>(emptyList())
    val pendingOffers: StateFlow<List<PendingOffer>> = _pendingOffers.asStateFlow()
    fun addPendingOffer(o: PendingOffer) = _pendingOffers.update { it + o }
    fun removePendingOffer(batch: String) = _pendingOffers.update { l -> l.filterNot { it.batch == batch } }
}
