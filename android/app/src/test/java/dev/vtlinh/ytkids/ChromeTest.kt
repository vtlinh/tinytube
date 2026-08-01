package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.InputStream

/* The pixel analysis that replaces a compiled-in guess at YouTube's inset.

   Three kinds of case, in this order:

   - synthetic frames, built the way YouTube builds a seek bar: a light track
     across the full width, a red played portion over part of it, and a fat
     round knob at the head of that;
   - one real paused frame, which is what caught two versions of this being
     aimed at the wrong thing;
   - the same layout at seven device geometries, which is what stops it being
     tuned to the one phone the frame came from.

   All of it on a plain JVM, which is the whole point of keeping Chrome pure —
   the alternative is finding out on a device that a red jumper moved the
   blocker. */
class ChromeTest {

    private val BLACK = 0xFF000000.toInt()
    private val RED = 0xFFFF0000.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    /* What the unplayed track looks like over a darkish frame. */
    private val TRACK = 0xFFB0B0B0.toInt()

    private fun strip(width: Int, height: Int) = IntArray(width * height) { BLACK }

    private fun IntArray.row(width: Int, y: Int, fromX: Int, toX: Int, color: Int) {
        for (x in fromX until toX) this[y * width + x] = color
    }

    /* A seek bar as YouTube draws one.
     *
     * `played` is how far along the head has got, 0f at the start and 1f at
     * the end. The knob rides at that head, four times the line's thickness,
     * which is the thing that broke the previous two versions of this. */
    private fun seekBar(
        width: Int,
        height: Int,
        barY: Int,
        thickness: Int = 7,
        inset: Int = -1,
        played: Float = 0.3f,
        knob: Int = -1,
        track: Int = TRACK,
    ): IntArray {
        val p = strip(width, height)
        val from = if (inset >= 0) inset else width / 12
        val to = width - from
        val head = from + ((to - from) * played).toInt()
        val knobThickness = if (knob >= 0) knob else thickness * 4

        for (y in barY until barY + thickness) {
            p.row(width, y, from, to, track)          // the whole track
            p.row(width, y, from, head, RED)          // the played part of it
        }
        val knobTop = barY - (knobThickness - thickness) / 2
        val knobFrom = (head - knobThickness / 2).coerceAtLeast(from)
        for (y in knobTop.coerceAtLeast(0) until (knobTop + knobThickness).coerceAtMost(height)) {
            p.row(width, y, knobFrom, (knobFrom + knobThickness).coerceAtMost(width), RED)
        }
        return p
    }

    @Test fun `saturated red is the progress bar, other reds are not`() {
        assertTrue(Chrome.isProgressRed(0xFFFF0000.toInt()))
        assertTrue(Chrome.isProgressRed(0xFFCC1010.toInt()))
        /* Too dark to be the bar. */
        assertFalse(Chrome.isProgressRed(0xFF700000.toInt()))
        /* Bright, but not red-dominated — a warm frame of video. */
        assertFalse(Chrome.isProgressRed(0xFFFF9060.toInt()))
        assertFalse(Chrome.isProgressRed(WHITE))
        assertFalse(Chrome.isProgressRed(BLACK))
    }

    @Test fun `finds the bar and measures what sits below it`() {
        val w = 800; val h = 500
        val p = seekBar(w, h, barY = 350, thickness = 7)
        assertEquals(350..356, Chrome.seekBar(p, w, h))
        assertEquals(356, Chrome.seekBarBottom(p, w, h))
        assertTrue(Chrome.hasProgressRed(p, w, 350..356))
        /* 143 rows under it, less two thicknesses. */
        assertEquals(143 - 14, Chrome.blockHeight(p, w, h, 99))
    }

