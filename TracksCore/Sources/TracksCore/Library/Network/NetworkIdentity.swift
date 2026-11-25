import Foundation
import Synchronization
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// Which network the device is on, as far as it can tell without asking for location: its
/// routers' addresses, its IPv4 subnets, and the unique-local IPv6 prefixes a home router
/// hands out, which stay the same on that router and differ from one to the next. Kept as a
/// digest, never the addresses themselves.
///
/// Not proof: a hostile network can copy all of it. It's a much higher bar than answering
/// at one address, which is all a nearby Subsonic server's address asks.
public enum NetworkIdentity {
    /// Nil when there's nothing to tell one network from another by.
    public static func make(gateways: [String], prefixes: [String]) -> String? {
        let gateways = Set(gateways.map { $0.lowercased() }).sorted()
        let prefixes = Set(prefixes.map { $0.lowercased() }).sorted()
        guard !gateways.isEmpty || !prefixes.isEmpty else { return nil }
        let text = "network-v1\u{1F}" + gateways.joined(separator: ",") + "\u{1F}" + prefixes.joined(separator: ",")
        return SHA1.digest(Array(text.utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    /// The IPv4 subnets and unique-local IPv6 /64 prefixes on the interfaces named.
    public static func localPrefixes(onInterfaces names: Set<String>) -> [String] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }
        var found: Set<String> = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let entry = cursor?.pointee {
            cursor = entry.ifa_next
            guard let name = entry.ifa_name.map({ String(cString: $0) }), names.contains(name), let address = entry.ifa_addr else { continue }
            switch Int32(address.pointee.sa_family) {
            case AF_INET6:
                let bytes = address.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { pointer in
                    withUnsafeBytes(of: pointer.pointee.sin6_addr) { Array($0) }
                }
                if let prefix = uniqueLocalPrefix(bytes) { found.insert(prefix) }
            case AF_INET:
                guard let mask = entry.ifa_netmask else { continue }
                let bytes = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { pointer in
                    withUnsafeBytes(of: pointer.pointee.sin_addr) { Array($0) }
                }
                let maskBytes = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { pointer in
                    withUnsafeBytes(of: pointer.pointee.sin_addr) { Array($0) }
                }
                if let subnet = subnet(bytes, mask: maskBytes) { found.insert(subnet) }
            default:
                continue
            }
        }
        return found.sorted()
    }

    /// The /64 of a unique-local address (fc00::/7). Global prefixes are left out: many
    /// providers change them every day.
    static func uniqueLocalPrefix(_ bytes: [UInt8]) -> String? {
        guard bytes.count == 16, bytes[0] & 0xfe == 0xfc else { return nil }
        let groups = stride(from: 0, to: 8, by: 2).map { String(format: "%x", Int(bytes[$0]) << 8 | Int(bytes[$0 + 1])) }
        return groups.joined(separator: ":") + "::/64"
    }

    static func subnet(_ address: [UInt8], mask: [UInt8]) -> String? {
        guard address.count == 4, mask.count == 4 else { return nil }
        let network = zip(address, mask).map { String($0 & $1) }.joined(separator: ".")
        let bits = mask.reduce(0) { $0 + $1.nonzeroBitCount }
        return "\(network)/\(bits)"
    }
}

/// The network the device is on now (``NetworkIdentity``), kept up to date by the app as the
/// path changes, read wherever credentials are about to go to a nearby address.
public final class CurrentNetwork: Sendable {
    public static let shared = CurrentNetwork()

    private let value = Mutex<String?>(nil)

    public init() {}

    public var identity: String? {
        get { value.withLock { $0 } }
        set { value.withLock { $0 = newValue } }
    }
}
