import XCTest
@testable import TinyTubeCore

/* Ported from ChromeTest.kt's synthetic half.
 *
 * The Kotlin suite also pins a real paused frame — a committed JPEG decoded
 * through java.awt. There is no decoder in the Swift standard library on Linux,
 * so the fixture case cannot come across as-is; the frames here are built to
 * the same recipe the Kotlin synthetic ones use, which is what the real frame
 * measured: a track a few pixels thick, a played portion, a knob riding at its
 * head, and a dark scrim behind it all.
 *
 * The device-geometry sweep is the load-bearing part either way. Every figure
 * in Chrome is a RATIO of the bar's own drawn thickness, so doubling the
 * resolution must double the answer — that is the property that makes the
 * measurement work on a phone nobody tested on. */
final class ChromeTests: XCTestCase {

    /* A frame like the ones YouTube actually draws: dark scrim, a light track
       across the full width, a red played portion, and a knob at its head. */
    private func frame(
        width: Int,
        height: Int,
        barTop: Int,
        thickness: Int,
        playedFraction: Double = 0.3,
        knobRadius: Int = 0
    ) -> [UInt32] {
        var px = [UInt32](repeating: 0xFF10_1010, count: width * height)   // dark scrim
        let knobCentre = Int(Double(width) * playedFraction)
        for y in barTop..<(barTop + thickness) {
            for x in 0..<width {
                let played = Double(x) / Double(width) < playedFraction
                px[y * width + x] = played ? 0xFFFF_0032 : 0xFFDD_DDDD
            }
        }
        /* The knob: thicker than the line, and the reason measuring the red
           rather than the track gave the wrong thickness. */
        if knobRadius > 0 {
            let centreY = barTop + thickness / 2
            for y in max(0, centreY - knobRadius)...min(height - 1, centreY + knobRadius) {
                for x in max(0, knobCentre - knobRadius)...min(width - 1, knobCentre + knobRadius) {
                    px[y * width + x] = 0xFFFF_0032
                }
            }
        }
        return px
    }

    func testFindsTheBarAndItsThickness() {
        let px = frame(width: 400, height: 200, barTop: 120, thickness: 6)
        let bar = Chrome.seekBar(px, width: 400, height: 200)
        XCTAssertNotNil(bar)
        XCTAssertEqual(bar!.lowerBound, 120)
        XCTAssertEqual(bar!.upperBound, 125)
    }

    /* The bug that started all of this. The knob is four times the line's
       thickness and sits at the head of the played portion; a measurement that
       looked at the red would report the knob's diameter and shrink the blocked
       strip to almost nothing while reporting success. */
    func testTheKnobDoesNotInflateTheThickness() {
        let px = frame(width: 400, height: 200, barTop: 120, thickness: 6, knobRadius: 12)
        let bar = Chrome.seekBar(px, width: 400, height: 200)
        XCTAssertNotNil(bar)
        XCTAssertEqual(bar!.upperBound - bar!.lowerBound + 1, 6,
                       "the track's thickness, not the knob's diameter")
    }

    /* A knob at the very start of the bar is the worst case — early in a long
       video it is nearly all the red there is. */
    func testAKnobAtTheStartDoesNotMoveTheAnswer() {
        let atStart = frame(width: 400, height: 200, barTop: 120, thickness: 6,
                            playedFraction: 0.02, knobRadius: 12)
        let midway = frame(width: 400, height: 200, barTop: 120, thickness: 6,
                           playedFraction: 0.5, knobRadius: 12)
        XCTAssertEqual(Chrome.measure(atStart, width: 400, height: 200),
                       Chrome.measure(midway, width: 400, height: 200))
    }

    func testTheBlockedStripLeavesAMarginUnderTheBar() {
        let m = Chrome.measure(frame(width: 400, height: 200, barTop: 120, thickness: 6),
                               width: 400, height: 200)!
        XCTAssertEqual(m.barBottom, 125)
        XCTAssertEqual(m.thickness, 6)
        XCTAssertEqual(m.below, 74)          // 199 - 125
        /* margin = min(6 * 2, 74 / 4) = 12 */
        XCTAssertEqual(m.blockPx, 62)
    }

    /* Three quarters of the space below the bar stays blocked whatever else
       goes wrong — the margin is capped as a ratio of the gap so an inflated
       thickness cannot eat the strip. */
    func testTheMarginNeverEatsMoreThanAQuarterOfTheGap() {
        for thickness in 2...20 {
            guard let m = Chrome.measure(
                frame(width: 400, height: 400, barTop: 300, thickness: thickness),
                width: 400, height: 400
            ) else { continue }
            XCTAssertGreaterThanOrEqual(m.blockPx, m.below - m.below / 4,
                                        "thickness \(thickness) ate too much")
        }
    }

