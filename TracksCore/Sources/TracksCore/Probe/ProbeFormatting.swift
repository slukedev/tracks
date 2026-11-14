import Foundation

/// Renders probe values so a missing key is visible.
public enum ProbeFormat {
    /// Nil renders differently from empty.
    public static func value(_ value: Any?) -> String {
        switch value {
        case .none: "‹absent›"
        case let string as String: string.isEmpty ? "‹empty string›" : string
        case let number as NSNumber: number.stringValue
        case let date as Date: date.formatted(.iso8601)
        case let some?: String(describing: some)
        }
    }

    /// JSON-encodes a `Codable` value so opaque types can be inspected.
    ///
    /// The probe reads `PlayParameters` this way. It's `Codable` but has no public members,
    /// and its payload may carry `kind: "radioStation"` for stations.
    public static func json(_ value: some Encodable) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(value),
              let string = String(data: data, encoding: .utf8)
        else { return "‹not encodable›" }
        return string
    }
}
