import SwiftUI
import QuickLook
import PasalaKit

struct HomeView: View {
    @EnvironmentObject var engine: Engine
    @Binding var pairLink: String?
    @State private var showPair = false
    @State private var showSettings = false
    @State private var sendTo: SendTarget?
    @State private var sendText: String?
    @State private var copiedId: Int64?
    @State private var preview: URL?
    @State private var renaming: Peer?
    @State private var newName = ""
    @State private var pairError: String?

    var received: [FileRecord] { engine.recent.filter { $0.direction == "in" } }

    var body: some View {
        NavigationStack {
            ZStack {
                Backdrop()
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        header
                        statusPill
                        if !engine.peers.isEmpty { pasteRow }
                        if let live = engine.transfers.first(where: \.isRunning) {
                            LiveCard(transfer: live).transition(.move(edge: .top).combined(with: .opacity))
                        }
                        devices
                        deck
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 40)
                    .animation(.spring(response: 0.45, dampingFraction: 0.8), value: engine.transfers.first(where: \.isRunning)?.id)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .sheet(isPresented: $showPair) { PairView().environmentObject(engine) }
        .sheet(isPresented: $showSettings) { SettingsView().environmentObject(engine) }
        .sheet(item: $sendTo) { t in SendView(initialPeer: t.peerId).environmentObject(engine) }
        .sheet(isPresented: Binding(get: { sendText != nil }, set: { if !$0 { sendText = nil } })) {
            TextSendSheet(text: sendText ?? "").environmentObject(engine)
        }
        .quickLookPreview($preview)
        .alert("Nombre del dispositivo", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Nombre", text: $newName)
            Button("Guardar") { if let p = renaming { engine.rename(p, to: newName) } }
            Button("Cancelar", role: .cancel) {}
        }
        .alert(pairAlertTitle, isPresented: Binding(get: { pairLink != nil }, set: { if !$0 { pairLink = nil } })) {
            Button("Vincular") { if let l = pairLink { Task { pairError = await engine.pair(withPayload: l) } } }
            Button("Cancelar", role: .cancel) {}
        } message: { Text("Después se pueden pasar archivos en los dos sentidos.") }
        .alert("No se pudo vincular", isPresented: Binding(get: { pairError != nil }, set: { if !$0 { pairError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(pairError ?? "") }
        .modifier(OfferPrompt())
    }

    var pairAlertTitle: String {
        guard let l = pairLink, let name = PairLink.deviceName(l) else { return String(localized: "¿Vincular este dispositivo?") }
        return String(localized: "¿Vincular con \(name)?")
    }

    var header: some View {
        HStack(spacing: 12) {
            PasalaTile(size: 46).shadow(color: Theme.violet.opacity(0.5), radius: 12)
            Text("Pasala").font(.system(size: 32, weight: .heavy, design: .rounded))
            Spacer()
            Button { showSettings = true } label: {
                Image(systemName: "slider.horizontal.3").font(.system(size: 18, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 42, height: 42).background(Circle().fill(Theme.surface))
            }
        }
        .padding(.top, 8)
    }

    var pasteRow: some View {
        HStack(spacing: 10) {
            PasteButton(payloadType: String.self) { strings in
                Task { @MainActor in sendText = strings.joined(separator: "\n") }
            }
            .buttonBorderShape(.capsule)
            .tint(Theme.violet)
            Text("Mandá lo que copiaste a otro dispositivo.").font(.system(size: 12, design: .rounded)).foregroundStyle(Theme.dim)
        }
    }

    var statusPill: some View {
        HStack(spacing: 8) {
            Circle().fill(Theme.mint).frame(width: 7, height: 7).shadow(color: Theme.mint, radius: 4)
            Text("Lista para recibir · \(engine.deviceName)").font(.system(size: 13, weight: .medium, design: .rounded))
        }
        .padding(.horizontal, 12).padding(.vertical, 7)
        .background(Capsule().fill(Theme.surface))
    }

    var devices: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                SectionLabel("TUS DISPOSITIVOS")
                Spacer()
                if !engine.peers.isEmpty {
                    Button { showPair = true } label: { Label("Sumar", systemImage: "plus").font(.system(size: 13, weight: .bold, design: .rounded)) }
                        .foregroundStyle(Theme.orange)
                }
            }
            if engine.peers.isEmpty {
                Button { showPair = true } label: {
                    VStack(alignment: .leading, spacing: 10) {
                        Image(systemName: "qrcode").font(.system(size: 26, weight: .semibold)).foregroundStyle(.white)
                            .frame(width: 56, height: 56).background(RoundedRectangle(cornerRadius: 16).fill(Theme.brand))
                        Text("Sumá tu primer dispositivo").font(.system(size: 22, weight: .heavy, design: .rounded))
                        Text("Escaneá el código de tu Mac o de otro teléfono con Pasala, o mostrá el tuyo. Se hace una sola vez.")
                            .font(.system(size: 14, design: .rounded)).foregroundStyle(Theme.dim).multilineTextAlignment(.leading)
                        Text("Empezar →").font(.system(size: 14, weight: .bold, design: .rounded)).foregroundStyle(Theme.orange)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20).card()
                }.buttonStyle(.plain)
            }
            ForEach(engine.peers) { p in
                Button { sendTo = SendTarget(peerId: p.id) } label: { PeerCard(peer: p) }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button { sendTo = SendTarget(peerId: p.id) } label: { Label("Mandarle archivos", systemImage: "arrow.up.circle") }
                        Button { newName = p.name; renaming = p } label: { Label("Cambiar nombre", systemImage: "pencil") }
                        Button(role: .destructive) { engine.unpair(p) } label: { Label("Desvincular", systemImage: "link.badge.minus") }
                    }
            }
        }
    }

    var deck: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel("LO QUE LLEGÓ")
            if received.isEmpty {
                Text(engine.peers.isEmpty ? String(localized: "Cuando te manden algo, aparece acá.") : String(localized: "Mandate algo desde la Mac o el otro teléfono: lo vas a ver caer acá."))
                    .font(.system(size: 14, design: .rounded)).foregroundStyle(Theme.dim)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(18).card()
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 10)], spacing: 10) {
                    ForEach(received.prefix(60)) { f in
                        Button {
                            if let t = engine.snippetText(f) { UIPasteboard.general.string = t; copiedId = f.id }
                            else if FileManager.default.fileExists(atPath: f.path) { preview = f.url }
                        } label: {
                            Group {
                                if f.mime == Engine.snippetMime {
                                    Text(copiedId == f.id ? String(localized: "Copiado") : (engine.snippetText(f) ?? f.name))
                                        .font(.system(size: 12, design: .rounded)).lineLimit(5)
                                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                                        .padding(10).background(RoundedRectangle(cornerRadius: 18).fill(Theme.surface2))
                                } else {
                                    Thumb(url: f.url, mime: f.mime, corner: 18)
                                }
                            }
                                .aspectRatio(1, contentMode: .fit)
                                .overlay(alignment: .bottomLeading) {
                                    Text(f.peerName).font(.system(size: 10, weight: .bold, design: .rounded)).lineLimit(1)
                                        .padding(.horizontal, 7).padding(.vertical, 3)
                                        .background(.ultraThinMaterial, in: Capsule()).padding(6)
                                }
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            ShareLink(item: f.url) { Label("Compartir", systemImage: "square.and.arrow.up") }
                        }
                        .transition(.scale(scale: 0.6).combined(with: .opacity))
                    }
                }
                .animation(.spring(response: 0.5, dampingFraction: 0.72), value: received.map(\.id))
                Text("También están en Archivos › En mi iPhone › Pasala.")
                    .font(.system(size: 12, design: .rounded)).foregroundStyle(Theme.dim)
            }
        }
    }
}

