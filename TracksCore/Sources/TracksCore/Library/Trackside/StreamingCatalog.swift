import Foundation
import Observation

/// What the streaming catalog asks a server. ``TracksideClient`` in the app; a fake in tests.
/// Everything here runs off the main actor, the asking and the reading of the answers.
public protocol CatalogService: Sendable {
    func catalogServices() async throws -> TracksideCatalogServiceList
    func searchCatalog(_ query: String, types: [TracksideClient.CatalogType], services: [String], limit: Int) async throws -> TracksideCatalogSearch
    func catalogItem(_ ref: String, cursor: String?, limit: Int?) async throws -> TracksideCatalogItem
    func catalogLibrary(service id: String, kind: TracksideCatalogLibraryPage.Kind, cursor: String?, limit: Int) async throws -> TracksideCatalogLibraryPage
    func catalogRecommendations(services: [String], limit: Int) async throws -> TracksideCatalogRecommendations
    func matchCatalog(_ request: TracksideCatalogMatchRequest) async throws -> TracksideCatalogMatch
    func setCatalogSaved(_ saved: Bool, ref: String) async throws -> TracksideCatalogSaved
}

extension TracksideClient: CatalogService {}

/// A music service on one of your servers. Two servers' services never mix.
public struct CatalogServiceAddress: Hashable, Sendable, Identifiable {
    public let serverID: String
    public let serviceID: String

    public var id: String { "\(serverID)/\(serviceID)" }

    public init(serverID: String, serviceID: String) {
        self.serverID = serverID
        self.serviceID = serviceID
    }
}

/// A service that can be searched now, and where it is.
public struct SearchableService: Identifiable, Sendable, Hashable {
    public let address: CatalogServiceAddress
    public let service: TracksideCatalogService

    public var id: String { address.id }
}

/// Why the services' results aren't there, in words to show calmly in their place.
public enum CatalogUnavailable: Error, Sendable, Equatable {
    /// The server can't reach its services right now.
    case unreachable
    /// Every service asked needs signing in again where the services are set up.
    case signedOut(service: String)
    /// The server didn't answer.
    case serverUnreachable
    /// Too many searches at once.
    case busy

    public var message: String {
        switch self {
        case .unreachable: String(localized: "Music services aren't available right now.")
        case .signedOut(let service): String(localized: "Sign in to \(service) again on your server.")
        case .serverUnreachable: String(localized: "Couldn't reach your server.")
        case .busy: String(localized: "Too many searches at once. Try again in a moment.")
        }
    }

    static func from(_ error: any Error, service: String?) -> CatalogUnavailable {
        switch error {
        case TracksideError.conflict(let reason) where reason == "service_signed_out":
            return .signedOut(service: service ?? String(localized: "the service"))
        case TracksideError.rateLimited: return .busy
        case TracksideError.unavailable: return .unreachable
        case is URLError: return .serverUnreachable
        default: return .unreachable
        }
    }
}

/// One search of the services on every server that has them.
public struct CatalogSearchAnswer: Sendable, Equatable {
    public var query: String
    /// What each server's services found, by server id.
    public var found: [String: TracksideCatalogSearch]
    /// Servers whose services couldn't be asked, and why.
    public var problems: [String: CatalogUnavailable]

    public init(query: String, found: [String: TracksideCatalogSearch] = [:], problems: [String: CatalogUnavailable] = [:]) {
        self.query = query
        self.found = found
        self.problems = problems
    }

    public var isEmpty: Bool { found.values.allSatisfy(\.isEmpty) }
}

/// The music services on every Trackside server that has them, for search, their pages and
/// playing. Shown only while `/me` says this user has them, so an older server shows nothing.
///
/// Apple Music is played natively on iPhone and Mac, so a server's Apple Music service is left
/// out here rather than offered twice.
@MainActor
@Observable
public final class StreamingCatalog {
    /// Each server's services, in the server's order, without Apple Music. A server without
    /// services isn't here.
    public private(set) var services: [String: [TracksideCatalogService]] = [:]
    /// Whether each server reaches its services now.
    public private(set) var states: [String: TracksideSpeakerList.State] = [:]
    /// What each server lets this device play through its relay.
    public private(set) var playback: [String: TracksideMe.Catalog] = [:]
    public private(set) var serverNames: [String: String] = [:]
    /// Each server's rows of its services' own picks, once asked for, Apple Music's left out.
    /// A server that has none, or hasn't been asked yet, isn't here.
    public private(set) var recommendations: [String: [TracksideCatalogShelf]] = [:]
    /// Items saved or removed here, by server and ref, until the server's own word is newer.
    public private(set) var saved: [String: Bool] = [:]

