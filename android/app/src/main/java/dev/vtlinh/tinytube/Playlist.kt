package dev.vtlinh.tinytube

/* What plays after the video that just ended.

   The list is whatever the child was looking at when they tapped. That is the
   whole of the scoping rule and it needs no special case: on the Videos tab it
   is every approved channel's uploads, and on a channel it is that channel's,
   because narrowing the grid is what the Channels tab does. A video started
   from inside one channel therefore cannot lead out of it, and one started
   from the full grid is not confined to where it began.

   Android-free and tested. Getting this wrong is not a crash — it is a child
   handed a video from somewhere they were not. */
object Playlist {

    /* How the next one is chosen. The parent picks; see SettingsActivity. */
    enum class Mode { IN_ORDER, RANDOM }

    fun modeOf(name: String?): Mode =
        Mode.entries.firstOrNull { it.name == name } ?: Mode.IN_ORDER

    /* The index to play next, or null for "stop and go back to the grid".
     *
     * IN_ORDER walks down the list and STOPS at the end rather than wrapping.
     * A grid that plays forever with no edge is a different product from one
     * that plays what a parent approved and then hands the screen back, and
     * the end of the list is the only natural place this ever stops on its
     * own.
     *
     * RANDOM never repeats the video that just played, which is the one
     * outcome that reads as broken rather than as random. With a list of one
     * that means stopping, and that is right: the single video is over.
     *
     * `roll` returns a value in 0 until its argument. Passed in rather than
     * called here so the choice is testable — a random next video is exactly
     * the kind of thing that is only wrong once in fifty runs. */
    fun next(count: Int, current: Int, mode: Mode, roll: (Int) -> Int): Int? {
        if (count <= 0 || current < 0 || current >= count) return null
        return when (mode) {
            Mode.IN_ORDER -> (current + 1).takeIf { it < count }
            Mode.RANDOM -> {
                if (count < 2) return null
                /* Roll among the OTHER videos and skip past the current one,
                   rather than rolling over all of them and retrying. Retrying
                   has no bound; this has none of the distribution skew that
                   makes people reach for retrying either. */
                val n = roll(count - 1).coerceIn(0, count - 2)
                if (n >= current) n + 1 else n
            }
        }
    }
}
