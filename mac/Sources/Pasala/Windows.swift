import SwiftUI
import AppKit
import CoreImage.CIFilterBuiltins
import UniformTypeIdentifiers
import PasalaCore

@MainActor
final class Windows {
    static let shared = Windows()
    private var windows: [String: NSWindow] = [:]
    let sendModel = SendModel()

    private func show<V: View>(_ key: String, title: String, size: CGSize, resizable: Bool = true, @ViewBuilder _ view: () -> V) {
        if let w = windows[key] { w.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true); return }
        var mask: NSWindow.StyleMask = [.titled, .closable, .miniaturizable, .fullSizeContentView]
        if resizable { mask.insert(.resizable) }
        let w = NSWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: mask, backing: .buffered, defer: false)
        w.title = title
        w.titlebarAppearsTransparent = true
        w.titleVisibility = .hidden
        w.isMovableByWindowBackground = true
        w.backgroundColor = NSColor(Theme.ink)
        w.appearance = NSAppearance(named: .darkAqua)
        w.isReleasedWhenClosed = false
        let host = NSHostingView(rootView: view().environmentObject(Engine.shared))
        host.sizingOptions = []          // See HISTORY.md#window-size
        w.contentView = host
        w.center()
        w.setFrameAutosaveName("pasala.\(key)")
        windows[key] = w
        w.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func showMain() { show("main", title: "Pasala", size: CGSize(width: 1120, height: 740)) { MainView() } }
    func showPair() { show("pair", title: "Sumar dispositivo", size: CGSize(width: 520, height: 680), resizable: false) { PairView() } }
    func showSend(to peerId: String?) {
        if let peerId { sendModel.target = peerId }
        else if sendModel.target == nil || !Engine.shared.peers.contains(where: { $0.id == sendModel.target }) {
            sendModel.target = Engine.shared.peers.first(where: { Engine.shared.online.contains($0.id) })?.id ?? Engine.shared.peers.first?.id
        }
        show("send", title: "Enviar", size: CGSize(width: 560, height: 600), resizable: false) { SendView(model: sendModel) }
    }
    func showSettings() { show("settings", title: "Ajustes", size: CGSize(width: 540, height: 660), resizable: false) { SettingsView() } }
}

// MARK: - Background

struct Aurora: View {
    @State private var t = false
    var body: some View {
        ZStack {
            Theme.ink
            Circle().fill(Theme.violet.opacity(0.35)).frame(width: 520).blur(radius: 140)
                .offset(x: t ? 260 : 180, y: t ? -260 : -200)
            Circle().fill(Theme.orange.opacity(0.22)).frame(width: 420).blur(radius: 140)
                .offset(x: t ? -320 : -240, y: t ? 260 : 300)
        }
        .ignoresSafeArea()
        .onAppear { withAnimation(.easeInOut(duration: 9).repeatForever(autoreverses: true)) { t = true } }
    }
}

// MARK: - Main window (the deck)

struct MainView: View {
    @EnvironmentObject var engine: Engine
    @State private var filter: String = "in"
    @State private var hoverDrop = false

    var files: [FileRecord] {
        switch filter {
        case "in": return engine.recent.filter { $0.direction == "in" }
        case "out": return engine.recent.filter { $0.direction == "out" }
        case let f where f.hasPrefix("col:"): return engine.recent.filter { $0.direction == "in" && $0.collectionId == String(f.dropFirst(4)) }
        default: return engine.recent.filter { $0.peerId == filter }
        }
    }

    var title: String {
        if filter == "out" { return "Lo que mandaste" }
        if filter.hasPrefix("col:"), let c = engine.collections.first(where: { "col:\($0.id)" == filter }) { return c.name }
        return "Lo que llegó"
    }

    var body: some View {
        ZStack {
            Aurora()
            HStack(spacing: 0) {
                sidebar.frame(width: 290)
                Rectangle().fill(Theme.faint).frame(width: 1).padding(.vertical, 24)
                deck
            }
        }
        .preferredColorScheme(.dark)
        .frame(minWidth: 900, minHeight: 600)
        .onAppear {
            if !engine.recent.contains(where: { $0.direction == "in" }) && engine.recent.contains(where: { $0.direction == "out" }) { filter = "out" }
        }
    }

