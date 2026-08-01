package dev.vtlinh.ytkids

/* Working out where YouTube's seek bar ends, by looking at the pixels.

   The player is a cross-origin iframe: its DOM cannot be read, so there is no
   way to ask it where it drew anything. The bottom blocker's height was
   therefore a constant — YouTube's mobile-embed inset, guessed once and wrong
   on any device or player version that differs. This measures it instead.

   Pure, and no Android in it, so ChromeTest can hold it to its promises under
   a plain JVM: it takes a rectangle of ARGB pixels and returns a height. The
   Activity does the capturing, and never keeps what it captured — see
   PlayerActivity.measureBlockHeight. */
object Chrome {

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

    /* The lowest row of the strip that looks like the progress bar, or null.

       Two things make a row qualify, and both are needed. It must contain a
       run of red — a single stray red pixel is noise, and a long one is the
       bar. And that run must START near the left edge, because the played
       portion always does: it grows rightwards from the beginning of the bar.
       Red in the middle of the frame is a red shirt.

       Scanned from the bottom up so the FIRST match is the bar's bottom edge.
       Below the bar is the row of chrome this is here to measure — share,
       "More videos", the YouTube wordmark, every one of them a way out.

       On the real frame the run starts 9% of the way across, because YouTube
       insets its controls past the display cutout. An eighth leaves room for
       a wider inset without reaching the middle of the picture, where a red
       jumper lives. */
    fun seekBarBottom(pixels: IntArray, width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val startsWithin = maxOf(width / 8, 1)
        val minRun = maxOf(width / 200, 4)

        for (y in height - 1 downTo 0) {
            var runStart = -1
            var run = 0
            for (x in 0 until width) {
                if (isProgressRed(pixels[y * width + x])) {
                    if (run == 0) runStart = x
                    run++
                    if (run >= minRun && runStart <= startsWithin) return y
                } else {
                    run = 0
                }
            }
        }
        return null
    }

    /* How tall the bottom blocker should be, given a strip captured from the
       bottom of the player.

       Not simply "everything under the bar". touchMarginPx of that is left
       reachable, because the drawn bar is thin — nine pixels on the frame this
       was measured from, under 4dp — and a thumb aiming for it lands around
       it, not on it. Blocking flush to the line would make the one control the
       reveal exists to reach the one control nobody can hit. Everything above
       the line is already reachable, so the margin only has to cover fingers
       that land low.

       maxPx is the caller's sanity limit, in the same pixels: a match further
       up than that is something in the picture rather than the bar. It comes
       from the caller because only the caller knows the player's full height —
       this sees a strip of it.

       Returns fallbackPx rather than guessing whenever the answer would be
       untrustworthy: no bar, a bar on the last row, or a match past maxPx. The
       fallback is the compiled-in constant the app used before this existed. */
    fun blockHeight(
        pixels: IntArray,
        width: Int,
        height: Int,
        fallbackPx: Int,
        maxPx: Int,
        touchMarginPx: Int,
    ): Int {
        val bottom = seekBarBottom(pixels, width, height) ?: return fallbackPx
        val below = height - 1 - bottom
        if (below <= 0 || below > maxPx) return fallbackPx
        /* A bar close enough to the bottom that the margin swallows the lot
           means there is genuinely nothing down there to block. Zero is the
           measurement, not a failure. */
        return (below - touchMarginPx).coerceAtLeast(0)
    }
}
