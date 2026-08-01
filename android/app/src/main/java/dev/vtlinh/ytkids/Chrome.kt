package dev.vtlinh.ytkids

/* Working out where YouTube's seek bar is, by looking at the pixels.

   The player is a cross-origin iframe: its DOM cannot be read, so there is no
   way to ask it where it drew anything. The bottom blocker's height was
   therefore a constant — YouTube's mobile-embed inset, guessed once and wrong
   on any device or player version that differs. This measures it instead.

   What it looks for is the TRACK: the thin light line that runs the whole
   width of the bar, played part and unplayed part alike. Not the red.

   The red was the first idea and it was the wrong signal, for a reason worth
   recording. The played portion is red, but so is the round scrubber knob at
   its head, and the knob is about four times thicker than the line. Early in a
   long video the knob is nearly all the red there is, so "how tall is the red"
   answered with the knob's diameter — and since the margin is a multiple of
   the thickness, the blocker shrank to almost nothing while reporting success.
   Every fix for that was a fix for a symptom.

   The track has none of those problems. It spans the bar at every playback
   position, it is the line's own thickness by definition, and a knob a few
   dozen columns wide cannot shift a measurement taken across the full width.
   Measured on a real frame: the bar's rows score 62-76% of columns while
   nothing else in the bottom of the picture reaches 3%.

   Pure, and no Android in it, so ChromeTest can hold it to its promises under
   a plain JVM: it takes a rectangle of ARGB pixels and returns a height. The
   Activity does the capturing — see PlayerActivity.measureBlockHeight. */
object Chrome {

    /* How much of the space under the bar stays reachable, in bar-thicknesses.
     *
     * On the frame this was calibrated against the line is 9px including the
     * rows its edges are antialiased into, the gap to the row of chrome below
     * is 58px, and the whole inset is 217px. Five thicknesses is 45px there:
     * comfortably inside the gap, and comfortably more than a fingertip's
     * error against a line that thin. Because the bar and the gap are both
     * drawn in dp, that ratio holds on every device without anyone converting
     * anything. */
    private const val MARGIN_IN_BARS = 5

    /* A sanity limit on how far above the bottom the bar may be, again in
       bar-thicknesses. The real figure is 24; anything past 60 is not an inset
       and the match was something else. */
    private const val MAX_BELOW_IN_BARS = 60

    /* And a limit on the line itself: a band a tenth of the frame tall is not
       a 3dp line. */
    private const val MAX_THICKNESS_FRACTION = 10

    /* How much lighter than its surroundings a pixel must be to count as part
       of the track. The track is white at partial opacity, so over a dark
       scene it is far lighter and over a bright one only somewhat — and
       YouTube lays a dark scrim behind its controls, which helps. Sixteen out
       of 255 is well below what the real frame produces and well above what
       compression noise does. */
    private const val CONTRAST = 16

    /* And how much of the width has to be lighter for the row to be a line
       rather than an edge in the picture. The real bar scores 62-76%; the
       next-best row in the bottom of that frame scores under 3%. */
    private const val MIN_COLUMNS_PERCENT = 40