    var sidebar: some View {
        VStack(alignment: .leading, spacing: 22) {
            HStack(spacing: 10) {
                PasalaTile(size: 40).shadow(color: Theme.violet.opacity(0.5), radius: 12)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Pasala").font(.system(size: 22, weight: .heavy, design: .rounded))
                    Text(engine.deviceName).font(.system(size: 12, design: .rounded)).foregroundStyle(Theme.dim)
                }
            }.padding(.top, 38)

            VStack(alignment: .leading, spacing: 10) {
                Text("DISPOSITIVOS").font(.system(size: 10, weight: .heavy, design: .rounded)).foregroundStyle(Theme.dim).tracking(1.2)
                if engine.peers.isEmpty {
                    Text("Escaneá el código con tu celular para empezar.").font(.system(size: 13, design: .rounded)).foregroundStyle(Theme.dim)
                }
                ForEach(engine.peers) { p in PeerRow(peer: p, selected: filter == p.id) { filter = p.id } }
                GlowButton(title: "Sumar dispositivo", icon: "qrcode") { Windows.shared.showPair() }.padding(.top, 4)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("COLECCIONES").font(.system(size: 10, weight: .heavy, design: .rounded)).foregroundStyle(Theme.dim).tracking(1.2)
                    Spacer()
                    Button { newCollection(engine) } label: { Image(systemName: "plus.circle.fill").foregroundStyle(Theme.brand) }
                        .buttonStyle(.plain).help("Nueva colección")
                }
                if engine.collections.isEmpty {
                    Text("Carpetas con nombre que tus dispositivos pueden elegir al mandarte algo.")
                        .font(.system(size: 12, design: .rounded)).foregroundStyle(Theme.dim)
                }
                ForEach(engine.collections) { c in CollectionRow(collection: c, selected: filter == "col:\(c.id)") { filter = "col:\(c.id)" } }
            }

            Spacer()

            VStack(alignment: .leading, spacing: 8) {
                Button { NSWorkspace.shared.open(engine.inbox) } label: {
                    Label(engine.inbox.path.replacingOccurrences(of: NSHomeDirectory(), with: "~"), systemImage: "folder.fill")
                        .font(.system(size: 12, weight: .medium, design: .rounded)).foregroundStyle(Theme.dim).lineLimit(1)
                }.buttonStyle(.plain)
                Button { Windows.shared.showSettings() } label: {
                    Label("Ajustes", systemImage: "gearshape.fill").font(.system(size: 12, weight: .medium, design: .rounded)).foregroundStyle(Theme.dim)
                }.buttonStyle(.plain)
            }.padding(.bottom, 22)
        }
        .padding(.horizontal, 22)
    }

    var deck: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).font(.system(size: 30, weight: .heavy, design: .rounded)).lineLimit(1)
                Text("\(files.count)").font(.system(size: 15, weight: .bold, design: .rounded)).foregroundStyle(Theme.dim)
                Spacer()
                Picker("", selection: $filter) {
                    Text("Recibido").tag("in"); Text("Enviado").tag("out")
                    ForEach(engine.peers) { Text($0.name).tag($0.id) }
                    ForEach(engine.collections) { Text($0.name).tag("col:\($0.id)") }
                }.pickerStyle(.menu).frame(maxWidth: 220)
            }
            .padding(.top, 38)

            if let live = engine.transfers.first(where: \.isRunning) { LiveBanner(transfer: live).transition(.move(edge: .top).combined(with: .opacity)) }

            if files.isEmpty {
                EmptyDeck(hasPeers: !engine.peers.isEmpty)
            } else {
                ScrollView {
                    if let first = files.first {
                        HeroStack(batch: files.filter { $0.batch == first.batch })
                            .id(first.batch)
                            .transition(.asymmetric(insertion: .offset(y: -40).combined(with: .opacity), removal: .opacity))
                            .padding(.bottom, 8)
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 190, maximum: 260), spacing: 18)], alignment: .leading, spacing: 18,
                              pinnedViews: [.sectionHeaders]) {
                        ForEach(byDay(files), id: \.0) { day, items in
                            Section {
                                ForEach(items) { f in DeckCard(file: f).transition(.scale(scale: 0.7).combined(with: .opacity)) }
                            } header: {
                                Text(day).font(.system(size: 13, weight: .heavy, design: .rounded)).foregroundStyle(Theme.dim)
                                    .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 6)
                            }
                        }
                    }
                    .padding(.bottom, 30)
                    .animation(.spring(response: 0.5, dampingFraction: 0.75), value: files.map(\.id))
                }
                .scrollIndicators(.hidden)
            }
        }
        .padding(.horizontal, 30)
        .animation(.spring(response: 0.45, dampingFraction: 0.8), value: engine.transfers.first(where: \.isRunning)?.id)
        .onDrop(of: [.fileURL], isTargeted: $hoverDrop) { providers in
            loadURLs(providers) { urls in
                Windows.shared.sendModel.files = urls
                Windows.shared.showSend(to: engine.peers.contains(where: { $0.id == filter }) ? filter : nil)
            }
            return true
        }
        .overlay {
            if hoverDrop {
                RoundedRectangle(cornerRadius: 28).strokeBorder(Theme.brand, style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
                    .background(RoundedRectangle(cornerRadius: 28).fill(Theme.violet.opacity(0.08)))
                    .overlay(Text("Soltá para mandar").font(.system(size: 20, weight: .bold, design: .rounded)))
                    .padding(20)
            }
        }
    }
}

