import Foundation
import TracksCore

/// The playlists made in Tracks, as Trackside Cloud keeps them: each playlist an item of
/// `playlists`, each song in one an item of `playlist-entries` with a fractional order key,
/// so songs added or moved on two devices at once all survive rather than one list winning.
///
/// What's kept beside the playlists file: each entry's order key, when each item last
/// changed here, the server's own description of each song (so ids from sources this device
/// doesn't have travel back untouched), and entries whose songs aren't in this library yet,
/// which are held, not shown, and never sent back as deleted.
@MainActor
final class PlaylistCloud {
    private weak var music: YourMusic?
    private var state: SavedState

    nonisolated struct SavedState: Codable, Sendable {
        /// Each entry's place in its playlist, compared as text.
        var orders: [String: String] = [:]
        /// When each item's fields last changed here, by a digest of them.
        var stamps: [String: Stamp] = [:]
        /// The song of each entry as the server described it.
        var tracks: [String: JSONValue] = [:]
        /// Entries from the server whose songs this library doesn't have.
        var waiting: [String: CloudRecord] = [:]
    }

    nonisolated struct Stamp: Codable, Sendable {
        var digest: String
        var at: Date
    }

    init(music: YourMusic) {
        self.music = music
        state = (try? Data(contentsOf: Self.url)).flatMap { try? JSONDecoder().decode(SavedState.self, from: $0) } ?? SavedState()
    }

    /// The two stores Trackside Cloud syncs through.
    var stores: [any CloudEntityStore] {
        [CloudPlaylistsStore(cloud: self), CloudPlaylistEntriesStore(cloud: self)]
    }

    private static var url: URL { LibraryFolders.index.appending(path: "playlists-cloud.json") }

    private func save() {
        guard let data = try? JSONEncoder().encode(state) else { return }
        try? data.write(to: Self.url, options: .atomic)
    }

    // MARK: - Playlists

    func playlistItems() -> [CloudLocalItem] {
        guard let music else { return [] }
        let items = music.playlists.all.map { playlist in
            var fields: [String: JSONValue] = [
                "name": .string(playlist.name),
                "createdAtMs": .int(CloudJSON.milliseconds(playlist.createdAt)),
                "updatedAtMs": .int(CloudJSON.milliseconds(playlist.updatedAt)),
            ]
            if let rules = playlist.rules, let encoded = try? CloudJSON.fields(rules) {
                fields["rules"] = .object(encoded)
            }
            return CloudLocalItem(id: playlist.id.uuidString, fields: fields, modifiedAt: playlist.updatedAt)
        }
        return items
    }

    func applyPlaylists(upserts: [CloudRecord], deletes: [CloudDeletion]) -> [String: [String: JSONValue]] {
        guard let music else { return [:] }
        var all = music.playlists.all
        let gone = Set(deletes.map(\.id))
        all.removeAll { gone.contains($0.id.uuidString) }
        for record in upserts {
            guard let id = UUID(uuidString: record.id) else { continue }
            let name = record.fields["name"]?.string ?? String(localized: "New Playlist")
            let rules: SmartRules? = if case .object(let object)? = record.fields["rules"] {
                try? CloudJSON.value(SmartRules.self, from: object)
            } else {
                nil
            }
            if let index = all.firstIndex(where: { $0.id == id }) {
                all[index].name = name
                all[index].rules = rules
                if let updated = record.fields["updatedAtMs"].flatMap(Self.milliseconds) { all[index].updatedAt = updated }
            } else {
                var playlist = TracksPlaylist(id: id, name: name, rules: rules)
                if let created = record.fields["createdAtMs"].flatMap(Self.milliseconds) { playlist.createdAt = created }
                if let updated = record.fields["updatedAtMs"].flatMap(Self.milliseconds) { playlist.updatedAt = updated }
                all.append(playlist)
            }
        }
        music.playlists.takeFromCloud(all)
        // A playlist deleted elsewhere takes its entries waiting here with it.
        state.waiting = state.waiting.filter { !gone.contains($0.value.fields["playlistID"]?.string ?? "") }
        save()
        let held = Dictionary(uniqueKeysWithValues: playlistItems().map { ($0.id, $0.fields) })
        return held.filter { id, _ in upserts.contains { $0.id == id } }
    }

    // MARK: - Entries

