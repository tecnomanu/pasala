import SwiftUI
import AppKit
import Combine
import UniformTypeIdentifiers
import PasalaCore
import PasalaKit

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
    enum Mode: Equatable { case idle, open, transfer, done, drop, offer, paired, pickText, textArrived }
    static let shared = NotchModel()
    static var current: NotchModel? { shared }
    weak var controller: NotchController?
    /// Text waiting for the person to pick a device (from the Services menu, the island or the menu bar).
    @Published var pendingText: String?
    @Published var textShownUntil: Date?
    /// The person can hide the island and keep using the menu bar icon and the window.
    @Published var islandHidden = Engine.shared.db.setting("island_hidden") == "1" {
        didSet { engine.db.setSetting("island_hidden", islandHidden ? "1" : "0") }
    }
    /// Even when hidden, the island shows up for things that need an answer.
    var mustShow: Bool { mode == .pickText || mode == .offer }

    @Published var hovering = false
    @Published var dragging = false
    @Published var dropTarget: String?
    @Published var showDoneUntil: Date?
    @Published var pairedToastUntil: Date?
    @Published var pinnedOpen = false
    @Published private(set) var mode: Mode = .idle
    /// What the closed island says: what just arrived (for 10 minutes) or how many devices are connected.
    @Published private(set) var idleInfo: IdleInfo?
    /// The island's "Lo último que llegó" only shows files received after this.
    @Published private(set) var clearedAt: Date

    struct IdleInfo: Equatable { let text: String; let arrived: Bool; let url: URL?; let mime: String }
    static let freshFor: TimeInterval = 10 * 60

    let engine = Engine.shared
    private var bag = Set<AnyCancellable>()
    private var lastFinished = Set<String>()

    init() {
        clearedAt = Date(timeIntervalSince1970: Double(Engine.shared.db.setting("island_cleared_at") ?? "") ?? 0)
        Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in Task { @MainActor in self?.recompute() } }
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
        engine.$lastText.compactMap { $0 }.sink { [weak self] _ in
            self?.textShownUntil = .now.addingTimeInterval(8)
            DispatchQueue.main.async { self?.recompute() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 8.1) { self?.recompute() }
        }.store(in: &bag)
        $pendingText.sink { [weak self] _ in DispatchQueue.main.async { self?.recompute() } }.store(in: &bag)
        engine.$justPaired.compactMap { $0 }.sink { [weak self] _ in
            self?.pairedToastUntil = .now.addingTimeInterval(4)
            DispatchQueue.main.async { self?.recompute() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 4.1) { self?.recompute() }
        }.store(in: &bag)
        engine.$recent.sink { [weak self] _ in DispatchQueue.main.async { self?.recompute() } }.store(in: &bag)
        engine.$online.sink { [weak self] _ in DispatchQueue.main.async { self?.recompute() } }.store(in: &bag)
        Publishers.Merge3($hovering.map { _ in () }, $dragging.map { _ in () }, $pinnedOpen.map { _ in () })
            .sink { [weak self] in DispatchQueue.main.async { self?.recompute() } }.store(in: &bag)
    }

    func recompute() {
        let m: Mode
        if dragging { m = .drop }
        else if pendingText != nil { m = .pickText }
        else if !engine.offers.isEmpty { m = .offer }
        else if engine.transfers.contains(where: \.isRunning) { m = .transfer }
        else if let u = pairedToastUntil, u > .now { m = .paired }
        else if let u = textShownUntil, u > .now, !hovering { m = .textArrived }
        else if hovering || pinnedOpen { m = .open }
        else if let u = showDoneUntil, u > .now { m = .done }
        else { m = .idle }
        let info = makeIdleInfo()
        if m != mode || info != idleInfo {
            withAnimation(.spring(response: 0.42, dampingFraction: 0.78)) { mode = m; idleInfo = info }
        }
    }

    func clearRecent() {
        clearedAt = .now
        engine.db.setSetting("island_cleared_at", String(clearedAt.timeIntervalSince1970))
        recompute()
    }

    private func makeIdleInfo() -> IdleInfo? {
        let since = max(clearedAt, .now.addingTimeInterval(-Self.freshFor))
        if let last = engine.recent.first(where: { $0.direction == "in" }), last.createdAt > since {
            if last.mime == Engine.snippetMime {
                return IdleInfo(text: String(localized: "Texto listo para copiar"), arrived: true, url: last.url, mime: last.mime)
            }
            let batch = engine.recent.filter { $0.batch == last.batch }
            return IdleInfo(text: Self.arrivedText(batch), arrived: true, url: last.url, mime: last.mime)
        }
        let n = engine.peers.filter { engine.online.contains($0.id) }.count
        guard n > 0 else { return nil }
        let text = n == 1 ? String(localized: "1 dispositivo conectado") : String(localized: "\(n) dispositivos conectados")
        return IdleInfo(text: text, arrived: false, url: nil, mime: "")
    }

    static func arrivedText(_ files: [FileRecord]) -> String {
        let n = files.count
        let kinds = Set(files.map { Mime.kind($0.mime) })
        if n == 1 {
            switch kinds.first {
            case "image": return String(localized: "Llegó una foto")
            case "video": return String(localized: "Llegó un video")
            default: return String(localized: "Llegó un archivo")
            }
        }
        if kinds == ["image"] { return String(localized: "Llegaron \(n) fotos") }
        if kinds == ["video"] { return String(localized: "Llegaron \(n) videos") }
        return String(localized: "Llegaron \(n) archivos")
    }

    var size: CGSize {
        switch mode {
        case .idle: return CGSize(width: idleInfo == nil ? 210 : (idleInfo!.arrived ? 330 : 300), height: 34)
        case .open: return CGSize(width: 620, height: 218)
        case .transfer: return CGSize(width: 560, height: 176)
        case .done: return CGSize(width: 500, height: 150)
        case .drop: return CGSize(width: 600, height: 196)
        case .offer: return CGSize(width: 520, height: 150)
        case .paired: return CGSize(width: 440, height: 96)
        case .pickText: return CGSize(width: 600, height: 200)
        case .textArrived: return CGSize(width: 540, height: 178)
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
    let model = NotchModel.shared
    private var panel: NotchPanel!
    private var monitors: [Any] = []
    private var dragChangeCount = NSPasteboard(name: .drag).changeCount
    private var leaveWork: DispatchWorkItem?
    static let canvas = CGSize(width: 760, height: 330)

    func install() {
        model.controller = self
        Publishers.CombineLatest(model.$islandHidden, model.$mode).receive(on: DispatchQueue.main).sink { [weak self] _, _ in
            guard let self, let panel = self.panel else { return }
            if !self.model.islandHidden || self.model.mustShow { panel.orderFrontRegardless() } else { panel.orderOut(nil) }
        }.store(in: &bag)
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
        if model.islandHidden { panel.orderOut(nil) }

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

    /// The screen the person put the island on (remembered by display id), else the main one.
    private var screen: NSScreen {
        // Debug/demo: PASALA_ISLAND_SCREEN=<part of the display name> puts the island on that display.
        if let want = ProcessInfo.processInfo.environment["PASALA_ISLAND_SCREEN"],
           let s = NSScreen.screens.first(where: { $0.localizedName.localizedCaseInsensitiveContains(want) }) { return s }
        if let id = Engine.shared.db.setting("island_screen").flatMap(UInt32.init),
           let s = NSScreen.screens.first(where: { $0.displayID == id }) { return s }
        return NSScreen.screens.first ?? NSScreen.main!
    }

    // MARK: moving the island between screens (long press + drag)

    private var bag = Set<AnyCancellable>()
    private var ghost: NSPanel?
    private var ghostTarget: NSScreen?

    func moveChanged() {
        let p = NSEvent.mouseLocation
        if ghost == nil {
            let g = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 210, height: 34), styleMask: [.borderless, .nonactivatingPanel],
                            backing: .buffered, defer: false)
            g.isOpaque = false; g.backgroundColor = .clear; g.hasShadow = false; g.ignoresMouseEvents = true
            g.level = panel.level
            g.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            g.contentView = NSHostingView(rootView: GhostIsland())
            g.orderFrontRegardless()
            ghost = g
            panel.alphaValue = 0.35
        }
        // Near the top edge of a screen, the shadow snaps to where the island would sit there.
        let over = NSScreen.screens.first { $0.frame.contains(p) } ?? screen
        if p.y > over.frame.maxY - 120 {
            ghostTarget = over
            ghost?.setFrameOrigin(NSPoint(x: over.frame.midX - 105, y: over.frame.maxY - 34))
        } else {
            ghostTarget = nil
            ghost?.setFrameOrigin(NSPoint(x: p.x - 105, y: p.y - 17))
        }
    }

    func moveEnded() {
        let target = ghostTarget ?? NSScreen.screens.first { $0.frame.contains(NSEvent.mouseLocation) }
        ghost?.orderOut(nil); ghost = nil; ghostTarget = nil
        panel.alphaValue = 1
        if let target, let id = target.displayID { Engine.shared.db.setSetting("island_screen", String(id)) }
        model.hovering = false
        position()
    }

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
                .gesture(LongPressGesture(minimumDuration: 0.45).sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .global))
                    .onChanged { v in if case .second(true, _) = v { model.controller?.moveChanged() } }
                    .onEnded { v in if case .second(true, _) = v { model.controller?.moveEnded() } })
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
        // Double click anywhere on the island (its edge included) opens the window.
        .simultaneousGesture(TapGesture(count: 2).onEnded { Windows.shared.showMain() })
        .contextMenu { IslandMenu(model: model) }
    }

    @ViewBuilder var content: some View {
        switch model.mode {
        case .idle: IdleBar(model: model)
        case .open: OpenPanel(model: model)
        case .transfer, .done: TransferPanel(done: model.mode == .done)
        case .drop: DropPanel(model: model)
        case .offer: OfferPanel()
        case .paired: PairedToast()
        case .pickText: PickTextPanel(model: model)
        case .textArrived: TextArrivedPanel(model: model)
        }
    }
}