func byDay(_ files: [FileRecord]) -> [(String, [FileRecord])] {
    let cal = Calendar.current
    let f = DateFormatter(); f.locale = Locale(identifier: "es_AR"); f.setLocalizedDateFormatFromTemplate("EEEE d MMMM")
    var out: [(String, [FileRecord])] = []
    for file in files {
        let label = cal.isDateInToday(file.createdAt) ? "Hoy" : cal.isDateInYesterday(file.createdAt) ? "Ayer" : f.string(from: file.createdAt).capitalized
        if out.last?.0 == label { out[out.count - 1].1.append(file) } else { out.append((label, [file])) }
    }
    return out
}

/// The latest batch as a fanned deck of big cards that drop in when something arrives.
struct HeroStack: View {
    let batch: [FileRecord]
    @State private var landed = false
    @State private var fan = false

    var body: some View {
        let cards = Array(batch.prefix(5).reversed())
        HStack(spacing: 36) {
            ZStack {
                ForEach(Array(cards.enumerated()), id: \.element.id) { i, f in
                    let n = Double(cards.count), center = (n - 1) / 2, k = Double(i) - center
                    Thumb(url: f.url, mime: f.mime, corner: 22)
                        .frame(width: 190, height: 236)
                        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(.white.opacity(0.14), lineWidth: 1))
                        .shadow(color: .black.opacity(0.45), radius: 18, y: 12)
                        .rotationEffect(.degrees(landed ? k * (fan ? 11 : 6) : k * 25), anchor: .bottom)
                        .offset(x: landed ? k * (fan ? 70 : 26) : 0, y: landed ? abs(k) * (fan ? 10 : 4) : -320)
                        .opacity(landed ? 1 : 0)
                        .animation(.spring(response: 0.6, dampingFraction: 0.62).delay(landed ? Double(i) * 0.07 : 0), value: landed)
                        .animation(.spring(response: 0.35, dampingFraction: 0.7), value: fan)
                        .onTapGesture(count: 2) { NSWorkspace.shared.open(f.url) }
                }
            }
            .frame(width: 400, height: 280)
            .onHover { fan = $0 }

            if let top = batch.first {
                VStack(alignment: .leading, spacing: 10) {
                    Text(top.direction == "in" ? "LO ÚLTIMO QUE LLEGÓ" : "LO ÚLTIMO QUE MANDASTE")
                        .font(.system(size: 11, weight: .heavy, design: .rounded)).tracking(1.4).foregroundStyle(Theme.brand)
                    Text(summary).font(.system(size: 28, weight: .heavy, design: .rounded)).lineLimit(2)
                    HStack(spacing: 8) {
                        DeviceGlyph(platform: Engine.shared.peers.first { $0.id == top.peerId }?.platform ?? "android", online: true, size: 22)
                        Text("\(top.direction == "in" ? "De" : "A") \(top.peerName) · \(top.createdAt.relative) · \(formatBytes(batch.reduce(0) { $0 + $1.size }))")
                            .font(.system(size: 13, design: .rounded)).foregroundStyle(Theme.dim)
                    }
                    HStack(spacing: 8) {
                        GlowButton(title: "Mostrar en Finder", icon: "folder.fill") {
                            NSWorkspace.shared.activateFileViewerSelecting(batch.map(\.url).filter { FileManager.default.fileExists(atPath: $0.path) })
                        }
                        if batch.count == 1 { GlowButton(title: "Abrir", prominent: false) { NSWorkspace.shared.open(top.url) } }
                    }.padding(.top, 6)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(24)
        .background(RoundedRectangle(cornerRadius: 32, style: .continuous).fill(Theme.surface.opacity(0.55)))
        .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous).strokeBorder(Theme.faint))
        .onAppear { landed = true }
    }

