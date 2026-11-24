import Foundation
import PasalaCore

// pasala-mcp: stdio MCP server over Pasala's local index (SQLite) and, for sending, the running app.

setvbuf(stdout, nil, _IOLBF, 0)
let db: PasalaDB
do { db = try PasalaDB() } catch {
    FileHandle.standardError.write(Data("pasala-mcp: \(error.localizedDescription)\n".utf8)); exit(1)
}
let iso = ISO8601DateFormatter()

func reply(_ id: Any, result: Any) { write(["jsonrpc": "2.0", "id": id, "result": result]) }
func replyError(_ id: Any, _ code: Int, _ msg: String) { write(["jsonrpc": "2.0", "id": id, "error": ["code": code, "message": msg]]) }
func write(_ obj: [String: Any]) {
    guard let d = try? JSONSerialization.data(withJSONObject: obj, options: [.withoutEscapingSlashes]) else { return }
    FileHandle.standardOutput.write(d); FileHandle.standardOutput.write(Data("\n".utf8))
}
func text(_ obj: Any) -> [String: Any] {
    let d = (try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .withoutEscapingSlashes, .sortedKeys])) ?? Data()
    return ["content": [["type": "text", "text": String(data: d, encoding: .utf8) ?? ""]]]
}
func fail(_ msg: String) -> [String: Any] { ["content": [["type": "text", "text": msg]], "isError": true] }

func describe(_ f: FileRecord) -> [String: Any] {
    ["id": f.id, "name": f.name, "path": f.path, "mime": f.mime, "size": f.size, "size_human": formatBytes(f.size),
     "direction": f.direction == "in" ? "received" : "sent", "device": f.peerName, "batch": f.batch,
     "at": iso.string(from: f.createdAt), "exists": FileManager.default.fileExists(atPath: f.path), "seen_by_agent": f.agentSeen,
     "collection": f.collection as Any]
}

func filters(_ a: [String: Any]) -> (String, [PasalaDB.Value]) {
    var w: [String] = [], args: [PasalaDB.Value] = []
    switch a["direction"] as? String ?? "received" {
    case "sent": w.append("direction='out'")
    case "all": break
    default: w.append("direction='in'")
    }
    if let s = a["since"] as? String {
        let d = iso.date(from: s) ?? ISO8601DateFormatter().date(from: s + "T00:00:00Z")
        if let d { w.append("created_at>=?"); args.append(.real(d.timeIntervalSince1970)) }
    }
    if let dev = a["device"] as? String, !dev.isEmpty { w.append("(peer_name LIKE ? OR peer_id=?)"); args += [.text("%\(dev)%"), .text(dev)] }
    if let q = a["query"] as? String, !q.isEmpty { w.append("name LIKE ?"); args.append(.text("%\(q)%")) }
    if let k = a["kind"] as? String {
        switch k {
        case "image", "video", "audio": w.append("mime LIKE ?"); args.append(.text("\(k)/%"))
        case "pdf": w.append("mime='application/pdf'")
        default: break
        }
    }
    if a["only_new"] as? Bool == true { w.append("agent_seen=0") }
    if let c = a["collection"] as? String, !c.isEmpty { w.append("(collection LIKE ? OR collection_id=?)"); args += [.text("%\(c)%"), .text(c)] }
    return (w.isEmpty ? "1=1" : w.joined(separator: " AND "), args)
}

let commonProps: [String: Any] = [
    "direction": ["type": "string", "enum": ["received", "sent", "all"], "description": "Default: received"],
    "since": ["type": "string", "description": "ISO 8601 date or datetime; only files at or after it"],
    "device": ["type": "string", "description": "Device name (partial match) or id"],
    "kind": ["type": "string", "enum": ["image", "video", "audio", "pdf"]],
    "collection": ["type": "string", "description": "Collection name (partial match) or id the file was received into"],
    "limit": ["type": "integer", "description": "Default 50, max 500"],
]

