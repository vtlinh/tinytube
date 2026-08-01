package dev.vtlinh.tinytube

import android.content.Context

/* Remembers what the player measured off its own paused frame.

   Without this the measurement lived for the life of the process: every cold
   start put the blocker back to the compiled-in fallback and waited for the
   child to pause a video before it was right again — which on a screen with no
   pause button means the first video of every session was played with the
   wrong strip blocked.

   Stored against the display it was measured on. A foldable opening, a change
   to Android's display-size setting, or the same install restored onto another
   phone all change the geometry, and a height measured on the old one would be
   wrong on the new one — so the key is the resolution, and a mismatch simply
   measures again.

   And against a VERSION, which is the more important half. The build that
   introduced this store also, separately, treated a failed capture as an
   answer — so it wrote the fallback here as though it had been measured. A
   preference file survives an app update, so every build afterwards read that
   back, concluded the work was done, and never looked again. Fixing the
   latching bug did nothing on any device that had already run the broken
   build: the wrong answer was on disk.

   So bump VERSION whenever the measurement changes, and old entries are
   ignored rather than trusted. It costs one re-measure per device. */
object BlockHeightStore {

    private const val PREFS = "player"
    private const val KEY_PX = "block_px"
    private const val KEY_DISPLAY = "block_display"
    private const val KEY_VERSION = "block_version"

    /* 1 was never written — the original store had no version key, so anything
       without one came from the build that could persist a failure. 2 was
       written by the builds that measured the RED, which read the scrubber
       knob as the bar's thickness and left the strip a fraction of its proper
       size; 3 measures the track instead and leaves a smaller margin, so
       nothing written before it describes what this build would measure. */
    private const val VERSION = 3

    /* What the display is, as a string to compare against later. */
    fun displayKey(context: Context): String {
        val m = context.resources.displayMetrics
        return "${m.widthPixels}x${m.heightPixels}@${m.densityDpi}"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* The remembered height, or null if there is nothing here worth trusting
       for this display and this version of the measurement. */
    fun get(context: Context): Int? {
        val p = prefs(context)
        if (p.getInt(KEY_VERSION, 0) != VERSION) return null
        if (p.getString(KEY_DISPLAY, null) != displayKey(context)) return null
        val px = p.getInt(KEY_PX, -1)
        return if (px >= 0) px else null
    }

    fun put(context: Context, px: Int) {
        prefs(context).edit()
            .putInt(KEY_PX, px)
            .putString(KEY_DISPLAY, displayKey(context))
            .putInt(KEY_VERSION, VERSION)
            .apply()
    }

    /* Throw the answer away and measure again on the next video. */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_PX)
            .remove(KEY_DISPLAY)
            .remove(KEY_VERSION)
            .apply()
    }
}
