import SwiftUI
import AppKit
import Combine
import UniformTypeIdentifiers
import PasalaCore

// MARK: - Shape

struct NotchShape: Shape {
    var ear: CGFloat
    var bottom: CGFloat
    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { .init(ear, bottom) }
        set { ear = newValue.first; bottom = newValue.second }
    }
    func path(in r: CGRect) -> Path {
        var p = Path()
        let w = r.width, h = r.height, e = ear, b = min(bottom, (h - e) / 1.2)
        p.move(to: .zero)
        p.addQuadCurve(to: CGPoint(x: e, y: e), control: CGPoint(x: e, y: 0))
        p.addLine(to: CGPoint(x: e, y: h - b))
        p.addQuadCurve(to: CGPoint(x: e + b, y: h), control: CGPoint(x: e, y: h))
        p.addLine(to: CGPoint(x: w - e - b, y: h))
        p.addQuadCurve(to: CGPoint(x: w - e, y: h - b), control: CGPoint(x: w - e, y: h))
        p.addLine(to: CGPoint(x: w - e, y: e))
        p.addQuadCurve(to: CGPoint(x: w, y: 0), control: CGPoint(x: w - e, y: 0))
        p.closeSubpath()
        return p
    }
}

// MARK: - Model

@MainActor
final class NotchModel: ObservableObject {
    enum Mode: Equatable { case idle, open, transfer, done, drop, offer, paired }

    @Published var hovering = false
    @Published var dragging = false
    @Published var dropTarget: String?
    @Published var showDoneUntil: Date?
    @Published var pairedToastUntil: Date?
    @Published var pinnedOpen = false
    @Published private(set) var mode: Mode = .idle

    let engine = Engine.shared
    private var bag = Set<AnyCancellable>()
    private var lastFinished = Set<String>()

    init() {
        if ProcessInfo.processInfo.environment["PASALA_ISLAND"] == "open" { pinnedOpen = true }
        engine.$transfers.sink { [weak self] ts in
            guard let self else { return }
            let finished = Set(ts.filter { !$0.isRunning }.map(\.id))
            if !finished.subtracting(self.lastFinished).isEmpty {
                self.showDoneUntil = .now.addingTimeInterval(5)
                DispatchQueue.main.asyncAfter(deadline: .now() + 5.1) { self.recompute() }
            }
            self.lastFinished = finished
            DispatchQueue.main.async { self.recompute() }
        }.store(in: &bag)
        engine.$offers.sink { [weak self] _ in DispatchQueue.main.async { self?.recompute() } }.store(in: &bag)
        engine.$justPaired.compactMap { $0 }.sink { [weak self] _ in
            self?.pairedToastUntil = .now.addingTimeInterval(4)
            DispatchQueue.main.async { self?.recompute() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 4.1) { self?.recompute() }
        }.store(in: &bag)
        Publishers.Merge3($hovering.map { _ in () }, $dragging.map { _ in () }, $pinnedOpen.map { _ in () })
            .sink { [weak self] in DispatchQueue.main.async { self?.recompute() } }.store(in: &bag)
    }

    func recompute() {
        let m: Mode
        if dragging { m = .drop }
        else if !engine.offers.isEmpty { m = .offer }
        else if engine.transfers.contains(where: \.isRunning) { m = .transfer }
        else if let u = pairedToastUntil, u > .now { m = .paired }
        else if hovering || pinnedOpen { m = .open }
        else if let u = showDoneUntil, u > .now { m = .done }
        else { m = .idle }
        if m != mode { withAnimation(.spring(response: 0.42, dampingFraction: 0.78)) { mode = m } }
    }

    var size: CGSize {
        switch mode {
        case .idle: return CGSize(width: 210, height: 34)
        case .open: return CGSize(width: 600, height: 206)
        case .transfer: return CGSize(width: 560, height: 176)
        case .done: return CGSize(width: 500, height: 150)
        case .drop: return CGSize(width: 600, height: 196)
        case .offer: return CGSize(width: 520, height: 150)
        case .paired: return CGSize(width: 440, height: 96)
        }
    }
}

// MARK: - Panel

final class NotchPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

