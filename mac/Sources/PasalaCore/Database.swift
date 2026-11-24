import Foundation
import SQLite3

public enum PasalaPaths {
    public static var supportDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("Pasala", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
    public static var databaseURL: URL { supportDir.appendingPathComponent("pasala.db") }
    public static var localTokenURL: URL { supportDir.appendingPathComponent("local-token") }
    public static var defaultInbox: URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Pasala", isDirectory: true)
    }
}

public struct Peer: Identifiable, Hashable, Codable {
    public var id: String
    public var name: String
    public var platform: String
    public var port: Int
    public var addrs: [String]
    public var secret: String
    public var pairedAt: Date
    public var lastSeen: Date?
    public var lastAddr: String?
    public var folder: String?

    public init(id: String, name: String, platform: String, port: Int, addrs: [String], secret: String,
                pairedAt: Date = Date(), lastSeen: Date? = nil, lastAddr: String? = nil, folder: String? = nil) {
        self.id = id; self.name = name; self.platform = platform; self.port = port; self.addrs = addrs
        self.secret = secret; self.pairedAt = pairedAt; self.lastSeen = lastSeen; self.lastAddr = lastAddr; self.folder = folder
    }
}

public struct FileRecord: Identifiable, Hashable, Codable {
    public var id: Int64
    public var batch: String
    public var direction: String   // "in" | "out"
    public var peerId: String
    public var peerName: String
    public var name: String
    public var path: String
    public var mime: String
    public var size: Int64
    public var createdAt: Date
    public var agentSeen: Bool
    public var collectionId: String? = nil
    public var collection: String? = nil

    public var url: URL { URL(fileURLWithPath: path) }
}

/// A named destination on this device (a folder) that senders can pick.
public struct FileCollection: Identifiable, Hashable, Codable {
    public var id: String
    public var name: String
    public var path: String
    public var url: URL { URL(fileURLWithPath: path) }
    public init(id: String, name: String, path: String) { self.id = id; self.name = name; self.path = path }
}