    /* Perceived brightness. The weights are the usual ones; what matters is
       only that the track reads as lighter than the frame behind it. */
    fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 30 + g * 59 + b * 11) / 100
    }

    /* The played portion of YouTube's progress bar, which is red and has been
       for the entire life of the product. Kept as corroboration rather than as
       the primary signal: a bright full-width line that also has red in it is
       a seek bar, and one without is something else.

       Measured off a real paused frame the bar comes out at #FF0032 — not the
       #FF0000 you would guess, and with JPEG in the way it wanders to #EE0532
       and #F60538. Hence a test on proportions rather than a colour match. */
    fun isProgressRed(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r >= 140 && g * 2 <= r && b * 2 <= r
    }

    /* How far above and below to look when asking whether a row is lighter
       than what surrounds it.
     *
     * It has to EXCEED the line's thickness, or the band's own outer rows
     * compare themselves against the middle of the same line and score
     * nothing — which showed up as a 9px line measuring 7. And it has to stay
     * inside the dark scrim YouTube lays behind its controls, or the
     * comparison lands on the picture. Measured on the real frame, anything
     * from a third of the line's thickness to three times it works; the score
     * falls off gently and the first false row only appears past that.
     * Proportional to the captured strip, so it holds at any resolution. */
    private fun gapFor(height: Int) = maxOf(6, height / 32)

    /* What percentage of the row's columns are lighter than the pixels a short
       way above AND below them. A drawn line scores most of the width; a
       bright object in the picture scores a few percent, because it is not a
       line. */
    fun lineScore(pixels: IntArray, width: Int, height: Int, y: Int): Int {
        val gap = gapFor(height)
        if (y - gap < 0 || y + gap >= height) return 0
        var count = 0
        for (x in 0 until width) {
            val here = luminance(pixels[y * width + x])
            val above = luminance(pixels[(y - gap) * width + x])
            val below = luminance(pixels[(y + gap) * width + x])
            if (here - maxOf(above, below) > CONTRAST) count++
        }
        return count * 100 / width
    }

    /* The bar, as the rows it occupies.

       Found by scanning up from the bottom, so the FIRST match is the lowest
       line in the frame — below the bar is only the row of chrome this is here
       to measure, and above it is a whole picture that might contain anything.
       Then out from there while rows still look like the same line, which is
       what gives the thickness everything else is scaled by. */
    fun seekBar(pixels: IntArray, width: Int, height: Int): IntRange? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null

        var seed = -1
        for (y in height - 1 downTo 0) {
            if (lineScore(pixels, width, height, y) >= MIN_COLUMNS_PERCENT) { seed = y; break }
        }
        if (seed < 0) return null

        /* Half the threshold on the way out, so the line's own softer edges
           are included and the frame beyond them is not. On the real frame the
           rows either side of the bar score under 3%, so this stops cleanly. */
        val edge = MIN_COLUMNS_PERCENT / 2
        var top = seed
        while (top > 0 && lineScore(pixels, width, height, top - 1) >= edge) top--
        var bottom = seed
        while (bottom + 1 < height && lineScore(pixels, width, height, bottom + 1) >= edge) bottom++

        if (bottom - top + 1 > maxOf(height / MAX_THICKNESS_FRACTION, 1)) return null
        return top..bottom
    }

    /* Kept for the cases that only care where it ends. */
    fun seekBarBottom(pixels: IntArray, width: Int, height: Int): Int? =
        seekBar(pixels, width, height)?.last

    /* Is there any of YouTube's red in this band?
     *
     * Corroboration, not detection. A full-width light line low in a video
     * frame is almost certainly the seek bar, and if it also carries the red
     * of a played portion or its knob then it certainly is. */
    fun hasProgressRed(pixels: IntArray, width: Int, band: IntRange): Boolean {
        for (y in band) {
            for (x in 0 until width) if (isProgressRed(pixels[y * width + x])) return true
        }
        return false
    }

    fun blockHeight(pixels: IntArray, width: Int, height: Int, fallbackPx: Int): Int =
        blockHeightOrNull(pixels, width, height) ?: fallbackPx

    /* The same, saying "I could not tell" rather than answering anyway.
     *
     * The caller needs the difference. A capture that came back blank and a
     * capture that genuinely measured the fallback's worth of inset are the
     * same number, and treating them alike is how one bad frame got written to
     * storage as if it were the answer — after which nothing ever looked
     * again. Null means try another frame. */
    fun blockHeightOrNull(pixels: IntArray, width: Int, height: Int): Int? =
        measure(pixels, width, height)?.blockPx

    /* The whole answer, so a readout can show the working rather than only the
       conclusion. A wrong number is far easier to place when the thickness and
       the gap it came from are next to it. */
    class Measurement(val barBottom: Int, val thickness: Int, val below: Int, val blockPx: Int) {
        override fun toString() = "bar@$barBottom thick $thickness below $below → $blockPx px"
    }

    fun measure(pixels: IntArray, width: Int, height: Int): Measurement? {
        val bar = seekBar(pixels, width, height) ?: return null
        val thickness = bar.last - bar.first + 1
        val below = height - 1 - bar.last
        if (below <= 0 || below > thickness * MAX_BELOW_IN_BARS) return null

        /* The margin never eats more than a quarter of the gap. Five
           thicknesses is the right size when the thickness is the line's; if
           anything ever inflates it, a ratio of the gap bounds the damage
           without reintroducing a fixed number. Three quarters of the space
           below the bar stays blocked whatever else goes wrong. */
        val margin = minOf(thickness * MARGIN_IN_BARS, below / 4)
        return Measurement(bar.last, thickness, below, (below - margin).coerceAtLeast(0))
    }
}