struct IdleBar: View {
    @ObservedObject var model: NotchModel
    @EnvironmentObject var engine: Engine
    @State private var pulse = false
    var body: some View {
        HStack(spacing: 8) {
            PlaneGlyph(size: 11)
            Spacer(minLength: 4)
            if let info = model.idleInfo {
                if info.arrived {
                    Thumb(url: info.url, mime: info.mime, corner: 5).frame(width: 20, height: 20)
                }
                Text(info.text).font(.system(size: 11.5, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.85)).lineLimit(1)
                    .transition(.opacity.combined(with: .scale(scale: 0.9)))
            }
            Spacer(minLength: 4)
            let n = engine.peers.filter { engine.online.contains($0.id) }.count
            Circle().fill(n > 0 ? Theme.mint : .gray).frame(width: 6, height: 6)
                .shadow(color: n > 0 ? Theme.mint : .clear, radius: pulse ? 5 : 1)
        }
        .frame(height: 34)
        .allowsHitTesting(false)
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

    var items: [FileRecord] { Array(engine.recent.filter { $0.direction == "in" && $0.createdAt > model.clearedAt }.prefix(5)) }
    var devices: [Peer] { engine.peers.sorted { engine.online.contains($0.id) && !engine.online.contains($1.id) } }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                IslandHeader(title: "Pasala", subtitle: engine.peers.isEmpty ? String(localized: "Sin dispositivos todavía")
                                                                                : String(localized: "Arrastrá archivos acá · doble clic abre Pasala"))
                Button { Windows.shared.showMain() } label: {
                    Image(systemName: "rectangle.expand.vertical").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.dim)
                }.buttonStyle(.plain).help(String(localized: "Abrir Pasala"))
            }
            HStack(alignment: .top, spacing: 20) {
                VStack(alignment: .leading, spacing: 7) {
                    label(engine.peers.count > 1 ? String(localized: "DISPOSITIVOS · \(engine.peers.count)") : String(localized: "DISPOSITIVOS"))
                    if engine.peers.isEmpty {
                        Text("Sumá uno con el botón de abajo.").font(.system(size: 12, design: .rounded)).foregroundStyle(Theme.dim)
                    } else {
                        ScrollView(.vertical, showsIndicators: devices.count > 3) {
                            VStack(alignment: .leading, spacing: 6) {
                                ForEach(devices) { p in
                                    Button { Windows.shared.showSend(to: p.id) } label: {
                                        HStack(spacing: 8) {
                                            DeviceGlyph(platform: p.platform, online: engine.online.contains(p.id), size: 24)
                                            Text(p.name).font(.system(size: 12, weight: .semibold, design: .rounded)).foregroundStyle(.white).lineLimit(1)
                                        }
                                    }.buttonStyle(.plain).help(String(localized: "Mandarle archivos"))
                                }
                            }
                        }
                        .frame(maxHeight: 90)
                    }
                }
                .frame(width: 190, alignment: .leading)

                VStack(alignment: .leading, spacing: 7) {
                    HStack {
                        label(String(localized: "LO ÚLTIMO QUE LLEGÓ"))
                        Spacer()
                        if !items.isEmpty {
                            Button { withAnimation(.spring(response: 0.35)) { model.clearRecent() } } label: {
                                Text("Limpiar").font(.system(size: 10.5, weight: .bold, design: .rounded)).foregroundStyle(Theme.dim)
                                    .padding(.horizontal, 8).padding(.vertical, 3).background(Capsule().fill(Theme.faint))
                            }.buttonStyle(.plain).help(String(localized: "Vacía esta lista; los archivos no se tocan"))
                        }
                    }
                    if items.isEmpty {
                        Text("Lo que te manden va a aparecer acá.").font(.system(size: 12, design: .rounded)).foregroundStyle(Theme.dim)
                            .frame(height: 64, alignment: .center)
                    } else {
                        HStack(spacing: 8) {
                            ForEach(items) { f in
                                Button { f.mime == Engine.snippetMime ? copyToClipboard(f) : { NSWorkspace.shared.open(f.url) }() } label: {
                                    Thumb(url: f.url, mime: f.mime, corner: 12).frame(width: 64, height: 64)
                                }
                                .buttonStyle(.plain)
                                .help(f.mime == Engine.snippetMime ? String(localized: "Clic para copiar: \(f.name)") : "\(f.name) · \(f.peerName)")
                                .contextMenu {
                                    Button(String(localized: "Copiar")) { copyToClipboard(f) }
                                    Button(String(localized: "Abrir")) { NSWorkspace.shared.open(f.url) }
                                    if f.mime != Engine.snippetMime {
                                        Button(String(localized: "Mostrar en Finder")) { NSWorkspace.shared.activateFileViewerSelecting([f.url]) }
                                    }
                                }
                                .onDrag { NSItemProvider(contentsOf: f.url) ?? NSItemProvider() }
                                .transition(.scale(scale: 0.6).combined(with: .opacity))
                            }
                        }
                    }
                }
            }
            .padding(.top, 12)
            Spacer(minLength: 10)
            HStack(spacing: 8) {
                GlowButton(title: String(localized: "Enviar archivos"), icon: "arrow.up.circle.fill") { Windows.shared.showSend(to: nil) }
                GlowButton(title: String(localized: "Abrir carpeta"), icon: "folder.fill", prominent: false) { NSWorkspace.shared.open(engine.inbox) }
                GlowButton(title: String(localized: "Sumar"), icon: "qrcode", prominent: false) { Windows.shared.showPair() }
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.bottom, 16)
        .frame(maxHeight: .infinity)
    }

    func label(_ t: String) -> some View {
        Text(t).font(.system(size: 9.5, weight: .heavy, design: .rounded)).foregroundStyle(Theme.dim).tracking(1)
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
                    IslandHeader(title: headline(t), subtitle: String(localized: "\(finished) de \(t.items.count) · \(formatBytes(t.totalBytes))"))
                    if !t.isRunning, t.direction == .incoming {
                        GlowButton(title: String(localized: "Mostrar"), icon: "folder.fill", prominent: false) {
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
        case (.incoming, .running): return t.collection.map { String(localized: "Llegando de \(t.peerName) a \($0)") } ?? String(localized: "Llegando de \(t.peerName)")
        case (.incoming, .done): return t.collection.map { String(localized: "Llegó de \(t.peerName) a \($0)") } ?? String(localized: "Llegó de \(t.peerName)")
        case (.outgoing, .running): return String(localized: "Mandando a \(t.peerName)")
        case (.outgoing, .done): return String(localized: "Listo, ya lo tiene \(t.peerName)")
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
            IslandHeader(title: String(localized: "Soltalo en un dispositivo"))
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
                IslandHeader(title: o.files.count == 1 ? String(localized: "\(o.peerName) te quiere mandar un archivo") : String(localized: "\(o.peerName) te quiere mandar \(o.files.count) archivos"),
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


// MARK: - Text between devices

/// Copies a received item: the text itself for snippets, the file for everything else.
@MainActor func copyToClipboard(_ f: FileRecord) {
    let pb = NSPasteboard.general
    pb.clearContents()
    if let t = Engine.shared.snippetText(f) { pb.setString(t, forType: .string) } else { pb.writeObjects([f.url as NSURL]) }
}

/// Starts "send this text": the island shows the devices to pick from.
@MainActor func beginSendText(_ text: String?) {
    guard let t = text?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { NSSound.beep(); return }
    NotchModel.current?.pendingText = t
}

struct IslandMenu: View {
    @ObservedObject var model: NotchModel
    var body: some View {
        Button(String(localized: "Enviar texto copiado")) { beginSendText(NSPasteboard.general.string(forType: .string)) }
            .disabled(NSPasteboard.general.string(forType: .string) == nil)
        Button(String(localized: "Enviar archivos…")) { Windows.shared.showSend(to: nil) }
        Divider()
        Button(String(localized: "Abrir Pasala")) { Windows.shared.showMain() }
        Button(String(localized: "Sumar dispositivo…")) { Windows.shared.showPair() }
    }
}

struct PickTextPanel: View {
    @ObservedObject var model: NotchModel
    @EnvironmentObject var engine: Engine
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                IslandHeader(title: String(localized: "Mandar texto a…"))
                Button { model.pendingText = nil } label: {
                    Image(systemName: "xmark.circle.fill").font(.system(size: 15)).foregroundStyle(Theme.dim)
                }.buttonStyle(.plain).help(String(localized: "Cancelar"))
            }
            Text(model.pendingText ?? "").font(.system(size: 12, design: .rounded)).foregroundStyle(.white.opacity(0.8))
                .lineLimit(1).padding(.horizontal, 10).padding(.vertical, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 10).fill(Theme.surface))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(engine.peers) { p in
                        Button {
                            let text = model.pendingText ?? ""
                            model.pendingText = nil
                            Task { await engine.sendText(text, to: p) }
                        } label: {
                            HStack(spacing: 8) {
                                DeviceGlyph(platform: p.platform, online: engine.online.contains(p.id), size: 28)
                                Text(p.name).font(.system(size: 12.5, weight: .semibold, design: .rounded)).foregroundStyle(.white).lineLimit(1)
                            }
                            .padding(.horizontal, 12).padding(.vertical, 9)
                            .background(Capsule().fill(Theme.surface))
                            .overlay(Capsule().strokeBorder(Theme.brand, lineWidth: 1))
                        }.buttonStyle(.plain)
                    }
                }
            }
        }
    }
}