    /* ------------------------------------------------------------------
       The knob.

       At the head of the played portion YouTube draws a round knob about four
       times the line's thickness, and it travels from one end of the bar to
       the other as a video plays. Two earlier versions of this measured the
       RED and so measured the knob: on a real device a thin line was reported
       as 36px, the margin came out at 180 against a 204px gap, and the strip
       shrank to 35px while reporting success.

       Measuring the track instead makes the knob irrelevant by construction —
       a few dozen columns cannot move a score taken across the full width — so
       these cases pin that rather than a workaround.
       ------------------------------------------------------------------ */

    @Test fun `the knob does not change the measurement, wherever it is`() {
        val w = 2322; val h = 480
        var first: Int? = null
        for (percent in listOf(0, 1, 5, 25, 50, 75, 95, 100)) {
            val p = seekBar(w, h, barY = 260, thickness = 7, played = percent / 100f)
            val m = Chrome.measure(p, w, h)!!
            assertEquals("at $percent% the line is still 7px", 7, m.thickness)
            assertEquals("at $percent% the margin is two lines", 14, m.below - m.blockPx)
            if (first == null) first = m.blockPx
            assertEquals("at $percent% the answer is unchanged", first, m.blockPx)
        }
    }

    /* The case that produced the 35px strip: a 22-minute video six seconds in,
       where the knob is nearly all the red there is. */
    @Test fun `a video six seconds into twenty-two minutes measures the same`() {
        val w = 2322; val h = 480
        val p = seekBar(w, h, barY = 260, thickness = 7, played = 6f / (22 * 60))
        val m = Chrome.measure(p, w, h)!!
        assertEquals(7, m.thickness)
        assertEquals(m.below - 14, m.blockPx)
    }

    /* ------------------------------------------------------------------
       Refusals. Each leaves the compiled-in constant in place rather than
       answering anyway.
       ------------------------------------------------------------------ */

    @Test fun `falls back when there is nothing to measure`() {
        val w = 800; val h = 400
        assertEquals(42, Chrome.blockHeight(strip(w, h), w, h, 42))
    }

    @Test fun `a bright object in the picture is not a line`() {
        val w = 800; val h = 400
        val p = strip(w, h)
        /* A lit window, a face, a table edge — bright, but nothing like the
           width of the frame. */
        for (y in 300..330) p.row(w, y, 200, 340, WHITE)
        assertNull(Chrome.seekBar(p, w, h))
    }

    @Test fun `refuses a band too thick to be a line`() {
        val w = 800; val h = 400
        val p = strip(w, h)
        for (y in 280..340) p.row(w, y, 60, 740, TRACK)
        assertNull(Chrome.seekBar(p, w, h))
        assertEquals(42, Chrome.blockHeight(p, w, h, 42))
    }

    @Test fun `falls back when the bar is flush with the bottom`() {
        val w = 800; val h = 400
        val p = seekBar(w, h, barY = h - 7, thickness = 7)
        /* Nothing below it, so there is nothing to block and no measurement
           worth trusting. */
        assertEquals(42, Chrome.blockHeight(p, w, h, 42))
    }

    @Test fun `falls back when the match is implausibly high up`() {
        val w = 800; val h = 500
        /* A 7px line with 421 rows under it is 60 thicknesses down — not an
           inset under a 3dp bar. */
        val p = seekBar(w, h, barY = 72, thickness = 7)
        assertEquals(42, Chrome.blockHeight(p, w, h, 42))
    }

    @Test fun `refuses a malformed or empty strip rather than reading past it`() {
        assertNull(Chrome.seekBarBottom(IntArray(0), 0, 0))
        assertNull(Chrome.seekBarBottom(IntArray(10), 800, 100))
        assertNull(Chrome.seekBarBottom(IntArray(100), -1, 10))
        assertEquals(42, Chrome.blockHeight(IntArray(10), 800, 100, 42))
    }

