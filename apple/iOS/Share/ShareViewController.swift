import UIKit
import SwiftUI
import UniformTypeIdentifiers
import PasalaKit

/// "Pasala" in the iOS share sheet: copies the shared items to temporary files and shows the send panel.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        let model = ShareModel()
        let root = ShareRoot(model: model) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil)
        }.environmentObject(Engine.shared)
        let host = UIHostingController(rootView: root)
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        Task { await model.load(providers) }
    }
}

@MainActor
final class ShareModel: ObservableObject {
    @Published var files: [URL] = []
    @Published var text: String?
    @Published var loading = true

    func load(_ providers: [NSItemProvider]) async {
        let textOnly = providers.allSatisfy { p in
            !p.registeredTypeIdentifiers.compactMap(UTType.init).contains { $0.conforms(to: .data) && !$0.conforms(to: .text) && !$0.conforms(to: .url) }
        }
        if textOnly, let t = await Self.text(from: providers), !t.isEmpty {
            text = t; loading = false; return
        }
        var out: [URL] = []
        for p in providers {
            if let u = await Self.file(from: p) { out.append(u) }
        }
        files = out
        loading = false
    }

    /// Prefers a file representation (photos, videos, documents); plain text and links become a .txt.
    static func file(from p: NSItemProvider) async -> URL? {
        let types = p.registeredTypeIdentifiers.compactMap(UTType.init)
        if let t = types.first(where: { $0.conforms(to: .data) && !$0.conforms(to: .plainText) && !$0.conforms(to: .url) }) ?? types.first(where: { $0.conforms(to: .data) }) {
            return await withCheckedContinuation { cont in
                _ = p.loadFileRepresentation(forTypeIdentifier: t.identifier) { url, _ in
                    guard let url else { cont.resume(returning: nil); return }
                    let name = p.suggestedName.map { $0 + (url.pathExtension.isEmpty ? "" : "." + url.pathExtension) } ?? url.lastPathComponent
                    let dst = uniqueURL(in: Self.dir, name: name)
                    cont.resume(returning: (try? FileManager.default.copyItem(at: url, to: dst)) != nil ? dst : nil)
                }
            }
        }
        if p.canLoadObject(ofClass: URL.self) || p.canLoadObject(ofClass: String.self) {
            let text: String? = await withCheckedContinuation { cont in
                if p.canLoadObject(ofClass: URL.self) {
                    _ = p.loadObject(ofClass: URL.self) { u, _ in cont.resume(returning: u?.absoluteString) }
                } else {
                    _ = p.loadObject(ofClass: String.self) { s, _ in cont.resume(returning: s) }
                }
            }
            guard let text else { return nil }
            let dst = uniqueURL(in: dir, name: "Texto compartido.txt")
            return (try? text.write(to: dst, atomically: true, encoding: .utf8)) != nil ? dst : nil
        }
        return nil
    }

    /// Plain text or links shared from another app, joined.
    static func text(from providers: [NSItemProvider]) async -> String? {
        var parts: [String] = []
        for p in providers {
            let s: String? = await withCheckedContinuation { cont in
                if p.canLoadObject(ofClass: String.self) {
                    _ = p.loadObject(ofClass: String.self) { s, _ in cont.resume(returning: s) }
                } else if p.canLoadObject(ofClass: URL.self) {
                    _ = p.loadObject(ofClass: URL.self) { u, _ in cont.resume(returning: u?.absoluteString) }
                } else { cont.resume(returning: nil) }
            }
            if let s { parts.append(s) }
        }
        return parts.isEmpty ? nil : parts.joined(separator: "\n")
    }

    static var dir: URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("pasala-share", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }
}

struct ShareRoot: View {
    @ObservedObject var model: ShareModel
    let close: () -> Void

    var body: some View {
        ZStack {
            Backdrop()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 10) {
                        PasalaTile(size: 34)
                        Text(model.files.count > 1 ? String(localized: "Pasar \(model.files.count) archivos") : String(localized: "Pasar"))
                            .font(.system(size: 26, weight: .heavy, design: .rounded))
                        Spacer()
                        Button(action: close) { Image(systemName: "xmark").font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
                            .frame(width: 34, height: 34).background(Circle().fill(Theme.surface)) }
                    }
                    if model.loading {
                        ProgressView("Preparando…").tint(.white)
                    } else if let text = model.text {
                        SendPanel(files: [], text: text) { ok in
                            if ok { DispatchQueue.main.asyncAfter(deadline: .now() + 1.2, execute: close) }
                        }
                    } else if model.files.isEmpty {
                        Text("No hay archivos para pasar.").foregroundStyle(Theme.dim)
                    } else {
                        SendPanel(files: model.files) { ok in
                            if ok { DispatchQueue.main.asyncAfter(deadline: .now() + 1.2, execute: close) }
                        }
                    }
                }
                .padding(20)
            }
        }
        .preferredColorScheme(.dark)
    }
}
