import Testing
import Foundation
import Synchronization
@testable import TracksCore

/// A Lidarr queue of so many items, a page at a time.
private final class Queue: LidarrTransport {
    let total: Int
    /// What the server says the total is, which a moving queue gets wrong.
    let claimedTotal: Int?
    private let pages = Mutex<[Int]>([])

    init(total: Int, claimedTotal: Int? = nil) {
        self.total = total
        self.claimedTotal = claimedTotal ?? total
    }

    var asked: [Int] { pages.withLock { $0 } }

    func send(_ request: URLRequest) async throws -> (Data, Int) {
        let query = Dictionary((URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") }) { a, _ in a }
        let page = Int(query["page"] ?? "1") ?? 1
        let size = Int(query["pageSize"] ?? "10") ?? 10
        pages.withLock { $0.append(page) }
        let range = ((page - 1) * size)..<min(total, page * size)
        let records = range.map { #"{"id":\#($0 + 1),"title":"Item \#($0 + 1)","status":"downloading"}"# }.joined(separator: ",")
        let totalField = claimedTotal.map { #","totalRecords":\#($0)"# } ?? ""
        return (Data(#"{"page":\#(page),"pageSize":\#(size)\#(totalField),"records":[\#(records)]}"#.utf8), 200)
    }
}

@Suite("Lidarr's queue")
struct LidarrQueuePagingTests {
    private let server = LidarrServer(url: URL(string: "http://192.168.1.20:8686")!)

    @Test("every item past the first hundred is read", arguments: [0, 1, 99, 100, 101, 250, 999])
    func everyItem(total: Int) async throws {
        let queue = Queue(total: total)
        let items = try await LidarrClient(server: server, apiKey: "k", transport: queue).queue()
        #expect(items.count == total)
        #expect(items.map(\.id) == Array(1...max(1, total)).prefix(total).map { $0 })
        #expect(queue.asked.count == max(1, Int((Double(total) / 100).rounded(.up))))
    }

    @Test("a queue that grows while it's read stops at the last full page, not on and on")
    func growing() async throws {
        let queue = Queue(total: 5_000, claimedTotal: 120)
        let items = try await LidarrClient(server: server, apiKey: "k", transport: queue).queue()
        #expect(items.count == 200)
        #expect(queue.asked == [1, 2])
    }

    @Test("a queue too long to read is read only as far as the page limit")
    func limit() async throws {
        let queue = Queue(total: 5_000)
        let items = try await LidarrClient(server: server, apiKey: "k", transport: queue).queue()
        #expect(items.count == LidarrClient.queuePageSize * LidarrClient.queuePageLimit)
        #expect(queue.asked.count == LidarrClient.queuePageLimit)
    }
}

@Suite("Lidarr's pictures")
struct LidarrPictureTests {
    @Test("a picture offered over plain http is asked for over https", arguments: [
        ("http://assets.fanart.tv/a.jpg", "https://assets.fanart.tv/a.jpg"),
        ("HTTP://coverartarchive.org/release/x/front.jpg", "https://coverartarchive.org/release/x/front.jpg"),
        ("https://imagecache.lidarr.audio/v1/a.jpg", "https://imagecache.lidarr.audio/v1/a.jpg"),
    ])
    func encrypted(offered: String, asked: String) {
        #expect(LidarrImage.encrypted(offered)?.absoluteString == asked)
    }
}
