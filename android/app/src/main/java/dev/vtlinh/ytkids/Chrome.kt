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
       under half its red is not. */
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
       There is nothing below the bar but the player's own inset, which is what
       this is here to measure. */
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

       Returns the fallback whenever the answer would be untrustworthy rather
       than guessing: no bar found, a bar at the very bottom row (nothing to
       block), or a result so large that something other than a seek bar was
       matched. The fallback is the compiled-in constant, which is what the app
       used before this existed — being wrong here costs a strip of the wrong
       height, and the wrong direction is the one that eats the seek bar. */
    fun blockHeight(pixels: IntArray, width: Int, height: Int, fallbackPx: Int): Int {
        val bottom = seekBarBottom(pixels, width, height) ?: return fallbackPx
        val below = height - 1 - bottom
        /* A quarter of the captured strip is already far more than any inset
           YouTube has ever drawn; past that, the match was something else. */
        if (below <= 0 || below > height / 4) return fallbackPx
        return below
    }
}