    var summary: String {
        let kinds = Set(batch.map { Mime.kind($0.mime) })
        let n = batch.count
        if kinds == ["image"] { return n == 1 ? "Una foto" : "\(n) fotos" }
        if kinds == ["video"] { return n == 1 ? "Un video" : "\(n) videos" }
        if kinds.isSubset(of: ["image", "video"]) { return "\(n) fotos y videos" }
        return n == 1 ? batch[0].name : "\(n) archivos"
    }
}

@MainActor func newCollection(_ engine: Engine) {
    let p = NSOpenPanel(); p.canChooseDirectories = true; p.canChooseFiles = false; p.canCreateDirectories = true
    p.prompt = "Usar como colección"; p.message = "Elegí o creá la carpeta. El nombre de la colección es el de la carpeta (después lo podés cambiar)."
    NSApp.activate(ignoringOtherApps: true)
    if p.runModal() == .OK, let u = p.url { engine.addCollection(folder: u) }
}

struct CollectionRow: View {
    @EnvironmentObject var engine: Engine
    let collection: FileCollection
    var selected = false
    var onTap: () -> Void = {}
    @State private var hover = false
    @State private var renaming = false
    @State private var newName = ""

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "folder.fill").font(.system(size: 14)).foregroundStyle(Theme.brand)
                .frame(width: 30, height: 30).background(Circle().fill(Theme.surface2))
            VStack(alignment: .leading, spacing: 1) {
                Text(collection.name).font(.system(size: 13, weight: .semibold, design: .rounded)).lineLimit(1)
                Text(collection.path.replacingOccurrences(of: NSHomeDirectory(), with: "~"))
                    .font(.system(size: 10.5, design: .rounded)).foregroundStyle(Theme.dim).lineLimit(1).truncationMode(.middle)
            }
            Spacer()
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(selected ? Theme.surface2 : (hover ? Theme.surface : .clear)))
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .onHover { hover = $0 }
        .contextMenu { menu }
        .alert("Nombre de la colección", isPresented: $renaming) {
            TextField("Nombre", text: $newName)
            Button("Guardar") { engine.renameCollection(collection, to: newName) }
            Button("Cancelar", role: .cancel) {}
        }
    }

    @ViewBuilder var menu: some View {
        Button("Abrir carpeta") { NSWorkspace.shared.open(collection.url) }
        Button("Cambiar nombre…") { newName = collection.name; renaming = true }
        Button("Cambiar carpeta…") {
            let p = NSOpenPanel(); p.canChooseDirectories = true; p.canChooseFiles = false; p.canCreateDirectories = true
            if p.runModal() == .OK, let u = p.url { engine.moveCollection(collection, to: u) }
        }
        Divider()
        Button("Quitar colección", role: .destructive) { engine.removeCollection(collection) }
    }
}

struct PeerRow: View {
    @EnvironmentObject var engine: Engine
    let peer: Peer
    let selected: Bool
    let onTap: () -> Void
    @State private var hover = false
    @State private var renaming = false
    @State private var newName = ""

    var body: some View {
        HStack(spacing: 10) {
            DeviceGlyph(platform: peer.platform, online: engine.online.contains(peer.id), size: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(peer.name).font(.system(size: 13.5, weight: .semibold, design: .rounded)).lineLimit(1)
                Text(engine.online.contains(peer.id) ? "Conectado" : (peer.lastSeen.map { "Visto \($0.relative)" } ?? "Sin conexión"))
                    .font(.system(size: 11, design: .rounded)).foregroundStyle(engine.online.contains(peer.id) ? Theme.mint : Theme.dim)
            }
            Spacer()
            if hover {
                Button { Windows.shared.showSend(to: peer.id) } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.system(size: 18)).foregroundStyle(Theme.brand)
                }.buttonStyle(.plain).help("Mandarle archivos")
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(selected ? Theme.surface2 : (hover ? Theme.surface : .clear)))
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .onHover { hover = $0 }
        .contextMenu {
            Button("Mandarle archivos…") { Windows.shared.showSend(to: peer.id) }
            Button("Cambiar nombre…") { newName = peer.name; renaming = true }
            Button("Guardar lo suyo en otra carpeta…") {
                let p = NSOpenPanel(); p.canChooseDirectories = true; p.canChooseFiles = false; p.canCreateDirectories = true
                if p.runModal() == .OK, let u = p.url { engine.setFolder(u, for: peer) }
            }
            if peer.folder != nil { Button("Usar la carpeta general") { engine.setFolder(nil, for: peer) } }
            Divider()
            Button("Desvincular", role: .destructive) { engine.unpair(peer) }
        }
        .alert("Nombre del dispositivo", isPresented: $renaming) {
            TextField("Nombre", text: $newName)
            Button("Guardar") { engine.rename(peer, to: newName) }
            Button("Cancelar", role: .cancel) {}
        }
    }
}

