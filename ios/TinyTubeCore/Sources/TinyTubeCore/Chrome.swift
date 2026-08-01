import Foundation

/* Working out where YouTube's seek bar is, by looking at the pixels.

   The player is a cross-origin iframe: its DOM cannot be read, so there is no
   way to ask it where it drew anything. The bottom blocker's height was
   therefore a constant — YouTube's embed inset, guessed once and wrong on any
   device or player version that differs. This measures it instead.

   What it looks for is the TRACK: the thin light line that runs the whole width
   of the bar, played part and unplayed part alike. Not the red.

   The red was the first idea and it was the wrong signal, for a reason worth
   recording. The played portion is red, but so is the round scrubber knob at
   its head, and the knob is about four times thicker than the line. Early in a
   long video the knob is nearly all the red there is, so "how tall is the red"
   answered with the knob's diameter — and since the margin is a multiple of the
   thickness, the blocker shrank to almost nothing while reporting success.

   The track has none of those problems. It spans the bar at every playback
   position, it is the line's own thickness by definition, and a knob a few
   dozen columns wide cannot shift a measurement taken across the full width.

   Ported from Chrome.kt, constant for constant.

   ⚠️ WHETHER iOS CAN FEED IT ANYTHING IS UNRESOLVED. On Android the pixels come
   from PixelCopy, which reads the composited window INCLUDING the hardware
   video surface — that is precisely why WebView.draw(Canvas) failed and
   PixelCopy did not. WKWebView.takeSnapshot and CALayer.render(in:) are the
   obvious iOS candidates and both are reported to come back blank over video,
   for the same compositing reason. If neither works, the iOS player falls back
   to a fixed inset and this file is unused there — which is why it is ported
   anyway: the logic is not the risky part, and having it ready means the answer
   to the spike is the only thing standing between here and a working blocker.
   See README's Platform differences. */
public enum Chrome {

    /* How much of the space under the bar stays reachable, in bar-thicknesses.
     *
     * Two rather than none because the line is thin and a thumb aiming at it
     * lands around it; everything ABOVE the bar is reachable anyway, so this
     * only has to cover fingers that land low. Because the bar and the gap are
     * both drawn in points, the ratio holds on every device without anyone
     * converting anything. */
    static let marginInBars = 2

    /* A sanity limit on how far above the bottom the bar may be, again in
       bar-thicknesses. The real figure is 24; anything past 60 is not an inset
       and the match was something else. */
    static let maxBelowInBars = 60

    /* And a limit on the line itself: a band a tenth of the frame tall is not a
       3-point line. */
    static let maxThicknessFraction = 10

    /* How much lighter than its surroundings a pixel must be to count as part
       of the track. Sixteen out of 255 is well below what a real frame produces
       and well above what compression noise does. */
    static let contrast = 16

    /* And how much of the width has to be lighter for the row to be a line
       rather than an edge in the picture. A real bar scores 62-76%; the
       next-best row in the bottom of that frame scores under 3%. */
    static let minColumnsPercent = 40

    /* Perceived brightness. The weights are the usual ones; what matters is
       only that the track reads as lighter than the frame behind it.
     *
     * Takes ARGB packed into a UInt32 rather than Int, unlike the Kotlin
     * version. Kotlin's Int is signed and its `shr` on a colour with the alpha
     * bit set sign-extends unless you use ushr; taking an unsigned type means
     * the question cannot come up. */
    public static func luminance(_ argb: UInt32) -> Int {
        let r = Int((argb >> 16) & 0xFF)
        let g = Int((argb >> 8) & 0xFF)
        let b = Int(argb & 0xFF)
        return (r * 30 + g * 59 + b * 11) / 100
    }

    /* The played portion of YouTube's progress bar, which is red. Kept as
       corroboration rather than as the primary signal.
     *
     * Measured off a real paused frame the bar comes out at #FF0032 — not the
     * #FF0000 you would guess, and with JPEG in the way it wanders to #EE0532
     * and #F60538. Hence a test on proportions rather than a colour match. */
    public static func isProgressRed(_ argb: UInt32) -> Bool {
        let r = Int((argb >> 16) & 0xFF)
        let g = Int((argb >> 8) & 0xFF)
        let b = Int(argb & 0xFF)
        return r >= 140 && g * 2 <= r && b * 2 <= r
    }

