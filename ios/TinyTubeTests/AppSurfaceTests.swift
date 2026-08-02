import XCTest
@testable import TinyTube

/* Tests for the parts of the app that are NOT in TinyTubeCore.
 *
 * The pure layer is tested on Linux, in the `ios-core` job, and nothing here
 * duplicates it — re-asserting `VideoId` or `Chrome` on a simulator would burn
 * macOS minutes to learn what a Linux runner already established in a second.
 *
 * What is left for this target is what only exists once there is an app: the
 * store's rules about when a measurement may be trusted, and the user-agent
 * evasion. The CAPTURE itself is not tested here — ReplayKit needs a device and
 * a consenting human, so it is the one part of this that CI cannot reach. That
 * gap is stated rather than papered over with a test that proves nothing. */
final class AppSurfaceTests: XCTestCase {

    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        /* A suite of its own, so a test never reads or writes the real app's
           stored measurement. */
        defaults = UserDefaults(suiteName: "AppSurfaceTests")!
        BlockHeightStore.clear(defaults)
    }

    override func tearDown() {
        BlockHeightStore.clear(defaults)
        super.tearDown()
    }

    // MARK: - The fallback

    /* Small on purpose: too tall and it covers the seek bar, which is the one
       control the reveal corner exists to reach. This is the direction the
       constant is allowed to be wrong in. */
    func testTheFallbackMatchesAndroidsAndStaysSmall() {
        /* android/app/src/main/res/values/dimens.xml — player_bottom_block. */
        XCTAssertEqual(PlayerChrome.fallbackPoints, 16)
        XCTAssertGreaterThan(PlayerChrome.fallbackPoints, 0)
    }

    /* A measurement that came back as most of the screen would block most of
       the player. Chrome refuses implausible geometry in ratio terms; this is
       the outer bound in points. */
    func testAnImplausiblyTallMeasurementIsRefused() {
        XCTAssertTrue(PlayerChrome.isPlausible(60, screenHeight: 800))
        XCTAssertFalse(PlayerChrome.isPlausible(400, screenHeight: 800))
        XCTAssertFalse(PlayerChrome.isPlausible(0, screenHeight: 800))
        XCTAssertFalse(PlayerChrome.isPlausible(-1, screenHeight: 800))
    }

    // MARK: - When a stored measurement may be trusted

    /* Nothing stored yet, so the fallback stands and a capture is worth
       starting. This is the state of every fresh install. */
    func testWithNothingStoredItFallsBackAndWantsToMeasure() {
        XCTAssertNil(BlockHeightStore.get(defaults))
        XCTAssertTrue(BlockHeightStore.shouldMeasure(defaults))
    }

    /* The whole point of the store: measure once, then never again. */
    func testOnceMeasuredItNeverAsksAgain() {
        BlockHeightStore.put(64, defaults: defaults)
        XCTAssertEqual(BlockHeightStore.get(defaults), 64)
        XCTAssertFalse(
            BlockHeightStore.shouldMeasure(defaults),
            "a stored answer must stop the capture — otherwise the consent alert returns"
        )
    }

    /* Versioning is GONE, and this pins the consequence rather than leaving it
       to be discovered. A stored answer now survives anything a new build might
       want to say about it — including a stale `block_version` left behind by a
       build that did version. The only ways to invalidate an answer are a
       different display and `clear`.

       This is a real loss, not a tidy-up: bumping the version was what let a
       corrected measurement reach a device that had already stored a bad
       number. It is recorded in BlockHeightStore so it can be put back. */
    func testAStoredAnswerOutlivesALegacyVersionKey() {
        BlockHeightStore.put(64, defaults: defaults)
        defaults.set(999, forKey: "block_version")

        XCTAssertEqual(
            BlockHeightStore.get(defaults), 64,
            "nothing reads block_version any more, so it must not affect the answer"
        )
        XCTAssertFalse(BlockHeightStore.shouldMeasure(defaults))
    }

    /* And an answer measured on a different display is not an answer for this
       one — a restore onto another device, or Display Zoom being changed. */
    func testAnAnswerFromAnotherDisplayIsIgnored() {
        BlockHeightStore.put(64, defaults: defaults)
        defaults.set("999x999@1.0", forKey: "block_display")

        XCTAssertNil(BlockHeightStore.get(defaults))
        XCTAssertTrue(BlockHeightStore.shouldMeasure(defaults))
    }

    // MARK: - Retrying until it works

    /* The give-up counter is GONE, and this is the property that replaced it:
       a device that has never produced an answer keeps asking, restart after
       restart, instead of stopping after three.

       What that costs is the reason the counter existed — ReplayKit's consent
       alert, which a CHILD can be the one looking at, now returns on every
       launch until some capture succeeds. That was accepted knowingly: the
       measurement is failing on real hardware, and a blocker stuck on its
       fallback leaves YouTube's seek bar reachable in the player, which is the
       failure the app exists to prevent. See BlockHeightStore. */
    func testItNeverGivesUpWhileThereIsNoAnswer() {
        for launch in 1...25 {
            XCTAssertTrue(
                BlockHeightStore.shouldMeasure(defaults),
                "launch \(launch): with nothing stored it must still be willing to measure"
            )
        }
    }

    /* But an answer stops it dead — the consent alert must not return on a
       device where the measurement already worked. */
    func testASuccessfulAnswerStopsTheRetrying() {
        BlockHeightStore.put(64, defaults: defaults)
        XCTAssertFalse(BlockHeightStore.shouldMeasure(defaults))
    }

    /* Unchanged and still the sharpest edge here: only a real answer is ever
       written. `Chrome.blockHeight` returns nil for "could not tell" precisely
       so a failure cannot be stored as though it were a measurement — the exact
       Android bug that made the feature silently do nothing. */
    func testClearingLeavesNothingThatLooksLikeAnAnswer() {
        BlockHeightStore.put(64, defaults: defaults)
        BlockHeightStore.clear(defaults)

        XCTAssertNil(BlockHeightStore.get(defaults))
        XCTAssertTrue(BlockHeightStore.shouldMeasure(defaults))
    }

    /* The blocker in use follows the store, and refuses a stored value that is
       out of range rather than applying it. */
    func testCurrentPointsPrefersAPlausibleStoredAnswer() {
        XCTAssertEqual(PlayerChrome.currentPoints(defaults), PlayerChrome.fallbackPoints)

        BlockHeightStore.put(64, defaults: defaults)
        XCTAssertEqual(PlayerChrome.currentPoints(defaults), 64)

        BlockHeightStore.put(100_000, defaults: defaults)
        XCTAssertEqual(
            PlayerChrome.currentPoints(defaults), PlayerChrome.fallbackPoints,
            "an implausible stored answer must not be applied just because it is stored"
        )
    }

    // MARK: - The user-agent evasion

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

    // MARK: - Which pixels are the bottom of the screen

    /* THE BUG THAT MADE THE MEASUREMENT DO NOTHING, pinned.
     *
     * ReplayKit hands over frames in the device's own orientation and says how
     * to read them with RPVideoSampleOrientationKey. The capture ignored it and
     * took the buffer's last rows as the screen's bottom — true in portrait,
     * and wrong in the landscape the player forces, where those rows are a band
     * down one side. Chrome then looked for a full-width horizontal seek bar in
     * a strip that could not contain one.
     *
     * The capture itself needs a device and a consenting human, so this is the
     * one part of it CI can reach: the arithmetic that decides which pixel is
     * which. */
    func testEveryOrientationMapsDisplayBackToTheRightBufferPixel() {
        let bw = 7, bh = 11

        /* The inverse of what ScreenMeasurement implements, written out from
           Apple's own wording for each case — "0th row on right, 0th column on
           top" and so on. If the two agree, the mapping is right. */
        func displayFor(bufferX bx: Int, bufferY by: Int,
                        _ o: ScreenMeasurement.FrameOrientation) -> (Int, Int) {
            switch o {
            case .up:    return (bx, by)
            case .down:  return (bw - 1 - bx, bh - 1 - by)
            case .right: return (bh - 1 - by, bx)
            case .left:  return (by, bw - 1 - bx)
            }
        }

        for o in [ScreenMeasurement.FrameOrientation.up, .down, .right, .left] {
            let (dw, dh) = ScreenMeasurement.displaySize(
                bufferWidth: bw, bufferHeight: bh, orientation: o)
            XCTAssertEqual(dw * dh, bw * bh, "\(o) changed the pixel count")

            var covered = Set<String>()
            for by in 0..<bh {
                for bx in 0..<bw {
                    let (dx, dy) = displayFor(bufferX: bx, bufferY: by, o)
                    XCTAssertTrue((0..<dw).contains(dx) && (0..<dh).contains(dy),
                                  "\(o) put a pixel outside the display")
                    covered.insert("\(dx),\(dy)")

                    let back = ScreenMeasurement.bufferCoord(
                        displayX: dx, displayY: dy,
                        bufferWidth: bw, bufferHeight: bh, orientation: o)
                    XCTAssertEqual(back.x, bx, "\(o) x round-trip")
                    XCTAssertEqual(back.y, by, "\(o) y round-trip")
                }
            }
            XCTAssertEqual(covered.count, bw * bh, "\(o) did not cover the display")
        }
    }

    /* The landscape cases are the whole point: a rotated frame must read WIDER
       than it is tall, or the strip is a side band and the seek bar is never in
       it. */
    func testARotatedFrameIsReadAsLandscape() {
        let tall = (w: 9, h: 16)   // a portrait buffer, as the device stores it
        for o in [ScreenMeasurement.FrameOrientation.right, .left] {
            let size = ScreenMeasurement.displaySize(
                bufferWidth: tall.w, bufferHeight: tall.h, orientation: o)
            XCTAssertGreaterThan(size.width, size.height,
                                 "\(o) should present a portrait buffer as landscape")
        }
        let upright = ScreenMeasurement.displaySize(
            bufferWidth: tall.w, bufferHeight: tall.h, orientation: .up)
        XCTAssertLessThan(upright.width, upright.height)
    }

    /* The bottom strip in display space must come from the correct EDGE of the
       buffer. In .right the display's bottom row is the buffer's first column;
       reading the buffer's last rows instead is exactly the old bug. */
    func testTheDisplaysBottomRowIsNotTheBuffersLastRow() {
        let bw = 8, bh = 20
        let (dw, dh) = ScreenMeasurement.displaySize(
            bufferWidth: bw, bufferHeight: bh, orientation: .right)

        var rowsTouched = Set<Int>()
        for dx in 0..<dw {
            let p = ScreenMeasurement.bufferCoord(
                displayX: dx, displayY: dh - 1,
                bufferWidth: bw, bufferHeight: bh, orientation: .right)
            rowsTouched.insert(p.y)
            XCTAssertEqual(p.x, bw - 1, "the display's bottom row is the buffer's last COLUMN here")
        }
        XCTAssertEqual(rowsTouched.count, bh,
                       "reading the display's bottom row should sweep every buffer row")
    }
}
