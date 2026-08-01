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

    /* This frame's density is not recorded anywhere in it. 2.75 is the most
       likely value for a 2608x1200 panel and is what the dp figures below are
       converted with; nothing in the test depends on it being exactly right,
       only on it being the same everywhere. */
    private val FRAME_DENSITY = 2.75f

    /* Where the app looks, computed the way PlayerActivity computes it rather
       than as a number chosen to fit. */
    private fun bottomStrip(f: Frame): Triple<IntArray, Int, Int> {
        val stripH = stripFor(f.height, FRAME_DENSITY)
        return Triple(f.pixels(f.height - stripH, stripH), f.width, stripH)
    }

    @Test fun `finds the seek bar in a real paused player`() {
        val f = realFrame()
        assertEquals(2608, f.width)
        assertEquals(1200, f.height)

        val (px, w, stripH) = bottomStrip(f)
        val top = f.height - stripH
        val bottom = Chrome.seekBarBottom(px, w, stripH)!!
        /* The bar's last row is y=982 in the full frame. */
        assertEquals(982, top + bottom)
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
       pixels they are on this frame. */
    @Test fun `the blocked strip covers the chrome but spares the bar`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f)

        val margin = (16 * FRAME_DENSITY).toInt()
        val height = Chrome.blockHeight(
            px, w, stripH,
            fallbackPx = (16 * FRAME_DENSITY).toInt(),
            maxPx = maxPxFor(f.height, FRAME_DENSITY),
            touchMarginPx = margin,
        )
        val blockTop = f.height - height

        /* Below the bar, so the bar itself stays draggable... */
        assertTrue("blocker must start below the bar's last row (982)", blockTop > 982)
        /* ...with room under it for a finger that lands low... */
        assertTrue("at least 16dp of slack under the bar", blockTop - 982 >= margin)
        /* ...and still above the row of ways out of the app. The share button,
           "More videos" and the YouTube wordmark begin at y=1040. */
        assertTrue("blocker must cover the chrome starting at y=1040", blockTop <= 1040)
    }

    /* What the margin costs, in numbers: without it the blocker would start
       one pixel under the bar. */
    @Test fun `the real frame has 217 pixels under the bar`() {
        val f = realFrame()
        val (px, w, stripH) = bottomStrip(f)
        assertEquals(
            217,
            Chrome.blockHeight(px, w, stripH, 44, maxPxFor(f.height, FRAME_DENSITY), 0),
        )
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
       anything about a tablet, a 720p handset or a foldable — and the first
       version of this code was tuned to that one frame in a way that would
       have broken on all three: the capture and the sanity limit were
       fractions of the player's height, when what they are measuring is a
       fixed physical size.

       YouTube's chrome under the bar is roughly 80dp on any device: a bar, a
       gap, a row of 48dp buttons, a bottom inset. As a FRACTION that is 25% of
       a short landscape phone and 10% of a tablet — so a fraction-shaped limit
       is either too tight for one or useless for the other. These cases lay
       the same dp geometry out at real device resolutions and check the
       measurement survives all of them.
       ------------------------------------------------------------------ */

    /* A player frame with the chrome where the dp says it should be. */
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

    /* The bounds PlayerActivity computes, restated so the tests exercise the
       real policy rather than a number picked to pass. */
    private fun maxPxFor(height: Int, density: Float) =
        maxOf((200 * density).toInt(), (height * 0.30f).toInt())

    private fun stripFor(height: Int, density: Float) =
        minOf(height, maxOf(maxPxFor(height, density) + (40 * density).toInt(), (height * 0.35f).toInt()))

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

    @Test fun `the bar is found on every device shape`() {
        for (d in devices) {
            val stripH = stripFor(d.h, d.density)
            val frame = deviceFrame(d.w, d.h, d.density)
            /* The strip, taken off the bottom exactly as the Activity does. */
            val strip = IntArray(d.w * stripH)
            System.arraycopy(frame, (d.h - stripH) * d.w, strip, 0, strip.size)

            val bottom = Chrome.seekBarBottom(strip, d.w, stripH)
            assertTrue("${d.name}: no bar found", bottom != null)
            assertEquals(
                "${d.name}: bar in the wrong place",
                (80 * d.density).toInt(),
                stripH - 1 - bottom!!,
            )
        }
    }

    @Test fun `the blocked strip lands correctly on every device shape`() {
        for (d in devices) {
            fun dp(v: Number) = (v.toFloat() * d.density).toInt()
            val stripH = stripFor(d.h, d.density)
            val frame = deviceFrame(d.w, d.h, d.density)
            val strip = IntArray(d.w * stripH)
            System.arraycopy(frame, (d.h - stripH) * d.w, strip, 0, strip.size)

            val height = Chrome.blockHeight(
                strip, d.w, stripH,
                fallbackPx = dp(16),
                maxPx = maxPxFor(d.h, d.density),
                touchMarginPx = dp(16),
            )
            assertEquals("${d.name}: wrong blocked height", dp(80) - dp(16), height)

            /* And the thing that actually matters, in dp: the bar keeps its
               16dp of slack underneath, and the chrome above the bottom edge
               is covered. */
            val blockTop = d.h - height
            val barBottom = d.h - 1 - dp(80)
            assertTrue("${d.name}: blocker sits on the bar", blockTop > barBottom)
            assertTrue("${d.name}: too little slack under the bar", blockTop - barBottom >= dp(15))
        }
    }

    /* A display cutout pushes YouTube's controls inwards — 9% of the width on
       the measured frame. The left-anchored test has to survive that on a
       device with a wider one. */
    @Test fun `a wide display cutout does not hide the bar`() {
        val d = Device("cutout phone", 2340, 1080, 2.75f)
        val stripH = stripFor(d.h, d.density)
        /* 48dp of cutout on top of the usual inset: 15% of this width. */
        val frame = deviceFrame(d.w, d.h, d.density, cutoutDp = 48)
        val strip = IntArray(d.w * stripH)
        System.arraycopy(frame, (d.h - stripH) * d.w, strip, 0, strip.size)
        assertEquals((80 * d.density).toInt(), stripH - 1 - Chrome.seekBarBottom(strip, d.w, stripH)!!)
    }

    /* A video paused seconds in has a played portion a few pixels wide. On a
       540p screen that is genuinely tiny, and it still has to count. */
    @Test fun `a barely-started video is found on a small screen`() {
        val d = Device("qHD budget phone", 960, 540, 1.5f)
        val stripH = stripFor(d.h, d.density)
        val frame = deviceFrame(d.w, d.h, d.density, playedFraction = 0.01f)
        val strip = IntArray(d.w * stripH)
        System.arraycopy(frame, (d.h - stripH) * d.w, strip, 0, strip.size)
        assertTrue("a 9-pixel played portion should still register", Chrome.seekBarBottom(strip, d.w, stripH) != null)
    }
}