@MainActor
final class NotchController {
    let model = NotchModel()
    private var panel: NotchPanel!
    private var monitors: [Any] = []
    private var dragChangeCount = NSPasteboard(name: .drag).changeCount
    private var leaveWork: DispatchWorkItem?
    static let canvas = CGSize(width: 760, height: 330)

    func install() {
        panel = NotchPanel(contentRect: NSRect(origin: .zero, size: Self.canvas),
                           styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.mainMenuWindow)) + 3)
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        panel.isMovable = false
        panel.ignoresMouseEvents = true
        let host = NSHostingView(rootView: NotchRoot(model: model).environmentObject(Engine.shared))
        host.frame = NSRect(origin: .zero, size: Self.canvas)
        panel.contentView = host
        position()
        panel.orderFrontRegardless()

        NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.position() }
        }
        let mask: NSEvent.EventTypeMask = [.mouseMoved, .leftMouseDragged, .leftMouseUp]
        monitors.append(NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] e in
            Task { @MainActor in self?.handle(e) }
        } as Any)
        monitors.append(NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] e in
            Task { @MainActor in self?.handle(e) }
            return e
        } as Any)
    }

    private var screen: NSScreen { NSScreen.screens.first ?? NSScreen.main! }

    private func position() {
        let f = screen.frame
        panel.setFrameOrigin(NSPoint(x: f.midX - Self.canvas.width / 2, y: f.maxY - Self.canvas.height))
    }

    /// Island rect in screen coordinates.
    private var islandRect: NSRect {
        let f = screen.frame, s = model.size
        return NSRect(x: f.midX - s.width / 2, y: f.maxY - s.height, width: s.width, height: s.height)
    }

    private func handle(_ e: NSEvent) {
        let p = NSEvent.mouseLocation
        switch e.type {
        case .leftMouseDragged:
            let pb = NSPasteboard(name: .drag)
            if pb.changeCount != dragChangeCount,
               pb.canReadObject(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) {
                let near = islandRect.insetBy(dx: -140, dy: -120).contains(p)
                if near && !model.dragging && !Engine.shared.peers.isEmpty { model.dragging = true }
                if model.dragging { panel.ignoresMouseEvents = false }
            }
        case .leftMouseUp:
            dragChangeCount = NSPasteboard(name: .drag).changeCount
            if model.dragging {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
                    self?.model.dragging = false; self?.model.dropTarget = nil; self?.updateHover()
                }
            }
        default:
            updateHover()
        }
    }

    // The panel only takes the mouse over the island. See HISTORY.md#island-mouse
    private func updateHover() {
        let inside = islandRect.insetBy(dx: -4, dy: -4).contains(NSEvent.mouseLocation)
        panel.ignoresMouseEvents = !(inside || model.dragging || model.mode == .offer)
        if inside {
            leaveWork?.cancel(); leaveWork = nil
            if !model.hovering {
                let w = DispatchWorkItem { [weak self] in
                    guard let self, self.islandRect.contains(NSEvent.mouseLocation) else { return }
                    self.model.hovering = true
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.12, execute: w)
            }
        } else if model.hovering && leaveWork == nil {
            let w = DispatchWorkItem { [weak self] in self?.model.hovering = false; self?.leaveWork = nil }
            leaveWork = w
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: w)
        }
    }
}

// MARK: - Views

struct NotchRoot: View {
    @ObservedObject var model: NotchModel
    @EnvironmentObject var engine: Engine

    var body: some View {
        VStack(spacing: 0) {
            island
            Spacer(minLength: 0)
        }
        .frame(width: NotchController.canvas.width, height: NotchController.canvas.height)
    }

    var expanded: Bool { model.mode != .idle }

