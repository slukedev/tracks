import Testing
import Foundation
@testable import TracksCore

@Suite("Trackside speakers on the wire")
struct TracksideSpeakersTests {
    typealias T = TracksideTesting

    private func fixture<Value: Decodable>(_ type: Value.Type, _ name: String) throws -> Value {
        try TracksideJSON.decoder.decode(type, from: TracksideFixtures.data(name))
    }

    @Test("the list reads every speaker: a grouped pair, one off and one out of reach")
    func list() throws {
        let list = try fixture(TracksideSpeakerList.self, "speakers.json")
        #expect(list.state == .ok)
        #expect(list.speakers.map(\.name) == ["Kitchen", "Living Room", "Bedroom", "Office"])

        let kitchen = list.speakers[0]
        #expect(kitchen.kind == .speaker)
        #expect(kitchen.link == .sonos)
        #expect(kitchen.capabilities.isSuperset(of: [.pause, .nextPrevious, .seek, .volume, .group, .repeatMode]))
        #expect(kitchen.isGroupLeader)
        #expect(kitchen.memberIDs == ["o7sny3kfnvsjbbhx01f2j"])
        #expect(kitchen.groupVolume == 40)
        #expect(kitchen.volume == 35)
        #expect(kitchen.nowPlaying?.songID == "sdjcz08vf67h7cx28snq6")
        #expect(kitchen.nowPlaying?.duration == 261.64)
        #expect(kitchen.nowPlaying?.songKey == "halo\u{1F}beyonce")
        #expect(kitchen.playback?.state == .playing)
        #expect(kitchen.playback?.repeatMode == .off)
        #expect(kitchen.context?.kind == .album)
        #expect(kitchen.revision == 1042)

        let livingRoom = list.speakers[1]
        #expect(livingRoom.isGroupMember)
        #expect(livingRoom.group?.leaderID == "o4j8fccgajqsqxancbfcz")

        let bedroom = list.speakers[2]
        #expect(bedroom.availability == .poweredOff)
        #expect(bedroom.capabilities == [.play, .power])
        #expect(bedroom.powered == false)
        #expect(bedroom.playback == nil)
        #expect(!bedroom.hasSong)

        let office = list.speakers[3]
        #expect(office.availability == .unavailable)
        #expect(office.capabilities.isEmpty)
        #expect(!office.isReachable)
    }

    @Test("a speaker playing a service's track, the out-of-reach list, and this phone's own stream")
    func shapes() throws {
        let speaker = try fixture(TracksideSpeaker.self, "speaker.json")
        #expect(speaker.nowPlaying?.ref == "mc1.dHJhY2sfc3BvdGlmeR81VDhFRFVEcUtjczZPU093RXNmcUc3")
        #expect(speaker.nowPlaying?.songID == nil)
        #expect(speaker.nowPlaying?.serviceID == "bqtyy4kcexcsg6wa0y4w5")
        #expect(speaker.context == TracksideSpeaker.Context(kind: .catalog, name: "Late Night Chill"))
        #expect(speaker.playback?.shuffle == true)
        #expect(speaker.playback?.repeatMode == .all)
        #expect(!speaker.can(.group))

        let unavailable = try fixture(TracksideSpeakerList.self, "speakers-unavailable.json")
        #expect(unavailable.state == .unavailable)
        #expect(unavailable.speakers.isEmpty)
        #expect(unavailable.since == T.date("2026-11-14T09:28:00.000Z"))

        let relay = try fixture(TracksideSpeaker.self, "relay-speaker.json")
        #expect(relay.kind == .phone)
        #expect(relay.link == .relay)
        #expect(relay.relay?.isThisDevice == true)
        #expect(relay.relay?.sessionID == "h9zvca9p26dymt4v7cxj5")
    }

