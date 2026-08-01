package dev.vtlinh.ytkids

/* Working out where YouTube's seek bar ends, by looking at the pixels.

   The player is a cross-origin iframe: its DOM cannot be read, so there is no
   way to ask it where it drew anything. The bottom blocker's height was
   therefore a constant — YouTube's mobile-embed inset, guessed once and wrong
   on any device or player version that differs. This measures it instead.

   Nothing here is in dp, and nothing here knows the screen's density. An
   earlier version did, and it was the wrong shape: the app can convert dp to
   pixels exactly, but only by trusting a figure for YouTube's chrome that came
   from eyeballing one screenshot whose density was itself a guess. Two guesses
   multiplied together.

   The picture already carries its own scale. The drawn bar is about 3dp thick,
   so its thickness in pixels IS the device's dp-to-pixel ratio, measured on the
   spot from the thing being measured. Everything below is expressed as a
   multiple of that — ratios, which are the same number at any resolution.

   Pure, and no Android in it, so ChromeTest can hold it to its promises under
   a plain JVM: it takes a rectangle of ARGB pixels and returns a height. The
   Activity does the capturing, and never keeps what it captured — see
   PlayerActivity.measureBlockHeight. */
object Chrome {

    /* How much of the space under the bar stays reachable, in bar-thicknesses.
     *
     * On the frame this was calibrated against the bar is 9px thick, the gap
     * to the row of chrome below is 58px — 6.4 thicknesses — and the whole
     * inset is 217px. Five thicknesses is 45px there: comfortably inside the
     * gap, and comfortably more than a fingertip's error against a 9px line.
     * Because the bar and the gap are both drawn in dp, that ratio holds on
     * every device without anyone converting anything. */
    private const val MARGIN_IN_BARS = 5

    /* A sanity limit on how far above the bottom the bar may be, again in
       bar-thicknesses. The real figure is 24; anything past 40 is not an inset
       and the match was something in the picture. */
    private const val MAX_BELOW_IN_BARS = 40

    /* And a limit on the bar itself: a red band a tenth of the frame tall is
       not a 3dp line. */
    private const val MAX_THICKNESS_FRACTION = 10

    /* The played portion of YouTube's progress bar, which is red and has been
       for the entire life of the product. Saturated red specifically: a
       reddish frame of video is common, a pixel whose green and blue are both
       under half its red is not.

       Measured off a real paused frame (see ChromeTest and the fixture beside
       it) the bar comes out at #FF0032 — not the #FF0000 you would guess, and
       with JPEG in the way it wanders to #EE0532 and #F60538. Hence a test on
       proportions rather than a colour match. */
    fun isProgressRed(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r >= 140 && g * 2 <= r && b * 2 <= r
    }

    /* Does this row look like the progress bar?

       Two things are needed, and both matter. It must contain a run of red — a
       single stray red pixel is noise, a long one is the bar. And that run
       must START near the left edge, because the played portion always does:
       it grows rightwards from the beginning of the bar. Red in the middle of
       the frame is a red shirt.

       On the real frame the run starts 9% of the way across, because YouTube
       insets its controls past the display cutout — and a cutout is exactly
       the sort of thing that differs between devices. A sixth leaves room for
       a wider one without reaching the middle of the picture, where the red
       jumper lives. Proportional rather than absolute for the same reason:
       nothing here may assume a resolution. */
    private fun rowIsBar(pixels: IntArray, width: Int, y: Int): Boolean {
        val startsWithin = maxOf(width / 6, 1)
        val minRun = maxOf(width / 200, 4)
        var runStart = -1
        var run = 0
        for (x in 0 until width) {
            if (isProgressRed(pixels[y * width + x])) {
                if (run == 0) runStart = x
                run++
                if (run >= minRun && runStart <= startsWithin) return true
            } else {
                run = 0
            }
        }
        return false
    }

    /* The bar, as the rows it occupies.

       Found by scanning up from the bottom, so the FIRST match is its bottom
       edge — below the bar is only the row of chrome this is here to measure,
       and above it is a whole frame of video that might be any colour at all.
       Then up again while rows keep qualifying, which is what gives the
       thickness everything else is scaled by. */
    fun seekBar(pixels: IntArray, width: Int, height: Int): IntRange? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null

        var bottom = -1
        for (y in height - 1 downTo 0) {
            if (rowIsBar(pixels, width, y)) { bottom = y; break }
        }
        if (bottom < 0) return null

        var top = bottom
        while (top > 0 && rowIsBar(pixels, width, top - 1)) top--

