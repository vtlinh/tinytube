import XCTest
@testable import TinyTubeCore

/* Ported from UploadsTest.kt and the ordering half of LibraryTest.kt. */
final class UploadsTests: XCTestCase {

    private let a = "aaaaaaaaaaa"
    private let b = "bbbbbbbbbbb"
    private let c = "ccccccccccc"

    private func reply(_ entries: String...) -> String {
        "{\"channel\":\"UC\(String(repeating: "a", count: 22))\",\"videos\":[\(entries.joined(separator: ","))]}"
    }

    private func bare(_ id: String) -> String { "\"\(id)\"" }

    private func full(_ id: String, title: String = "T", published: Int64? = 100, thumb: String? = nil) -> String {
        var parts = ["\"id\":\"\(id)\"", "\"title\":\"\(title)\""]
        if let published { parts.append("\"published\":\(published)") }
        parts.append("\"thumb\":\"\(thumb ?? "https://i.ytimg.com/vi/\(id)/hqdefault.jpg")\"")
        return "{\(parts.joined(separator: ","))}"
    }

    func testAFullEntryBecomesAVideo() {
        let v = Uploads.parse(reply(full(a, title: "First", published: 500)), known: [:]).first!
        XCTAssertEqual(v.id, a)
        XCTAssertEqual(v.title, "First")
        XCTAssertEqual(v.publishedAt, 500)
        XCTAssertEqual(v.thumbnailURL, "https://i.ytimg.com/vi/\(a)/hqdefault.jpg")
    }

    /* The whole point of the protocol. */
    func testABareIdIsFilledInFromWhatWeAlreadyHad() {
        let cached = Video(id: a, title: "Cached title", publishedAt: 500,
                           thumbURL: "https://i.ytimg.com/vi/\(a)/hqdefault.jpg")
        let v = Uploads.parse(reply(bare(a)), known: [a: cached])
        XCTAssertEqual(v, [cached])
    }

    func testBareAndFullEntriesMixInTheOrderSent() {
        let cached = Video(id: b, title: "Cached", publishedAt: 400)
        let out = Uploads.parse(
            reply(full(a, title: "New", published: 500), bare(b), full(c, title: "Also new", published: 300)),
            known: [b: cached]
        )
        XCTAssertEqual(out.map(\.id), [a, b, c])
        XCTAssertEqual(out.map(\.title), ["New", "Cached", "Also new"])
    }

    /* Dropping it is right: it comes back in full next time, because we will no
       longer claim to know it. A blank tile would not fix itself. */
    func testABareIdWeDoNotHaveIsDropped() {
        let out = Uploads.parse(reply(bare(a), full(b, title: "Have this")), known: [:])
        XCTAssertEqual(out.map(\.id), [b])
    }

    /* The Worker validated these too. This validates them again: an id ends up
       in a URL and in a JS string literal. */
    func testAnIdThatIsNotValidIsRefusedHoweverItArrives() {
        for bad in ["short", "aaaaaaaaaa/", "../../etcpasswd", "aaaaaaaaaaaa", ""] {
            let out = Uploads.parse(
                reply(full(bad), bare(bad), full(a)),
                known: [bad: Video(id: bad, title: "x")]
            )
            XCTAssertEqual(out.map(\.id), [a], "should have refused \(bad.debugDescription)")
        }
    }

    func testARepeatedIdAppearsOnce() {
        let out = Uploads.parse(reply(full(a, title: "First"), full(a, title: "Second")), known: [:])
        XCTAssertEqual(out.count, 1)
        XCTAssertEqual(out.first?.title, "First")
    }

    func testAnEntryWithNoTitleFallsBackToItsId() {
        XCTAssertEqual(Uploads.parse(reply(full(a, title: "")), known: [:]).first?.title, a)
        XCTAssertEqual(Uploads.parse(reply(full(a, title: "   ")), known: [:]).first?.title, a)
    }

    /* 1970 is not an upload time. */
    func testAMissingOrZeroPublishedTimeReadsAsNoDate() {
        XCTAssertNil(Uploads.parse(reply(full(a, published: nil)), known: [:]).first?.publishedAt)
        XCTAssertNil(Uploads.parse(reply(full(a, published: 0)), known: [:]).first?.publishedAt)
    }

    /* Whatever is stored here is later fetched and drawn. */
    func testAThumbnailFromAnywhereElseIsDropped() {
        for bad in ["https://attacker.example/x.jpg",
                    "https://i.ytimg.com.attacker.example/x.jpg",
                    "http://evil/i.ytimg.com/x.jpg",
                    "javascript:alert(1)",
                    "//i.ytimg.com/x.jpg",
                    "not a url"] {
            XCTAssertNil(Uploads.thumb(bad, id: a), "should have refused \(bad)")
            let v = Uploads.parse(reply(full(a, thumb: bad)), known: [:]).first!
            XCTAssertEqual(v.thumbnailURL, "https://i.ytimg.com/vi/\(a)/hqdefault.jpg")
        }
    }

    func testYouTubesOwnThumbnailHostsAreKept() {
        XCTAssertEqual(Uploads.thumb("https://i.ytimg.com/vi/\(a)/hqdefault.jpg", id: a),
                       "https://i.ytimg.com/vi/\(a)/hqdefault.jpg")
        XCTAssertEqual(Uploads.thumb("https://img.youtube.com/vi/\(a)/0.jpg", id: a),
                       "https://img.youtube.com/vi/\(a)/0.jpg")
    }

    /* Every one of these is reachable: a Worker that is down and returning an
       error page, a truncated body, a captive portal, a shape from a future
       Worker. Each has to come back empty so the caller keeps what it had. */
    func testJunkComesBackEmpty() {
        for junk in ["", "   ", "not json", "<html>502 Bad Gateway</html>",
                     "{}", "{\"videos\":null}", "{\"videos\":{}}", "[1,2,3]",
                     "{\"videos\":[null,1,true,[],{}]}",
                     "{\"videos\":[{\"id\":null}]}"] {
            XCTAssertTrue(Uploads.parse(junk, known: [:]).isEmpty,
                          "should have been empty for \(junk.debugDescription)")
        }
    }

    func testTheRequestNamesTheChannelAndTheIdsWeHave() throws {
        let body = Uploads.request(channelId: "UC" + String(repeating: "a", count: 22),
                                   known: [a, "short", "", b])
        let json = try JSONSerialization.jsonObject(with: body.data(using: .utf8)!) as! [String: Any]
        XCTAssertEqual(json["channel"] as? String, "UC" + String(repeating: "a", count: 22))
        XCTAssertEqual(json["known"] as? [String], [a, b])
    }
}

final class LibraryTests: XCTestCase {

