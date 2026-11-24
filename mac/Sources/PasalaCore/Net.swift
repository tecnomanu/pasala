import Foundation
import UniformTypeIdentifiers

public enum NetInfo {
    /// IPv4 addresses this Mac can be reached on: LAN first, then Tailscale (100.64.0.0/10).
    public static func addresses() -> [String] {
        var lan: [String] = [], tailnet: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let i = ptr.pointee
            guard let sa = i.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
            let flags = Int32(i.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            let ip = String(cString: host)
            let name = String(cString: i.ifa_name)
            if isTailscale(ip) { tailnet.append(ip) }
            else if name.hasPrefix("en") && !ip.hasPrefix("169.254.") { lan.append(ip) }
        }
        return Array(NSOrderedSet(array: lan + tailnet)) as? [String] ?? lan + tailnet
    }

    public static func isTailscale(_ ip: String) -> Bool {
        let p = ip.split(separator: ".").compactMap { Int($0) }
        return p.count == 4 && p[0] == 100 && (64...127).contains(p[1])
    }
}

public enum Mime {
    public static func of(_ url: URL) -> String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }
    public static func ext(_ mime: String) -> String? { UTType(mimeType: mime)?.preferredFilenameExtension }
    public static func kind(_ mime: String) -> String {
        if mime.hasPrefix("image/") { return "image" }
        if mime.hasPrefix("video/") { return "video" }
        if mime.hasPrefix("audio/") { return "audio" }
        if mime == "application/pdf" { return "pdf" }
        return "file"
    }
}

/// Picks a free name in `dir`: foto.jpg → foto (1).jpg → foto (2).jpg
public func uniqueURL(in dir: URL, name: String) -> URL {
    let safe = name.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
    let clean = safe.isEmpty || safe.hasPrefix(".") ? "archivo\(safe)" : safe
    var url = dir.appendingPathComponent(clean)
    let base = url.deletingPathExtension().lastPathComponent, ext = url.pathExtension
    var n = 1
    while FileManager.default.fileExists(atPath: url.path) {
        url = dir.appendingPathComponent(ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)")
        n += 1
    }
    return url
}

public func formatBytes(_ n: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
}
