package dev.vtlinh.ytkids

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
   measures again. */
object BlockHeightStore {

    private const val PREFS = "player"
    private const val KEY_PX = "block_px"
    private const val KEY_DISPLAY = "block_display"

    /* What the display is, as a string to compare against later. */
    fun displayKey(context: Context): String {
        val m = context.resources.displayMetrics
        return "${m.widthPixels}x${m.heightPixels}@${m.densityDpi}"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* The remembered height, or null if there is none for this display. */
    fun get(context: Context): Int? {
        val p = prefs(context)
        if (p.getString(KEY_DISPLAY, null) != displayKey(context)) return null
        val px = p.getInt(KEY_PX, -1)
        return if (px >= 0) px else null
    }

    fun put(context: Context, px: Int) {
        prefs(context).edit()
            .putInt(KEY_PX, px)
            .putString(KEY_DISPLAY, displayKey(context))
            .apply()
    }
}