    /* How far above and below to look when asking whether a row is lighter than
       what surrounds it.
     *
     * It has to EXCEED the line's thickness, or the band's own outer rows
     * compare themselves against the middle of the same line and score nothing
     * — which showed up as a 9px line measuring 7. Proportional to the captured
     * strip, so it holds at any resolution. */
    static func gap(forHeight height: Int) -> Int { max(6, height / 32) }

    /* What percentage of the row's columns are lighter than the pixels a short
       way above AND below them. A drawn line scores most of the width; a bright
       object in the picture scores a few percent, because it is not a line. */
    public static func lineScore(_ pixels: [UInt32], width: Int, height: Int, y: Int) -> Int {
        let g = gap(forHeight: height)
        guard y - g >= 0, y + g < height, width > 0 else { return 0 }
        var count = 0
        for x in 0..<width {
            let here = luminance(pixels[y * width + x])
            let above = luminance(pixels[(y - g) * width + x])
            let below = luminance(pixels[(y + g) * width + x])
            if here - max(above, below) > contrast { count += 1 }
        }
        return count * 100 / width
    }

    /* The bar, as the rows it occupies.
     *
     * Found by scanning up from the bottom, so the FIRST match is the lowest
     * line in the frame — below the bar is only the row of chrome this is here
     * to measure, and above it is a whole picture that might contain anything.
     * Then out from there while rows still look like the same line, which is
     * what gives the thickness everything else is scaled by. */
    public static func seekBar(_ pixels: [UInt32], width: Int, height: Int) -> ClosedRange<Int>? {
        guard width > 0, height > 0, pixels.count >= width * height else { return nil }

        var seed = -1
        for y in stride(from: height - 1, through: 0, by: -1)
        where lineScore(pixels, width: width, height: height, y: y) >= minColumnsPercent {
            seed = y
            break
        }
        guard seed >= 0 else { return nil }

        /* Half the threshold on the way out, so the line's own softer edges are
           included and the frame beyond them is not. */
        let edge = minColumnsPercent / 2
        var top = seed
        while top > 0, lineScore(pixels, width: width, height: height, y: top - 1) >= edge { top -= 1 }
        var bottom = seed
        while bottom + 1 < height,
              lineScore(pixels, width: width, height: height, y: bottom + 1) >= edge { bottom += 1 }

        if bottom - top + 1 > max(height / maxThicknessFraction, 1) { return nil }
        return top...bottom
    }

    /* Is there any of YouTube's red in this band? Corroboration, not detection. */
    public static func hasProgressRed(_ pixels: [UInt32], width: Int, band: ClosedRange<Int>) -> Bool {
        for y in band {
            for x in 0..<width where isProgressRed(pixels[y * width + x]) { return true }
        }
        return false
    }

    /* The whole answer, so a readout can show the working rather than only the
       conclusion. */
    public struct Measurement: Equatable, Sendable {
        public let barBottom: Int
        public let thickness: Int
        public let below: Int
        public let blockPx: Int
    }

    public static func measure(_ pixels: [UInt32], width: Int, height: Int) -> Measurement? {
        guard let bar = seekBar(pixels, width: width, height: height) else { return nil }
        let thickness = bar.upperBound - bar.lowerBound + 1
        let below = height - 1 - bar.upperBound
        guard below > 0, below <= thickness * maxBelowInBars else { return nil }

        /* The margin never eats more than a quarter of the gap. Three quarters
           of the space below the bar stays blocked whatever else goes wrong. */
        let margin = min(thickness * marginInBars, below / 4)
        return Measurement(
            barBottom: bar.upperBound,
            thickness: thickness,
            below: below,
            blockPx: max(below - margin, 0)
        )
    }

    /* Saying "I could not tell" rather than answering anyway.
     *
     * The caller needs the difference. A capture that came back blank and a
     * capture that genuinely measured the fallback's worth of inset are the
     * same number, and treating them alike is how one bad frame got written to
     * storage as if it were the answer — after which nothing ever looked again.
     * nil means try another frame. */
    public static func blockHeight(_ pixels: [UInt32], width: Int, height: Int) -> Int? {
        measure(pixels, width: width, height: height)?.blockPx
    }
}