    private func v(_ id: String, _ title: String? = nil, at: Int64? = nil) -> Video {
        Video(id: id, title: title ?? id, publishedAt: at)
    }

    func testCollatePutsTheNewestUploadFirstAcrossChannels() {
        let out = Library.collate([
            v("aaaaaaaaaaa", at: 100),
            v("bbbbbbbbbbb", at: 50),
            v("ccccccccccc", at: 300),
            v("ddddddddddd", at: 75),
        ])
        XCTAssertEqual(out.map(\.id), ["ccccccccccc", "aaaaaaaaaaa", "ddddddddddd", "bbbbbbbbbbb"])
    }

    /* Swift's sort is not stable, so this is bought explicitly rather than
       inherited. A grid that reshuffled equal timestamps between refreshes
       would move a tile out from under a finger. */
    func testEqualTimestampsKeepTheirArrivalOrder() {
        let out = Library.newestFirst([
            v("aaaaaaaaaaa", at: 10), v("bbbbbbbbbbb", at: 10), v("ccccccccccc", at: 10),
        ])
        XCTAssertEqual(out.map(\.id), ["aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"])
    }

    func testAnUndatedVideoSortsBelowEveryDatedOne() {
        let out = Library.newestFirst([
            v("aaaaaaaaaaa"), v("bbbbbbbbbbb", at: 1), v("ccccccccccc", at: 2),
        ])
        XCTAssertEqual(out.map(\.id), ["ccccccccccc", "bbbbbbbbbbb", "aaaaaaaaaaa"])
    }