    var island: some View {
        let s = model.size
        return ZStack(alignment: .top) {
            NotchShape(ear: expanded ? 14 : 8, bottom: expanded ? 30 : 13)
                .fill(Color.black)
                .onTapGesture(count: 2) { Windows.shared.showMain() }
                .shadow(color: expanded ? Theme.violet.opacity(0.35) : .clear, radius: 24, y: 10)
            NotchShape(ear: expanded ? 14 : 8, bottom: expanded ? 30 : 13)
                .stroke(LinearGradient(colors: [.clear, .white.opacity(expanded ? 0.12 : 0.05)], startPoint: .top, endPoint: .bottom), lineWidth: 1)
            content
                .padding(.horizontal, expanded ? 30 : 20)
                .padding(.top, expanded ? 12 : 0)
                .frame(width: s.width, height: s.height, alignment: .top)
                .clipShape(NotchShape(ear: expanded ? 14 : 8, bottom: expanded ? 30 : 13))
        }
        .frame(width: s.width, height: s.height)
        .contentShape(NotchShape(ear: 14, bottom: 30))
    }

    @ViewBuilder var content: some View {
        switch model.mode {
        case .idle: IdleBar()
        case .open: OpenPanel(model: model)
        case .transfer, .done: TransferPanel(done: model.mode == .done)
        case .drop: DropPanel(model: model)
        case .offer: OfferPanel()
        case .paired: PairedToast()
        }
    }
}

struct IdleBar: View {
    @EnvironmentObject var engine: Engine
    @State private var pulse = false
    var body: some View {
        HStack {
            PlaneGlyph(size: 11)
            Spacer()
            let n = engine.peers.filter { engine.online.contains($0.id) }.count
            HStack(spacing: 4) {
                Circle().fill(n > 0 ? Theme.mint : .gray).frame(width: 6, height: 6)
                    .shadow(color: n > 0 ? Theme.mint : .clear, radius: pulse ? 5 : 1)
                if n > 0 { Text("\(n)").font(.system(size: 10, weight: .bold, design: .rounded)).foregroundStyle(.white.opacity(0.7)) }
            }
        }
        .frame(height: 34)
        .onAppear { withAnimation(.easeInOut(duration: 1.6).repeatForever()) { pulse = true } }
    }
}

struct IslandHeader: View {
    let title: String
    var subtitle: String? = nil
    var body: some View {
        HStack(spacing: 8) {
            PlaneGlyph(size: 13)
            Text(title).font(.system(size: 14, weight: .bold, design: .rounded)).foregroundStyle(.white)
            if let subtitle { Text(subtitle).font(.system(size: 12, weight: .medium, design: .rounded)).foregroundStyle(Theme.dim) }
            Spacer()
        }
    }
}

struct OpenPanel: View {
    @ObservedObject var model: NotchModel
    @EnvironmentObject var engine: Engine

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                IslandHeader(title: "Pasala", subtitle: engine.peers.isEmpty ? "Sin dispositivos todavía" : "Arrastrá archivos acá · doble clic abre Pasala")
                Button { Windows.shared.showMain() } label: {
                    Image(systemName: "rectangle.expand.vertical").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.dim)
                }.buttonStyle(.plain).help("Abrir Pasala")
            }
            HStack(alignment: .top, spacing: 18) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("DISPOSITIVOS").font(.system(size: 9.5, weight: .heavy, design: .rounded)).foregroundStyle(Theme.dim).tracking(1)
                    if engine.peers.isEmpty {
                        GlowButton(title: "Sumar dispositivo", icon: "qrcode") { Windows.shared.showPair() }
                    } else {
                        ForEach(engine.peers.prefix(3)) { p in
                            Button { Windows.shared.showSend(to: p.id) } label: {
                                HStack(spacing: 8) {
                                    DeviceGlyph(platform: p.platform, online: engine.online.contains(p.id), size: 26)
                                    Text(p.name).font(.system(size: 12, weight: .semibold, design: .rounded)).foregroundStyle(.white).lineLimit(1)
                                }
                            }.buttonStyle(.plain)
                        }
                    }
                }.frame(width: 170, alignment: .leading)

                VStack(alignment: .leading, spacing: 8) {
                    Text("LO ÚLTIMO QUE LLEGÓ").font(.system(size: 9.5, weight: .heavy, design: .rounded)).foregroundStyle(Theme.dim).tracking(1)
                    let items = engine.recent.filter { $0.direction == "in" }.prefix(5)
                    if items.isEmpty {
                        Text("Lo que te manden va a aparecer acá.").font(.system(size: 12, design: .rounded)).foregroundStyle(Theme.dim)
                            .frame(height: 64)
                    } else {
                        HStack(spacing: 8) {
                            ForEach(Array(items.enumerated()), id: \.element.id) { i, f in
                                Button { NSWorkspace.shared.open(f.url) } label: {
                                    Thumb(url: f.url, mime: f.mime, corner: 12).frame(width: 64, height: 64)
                                }
                                .buttonStyle(.plain)
                                .help("\(f.name) · \(f.peerName)")
                                .onDrag { NSItemProvider(contentsOf: f.url) ?? NSItemProvider() }
                            }
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                GlowButton(title: "Enviar archivos", icon: "arrow.up.circle.fill") { Windows.shared.showSend(to: nil) }
                GlowButton(title: "Abrir carpeta", icon: "folder.fill", prominent: false) { NSWorkspace.shared.open(engine.inbox) }
                GlowButton(title: "Sumar", icon: "qrcode", prominent: false) { Windows.shared.showPair() }
                Spacer()
            }
        }
    }
}