        /* Too thick to be a 3dp line — a red band in the picture that happened
           to reach the left edge. */
        if (bottom - top + 1 > maxOf(height / MAX_THICKNESS_FRACTION, 1)) return null
        return top..bottom
    }

    /* Kept for the cases that only care where it ends. */
    fun seekBarBottom(pixels: IntArray, width: Int, height: Int): Int? =
        seekBar(pixels, width, height)?.last

    /* How thick the drawn LINE is, which is not the same as how tall the red
       band is.
     *
     * At the head of the played portion YouTube draws a round scrubber knob,
     * about four times the line's thickness. On a long video that knob sits at
     * the left of the bar for minutes, so the band found above is knob-tall
     * rather than line-tall — and since the margin is a multiple of the
     * thickness, that made the margin four times too big and swallowed the
     * whole inset. Measured on a real device: a 9px line reported as 36, a
     * 180px margin against a 204px gap, a 35px strip where 170 was right.
     *
     * So the thickness is counted per column, and taken as the THINNEST run
     * that occurs on enough columns to be a drawn line rather than an edge.
     *
     * Two rejected alternatives, both of which fail on a real frame:
     *
     * - The plain minimum. The columns at either end of the bar are
     *   antialiased down to a pixel or two, so the smallest run describes the
     *   rendering rather than the line. On the real frame that came out as 1,
     *   which made the gap forty times the thickness and got the whole
     *   measurement rejected as implausible.
     * - The median. Fine while the knob is a small minority, which it is once
     *   a video is a few percent in — but at the very start the knob is most
     *   of the red there is, and the median becomes the knob again. The bug
     *   this replaced was exactly that, seen at 0:06 of a 22-minute video.
     *
     * Requiring a minimum number of columns keeps the antialiased ends out
     * while still finding the line when the knob outnumbers it. Where there is
     * genuinely no line yet — a video paused at zero — the knob is all there
     * is, and the ceiling in measure() is what stops that from mattering. */
    fun barThickness(pixels: IntArray, width: Int, height: Int, band: IntRange): Int {
        /* Anchor on the band's densest row. The line spans the whole played
           portion while the knob is a few dozen columns, so the row with the
           most red in it is a line row — which also excludes the stray red the
           video itself contributes, since that is scattered rather than in a
           row hundreds of pixels long. */
        var anchor = band.first
        var most = -1
        for (y in band) {
            var count = 0
            for (x in 0 until width) if (isProgressRed(pixels[y * width + x])) count++
            if (count > most) { most = count; anchor = y }
        }
        if (most <= 0) return band.last - band.first + 1

        /* Then the CONTIGUOUS run through that row, per column. Contiguous
           matters: counting red anywhere in the band lets a red frame behind
           the bar add a pixel to every column and drag the answer to 1, which
           made the gap forty times the thickness and got the whole measurement
           thrown out as implausible. */
        val runs = ArrayList<Int>()
        for (x in 0 until width) {
            if (!isProgressRed(pixels[anchor * width + x])) continue
            var top = anchor
            while (top - 1 >= band.first && isProgressRed(pixels[(top - 1) * width + x])) top--
            var bottom = anchor
            while (bottom + 1 <= band.last && isProgressRed(pixels[(bottom + 1) * width + x])) bottom++
            runs.add(bottom - top + 1)
        }
        if (runs.isEmpty()) return band.last - band.first + 1
        runs.sort()
        return runs[runs.size / 2].coerceAtLeast(1)
    }

    /* How tall the bottom blocker should be, given a strip captured from the
       bottom of the player.

       Not simply "everything under the bar". A few bar-thicknesses of it are
       left reachable, because the drawn bar is thin — nine pixels on the frame
       this was calibrated against — and a thumb aiming for it lands around it,
       not on it. Blocking flush to the line would make the one control the
       reveal exists to reach the one control nobody can hit. Everything above
       the line is already reachable, so the margin only has to cover fingers
       that land low.

       Takes no measurements from its caller beyond the fallback. Where the bar
       is, how thick it is, how much room to leave and what counts as an
       implausible answer all come out of the pixels.

       Returns fallbackPx rather than guessing whenever the answer would be
       untrustworthy: no bar, a bar on the last row, or a gap too large to be
       an inset. The fallback is the compiled-in constant the app used before
       this existed. */
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
        val thickness = barThickness(pixels, width, height, bar)
        val below = height - 1 - bar.last
        if (below <= 0 || below > thickness * MAX_BELOW_IN_BARS) return null

        /* The margin never eats more than a quarter of the gap.
         *
         * Five thicknesses is the right size when the thickness is the line's.
         * When something inflates it — a knob at the head of the played
         * portion, a chunky progress style on some future player — five of
         * them can exceed the whole inset, and the blocker silently shrinks to
         * nothing while still reporting success. A ratio of the gap bounds
         * that without reintroducing a fixed number: whatever else is wrong,
         * three quarters of the space below the bar is still blocked. */
        val margin = minOf(thickness * MARGIN_IN_BARS, below / 4)
        return Measurement(bar.last, thickness, below, (below - margin).coerceAtLeast(0))
    }
}