    func entryItems() -> [CloudLocalItem] {
        guard let music else { return [] }
        var items: [CloudLocalItem] = []
        var changedOrders = false
        for playlist in music.playlists.all where !playlist.isSmart {
            var previous: String?
            for (offset, entry) in playlist.entries.enumerated() {
                let id = entry.id.uuidString
                var key = state.orders[id]
                if key == nil || key! <= (previous ?? "") || !Self.isKey(key!) {
                    let next = playlist.entries[(offset + 1)...].lazy.compactMap { self.state.orders[$0.id.uuidString] }.first { $0 > (previous ?? "") && Self.isKey($0) }
                    key = FractionalKey.between(previous, next)
                    state.orders[id] = key
                    changedOrders = true
                }
                previous = key
                let fields: [String: JSONValue] = [
                    "playlistID": .string(playlist.id.uuidString),
                    "order": .string(key!),
                    "addedAtMs": .int(CloudJSON.milliseconds(entry.addedAt)),
                    "track": trackFields(entry.track, entryID: id),
                ]
                items.append(CloudLocalItem(id: id, fields: fields, modifiedAt: stamp(id, fields: fields, since: entry.addedAt)))
            }
        }
        // Entries waiting for their songs are still in their playlists, as far as anyone
        // else knows.
        for (id, record) in state.waiting {
            items.append(CloudLocalItem(id: id, fields: record.fields, modifiedAt: record.clientModifiedAt ?? .distantPast))
        }
        if changedOrders { save() }
        return items
    }

    func applyEntries(upserts: [CloudRecord], deletes: [CloudDeletion]) -> [String: [String: JSONValue]] {
        guard let music else { return [:] }
        var all = music.playlists.all
        let gone = Set(deletes.map(\.id))
        for index in all.indices {
            all[index].entries.removeAll { gone.contains($0.id.uuidString) }
        }
        for id in gone {
            state.waiting[id] = nil
            state.orders[id] = nil
            state.tracks[id] = nil
            state.stamps[id] = nil
        }
        for record in upserts {
            guard let entryID = UUID(uuidString: record.id),
                  let playlistID = record.fields["playlistID"]?.string.flatMap(UUID.init(uuidString:)),
                  let order = record.fields["order"]?.string,
                  case .object(let track)? = record.fields["track"],
                  let index = all.firstIndex(where: { $0.id == playlistID })
            else {
                state.waiting[record.id] = record
                continue
            }
            state.orders[record.id] = order
            state.tracks[record.id] = .object(track)
            guard let found = find(track) else {
                all[index].entries.removeAll { $0.id == entryID }
                state.waiting[record.id] = record
                continue
            }
            state.waiting[record.id] = nil
            let addedAt = record.fields["addedAtMs"].flatMap(Self.milliseconds) ?? record.clientModifiedAt ?? .now
            if let position = all[index].entries.firstIndex(where: { $0.id == entryID }) {
                all[index].entries[position].track = found
                all[index].entries[position].addedAt = addedAt
            } else {
                all[index].entries.append(TracksPlaylist.Entry(id: entryID, track: found, addedAt: addedAt))
            }
            if let modified = record.clientModifiedAt {
                state.stamps[record.id] = Stamp(digest: "", at: modified)
            }
        }
        // Each playlist in its order keys' order; entries without a key keep their place
        // after the one before them.
        for index in all.indices where !all[index].isSmart {
            all[index].entries = Self.ordered(all[index].entries, keys: state.orders)
        }
        music.playlists.takeFromCloud(all)
        save()
        let held = Dictionary(entryItems().map { ($0.id, $0.fields) }, uniquingKeysWith: { first, _ in first })
        return held.filter { id, _ in upserts.contains { $0.id == id } }
    }

    /// Retries entries waiting for their songs, after the library changed.
    func libraryChanged() {
        guard !state.waiting.isEmpty else { return }
        let waiting = Array(state.waiting.values)
        _ = applyEntries(upserts: waiting, deletes: [])
    }

    // MARK: - Songs