    func testAVideoInTwoChannelsAppearsOnce() {
        let out = Library.collate([
            v("aaaaaaaaaaa", "As channel one has it", at: 10),
            v("bbbbbbbbbbb", at: 5),
            v("aaaaaaaaaaa", "As channel two has it", at: 10),
        ])
        XCTAssertEqual(out.map(\.id), ["aaaaaaaaaaa", "bbbbbbbbbbb"])
        XCTAssertEqual(out.first?.title, "As channel one has it")
    }

    func testDatedVideosKeepTheDateTheFeedGaveThem() {
        let out = Library.datePositions(
            ordered: [v("aaaaaaaaaaa"), v("bbbbbbbbbbb")],
            dated: ["aaaaaaaaaaa": 500, "bbbbbbbbbbb": 400],
            fallback: 1000
        )
        XCTAssertEqual(out.map(\.publishedAt), [500, 400])
    }

    func testUndatedVideosArePlacedBelowTheLastDatedOne() {
        let out = Library.datePositions(
            ordered: [v("aaaaaaaaaaa"), v("bbbbbbbbbbb"), v("ccccccccccc"), v("ddddddddddd")],
            dated: ["aaaaaaaaaaa": 500, "bbbbbbbbbbb": 400],
            fallback: 1000
        )
        XCTAssertEqual(out.map(\.publishedAt), [500, 400, 399, 398])
    }

    func testEntriesAboveTheNewestDatedOneStartFromTheFallback() {
        let out = Library.datePositions(
            ordered: [v("aaaaaaaaaaa"), v("bbbbbbbbbbb"), v("ccccccccccc")],
            dated: ["ccccccccccc": 400],
            fallback: 1000
        )
        XCTAssertEqual(out.map(\.publishedAt), [999, 998, 400])
    }

    /* The property the grid's order depends on. */
    func testTheResultAlwaysDescendsEvenWhenADateDisagrees() {
        let out = Library.datePositions(
            ordered: [v("aaaaaaaaaaa"), v("bbbbbbbbbbb"), v("ccccccccccc")],
            dated: ["aaaaaaaaaaa": 100, "bbbbbbbbbbb": 900, "ccccccccccc": 50],
            fallback: 1000
        )
        let keys = out.compactMap(\.publishedAt)
        XCTAssertEqual(keys.count, 3)
        for i in 1..<keys.count {
            XCTAssertLessThan(keys[i], keys[i - 1], "\(keys) is not descending")
        }
        XCTAssertEqual(out.map(\.id), ["aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"])
    }

    /* Swift dictionaries have no order, so the caller passes the approved
       list's. Iterating the dictionary itself would give a grid that reordered
       between launches. */
    func testFlattenFollowsTheChannelOrderItIsGiven() {
        let byChannel = ["UC1": [v("aaaaaaaaaaa"), v("bbbbbbbbbbb")], "UC2": [v("ccccccccccc")]]
        XCTAssertEqual(
            Library.flatten(byChannel: byChannel, channelOrder: ["UC1", "UC2"]).map(\.id),
            ["aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"]
        )
        XCTAssertEqual(
            Library.flatten(byChannel: byChannel, channelOrder: ["UC2", "UC1"]).map(\.id),
            ["ccccccccccc", "aaaaaaaaaaa", "bbbbbbbbbbb"]
        )
    }

    /* A channel removed while its tab was open. Showing the whole library
       instead would be the worst possible reading of "unknown". */
    func testAnUnknownChannelShowsNothingNotEverything() {
        let byChannel = ["UC1": [v("aaaaaaaaaaa")]]
        XCTAssertTrue(Library.forChannel(byChannel: byChannel, channelId: "UC-gone").isEmpty)
    }
}
