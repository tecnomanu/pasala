// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Pasala",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "PasalaKit", targets: ["PasalaKit"]),
        .executable(name: "Pasala", targets: ["Pasala"]),
        .executable(name: "pasala-mcp", targets: ["PasalaMCP"]),
    ],
    targets: [
        // Storage, HTTP server, network helpers. No UI.
        .target(name: "PasalaCore", linkerSettings: [.linkedLibrary("sqlite3")]),
        // Shared by the Mac and iPhone apps: the engine (pairing, sending, receiving, collections) and brand views.
        .target(name: "PasalaKit", dependencies: ["PasalaCore"]),
        .executableTarget(name: "Pasala", dependencies: ["PasalaKit"]),
        .executableTarget(name: "PasalaMCP", dependencies: ["PasalaCore"]),
    ],
    swiftLanguageModes: [.v5]
)
