import SwiftUI
import PasalaKit

/// Destination, collection and send button for a set of local files. Used by the app and the Share extension.
struct SendPanel: View {
    @EnvironmentObject var engine: Engine
    let files: [URL]
    /// When set, sends this text (to copy on the other device) instead of files.
    var text: String? = nil
    var initialPeer: String?
    var onFinished: (Bool) -> Void = { _ in }

    @State private var target: String?
    @State private var collection: String?
    @State private var sending = false
    @State private var batchId: String?
    @State private var result: String?
    @State private var ok = false

    var transfer: Transfer? { engine.transfers.first { $0.direction == .outgoing && ($0.isRunning || $0.id == batchId) } }
    var collections: [RemoteCollection] { target.flatMap { engine.peerCollections[$0] } ?? [] }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let text {
                Text(text).font(.system(size: 15, design: .rounded)).lineLimit(6)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(14).card()
            }
            if !files.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(files, id: \.self) { u in
                            Thumb(url: u, mime: Mime.of(u), corner: 16).frame(width: 84, height: 84)
                        }
                    }
                }
                Text("\(files.count == 1 ? files[0].lastPathComponent : "\(files.count) archivos") · \(formatBytes(totalBytes))")
                    .font(.system(size: 13, design: .rounded)).foregroundStyle(Theme.dim).lineLimit(1)
            }

            SectionLabel("¿A DÓNDE?")
            if engine.peers.isEmpty {
                Text("Todavía no vinculaste ningún dispositivo. Abrí Pasala y tocá Sumar dispositivo.")
                    .font(.system(size: 14, design: .rounded)).foregroundStyle(Theme.dim).padding(16).card()
            }
            ForEach(engine.peers) { p in
                Button { target = p.id } label: {
                    HStack(spacing: 12) {
                        DeviceGlyph(platform: p.platform, online: engine.online.contains(p.id), size: 38)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.name).font(.system(size: 16, weight: .bold, design: .rounded))
                            Text(engine.online.contains(p.id) ? String(localized: "En línea") : String(localized: "No aparece ahora"))
                                .font(.system(size: 12, design: .rounded)).foregroundStyle(engine.online.contains(p.id) ? Theme.mint : Theme.dim)
                        }
                        Spacer()
                        Image(systemName: target == p.id ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 22)).foregroundStyle(target == p.id ? AnyShapeStyle(Theme.brand) : AnyShapeStyle(Theme.dim))
                    }
                    .padding(14).card(highlight: target == p.id)
                }.buttonStyle(.plain)
            }

            if text == nil, !collections.isEmpty {
                SectionLabel("¿EN QUÉ COLECCIÓN?")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        chip(String(localized: "Ninguna"), icon: "tray", selected: collection == nil) { collection = nil }
                        ForEach(collections) { c in chip(c.name, icon: "folder.fill", selected: collection == c.id) { collection = c.id } }
                    }
                }
                Text(collection == nil ? String(localized: "Va a la carpeta de siempre del otro dispositivo.") : String(localized: "Va a la carpeta de esa colección."))
                    .font(.system(size: 12, design: .rounded)).foregroundStyle(Theme.dim)
            }

            if let t = transfer, sending || t.isRunning {
                HStack(spacing: 12) {
                    ProgressRing(progress: t.progress, size: 30, line: 4)
                    Text("Mandando… \(t.items.filter(\.finished).count) de \(t.items.count)").font(.system(size: 14, weight: .semibold, design: .rounded))
                }
            } else if let result {
                Text(result).font(.system(size: 14, weight: .semibold, design: .rounded)).foregroundStyle(ok ? Theme.mint : Theme.orange)
            }

            Button(action: send) {
                HStack {
                    Image(systemName: "paperplane.fill")
                    Text(buttonTitle).font(.system(size: 17, weight: .bold, design: .rounded)).lineLimit(1)
                }
                .foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 16)
                .background(Capsule().fill(Theme.brand)).opacity(canSend ? 1 : 0.45)
            }
            .buttonStyle(.plain)
            .disabled(!canSend)
        }
        .onAppear {
            if target == nil { target = initialPeer ?? engine.peers.first(where: { engine.online.contains($0.id) })?.id ?? engine.peers.first?.id }
        }
        .task(id: target) {
            guard let id = target, let p = engine.peers.first(where: { $0.id == id }) else { return }
            if await engine.resolve(p) != nil { engine.online.insert(id) }
            if let c = collection, !collections.contains(where: { $0.id == c }) { collection = nil }
        }
    }

    var totalBytes: Int64 {
        files.reduce(0) { $0 + ((try? FileManager.default.attributesOfItem(atPath: $1.path)[.size] as? NSNumber)?.int64Value ?? 0) }
    }
    var canSend: Bool { (!files.isEmpty || !(text ?? "").isEmpty) && target != nil && !sending && !ok }
    var buttonTitle: String {
        guard let t = target, let p = engine.peers.first(where: { $0.id == t }) else { return String(localized: "Enviar") }
        if let c = collections.first(where: { $0.id == collection }) { return String(localized: "Enviar a \(p.name) · \(c.name)") }
        return String(localized: "Enviar a \(p.name)")
    }

    func chip(_ title: String, icon: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(.system(size: 14, weight: .semibold, design: .rounded))
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(Capsule().fill(selected ? Theme.violet.opacity(0.3) : Theme.surface))
                .overlay(Capsule().strokeBorder(selected ? AnyShapeStyle(Theme.brand) : AnyShapeStyle(Theme.faint), lineWidth: 1.5))
        }.buttonStyle(.plain)
    }

    func send() {
        guard let id = target, let p = engine.peers.first(where: { $0.id == id }) else { return }
        sending = true; result = nil
        let before = Set(engine.transfers.map(\.id))
        Task {
            async let err = text != nil ? engine.sendText(text!, to: p) : engine.send(files, to: p, collection: collection)
            try? await Task.sleep(for: .milliseconds(100))
            batchId = engine.transfers.first { !before.contains($0.id) }?.id
            let e = await err
            sending = false
            if let e { result = e; ok = false } else { result = String(localized: "Listo, ya lo tiene \(p.name)."); ok = true }
            onFinished(e == nil)
        }
    }
}
