import XCTest
@testable import TinyTubeCore

/* Ported from ChallengeTest.kt. */
final class ChallengeTests: XCTestCase {

    /* An odd sum has no whole-number solution and the gate would be
       unanswerable. Checked across the whole generator range rather than on a
       sample, because "sometimes unanswerable" is the failure that only shows
       up on somebody else's phone. */
    func testEveryGeneratedPuzzleHasAWholeNumberSolution() {
        for x in 12..<50 {
            for y in 3..<x {
                let p = Challenge.generate { from, _ in from == 12 ? x : y }
                XCTAssertEqual((p.sum + p.difference) % 2, 0, "x=\(x) y=\(y) has no whole answer")
                XCTAssertEqual(p.x, x)
                XCTAssertEqual(p.y, y)
            }
        }
    }

    /* A difference of zero gives X away as sum/2 at a glance; a negative one
       turns a quick puzzle into a sign-handling one. */
    func testTheDifferenceIsAlwaysPositive() {
        for x in 12..<50 {
            for y in 3..<x {
                let p = Challenge.generate { from, _ in from == 12 ? x : y }
                XCTAssertGreaterThan(p.difference, 0, "x=\(x) y=\(y)")
                XCTAssertGreaterThan(p.y, 0)
            }
        }
    }

    func testBothUnknownsHaveToBeRight() {
        let p = Challenge.Puzzle(sum: 30, difference: 10)   // x = 20, y = 10
        XCTAssertTrue(Challenge.isCorrect(p, xInput: "20", yInput: "10"))
        XCTAssertTrue(Challenge.isCorrect(p, xInput: " 20 ", yInput: "\t10\n"))
        XCTAssertFalse(Challenge.isCorrect(p, xInput: "20", yInput: "11"))
        XCTAssertFalse(Challenge.isCorrect(p, xInput: "10", yInput: "20"))
        XCTAssertFalse(Challenge.isCorrect(p, xInput: "20", yInput: ""))
    }

    /* A leading plus is ACCEPTED, on both platforms. This test asserted it was
       junk and was wrong about both — Kotlin's toIntOrNull and Swift's Int(_:)
       each read "+20" as 20. Neither is a problem: a parent who types it has
       answered correctly. Pinned here and in ChallengeTest.kt because it is the
       kind of agreement that could silently stop being one. */
    func testALeadingPlusIsAcceptedMatchingAndroid() {
        let p = Challenge.Puzzle(sum: 30, difference: 10)   // x = 20, y = 10
        XCTAssertTrue(Challenge.isCorrect(p, xInput: "+20", yInput: "10"))
        XCTAssertTrue(Challenge.isCorrect(p, xInput: "20", yInput: "+10"))
    }

    /* All of these come off a number keypad or a paste. None is an error worth
       a message — they are simply wrong. */
    func testJunkIsWrongRatherThanACrash() {
        let p = Challenge.Puzzle(sum: 30, difference: 10)
        for (x, y) in [("", ""), ("  ", "10"), ("abc", "10"), ("20", "ten"),
                       ("20.0", "10"), ("2 0", "10"),
                       ("99999999999999999999", "10"), ("-20", "-10")] {
            XCTAssertFalse(Challenge.isCorrect(p, xInput: x, yInput: y),
                           "should have been wrong: \(x.debugDescription)/\(y.debugDescription)")
        }
    }
}

/* Ported from YouTubeUrlsTest.kt. */
final class YouTubeUrlsTests: XCTestCase {

    private let ok = "UC" + String(repeating: "a", count: 22)

    func testChannelIdsAreExactlyUCPlusTwentyTwo() {
        XCTAssertTrue(YouTubeUrls.isValidChannelId(ok))
        XCTAssertTrue(YouTubeUrls.isValidChannelId("UCBR8-60-B28hp2BmDPdntcQ"))
        for bad in ["", "UC", "nope", "UC" + String(repeating: "a", count: 21),
                    "UC" + String(repeating: "a", count: 23),
                    "uc" + String(repeating: "a", count: 22),
                    "UC" + String(repeating: "a", count: 21) + "&x=1",
                    " UC" + String(repeating: "a", count: 22),
                    "UC" + String(repeating: "a", count: 22) + "\nevil"] {
            XCTAssertFalse(YouTubeUrls.isValidChannelId(bad), "should have refused \(bad.debugDescription)")
        }
    }

    func testParentModeMayBrowseYouTubeAndGoogleSignIn() {
        for url in ["https://m.youtube.com/", "https://www.youtube.com/@someone",
                    "https://accounts.google.com/signin", "https://google.com/",
                    "https://consent.youtube.com/x", "https://yt3.ggpht.com/x",
                    "https://lh3.googleusercontent.com/x", "https://fonts.gstatic.com/x"] {
            XCTAssertTrue(YouTubeUrls.isParentBrowsable(url), "should have allowed \(url)")
        }
    }