struct TextArrivedPanel: View {
    @ObservedObject var model: NotchModel
    @EnvironmentObject var engine: Engine
    @State private var copied = false
    var body: some View {
        if let f = engine.lastText {
            VStack(alignment: .leading, spacing: 10) {
                IslandHeader(title: String(localized: "Texto listo para copiar"), subtitle: String(localized: "De \(f.peerName)"))
                Text(engine.snippetText(f) ?? f.name).font(.system(size: 12.5, design: .rounded)).foregroundStyle(.white.opacity(0.9))
                    .lineLimit(3).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10).background(RoundedRectangle(cornerRadius: 12).fill(Theme.surface))
                HStack(spacing: 8) {
                    GlowButton(title: copied ? String(localized: "Copiado") : String(localized: "Copiar"), icon: copied ? "checkmark" : "doc.on.doc") {
                        copyToClipboard(f); copied = true
                        model.textShownUntil = .now.addingTimeInterval(1.2)
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.3) { model.recompute() }
                    }
                    GlowButton(title: String(localized: "Abrir"), prominent: false) { NSWorkspace.shared.open(f.url) }
                    Spacer()
                }
            }
        }
    }
}


/// The see-through island that follows the pointer while moving it to another screen.
struct GhostIsland: View {
    var body: some View {
        NotchShape(ear: 8, bottom: 13)
            .fill(Color.black.opacity(0.55))
            .overlay(NotchShape(ear: 8, bottom: 13).stroke(Theme.brand, style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])))
            .overlay(PlaneGlyph(size: 11).opacity(0.8))
            .frame(width: 210, height: 34)
    }
}

extension NSScreen {
    var displayID: UInt32? { (deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value }
}