struct DeckCard: View {
    let file: FileRecord
    @State private var hover = false
    var exists: Bool { FileManager.default.fileExists(atPath: file.path) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Thumb(url: file.url, mime: file.mime, corner: 0)
                .frame(height: 170)
                .clipped()
                .overlay(alignment: .topLeading) {
                    HStack(spacing: 4) {
                        Image(systemName: file.direction == "in" ? "arrow.down" : "arrow.up").font(.system(size: 9, weight: .heavy))
                        Text(file.peerName).font(.system(size: 10.5, weight: .bold, design: .rounded)).lineLimit(1)
                    }
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(.ultraThinMaterial, in: Capsule())
                    .padding(10)
                }
            VStack(alignment: .leading, spacing: 4) {
                Text(file.name).font(.system(size: 13, weight: .semibold, design: .rounded)).lineLimit(1)
                Text([formatBytes(file.size), file.collection, file.createdAt.relative].compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 11, design: .rounded)).foregroundStyle(Theme.dim).lineLimit(1)
            }.padding(12)
        }
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(hover ? AnyShapeStyle(Theme.brand) : AnyShapeStyle(Theme.faint), lineWidth: 1))
        .shadow(color: .black.opacity(0.35), radius: hover ? 22 : 10, y: hover ? 14 : 6)
        .scaleEffect(hover ? 1.025 : 1)
        .rotation3DEffect(.degrees(hover ? 2 : 0), axis: (x: 1, y: 0, z: 0))
        .opacity(exists ? 1 : 0.45)
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: hover)
        .onHover { hover = $0 }
        .onTapGesture(count: 2) { if exists { NSWorkspace.shared.open(file.url) } }
        .onDrag { NSItemProvider(contentsOf: file.url) ?? NSItemProvider() }
        .contextMenu {
            Button("Abrir") { NSWorkspace.shared.open(file.url) }
            Button("Mostrar en Finder") { NSWorkspace.shared.activateFileViewerSelecting([file.url]) }
            Button("Copiar ruta") { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(file.path, forType: .string) }
        }
        .help(exists ? "Doble clic para abrir" : "Este archivo ya no está en la carpeta")
    }
}

struct LiveBanner: View {
    let transfer: Transfer
    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                ForEach(Array(transfer.items.prefix(3).enumerated()), id: \.offset) { i, it in
                    Thumb(url: it.finished ? it.url : nil, mime: it.mime, corner: 10)
                        .frame(width: 44, height: 44)
                        .rotationEffect(.degrees(Double(i - 1) * 8))
                        .offset(x: CGFloat(i) * 6)
                }
            }.frame(width: 64)
            VStack(alignment: .leading, spacing: 6) {
                Text(transfer.direction == .incoming ? "Llegando de \(transfer.peerName)" : "Mandando a \(transfer.peerName)")
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Theme.faint)
                        Capsule().fill(Theme.brand).frame(width: max(8, g.size.width * transfer.progress))
                    }
                }.frame(height: 5)
            }
            Text("\(transfer.items.filter(\.finished).count)/\(transfer.items.count)").font(.system(size: 13, weight: .bold, design: .rounded).monospacedDigit()).foregroundStyle(Theme.dim)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Theme.surface))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Theme.brand.opacity(0.6), lineWidth: 1))
    }
}

