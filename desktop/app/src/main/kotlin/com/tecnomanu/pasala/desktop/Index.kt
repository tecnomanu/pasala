package com.tecnomanu.pasala.desktop

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** A file sent or received, as the index (and the MCP) sees it. */
data class FileRecord(
    val id: Long,
    val batch: String,
    val direction: String, // "in" | "out"
    val peerId: String,
    val peerName: String,
    val name: String,
    val path: String,
    val mime: String,
    val size: Long,
    val createdAt: Long, // epoch ms
    val agentSeen: Boolean,
    val collectionId: String?,
    val collection: String?,
) {
    val file get() = File(path)
}

/** A named destination on this computer (a folder) that senders can pick. */
data class FileCollection(val id: String, val name: String, val path: String)

/**
 * SQLite index with the same schema as the Mac's `pasala.db` (apple/Sources/PasalaCore/Database.swift),
 * so `--mcp` answers the same way. The app writes; the MCP mostly reads (WAL).
 */
class Index(file: File = Paths.databaseFile, readOnly: Boolean = false) {
    private val conn: Connection = DriverManager.getConnection(
        "jdbc:sqlite:${file.path}" + if (readOnly) "?open_mode=1" else "",
    )

    init {
        conn.createStatement().use { it.execute("PRAGMA busy_timeout=3000") }
        if (!readOnly) migrate()
    }

    fun close() = runCatching { conn.close() }

    private fun migrate() = synchronized(this) {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            st.execute(
                """CREATE TABLE IF NOT EXISTS files(
                  id INTEGER PRIMARY KEY AUTOINCREMENT, batch TEXT NOT NULL, direction TEXT NOT NULL,
                  peer_id TEXT NOT NULL, peer_name TEXT NOT NULL, name TEXT NOT NULL, path TEXT NOT NULL,
                  mime TEXT NOT NULL, size INTEGER NOT NULL, created_at REAL NOT NULL, agent_seen INTEGER NOT NULL DEFAULT 0,
                  collection_id TEXT, collection TEXT)""",
            )
            st.execute("CREATE INDEX IF NOT EXISTS files_created ON files(created_at)")
            st.execute("CREATE TABLE IF NOT EXISTS collections(id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL, created_at REAL NOT NULL)")
        }
    }

    private fun bindAll(sql: String, args: List<Any?>) = conn.prepareStatement(sql).apply {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> setObject(i + 1, null)
                is String -> setString(i + 1, a)
                is Int -> setLong(i + 1, a.toLong())
                is Long -> setLong(i + 1, a)
                is Double -> setDouble(i + 1, a)
                is Boolean -> setLong(i + 1, if (a) 1 else 0)
                else -> setString(i + 1, a.toString())
            }
        }
    }

    fun run(sql: String, args: List<Any?> = emptyList()): Long = synchronized(this) {
        bindAll(sql, args).use { it.executeUpdate() }
        conn.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { if (it.next()) it.getLong(1) else -1 } }
    }

    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>> = synchronized(this) {
        bindAll(sql, args).use { ps ->
            ps.executeQuery().use { rs ->
                val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                buildList { while (rs.next()) add(cols.associateWith { rs.getObject(it) }) }
            }
        }
    }

    // settings

    fun setting(key: String): String? = query("SELECT value FROM settings WHERE key=?", listOf(key)).firstOrNull()?.get("value") as? String
    fun setSetting(key: String, value: String) {
        run("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", listOf(key, value))
    }

    // files

    fun addFile(
        batch: String, direction: String, peerId: String, peerName: String, name: String, path: String,
        mime: String, size: Long, at: Long = System.currentTimeMillis(), collectionId: String? = null, collection: String? = null,
    ): Long = run(
        "INSERT INTO files(batch,direction,peer_id,peer_name,name,path,mime,size,created_at,collection_id,collection) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        listOf(batch, direction, peerId, peerName, name, path, mime, size, at / 1000.0, collectionId, collection),
    )

    fun files(where: String = "1=1", args: List<Any?> = emptyList(), limit: Int = 200): List<FileRecord> =
        synchronized(this) {
            bindAll("SELECT * FROM files WHERE $where ORDER BY created_at DESC LIMIT $limit", args).use { ps ->
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(file(rs)) } }
            }
        }

    fun markAgentSeen(ids: List<Long>) {
        if (ids.isEmpty()) return
        run("UPDATE files SET agent_seen=1 WHERE id IN (${ids.joinToString(",")})")
    }

    private fun file(r: ResultSet) = FileRecord(
        id = r.getLong("id"), batch = r.getString("batch"), direction = r.getString("direction"),
        peerId = r.getString("peer_id"), peerName = r.getString("peer_name"), name = r.getString("name"),
        path = r.getString("path"), mime = r.getString("mime"), size = r.getLong("size"),
        createdAt = (r.getDouble("created_at") * 1000).toLong(), agentSeen = r.getLong("agent_seen") != 0L,
        collectionId = r.getString("collection_id"), collection = r.getString("collection"),
    )

    // collections

    fun collections(): List<FileCollection> = query("SELECT * FROM collections ORDER BY created_at").map {
        FileCollection(it["id"] as String, it["name"] as String, it["path"] as String)
    }

    fun saveCollection(c: FileCollection) {
        run(
            "INSERT INTO collections(id,name,path,created_at) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name, path=excluded.path",
            listOf(c.id, c.name, c.path, System.currentTimeMillis() / 1000.0),
        )
    }

    fun deleteCollection(id: String) { run("DELETE FROM collections WHERE id=?", listOf(id)) }
}
