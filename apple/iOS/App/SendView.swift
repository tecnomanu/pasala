import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import PasalaKit

/// Pick photos, videos or files from the app and send them.
struct SendView: View {
    @EnvironmentObject var engine: Engine
    @Environment(\.dismiss) private var dismiss
    var initialPeer: String?
    @State private var picked: [PhotosPickerItem] = []
    @State private var files: [URL] = []
    @State private var importing = false
    @State private var loading = false

    var body: some View {
        ZStack {
            Backdrop()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack {
                        Text("Enviar").font(.system(size: 30, weight: .heavy, design: .rounded))
                        Spacer()
                        Button { dismiss() } label: { Image(systemName: "xmark").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                            .frame(width: 36, height: 36).background(Circle().fill(Theme.surface)) }
                    }
                    HStack(spacing: 10) {
                        PhotosPicker(selection: $picked, matching: .any(of: [.images, .videos]), photoLibrary: .shared()) {
                            source(String(localized: "Fotos y videos"), icon: "photo.on.rectangle.angled")
                        }
                        Button { importing = true } label: { source(String(localized: "Archivos"), icon: "folder") }.buttonStyle(.plain)
                    }
                    if loading { ProgressView("Preparando…").tint(.white) }
                    SendPanel(files: files, initialPeer: initialPeer)
                }
                .padding(20)
            }
        }
        .onChange(of: picked) { _, items in Task { await load(items) } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { r in
            if case let .success(urls) = r { files += urls.compactMap(TempCopy.make) }
        }
    }

    func source(_ title: String, icon: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 22, weight: .semibold)).foregroundStyle(Theme.brand)
            Text(title).font(.system(size: 14, weight: .semibold, design: .rounded)).foregroundStyle(.white)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 18).card()
    }

    func load(_ items: [PhotosPickerItem]) async {
        loading = true
        var out: [URL] = []
        for item in items {
            if let f = try? await item.loadTransferable(type: PickedMedia.self) { out.append(f.url) }
        }
        files = out
        loading = false
    }
}

/// A photo or video from the picker, copied to a temporary file with a camera-style name.
struct PickedMedia: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in PickedMedia(url: try TempCopy.copy(received.file, prefix: "VID")) }
        FileRepresentation(importedContentType: .image) { received in PickedMedia(url: try TempCopy.copy(received.file, prefix: "IMG")) }
    }
}

enum TempCopy {
    static var dir: URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("pasala-out", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    /// Picker files arrive with opaque names; give them one a person recognizes.
    static func copy(_ src: URL, prefix: String) throws -> URL {
        let f = DateFormatter(); f.dateFormat = "yyyyMMdd_HHmmss"; f.locale = Locale(identifier: "en_US_POSIX")
        let ext = src.pathExtension.isEmpty ? "" : "." + src.pathExtension.lowercased()
        let dst = uniqueURL(in: dir, name: "\(prefix)_\(f.string(from: .now))\(ext)")
        try FileManager.default.copyItem(at: src, to: dst)
        return dst
    }

    /// Files from the Files app are security-scoped; copy them so sending does not depend on that access.
    static func make(_ url: URL) -> URL? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let dst = uniqueURL(in: dir, name: url.lastPathComponent)
        return (try? FileManager.default.copyItem(at: url, to: dst)) != nil ? dst : nil
    }
}