struct EmptyDeck: View {
    let hasPeers: Bool
    @State private var float = false
    var body: some View {
        VStack(spacing: 18) {
            Spacer()
            ZStack {
                ForEach(0..<3) { i in
                    RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Theme.surface)
                        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Theme.faint))
                        .frame(width: 130, height: 160)
                        .rotationEffect(.degrees(Double(i - 1) * 10))
                        .offset(x: CGFloat(i - 1) * 34, y: float ? -4 : 4)
                }
                PasalaTile(size: 76).shadow(color: Theme.violet.opacity(0.5), radius: 18)
                    .offset(y: float ? -10 : 6).rotationEffect(.degrees(float ? -6 : 4))
            }
            .frame(height: 200)
            Text(hasPeers ? "Todavía no llegó nada" : "Vinculá tu celular").font(.system(size: 20, weight: .bold, design: .rounded))
            Text(hasPeers ? "Compartí algo desde el celular y elegí Pasala. Lo vas a ver caer acá." : "Abrí Pasala en el celular y escaneá el código de esta Mac.")
                .font(.system(size: 14, design: .rounded)).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
            if !hasPeers { GlowButton(title: "Mostrar código", icon: "qrcode") { Windows.shared.showPair() } }
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .onAppear { withAnimation(.easeInOut(duration: 2.4).repeatForever()) { float = true } }
    }
}

// MARK: - Pairing

struct PairView: View {
    @EnvironmentObject var engine: Engine
    @State private var now = Date()
    @State private var pasted = ""
    @State private var pairError: String?
    @State private var working = false
    let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Aurora()
            VStack(spacing: 18) {
                Text("Sumar dispositivo").font(.system(size: 26, weight: .heavy, design: .rounded)).padding(.top, 34)
                Text("Abrí Pasala en el celular, tocá **Sumar dispositivo → Escanear** y apuntá acá.")
                    .font(.system(size: 13.5, design: .rounded)).foregroundStyle(Theme.dim).multilineTextAlignment(.center).padding(.horizontal, 40)

                ZStack {
                    RoundedRectangle(cornerRadius: 30, style: .continuous).fill(Theme.brandAngular).frame(width: 300, height: 300)
                        .blur(radius: 18).opacity(0.55)
                    RoundedRectangle(cornerRadius: 28, style: .continuous).fill(.white).frame(width: 280, height: 280)
                    if let img = qr(engine.pairPayload) {
                        Image(nsImage: img).interpolation(.none).resizable().frame(width: 244, height: 244)
                    }
                    PasalaTile(size: 50).overlay(RoundedRectangle(cornerRadius: 13.5, style: .continuous).stroke(.white, lineWidth: 4))
                }
                .id(engine.pairCode)
                .transition(.scale.combined(with: .opacity))

                let left = max(0, Int(engine.pairExpires.timeIntervalSince(now)))
                HStack(spacing: 8) {
                    Text("El código cambia en \(left / 60):\(String(format: "%02d", left % 60))").font(.system(size: 12, design: .rounded).monospacedDigit()).foregroundStyle(Theme.dim)
                    Button("Nuevo código") { withAnimation { engine.newPairCode() } }.buttonStyle(.link).font(.system(size: 12, design: .rounded))
                }

                VStack(spacing: 8) {
                    Text("¿Es otra compu? Pegá acá su código").font(.system(size: 12, weight: .semibold, design: .rounded)).foregroundStyle(Theme.dim)
                    HStack {
                        TextField("pasala://…", text: $pasted).textFieldStyle(.roundedBorder).font(.system(size: 12, design: .monospaced))
                        Button("Vincular") {
                            working = true
                            Task { pairError = await engine.pair(withPayload: pasted); working = false; if pairError == nil { pasted = "" } }
                        }.disabled(pasted.isEmpty || working)
                        Button { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(engine.pairPayload, forType: .string) } label: {
                            Image(systemName: "doc.on.doc")
                        }.help("Copiar el código de esta Mac")
                    }
                    if let pairError { Text(pairError).font(.system(size: 12, design: .rounded)).foregroundStyle(Theme.orange) }
                }.padding(.horizontal, 44)
                Spacer()
            }
        }
        .preferredColorScheme(.dark)
        .onReceive(timer) { now = $0 }
    }

    func qr(_ s: String) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8); f.correctionLevel = "Q"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        let rep = NSCIImageRep(ciImage: out)
        let img = NSImage(size: rep.size); img.addRepresentation(rep)
        return img
    }
}

// MARK: - Send

@MainActor
final class SendModel: ObservableObject {
    @Published var target: String?
    @Published var collection: String?      // nil = Ninguna
    @Published var files: [URL] = []
    @Published var sending = false
    @Published var message: String?
}

struct SendView: View {
    @EnvironmentObject var engine: Engine
    @ObservedObject var model: SendModel
    @State private var targeted = false