let tools: [[String: Any]] = [
    ["name": "pasala_new_files",
     "description": "Files received by Pasala on this Mac that this agent has not seen yet (newest first). Marks them as seen unless mark_seen=false. Use it to answer 'did anything new arrive?'.",
     "inputSchema": ["type": "object", "properties": ["mark_seen": ["type": "boolean", "description": "Default true"],
                                                       "limit": ["type": "integer"]]]],
    ["name": "pasala_list_files",
     "description": "List files in the Pasala index (received from or sent to paired phones/computers) with path, type, size, device and date.",
     "inputSchema": ["type": "object", "properties": commonProps.merging(["only_new": ["type": "boolean"]]) { a, _ in a }]],
    ["name": "pasala_search",
     "description": "Search the Pasala index by file name.",
     "inputSchema": ["type": "object", "properties": commonProps.merging(["query": ["type": "string"]]) { a, _ in a }, "required": ["query"]]],
    ["name": "pasala_get_file",
     "description": "Metadata of one file by id. With include_content=true returns the image itself (images up to 8 MB) or the text (text files up to 256 KB) so you can look at it.",
     "inputSchema": ["type": "object", "properties": ["id": ["type": "integer"], "include_content": ["type": "boolean"]], "required": ["id"]]],
    ["name": "pasala_mark_seen",
     "description": "Mark files as seen by the agent so pasala_new_files stops returning them.",
     "inputSchema": ["type": "object", "properties": ["ids": ["type": "array", "items": ["type": "integer"]]], "required": ["ids"]]],
    ["name": "pasala_devices",
     "description": "Paired devices, whether each is reachable now, this Mac's inbox folder, its collections (named folders senders can pick) and stats.",
     "inputSchema": ["type": "object", "properties": [:]]],
    ["name": "pasala_pair",
     "description": "Pair this Mac with another device using its pairing link (pasala://pair?d=...), the same text its QR shows.",
     "inputSchema": ["type": "object", "properties": ["link": ["type": "string"]], "required": ["link"]]],
    ["name": "pasala_send_text",
     "description": "Send a piece of text to a paired device; it shows there as 'text ready to copy' (the device's clipboard is not touched). Received texts appear in the index with mime text/x-pasala-snippet; use pasala_get_file with include_content to read one.",
     "inputSchema": ["type": "object", "properties": ["text": ["type": "string"], "device": ["type": "string"]], "required": ["text", "device"]]],
    ["name": "pasala_send",
     "description": "Send local files from this Mac to a paired device (needs the Pasala app running). The device can be a name or id.",
     "inputSchema": ["type": "object", "properties": ["paths": ["type": "array", "items": ["type": "string"]], "device": ["type": "string"],
                                                       "collection": ["type": "string", "description": "Optional id of one of the device's collections"]],
                     "required": ["paths", "device"]]],
]

func localCall(_ path: String, body: [String: Any]? = nil, timeout: TimeInterval = 10) -> (Int, [String: Any])? {
    guard let token = try? String(contentsOf: PasalaPaths.localTokenURL, encoding: .utf8),
          let port = db.setting("port") else { return nil }
    var r = URLRequest(url: URL(string: "http://127.0.0.1:\(port)\(path)")!, timeoutInterval: timeout)
    r.httpMethod = body == nil ? "GET" : "POST"
    r.setValue(token, forHTTPHeaderField: "X-Pasala-Local")
    if let body { r.httpBody = try? JSONSerialization.data(withJSONObject: body); r.setValue("application/json", forHTTPHeaderField: "Content-Type") }
    let sem = DispatchSemaphore(value: 0)
    var out: (Int, [String: Any])?
    URLSession.shared.dataTask(with: r) { d, resp, _ in
        if let http = resp as? HTTPURLResponse {
            out = (http.statusCode, d.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:])
        }
        sem.signal()
    }.resume()
    _ = sem.wait(timeout: .now() + timeout + 1)
    return out
}

