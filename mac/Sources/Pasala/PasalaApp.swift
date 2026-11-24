import SwiftUI
import AppKit
import ServiceManagement
import PasalaCore

@main
struct PasalaApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @ObservedObject var engine = Engine.shared

    var body: some Scene {
        MenuBarExtra {
            MenuContent().environmentObject(engine)
        } label: {
            Image(nsImage: MarkGeometry.template())
        }
        .menuBarExtraStyle(.menu)
    }
}

struct MenuContent: View {
    @EnvironmentObject var engine: Engine
    var body: some View {
        Button("Enviar archivos…") { Windows.shared.showSend(to: nil) }.keyboardShortcut("e")
        if !engine.peers.isEmpty {
            Menu("Enviar a") {
                ForEach(engine.peers) { p in
                    Button((engine.online.contains(p.id) ? "● " : "○ ") + p.name) { Windows.shared.showSend(to: p.id) }
                }
            }
        }
        Divider()
        Button("Abrir Pasala") { Windows.shared.showMain() }.keyboardShortcut("o")
        Button("Abrir carpeta de recibidos") { NSWorkspace.shared.open(engine.inbox) }
        Button("Sumar dispositivo…") { Windows.shared.showPair() }
        Divider()
        Toggle("Abrir al iniciar sesión", isOn: Binding(
            get: { SMAppService.mainApp.status == .enabled },
            set: { on in try? on ? SMAppService.mainApp.register() : SMAppService.mainApp.unregister() }))
        Button("Ajustes…") { Windows.shared.showSettings() }.keyboardShortcut(",")
        Button("Salir de Pasala") { NSApp.terminate(nil) }.keyboardShortcut("q")
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    var notch: NotchController?

    func applicationDidFinishLaunching(_ n: Notification) {
        // See HISTORY.md#single-instance
        let me = ProcessInfo.processInfo.processIdentifier
        if let other = NSRunningApplication.runningApplications(withBundleIdentifier: "com.tecnomanu.pasala").first(where: { $0.processIdentifier != me }) {
            other.activate()
            exit(0)
        }
        NSApp.setActivationPolicy(.accessory)
        MainActor.assumeIsolated {
            Engine.shared.start()
            let c = NotchController(); c.install(); notch = c
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