struct SendTarget: Identifiable { let peerId: String?; var id: String { peerId ?? "-" } }

struct PeerCard: View {
    @EnvironmentObject var engine: Engine
    let peer: Peer
    var online: Bool { engine.online.contains(peer.id) }
    var body: some View {
        HStack(spacing: 14) {
            DeviceGlyph(platform: peer.platform, online: online, size: 46)
            VStack(alignment: .leading, spacing: 3) {
                Text(peer.name).font(.system(size: 17, weight: .bold, design: .rounded)).lineLimit(1)
                Text(online ? String(localized: "En línea") : (peer.lastSeen.map { String(localized: "Visto \($0.relative)") } ?? String(localized: "No aparece ahora")))
                    .font(.system(size: 13, weight: .medium, design: .rounded)).foregroundStyle(online ? Theme.mint : Theme.dim)
            }
            Spacer()
            Image(systemName: "arrow.up.right").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                .frame(width: 42, height: 42).background(Circle().fill(Theme.brand))
        }
        .padding(16).card()
    }
}

struct LiveCard: View {
    let transfer: Transfer
    var body: some View {
        HStack(spacing: 14) {
            ProgressRing(progress: transfer.progress, size: 40, line: 4)
            VStack(alignment: .leading, spacing: 4) {
                Text(transfer.direction == .incoming ? String(localized: "Llegando de \(transfer.peerName)") : String(localized: "Mandando a \(transfer.peerName)"))
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                Text("\(transfer.items.filter(\.finished).count) de \(transfer.items.count) · \(formatBytes(transfer.totalBytes))")
                    .font(.system(size: 13, design: .rounded)).foregroundStyle(Theme.dim)
            }
            Spacer()
        }
        .padding(16).card(highlight: true)
    }
}

/// Asks the person before receiving when "Preguntarme antes de recibir" is on.
struct OfferPrompt: ViewModifier {
    @EnvironmentObject var engine: Engine
    func body(content: Content) -> some View {
        let offer = engine.offers.first
        content.alert(offer.map { $0.files.count == 1 ? String(localized: "\($0.peerName) te quiere mandar un archivo") : String(localized: "\($0.peerName) te quiere mandar \($0.files.count) archivos") } ?? "",
                      isPresented: Binding(get: { offer != nil }, set: { _ in })) {
            Button("Aceptar") { if let o = offer { engine.answerOffer(o.id, accept: true) } }
            Button("Rechazar", role: .cancel) { if let o = offer { engine.answerOffer(o.id, accept: false) } }
        } message: { Text(offer.map { formatBytes($0.totalBytes) } ?? "") }
    }
}

enum PairLink {
    static func deviceName(_ link: String) -> String? {
        guard let d = URLComponents(string: link)?.queryItems?.first(where: { $0.name == "d" })?.value,
              let data = Data(base64URL: d),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return obj["name"] as? String
    }
}

/// Sends the copied text: pick the device and go.
struct TextSendSheet: View {
    @EnvironmentObject var engine: Engine
    @Environment(\.dismiss) private var dismiss
    let text: String
    var body: some View {
        ZStack {
            Backdrop()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack {
                        Text("Mandar texto").font(.system(size: 30, weight: .heavy, design: .rounded))
                        Spacer()
                        Button { dismiss() } label: { Image(systemName: "xmark").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                            .frame(width: 36, height: 36).background(Circle().fill(Theme.surface)) }
                    }
                    SendPanel(files: [], text: text) { ok in if ok { DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: { dismiss() }) } }
                }
                .padding(20)
            }
        }
    }
}