struct TransferPanel: View {
    let done: Bool
    @EnvironmentObject var engine: Engine

    var transfer: Transfer? { engine.transfers.first(where: \.isRunning) ?? engine.transfers.first }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let t = transfer {
                let finished = t.items.filter(\.finished).count
                HStack {
                    IslandHeader(title: headline(t), subtitle: "\(finished) de \(t.items.count) · \(formatBytes(t.totalBytes))")
                    if !t.isRunning, t.direction == .incoming {
                        GlowButton(title: "Mostrar", icon: "folder.fill", prominent: false) {
                            let urls = t.items.compactMap(\.url)
                            if urls.isEmpty { NSWorkspace.shared.open(engine.inbox) } else { NSWorkspace.shared.activateFileViewerSelecting(urls) }
                        }
                    }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(Array(t.items.enumerated()), id: \.element.id) { i, item in
                            FlyingCard(item: item, index: i)
                                .transition(.asymmetric(
                                    insertion: .scale(scale: 0.3, anchor: .top).combined(with: .offset(y: -50)).combined(with: .opacity),
                                    removal: .opacity))
                        }
                    }
                    .padding(.vertical, 4)
                    .animation(.spring(response: 0.5, dampingFraction: 0.7), value: t.items.map(\.finished))
                }
                .frame(height: 78)
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Theme.faint)
                        Capsule().fill(statusFill(t)).frame(width: max(8, g.size.width * (t.isRunning ? t.progress : 1)))
                            .animation(.easeOut(duration: 0.3), value: t.progress)
                    }
                }.frame(height: 4)
            }
        }
    }

    func headline(_ t: Transfer) -> String {
        switch (t.direction, t.state) {
        case (.incoming, .running): return "Llegando de \(t.peerName)" + (t.collection.map { " a \($0)" } ?? "")
        case (.incoming, .done): return "Llegó de \(t.peerName)" + (t.collection.map { " a \($0)" } ?? "")
        case (.outgoing, .running): return "Mandando a \(t.peerName)"
        case (.outgoing, .done): return "Listo, ya lo tiene \(t.peerName)"
        case (_, .failed(let m)): return m
        }
    }

    func statusFill(_ t: Transfer) -> AnyShapeStyle {
        if case .failed = t.state { return AnyShapeStyle(Color.red.opacity(0.8)) }
        if case .done = t.state { return AnyShapeStyle(Theme.mint) }
        return AnyShapeStyle(Theme.brand)
    }
}

struct FlyingCard: View {
    let item: TransferItem
    let index: Int
    @State private var landed = false
    var body: some View {
        ZStack {
            Thumb(url: item.finished ? item.url : nil, mime: item.mime, corner: 14)
                .frame(width: 70, height: 70)
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(item.finished ? Theme.mint.opacity(0.6) : Theme.faint, lineWidth: 1))
            if !item.finished && !item.failed {
                Color.black.opacity(0.35).clipShape(RoundedRectangle(cornerRadius: 14))
                ProgressRing(progress: item.progress, size: 26)
            }
            if item.failed { Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red) }
        }
        .rotationEffect(.degrees(landed ? 0 : Double((index % 3) - 1) * 9))
        .offset(y: landed ? 0 : -26)
        .scaleEffect(landed ? 1 : 0.6)
        .opacity(landed ? 1 : 0)
        .help(item.name)
        .onAppear {
            withAnimation(.spring(response: 0.55, dampingFraction: 0.62).delay(Double(index) * 0.06)) { landed = true }
        }
    }
}

