import AppIntents
import TracksCore

/// A source to narrow Play and Library to, or All Sources, for Shortcuts and Focus.
struct SourceEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Source"
    static let defaultQuery = SourceQuery()

    /// The ``SourceSelection``'s stored value.
    let id: String
    let name: String
    let symbol: String

    var selection: SourceSelection { SourceSelection(rawValue: id) }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", image: .init(systemName: symbol))
    }

    static var all: SourceEntity {
        SourceEntity(id: SourceSelection.all.rawValue, name: String(localized: "All Sources"), symbol: "music.note.list")
    }

    init(id: String, name: String, symbol: String) {
        self.id = id
        self.name = name
        self.symbol = symbol
    }

    init(_ entry: SourceEntry) {
        self.init(id: SourceSelection.only(entry.id).rawValue, name: entry.name, symbol: entry.symbol)
    }
}

struct SourceQuery: EntityQuery {
    @MainActor
    func entities(for identifiers: [String]) async throws -> [SourceEntity] {
        let found = suggested()
        return identifiers.compactMap { id in found.first { $0.id == id } }
    }

    @MainActor
    func suggestedEntities() async throws -> [SourceEntity] {
        suggested()
    }

    @MainActor
    private func suggested() -> [SourceEntity] {
        [SourceEntity.all] + AppModel.shared.sourceEntries.filter(\.canPlay).map(SourceEntity.init)
    }
}

/// A Focus filter: which source Play and Library show while a Focus is on, as Apple Music at
/// the gym and the files on your Mac at your desk. When the Focus ends, it goes back.
struct SourceFocusFilter: SetFocusFilterIntent {
    static let title: LocalizedStringResource = "Choose Source"
    static let description = IntentDescription("Choose which source Play and Library show while this Focus is on.")

    @Parameter(title: "Source")
    var source: SourceEntity?

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(source?.name ?? String(localized: "Unchanged"))")
    }

    private static let beforeFocusKey = "sourceFilterBeforeFocus"

    @MainActor
    func perform() async throws -> some IntentResult {
        let model = AppModel.shared
        let defaults = UserDefaults.standard
        if let source {
            // The first Focus to change it remembers what to go back to.
            if defaults.string(forKey: Self.beforeFocusKey) == nil {
                defaults.set(model.sourceSelection.rawValue, forKey: Self.beforeFocusKey)
            }
            model.chooseSources(source.selection)
        } else if let before = defaults.string(forKey: Self.beforeFocusKey) {
            // The Focus ended, or stopped choosing: back to what it was.
            defaults.removeObject(forKey: Self.beforeFocusKey)
            model.chooseSources(SourceSelection(rawValue: before))
        }
        return .result()
    }
}

/// Narrows Play and Library to one source, or opens them to all, from Shortcuts or the Action
/// button. What's playing carries on.
struct ChooseSourceIntent: AppIntent {
    static let title: LocalizedStringResource = "Choose Source"
    static let description = IntentDescription("Chooses which source Play and Library show. What's playing carries on.")

    @Parameter(title: "Source")
    var source: SourceEntity

    static var parameterSummary: some ParameterSummary {
        Summary("Show \(\.$source)")
    }

    init() {}

    init(source: SourceEntity) {
        self.source = source
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let model = AppModel.shared
        let selection = source.selection
        if case .only(let chosen) = selection, !model.playableSources.contains(chosen) {
            throw PlayIntentError.problem(String(localized: "\(source.name) isn't added in Tracks."))
        }
        model.chooseSources(selection)
        return .result(dialog: "Showing \(source.name).")
    }
}
