import SwiftUI
import AppKit
import ServiceManagement
import PasalaCore
import PasalaKit

@main
struct PasalaApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    // The menu bar icon is an NSStatusItem (StatusBar) so a double click can open the window.
    // SwiftUI needs one scene; a menu bar extra that is never inserted opens no window by itself
    // (an empty Settings scene popped up when our own Ajustes window closed). See HISTORY.md#status-item
    var body: some Scene {
        MenuBarExtra("Pasala", isInserted: .constant(false)) { EmptyView() }
    }
}

/// Menu bar icon: one click shows the menu, a double click opens Pasala, right click shows the menu at once.
@MainActor
final class StatusBar: NSObject {
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    private var pendingMenu: DispatchWorkItem?

    override init() {
        super.init()
        item.button?.image = MarkGeometry.template()
        item.button?.target = self
        item.button?.action = #selector(clicked)
        item.button?.sendAction(on: [.leftMouseUp, .rightMouseUp])
    }

    @objc private func clicked() {
        let e = NSApp.currentEvent
        if e?.type == .rightMouseUp { showMenu(); return }
        if (e?.clickCount ?? 1) >= 2 {
            pendingMenu?.cancel(); pendingMenu = nil
            Windows.shared.showMain()
            return
        }
        let w = DispatchWorkItem { [weak self] in self?.showMenu() }
        pendingMenu = w
        DispatchQueue.main.asyncAfter(deadline: .now() + NSEvent.doubleClickInterval, execute: w)
    }

    private func showMenu() {
        pendingMenu = nil
        item.menu = buildMenu()          // built fresh so checkmarks and devices are current
        item.button?.performClick(nil)
        item.menu = nil
    }

    private func buildMenu() -> NSMenu {
        let engine = Engine.shared
        let m = NSMenu()
        m.addItem(BlockItem(String(localized: "Enviar archivos…"), key: "e") { Windows.shared.showSend(to: nil) })
        m.addItem(BlockItem(String(localized: "Enviar texto copiado"), key: "t") { beginSendText(NSPasteboard.general.string(forType: .string)) })
        if !engine.peers.isEmpty {
            let sub = NSMenu()
            for p in engine.peers {
                sub.addItem(BlockItem((engine.online.contains(p.id) ? "● " : "○ ") + p.name) { Windows.shared.showSend(to: p.id) })
            }
            let to = NSMenuItem(title: String(localized: "Enviar a"), action: nil, keyEquivalent: "")
            to.submenu = sub
            m.addItem(to)
        }
        m.addItem(.separator())
        let notch = BlockItem(String(localized: "Mostrar notch")) { NotchModel.shared.islandHidden.toggle() }
        notch.state = NotchModel.shared.islandHidden ? .off : .on
        m.addItem(notch)
        m.addItem(BlockItem(String(localized: "Abrir Pasala"), key: "o") { Windows.shared.showMain() })
        m.addItem(BlockItem(String(localized: "Abrir carpeta de recibidos")) { NSWorkspace.shared.open(engine.inbox) })
        m.addItem(BlockItem(String(localized: "Sumar dispositivo…")) { Windows.shared.showPair() })
        m.addItem(.separator())
        let login = BlockItem(String(localized: "Abrir al iniciar sesión")) {
            if SMAppService.mainApp.status == .enabled { try? SMAppService.mainApp.unregister() } else { try? SMAppService.mainApp.register() }
        }
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        m.addItem(login)
        m.addItem(BlockItem(String(localized: "Ajustes…"), key: ",") { Windows.shared.showSettings() })
        m.addItem(BlockItem(String(localized: "Salir de Pasala"), key: "q") { NSApp.terminate(nil) })
        return m
    }
}

/// NSMenuItem that runs a closure.
final class BlockItem: NSMenuItem {
    private let run: () -> Void
    init(_ title: String, key: String = "", _ run: @escaping () -> Void) {
        self.run = run
        super.init(title: title, action: #selector(fire), keyEquivalent: key)
        target = self
    }
    required init(coder: NSCoder) { fatalError() }
    @objc private func fire() { run() }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    var notch: NotchController?
    var statusBar: StatusBar?
    let services = PasalaServices()

    func applicationDidFinishLaunching(_ n: Notification) {
        // See HISTORY.md#single-instance
        let me = ProcessInfo.processInfo.processIdentifier
        if let other = NSRunningApplication.runningApplications(withBundleIdentifier: "com.tecnomanu.pasala").first(where: { $0.processIdentifier != me }) {
            other.activate()
            exit(0)
        }
        NSApp.setActivationPolicy(.accessory)
        NSApp.servicesProvider = services
        NSUpdateDynamicServices()
        MainActor.assumeIsolated {
            Engine.shared.start()
            let c = NotchController(); c.install(); notch = c
            statusBar = StatusBar()
            if Engine.shared.peers.isEmpty || CommandLine.arguments.contains("--show") { Windows.shared.showMain() }
            if CommandLine.arguments.contains("--pair") { Windows.shared.showPair() }
            if CommandLine.arguments.contains("--settings") { Windows.shared.showSettings() }
        }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        MainActor.assumeIsolated { Windows.shared.showMain() }
        return true
    }
}

/// macOS Services (right-click menus in other apps). See HISTORY.md#services-menu
final class PasalaServices: NSObject {
    /// Selected text → "Pasar con Pasala".
    @objc func sendText(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let text = pboard.string(forType: .string)
        DispatchQueue.main.async { MainActor.assumeIsolated { beginSendText(text) } }
    }

    /// Files in Finder → "Enviar con Pasala": opens the usual send window with them.
    @objc func sendFiles(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let urls = (pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                guard !urls.isEmpty else { return }
                Windows.shared.sendModel.files = urls
                Windows.shared.showSend(to: nil)
            }
        }
    }
}
