// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Pasala",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "Pasala", targets: ["Pasala"]),
        .executable(name: "pasala-mcp", targets: ["PasalaMCP"]),
    ],
    targets: [
        .target(
            name: "PasalaCore",
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
        .executableTarget(
            name: "Pasala",
            dependencies: ["PasalaCore"]
        ),
        .executableTarget(
            name: "PasalaMCP",
            dependencies: ["PasalaCore"]
        ),
    ],
    swiftLanguageModes: [.v5]
)