    @ObservationIgnored private var clients: [String: any CatalogService] = [:]
    @ObservationIgnored private var refreshes: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var recommendationReads: [String: Task<Void, Never>] = [:]
    /// When each server's picks were last read, and until when it asked not to be asked again.
    @ObservationIgnored private var recommendationsReadAt: [String: Date] = [:]
    @ObservationIgnored private var recommendationsHeldUntil: [String: Date] = [:]
    @ObservationIgnored private var retriedPartial: Set<String> = []
    /// Play has asked for picks, so servers whose services change are asked again.
    @ObservationIgnored private var wantsRecommendations = false
    @ObservationIgnored private let hidesNativeService: Bool

    public init(hidesNativeService: Bool = true) {
        self.hidesNativeService = hidesNativeService
    }

    // MARK: - Servers

    /// Starts offering a server's services, once `/me` says this user has them.
    public func attach(serverID: String, name: String, service: any CatalogService, features: TracksideMe.Catalog) {
        clients[serverID] = service
        playback[serverID] = features
        serverNames[serverID] = name
        if states[serverID] == nil { states[serverID] = .connecting }
        refresh(serverID)
    }

    /// Stops offering them: the server was removed, signed out, or no longer has them.
    public func detach(serverID: String) {
        clients[serverID] = nil
        refreshes.removeValue(forKey: serverID)?.cancel()
        forgetRecommendations(serverID)
        recommendations[serverID] = nil
        services[serverID] = nil
        states[serverID] = nil
        playback[serverID] = nil
        serverNames[serverID] = nil
    }

    public func isAttached(_ serverID: String) -> Bool { clients[serverID] != nil }

    /// Reads a server's services again, one read at a time.
    public func refresh(_ serverID: String) {
        guard let client = clients[serverID], refreshes[serverID] == nil else { return }
        refreshes[serverID] = Task { [weak self] in
            let answer = try? await client.catalogServices()
            guard let self else { return }
            refreshes[serverID] = nil
            guard clients[serverID] != nil else { return }
            guard let answer else {
                // Kept as they were: the server will say when they change.
                if services[serverID] == nil { states[serverID] = .unavailable }
                return
            }
            let shown = answer.services.filter { !(hidesNativeService && $0.isNativeOnApple) }
            if services[serverID] != shown { services[serverID] = shown }
            if states[serverID] != answer.state { states[serverID] = answer.state }
            if wantsRecommendations { loadRecommendations(serverID, now: .now) }
        }
    }

    /// A server's event: its services' own are kept; the rest are ignored.
    public func handle(_ event: TracksideEvent, from serverID: String) {
        guard clients[serverID] != nil else { return }
        switch event {
        case .hello, .catalogServices:
            // The server dropped its picks with the change: the next look asks again.
            forgetRecommendations(serverID)
            refresh(serverID)
        case .speakers(let state):
            // The services go and come with the speakers: both are reached the same way.
            if states[serverID] != state.state { states[serverID] = state.state }
            if state.state == .ok { refresh(serverID) }
        default:
            break
        }
    }

    // MARK: - What shows

    /// Whether any server has a service to offer.
    public var hasServices: Bool { services.values.contains { !$0.isEmpty } }

    /// The servers with services, in a steady order.
    public var serverIDs: [String] {
        services.keys.filter { !(services[$0] ?? []).isEmpty }.sorted {
            (serverNames[$0] ?? $0).localizedStandardCompare(serverNames[$1] ?? $1) == .orderedAscending
        }
    }

    /// Every service that can be searched now, server by server, in each server's order.
    public var searchable: [SearchableService] {
        serverIDs.flatMap { serverID in
            (services[serverID] ?? []).filter(\.isSearchable).map { SearchableService(address: CatalogServiceAddress(serverID: serverID, serviceID: $0.id), service: $0) }
        }
    }

