#if DEBUG
import Foundation
import AppKit
import TracksCore
import TracksMusic
import MusicKit

/// Runs the probe from the command line, with no window and no Dock icon. Output goes to
/// stdout with a meaningful exit status, so it can be scripted and diffed.
enum HeadlessProbe {
    static let flag = "--headless"

    static func shouldRun(_ arguments: [String]) -> Bool {
        arguments.contains(flag)
    }

    static func usage() -> String {
        """
        Tracks headless probe

          Tracks.app/Contents/MacOS/Tracks --headless <command>

        Commands:
          env               Print environment: authorization, subscription, store backing.
          catalog <term>    Search the Apple Music catalog and print candidate song ids.
          write <songID>    Create a throwaway playlist and add a song to it. Writes to your
                            Apple Music library; delete the playlist afterwards.
          scrobble          Send everything owed to Last.fm now.
          lastfm            Report whether Last.fm is configured and connected, and how
                            many songs are owed a scrobble.
          forget <text>     Remove every song whose title contains <text>, and stop the
                            Last.fm import bringing it back.
          stations          List the stations Apple Music remembers, with provider and
                            live flags, to see which are Apple's own.
          cleanup           List the "Tracks Probe …" playlists so you can delete them
                            in Music. The Apple Music API cannot delete playlists.
          playlists         List every library playlist with its id.
          captures          Dump stored captures: catalog id, artwork, playlist state.
          artists           Look up Apple Music's picture for each artist in the history.
          genres            Look up the genre and release year of the most played songs.
          script <source>   Run AppleScript inside the sandbox, to test Music's access groups.
          readonly          Try to open the store read-only, as a widget would.
          stats             Compute the statistics screen's numbers for every range, and
                            report how many rows actually carry a station name.
          retry-unresolved  Retry captures the catalog could not identify.
          cloudschema       Upload every record type and field to the iCloud container's
                            Development schema, ready to deploy to Production.

        Exit status is 0 on success, 1 on failure, 2 on bad usage.
        """
    }

    /// Runs to completion and exits. Never returns.
    static func run(_ arguments: [String]) -> Never {
        // No Dock icon, menu bar or window.
        NSApplication.shared.setActivationPolicy(.prohibited)

        var command = "env"
        if let index = arguments.firstIndex(of: flag), arguments.count > index + 1 {
            command = arguments[index + 1]
        }
        let remainder = arguments.drop { $0 != flag }.dropFirst(2)

        var exitCode: Int32 = 0
        var finished = false

        Task { @MainActor in
            exitCode = await execute(command: command, arguments: Array(remainder))
            finished = true
        }

        // Spin the main run loop instead of blocking it. MusicKit's token and authorization
        // work needs a live run loop, so a semaphore here would deadlock.
        while !finished {
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        }
        exit(exitCode)
    }