    /* The distinction the Activity needs, and the one whose absence broke the
       whole feature on a real phone: a capture that came back blank and a
       capture that genuinely measured the fallback's worth of inset are the
       same number. Latching on the number meant one bad frame was written to
       storage as the answer, after which nothing ever looked again. */
    @Test fun `says it could not tell, rather than answering the fallback`() {
        val w = 800; val h = 400
        /* A blank frame — which is exactly what a hardware-composited player
           gives back to a software canvas. */
        assertNull(Chrome.blockHeightOrNull(strip(w, h), w, h))
        assertEquals(16, Chrome.blockHeight(strip(w, h), w, h, 16))

        /* And a real one still answers. */
        assertEquals(143 - 14, Chrome.blockHeightOrNull(seekBar(w, 500, barY = 350), w, 500))
    }

    /* Scanning is bottom-up: with a line higher in the picture and the real
       bar lower, the lower one wins. */
    @Test fun `takes the lowest line, not the first one down the frame`() {
        val w = 800; val h = 500
        val p = seekBar(w, h, barY = 350, thickness = 7)
        for (y in 120..124) p.row(w, y, 40, 760, TRACK)   // something linear, higher up
        assertEquals(356, Chrome.seekBarBottom(p, w, h))
    }

    /* ------------------------------------------------------------------
       The margin.
       ------------------------------------------------------------------ */

    @Test fun `leaves a margin below the bar, scaled by the bar itself`() {
        val w = 800

        val thin = seekBar(w, 300, barY = 197, thickness = 3)
        val a = Chrome.measure(thin, w, 300)!!
        assertEquals(3, a.thickness)
        assertEquals("two thicknesses", 2 * 3, a.below - a.blockPx)

        /* Three times the line, three times the margin — with nothing told the
           scale changed. */
        val thick = seekBar(w, 400, barY = 191, thickness = 9)
        val b = Chrome.measure(thick, w, 400)!!
        assertEquals(9, b.thickness)
        assertEquals("two thicknesses", 2 * 9, b.below - b.blockPx)
    }

    /* Five thicknesses is right when the thickness is the line's. If anything
       ever inflates it, the margin must not be allowed to swallow the strip —
       so it is bounded by a quarter of the gap, a ratio rather than a number. */
    @Test fun `the margin is capped at a quarter of the gap`() {
        val w = 800; val h = 400
        val p = seekBar(w, h, barY = 375, thickness = 9)
        val m = Chrome.measure(p, w, h)!!
        val below = m.below
        assertEquals(below - below / 4, m.blockPx)
        assertTrue("most of the gap stays blocked", m.blockPx >= below * 3 / 4)
    }

    /* ------------------------------------------------------------------
       A real paused frame.

       Everything above is synthetic and proves the logic; this proves the
       logic was aimed at the right thing. The fixture is a screenshot of the
       actual player, paused, at the size a phone renders it: 2608x1200.

       What it says that no synthetic case would have: the bar's rows score
       78-85% of columns while nothing else in the bottom of the frame reaches
       3%; YouTube insets its controls 9% from the left, past the display
       cutout; and there are over 200 pixels BELOW the line, 18% of the player,
       holding the share button, "More videos" and the YouTube wordmark.
       ------------------------------------------------------------------ */

    /* Decoding it needs javax.imageio, which is NOT on this source set's
       compile classpath: android.jar is the bootclasspath here, and Android's
       java.* has no java.awt. It is there at RUNTIME, because these tests run
       on an ordinary JVM — so the decode goes through reflection.

       Ugly, and worth it. The alternatives were a hand-written JPEG decoder, a
       multi-megabyte raw pixel dump in the repository, or a second Gradle
       module — all of that to avoid three reflective calls in one test file. */
    private class Frame(private val image: Any) {
        private val cls = Class.forName("java.awt.image.BufferedImage")
        val width: Int get() = cls.getMethod("getWidth").invoke(image) as Int
        val height: Int get() = cls.getMethod("getHeight").invoke(image) as Int

        fun pixels(top: Int, rows: Int): IntArray {
            val out = IntArray(width * rows)
            cls.getMethod(
                "getRGB",
                Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                IntArray::class.java, Int::class.java, Int::class.java,
            ).invoke(image, 0, top, width, rows, out, 0, width)
            return out
        }