    /* THE property. Every figure in Chrome is a ratio of the bar's own drawn
       thickness, so a screen with twice the pixels must give twice the answer
       — with nobody converting points to pixels or asking the device its
       scale. This is what makes it work on a phone nobody tested on. */
    func testDoublingTheResolutionDoublesTheAnswer() {
        let base = Chrome.measure(frame(width: 400, height: 200, barTop: 120, thickness: 6),
                                  width: 400, height: 200)!
        let doubled = Chrome.measure(frame(width: 800, height: 400, barTop: 240, thickness: 12),
                                     width: 800, height: 400)!
        XCTAssertEqual(doubled.blockPx, base.blockPx * 2)
        XCTAssertEqual(doubled.thickness, base.thickness * 2)
    }

    /* Seven geometries, as the Kotlin suite does. A measurement that only works
       at one aspect ratio is one that works on one phone. */
    func testHoldsAcrossDeviceGeometries() {
        let geometries = [
            (640, 360), (800, 450), (960, 540), (1280, 720),
            (1600, 900), (1920, 1080), (2340, 1080),
        ]
        for (w, h) in geometries {
            let thickness = max(3, h / 120)
            let barTop = h - h / 5
            let px = frame(width: w, height: h, barTop: barTop, thickness: thickness)
            guard let m = Chrome.measure(px, width: w, height: h) else {
                XCTFail("no measurement at \(w)x\(h)")
                continue
            }
            XCTAssertEqual(m.thickness, thickness, "thickness at \(w)x\(h)")
            XCTAssertEqual(m.barBottom, barTop + thickness - 1, "bar bottom at \(w)x\(h)")
            XCTAssertGreaterThan(m.blockPx, 0, "blocked strip at \(w)x\(h)")
            XCTAssertLessThan(m.blockPx, h, "blocked strip at \(w)x\(h)")
        }
    }

    /* nil rather than a number that happens to equal the fallback. A blank
       capture and a real measurement must be distinguishable, or one bad frame
       gets written to storage as the answer and nothing looks again. */
    func testAFrameWithNoBarMeasuresNothing() {
        let blank = [UInt32](repeating: 0xFF00_0000, count: 400 * 200)
        XCTAssertNil(Chrome.seekBar(blank, width: 400, height: 200))
        XCTAssertNil(Chrome.measure(blank, width: 400, height: 200))
        XCTAssertNil(Chrome.blockHeight(blank, width: 400, height: 200))

        let noise = (0..<(400 * 200)).map { UInt32(0xFF00_0000 | UInt32(($0 * 7919) % 0x30)) }
        XCTAssertNil(Chrome.measure(noise, width: 400, height: 200))
    }

    func testRefusesNonsenseDimensions() {
        let px = frame(width: 400, height: 200, barTop: 120, thickness: 6)
        XCTAssertNil(Chrome.seekBar(px, width: 0, height: 200))
        XCTAssertNil(Chrome.seekBar(px, width: 400, height: 0))
        XCTAssertNil(Chrome.seekBar(px, width: -1, height: 200))
        XCTAssertNil(Chrome.seekBar(px, width: 4000, height: 2000))   // too few pixels
    }

    /* A bar flush against the bottom edge leaves nothing to block, and a
       "measurement" of zero is not one. */
    func testABarAtTheBottomEdgeMeasuresNothing() {
        let px = frame(width: 400, height: 200, barTop: 194, thickness: 6)
        XCTAssertNil(Chrome.measure(px, width: 400, height: 200))
    }

    /* Alpha is set on every pixel here, and on Android the same colour in a
       signed Int has its top bit set. Taking UInt32 is what stops that
       becoming a sign-extension question at all. */
    func testLuminanceIgnoresAlpha() {
        XCTAssertEqual(Chrome.luminance(0xFFFF_FFFF), Chrome.luminance(0x00FF_FFFF))
        XCTAssertEqual(Chrome.luminance(0xFF00_0000), 0)
        XCTAssertGreaterThan(Chrome.luminance(0xFFDD_DDDD), Chrome.luminance(0xFF10_1010))
    }

    /* Measured off a real paused frame the bar is #FF0032, and JPEG moves it to
       #EE0532 and #F60538 — which is why this tests proportions rather than
       matching a colour. */
    func testProgressRedAcceptsWhatYouTubeActuallyDraws() {
        for red: UInt32 in [0xFFFF_0032, 0xFFEE_0532, 0xFFF6_0538, 0xFFFF_0000] {
            XCTAssertTrue(Chrome.isProgressRed(red), "should have been red: \(String(red, radix: 16))")
        }
        for notRed: UInt32 in [0xFFFF_FFFF, 0xFF10_1010, 0xFFDD_DDDD, 0xFF00_FF00, 0xFF88_8888] {
            XCTAssertFalse(Chrome.isProgressRed(notRed), "should not have been red: \(String(notRed, radix: 16))")
        }
    }

    func testRedIsFoundInTheBarsBand() {
        let px = frame(width: 400, height: 200, barTop: 120, thickness: 6)
        XCTAssertTrue(Chrome.hasProgressRed(px, width: 400, band: 120...125))
        XCTAssertFalse(Chrome.hasProgressRed(px, width: 400, band: 100...105))
    }
}
