import AppIntents
import MusicKit
import TracksCore

/// "Play a song in Tracks", and the Play Music action in Shortcuts and Spotlight on iPhone and
/// Mac: a song, album, artist, playlist or genre by name, from whichever source has it.
///
/// On iPhone, Siri's own "Play Bad Guy in Tracks" arrives as a media intent instead (see
/// `PlayMediaIntentHandler`); both end in ``MediaRequestResolver``, so they find the same
/// things and switch source the same way.
struct PlayMusicIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play Music"
    static let description = IntentDescription(
        "Finds a song, album, artist, playlist or genre by name and plays it in Tracks, from the music source that's on or the other one, whichever has it.",
        categoryName: "Playback",
        searchKeywords: ["song", "album", "artist", "playlist", "genre", "queue", "play next"]
    )

    @Parameter(title: "Name", requestValueDialog: "What would you like to play?")
    var name: String

    @Parameter(title: "Kind", default: .anything)
    var kind: MediaKindOption

    @Parameter(title: "From", default: .automatic)
    var source: SourceOption

    @Parameter(title: "When", default: .now)
    var placement: PlacementOption

    @Parameter(title: "Shuffle", default: false)
    var shuffled: Bool

    static var parameterSummary: some ParameterSummary {
        When(\.$placement, .equalTo, .now) {
            Summary("Play \(\.$kind) \(\.$name) from \(\.$source)") {
                \.$placement
                \.$shuffled
            }
        } otherwise: {
            Summary("Play \(\.$kind) \(\.$name) from \(\.$source) \(\.$placement)") {
                \.$shuffled
            }
        }
    }

    init() {}

    init(name: String, kind: MediaKindOption = .anything, source: SourceOption = .automatic) {
        self.name = name
        self.kind = kind
        self.source = source
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let model = AppModel.shared
        guard !model.isDemoLaunch else { throw PlayIntentError.sampleData }
        let readings = MediaRequest.readings(of: name, kind: kind.kind).map { reading in
            var reading = reading
            reading.source = source.source
            return reading
        }
        let found: ResolvedMedia
        switch await MediaRequestResolver.answer(for: readings) {
        case .play(let matches):
            found = matches[0]
        case .ask(let matches):
            found = try await choose(among: matches)
        case .missing(let miss):
            throw PlayIntentError.problem(miss.message)
        }
        if let problem = await MediaRequestResolver.play(found.identifier, shuffled: shuffled, placement: placement.placement) {
            throw PlayIntentError.playing(problem)
        }
        MediaRequestResolver.remember([name])
        let title = found.spokenTitle
        let dialog: IntentDialog = switch placement {
        case .now: "Playing \(title)."
        case .next: "\(title) will play next."
        case .later: "Added \(title) to the queue."
        }
        return .result(dialog: dialog)
    }
}

extension PlayMusicIntent {
    /// Off the main actor, where App Intents asks, so the options never cross into it.
    nonisolated private func choose(among matches: [ResolvedMedia]) async throws -> ResolvedMedia {
        let options = matches.map { IntentChoiceOption(title: "\($0.spokenTitle)") }
        let chosen = try await requestChoice(between: options + [.cancel], dialog: "Which one?")
        guard let index = options.firstIndex(of: chosen) else { throw CancellationError() }
        return matches[index]
    }

    enum MediaKindOption: String, AppEnum {
        case anything, song, album, artist, playlist, station, genre

        static let typeDisplayRepresentation: TypeDisplayRepresentation = "Kind"
        static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
            .anything: "music",
            .song: "the song",
            .album: "the album",
            .artist: "the artist",
            .playlist: "the playlist",
            .station: "the station",
            .genre: "the genre",
        ]

        var kind: MediaKind { MediaKind(rawValue: rawValue) ?? .anything }
    }

    enum SourceOption: String, AppEnum {
        case automatic, appleMusic, yourMusic

        static let typeDisplayRepresentation: TypeDisplayRepresentation = "Source"
        static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
            .automatic: DisplayRepresentation(title: "Anywhere", subtitle: "The source that's on, then the other"),
            .appleMusic: "Apple Music",
            .yourMusic: "Your Music",
        ]

        var source: MusicSource? { MusicSource(rawValue: rawValue) }
    }

    enum PlacementOption: String, AppEnum {
        case now, next, later

        static let typeDisplayRepresentation: TypeDisplayRepresentation = "When"
        static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
            .now: "Now",
            .next: "Next",
            .later: "Last",
        ]

        var placement: MediaPlacement {
            switch self {
            case .now: .now
            case .next: .next
            case .later: .later
            }
        }
    }
}