/// Thin SQLite wrapper. One connection per process; the app writes, the MCP mostly reads.
public final class PasalaDB {
    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "pasala.db")

    public init(url: URL = PasalaPaths.databaseURL, readOnly: Bool = false) throws {
        let flags = readOnly ? SQLITE_OPEN_READONLY : (SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE)
        guard sqlite3_open_v2(url.path, &db, flags | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            throw NSError(domain: "PasalaDB", code: 1, userInfo: [NSLocalizedDescriptionKey: "No se pudo abrir la base \(url.path)"])
        }
        sqlite3_busy_timeout(db, 3000)
        if !readOnly { try migrate() }
    }

    deinit { sqlite3_close(db) }

    private func migrate() throws {
        try exec("PRAGMA journal_mode=WAL;")
        try exec("""
        CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS peers(
          id TEXT PRIMARY KEY, name TEXT NOT NULL, platform TEXT NOT NULL, port INTEGER NOT NULL,
          addrs TEXT NOT NULL, secret TEXT NOT NULL, paired_at REAL NOT NULL, last_seen REAL,
          last_addr TEXT, folder TEXT);
        CREATE TABLE IF NOT EXISTS files(
          id INTEGER PRIMARY KEY AUTOINCREMENT, batch TEXT NOT NULL, direction TEXT NOT NULL,
          peer_id TEXT NOT NULL, peer_name TEXT NOT NULL, name TEXT NOT NULL, path TEXT NOT NULL,
          mime TEXT NOT NULL, size INTEGER NOT NULL, created_at REAL NOT NULL, agent_seen INTEGER NOT NULL DEFAULT 0);
        CREATE INDEX IF NOT EXISTS files_created ON files(created_at);
        CREATE TABLE IF NOT EXISTS collections(id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL, created_at REAL NOT NULL);
        """)
        let cols = Set(query("PRAGMA table_info(files)").compactMap { $0["name"] as? String })
        if !cols.contains("collection_id") { try exec("ALTER TABLE files ADD COLUMN collection_id TEXT;") }
        if !cols.contains("collection") { try exec("ALTER TABLE files ADD COLUMN collection TEXT;") }
    }

    // MARK: low level

    public func exec(_ sql: String) throws {
        try queue.sync {
            var err: UnsafeMutablePointer<CChar>?
            if sqlite3_exec(db, sql, nil, nil, &err) != SQLITE_OK {
                let msg = err.map { String(cString: $0) } ?? "?"
                sqlite3_free(err)
                throw NSError(domain: "PasalaDB", code: 2, userInfo: [NSLocalizedDescriptionKey: msg])
            }
        }
    }

    public enum Value { case text(String?), int(Int64?), real(Double?) }

    @discardableResult
    public func run(_ sql: String, _ args: [Value] = []) -> Int64 {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return -1 }
            defer { sqlite3_finalize(stmt) }
            bind(stmt, args)
            sqlite3_step(stmt)
            return sqlite3_last_insert_rowid(db)
        }
    }

    public func query(_ sql: String, _ args: [Value] = []) -> [[String: Any]] {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            bind(stmt, args)
            var rows: [[String: Any]] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                var row: [String: Any] = [:]
                for i in 0..<sqlite3_column_count(stmt) {
                    let name = String(cString: sqlite3_column_name(stmt, i))
                    switch sqlite3_column_type(stmt, i) {
                    case SQLITE_INTEGER: row[name] = sqlite3_column_int64(stmt, i)
                    case SQLITE_FLOAT: row[name] = sqlite3_column_double(stmt, i)
                    case SQLITE_TEXT: row[name] = String(cString: sqlite3_column_text(stmt, i))
                    default: break
                    }
                }
                rows.append(row)
            }
            return rows
        }
    }

    private func bind(_ stmt: OpaquePointer?, _ args: [Value]) {
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (i, a) in args.enumerated() {
            let idx = Int32(i + 1)
            switch a {
            case .text(let s): if let s { sqlite3_bind_text(stmt, idx, s, -1, transient) } else { sqlite3_bind_null(stmt, idx) }
            case .int(let n): if let n { sqlite3_bind_int64(stmt, idx, n) } else { sqlite3_bind_null(stmt, idx) }
            case .real(let d): if let d { sqlite3_bind_double(stmt, idx, d) } else { sqlite3_bind_null(stmt, idx) }
            }
        }
    }

    // MARK: settings

    public func setting(_ key: String) -> String? {
        query("SELECT value FROM settings WHERE key=?", [.text(key)]).first?["value"] as? String
    }
    public func setSetting(_ key: String, _ value: String) {
        run("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", [.text(key), .text(value)])
    }

    // MARK: peers

    public func peers() -> [Peer] {
        query("SELECT * FROM peers ORDER BY paired_at").map(Self.peer)
    }

    public func savePeer(_ p: Peer) {
        let addrs = (try? String(data: JSONEncoder().encode(p.addrs), encoding: .utf8)) ?? "[]"
        run("""
        INSERT INTO peers(id,name,platform,port,addrs,secret,paired_at,last_seen,last_addr,folder) VALUES(?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET name=excluded.name, platform=excluded.platform, port=excluded.port, addrs=excluded.addrs,
          secret=excluded.secret, paired_at=excluded.paired_at, last_seen=excluded.last_seen, last_addr=excluded.last_addr, folder=excluded.folder
        """, [.text(p.id), .text(p.name), .text(p.platform), .int(Int64(p.port)), .text(addrs), .text(p.secret),
              .real(p.pairedAt.timeIntervalSince1970), .real(p.lastSeen?.timeIntervalSince1970), .text(p.lastAddr), .text(p.folder)])
    }

    public func deletePeer(_ id: String) { run("DELETE FROM peers WHERE id=?", [.text(id)]) }

    static func peer(_ r: [String: Any]) -> Peer {
        let addrs = (r["addrs"] as? String).flatMap { try? JSONDecoder().decode([String].self, from: Data($0.utf8)) } ?? []
        return Peer(id: r["id"] as? String ?? "", name: r["name"] as? String ?? "", platform: r["platform"] as? String ?? "",
                    port: Int(r["port"] as? Int64 ?? 47820), addrs: addrs, secret: r["secret"] as? String ?? "",
                    pairedAt: Date(timeIntervalSince1970: r["paired_at"] as? Double ?? 0),
                    lastSeen: (r["last_seen"] as? Double).map(Date.init(timeIntervalSince1970:)),
                    lastAddr: r["last_addr"] as? String, folder: r["folder"] as? String)
    }

    // MARK: files

    @discardableResult
    public func addFile(batch: String, direction: String, peerId: String, peerName: String, name: String,
                        path: String, mime: String, size: Int64, at: Date = Date(),
                        collectionId: String? = nil, collection: String? = nil) -> Int64 {
        run("INSERT INTO files(batch,direction,peer_id,peer_name,name,path,mime,size,created_at,collection_id,collection) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            [.text(batch), .text(direction), .text(peerId), .text(peerName), .text(name), .text(path), .text(mime),
             .int(size), .real(at.timeIntervalSince1970), .text(collectionId), .text(collection)])
    }

    // MARK: collections

    public func collections() -> [FileCollection] {
        query("SELECT * FROM collections ORDER BY created_at").map {
            FileCollection(id: $0["id"] as? String ?? "", name: $0["name"] as? String ?? "", path: $0["path"] as? String ?? "")
        }
    }
    public func saveCollection(_ c: FileCollection) {
        run("INSERT INTO collections(id,name,path,created_at) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name, path=excluded.path",
            [.text(c.id), .text(c.name), .text(c.path), .real(Date().timeIntervalSince1970)])
    }
    public func deleteCollection(_ id: String) { run("DELETE FROM collections WHERE id=?", [.text(id)]) }

    public func files(where clause: String = "1=1", _ args: [Value] = [], limit: Int = 200) -> [FileRecord] {
        query("SELECT * FROM files WHERE \(clause) ORDER BY created_at DESC LIMIT \(limit)", args).map(Self.file)
    }

    public func markAgentSeen(_ ids: [Int64]) {
        guard !ids.isEmpty else { return }
        run("UPDATE files SET agent_seen=1 WHERE id IN (\(ids.map(String.init).joined(separator: ",")))")
    }

    static func file(_ r: [String: Any]) -> FileRecord {
        FileRecord(id: r["id"] as? Int64 ?? 0, batch: r["batch"] as? String ?? "", direction: r["direction"] as? String ?? "in",
                   peerId: r["peer_id"] as? String ?? "", peerName: r["peer_name"] as? String ?? "",
                   name: r["name"] as? String ?? "", path: r["path"] as? String ?? "", mime: r["mime"] as? String ?? "",
                   size: r["size"] as? Int64 ?? 0, createdAt: Date(timeIntervalSince1970: r["created_at"] as? Double ?? 0),
                   agentSeen: (r["agent_seen"] as? Int64 ?? 0) != 0,
                   collectionId: r["collection_id"] as? String, collection: r["collection"] as? String)
    }
}

public enum Rand {
    public static func base64url(bytes n: Int) -> String {
        var b = [UInt8](repeating: 0, count: n)
        _ = SecRandomCopyBytes(kSecRandomDefault, n, &b)
        return Data(b).base64URL
    }
}

public extension Data {
    var base64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
    init?(base64URL s: String) {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        self.init(base64Encoded: t)
    }
}

/// Compares secrets without leaking timing.
public func constantTimeEquals(_ a: String, _ b: String) -> Bool {
    let x = Array(a.utf8), y = Array(b.utf8)
    guard x.count == y.count else { return false }
    var diff: UInt8 = 0
    for i in 0..<x.count { diff |= x[i] ^ y[i] }
    return diff == 0
}
