package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.InputStream

/* The pixel analysis that replaces a compiled-in guess at YouTube's inset.

   Everything here is a synthetic strip: a black background, a red run where
   the played part of the progress bar would be, and whatever else the case is
   about. That is the whole point of keeping this pure — the alternative is
   finding out on a device that a red jumper moved the blocker. */
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
        assertEquals(93, Chrome.seekBarBottom(p, w, h))
        assertEquals(6, Chrome.blockHeight(p, w, h, 99, w, 0))
    }

    /* The played portion grows rightwards from the start of the bar, so red
       that begins in the middle of the frame is something in the video. */
    @Test fun `ignores red that does not start at the left`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        p.row(w, 95, 400, 700, RED)
        assertNull(Chrome.seekBarBottom(p, w, h))
        assertEquals(99, Chrome.blockHeight(p, w, h, 99, w, 0))
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
        assertEquals(7, Chrome.blockHeight(p, w, h, 99, w, 0))
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
        assertEquals(42, Chrome.blockHeight(strip(w, h), w, h, 42, h / 4, 0))
    }

    @Test fun `falls back when the bar is flush with the bottom`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        p.row(w, h - 1, 12, 300, RED)
        assertEquals(h - 1, Chrome.seekBarBottom(p, w, h))
        /* Nothing below it, so there is nothing to block and no measurement
           worth trusting. */
        assertEquals(42, Chrome.blockHeight(p, w, h, 42, h / 4, 0))
    }

    @Test fun `falls back when the match is implausibly high up`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        /* More than a quarter of the strip below it — not an inset. */
        p.row(w, 40, 12, 300, RED)
        assertEquals(42, Chrome.blockHeight(p, w, h, 42, h / 4, 0))
    }

    @Test fun `refuses a malformed or empty strip rather than reading past it`() {
        assertNull(Chrome.seekBarBottom(IntArray(0), 0, 0))
        assertNull(Chrome.seekBarBottom(IntArray(10), 800, 100))
        assertNull(Chrome.seekBarBottom(IntArray(100), -1, 10))
        assertEquals(42, Chrome.blockHeight(IntArray(10), 800, 100, 42, 25, 0))
    }

    /* A finger aiming at a 9-pixel line lands around it. Everything above the
       bar is reachable already, so the margin only has to cover the ones that
       land low — but it has to, or the one control the reveal exists to reach
       becomes the one control nobody can hit. */
    @Test fun `leaves a margin below the bar for a fat finger`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        p.row(w, 60, 12, 300, RED)
        /* 39 rows below the bar; 15 of them stay reachable. */
        assertEquals(39, Chrome.blockHeight(p, w, h, 42, h, 0))
        assertEquals(24, Chrome.blockHeight(p, w, h, 42, h, 15))
    }

    @Test fun `a margin wider than the gap blocks nothing at all`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        p.row(w, 95, 12, 300, RED)
        /* Four rows below the bar and a margin of twenty. Zero is the
           measurement — there is nothing down there — not a failure, so the
           fallback must NOT come back instead. */
        assertEquals(0, Chrome.blockHeight(p, w, h, 42, h, 20))
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

    /* Where the app looks: the bottom 30% of the player. */
    private fun bottomStrip(f: Frame, fraction: Float): Triple<IntArray, Int, Int> {
        val stripH = (f.height * fraction).toInt()
        return Triple(f.pixels(f.height - stripH, stripH), f.width, stripH)
    }

    @Test fun `finds the seek bar in a real paused player`() {
        val f = realFrame()
        assertEquals(2608, f.width)
        assertEquals(1200, f.height)

        val (px, w, stripH) = bottomStrip(f, 0.30f)
        val top = f.height - stripH
        val bottom = Chrome.seekBarBottom(px, w, stripH)!!
        /* The bar's last row is y=982 in the full frame. */
        assertEquals(982, top + bottom)
    }

    /* The strip has to be tall enough to contain the bar in the first place.
       At a fifth it would start at 80% of the frame and only just reach it,
       which is why the app captures 30%. */
    @Test fun `a thirty percent strip comfortably contains the bar`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f, 0.30f)
        val bottom = Chrome.seekBarBottom(px, w, stripH)!!
        assertTrue("bar should not be pinned to the strip's top edge", bottom > stripH / 4)
    }

    /* The two things the blocker has to get right at once, stated as the
       pixels they are on this frame. */
    @Test fun `the blocked strip covers the chrome but spares the bar`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f, 0.30f)

        /* 20dp of margin at this frame's density, and the same quarter-height
           sanity limit the Activity passes. */
        val margin = 55
        val height = Chrome.blockHeight(px, w, stripH, 44, f.height / 4, margin)
        val blockTop = f.height - height

        /* Below the bar, so the bar itself stays draggable... */
        assertTrue("blocker must start below the bar's last row (982)", blockTop > 982)
        /* ...with room under it for a finger that lands low... */
        assertTrue("at least 20dp of slack under the bar", blockTop - 982 >= 50)
        /* ...and still above the row of ways out of the app. The share button,
           "More videos" and the YouTube wordmark begin at y=1040. */
        assertTrue("blocker must cover the chrome starting at y=1040", blockTop <= 1040)
    }

    /* What the margin costs, in numbers: without it the blocker would start
       one pixel under the bar. */
    @Test fun `the real frame has 217 pixels under the bar`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f, 0.30f)
        assertEquals(217, Chrome.blockHeight(px, w, stripH, 44, f.height / 4, 0))
    }

    /* The picture is full of red — a red-lit set behind the presenter, with
       runs 300 pixels long two thirds of the way across. None of it starts at
       the left edge, which is the whole reason that constraint is there. */
    @Test fun `the red set behind the presenter is not mistaken for the bar`() {
        val f = realFrame()
        /* Everything above the bar, where only video lives. */
        assertNull(Chrome.seekBarBottom(f.pixels(0, 900), f.width, 900))
    }
}