        companion object {
            fun read(name: String): Frame {
                val stream: InputStream = Frame::class.java.getResourceAsStream(name)
                    ?: error("missing test fixture: $name")
                val image = Class.forName("javax.imageio.ImageIO")
                    .getMethod("read", InputStream::class.java)
                    .invoke(null, stream) ?: error("could not decode: $name")
                return Frame(image)
            }
        }
    }

    private fun realFrame() = Frame.read("/paused-player.jpg")

    /* Where the app looks: the bottom two fifths. */
    private fun bottomStrip(f: Frame): Triple<IntArray, Int, Int> {
        val stripH = (f.height * 0.4f).toInt()
        return Triple(f.pixels(f.height - stripH, stripH), f.width, stripH)
    }

    @Test fun `finds the seek bar in a real paused player`() {
        val f = realFrame()
        assertEquals(2608, f.width)
        assertEquals(1200, f.height)

        val (px, w, stripH) = bottomStrip(f)
        val top = f.height - stripH
        val bar = Chrome.seekBar(px, w, stripH)!!
        /* Rows 974..982 in the full frame: the drawn line plus the rows its
           edges are antialiased into, which is what a thumb aims at. */
        assertEquals(974, top + bar.first)
        assertEquals(982, top + bar.last)
        assertTrue("and it carries the played portion's red", Chrome.hasProgressRed(px, w, bar))
    }

