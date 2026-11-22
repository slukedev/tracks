import Foundation
import os

/// Reads the JSON files Tracks keeps its own records in (playlists, downloads, servers, plays
/// waiting to be sent) so that one it can't read is never written over.
///
/// A file cut short, written by a newer Tracks, or holding one entry an older one can't read
/// would otherwise read as empty, and the next save would replace everything in it with
/// nothing. Instead a file that can't be read at all is moved aside before anything can write,
/// and a list or table is read entry by entry, so one bad entry costs only itself; the file is
/// copied aside first, so nothing in it is lost for good.
public enum StoredJSON {
    private static let log = Logger(subsystem: "com.luke.tracks", category: "StoredJSON")

    /// The file's value, or nil when there's none, or none that can be read.
    public static func load<Value: Decodable>(_ type: Value.Type, from url: URL, decoder: JSONDecoder = JSONDecoder()) -> Value? {
        guard let data = read(url) else { return nil }
        do {
            return try decoder.decode(Value.self, from: data)
        } catch {
            setAside(url, moving: true, because: error)
            return nil
        }
    }

    /// Each entry of a list that can be read, in order.
    public static func loadEach<Element: Decodable>(_ type: [Element].Type, from url: URL, decoder: JSONDecoder = JSONDecoder()) -> [Element]? {
        guard let data = read(url) else { return nil }
        return decodeEach(type, from: data, decoder: decoder) { setAside(url, moving: $0, because: $1) }
    }

    /// Each entry of a table that can be read.
    public static func loadEach<Element: Decodable>(_ type: [String: Element].Type, from url: URL, decoder: JSONDecoder = JSONDecoder()) -> [String: Element]? {
        guard let data = read(url) else { return nil }
        return decodeEach(type, from: data, decoder: decoder) { setAside(url, moving: $0, because: $1) }
    }

    /// Each entry of a list kept somewhere other than a file, such as the app's defaults. What
    /// can't all be read is written to `copy` first.
    public static func decodeEach<Element: Decodable>(_ type: [Element].Type, from data: Data, keepingCopyAt copy: URL, decoder: JSONDecoder = JSONDecoder()) -> [Element]? {
        decodeEach(type, from: data, decoder: decoder) { _, error in
            keep(data, at: copy, because: error)
        }
    }

    /// Where a file that couldn't be read goes: beside it, named for when, and never over one
    /// set aside before.
    public static func asideURL(for url: URL, at date: Date = .now) -> URL {
        let stem = url.deletingPathExtension().lastPathComponent + ".unreadable-\(Int(date.timeIntervalSince1970))"
        let ext = url.pathExtension.isEmpty ? "json" : url.pathExtension
        let folder = url.deletingLastPathComponent()
        var candidate = folder.appending(path: "\(stem).\(ext)")
        var number = 1
        while FileManager.default.fileExists(atPath: candidate.path(percentEncoded: false)) {
            number += 1
            candidate = folder.appending(path: "\(stem)-\(number).\(ext)")
        }
        return candidate
    }

    private static func decodeEach<Element: Decodable>(_ type: [Element].Type, from data: Data, decoder: JSONDecoder, setAside: (_ moving: Bool, _ error: any Error) -> Void) -> [Element]? {
        do {
            let entries = try decoder.decode([Lossy<Element>].self, from: data)
            let read = entries.compactMap(\.value)
            if read.count < entries.count {
                setAside(false, StoredJSONError.unreadableEntries(entries.count - read.count))
            }
            return read
        } catch {
            setAside(true, error)
            return nil
        }
    }

    private static func decodeEach<Element: Decodable>(_ type: [String: Element].Type, from data: Data, decoder: JSONDecoder, setAside: (_ moving: Bool, _ error: any Error) -> Void) -> [String: Element]? {
        do {
            let entries = try decoder.decode([String: Lossy<Element>].self, from: data)
            let read = entries.compactMapValues(\.value)
            if read.count < entries.count {
                setAside(false, StoredJSONError.unreadableEntries(entries.count - read.count))
            }
            return read
        } catch {
            setAside(true, error)
            return nil
        }
    }

    /// The file's bytes, or nil for one that isn't there. One there but unreadable now, as
    /// before the first unlock, is left alone: it can't be written over then either.
    private static func read(_ url: URL) -> Data? {
        do {
            return try Data(contentsOf: url)
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
            return nil
        } catch {
            log.error("Couldn't read \(url.lastPathComponent, privacy: .public): \(error, privacy: .public)")
            return nil
        }
    }

    private static func setAside(_ url: URL, moving: Bool, because error: any Error) {
        let aside = asideURL(for: url)
        do {
            if moving {
                try FileManager.default.moveItem(at: url, to: aside)
            } else {
                try FileManager.default.copyItem(at: url, to: aside)
            }
            log.error("Set \(url.lastPathComponent, privacy: .public) aside as \(aside.lastPathComponent, privacy: .public): \(error, privacy: .public)")
        } catch let failure {
            log.fault("Couldn't set \(url.lastPathComponent, privacy: .public) aside: \(failure, privacy: .public), after \(error, privacy: .public)")
        }
    }

    private static func keep(_ data: Data, at url: URL, because error: any Error) {
        let aside = asideURL(for: url)
        do {
            try FileManager.default.createDirectory(at: aside.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: aside, options: .atomic)
            log.error("Kept a copy of unreadable \(url.lastPathComponent, privacy: .public) as \(aside.lastPathComponent, privacy: .public): \(error, privacy: .public)")
        } catch let failure {
            log.fault("Couldn't keep a copy of \(url.lastPathComponent, privacy: .public): \(failure, privacy: .public), after \(error, privacy: .public)")
        }
    }
}

enum StoredJSONError: Error {
    case unreadableEntries(Int)
}

/// One entry that reads as nil rather than failing the whole list.
private struct Lossy<Value: Decodable>: Decodable {
    let value: Value?

    init(from decoder: any Decoder) throws {
        value = try? Value(from: decoder)
    }
}