    /* Suffixes are matched on a leading dot, so a lookalike registered domain
       does not qualify. */
    func testParentModeRefusesLookalikesAndTheOpenWeb() {
        for url in ["https://google.com.attacker.example/", "https://notgoogle.com/",
                    "https://evilgooglevideo.com/", "https://attacker.example/",
                    "javascript:alert(1)", "file:///etc/passwd", ""] {
            XCTAssertFalse(YouTubeUrls.isParentBrowsable(url), "should have refused \(url.debugDescription)")
        }
    }

    /* The narrower list is the point: a signed-in Google page must never be
       reachable from the child's screen. */
    func testTheSignInHostsAreNotReachableFromThePlayer() {
        for url in ["https://google.com/", "https://accounts.google.com/signin",
                    "https://accounts.youtube.com/x", "https://consent.youtube.com/x",
                    "https://m.youtube.com/"] {
            XCTAssertFalse(Player.isPlayerURL(url), "the player must refuse \(url)")
        }
    }

    func testRecognisesAChannelPage() {
        for url in ["https://www.youtube.com/channel/\(ok)",
                    "https://m.youtube.com/channel/\(ok)/videos",
                    "https://www.youtube.com/@SomeChannel",
                    "https://m.youtube.com/@some.channel/videos"] {
            XCTAssertTrue(YouTubeUrls.isChannelPage(url), "should have been a channel page: \(url)")
        }
    }

    /* A watch page mentions its uploader and a search result lists a dozen
       channels, but neither IS a channel. */
    func testAWatchOrSearchPageIsNotAChannel() {
        for url in ["https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    "https://www.youtube.com/results?search_query=x",
                    "https://www.youtube.com/",
                    "https://www.youtube.com/feed/subscriptions",
                    "https://attacker.example/channel/\(ok)",
                    "https://www.youtube.com/x/channel/\(ok)",
                    "https://www.youtube.com/x/@handle"] {
            XCTAssertFalse(YouTubeUrls.isChannelPage(url), "should not have been a channel page: \(url)")
        }
    }

    func testReadsAChannelIdOutOfAUrl() {
        XCTAssertEqual(YouTubeUrls.channelIdFromURL("https://www.youtube.com/channel/\(ok)"), ok)
        XCTAssertEqual(YouTubeUrls.channelIdFromURL("https://m.youtube.com/channel/\(ok)/videos"), ok)
        XCTAssertNil(YouTubeUrls.channelIdFromURL("https://www.youtube.com/@handle"))
        XCTAssertNil(YouTubeUrls.channelIdFromURL("https://www.youtube.com/channel/short"))
        XCTAssertNil(YouTubeUrls.channelIdFromURL("javascript:alert(1)"))
    }

    func testReadsAHandleOutOfAUrl() {
        XCTAssertEqual(YouTubeUrls.handleFromURL("https://www.youtube.com/@SomeChannel"), "SomeChannel")
        XCTAssertEqual(YouTubeUrls.handleFromURL("https://m.youtube.com/@a.b-c_d/videos"), "a.b-c_d")
        XCTAssertNil(YouTubeUrls.handleFromURL("https://www.youtube.com/@ab"))      // too short
        XCTAssertNil(YouTubeUrls.handleFromURL("https://www.youtube.com/channel/\(ok)"))
        XCTAssertNil(YouTubeUrls.handleFromURL("file:///@handle"))
    }

    func testPathsAreReadWithoutQueryOrFragment() {
        XCTAssertEqual(YouTubeUrls.pathOf("https://www.youtube.com/a/b?x=1#f"), "/a/b")
        XCTAssertEqual(YouTubeUrls.pathOf("https://www.youtube.com"), "/")
        XCTAssertEqual(YouTubeUrls.pathOf("https://www.youtube.com/"), "/")
        XCTAssertEqual(YouTubeUrls.pathOf("https://www.youtube.com?x=1"), "/")
        XCTAssertNil(YouTubeUrls.pathOf("javascript:alert(1)"))
    }

    /* Whatever is stored is later fetched and drawn, and an og:image tag is
       page-controlled. */
    func testAvatarsAreOnlyKeptFromYouTubesOwnHosts() {
        XCTAssertTrue(YouTubeUrls.isAllowedAvatar("https://yt3.ggpht.com/x"))
        XCTAssertTrue(YouTubeUrls.isAllowedAvatar("https://yt3.googleusercontent.com/x"))
        for bad in ["https://attacker.example/x.jpg",
                    "https://yt3.ggpht.com.attacker.example/x.jpg",
                    "javascript:alert(1)", ""] {
            XCTAssertFalse(YouTubeUrls.isAllowedAvatar(bad), "should have refused \(bad.debugDescription)")
        }
    }
}
