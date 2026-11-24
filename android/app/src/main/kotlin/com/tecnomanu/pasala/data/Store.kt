package com.tecnomanu.pasala.data

import android.content.Context
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

val PasalaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Everything persisted lives in one small JSON file in app storage. Small enough
 * (a handful of peers, last 200 history entries) that rewriting it is cheap.
 */
object Store {
    private const val TAG = "Pasala"
    private const val MAX_HISTORY = 200

    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val _state = MutableStateFlow(PersistedState())
    val state: StateFlow<PersistedState> = _state.asStateFlow()

    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        file = File(context.filesDir, "pasala.json")
        var loaded = runCatching {
            if (file.exists()) PasalaJson.decodeFromString(PersistedState.serializer(), file.readText()) else null
        }.onFailure { Log.w(TAG, "state file unreadable, starting fresh", it) }.getOrNull() ?: PersistedState()
        if (loaded.identity == null) {
            loaded = loaded.copy(identity = Identity(id = UUID.randomUUID().toString().lowercase(), name = defaultName(context)))
        }
        _state.value = loaded
        initialized = true
        persist()
    }

    private fun defaultName(context: Context): String {
        val n = runCatching { AndroidSettings.Global.getString(context.contentResolver, AndroidSettings.Global.DEVICE_NAME) }.getOrNull()
        if (!n.isNullOrBlank()) return n
        val model = Build.MODEL ?: "Android"
        val maker = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: ""
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    val identity: Identity get() = _state.value.identity!!
    val settings: Settings get() = _state.value.settings
    fun peer(id: String): Peer? = _state.value.peers.firstOrNull { it.id == id }

    fun mutate(block: (PersistedState) -> PersistedState) {
        _state.update(block)
        persist()
    }

    fun upsertPeer(peer: Peer) = mutate { s ->
        val others = s.peers.filterNot { it.id == peer.id }
        val existing = s.peers.firstOrNull { it.id == peer.id }
        s.copy(peers = others + peer.copy(localName = peer.localName ?: existing?.localName))
    }

    /**
     * A peer was heard from. Peers check in every few seconds, so the file is only rewritten when
     * something changed or the stored time is stale. See HISTORY.md#idle-wakeups
     */
    fun seen(id: String, name: String? = null, port: Int? = null, addrs: List<String>? = null) {
        val now = System.currentTimeMillis()
        var changed = false
        _state.update { s ->
            s.copy(peers = s.peers.map { p ->
                if (p.id != id) return@map p
                val n = p.copy(
                    name = name?.ifBlank { null } ?: p.name,
                    port = port?.takeIf { it > 0 } ?: p.port,
                    addrs = addrs?.ifEmpty { null } ?: p.addrs,
                )
                changed = n.name != p.name || n.port != p.port || n.addrs != p.addrs ||
                    now - (seenSaved[id] ?: 0L) > SEEN_PERSIST_MS
                n.copy(lastSeen = now)
            })
        }
        if (changed) {
            seenSaved[id] = now
            persist()
        }
    }

    private const val SEEN_PERSIST_MS = 10 * 60 * 1000L
    /** When each peer's lastSeen last reached the file. */
    private val seenSaved = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun updatePeer(id: String, block: (Peer) -> Peer) = mutate { s ->
        s.copy(peers = s.peers.map { if (it.id == id) block(it) else it })
    }

    fun removePeer(id: String) = mutate { s -> s.copy(peers = s.peers.filterNot { it.id == id }) }

    fun addHistory(item: HistoryItem) = mutate { s ->
        val all = listOf(item) + s.history
        // Texts keep their full body in their own file: drop it with the entry.
        all.drop(MAX_HISTORY).filter { it.mime == TEXT_MIME }.forEach { File(file.parentFile, "texts/${it.id}.txt").delete() }
        s.copy(history = all.take(MAX_HISTORY))
    }

    fun setName(name: String) = mutate { s -> s.copy(identity = s.identity!!.copy(name = name.trim().ifBlank { s.identity.name })) }

    fun setLastTextPeer(id: String) = mutate { s -> s.copy(settings = s.settings.copy(lastTextPeer = id)) }

    fun dismissBackgroundCard() = mutate { s -> s.copy(settings = s.settings.copy(backgroundCardDismissed = true)) }

    fun setAskBeforeReceiving(on: Boolean) = mutate { s -> s.copy(settings = s.settings.copy(askBeforeReceiving = on)) }

    private fun persist() {
        if (!::file.isInitialized) return
        scope.launch {
            writeLock.withLock {
                val snapshot = _state.value
                runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(PasalaJson.encodeToString(PersistedState.serializer(), snapshot))
                    if (!tmp.renameTo(file)) {
                        file.delete(); tmp.renameTo(file)
                    }
                }.onFailure { Log.e(TAG, "could not save state", it) }
            }
        }
    }
}
