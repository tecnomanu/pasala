import SwiftUI
import Photos
import PasalaKit

@main
struct PasalaPhoneApp: App {
    @ObservedObject private var engine = Engine.shared
    @Environment(\.scenePhase) private var phase
    @State private var pairLink: String?
    @State private var started = false

    var body: some Scene {
        WindowGroup {
            HomeView(pairLink: $pairLink)
                .environmentObject(engine)
                .preferredColorScheme(.dark)
                .tint(Theme.violet)
                .onOpenURL { url in if url.scheme == "pasala" { pairLink = url.absoluteString } }
                .task {
                    guard !started else { return }
                    started = true
                    engine.onFileReceived = { url, mime in PhotoSaver.saveIfWanted(url, mime: mime) }
                    engine.start()
                }
        }
        .onChange(of: phase) { _, p in
            switch p {
            case .active: Task { await engine.refreshOnline() }
            case .background: BackgroundTime.keepTransfersAlive(engine)
            default: break
            }
        }
    }
}

/// Copies received photos and videos to the Photos library when the person wants that.
@MainActor
enum PhotoSaver {
    static var enabled: Bool {
        get { Engine.shared.db.setting("save_photos") != "0" }
        set { Engine.shared.db.setSetting("save_photos", newValue ? "1" : "0") }
    }

    static func saveIfWanted(_ url: URL, mime: String) {
        guard enabled, mime.hasPrefix("image/") || mime.hasPrefix("video/") else { return }
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else { return }
            PHPhotoLibrary.shared().performChanges {
                let req = PHAssetCreationRequest.forAsset()
                req.addResource(with: mime.hasPrefix("video/") ? .video : .photo, fileURL: url, options: nil)
            }
        }
    }
}

/// iOS suspends the app soon after it leaves the screen; ask for time to finish what is in flight.
enum BackgroundTime {
    @MainActor static func keepTransfersAlive(_ engine: Engine) {
        guard engine.transfers.contains(where: \.isRunning) else { return }
        var task: UIBackgroundTaskIdentifier = .invalid
        task = UIApplication.shared.beginBackgroundTask(withName: "pasala-transfer") {
            UIApplication.shared.endBackgroundTask(task)
        }
        Task { @MainActor in
            while engine.transfers.contains(where: \.isRunning) { try? await Task.sleep(for: .seconds(1)) }
            UIApplication.shared.endBackgroundTask(task)
        }
    }
}