func call(_ name: String, _ a: [String: Any]) -> [String: Any] {
    let limit = min(max(a["limit"] as? Int ?? 50, 1), 500)
    switch name {
    case "pasala_new_files":
        let files = db.files(where: "direction='in' AND agent_seen=0", limit: limit)
        if a["mark_seen"] as? Bool ?? true { db.markAgentSeen(files.map(\.id)) }
        return text(["count": files.count, "files": files.map(describe)])
    case "pasala_list_files", "pasala_search":
        let (w, args) = filters(a)
        let files = db.files(where: w, args, limit: limit)
        return text(["count": files.count, "files": files.map(describe)])
    case "pasala_get_file":
        guard let id = (a["id"] as? NSNumber)?.int64Value, let f = db.files(where: "id=?", [.int(id)], limit: 1).first else { return fail("No file with that id.") }
        var content: [[String: Any]] = [["type": "text", "text": String(data: (try? JSONSerialization.data(withJSONObject: describe(f), options: [.prettyPrinted, .withoutEscapingSlashes])) ?? Data(), encoding: .utf8) ?? ""]]
        if a["include_content"] as? Bool == true, FileManager.default.fileExists(atPath: f.path) {
            if f.mime.hasPrefix("image/"), f.size <= 8 << 20, let d = try? Data(contentsOf: f.url),
               ["image/png", "image/jpeg", "image/gif", "image/webp"].contains(f.mime) {
                content.append(["type": "image", "data": d.base64EncodedString(), "mimeType": f.mime])
            } else if (f.mime.hasPrefix("text/") || f.mime == "application/json"), f.size <= 1 << 20,
                      let s = try? String(contentsOf: f.url, encoding: .utf8) {
                content.append(["type": "text", "text": s])
            } else {
                content.append(["type": "text", "text": "Content not inlined (type or size). Read it from the path."])
            }
        }
        db.markAgentSeen([f.id])
        return ["content": content]
    case "pasala_mark_seen":
        let ids = (a["ids"] as? [NSNumber] ?? []).map(\.int64Value)
        db.markAgentSeen(ids)
        return text(["ok": true, "marked": ids.count])
    case "pasala_devices":
        let peers = db.peers()
        let live = localCall("/local/status", timeout: 4)
        let onlineIds = Set(((live?.1["devices"] as? [[String: Any]]) ?? []).filter { $0["online"] as? Bool == true }.compactMap { $0["id"] as? String })
        let counts = db.query("SELECT peer_id, direction, COUNT(*) n FROM files GROUP BY peer_id, direction")
        return text([
            "app_running": live != nil,
            "inbox": (live?.1["inbox"] as? String) ?? db.setting("inbox") ?? PasalaPaths.defaultInbox.path,
            "this_mac": db.setting("device_name") ?? Host.current().localizedName ?? "Mac",
            "collections": db.collections().map { ["id": $0.id, "name": $0.name, "folder": $0.path] },
            "devices": peers.map { p in
                ["id": p.id, "name": p.name, "platform": p.platform, "online": onlineIds.contains(p.id),
                 "last_seen": p.lastSeen.map { iso.string(from: $0) } as Any, "folder": p.folder as Any,
                 "received": counts.first { $0["peer_id"] as? String == p.id && $0["direction"] as? String == "in" }?["n"] ?? 0,
                 "sent": counts.first { $0["peer_id"] as? String == p.id && $0["direction"] as? String == "out" }?["n"] ?? 0] as [String: Any]
            }])
    case "pasala_pair":
        guard let link = a["link"] as? String else { return fail("link is required.") }
        guard let (status, body) = localCall("/local/pair", body: ["link": link], timeout: 30) else {
            return fail("The Pasala app is not running on this Mac. Open it and try again.")
        }
        return status == 200 ? text(body) : fail("\(body["error"] ?? "Pairing failed")")
    case "pasala_send_text":
        guard let t = a["text"] as? String, !t.isEmpty, let dev = a["device"] as? String else { return fail("text and device are required.") }
        guard let (status, body) = localCall("/local/text", body: ["text": t, "device": dev], timeout: 30) else {
            return fail("The Pasala app is not running on this Mac. Open it and try again.")
        }
        return status == 200 ? text(body) : fail("Send failed (\(status)): \(body)")
    case "pasala_send":
        guard let paths = a["paths"] as? [String], !paths.isEmpty, let dev = a["device"] as? String else { return fail("paths and device are required.") }
        var req: [String: Any] = ["paths": paths, "device": dev]
        if let c = a["collection"] as? String { req["collection"] = c }
        guard let (status, body) = localCall("/local/send", body: req, timeout: 60 * 30) else {
            return fail("The Pasala app is not running on this Mac. Open it and try again.")
        }
        return status == 200 ? text(body) : fail("Send failed (\(status)): \(body)")
    default:
        return fail("Unknown tool \(name)")
    }
}

while let line = readLine(strippingNewline: true) {
    guard let data = line.data(using: .utf8), let msg = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
    let method = msg["method"] as? String ?? ""
    guard let id = msg["id"] else { continue }   // notifications
    let params = msg["params"] as? [String: Any] ?? [:]
    switch method {
    case "initialize":
        reply(id, result: ["protocolVersion": params["protocolVersion"] as? String ?? "2025-06-18",
                           "capabilities": ["tools": [:]],
                           "serverInfo": ["name": "pasala", "version": "0.1.0"],
                           "instructions": "Pasala moves files between the user's phones and this Mac. Use pasala_new_files to see what just arrived, pasala_get_file to look at one, pasala_send to push files to a phone."])
    case "tools/list": reply(id, result: ["tools": tools])
    case "tools/call":
        reply(id, result: call(params["name"] as? String ?? "", params["arguments"] as? [String: Any] ?? [:]))
    case "ping": reply(id, result: [:])
    default: replyError(id, -32601, "Method not found")
    }
}