    @MainActor
    private static func execute(command: String, arguments: [String]) async -> Int32 {
        // Before ProbeModel, which opens the real store (and with it iCloud sync).
        if command == "cloudcheck" {
            if arguments.first == "--purge-local" { return CloudCheck.purgeLocal() }
            return await CloudCheck.run(purge: arguments.first == "--purge")
        }
        // Also before ProbeModel: it works on a scratch store and never opens the real one.
        if command == "cloudschema" {
            return CloudSchema.initializeDevelopment()
        }
        let model = ProbeModel()

        switch command {
        case "env":
            await model.loadEnvironment()
            print(render(model.log))
            return 0

        case "catalog":
            let term = arguments.joined(separator: " ")
            guard !term.isEmpty else {
                FileHandle.standardError.write(Data("catalog needs a search term\n".utf8))
                return 2
            }
            await model.searchCatalog(term: term)
            print(render(model.log))
            return 0

        case "write":
            guard let songID = arguments.first, !songID.isEmpty else {
                FileHandle.standardError.write(Data("write needs a song id\n".utf8))
                return 2
            }
            await model.loadEnvironment()
            await model.testCreatePlaylist()
            guard model.probePlaylistID != nil else {
                print(render(model.log))
                FileHandle.standardError.write(Data("\nCreate failed; not attempting the add.\n".utf8))
                return 1
            }
            await model.testAddSong(id: songID)
            print(render(model.log))
            return 0

        case "retry-unresolved":
            await model.retryUnresolved()
            print(render(model.log))
            return 0

        case "readonly":
            // Checks a second process can read the store while the app has it open, which
            // widgets depend on.
            do {
                let store = try TracksStore(readOnly: true)
                let captures = try store.context.fetch(TracksStore.radioCaptures(limit: 5))
                model.log.append(.environment, "Read-only open", fields: [
                    ("backing", store.backing.description),
                    ("captures readable", String(captures.count)),
                    ("newest", captures.first.map { "\($0.title) by \($0.artistName)" } ?? "‹none›"),
                ])
            } catch {
                model.log.append(.error, "Read-only open failed", fields: [
                    ("error", String(describing: error)),
                ])
            }
            print(render(model.log))
            return 0

        case "captures":
            model.dumpCaptures()
            print(render(model.log))
            return 0

        case "script":
            // Runs AppleScript inside the sandbox, where Music's access groups apply. A script
            // run from Terminal isn't sandboxed, so it can't reproduce a -10004.
            let source = arguments.joined(separator: " ")
            var error: NSDictionary?
            let result = NSAppleScript(source: source)?.executeAndReturnError(&error)
            print(error.map { "error: \($0)" } ?? "result: \(result?.stringValue ?? String(describing: result))")
            return error == nil ? 0 : 1

        case "artists":
            // Read-only, so it can run while the app is open. Ignores what's already cached.
            do {
                let store = try TracksStore(readOnly: true)
                let history = ListeningHistory(try store.context.fetch(TracksStore.allCaptures()).map(\.stat))
                let requests = ArtistArtworkLookup.pending(in: history, lookedUp: [], limit: 25)
                let found = try await CatalogLookup().artistArtworkURLs(for: requests)
                for request in requests {
                    model.log.append(.environment, request.artistName, fields: [
                        ("song", request.hasCatalogID ? request.songID : "search: \(request.title)"),
                        ("picture", found[request.artistIdentity] ?? "‹none›"),
                    ])
                }
            } catch {
                model.log.append(.error, "Artist lookup failed", fields: [("error", String(describing: error))])
            }
            print(render(model.log))
            return 0

        case "genres":
            // Read-only like `artists`, and leaves the app's cache alone.
            do {
                let store = try TracksStore(readOnly: true)
                let history = ListeningHistory(try store.context.fetch(TracksStore.allCaptures()).map(\.stat))
                let requests = SongMetadataLookup.pending(in: history, lookedUp: [], limit: 25, searchLimit: 5)
                let found = try await CatalogLookup().songMetadata(for: requests)
                // Apple's own list too, to check how subgenres roll up to the genre shown.
                let ids = requests.filter(\.hasCatalogID).map { MusicItemID($0.songID) }
                let raw = ids.isEmpty ? [] : try await MusicCatalogResourceRequest<Song>(
                    matching: \.id,
                    memberOf: ids
                ).response().items.map { $0 }
                let names = Dictionary(raw.map { ($0.id.rawValue, $0.genreNames) }) { first, _ in first }
                for request in requests {
                    let metadata = found[request.songIdentity]
                    model.log.append(.environment, "\(request.title) · \(request.artistName)", fields: [
                        ("song", request.needsSearch ? "search" : request.songID),
                        ("genre", metadata?.genre ?? "‹none›"),
                        ("year", metadata?.releaseYear.map { String($0) } ?? "‹none›"),
                        ("catalog", names[request.songID]?.joined(separator: ", ") ?? "‹searched›"),
                    ])
                }
            } catch {
                model.log.append(.error, "Genre lookup failed", fields: [("error", String(describing: error))])
            }
            print(render(model.log))
            return 0

        case "stats":
            model.dumpStats()
            print(render(model.log))
            return 0

        case "playlists":
            await model.listAllPlaylists()
            print(render(model.log))
            return 0

        case "cleanup":
            await model.listProbePlaylists()
            print(render(model.log))
            return 0

        case "scrobble":
            await model.sendScrobbles()
            print(render(model.log))
            return 0

        case "lastfm":
            model.dumpLastFM()
            print(render(model.log))
            return 0

        case "forget":
            guard let text = arguments.first else {
                FileHandle.standardError.write(Data("forget needs some of a song title\n".utf8))
                return 2
            }
            model.forgetSong(matching: text)
            print(render(model.log))
            return 0

        case "menubar":
            model.setMenuBarStyle(arguments.first ?? "radio")
            print(render(model.log))
            return 0

        case "stations":
            await model.dumpStations()
            print(render(model.log))
            return 0

        default:
            FileHandle.standardError.write(Data((usage() + "\n").utf8))
            return 2
        }
    }

    @MainActor
    private static func render(_ log: ProbeLog) -> String {
        log.exportText
    }
}
#endif
