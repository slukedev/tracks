import Foundation

/// MD5, for the one thing that asks for it: the Subsonic API's `t` token, md5(password + salt).
///
/// Android has no CryptoKit, and pulling in swift-crypto would build all of BoringSSL into the
/// app for one hash.
public enum MD5Digest {
    public static func hash(data: Data) -> [UInt8] {
        var message = [UInt8](data)
        let bitLength = UInt64(message.count) * 8
        message.append(0x80)
        while message.count % 64 != 56 { message.append(0) }
        for index in 0..<8 { message.append(UInt8(truncatingIfNeeded: bitLength >> (8 * UInt64(index)))) }

        var a0: UInt32 = 0x67452301
        var b0: UInt32 = 0xefcdab89
        var c0: UInt32 = 0x98badcfe
        var d0: UInt32 = 0x10325476

        for chunk in stride(from: 0, to: message.count, by: 64) {
            var words = [UInt32](repeating: 0, count: 16)
            for index in 0..<16 {
                let base = chunk + index * 4
                words[index] = UInt32(message[base])
                    | UInt32(message[base + 1]) << 8
                    | UInt32(message[base + 2]) << 16
                    | UInt32(message[base + 3]) << 24
            }
            var a = a0, b = b0, c = c0, d = d0
            for step in 0..<64 {
                var f: UInt32
                let g: Int
                switch step {
                case 0..<16:
                    f = (b & c) | (~b & d)
                    g = step
                case 16..<32:
                    f = (d & b) | (~d & c)
                    g = (5 * step + 1) % 16
                case 32..<48:
                    f = b ^ c ^ d
                    g = (3 * step + 5) % 16
                default:
                    f = c ^ (b | ~d)
                    g = (7 * step) % 16
                }
                f = f &+ a &+ constants[step] &+ words[g]
                a = d
                d = c
                c = b
                b = b &+ (f << shifts[step] | f >> (32 - shifts[step]))
            }
            a0 = a0 &+ a
            b0 = b0 &+ b
            c0 = c0 &+ c
            d0 = d0 &+ d
        }

        return [a0, b0, c0, d0].flatMap { word in
            (0..<4).map { UInt8(truncatingIfNeeded: word >> (8 * UInt32($0))) }
        }
    }

    private static let shifts: [UInt32] = [
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    ]

    /// floor(abs(sin(i + 1)) × 2³²).
    private static let constants: [UInt32] = (0..<64).map {
        UInt32(truncatingIfNeeded: Int64(abs(sin(Double($0 + 1))) * 4_294_967_296))
    }
}

#if !canImport(CryptoKit)
/// CryptoKit's spelling, so MotifCore's `SubsonicClient` compiles unchanged.
public enum Insecure {
    public enum MD5 {
        public static func hash(data: Data) -> [UInt8] { MD5Digest.hash(data: data) }
    }
}
#endif
