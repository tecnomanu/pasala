import Foundation
import Network
import AppKit
import PasalaCore

struct TransferItem: Identifiable, Hashable {
    let id = UUID()
    var name: String
    var size: Int64
    var mime: String
    var done: Int64 = 0
    var finished = false
    var failed = false
    var url: URL?
    var progress: Double { size > 0 ? min(1, Double(done) / Double(size)) : (finished ? 1 : 0) }
}

struct Transfer: Identifiable {
    enum Direction { case incoming, outgoing }
    enum State { case running, done, failed(String) }
    let id: String            // batch
    let direction: Direction
    let peerId: String
    var peerName: String
    var items: [TransferItem]
    var collection: String? = nil
    var state: State = .running
    var finishedAt: Date?
    var totalBytes: Int64 { items.reduce(0) { $0 + $1.size } }
    var doneBytes: Int64 { items.reduce(0) { $0 + $1.done } }
    var progress: Double { totalBytes > 0 ? Double(doneBytes) / Double(totalBytes) : 0 }
    var isRunning: Bool { if case .running = state { return true }; return false }
}

struct PendingOffer: Identifiable {
    let id: String
    let peerName: String
    let platform: String
    let files: [(name: String, size: Int64, mime: String)]
    var totalBytes: Int64 { files.reduce(0) { $0 + $1.size } }
}

@MainActor
final class Engine: ObservableObject {
    static let shared = Engine()

    let db: PasalaDB
    let server = HTTPServer()
    let deviceId: String
    @Published var deviceName: String
    @Published var port: Int = 47820
    @Published var addrs: [String] = []

    @Published var peers: [Peer] = []
    @Published var online: Set<String> = []
    @Published var recent: [FileRecord] = []
    @Published var transfers: [Transfer] = []
    @Published var offers: [PendingOffer] = []
    @Published var pairCode: String = ""
    @Published var pairExpires: Date = .now
    @Published var justPaired: Peer?
    @Published var inbox: URL
    @Published var askBeforeReceiving: Bool
    @Published var lastError: String?
    @Published var collections: [FileCollection] = []
    /// Collections each paired device offers, from its latest hello reply.
    @Published var peerCollections: [String: [RemoteCollection]] = [:]

