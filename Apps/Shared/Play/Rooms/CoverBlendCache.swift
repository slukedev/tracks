import SwiftUI

/// The blends drawn so far, let go of under memory pressure.
enum CoverBlendCache {
    static func picture(for key: CoverBlendKey) -> CGImage? {
        pictures.object(forKey: Box(key))?.picture
    }

    static func keep(_ picture: CGImage, for key: CoverBlendKey) {
        pictures.setObject(Picture(picture), forKey: Box(key), cost: picture.bytesPerRow * picture.height)
    }

    private static let pictures: NSCache<Box, Picture> = {
        let cache = NSCache<Box, Picture>()
        // A shelf of cards at three times is about 0.8 MB each, a hero about 9 MB.
        cache.totalCostLimit = 40 * 1024 * 1024
        return cache
    }()

    nonisolated private final class Box: NSObject {
        let key: CoverBlendKey
        init(_ key: CoverBlendKey) { self.key = key }
        override func isEqual(_ object: Any?) -> Bool { (object as? Box)?.key == key }
        override var hash: Int { key.hashValue }
    }

    nonisolated private final class Picture {
        let picture: CGImage
        init(_ picture: CGImage) { self.picture = picture }
    }
}
