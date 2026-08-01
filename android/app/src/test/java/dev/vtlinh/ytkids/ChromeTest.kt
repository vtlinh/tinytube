package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

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
        assertEquals(6, Chrome.blockHeight(p, w, h, 99))
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
        assertEquals(7, Chrome.blockHeight(p, w, h, 99))
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

    @Test fun `falls back when the match is implausibly high up`() {
        val w = 800; val h = 100
        val p = strip(w, h)
        /* More than a quarter of the strip below it — not an inset. */
        p.row(w, 40, 12, 300, RED)
        assertEquals(42, Chrome.blockHeight(p, w, h, 42))
    }

    @Test fun `refuses a malformed or empty strip rather than reading past it`() {
        assertNull(Chrome.seekBarBottom(IntArray(0), 0, 0))
        assertNull(Chrome.seekBarBottom(IntArray(10), 800, 100))
        assertNull(Chrome.seekBarBottom(IntArray(100), -1, 10))
        assertEquals(42, Chrome.blockHeight(IntArray(10), 800, 100, 42))
    }
}
