import XCTest
@testable import TinyTube

/* Tests for the parts of the app that are NOT in TinyTubeCore.
 *
 * The pure layer is tested on Linux, in the `ios-core` job, and nothing here
 * duplicates it — re-asserting `VideoId` on a simulator would burn macOS
 * minutes to learn what a Linux runner already established in a second.
 *
 * What is left for this target is the handful of decisions that only exist
 * once there is an app: the constants the two spikes settled, and the fact
 * that the app target links the core at all. */
final class AppSurfaceTests: XCTestCase {

    /* The iOS blocker is a fixed inset because no iOS API returns the
       composited pixels of a playing video. That decision is a number, and a
       number with nothing pinning it drifts. */
    func testTheBottomBlockerMatchesAndroidsFallback() {
        /* android/app/src/main/res/values/dimens.xml — player_bottom_block.
           Same player, same decision, so the same figure. */
        XCTAssertEqual(PlayerChrome.bottomBlockPoints, 16)
    }

    /* Small on purpose: too tall and it covers the seek bar, which is the one
       control the reveal corner exists to reach. This is the direction the
       constant is allowed to be wrong in. */
    func testTheBottomBlockerIsSmallEnoughToLeaveTheSeekBarReachable() {
        XCTAssertGreaterThan(PlayerChrome.bottomBlockPoints, 0)
        XCTAssertLessThanOrEqual(PlayerChrome.bottomBlockPoints, 24)
    }

    /* Google shows a login form only if the user agent reads as a real browser.
       Both halves have to be there — `Version/` without `Safari/` is not a
       string Safari has ever sent. */
    func testTheSafariSuffixCarriesBothTokens() {
        XCTAssertTrue(BrowserUserAgent.safariSuffix.contains("Version/"))
        XCTAssertTrue(BrowserUserAgent.safariSuffix.contains("Safari/"))
    }

    /* Appended, not substituted. WKWebView's own string is the honest half —
       real OS version, real WebKit build — and it has to survive, or the app
       starts claiming to be a device that does not exist. */
    func testAppendingTheSuffixToWKWebViewsDefaultReadsAsSafari() {
        let wkDefault = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

        XCTAssertFalse(
            BrowserUserAgent.looksLikeSafari(wkDefault),
            "WKWebView's default is missing Version/…Safari/… — that absence is what Google matches on"
        )
        XCTAssertTrue(
            BrowserUserAgent.looksLikeSafari(wkDefault + " " + BrowserUserAgent.safariSuffix)
        )
    }

    /* There is deliberately no "does the app link the core" test here. The app
       target calls VideoId directly, so linkage is a COMPILE failure if it ever
       breaks — earlier and louder than any assertion, and without a function
       that exists only to be asserted on. */
}
