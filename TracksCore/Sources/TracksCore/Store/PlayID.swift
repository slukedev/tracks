import Foundation

/// A play's lasting id: the same in the store, the history index, the play a server is told
/// of (its listen id) and Trackside Cloud.
///
/// A new play gets a random UUID when it starts. A play kept before ids existed gets one made
/// from what it is, a UUIDv5 of the key the history index has always known it by
/// (`ms␟kind␟source␟song identity`, plus the copy's ordinal for exact copies), so every device
/// that holds the row through iCloud gives it the same id without asking the others.
public enum PlayID {
    /// The namespace every derived id is made in. Never change it: every derived id would
    /// change with it. Android derives the same ids from the same namespace.
    public static let namespace = UUID(uuidString: "8F6B1D3C-2A47-5E90-B1C8-4D2E7F9A0B36")!

    /// The key a player puts a play's id under in what it tells capture, so the history row
    /// and the play its server is told of share one id.
    public static let observationKey = "playID"

    /// A fresh id for a play that's starting now.
    public static func make() -> String {
        UUID().uuidString.lowercased()
    }

    /// A stored id worth keeping: a UUID in any case. Anything else is made again.
    public static func normalized(_ text: String?) -> String? {
        guard let text, let uuid = UUID(uuidString: text.trimmingCharacters(in: .whitespaces)) else { return nil }
        return uuid.uuidString.lowercased()
    }

    /// The key the history index has known a play by: when, how, from where and what.
    public static func contentKey(capturedAt: Date, kind: String, source: String, songIdentity: String) -> String {
        let milliseconds = Int64((capturedAt.timeIntervalSince1970 * 1000).rounded())
        return "\(milliseconds)\u{1F}\(kind)\u{1F}\(source)\u{1F}\(songIdentity)"
    }

    /// The key of one copy of a play the store holds more than once: the first is the key
    /// itself, the rest have their ordinal on the end.
    public static func copyKey(_ key: String, copy: Int) -> String {
        copy == 0 ? key : "\(key)\u{1F}\(copy)"
    }

    /// The id a play kept before ids existed is given.
    public static func derived(fromKey key: String) -> String {
        uuidV5(namespace: namespace, name: key).uuidString.lowercased()
    }

    /// A scrobble brought in from Last.fm, the same id on every device and platform, so the
    /// same scrobble imported anywhere is one play. Android's `PlayIds.lastFm` makes the same.
    public static func lastFM(playedAt: Date, title: String, artistName: String) -> String {
        let seconds = Int64(playedAt.timeIntervalSince1970.rounded(.down))
        let key = "\(seconds * 1000)\u{1F}lastFM\u{1F}appleMusic\u{1F}\(SongIdentity.key(title: title, artistName: artistName))"
        return derived(fromKey: key)
    }

    /// Ids for rows that have none, the same on every device for the same rows. Exact copies
    /// are put in an order every device agrees on (`precedes`) before they're numbered.
    public static func backfill<Row>(_ rows: [Row], key: (Row) -> String, precedes: (Row, Row) -> Bool) -> [(row: Row, id: String)] {
        let grouped = Dictionary(grouping: rows.indices, by: { key(rows[$0]) })
        var result: [(row: Row, id: String)] = []
        result.reserveCapacity(rows.count)
        for (content, indices) in grouped.sorted(by: { $0.key < $1.key }) {
            let ordered = indices.sorted { precedes(rows[$0], rows[$1]) }
            for (copy, index) in ordered.enumerated() {
                result.append((rows[index], derived(fromKey: copyKey(content, copy: copy))))
            }
        }
        return result
    }

    /// RFC 9562 version 5: SHA-1 of the namespace's bytes and the name's UTF-8, with the
    /// version and variant bits set.
    public static func uuidV5(namespace: UUID, name: String) -> UUID {
        let space = namespace.uuid
        var message: [UInt8] = [
            space.0, space.1, space.2, space.3, space.4, space.5, space.6, space.7,
            space.8, space.9, space.10, space.11, space.12, space.13, space.14, space.15,
        ]
        message.append(contentsOf: Array(name.utf8))
        var hash = SHA1.digest(message)
        hash[6] = (hash[6] & 0x0F) | 0x50
        hash[8] = (hash[8] & 0x3F) | 0x80
        return UUID(uuid: (
            hash[0], hash[1], hash[2], hash[3], hash[4], hash[5], hash[6], hash[7],
            hash[8], hash[9], hash[10], hash[11], hash[12], hash[13], hash[14], hash[15]
        ))
    }
}

/// SHA-1, for UUIDv5 only, where the standard names it. Not for anything that needs to resist
/// an attacker. Written out so the ids can be made, and tested, on every platform.
enum SHA1 {
    static func digest(_ message: [UInt8]) -> [UInt8] {
        var h0: UInt32 = 0x67452301
        var h1: UInt32 = 0xEFCDAB89
        var h2: UInt32 = 0x98BADCFE
        var h3: UInt32 = 0x10325476
        var h4: UInt32 = 0xC3D2E1F0

        var data = message
        let bitLength = UInt64(message.count) * 8
        data.append(0x80)
        while data.count % 64 != 56 { data.append(0) }
        for shift in stride(from: 56, through: 0, by: -8) {
            data.append(UInt8(truncatingIfNeeded: bitLength >> UInt64(shift)))
        }

        var w = [UInt32](repeating: 0, count: 80)
        for chunk in stride(from: 0, to: data.count, by: 64) {
            for i in 0..<16 {
                let b = chunk + i * 4
                w[i] = UInt32(data[b]) << 24 | UInt32(data[b + 1]) << 16 | UInt32(data[b + 2]) << 8 | UInt32(data[b + 3])
            }
            for i in 16..<80 {
                w[i] = rotate(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1)
            }
            var a = h0, b = h1, c = h2, d = h3, e = h4
            for i in 0..<80 {
                let f: UInt32
                let k: UInt32
                switch i {
                case 0..<20:
                    f = (b & c) | (~b & d)
                    k = 0x5A827999
                case 20..<40:
                    f = b ^ c ^ d
                    k = 0x6ED9EBA1
                case 40..<60:
                    f = (b & c) | (b & d) | (c & d)
                    k = 0x8F1BBCDC
                default:
                    f = b ^ c ^ d
                    k = 0xCA62C1D6
                }
                let temp = rotate(a, 5) &+ f &+ e &+ k &+ w[i]
                e = d
                d = c
                c = rotate(b, 30)
                b = a
                a = temp
            }
            h0 = h0 &+ a
            h1 = h1 &+ b
            h2 = h2 &+ c
            h3 = h3 &+ d
            h4 = h4 &+ e
        }

        var out: [UInt8] = []
        out.reserveCapacity(20)
        for value in [h0, h1, h2, h3, h4] {
            out.append(UInt8(truncatingIfNeeded: value >> 24))
            out.append(UInt8(truncatingIfNeeded: value >> 16))
            out.append(UInt8(truncatingIfNeeded: value >> 8))
            out.append(UInt8(truncatingIfNeeded: value))
        }
        return out
    }

    private static func rotate(_ value: UInt32, _ count: UInt32) -> UInt32 {
        (value << count) | (value >> (32 - count))
    }
}