struct DropPanel: View {
    @ObservedObject var model: NotchModel
    @EnvironmentObject var engine: Engine

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            IslandHeader(title: "Soltalo en un dispositivo")
            HStack(spacing: 12) {
                ForEach(engine.peers.prefix(4)) { p in
                    DropTile(peer: p, online: engine.online.contains(p.id), targeted: model.dropTarget == p.id)
                        .onDrop(of: [.fileURL], isTargeted: Binding(get: { model.dropTarget == p.id },
                                                                    set: { model.dropTarget = $0 ? p.id : (model.dropTarget == p.id ? nil : model.dropTarget) })) { providers in
                            loadURLs(providers) { urls in
                                guard !urls.isEmpty else { return }
                                Task { await engine.send(urls, to: p) }
                            }
                            model.dragging = false
                            return true
                        }
                }
                Spacer(minLength: 0)
            }
        }
    }
}

struct DropTile: View {
    let peer: Peer
    let online: Bool
    let targeted: Bool
    var body: some View {
        VStack(spacing: 8) {
            DeviceGlyph(platform: peer.platform, online: online, size: 40)
            Text(peer.name).font(.system(size: 12, weight: .semibold, design: .rounded)).foregroundStyle(.white).lineLimit(1)
        }
        .frame(width: 124, height: 106)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(targeted ? Theme.violet.opacity(0.25) : Theme.surface))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
            .strokeBorder(targeted ? AnyShapeStyle(Theme.brand) : AnyShapeStyle(Theme.faint), style: StrokeStyle(lineWidth: targeted ? 2 : 1, dash: targeted ? [] : [5, 4])))
        .scaleEffect(targeted ? 1.07 : 1)
        .animation(.spring(response: 0.3, dampingFraction: 0.6), value: targeted)
    }
}

struct OfferPanel: View {
    @EnvironmentObject var engine: Engine
    var body: some View {
        if let o = engine.offers.first {
            VStack(alignment: .leading, spacing: 14) {
                IslandHeader(title: "\(o.peerName) te quiere mandar \(o.files.count == 1 ? "un archivo" : "\(o.files.count) archivos")",
                             subtitle: formatBytes(o.totalBytes))
                Text(o.files.prefix(3).map(\.name).joined(separator: " · ") + (o.files.count > 3 ? " …" : ""))
                    .font(.system(size: 12, design: .rounded)).foregroundStyle(Theme.dim).lineLimit(1)
                HStack(spacing: 8) {
                    GlowButton(title: "Aceptar", icon: "checkmark") { engine.answerOffer(o.id, accept: true) }
                    GlowButton(title: "Rechazar", prominent: false) { engine.answerOffer(o.id, accept: false) }
                    Spacer()
                }
            }
        }
    }
}

struct PairedToast: View {
    @EnvironmentObject var engine: Engine
    var body: some View {
        HStack(spacing: 12) {
            DeviceGlyph(platform: engine.justPaired?.platform ?? "android", online: true, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text("¡Vinculado!").font(.system(size: 14, weight: .bold, design: .rounded)).foregroundStyle(.white)
                Text("Ya podés pasarle archivos a \(engine.justPaired?.name ?? "")").font(.system(size: 12, design: .rounded)).foregroundStyle(Theme.dim)
            }
            Spacer()
        }.padding(.top, 6)
    }
}

func loadURLs(_ providers: [NSItemProvider], _ done: @escaping ([URL]) -> Void) {
    var urls: [URL] = []
    let g = DispatchGroup()
    for p in providers where p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
        g.enter()
        _ = p.loadObject(ofClass: URL.self) { u, _ in
            if let u { DispatchQueue.main.async { urls.append(u) } }
            g.leave()
        }
    }
    g.notify(queue: .main) { DispatchQueue.main.async { done(urls) } }
}
