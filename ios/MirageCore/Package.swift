// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MirageCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "MirageCore", targets: ["MirageCore"])],
    targets: [
        .target(name: "MirageCore"),
        .testTarget(name: "MirageCoreTests", dependencies: ["MirageCore"],
                    resources: [.process("Fixtures")])
    ]
)
