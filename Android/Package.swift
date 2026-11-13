// swift-tools-version: 6.1
// Tracks for Android, built with Skip (https://skip.dev).
//
// The app module, Tracks, is SwiftUI that Skip Fuse compiles natively for Android and renders
// with Jetpack Compose. TracksStats is the statistics engine it shares with the iPhone and Mac
// apps: its `Shared` folder links to the TracksCore files that need nothing but Foundation.
import PackageDescription

let package = Package(
    name: "TracksAndroid",
    defaultLocalization: "en",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [
        .library(name: "Tracks", type: .dynamic, targets: ["Tracks"]),
    ],
    dependencies: [
        .package(url: "https://github.com/skiptools/skip.git", from: "1.9.11"),
        .package(url: "https://github.com/skiptools/skip-fuse-ui.git", from: "1.0.0"),
        .package(url: "https://github.com/skiptools/skip-fuse.git", from: "1.0.0"),
    ],
    targets: [
        .target(name: "Tracks", dependencies: [
            "TracksStats",
            .product(name: "SkipFuseUI", package: "skip-fuse-ui"),
            .product(name: "SkipFuse", package: "skip-fuse"),
        ], resources: [.process("Resources")], plugins: [.plugin(name: "skipstone", package: "skip")]),
        // Plain Swift: nothing in it is shown to Kotlin, so it needs no Skip plugin.
        .target(name: "TracksStats", swiftSettings: [.swiftLanguageMode(.v6), .define("TRACKS_STATS_ONLY")]),
        .testTarget(name: "TracksStatsTests", dependencies: ["TracksStats"]),
    ]
)
