package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.InputStream

/* The pixel analysis that replaces a compiled-in guess at YouTube's inset.

   Three kinds of case, in this order:

   - synthetic strips, a few pixels of black with a red run in them, pinning
     the logic one rule at a time;
   - one real paused frame, which is what caught the logic being aimed at the
     wrong thing;
   - the same layout at seven device geometries, which is what stops it being
     tuned to the one phone the frame came from.

   All of it on a plain JVM, which is the whole point of keeping Chrome pure —
   the alternative is finding out on a device that a red jumper moved the
   blocker. */
class ChromeTest {

    private val BLACK = 0xFF000000.toInt()
    private val RED = 0xFFFF0000.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()

    private fun strip(width: Int, height: Int) = IntArray(width * height) { BLACK }

    private fun IntArray.row(width: Int, y: Int, fromX: Int, toX: Int, color: Int) {
        for (x in fromX until toX) this[y * width + x] = color
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
        val w = 800; val h = 100
        val p = strip(w, h)
        /* A four-pixel-tall bar at y = 90..93, played about a third across,
           leaving six rows of inset underneath. */
        for (y in 90..93) p.row(w, y, 12, 260, RED)
        assertEquals(90..93, Chrome.seekBar(p, w, h))
        assertEquals(93, Chrome.seekBarBottom(p, w, h))
        /* Six rows under it. Five thicknesses would be twenty, so the quarter
           of the gap ceiling applies and one row is given back. */
        assertEquals(5, Chrome.blockHeight(p, w, h, 99))
    }