    var body: some View {
        ZStack {
            Aurora()
            VStack(alignment: .leading, spacing: 18) {
                Text("Enviar").font(.system(size: 26, weight: .heavy, design: .rounded)).padding(.top, 34)
                if engine.peers.isEmpty {
                    Text("Primero vinculá un dispositivo.").foregroundStyle(Theme.dim)
                    GlowButton(title: "Sumar dispositivo", icon: "qrcode") { Windows.shared.showPair() }
                    Spacer()
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(engine.peers) { p in
                                Button { model.target = p.id } label: {
                                    HStack(spacing: 8) {
                                        DeviceGlyph(platform: p.platform, online: engine.online.contains(p.id), size: 26)
                                        Text(p.name).font(.system(size: 13, weight: .semibold, design: .rounded))
                                    }
                                    .padding(.horizontal, 12).padding(.vertical, 8)
                                    .background(Capsule().fill(model.target == p.id ? Theme.violet.opacity(0.3) : Theme.surface))
                                    .overlay(Capsule().strokeBorder(model.target == p.id ? AnyShapeStyle(Theme.brand) : AnyShapeStyle(Theme.faint), lineWidth: 1.5))
                                }.buttonStyle(.plain)
                            }
                        }
                    }

                    if let t = model.target, let cols = engine.peerCollections[t], !cols.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("¿En qué colección?").font(.system(size: 12, weight: .semibold, design: .rounded)).foregroundStyle(Theme.dim)
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    chip("Ninguna", icon: "tray", selected: model.collection == nil) { model.collection = nil }
                                    ForEach(cols) { c in chip(c.name, icon: "folder.fill", selected: model.collection == c.id) { model.collection = c.id } }
                                }
                            }
                        }
                    }

                    dropZone

                    if !model.files.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 10) {
                                ForEach(model.files, id: \.self) { u in
                                    Thumb(url: u, mime: Mime.of(u), corner: 14).frame(width: 74, height: 74)
                                        .overlay(alignment: .topTrailing) {
                                            Button { model.files.removeAll { $0 == u } } label: {
                                                Image(systemName: "xmark.circle.fill").foregroundStyle(.white, .black.opacity(0.6))
                                            }.buttonStyle(.plain).padding(4)
                                        }
                                        .help(u.lastPathComponent)
                                }
                            }
                        }.frame(height: 78)
                    }

                    HStack {
                        if let t = engine.transfers.first(where: { $0.direction == .outgoing }), model.sending || t.isRunning {
                            ProgressRing(progress: t.progress, size: 22)
                            Text("Mandando… \(Int(t.progress * 100))%").font(.system(size: 13, design: .rounded)).foregroundStyle(Theme.dim)
                        } else if let m = model.message {
                            Text(m).font(.system(size: 13, design: .rounded)).foregroundStyle(m.hasPrefix("Listo") ? Theme.mint : Theme.orange)
                        }
                        Spacer()
                        GlowButton(title: model.files.isEmpty ? "Elegir archivos" : "Enviar \(model.files.count)", icon: "arrow.up.circle.fill") {
                            if model.files.isEmpty { pick() } else { send() }
                        }
                        .disabled(model.sending || model.target == nil)
                    }
                    .padding(.bottom, 24)
                }
            }
            .padding(.horizontal, 30)
        }
        .preferredColorScheme(.dark)
        .task(id: model.target) {
            guard let id = model.target, let p = engine.peers.first(where: { $0.id == id }) else { return }
            _ = await engine.resolve(p)
            if let c = model.collection, !(engine.peerCollections[id] ?? []).contains(where: { $0.id == c }) { model.collection = nil }
        }
    }

    func chip(_ title: String, icon: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(.system(size: 12.5, weight: .semibold, design: .rounded))
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(Capsule().fill(selected ? Theme.violet.opacity(0.3) : Theme.surface))
                .overlay(Capsule().strokeBorder(selected ? AnyShapeStyle(Theme.brand) : AnyShapeStyle(Theme.faint), lineWidth: 1.5))
        }.buttonStyle(.plain)
    }

    var dropZone: some View {
        VStack(spacing: 10) {
            Image(systemName: "tray.and.arrow.up.fill").font(.system(size: 34, weight: .semibold)).foregroundStyle(Theme.brand)
                .scaleEffect(targeted ? 1.15 : 1)
            Text(targeted ? "Soltalos" : "Arrastrá archivos acá").font(.system(size: 16, weight: .bold, design: .rounded))
            Button("o elegilos del disco") { pick() }.buttonStyle(.link).font(.system(size: 12.5, design: .rounded))
        }
        .frame(maxWidth: .infinity, minHeight: 200)
        .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(targeted ? Theme.violet.opacity(0.18) : Theme.surface.opacity(0.7)))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous)
            .strokeBorder(targeted ? AnyShapeStyle(Theme.brand) : AnyShapeStyle(Color.white.opacity(0.18)), style: StrokeStyle(lineWidth: 2, dash: [8, 7])))
        .animation(.spring(response: 0.3, dampingFraction: 0.65), value: targeted)
        .onDrop(of: [.fileURL], isTargeted: $targeted) { providers in
            loadURLs(providers) { urls in model.files.append(contentsOf: urls.filter { !model.files.contains($0) }) }
            return true
        }
    }

    func pick() {
        let p = NSOpenPanel(); p.allowsMultipleSelection = true; p.canChooseDirectories = false
        if p.runModal() == .OK { model.files.append(contentsOf: p.urls) }
    }

    func send() {
        guard let id = model.target, let peer = engine.peers.first(where: { $0.id == id }) else { return }
        let urls = model.files
        model.sending = true; model.message = nil
        Task {
            let err = await engine.send(urls, to: peer, collection: model.collection)
            model.sending = false
            if let err { model.message = err } else { model.message = "Listo, \(peer.name) ya los tiene."; model.files = [] }
        }
    }
}

