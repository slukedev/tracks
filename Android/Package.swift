// swift-tools-version: 6.1
// Motif for Android, built with Skip (https://skip.dev).
//
// The app module, Motif, is SwiftUI that Skip Fuse compiles natively for Android and renders
// with Jetpack Compose. MotifStats is the statistics engine it shares with the iPhone and Mac
// apps: its `Shared` folder links to the MotifCore files that need nothing but Foundation.
import PackageDescription

let package = Package(
    name: "MotifAndroid",
    defaultLocalization: "en",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [
        .library(name: "Motif", type: .dynamic, targets: ["Motif"]),
    ],
    dependencies: [
        .package(url: "https://github.com/skiptools/skip.git", from: "1.9.11"),
        .package(url: "https://github.com/skiptools/skip-fuse-ui.git", from: "1.0.0"),
        .package(url: "https://github.com/skiptools/skip-fuse.git", from: "1.0.0"),
    ],
    targets: [
        .target(name: "Motif", dependencies: [
            "MotifStats",
            .product(name: "SkipFuseUI", package: "skip-fuse-ui"),
            .product(name: "SkipFuse", package: "skip-fuse"),
        ], resources: [.process("Resources")], plugins: [.plugin(name: "skipstone", package: "skip")]),
        // Plain Swift: nothing in it is shown to Kotlin, so it needs no Skip plugin.
        .target(name: "MotifStats", swiftSettings: [.swiftLanguageMode(.v6)]),
        .testTarget(name: "MotifStatsTests", dependencies: ["MotifStats"]),
    ]
)