    /* The played portion grows rightwards from the start of the bar, so red
       that begins in the middle of the frame is something in the video. */
    @Test fun `ignores red that does not start at the left`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        p.row(w, 95, 400, 700, RED)
        assertNull(Chrome.seekBarBottom(p, w, h))
        assertEquals(99, Chrome.blockHeight(p, w, h, 99))
    }

    @Test fun `ignores a stray red pixel at the left edge`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        p.row(w, 95, 0, 1, RED)
        assertNull(Chrome.seekBarBottom(p, w, h))
    }

    /* A video barely started still has a played portion; it is just short.
       The run threshold has to be small enough to see it. */
    @Test fun `finds a bar that has only just begun to fill`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        p.row(w, 92, 12, 22, RED)
        assertEquals(92, Chrome.seekBarBottom(p, w, h))
    }

    /* Scanning is bottom-up: with a red frame higher in the picture and the
       real bar lower, the lower one wins. */
    @Test fun `takes the lowest match, not the first one down the frame`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        p.row(w, 20, 0, 500, RED)   // a red band in the video
        p.row(w, 94, 12, 300, RED)  // the actual bar
        assertEquals(94, Chrome.seekBarBottom(p, w, h))
    }

    /* Both are refusals rather than guesses, and both leave the compiled-in
       constant in place. */
    @Test fun `falls back when there is nothing to measure`() {
        val w = 800; val h = 100
        assertEquals(42, Chrome.blockHeight(strip(w, h), w, h, 42))
    }

    @Test fun `falls back when the bar is flush with the bottom`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        p.row(w, h - 1, 12, 300, RED)
        assertEquals(h - 1, Chrome.seekBarBottom(p, w, h))
        /* Nothing below it, so there is nothing to block and no measurement
           worth trusting. */
        assertEquals(42, Chrome.blockHeight(p, w, h, 42))
    }

    /* The limit is in bar-thicknesses, not pixels and not a fraction of the
       strip: a one-pixel line with 59 rows under it is 59 thicknesses down,
       which is not an inset under a 3dp bar. */
    @Test fun `falls back when the match is implausibly high up`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        p.row(w, 40, 12, 300, RED)
        assertEquals(42, Chrome.blockHeight(p, w, h, 42))
    }

    /* And a red band far too thick to be a 3dp line is not the bar at all,
       however far left it starts. */
    @Test fun `refuses a red band too thick to be a seek bar`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        for (y in 70..95) p.row(w, y, 0, 400, RED)
        assertNull(Chrome.seekBar(p, w, h))
        assertEquals(42, Chrome.blockHeight(p, w, h, 42))
    }

    /* The distinction the Activity needs, and the one whose absence broke the
       whole feature on a real phone: a capture that came back blank and a
       capture that genuinely measured the fallback's worth of inset are the
       same number. Latching on the number meant one bad frame was written to
       storage as the answer, after which nothing ever looked again. */
    @Test fun `says it could not tell, rather than answering the fallback`() {
        val w = 800; val h = 100
        /* A blank frame — which is exactly what a hardware-composited player
           gives back to a software canvas. */
        assertNull(Chrome.blockHeightOrNull(strip(w, h), w, h))
        assertEquals(16, Chrome.blockHeight(strip(w, h), w, h, 16))

        /* And a real one still answers. */
        val p = strip(w, h)
        for (y in 76..78) p.row(w, y, 12, 300, RED)
        /* 21 rows below a 3px bar; five thicknesses is 15, the quarter-gap
           ceiling is 5, so 5 comes off. */
        assertEquals(21 - 5, Chrome.blockHeightOrNull(p, w, h))
    }

    @Test fun `refuses a malformed or empty strip rather than reading past it`() {
        assertNull(Chrome.seekBarBottom(IntArray(0), 0, 0))
        assertNull(Chrome.seekBarBottom(IntArray(10), 800, 100))
        assertNull(Chrome.seekBarBottom(IntArray(100), -1, 10))
        assertEquals(42, Chrome.blockHeight(IntArray(10), 800, 100, 42))
    }

    /* A finger aiming at a thin line lands around it. Everything above the bar
       is reachable already, so the margin only has to cover the ones that land
       low — but it has to, or the one control the reveal exists to reach
       becomes the one control nobody can hit.

       Five bar-thicknesses of it, taken from the bar the picture actually
       drew. Nobody passes a margin in. */
    @Test fun `leaves a margin below the bar, scaled by the bar itself`() {
        val w = 800; val h = 300

        val thin = strip(w, h)
        for (y in 197..199) thin.row(w, y, 12, 300, RED)   // 3px line, 100 below
        val a = Chrome.measure(thin, w, h)!!
        assertEquals(3, a.thickness)
        assertEquals("five thicknesses", 5 * 3, a.below - a.blockPx)

        /* Three times the line, three times the margin — with nothing told the
           scale changed. Gaps chosen so the quarter-of-the-gap ceiling is not
           what is being measured here; that has a test of its own above. */
        val thick = strip(w, h)
        for (y in 91..99) thick.row(w, y, 12, 300, RED)    // 9px line, 200 below
        val b = Chrome.measure(thick, w, h)!!
        assertEquals(9, b.thickness)
        assertEquals("five thicknesses", 5 * 9, b.below - b.blockPx)
    }

    /* The ceiling on the margin, stated on its own. Five thicknesses is right
       when the thickness is the line's; when something inflates it the margin
       must not be allowed to swallow the strip, so it is bounded by a quarter
       of the gap — a ratio, not a number. */
    @Test fun `the margin is capped at a quarter of the gap`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        for (y in 93..95) p.row(w, y, 12, 300, RED)
        /* Four rows under a three-pixel bar. Five thicknesses is fifteen,
           which is more than the gap; a quarter of four is one. */
        assertEquals(3, Chrome.blockHeight(p, w, h, 42))
    }

    /* ------------------------------------------------------------------
       The scrubber knob.

       At the head of the played portion YouTube draws a round knob about four
       times the line's thickness, and on a long video it sits at the left of
       the bar for minutes — so the red BAND is knob-tall while the LINE is
       not. Since the margin is a multiple of the thickness, measuring the band
       made the margin four times too big.

       This is not hypothetical. On a real device: a 9px line reported as 36, a
       180px margin against a 204px gap, and a 35px strip where about 170 was
       right — reported as a success, because nothing knew the difference.
       ------------------------------------------------------------------ */

    /* A bar with a knob on its head: a thin line from the bar's start to
       wherever playback has reached, and a fat blob AT that head — which is
       where YouTube draws it, and which moves right as the video plays. */
    private fun barWithKnob(
        width: Int,
        height: Int,
        barY: Int,
        lineThickness: Int,
        knobThickness: Int,
        from: Int,
        to: Int,
    ): IntArray {
        val p = strip(width, height)
        for (y in barY until barY + lineThickness) p.row(width, y, from, to, RED)
        val knobTop = barY - (knobThickness - lineThickness) / 2
        val knobFrom = (to - knobThickness / 2).coerceAtLeast(from)
        for (y in knobTop until knobTop + knobThickness) {
            p.row(width, y, knobFrom, (knobFrom + knobThickness).coerceAtMost(width), RED)
        }
        return p
    }

    /* Once the knob has moved off the start, its own rows no longer look like
       the bar at all — they are a short red run in the middle of the frame,
       which the left-anchored rule already refuses — so the band is the line
       and the thickness follows. The knob only confuses anything while it is
       still sitting on the bar's left end. */
    @Test fun `away from the start the band is the line itself`() {
        val w = 2322; val h = 480
        val p = barWithKnob(w, h, barY = 280, lineThickness = 9, knobThickness = 36, from = 200, to = 900)
        val band = Chrome.seekBar(p, w, h)!!
        assertEquals(9, band.last - band.first + 1)
        assertEquals(9, Chrome.barThickness(p, w, h, band))
    }

    /* Wherever playback has reached. The knob starts at the left and travels
       to the right as a video plays, and the measurement has to be the same
       whichever end of the bar it is sitting on. */
    @Test fun `the thickness is the line at every position of the knob`() {
        val w = 2322; val h = 480
        val from = 200
        val end = 2100
        for (percent in listOf(5, 25, 50, 75, 95, 100)) {
            val to = from + (end - from) * percent / 100
            val p = barWithKnob(w, h, barY = 267, lineThickness = 9, knobThickness = 36, from = from, to = to)
            val band = Chrome.seekBar(p, w, h)!!
            assertEquals(
                "at $percent% played the line is still 9px",
                9,
                Chrome.barThickness(p, w, h, band),
            )
            val m = Chrome.measure(p, w, h)!!
            assertEquals("at $percent% the margin is five lines", 45, m.below - m.blockPx)
        }
    }

    /* The one position where there is no line to measure, because playback has
       not produced one yet. The knob is all there is, so the thickness reads
       as the knob — and the ceiling on the margin is what keeps the strip from
       vanishing. That case has its own test below; this pins the boundary. */
    @Test fun `at the very start the knob is all there is`() {
        val w = 2322; val h = 480
        val p = barWithKnob(w, h, barY = 267, lineThickness = 9, knobThickness = 36, from = 200, to = 205)
        val band = Chrome.seekBar(p, w, h)!!
        assertEquals(36, Chrome.barThickness(p, w, h, band))
        val m = Chrome.measure(p, w, h)!!
        assertTrue("the ceiling keeps most of the gap blocked", m.blockPx >= m.below * 3 / 4)
    }

    /* The device numbers, end to end. */
    @Test fun `a knob no longer swallows the whole inset`() {
        val w = 2322; val h = 480
        val p = barWithKnob(w, h, barY = 267, lineThickness = 9, knobThickness = 36, from = 200, to = 900)
        val m = Chrome.measure(p, w, h)!!
        assertEquals("thickness should be the line", 9, m.thickness)
        /* The knob's lower half sets the band's bottom edge. What matters is
           what comes off the gap: five line-thicknesses, not five knobs. */
        assertEquals(45, m.below - m.blockPx)
        assertTrue("most of the gap stays blocked", m.blockPx > m.below * 3 / 4)
    }

    /* And when nothing but the knob is on screen — the first seconds of a long
       video, where there is no line to measure — the margin is bounded by the
       gap rather than by the knob, so the strip survives. */
    @Test fun `the margin never eats more than a quarter of the gap`() {
        val w = 2322; val h = 480
        val p = strip(w, h)
        for (y in 250..285) p.row(w, y, 200, 236, RED)   // knob only, 36px
        val m = Chrome.measure(p, w, h)!!
        assertEquals(36, m.thickness)
        val below = h - 1 - 285
        /* Five thicknesses would be 180, more than the gap. A quarter of the
           gap is the ceiling, so three quarters of it stays blocked. */
        assertEquals(below - below / 4, m.blockPx)
        assertTrue("most of the gap must still be blocked", m.blockPx > below / 2)
    }

    /* ------------------------------------------------------------------
       A real paused frame.

       Everything above is synthetic and proves the logic; this proves the
       logic was aimed at the right thing. The fixture is a screenshot of the
       actual player, paused, at the size a phone renders it: 2608x1200.

       What is in it that the synthetic cases could never have caught: the
       bar's colour is #FF0032 rather than the #FF0000 anyone would assume;
       YouTube insets its controls 9% from the left, past the display cutout,
       not to the edge; the bar is 9 pixels tall; and — the one that mattered —
       there are 217 pixels BELOW it, 18% of the player, holding the share
       button, "More videos" and the YouTube wordmark. The first version of
       this code rejected any gap over a quarter of the captured strip as
       implausible, which would have thrown this real frame away.
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

    /* Where the app looks: the bottom two fifths. That fraction is not a
       claim about where YouTube's chrome is — Chrome works that out from the
       pixels — only about how much of the screen is worth drawing, and how
       much is deliberately never captured. */
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
        /* Rows 974..982 in the full frame: nine pixels of bar. */
        assertEquals(974, top + bar.first)
        assertEquals(982, top + bar.last)
        assertEquals(9, bar.last - bar.first + 1)
    }

    /* The strip has to contain the bar with room over it, not catch it on the
       edge. An early version captured the bottom fifth, which on this frame
       would have reached the bar with 12 pixels to spare. */
    @Test fun `the captured strip comfortably contains the bar`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f)
        val bottom = Chrome.seekBarBottom(px, w, stripH)!!
        assertTrue("bar should not be pinned to the strip's top edge", bottom > stripH / 4)
    }

    /* The two things the blocker has to get right at once, stated as the
       pixels they are on this frame — and with no density anywhere, because
       the nine-pixel bar is the scale. */
    @Test fun `the blocked strip covers the chrome but spares the bar`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f)

        val height = Chrome.blockHeight(px, w, stripH, fallbackPx = 44)
        val blockTop = f.height - height

        /* Below the bar, so the bar itself stays draggable... */
        assertTrue("blocker must start below the bar's last row (982)", blockTop > 982)
        /* ...with five bar-thicknesses of room under it: the bar is 9 pixels,
           so 45 rows starting from 983, the first row that is not bar. */
        assertEquals(983 + 45, blockTop)
        /* ...and still above the row of ways out of the app. The share button,
           "More videos" and the YouTube wordmark begin at y=1040. */
        assertTrue("blocker must cover the chrome starting at y=1040", blockTop <= 1040)
    }

    /* The whole measurement on the real frame, as numbers rather than as a
       conclusion — the form the app now reports on About, so a bad reading can
       be placed rather than guessed at. */
    @Test fun `the real frame measures a nine pixel line with 217 below it`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f)
        val m = Chrome.measure(px, w, stripH)!!
        assertEquals("the line, not the knob on its head", 9, m.thickness)
        assertEquals(217, m.below)
        assertEquals(217 - 45, m.blockPx)
    }

    /* The picture is full of red — a red-lit set behind the presenter, with
       runs 300 pixels long two thirds of the way across. None of it starts at
       the left edge, which is the whole reason that constraint is there. */
    @Test fun `the red set behind the presenter is not mistaken for the bar`() {
        val f = realFrame()
        /* Everything above the bar, where only video lives. */
        assertNull(Chrome.seekBarBottom(f.pixels(0, 900), f.width, 900))
    }

    /* ------------------------------------------------------------------
       Other devices.

       One real frame proves the analysis works on one phone. It cannot say
       anything about a tablet, a 720p handset or a foldable.

       Two earlier versions were tuned to that one frame and would have broken
       on all three. The first made the capture and the sanity limit fractions
       of the player's height, when YouTube's chrome is a fixed physical size —
       roughly 80dp, which is 25% of a short landscape phone and 10% of a
       tablet. The second fixed that by putting the 80dp figure in dimens, and
       traded one problem for another: 80dp was eyeballed off a screenshot that
       does not record its own density, so a guess at the chrome was being
       multiplied by a guess at the scale.

       Neither number exists any more. Chrome takes its scale from the drawn
       bar's own thickness, so what these cases prove is stronger than "the
       constants are right": the SAME layout at seven resolutions and five
       densities produces the right answer with nothing told the scale.
       ------------------------------------------------------------------ */

    /* A player frame laid out the way YouTube lays one out, at a given
       density. Nothing under test is given the density — it exists here only
       to build the fixture. */
    private fun deviceFrame(
        width: Int,
        height: Int,
        density: Float,
        playedFraction: Float = 0.3f,
        cutoutDp: Int = 0,
    ): IntArray {
        fun dp(v: Number) = (v.toFloat() * density).toInt()
        val px = IntArray(width * height) { BLACK }
        /* Distance from the bottom to the bar's last row. */
        val below = dp(80)
        val barBottom = height - 1 - below
        val barTop = barBottom - dp(3).coerceAtLeast(1)
        val from = dp(16) + dp(cutoutDp)
        val to = from + ((width - 2 * from) * playedFraction).toInt()
        for (y in barTop..barBottom) px.row(width, y, from, to, RED)
        return px
    }

    /* The one thing the Activity still decides: how much to draw. */
    private fun stripFor(height: Int) = (height * 0.4f).toInt()

    private data class Device(val name: String, val w: Int, val h: Int, val density: Float)

    /* The strip, taken off the bottom exactly as the Activity takes it. */
    private fun stripOf(d: Device, frame: IntArray): Triple<IntArray, Int, Int> {
        val stripH = stripFor(d.h)
        val strip = IntArray(d.w * stripH)
        System.arraycopy(frame, (d.h - stripH) * d.w, strip, 0, strip.size)
        return Triple(strip, d.w, stripH)
    }

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

    @Test fun `the bar is found on every device shape`() {
        for (d in devices) {
            val (strip, w, stripH) = stripOf(d, deviceFrame(d.w, d.h, d.density))
            val bar = Chrome.seekBar(strip, w, stripH)
            assertTrue("${d.name}: no bar found", bar != null)
            assertEquals(
                "${d.name}: bar in the wrong place",
                (80 * d.density).toInt(),
                stripH - 1 - bar!!.last,
            )
        }
    }

    /* The heart of it. The margin is five bar-thicknesses, and the bar is
       drawn 3dp thick — so on every device the slack under the bar comes out
       at 15dp WITHOUT anything being told what a dp is here. */
    @Test fun `the blocked strip lands correctly on every device shape`() {
        for (d in devices) {
            fun dp(v: Number) = (v.toFloat() * d.density).toInt()
            val (strip, w, stripH) = stripOf(d, deviceFrame(d.w, d.h, d.density))

            val height = Chrome.blockHeight(strip, w, stripH, fallbackPx = dp(16))
            val blockTop = d.h - height
            val barBottom = d.h - 1 - dp(80)

            assertTrue("${d.name}: blocker sits on the bar", blockTop > barBottom)
            /* Five thicknesses of a bar drawn dp(3)+1 tall, counted from the
               first row that is not bar. */
            val thickness = dp(3).coerceAtLeast(1) + 1
            assertEquals(
                "${d.name}: wrong slack under the bar",
                5 * thickness,
                blockTop - (barBottom + 1),
            )
            /* And the chrome — which starts about 21dp under the bar — is
               still covered on every one of them. */
            assertTrue("${d.name}: chrome left reachable", blockTop <= barBottom + dp(21))
        }
    }

    /* Fixture geometry aside, this is the property that replaced the dp
       constants: double every pixel and every answer doubles, with no scale
       passed in anywhere. */
    @Test fun `doubling the resolution doubles the measurement`() {
        val one = Device("1x", 1280, 720, 1.5f)
        val two = Device("2x", 2560, 1440, 3.0f)
        val (s1, w1, h1) = stripOf(one, deviceFrame(one.w, one.h, one.density))
        val (s2, w2, h2) = stripOf(two, deviceFrame(two.w, two.h, two.density))
        assertEquals(2 * Chrome.blockHeight(s1, w1, h1, 0), Chrome.blockHeight(s2, w2, h2, 0))
    }

    /* A display cutout pushes YouTube's controls inwards — 9% of the width on
       the measured frame. The left-anchored test has to survive that on a
       device with a wider one. */
    @Test fun `a wide display cutout does not hide the bar`() {
        val d = Device("cutout phone", 2340, 1080, 2.75f)
        /* 48dp of cutout on top of the usual inset: 15% of this width. */
        val (strip, w, stripH) = stripOf(d, deviceFrame(d.w, d.h, d.density, cutoutDp = 48))
        assertEquals((80 * d.density).toInt(), stripH - 1 - Chrome.seekBarBottom(strip, w, stripH)!!)
    }

    /* A video paused seconds in has a played portion a few pixels wide. On a
       540p screen that is genuinely tiny, and it still has to count. */
    @Test fun `a barely-started video is found on a small screen`() {
        val d = Device("qHD budget phone", 960, 540, 1.5f)
        val (strip, w, stripH) = stripOf(d, deviceFrame(d.w, d.h, d.density, playedFraction = 0.01f))
        assertTrue("a 9-pixel played portion should still register", Chrome.seekBarBottom(strip, w, stripH) != null)
    }
}
