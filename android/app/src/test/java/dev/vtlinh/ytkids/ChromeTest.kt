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
        /* Six rows under it, less five thicknesses of margin — which is more
           than six, so nothing is left to block. */
        assertEquals(0, Chrome.blockHeight(p, w, h, 99))
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
        assertEquals(21 - 15, Chrome.blockHeightOrNull(p, w, h))
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
        val w = 800; val h = 200
        val thin = strip(w, h)
        for (y in 118..120) thin.row(w, y, 12, 300, RED)   // 3px bar, 79 below
        assertEquals(79 - 15, Chrome.blockHeight(thin, w, h, 42))

        /* The same layout at three times the scale: three times the bar, three
           times the gap, three times the margin. The blocked height triples
           too, without anything being told the scale changed. */
        val thick = strip(w, h)
        for (y in 108..116) thick.row(w, y, 12, 300, RED)  // 9px bar, 83 below
        assertEquals(83 - 45, Chrome.blockHeight(thick, w, h, 42))
    }

    @Test fun `a margin wider than the gap blocks nothing at all`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        for (y in 93..95) p.row(w, y, 12, 300, RED)
        /* Four rows under a three-pixel bar, and the margin is fifteen. Zero
           is the measurement — there is nothing down there — not a failure, so
           the fallback must NOT come back instead. */
        assertEquals(0, Chrome.blockHeight(p, w, h, 42))
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

    /* What the margin costs, in numbers: 217 pixels under the bar, 45 of them
       given back. */
    @Test fun `the real frame has 217 pixels under the bar`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f)
        assertEquals(217 - 45, Chrome.blockHeight(px, w, stripH, 44))
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
