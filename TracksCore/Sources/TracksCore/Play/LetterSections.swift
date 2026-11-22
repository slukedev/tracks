import Foundation

/// Names grouped by their first letter, as a long list is indexed in the car: A to Z, then
/// everything that doesn't start with a letter under "#". A car allows only so many sections,
/// so past that the smallest neighbouring letters share one, named by the first of them.
public struct LetterSection: Equatable, Sendable {
    public let letter: String
    /// Where each name in the section sits in the list it came from, in that list's order.
    public let indices: [Int]

    public init(letter: String, indices: [Int]) {
        self.letter = letter
        self.indices = indices
    }

    public static let other = "#"

    public static func sections(for names: [String], maximumSections: Int, locale: Locale = .current) -> [LetterSection] {
        guard !names.isEmpty, maximumSections > 0 else { return [] }
        var byLetter: [String: [Int]] = [:]
        for (index, name) in names.enumerated() {
            byLetter[letter(of: name, locale: locale), default: []].append(index)
        }
        var sections = byLetter
            .map { LetterSection(letter: $0.key, indices: $0.value) }
            .sorted { lhs, rhs in
                if (lhs.letter == other) != (rhs.letter == other) { return rhs.letter == other }
                return lhs.letter.compare(rhs.letter, locale: locale) == .orderedAscending
            }
        while sections.count > maximumSections {
            // The neighbours with the fewest names between them make the least crowded section.
            let pair = sections.indices.dropLast().min { lhs, rhs in
                sections[lhs].indices.count + sections[lhs + 1].indices.count
                    < sections[rhs].indices.count + sections[rhs + 1].indices.count
            } ?? 0
            let merged = LetterSection(letter: sections[pair].letter, indices: (sections[pair].indices + sections[pair + 1].indices).sorted())
            sections.replaceSubrange(pair...(pair + 1), with: [merged])
        }
        return sections
    }

    /// "É" files under "E", and a name starting with a digit or a symbol under "#".
    static func letter(of name: String, locale: Locale) -> String {
        guard let first = name.trimmingCharacters(in: .whitespacesAndNewlines).first, first.isLetter else { return other }
        return String(first).folding(options: [.diacriticInsensitive, .caseInsensitive], locale: locale).uppercased(with: locale)
    }
}
