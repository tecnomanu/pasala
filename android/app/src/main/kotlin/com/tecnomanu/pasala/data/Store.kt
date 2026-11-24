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

    fun updatePeer(id: String, block: (Peer) -> Peer) = mutate { s ->
        s.copy(peers = s.peers.map { if (it.id == id) block(it) else it })
    }

    fun removePeer(id: String) = mutate { s -> s.copy(peers = s.peers.filterNot { it.id == id }) }

    fun addHistory(item: HistoryItem) = mutate { s ->
        s.copy(history = (listOf(item) + s.history).take(MAX_HISTORY))
    }

    fun setName(name: String) = mutate { s -> s.copy(identity = s.identity!!.copy(name = name.trim().ifBlank { s.identity.name })) }

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
