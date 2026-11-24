import Foundation

/// The server's wire fixtures, which its own tests check its types against. `Fixtures/` is a
/// copy of `crates/trackside-api/tests/fixtures` from slukedev/tracks-companion, without the
/// dashboard's `admin/` shapes; re-copy it when they change. `TRACKSIDE_FIXTURES` points the
/// tests at another copy, such as a server checkout.
enum TracksideFixtures {
    static let directory: URL? = {
        if let path = ProcessInfo.processInfo.environment["TRACKSIDE_FIXTURES"] {
            return URL(fileURLWithPath: path, isDirectory: true)
        }
        let copy = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent().appending(path: "Fixtures", directoryHint: .isDirectory)
        return FileManager.default.fileExists(atPath: copy.path) ? copy : nil
    }()

    static func data(_ name: String) throws -> Data {
        guard let directory else { throw CocoaError(.fileNoSuchFile) }
        return try Data(contentsOf: directory.appending(path: name))
    }

    static var files: [URL] {
        guard let directory, let found = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil) else { return [] }
        return found.compactMap { $0 as? URL }.filter { $0.pathExtension == "json" }.sorted { $0.path < $1.path }
    }
}

/// A folder of its own for each test, removed afterwards.
struct TemporaryFolder: ~Copyable {
    let url: URL

    init() throws {
        url = FileManager.default.temporaryDirectory.appending(path: "tracks-tests-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit {
        try? FileManager.default.removeItem(at: url)
    }
}
