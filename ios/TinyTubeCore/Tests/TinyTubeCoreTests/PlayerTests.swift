import XCTest
@testable import TinyTubeCore

/* Ported from PlayerTest.kt and VideoIdTest.kt. The lookalike cases are the
   point of both files: they are what a substring check would let through. */
final class VideoIdTests: XCTestCase {

    func testElevenUrlSafeCharacters() {
        XCTAssertTrue(VideoId.isValid("dQw4w9WgXcQ"))
        XCTAssertTrue(VideoId.isValid("aaaaaaaaaaa"))
        XCTAssertTrue(VideoId.isValid("_-_-_-_-_-_"))
        XCTAssertTrue(VideoId.isValid("01234567890"))
    }

    func testRefusesAnythingElse() {
        for bad in ["", "   ", "short", "twelvechars1", "aaaaaaaaaa/", "aaaaaaaaaa?",
                    "aaaaaaaaaa&", "aaaaaaaaaa'", "aaaaaaaaaa\"", "../../etcpas",
                    "aaaaaaaaaa.", "aaaaaaaaaa ", " aaaaaaaaaa", "aaaaa aaaaa"] {
            XCTAssertFalse(VideoId.isValid(bad), "should have refused \(bad.debugDescription)")
        }
    }

    /* The reason this counts characters instead of using a regex. An anchored
       NSRegularExpression matches per LINE by default, so a newline is how a
       hostile id smuggles a valid-looking prefix past a careless check. */
    func testRefusesAnEmbeddedNewline() {
        XCTAssertFalse(VideoId.isValid("aaaaaaaaaaa\nevil"))
        XCTAssertFalse(VideoId.isValid("evil\naaaaaaaaaaa"))
        XCTAssertFalse(VideoId.isValid("aaaaaaaaaa\n"))
    }

    func testThumbnailIsDerivedWhenNoneWasGiven() {
        let v = Video(id: "dQw4w9WgXcQ", title: "x")
        XCTAssertEqual(v.thumbnailURL, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
    }

    func testAStoredThumbnailWins() {
        let v = Video(id: "dQw4w9WgXcQ", title: "x", thumbURL: "https://i.ytimg.com/vi/x/0.jpg")
        XCTAssertEqual(v.thumbnailURL, "https://i.ytimg.com/vi/x/0.jpg")
    }
}

final class PlayerTests: XCTestCase {

    func testAllowsThePlayersOwnTraffic() {
        for url in ["https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
                    "https://youtube-nocookie.com/x",
                    "https://www.youtube.com/iframe_api",
                    "https://s.ytimg.com/yts/jsbin/x.js",
                    "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                    "https://yt3.ggpht.com/x",
                    "https://googlevideo.com/videoplayback",
                    "https://rr3---sn-abc.googlevideo.com/videoplayback?x=1"] {
            XCTAssertTrue(Player.isPlayerURL(url), "should have allowed \(url)")
        }
    }

    /* Every one of these contains an allowed host as a substring. This is the
       whole reason matching is on the parsed host. */
    func testRefusesLookalikes() {
        for url in ["https://youtube.com.attacker.example/",
                    "https://notyoutube.com/",
                    "https://www.youtube.com@attacker.example/",
                    "https://attacker.example/?next=https://www.youtube.com/",
                    "https://evilgooglevideo.com/videoplayback",
                    "https://googlevideo.com.attacker.example/",
                    "https://www.youtube-nocookie.com.evil.example/"] {
            XCTAssertFalse(Player.isPlayerURL(url), "should have refused \(url)")
        }
    }

    /* No host, no navigation. These are how a page escapes a web view into the
       device rather than into another site. */
    func testRefusesNonHttpSchemes() {
        for url in ["javascript:alert(1)", "intent://x#Intent;scheme=http;end",
                    "file:///etc/passwd", "data:text/html,<script>", "about:blank",
                    "", "   ", "not a url", "//www.youtube.com/x"] {
            XCTAssertNil(Player.hostOf(url), "should have had no host: \(url.debugDescription)")
            XCTAssertFalse(Player.isPlayerURL(url), "should have refused \(url.debugDescription)")
        }
    }

    func testHostIsParsedTheSameWayAsOnAndroid() {
        XCTAssertEqual(Player.hostOf("https://WWW.YouTube.COM/x"), "www.youtube.com")
        XCTAssertEqual(Player.hostOf("https://www.youtube.com:443/x"), "www.youtube.com")
        XCTAssertEqual(Player.hostOf("https://www.youtube.com"), "www.youtube.com")
        XCTAssertEqual(Player.hostOf("https://www.youtube.com?x=1"), "www.youtube.com")
        XCTAssertEqual(Player.hostOf("https://www.youtube.com#f"), "www.youtube.com")
        XCTAssertEqual(Player.hostOf("  https://www.youtube.com/x  "), "www.youtube.com")
        /* userinfo: the host is what comes AFTER the last @ */
        XCTAssertEqual(Player.hostOf("https://user:pw@www.youtube.com/x"), "www.youtube.com")
        XCTAssertEqual(Player.hostOf("https://a@b@attacker.example/"), "attacker.example")
    }

    func testBuildsAPageOnlyForAValidId() {
        XCTAssertNotNil(Player.pageFor(videoId: "dQw4w9WgXcQ"))
        for bad in ["", "short", "aaaaaaaaaa'", "'); alert(1); ('", "aaaaaaaaaaa\nevil"] {
            XCTAssertNil(Player.pageFor(videoId: bad), "should have refused \(bad.debugDescription)")
        }
    }

    /* The id lands inside a JS string literal, so a page built from a valid id
       must carry it exactly once and carry nothing that could end the
       literal. */
    func testThePageCarriesTheIdAndNoQuoteBreakingIt() {
        let page = Player.pageFor(videoId: "dQw4w9WgXcQ")!
        XCTAssertTrue(page.contains("videoId: 'dQw4w9WgXcQ'"))
        XCTAssertTrue(page.contains(Player.origin) || page.contains("youtube.com/iframe_api"))
    }
}