    @Test("a command's answer carries what the speaker should be once it's done")
    func command() throws {
        let command = try fixture(TracksideSpeakerCommand.self, "speaker-command.json")
        #expect(command.commandID == "j7bwksg3pg57nf95cwz0b")
        #expect(command.action == "pause")
        #expect(command.settleBy == T.date("2026-11-14T09:30:05.000Z"))
        #expect(command.speaker.playback?.state == .paused)
        #expect(command.speaker.revision == 980)
    }

    @Test("a speaker's Up Next starts at the song playing")
    func queue() throws {
        let queue = try fixture(TracksideSpeakerQueue.self, "speaker-queue.json")
        #expect(queue.currentIndex == 0)
        #expect(queue.items.map(\.title) == ["Halo", "Don't Stop Me Now"])
        #expect(queue.upNext.map(\.itemID) == ["0b1c2d3e4f5061728394a5b6c7d8e9f0"])
        #expect(queue.items[0].songID == "sdjcz08vf67h7cx28snq6")
        #expect(queue.items[1].ref != nil)
        #expect(queue.nextCursor == "eyJvIjoyfQ")
        #expect(queue.total == 12)
    }

    @Test("a queue to play is written as the server's own example reads")
    func playRequest() throws {
        let written = TracksideSpeakerPlayRequest(
            items: [.song("sdjcz08vf67h7cx28snq6"), .ref("mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0"), .song("sqfnw07xdpvnbah038cz8")],
            startIndex: 0,
            position: 81,
            shuffle: false,
            repeatMode: .off,
            context: TracksideSpeaker.Context(id: "a3pqh4wnvzkjak75rxbft", kind: .album, name: "I Am... Sasha Fierce")
        )
        let example = try fixture(TracksideSpeakerPlayRequest.self, "speaker-play-request.json")
        #expect(written == example)
        let body = try JSONSerialization.jsonObject(with: TracksideJSON.encoder.encode(written)) as? [String: Any]
        let items = body?["items"] as? [[String: String]]
        #expect(items?[0] == ["song_id": "sdjcz08vf67h7cx28snq6"])
        #expect(items?[1].keys.sorted() == ["ref"])
        #expect(body?["position_ms"] as? Int == 81_000)
        #expect(body?["mode"] as? String == "replace")
    }

