package com.tecnomanu.pasala.data

import kotlinx.serialization.Serializable

/** Persisted identity of this device (protocol § Identidad). */
@Serializable
data class Identity(
    val id: String,
    val name: String,
    val platform: String = "android",
)

/** A named destination the receiver defines (protocol § hello, `collections`). */
@Serializable
data class Collection(val id: String, val name: String)

@Serializable
data class Peer(
    val id: String,
    val name: String,
    val platform: String,
    val port: Int,
    val addrs: List<String>,
    val secret: String,
    val pairedAt: Long,
    val lastSeen: Long = 0,
    /** Local-only rename. Null = use [name] the peer reports. */
    val localName: String? = null,
    /** Last address that answered (host only). */
    val lastGoodAddr: String? = null,
    /** Collections from this peer's latest hello reply. */
    val collections: List<Collection> = emptyList(),
    /** Collection id last chosen when sending to this peer (null = Ninguna). */
    val lastCollection: String? = null,
) {
    val displayName: String get() = localName?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class Settings(
    val askBeforeReceiving: Boolean = false,
    /** Device last chosen to send a text to. */
    val lastTextPeer: String? = null,
    /** The person said "Not now" to the background card on Home. */
    val backgroundCardDismissed: Boolean = false,
)

@Serializable
data class HistoryItem(
    val id: String,
    val direction: String, // "in" | "out"
    val peerId: String,
    val peerName: String,
    val name: String,
    val size: Long,
    val mime: String,
    val time: Long,
    /** For received items: MediaStore content uri. */
    val uri: String? = null,
    /** For sent items: path of a small cached thumbnail, if any. */
    val thumbPath: String? = null,
    /** For sent items: name of the destination collection, if one was chosen. */
    val collection: String? = null,
    /** Text items (mime [TEXT_MIME]): a short preview; the full text lives in [TextStore]. */
    val text: String? = null,
    val ok: Boolean = true,
)

@Serializable
data class PersistedState(
    val identity: Identity? = null,
    val peers: List<Peer> = emptyList(),
    val settings: Settings = Settings(),
    val history: List<HistoryItem> = emptyList(),
)

// ---- Wire DTOs (docs/PROTOCOL.md) ----

@Serializable
data class QrPayload(
    val v: Int = 1,
    val id: String,
    val name: String,
    val platform: String,
    val port: Int,
    val addrs: List<String>,
    val code: String,
)

@Serializable
data class PairRequest(
    val code: String,
    val id: String,
    val name: String,
    val platform: String,
    val port: Int,
    val addrs: List<String>,
)

@Serializable
data class PairResponse(
    val id: String,
    val name: String,
    val platform: String,
    val port: Int,
    val addrs: List<String>,
    val secret: String,
)

@Serializable
data class HelloRequest(
    val name: String,
    val port: Int,
    val addrs: List<String>,
)

@Serializable
data class HelloResponse(
    val id: String,
    val name: String,
    val platform: String,
    val port: Int,
    val addrs: List<String>,
    /** Absent or empty = the peer has no collections. Android defines none. */
    val collections: List<Collection> = emptyList(),
)

@Serializable
data class OfferFile(val name: String, val size: Long, val mime: String)

@Serializable
data class OfferRequest(
    val batch: String,
    val files: List<OfferFile>,
    /** Destination collection id; null = receiver's default (omitted on the wire). */
    val collection: String? = null,
)

@Serializable
data class OfferResponse(val accepted: Boolean)

@Serializable
data class FileResponse(val ok: Boolean, val name: String)

@Serializable
data class OkResponse(val ok: Boolean)

@Serializable
data class ErrorResponse(val error: String)

/** `POST /v1/text` (protocol § text). */
@Serializable
data class TextRequest(val id: String, val text: String)

const val TEXT_MIME = "text/x-pasala-snippet"
const val TEXT_MAX_BYTES = 1 shl 20