    /* The signal, as the numbers that made it worth switching to. */
    @Test fun `the bar's rows stand out from everything else in the frame`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f)
        val top = f.height - stripH

        for (y in 974..982) {
            assertTrue(
                "row $y should read as a line",
                Chrome.lineScore(px, w, stripH, y - top) >= 50,
            )
        }
        /* A few rows away in either direction there is nothing at all. */
        assertTrue(Chrome.lineScore(px, w, stripH, 968 - top) < 10)
        assertTrue(Chrome.lineScore(px, w, stripH, 990 - top) < 10)
    }

    @Test fun `the real frame measures a nine pixel line with 217 below it`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f)
        val m = Chrome.measure(px, w, stripH)!!
        assertEquals("the line, not the knob on its head", 9, m.thickness)
        assertEquals(217, m.below)
        assertEquals(217 - 18, m.blockPx)
    }

    /* The two things the blocker has to get right at once, on the real frame,
       with no density anywhere. */
    @Test fun `the blocked strip covers the chrome but spares the bar`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f)
        val blockTop = f.height - Chrome.blockHeight(px, w, stripH, fallbackPx = 44)

        assertTrue("blocker must start below the bar's last row (982)", blockTop > 982)
        assertEquals(983 + 18, blockTop)
        /* The share button, "More videos" and the YouTube wordmark begin at
           y=1040 and must be covered. */
        assertTrue("blocker must cover the chrome starting at y=1040", blockTop <= 1040)
    }

    /* The picture is full of red — a red-lit set behind the presenter, with
       runs 300 pixels long two thirds of the way across — and none of it is a
       line. Measuring the track rather than the red is what makes that a
       non-question. */
    @Test fun `the red set behind the presenter is not mistaken for the bar`() {
        val f = realFrame()
        assertNull(Chrome.seekBar(f.pixels(0, 900), f.width, 900))
    }

    /* ------------------------------------------------------------------
       Other devices.

       One real frame proves the analysis works on one phone. It cannot say
       anything about a tablet, a 720p handset or a foldable — and an earlier
       version was tuned to that one frame in a way that would have broken on
       all three, because it made the capture and the sanity limit fractions of
       the player's height when what they measure is a fixed physical size.

       Nothing here is in dp and nothing knows the density: the line's own
       thickness is the scale. So what these prove is stronger than "the
       constants are right" — the SAME layout at seven resolutions and five
       densities produces the right answer with nothing told the scale.
       ------------------------------------------------------------------ */

    private data class Device(val name: String, val w: Int, val h: Int, val density: Float)

    private val devices = listOf(
        /* landscape, because the player is locked to it */
        Device("720p handset", 1280, 720, 1.5f),
        Device("1080p handset", 1920, 1080, 2.0f),
        Device("tall handset", 2340, 1080, 2.75f),
        Device("the measured frame", 2608, 1200, 2.75f),
        Device("10in tablet", 2560, 1600, 2.0f),
        Device("foldable inner", 2208, 1768, 3.0f),
        Device("qHD budget phone", 960, 540, 1.5f),
    )

    /* A player frame laid out the way YouTube lays one out, at a given
       density. Nothing under test is given the density — it exists here only
       to build the fixture. */
    private fun deviceFrame(
        d: Device,
        played: Float = 0.3f,
        cutoutDp: Int = 0,
    ): Triple<IntArray, Int, Int> {
        fun dp(v: Number) = (v.toFloat() * d.density).toInt()
        val stripH = (d.h * 0.4f).toInt()
        val thickness = dp(3).coerceAtLeast(2)
        /* The line's last row sits 80dp above the bottom. */
        val barY = stripH - dp(80) - thickness
        val p = seekBar(
            d.w, stripH,
            barY = barY,
            thickness = thickness,
            inset = dp(16) + dp(cutoutDp),
            played = played,
        )
        return Triple(p, d.w, stripH)
    }

    @Test fun `the bar is found on every device shape`() {
        for (d in devices) {
            val (px, w, stripH) = deviceFrame(d)
            val bar = Chrome.seekBar(px, w, stripH)
            assertTrue("${d.name}: no bar found", bar != null)
            assertEquals(
                "${d.name}: bar in the wrong place",
                (80 * d.density).toInt(),
                stripH - 1 - bar!!.last,
            )
        }
    }

    /* The heart of it. The margin is five line-thicknesses and the line is
       drawn 3dp thick, so the slack under the bar lands at 15dp on every one
       of these WITHOUT anything being told what a dp is. */
    @Test fun `the blocked strip lands correctly on every device shape`() {
        for (d in devices) {
            fun dp(v: Number) = (v.toFloat() * d.density).toInt()
            val (px, w, stripH) = deviceFrame(d)
            val m = Chrome.measure(px, w, stripH)!!

            val thickness = dp(3).coerceAtLeast(2)
            assertEquals("${d.name}: wrong thickness", thickness, m.thickness)
            assertEquals("${d.name}: wrong slack", 2 * thickness, m.below - m.blockPx)
            /* And the chrome, which starts about 21dp under the bar, is still
               covered on every one of them. */
            assertTrue("${d.name}: chrome left reachable", m.below - m.blockPx <= dp(21))
        }
    }

    /* Double every pixel and every answer doubles, with no scale passed in
       anywhere. */
    @Test fun `doubling the resolution doubles the measurement`() {
        val (p1, w1, h1) = deviceFrame(Device("1x", 1280, 720, 1.5f))
        val (p2, w2, h2) = deviceFrame(Device("2x", 2560, 1440, 3.0f))
        val one = Chrome.blockHeight(p1, w1, h1, 0)
        val two = Chrome.blockHeight(p2, w2, h2, 0)
        /* Within a few pixels: the fixtures round dp to whole pixels at each
           scale, so an exact doubling is not something either side promises. */
        assertTrue("$two should be about twice $one", Math.abs(two - 2 * one) <= 6)
    }

    /* A display cutout pushes YouTube's controls inwards — 9% of the width on
       the measured frame. A wider one must not hide the bar. */
    @Test fun `a wide display cutout does not hide the bar`() {
        val d = Device("cutout phone", 2340, 1080, 2.75f)
        val (px, w, stripH) = deviceFrame(d, cutoutDp = 48)
        assertEquals((80 * d.density).toInt(), stripH - 1 - Chrome.seekBar(px, w, stripH)!!.last)
    }
}