// MARK: - Settings

struct SettingsView: View {
    @EnvironmentObject var engine: Engine
    @State private var name = ""
    var body: some View {
        ZStack {
            Aurora()
            VStack(alignment: .leading, spacing: 20) {
                Text("Ajustes").font(.system(size: 26, weight: .heavy, design: .rounded)).padding(.top, 34)
                VStack(alignment: .leading, spacing: 6) {
                    Text("Nombre de esta Mac").font(.system(size: 12, weight: .semibold, design: .rounded)).foregroundStyle(Theme.dim)
                    TextField("", text: $name, onCommit: { engine.setDeviceName(name) }).textFieldStyle(.roundedBorder)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Dónde guardo lo que llega").font(.system(size: 12, weight: .semibold, design: .rounded)).foregroundStyle(Theme.dim)
                    HStack {
                        Text(engine.inbox.path.replacingOccurrences(of: NSHomeDirectory(), with: "~")).font(.system(size: 13, design: .rounded)).lineLimit(1)
                        Spacer()
                        Button("Cambiar…") {
                            let p = NSOpenPanel(); p.canChooseDirectories = true; p.canChooseFiles = false; p.canCreateDirectories = true
                            if p.runModal() == .OK, let u = p.url { engine.setInbox(u) }
                        }
                    }
                    Text("Cada dispositivo puede tener su propia carpeta: clic derecho sobre él en la ventana principal.")
                        .font(.system(size: 11, design: .rounded)).foregroundStyle(Theme.dim)
                }
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("Colecciones").font(.system(size: 12, weight: .semibold, design: .rounded)).foregroundStyle(Theme.dim)
                        Spacer()
                        Button("Nueva…") { newCollection(engine) }
                    }
                    Text("Cuando un dispositivo te manda algo, elige en cuál dejarlo. Si elige \"Ninguna\", va a la carpeta de arriba.")
                        .font(.system(size: 11, design: .rounded)).foregroundStyle(Theme.dim)
                    ForEach(engine.collections) { c in CollectionRow(collection: c) }
                    if !engine.collections.isEmpty {
                        Text("Clic derecho sobre una colección para cambiarle el nombre, la carpeta o quitarla.")
                            .font(.system(size: 11, design: .rounded)).foregroundStyle(Theme.dim)
                    }
                }
                Toggle("Preguntarme antes de recibir", isOn: Binding(get: { engine.askBeforeReceiving }, set: { engine.setAsk($0) }))
                    .toggleStyle(.switch)
                DisclosureGroup("Detalles de conexión") {
                    Text("Direcciones: \(engine.addrs.joined(separator: ", ")) · puerto \(engine.port)")
                        .font(.system(size: 11, design: .monospaced)).foregroundStyle(Theme.dim).textSelection(.enabled)
                }.font(.system(size: 12, design: .rounded))
                Spacer()
            }
            .padding(.horizontal, 30)
        }
        .preferredColorScheme(.dark)
        .onAppear { name = engine.deviceName }
    }
}
