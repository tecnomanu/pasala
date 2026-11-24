import Foundation
import Network

/// Minimal HTTP/1.1 server on Network.framework: one request per connection, bodies streamed.
public final class HTTPServer {
    public struct Request {
        public let method: String
        public let path: String
        public let headers: [String: String]   // lowercased keys
        public let remoteHost: String
        public var contentLength: Int64 { Int64(headers["content-length"] ?? "") ?? 0 }
        public func header(_ k: String) -> String? { headers[k.lowercased()] }
    }

    public struct Response {
        public var status: Int
        public var body: Data
        public var contentType = "application/json"
        public init(status: Int, body: Data, contentType: String = "application/json") {
            self.status = status; self.body = body; self.contentType = contentType
        }
        public static func json(_ obj: Any, status: Int = 200) -> Response {
            Response(status: status, body: (try? JSONSerialization.data(withJSONObject: obj)) ?? Data("{}".utf8))
        }
        public static func error(_ code: String, _ status: Int) -> Response { .json(["error": code], status: status) }
    }

    public typealias Handler = (Request, Body) async -> Response

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "pasala.http", attributes: .concurrent)
    public private(set) var port: UInt16 = 0
    public var handler: Handler = { _, _ in .error("not_found", 404) }

    public init() {}

    /// Starts on the first free port from `preferred`. Advertises Bonjour `_pasala._tcp` with TXT id.
    public func start(preferred: UInt16, serviceName: String, txt: [String: String]) throws {
        var lastError: Error?
        for p in preferred..<(preferred + 20) {
            do {
                let params = NWParameters.tcp
                params.allowLocalEndpointReuse = true
                let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: p)!)
                var record = NWTXTRecord()
                for (k, v) in txt { record[k] = v }
                l.service = NWListener.Service(name: serviceName, type: "_pasala._tcp", domain: nil, txtRecord: record)
                l.newConnectionHandler = { [weak self] c in self?.accept(c) }
                let sem = DispatchSemaphore(value: 0)
                var failed: Error?
                l.stateUpdateHandler = { st in
                    switch st {
                    case .ready: sem.signal()
                    case .failed(let e): failed = e; sem.signal()
                    default: break
                    }
                }
                l.start(queue: queue)
                _ = sem.wait(timeout: .now() + 3)
                if let failed { l.cancel(); throw failed }
                listener = l
                port = p
                return
            } catch { lastError = error; continue }
        }
        throw lastError ?? NSError(domain: "HTTPServer", code: 1)
    }

    public func stop() { listener?.cancel(); listener = nil }

    private func accept(_ c: NWConnection) {
        c.start(queue: queue)
        let body = Body(connection: c)
        Task {
            guard let req = await body.readHead(remote: c.endpoint) else { c.cancel(); return }
            let resp = await handler(req, body)
            var head = "HTTP/1.1 \(resp.status) \(Self.reason(resp.status))\r\n"
            head += "Content-Type: \(resp.contentType)\r\nContent-Length: \(resp.body.count)\r\nConnection: close\r\n\r\n"
            var out = Data(head.utf8); out.append(resp.body)
            c.send(content: out, completion: .contentProcessed { _ in c.cancel() })
        }
    }

    static func reason(_ s: Int) -> String {
        [200: "OK", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden", 404: "Not Found",
         409: "Conflict", 413: "Payload Too Large", 500: "Internal Server Error"][s] ?? "OK"
    }

    /// Reads the request head and then, on demand, the body.
    public final class Body {
        let c: NWConnection
        var buffer = Data()
        var remaining: Int64 = 0
        init(connection: NWConnection) { c = connection }

        func chunk() async -> Data? {
            await withCheckedContinuation { cont in
                c.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { data, _, done, err in
                    if let data, !data.isEmpty { cont.resume(returning: data) }
                    else if done || err != nil { cont.resume(returning: nil) }
                    else { cont.resume(returning: Data()) }
                }
            }
        }

        func readHead(remote: NWEndpoint) async -> Request? {
            let sep = Data("\r\n\r\n".utf8)
            while buffer.range(of: sep) == nil {
                guard buffer.count < 64 * 1024, let d = await chunk() else { return nil }
                buffer.append(d)
            }
            let r = buffer.range(of: sep)!
            guard let head = String(data: buffer[..<r.lowerBound], encoding: .utf8) else { return nil }
            buffer = Data(buffer[r.upperBound...])
            var lines = head.components(separatedBy: "\r\n")
            let first = lines.removeFirst().split(separator: " ")
            guard first.count >= 2 else { return nil }
            var headers: [String: String] = [:]
            for l in lines {
                guard let i = l.firstIndex(of: ":") else { continue }
                headers[l[..<i].trimmingCharacters(in: .whitespaces).lowercased()] = l[l.index(after: i)...].trimmingCharacters(in: .whitespaces)
            }
            var host = ""
            if case let .hostPort(h, _) = remote {
                switch h {
                case .ipv4(let a): host = "\(a)"
                case .ipv6(let a): host = "\(a)"
                case .name(let n, _): host = n
                @unknown default: break
                }
            }
            if let pct = host.firstIndex(of: "%") { host = String(host[..<pct]) }
            let req = Request(method: String(first[0]), path: String(first[1]), headers: headers, remoteHost: host)
            remaining = req.contentLength
            return req
        }

        /// Whole body in memory (small JSON bodies only).
        public func readAll(max: Int = 1 << 20) async -> Data? {
            guard remaining <= max else { return nil }
            var out = Data()
            if !buffer.isEmpty { out.append(buffer.prefix(Int(remaining))); remaining -= Int64(min(buffer.count, Int(remaining))); buffer.removeAll() }
            while remaining > 0 {
                guard let d = await chunk() else { return nil }
                let take = d.prefix(Int(remaining)); out.append(take); remaining -= Int64(take.count)
            }
            return out
        }

        /// Streams the body to `url`. Returns false if the connection ended early.
        public func stream(to url: URL, progress: (Int64) -> Void) async -> Bool {
            FileManager.default.createFile(atPath: url.path, contents: nil)
            guard let fh = try? FileHandle(forWritingTo: url) else { return false }
            defer { try? fh.close() }
            var written: Int64 = 0
            func write(_ d: Data) { fh.write(d); written += Int64(d.count); remaining -= Int64(d.count) }
            if !buffer.isEmpty { write(buffer.prefix(Int(remaining))); buffer.removeAll() }
            var lastReport = Date.distantPast
            while remaining > 0 {
                guard let d = await chunk() else { return false }
                if d.isEmpty { continue }
                write(d.prefix(Int(remaining)))
                if Date().timeIntervalSince(lastReport) > 0.08 { progress(written); lastReport = Date() }
            }
            progress(written)
            return true
        }
    }
}