    public func service(_ address: CatalogServiceAddress) -> TracksideCatalogService? {
        services[address.serverID]?.first { $0.id == address.serviceID }
    }

    /// Whether the server's services are out of reach for now.
    public func isUnavailable(_ serverID: String) -> Bool {
        states[serverID] == .unavailable
    }

    /// Whether this device can play a server's services itself.
    public func canPlayHere(_ serverID: String) -> Bool {
        playback[serverID]?.relay == true
    }

    public func client(for serverID: String) -> (any CatalogService)? { clients[serverID] }

    // MARK: - Asking

    /// Searches the services on every server that has them, or only the ones in `only`.
    /// Asked off the main actor, each server at once; a server that fails is said so, never
    /// the whole search.
    public func search(_ query: String, only: Set<CatalogServiceAddress>? = nil, limit: Int = 20) async -> CatalogSearchAnswer {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return CatalogSearchAnswer(query: query) }
        var asks: [(serverID: String, client: any CatalogService, serviceIDs: [String], name: String?)] = []
        for serverID in serverIDs {
            guard let client = clients[serverID] else { continue }
            let chosen = (services[serverID] ?? []).filter { service in
                service.isSearchable && (only?.contains(CatalogServiceAddress(serverID: serverID, serviceID: service.id)) ?? true)
            }
            guard !chosen.isEmpty else { continue }
            asks.append((serverID: serverID, client: client, serviceIDs: chosen.map(\.id), name: chosen.count == 1 ? chosen.first?.name : nil))
        }
        return await Self.ask(asks, query: trimmed, original: query, limit: limit)
    }

    @concurrent
    nonisolated private static func ask(_ asks: [(serverID: String, client: any CatalogService, serviceIDs: [String], name: String?)], query: String, original: String, limit: Int) async -> CatalogSearchAnswer {
        await withTaskGroup(of: (String, Result<TracksideCatalogSearch, CatalogUnavailable>).self) { group in
            for ask in asks {
                group.addTask {
                    do {
                        return (ask.serverID, .success(try await ask.client.searchCatalog(query, types: TracksideClient.CatalogType.standard, services: ask.serviceIDs, limit: limit)))
                    } catch {
                        return (ask.serverID, .failure(CatalogUnavailable.from(error, service: ask.name)))
                    }
                }
            }
            var answer = CatalogSearchAnswer(query: original)
            for await (serverID, result) in group {
                switch result {
                case .success(let found):
                    if found.isUnavailable {
                        answer.problems[serverID] = .unreachable
                    } else {
                        answer.found[serverID] = found
                    }
                case .failure(let problem):
                    answer.problems[serverID] = problem
                }
            }
            return answer
        }
    }

    /// An item's page, from the server whose listing gave its ref.
    public func item(_ ref: String, on serverID: String, cursor: String? = nil) async throws -> TracksideCatalogItem {
        guard let client = clients[serverID] else { throw TracksideError.featureUnavailable }
        return try await client.catalogItem(ref, cursor: cursor, limit: nil)
    }

    // MARK: - Saving to a service

    /// Whether a service's track can be saved to its library from here.
    public func canSave(track serviceID: String, on serverID: String) -> Bool {
        playback[serverID]?.save == true && service(CatalogServiceAddress(serverID: serverID, serviceID: serviceID))?.canSave("track") == true
    }

    /// Whether an item is saved in its service's library: as last set here, or as the server
    /// said. Nil when neither knows.
    public func isSaved(_ ref: String, on serverID: String, known: Bool? = nil) -> Bool? {
        saved["\(serverID)/\(ref)"] ?? known
    }

    /// Saves an item to its service's library, or removes it. Shown at once, and put back
    /// when the server refuses; one the service can't remove (`not_removable`) shows saved.
    public func setSaved(_ isSaved: Bool, ref: String, on serverID: String) async throws {
        guard let client = clients[serverID] else { throw TracksideError.featureUnavailable }
        let key = "\(serverID)/\(ref)"
        let was = saved[key]
        saved[key] = isSaved
        do {
            let answer = try await client.setCatalogSaved(isSaved, ref: ref)
            saved[key] = answer.isSaved
        } catch TracksideError.conflict(let reason) where reason == "not_removable" {
            // The service's library entry holds it for another source too: it stays saved.
            saved[key] = true
            throw TracksideError.conflict(reason: reason)
        } catch {
            saved[key] = was
            throw error
        }
    }

    // MARK: - The services' own picks

    /// How long a server's picks are kept before Play asks for them again.
    public static let recommendationsKept: TimeInterval = 20 * 60

    /// Asks each server for its services' picks, where they're missing or older than
    /// ``recommendationsKept``. A server that's busy, or can't reach its services, keeps what
    /// it showed until it can be asked again.
    public func loadRecommendations(now: Date = .now) {
        wantsRecommendations = true
        for serverID in clients.keys {
            loadRecommendations(serverID, now: now)
        }
    }

    private func loadRecommendations(_ serverID: String, now: Date) {
        guard let client = clients[serverID], recommendationReads[serverID] == nil else { return }
        let asked = recommendingServices(serverID)
        guard !asked.isEmpty else {
            if recommendations[serverID] != nil { recommendations[serverID] = nil }
            return
        }
        if let held = recommendationsHeldUntil[serverID], held > now { return }
        if let read = recommendationsReadAt[serverID], now.timeIntervalSince(read) < Self.recommendationsKept { return }
        recommendationReads[serverID] = Task { [weak self] in
            let result: Result<TracksideCatalogRecommendations, any Error>
            do {
                result = .success(try await client.catalogRecommendations(services: asked, limit: 20))
            } catch {
                result = .failure(error)
            }
            guard let self, !Task.isCancelled else { return }
            recommendationReads[serverID] = nil
            guard clients[serverID] != nil else { return }
            take(result, for: serverID, asked: Set(asked))
        }
    }

    private func take(_ result: Result<TracksideCatalogRecommendations, any Error>, for serverID: String, asked: Set<String>) {
        switch result {
        case .success(let answer):
            let shown = answer.shelves.filter { asked.contains($0.serviceID) && !$0.items.isEmpty }
            if recommendations[serverID] != shown { recommendations[serverID] = shown }
            recommendationsReadAt[serverID] = .now
            if answer.isComplete {
                retriedPartial.remove(serverID)
            } else if retriedPartial.insert(serverID).inserted {
                // A service that didn't answer in time may have by the end of the server's
                // short hold on the partial answer: kept briefly, so Play asks once more.
                recommendationsReadAt[serverID] = .now.addingTimeInterval(12 - Self.recommendationsKept)
            }
        case .failure(let error):
            held(by: error, serverID: serverID)
        }
    }

    private func held(by error: any Error, serverID: String) {
        switch error {
        case TracksideError.notFound, TracksideError.featureUnavailable:
            // No such route for this account: nothing to show until something changes.
            recommendations[serverID] = []
            recommendationsReadAt[serverID] = .now
        case TracksideError.rateLimited(let retryAfter):
            recommendationsHeldUntil[serverID] = .now.addingTimeInterval(retryAfter ?? 30)
        case TracksideError.unavailable(_, let retryAfter):
            recommendationsHeldUntil[serverID] = .now.addingTimeInterval(retryAfter ?? 10)
        default:
            // Offline or refused: what was shown stays, and the next look asks again.
            break
        }
    }

    /// The services on a server whose picks Play shows: signed in, recommending, and not
    /// Apple Music, which plays natively.
    private func recommendingServices(_ serverID: String) -> [String] {
        guard playback[serverID]?.recommendations == true, states[serverID] != .unavailable else { return [] }
        return (services[serverID] ?? []).filter { $0.isSignedIn && $0.can(.recommendations) }.map(\.id)
    }

    private func forgetRecommendations(_ serverID: String) {
        recommendationReads.removeValue(forKey: serverID)?.cancel()
        recommendationsReadAt[serverID] = nil
        recommendationsHeldUntil[serverID] = nil
        retriedPartial.remove(serverID)
    }

    /// What's saved on a service, where it says it has a library.
    public func library(of address: CatalogServiceAddress, kind: TracksideCatalogLibraryPage.Kind = .playlist, cursor: String? = nil) async throws -> TracksideCatalogLibraryPage {
        guard let client = clients[address.serverID], service(address)?.can(.library) == true else { throw TracksideError.featureUnavailable }
        return try await client.catalogLibrary(service: address.serviceID, kind: kind, cursor: cursor, limit: 100)
    }
}