    @Test("values and fields a newer server adds never lose the speaker")
    func newerServer() throws {
        let speaker = try T.decode(TracksideSpeaker.self, #"""
        {"id": "O4J8FCCGAJQSQXANCBFCZ", "name": "Den", "kind": "soundbar", "protocol": "zigbee",
         "availability": "napping", "capabilities": ["pause", "teleport", 7, "volume"],
         "volume": "loud", "playback": {"state": "dancing", "position_ms": 10, "position_at": "2026-11-14T09:30:00Z", "repeat": "twice"},
         "now_playing": {"title": "Halo", "origin": "radio", "duration_ms": "long"}, "lasers": true, "revision": 3}
        """#)
        #expect(speaker.id == "o4j8fccgajqsqxancbfcz")
        #expect(speaker.kind == .unknown)
        #expect(speaker.link == .unknown)
        #expect(speaker.availability == .unknown)
        #expect(speaker.capabilities == [.pause, .volume])
        #expect(speaker.volume == nil)
        #expect(speaker.playback?.state == .unknown)
        #expect(speaker.playback?.repeatMode == .unknown)
        #expect(speaker.nowPlaying?.origin == .unknown)
        #expect(speaker.nowPlaying?.duration == nil)
        #expect(!speaker.canChoose(hasMusicHere: true))

        let list = try T.decode(TracksideSpeakerList.self, #"{"speakers": [{"name": "No id"}, {"id": "o1", "name": "Hall"}], "state": "rebooting"}"#)
        #expect(list.speakers.map(\.id) == ["o1"])
        #expect(list.state == .unknown)
    }

    @Test("each speaker event reads as its own, and one in a shape this build doesn't know is ignored")
    func events() throws {
        func event(_ name: String, _ file: String) throws -> TracksideEvent {
            TracksideEvent(ServerSentEvent(id: "9", event: name, data: String(decoding: try TracksideFixtures.data(file), as: UTF8.self)))
        }
        guard case .speakerChanged(let updated) = try event("speaker_updated", "speaker.json") else { Issue.record("speaker_updated"); return }
        #expect(updated.name == "Bedroom")
        guard case .speakerChanged = try event("speaker_added", "speaker.json") else { Issue.record("speaker_added"); return }
        #expect(try event("speaker_removed", "speaker-removed-event.json") == .speakerRemoved(id: "osm579gnwm00ysy1hbvhq"))
        #expect(try event("speaker_queue", "speaker-queue-event.json") == .speakerQueue(id: "o4j8fccgajqsqxancbfcz", revision: 1043))
        #expect(try event("speaker_progress", "speaker-progress-event.json") == .speakerProgress(TracksideSpeakerProgress(
            id: "o4j8fccgajqsqxancbfcz", positionMS: 111_000, positionAt: T.date("2026-11-14T09:30:30.000Z"), revision: 1042, state: .playing
        )))
        #expect(try event("speaker_command", "speaker-command-event.json") == .speakerCommand(TracksideSpeakerCommandOutcome(
            commandID: "j8r94fwwwtm0mgm02w6gx", speakerID: "o0jjvqt740t3wm3jvetx2", outcome: .failed,
            error: .init(code: "command_failed", message: "The speaker did not accept the command.")
        )))
        #expect(try event("speakers", "speakers-event.json") == .speakers(TracksideSpeakersState(state: .unavailable, since: T.date("2026-11-14T09:28:00.000Z"))))
        #expect(TracksideEvent(ServerSentEvent(id: nil, event: "speaker_progress", data: #"{"id": "o1"}"#)) == .other("speaker_progress"))
        #expect(TracksideEvent(ServerSentEvent(id: nil, event: "speaker_party", data: "{}")) == .other("speaker_party"))
    }

    @Test("the server says it has speakers, and /me says this key may use and control them")
    func detection() throws {
        let capabilities = try fixture(TracksideCapabilities.self, "capabilities.json")
        #expect(capabilities.features?.speakers == true)
        let me = try fixture(TracksideMe.self, "me.json")
        #expect(me.features?.speakers?.control == true)
        #expect(me.features?.catalog?.relay == true)
        #expect(me.features?.acquisition?.instantPlay == true)
        #expect(me.user.permissions?.canUseSpeakers == true)
        #expect(me.user.permissions?.canPlayServices == true)
        let old = try fixture(TracksideMe.self, "me-without-acquisition.json")
        #expect(old.features?.speakers == nil)
        #expect(old.features?.catalog == nil)
        let odd = try T.decode(TracksideMe.Features.self, #"{"speakers": true, "acquisition": {"instant_play": true}}"#)
        #expect(odd.speakers == nil)
        #expect(odd.acquisition?.instantPlay == true)
    }

    @Test("a speaker's refusals are told apart, and a speaker out of reach is worth asking again")
    func refusals() throws {
        let unsupported = TracksideClient.error(from: try TracksideFixtures.data("error-speaker-unsupported.json"), response: HTTPURLResponse(url: T.home, statusCode: 422, httpVersion: nil, headerFields: nil)!)
        #expect(unsupported == .unsupported(capability: "seek"))
        let away = TracksideClient.error(from: try TracksideFixtures.data("error-speakers-unavailable.json"), response: HTTPURLResponse(url: T.home, statusCode: 503, httpVersion: nil, headerFields: ["Retry-After": "10"])!)
        #expect(away == .unavailable(code: "speakers_unavailable", retryAfter: 10))
        #expect(SpeakerProblem.reason(for: unsupported) == .unsupported)
        #expect(SpeakerProblem.reason(for: away) == .speakersUnavailable)
        #expect(SpeakerProblem.reason(for: TracksideError.conflict(reason: "powered_off")) == .poweredOff)
        #expect(SpeakerProblem.reason(for: URLError(.notConnectedToInternet)) == .serverUnreachable)
    }

    @Test("commands go to the speaker's own routes, with a key only when one is given")
    func requests() async throws {
        let answer = String(decoding: try TracksideFixtures.data("speaker-command.json"), as: UTF8.self)
        let stub = StubTracksideTransport(status: 202, body: answer)
        let client = T.client(stub)
        _ = try await client.control(speaker: "o1", .seek(120.5))
        _ = try await client.control(speaker: "o1", .next, retryKey: "retry-1")
        _ = try await client.change(speaker: "o1", TracksideSpeakerChange(volume: 140))
        _ = try await client.group(speaker: "o1", with: ["o2", "o3"])
        _ = try await client.ungroup(speaker: "o2")
        _ = try await client.play(on: "o1", TracksideSpeakerPlayRequest(items: [.song("s1")]), key: "cast-1")
        _ = try await client.transfer(from: "o1", to: "o2", key: "move-1")
        let asked = stub.requests
        #expect(asked.map(\.httpMethod) == ["POST", "POST", "PATCH", "PUT", "DELETE", "POST", "POST"])
        #expect(asked.map { $0.url?.path() } == [
            "/api/v1/speakers/o1/playback", "/api/v1/speakers/o1/playback", "/api/v1/speakers/o1",
            "/api/v1/speakers/o1/members", "/api/v1/speakers/o2/group", "/api/v1/speakers/o1/play", "/api/v1/speakers/o1/transfer",
        ])
        #expect(asked.map { $0.value(forHTTPHeaderField: "Idempotency-Key") } == [nil, "retry-1", nil, nil, nil, "cast-1", "move-1"])
        func body(_ index: Int) throws -> [String: Any] {
            try #require(JSONSerialization.jsonObject(with: try #require(asked[index].httpBody)) as? [String: Any])
        }
        #expect(try body(0)["action"] as? String == "seek")
        #expect(try body(0)["position_ms"] as? Int == 120_500)
        #expect(try body(1).keys.sorted() == ["action"])
        #expect(try body(2) as? [String: Int] == ["volume": 100])
        #expect(try body(3)["member_ids"] as? [String] == ["o2", "o3"])
        #expect(try body(6)["to"] as? String == "o2")
        #expect(try body(6)["keep_playing_here"] as? Bool == false)
    }

    @Test("the list is read with its ETag, and the server's clock comes with it")
    func listAnswer() async throws {
        let list = String(decoding: try TracksideFixtures.data("speakers.json"), as: UTF8.self)
        let stub = StubTracksideTransport { request -> StubTracksideTransport.Answer in
            if request.value(forHTTPHeaderField: "If-None-Match") == #"W/"1042""# {
                return (status: 304, body: "", headers: [:])
            }
            return (status: 200, body: list, headers: ["ETag": #"W/"1042""#, "Date": "Sat, 14 Nov 2026 09:30:00 GMT"])
        }
        let client = T.client(stub)
        guard case .changed(let read, let etag, let date) = try await client.speakers(ifNoneMatch: nil) else {
            Issue.record("expected a list")
            return
        }
        #expect(read.speakers.count == 4)
        #expect(etag == #"W/"1042""#)
        #expect(date == T.date("2026-11-14T09:30:00.000Z"))
        #expect(try await client.speakers(ifNoneMatch: etag) == .unchanged)
    }

    @Test("a clock only a header's rounding apart isn't taken for one that's off")
    func skew() {
        let now = T.date("2026-11-14T09:30:00.400Z")
        #expect(TracksideClock.skew(serverDate: T.date("2026-11-14T09:30:00.000Z"), receivedAt: now) == 0)
        #expect(abs(TracksideClock.skew(serverDate: T.date("2026-11-14T09:30:05.000Z"), receivedAt: now) - 4.6) < 0.001)
        #expect(TracksideClock.skew(serverDate: T.date("2026-11-14T09:29:50.000Z"), receivedAt: now).rounded() == -10)
    }
}