    /// A song as entries carry it: title, artist, album and its id in each source, keeping
    /// ids from sources this device doesn't have as the server last described them.
    private func trackFields(_ track: LocalTrack, entryID: String) -> JSONValue {
        var ids: [String: JSONValue] = [:]
        if case .object(let known)? = state.tracks[entryID],
           known["title"]?.string == track.title, known["artist"]?.string == track.artist,
           case .object(let knownIDs)? = known["ids"] {
            ids = knownIDs
        }
        if case .server(let serverID, let songID) = track.origin, let server = music?.servers.server(serverID) {
            ids[server.isTrackside ? "trackside" : "subsonic"] = .string(songID)
        }
        var fields: [String: JSONValue] = [
            "title": .string(track.title),
            "artist": .string(track.artist),
            "ids": .object(ids),
        ]
        if let album = track.album { fields["album"] = .string(album) }
        return .object(fields)
    }

    /// The song in this library an entry names: by its id on one of your servers, else by
    /// title and artist.
    private func find(_ track: [String: JSONValue]) -> LocalTrack? {
        guard let music, let title = track["title"]?.string, let artist = track["artist"]?.string else { return nil }
        if case .object(let ids)? = track["ids"] {
            for server in music.servers.servers {
                guard let songID = ids[server.isTrackside ? "trackside" : "subsonic"]?.string else { continue }
                if let found = music.index.track(id: LocalTrack.id(for: .server(serverID: server.id, songID: songID))) {
                    return found
                }
            }
        }
        let identity = SongIdentity.key(title: title, artistName: artist)
        let copies = music.index.tracks(withIdentity: identity)
        let album = track["album"]?.string
        return copies.first { $0.album == album } ?? copies.first
    }

    // MARK: - Helpers

    /// When an item's fields last changed here: noted the first time they're seen as they are.
    private func stamp(_ id: String, fields: [String: JSONValue], since earliest: Date) -> Date {
        let digest = CloudJSON.digest(fields)
        if let known = state.stamps[id] {
            if known.digest == digest { return known.at }
            if known.digest.isEmpty {
                // Just taken in from the server: these are its fields.
                state.stamps[id] = Stamp(digest: digest, at: known.at)
                return known.at
            }
        }
        let at = state.stamps[id] == nil ? max(earliest, .distantPast) : .now
        state.stamps[id] = Stamp(digest: digest, at: at)
        return at
    }

    nonisolated private static func milliseconds(_ value: JSONValue) -> Date? {
        switch value {
        case .int(let ms): CloudJSON.date(milliseconds: ms)
        case .double(let ms): CloudJSON.date(milliseconds: Int64(ms))
        default: nil
        }
    }

    nonisolated private static func isKey(_ key: String) -> Bool {
        !key.isEmpty && key.allSatisfy { FractionalKey.digits.contains($0) }
    }

    /// Entries sorted by key, those without one kept right after the entry before them.
    nonisolated static func ordered(_ entries: [TracksPlaylist.Entry], keys: [String: String]) -> [TracksPlaylist.Entry] {
        var groups: [(key: String, entries: [TracksPlaylist.Entry])] = []
        for entry in entries {
            if let key = keys[entry.id.uuidString] {
                groups.append((key, [entry]))
            } else if groups.isEmpty {
                groups.append(("", [entry]))
            } else {
                groups[groups.count - 1].entries.append(entry)
            }
        }
        return groups.enumerated()
            .sorted { $0.element.key == $1.element.key ? $0.offset < $1.offset : $0.element.key < $1.element.key }
            .flatMap(\.element.entries)
    }
}

nonisolated struct CloudPlaylistsStore: CloudEntityStore {
    let cloud: PlaylistCloud
    var entity: CloudEntity { .playlists }

    func localItems() async throws -> [CloudLocalItem] {
        await cloud.playlistItems()
    }

    func apply(upserts: [CloudRecord], deletes: [CloudDeletion]) async throws -> [String: [String: JSONValue]] {
        await cloud.applyPlaylists(upserts: upserts, deletes: deletes)
    }
}

nonisolated struct CloudPlaylistEntriesStore: CloudEntityStore {
    let cloud: PlaylistCloud
    var entity: CloudEntity { .playlistEntries }

    func localItems() async throws -> [CloudLocalItem] {
        await cloud.entryItems()
    }

    func apply(upserts: [CloudRecord], deletes: [CloudDeletion]) async throws -> [String: [String: JSONValue]] {
        await cloud.applyEntries(upserts: upserts, deletes: deletes)
    }
}