    private var codes: [String: Date] = [:]
    private var acceptedBatches: [String: String] = [:]   // batch -> peerId
    private var batchCollection: [String: String] = [:]   // batch -> collection id
    private var offerWaiters: [String: CheckedContinuation<Bool, Never>] = [:]
    private var discovered: [String: String] = [:]          // peerId -> "ip"
    private var browser: NWBrowser?
    let localToken: String
    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.waitsForConnectivity = false
        c.timeoutIntervalForRequest = 120
        c.timeoutIntervalForResource = 60 * 60 * 6
        return URLSession(configuration: c)
    }()

    private init() {
        db = try! PasalaDB()
        if let id = db.setting("device_id") { deviceId = id } else {
            deviceId = UUID().uuidString.lowercased(); db.setSetting("device_id", deviceId)
        }
        deviceName = db.setting("device_name") ?? (Host.current().localizedName ?? "Mac")
        inbox = db.setting("inbox").map { URL(fileURLWithPath: $0) } ?? PasalaPaths.defaultInbox
        askBeforeReceiving = db.setting("ask") == "1"
        if let t = try? String(contentsOf: PasalaPaths.localTokenURL, encoding: .utf8), !t.isEmpty { localToken = t } else {
            localToken = Rand.base64url(bytes: 24)
            try? localToken.write(to: PasalaPaths.localTokenURL, atomically: true, encoding: .utf8)
            chmod(PasalaPaths.localTokenURL.path, 0o600)
        }
        peers = db.peers()
        collections = db.collections()
        reloadRecent()
    }

    // MARK: lifecycle

    func start() {
        try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        server.handler = { [weak self] req, body in
            guard let self else { return .error("gone", 500) }
            return await self.handle(req, body)
        }
        do {
            try server.start(preferred: 47820, serviceName: deviceName, txt: ["id": deviceId])
            port = Int(server.port)
        } catch {
            lastError = "No pude abrir el puerto para recibir: \(error.localizedDescription)"
        }
        addrs = NetInfo.addresses()
        db.setSetting("port", String(port))
        newPairCode()
        startBrowsing()
        Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { _ in Task { @MainActor in self.tick() } }
        Task { await refreshOnline() }
    }

    private func tick() {
        addrs = NetInfo.addresses()
        if pairExpires < .now { newPairCode() }
        transfers.removeAll { !$0.isRunning && ($0.finishedAt ?? .now) < .now.addingTimeInterval(-30) }
        Task { await refreshOnline() }
    }

    func reloadRecent() { recent = db.files(limit: 300) }

    // MARK: settings

    func setDeviceName(_ n: String) {
        let t = n.trimmingCharacters(in: .whitespaces); guard !t.isEmpty else { return }
        deviceName = t; db.setSetting("device_name", t)
        Task { await refreshOnline() }
    }
    func setInbox(_ u: URL) { inbox = u; db.setSetting("inbox", u.path) }
    func setAsk(_ v: Bool) { askBeforeReceiving = v; db.setSetting("ask", v ? "1" : "0") }
    func setFolder(_ u: URL?, for peer: Peer) {
        var p = peer; p.folder = u?.path; db.savePeer(p); peers = db.peers()
    }
    func rename(_ peer: Peer, to name: String) {
        var p = peer; p.name = name; db.savePeer(p); peers = db.peers()
    }

    // MARK: collections

    @discardableResult
    func addCollection(folder: URL, name: String? = nil) -> FileCollection {
        if let existing = collections.first(where: { $0.path == folder.path }) { return existing }
        let c = FileCollection(id: "c-" + Rand.base64url(bytes: 6), name: (name?.isEmpty == false ? name! : folder.lastPathComponent), path: folder.path)
        db.saveCollection(c); collections = db.collections()
        return c
    }
    func renameCollection(_ c: FileCollection, to name: String) {
        let t = name.trimmingCharacters(in: .whitespaces); guard !t.isEmpty else { return }
        var n = c; n.name = t; db.saveCollection(n); collections = db.collections()
    }
    func moveCollection(_ c: FileCollection, to folder: URL) {
        var n = c; n.path = folder.path; db.saveCollection(n); collections = db.collections()
    }
    func removeCollection(_ c: FileCollection) { db.deleteCollection(c.id); collections = db.collections() }

    // MARK: pairing

    func newPairCode() {
        let c = Rand.base64url(bytes: 16)
        codes = codes.filter { $0.value > .now }
        codes[c] = .now.addingTimeInterval(300)
        pairCode = c
        pairExpires = .now.addingTimeInterval(300)
        if ProcessInfo.processInfo.environment["PASALA_DEBUG"] != nil {
            try? pairPayload.write(to: PasalaPaths.supportDir.appendingPathComponent("debug-pair.txt"), atomically: true, encoding: .utf8)
        }
    }

    var pairPayload: String {
        let obj: [String: Any] = ["v": 1, "id": deviceId, "name": deviceName, "platform": "mac",
                                  "port": port, "addrs": addrs, "code": pairCode]
        let data = (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data()
        return "pasala://pair?d=" + data.base64URL
    }

    /// Pair by pasting a code from another device (Mac ↔ Mac, or no camera at hand).
    func pair(withPayload text: String) async -> String? {
        guard let comps = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              let d = comps.queryItems?.first(where: { $0.name == "d" })?.value,
              let data = Data(base64URL: d),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let code = obj["code"] as? String, let port = obj["port"] as? Int,
              let hostAddrs = obj["addrs"] as? [String] else { return "Ese código no es de Pasala." }
        let mine: [String: Any] = ["code": code, "id": deviceId, "name": deviceName, "platform": "mac", "port": self.port, "addrs": addrs]
        for host in hostAddrs {
            var req = URLRequest(url: URL(string: "http://\(host):\(port)/v1/pair")!, timeoutInterval: 3)
            req.httpMethod = "POST"; req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: mine)
            guard let (data, resp) = try? await session.data(for: req), let http = resp as? HTTPURLResponse else { continue }
            if http.statusCode == 403 { return "El código venció. Generá uno nuevo en el otro dispositivo." }
            guard http.statusCode == 200, let r = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let id = r["id"] as? String, let secret = r["secret"] as? String else { continue }
            let p = Peer(id: id, name: r["name"] as? String ?? "Dispositivo", platform: r["platform"] as? String ?? "?",
                         port: r["port"] as? Int ?? port, addrs: r["addrs"] as? [String] ?? hostAddrs, secret: secret,
                         lastSeen: .now, lastAddr: host)
            db.savePeer(p); peers = db.peers(); online.insert(id); justPaired = p
            return nil
        }
        return "No encontré el otro dispositivo. ¿Está en la misma red o en Tailscale?"
    }

    func unpair(_ peer: Peer) {
        Task {
            if let host = await resolve(peer) {
                var req = authed(peer, host: host, path: "/v1/unpair"); req.timeoutInterval = 3
                _ = try? await session.data(for: req)
            }
        }
        db.deletePeer(peer.id); peers = db.peers(); online.remove(peer.id)
    }

    // MARK: server

    private nonisolated func handle(_ req: HTTPServer.Request, _ body: HTTPServer.Body) async -> HTTPServer.Response {
        let path = req.path.components(separatedBy: "?")[0]
        if req.method != "POST" && path != "/local/status" { return .error("method", 404) }
        if path.hasPrefix("/local/") { return await handleLocal(path, req, body) }
        if path == "/v1/pair" { return await handlePair(body) }
        guard let peer = await authorize(req) else { return .error("unauthorized", 401) }
        switch path {
        case "/v1/hello":
            let obj = await body.readAll().flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:]
            return await MainActor.run {
                var p = peer
                if let n = obj["name"] as? String, !n.isEmpty { p.name = n }
                if let port = obj["port"] as? Int { p.port = port }
                if let a = obj["addrs"] as? [String], !a.isEmpty { p.addrs = a }
                p.lastSeen = .now; p.lastAddr = req.remoteHost.isEmpty ? p.lastAddr : req.remoteHost
                self.db.savePeer(p); self.peers = self.db.peers(); self.online.insert(p.id)
                return .json(self.selfInfo())
            }
        case "/v1/offer": return await handleOffer(peer, body)
        case "/v1/files": return await handleFile(peer, req, body)
        case "/v1/unpair":
            await MainActor.run { self.db.deletePeer(peer.id); self.peers = self.db.peers(); self.online.remove(peer.id) }
            return .json(["ok": true])
        default: return .error("not_found", 404)
        }
    }

    private func selfInfo() -> [String: Any] {
        ["id": deviceId, "name": deviceName, "platform": "mac", "port": port, "addrs": addrs,
         "collections": collections.map { ["id": $0.id, "name": $0.name] }]
    }

    private nonisolated func authorize(_ req: HTTPServer.Request) async -> Peer? {
        guard let from = req.header("X-Pasala-From"), let auth = req.header("Authorization"), auth.hasPrefix("Bearer ") else { return nil }
        let secret = String(auth.dropFirst(7))
        return await MainActor.run {
            guard let p = self.peers.first(where: { $0.id == from }), constantTimeEquals(p.secret, secret) else { return nil }
            self.online.insert(p.id)
            return p
        }
    }

    private nonisolated func handlePair(_ body: HTTPServer.Body) async -> HTTPServer.Response {
        guard let data = await body.readAll(),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let code = o["code"] as? String, let id = o["id"] as? String else { return .error("bad_request", 400) }
        return await MainActor.run {
            guard let exp = self.codes[code], exp > .now else { return .error("bad_code", 403) }
            self.codes[code] = nil
            if code == self.pairCode { self.newPairCode() }
            let secret = Rand.base64url(bytes: 32)
            let p = Peer(id: id, name: o["name"] as? String ?? "Dispositivo", platform: o["platform"] as? String ?? "android",
                         port: o["port"] as? Int ?? 47820, addrs: o["addrs"] as? [String] ?? [], secret: secret, lastSeen: .now)
            self.db.savePeer(p); self.peers = self.db.peers(); self.online.insert(id)
            self.justPaired = p
            var info = self.selfInfo(); info["secret"] = secret
            return .json(info)
        }
    }

    private nonisolated func handleOffer(_ peer: Peer, _ body: HTTPServer.Body) async -> HTTPServer.Response {
        guard let data = await body.readAll(),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let batch = o["batch"] as? String, let files = o["files"] as? [[String: Any]] else { return .error("bad_request", 400) }
        let collectionId = o["collection"] as? String
        let list = files.map { (name: $0["name"] as? String ?? "archivo", size: ($0["size"] as? NSNumber)?.int64Value ?? 0,
                                mime: $0["mime"] as? String ?? "application/octet-stream") }
        let ask = await MainActor.run { self.askBeforeReceiving }
        var accepted = true
        if ask {
            accepted = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
                Task { @MainActor in
                    self.offerWaiters[batch] = cont
                    self.offers.append(PendingOffer(id: batch, peerName: peer.name, platform: peer.platform, files: list))
                    Task { @MainActor in
                        try? await Task.sleep(for: .seconds(60))
                        self.answerOffer(batch, accept: false)
                    }
                }
            }
        }
        if accepted {
            await MainActor.run {
                self.acceptedBatches[batch] = peer.id
                let col = self.collections.first { $0.id == collectionId }
                self.batchCollection[batch] = col?.id
                let items = list.map { TransferItem(name: $0.name, size: $0.size, mime: $0.mime) }
                self.transfers.removeAll { $0.id == batch }
                self.transfers.insert(Transfer(id: batch, direction: .incoming, peerId: peer.id, peerName: peer.name, items: items,
                                               collection: col?.name), at: 0)
            }
        }
        return .json(["accepted": accepted])
    }

    func answerOffer(_ batch: String, accept: Bool) {
        offers.removeAll { $0.id == batch }
        offerWaiters.removeValue(forKey: batch)?.resume(returning: accept)
    }

    private nonisolated func handleFile(_ peer: Peer, _ req: HTTPServer.Request, _ body: HTTPServer.Body) async -> HTTPServer.Response {
        guard let batch = req.header("X-Pasala-Batch") else { return .error("bad_request", 400) }
        let ok = await MainActor.run { self.acceptedBatches[batch] == peer.id }
        guard ok else { return .error("not_accepted", 409) }
        let name = (req.header("X-Pasala-Name") ?? "archivo").removingPercentEncoding ?? "archivo"
        let mime = req.header("X-Pasala-Mime") ?? "application/octet-stream"
        let index = Int(req.header("X-Pasala-Index") ?? "0") ?? 0
        let count = Int(req.header("X-Pasala-Count") ?? "1") ?? 1
        let size = req.contentLength
        let (dir, col): (URL, FileCollection?) = await MainActor.run {
            if let cid = self.batchCollection[batch], let c = self.collections.first(where: { $0.id == cid }) { return (c.url, c) }
            return (peer.folder.map { URL(fileURLWithPath: $0) } ?? self.inbox, nil)
        }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let tmp = dir.appendingPathComponent(".pasala-\(UUID().uuidString).part")

        await MainActor.run { self.updateItem(batch, index: index, peer: peer, count: count) { $0.name = name; $0.size = size; $0.mime = mime } }
        let complete = await body.stream(to: tmp) { written in
            Task { @MainActor in self.updateItem(batch, index: index, peer: peer, count: count) { $0.done = written } }
        }
        guard complete else {
            try? FileManager.default.removeItem(at: tmp)
            await MainActor.run { self.updateItem(batch, index: index, peer: peer, count: count) { $0.failed = true }; self.finishIfDone(batch) }
            return .error("incomplete", 400)
        }
        let dest = uniqueURL(in: dir, name: name)
        do { try FileManager.default.moveItem(at: tmp, to: dest) } catch { return .error("write_failed", 500) }
        if let ms = req.header("X-Pasala-Modified").flatMap(Double.init) {
            try? FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: ms / 1000)], ofItemAtPath: dest.path)
        }
        await MainActor.run {
            self.db.addFile(batch: batch, direction: "in", peerId: peer.id, peerName: peer.name, name: dest.lastPathComponent,
                            path: dest.path, mime: mime, size: size, collectionId: col?.id, collection: col?.name)
            self.updateItem(batch, index: index, peer: peer, count: count) { $0.done = size; $0.finished = true; $0.url = dest; $0.name = dest.lastPathComponent }
            self.reloadRecent()
            self.finishIfDone(batch)
        }
        return .json(["ok": true, "name": dest.lastPathComponent])
    }

    private func updateItem(_ batch: String, index: Int, peer: Peer, count: Int, _ f: (inout TransferItem) -> Void) {
        if !transfers.contains(where: { $0.id == batch }) {
            transfers.insert(Transfer(id: batch, direction: .incoming, peerId: peer.id, peerName: peer.name,
                                      items: (0..<max(count, 1)).map { _ in TransferItem(name: "", size: 0, mime: "") }), at: 0)
        }
        guard let t = transfers.firstIndex(where: { $0.id == batch }) else { return }
        while transfers[t].items.count <= index { transfers[t].items.append(TransferItem(name: "", size: 0, mime: "")) }
        f(&transfers[t].items[index])
    }

    private func finishIfDone(_ batch: String) {
        guard let t = transfers.firstIndex(where: { $0.id == batch }) else { return }
        if transfers[t].items.allSatisfy({ $0.finished || $0.failed }) {
            transfers[t].state = transfers[t].items.contains { $0.failed } ? .failed("Algunos archivos no llegaron completos") : .done
            transfers[t].finishedAt = .now
            acceptedBatches[batch] = nil
            batchCollection[batch] = nil
        }
    }

    // MARK: local control (used by pasala-mcp on this Mac)

    private nonisolated func handleLocal(_ path: String, _ req: HTTPServer.Request, _ body: HTTPServer.Body) async -> HTTPServer.Response {
        let loopback = req.remoteHost == "127.0.0.1" || req.remoteHost == "::1"
        let token = await MainActor.run { self.localToken }
        guard loopback, constantTimeEquals(req.header("X-Pasala-Local") ?? "", token) else { return .error("unauthorized", 401) }
        switch path {
        case "/local/status":
            return await MainActor.run {
                .json(["device": self.selfInfo(), "inbox": self.inbox.path,
                       "devices": self.peers.map { ["id": $0.id, "name": $0.name, "platform": $0.platform,
                                                    "online": self.online.contains($0.id)] }])
            }
        case "/local/pair":
            guard let data = await body.readAll(), let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let link = o["link"] as? String else { return .error("bad_request", 400) }
            let err = await self.pair(withPayload: link)
            let name = await MainActor.run { self.justPaired?.name ?? "" }
            return err == nil ? .json(["ok": true, "device": name]) : .json(["error": err!], status: 400)
        case "/local/send":
            guard let data = await body.readAll(), let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let paths = o["paths"] as? [String], let dev = o["device"] as? String else { return .error("bad_request", 400) }
            let peer = await MainActor.run {
                self.peers.first { $0.id == dev } ?? self.peers.first { $0.name.localizedCaseInsensitiveCompare(dev) == .orderedSame }
                    ?? self.peers.first { $0.name.localizedCaseInsensitiveContains(dev) }
            }
            guard let peer else { return .error("unknown_device", 404) }
            let urls = paths.map { URL(fileURLWithPath: ($0 as NSString).expandingTildeInPath) }
            let missing = urls.filter { !FileManager.default.fileExists(atPath: $0.path) }
            guard missing.isEmpty else { return .json(["error": "missing_files", "paths": missing.map(\.path)], status: 400) }
            let err = await self.send(urls, to: peer, collection: o["collection"] as? String)
            return err == nil ? .json(["ok": true, "device": peer.name, "count": urls.count]) : .json(["error": err!], status: 500)
        default: return .error("not_found", 404)
        }
    }

    // MARK: client

    private func authed(_ peer: Peer, host: String, path: String) -> URLRequest {
        var r = URLRequest(url: URL(string: "http://\(host):\(peer.port)\(path)")!)
        r.httpMethod = "POST"
        r.setValue(deviceId, forHTTPHeaderField: "X-Pasala-From")
        r.setValue("Bearer \(peer.secret)", forHTTPHeaderField: "Authorization")
        return r
    }

    private func hello(_ peer: Peer, host: String) async -> Bool {
        var r = authed(peer, host: host, path: "/v1/hello")
        r.timeoutInterval = 2.5
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try? JSONSerialization.data(withJSONObject: ["name": deviceName, "port": port, "addrs": addrs])
        guard let (data, resp) = try? await session.data(for: r), (resp as? HTTPURLResponse)?.statusCode == 200 else { return false }
        if let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any], var p = peers.first(where: { $0.id == peer.id }) {
            if let n = o["name"] as? String, !n.isEmpty { p.name = n }
            if let a = o["addrs"] as? [String], !a.isEmpty { p.addrs = a }
            if let port = o["port"] as? Int { p.port = port }
            p.lastSeen = .now; p.lastAddr = host
            db.savePeer(p); peers = db.peers()
            peerCollections[p.id] = (o["collections"] as? [[String: Any]] ?? []).compactMap { c in
                guard let id = c["id"] as? String, let name = c["name"] as? String else { return nil }
                return RemoteCollection(id: id, name: name)
            }
        }
        return true
    }

    /// Finds an address that answers right now.
    func resolve(_ peer: Peer) async -> String? {
        var candidates: [String] = []
        for c in [peer.lastAddr, discovered[peer.id]].compactMap({ $0 }) + peer.addrs where !candidates.contains(c) { candidates.append(c) }
        // Try LAN-ish first in parallel, return the first that answers.
        return await withTaskGroup(of: (Int, String?).self) { group in
            for (i, host) in candidates.enumerated() {
                group.addTask { @MainActor in (i, await self.hello(peer, host: host) ? host : nil) }
            }
            var best: (Int, String)?
            for await (i, host) in group {
                if let host, best == nil || i < best!.0 { best = (i, host); if i == 0 { group.cancelAll(); break } }
            }
            return best?.1
        }
    }

    func refreshOnline() async {
        for p in peers {
            let ok = await resolve(p) != nil
            if ok { online.insert(p.id) } else { online.remove(p.id) }
        }
    }

    /// Sends files to one device. Returns an error message for the person, or nil.
    @discardableResult
    func send(_ input: [URL], to peer: Peer, collection: String? = nil) async -> String? {
        let urls = Self.expand(input)
        guard !urls.isEmpty else { return "No hay archivos para mandar." }
        let batch = UUID().uuidString.lowercased()
        let files = urls.map { u -> TransferItem in
            let size = (try? FileManager.default.attributesOfItem(atPath: u.path)[.size] as? NSNumber)??.int64Value ?? 0
            var it = TransferItem(name: u.lastPathComponent, size: size, mime: Mime.of(u)); it.url = u; return it
        }
        let colName = peerCollections[peer.id]?.first { $0.id == collection }?.name
        transfers.insert(Transfer(id: batch, direction: .outgoing, peerId: peer.id, peerName: peer.name, items: files, collection: colName), at: 0)
        func fail(_ msg: String) -> String {
            if let t = transfers.firstIndex(where: { $0.id == batch }) { transfers[t].state = .failed(msg); transfers[t].finishedAt = .now }
            return msg
        }
        guard let host = await resolve(peer) else { online.remove(peer.id); return fail("\(peer.name) no responde. ¿Tiene Pasala abierta?") }
        online.insert(peer.id)
        guard let p = peers.first(where: { $0.id == peer.id }) else { return fail("Ese dispositivo ya no está vinculado.") }

        var offer = authed(p, host: host, path: "/v1/offer")
        offer.setValue("application/json", forHTTPHeaderField: "Content-Type")
        offer.timeoutInterval = 90   // See HISTORY.md#offer-timeout
        var offerBody: [String: Any] = ["batch": batch, "files": files.map { ["name": $0.name, "size": $0.size, "mime": $0.mime] }]
        if let collection { offerBody["collection"] = collection }
        offer.httpBody = try? JSONSerialization.data(withJSONObject: offerBody)
        guard let (od, _) = try? await session.data(for: offer),
              let oo = try? JSONSerialization.jsonObject(with: od) as? [String: Any] else { return fail("Se cortó la conexión con \(p.name).") }
        guard oo["accepted"] as? Bool == true else { return fail("\(p.name) no aceptó los archivos.") }

        for (i, item) in files.enumerated() {
            guard let src = item.url else { continue }
            var r = authed(p, host: host, path: "/v1/files")
            r.timeoutInterval = 120
            r.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            r.setValue(batch, forHTTPHeaderField: "X-Pasala-Batch")
            r.setValue(String(i), forHTTPHeaderField: "X-Pasala-Index")
            r.setValue(String(files.count), forHTTPHeaderField: "X-Pasala-Count")
            r.setValue(item.name.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "archivo", forHTTPHeaderField: "X-Pasala-Name")
            r.setValue(item.mime, forHTTPHeaderField: "X-Pasala-Mime")
            if let m = (try? FileManager.default.attributesOfItem(atPath: src.path)[.modificationDate]) as? Date {
                r.setValue(String(Int64(m.timeIntervalSince1970 * 1000)), forHTTPHeaderField: "X-Pasala-Modified")
            }
            let delegate = UploadProgress { [weak self] sent in
                Task { @MainActor in
                    guard let self, let t = self.transfers.firstIndex(where: { $0.id == batch }) else { return }
                    self.transfers[t].items[i].done = sent
                }
            }
            do {
                let (_, resp) = try await session.upload(for: r, fromFile: src, delegate: delegate)
                guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
                if let t = transfers.firstIndex(where: { $0.id == batch }) { transfers[t].items[i].done = item.size; transfers[t].items[i].finished = true }
                db.addFile(batch: batch, direction: "out", peerId: p.id, peerName: p.name, name: item.name, path: src.path, mime: item.mime,
                           size: item.size, collectionId: collection, collection: colName)
            } catch {
                if let t = transfers.firstIndex(where: { $0.id == batch }) { transfers[t].items[i].failed = true }
                reloadRecent()
                return fail("No pude terminar de mandar \(item.name).")
            }
        }
        if let t = transfers.firstIndex(where: { $0.id == batch }) { transfers[t].state = .done; transfers[t].finishedAt = .now }
        reloadRecent()
        return nil
    }

    /// Folders become the files inside them (hidden files skipped).
    static func expand(_ urls: [URL]) -> [URL] {
        var out: [URL] = []
        for u in urls {
            var isDir: ObjCBool = false
            guard FileManager.default.fileExists(atPath: u.path, isDirectory: &isDir) else { continue }
            if !isDir.boolValue { out.append(u); continue }
            let e = FileManager.default.enumerator(at: u, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles])
            while let f = e?.nextObject() as? URL {
                if (try? f.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true { out.append(f) }
            }
        }
        return out
    }

    // MARK: discovery

    private func startBrowsing() {
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_pasala._tcp", domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            for r in results {
                guard case let .bonjour(txt) = r.metadata, let id = txt["id"] else { continue }
                Task { @MainActor in
                    guard let self, id != self.deviceId, self.peers.contains(where: { $0.id == id }) else { return }
                    self.resolveEndpoint(r.endpoint, for: id)
                }
            }
        }
        b.start(queue: .main)
        browser = b
    }

    private func resolveEndpoint(_ ep: NWEndpoint, for id: String) {
        let params = NWParameters.tcp
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
        let c = NWConnection(to: ep, using: params)
        c.stateUpdateHandler = { [weak self] st in
            if case .ready = st, case let .hostPort(host, _)? = c.currentPath?.remoteEndpoint {
                var h = "\(host)"; if let pct = h.firstIndex(of: "%") { h = String(h[..<pct]) }
                Task { @MainActor in self?.discovered[id] = h; self?.online.insert(id) }
                c.cancel()
            } else if case .failed = st { c.cancel() }
        }
        c.start(queue: .main)
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { c.cancel() }
    }
}

struct RemoteCollection: Hashable, Identifiable { let id: String; let name: String }

final class UploadProgress: NSObject, URLSessionTaskDelegate {
    let onSent: (Int64) -> Void
    init(_ f: @escaping (Int64) -> Void) { onSent = f }
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        onSent(totalBytesSent)
    }
}
