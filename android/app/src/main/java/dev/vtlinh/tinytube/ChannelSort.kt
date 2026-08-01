package dev.vtlinh.tinytube

/* What order the approved channels are listed in.

   Three orders, and the third one is the reason this file exists.

   LAST_ADDED is what the list has always done and is still the default: a
   parent who just approved something looks for it at the top.

   A_Z is for a list long enough that "where is it" beats "what did I just do".

   MOST_WATCHED is the interesting one, because "most watched" is only a
   question with an answer if there is something to count. So it is a LADDER,
   not a single window: the last 7 days, and if nothing was watched in them the
   last 30, and if nothing then the last 365 — and if a device has no history at
   all, A-Z, because falling back to last-added would make an empty history look
   like a real answer that happened to agree with the default.

   The point of narrowing first is that recency is the useful part. A channel
   watched daily this week should lead even if another was watched more often a
   year ago; a list sorted over all time would freeze into whatever was popular
   when the phone was new.

   Android-free and tested. The counting happens in WatchStore, which has the
   database; everything about which window applies and how ties break is
   here. */
object ChannelSort {

    enum class Mode { LAST_ADDED, A_Z, MOST_WATCHED }

    /* The ladder, in days, narrowest first. Each rung is only used when every
       narrower one found nothing at all. */
    val WINDOWS_DAYS = listOf(7, 30, 365)

    fun modeOf(name: String?): Mode =
        Mode.entries.firstOrNull { it.name == name } ?: Mode.LAST_ADDED

    /* Cycling rather than a menu: one button, three states, and the toolbar
       says which. */
    fun next(mode: Mode): Mode =
        Mode.entries[(Mode.entries.indexOf(mode) + 1) % Mode.entries.size]

    /* Which rung of the ladder actually applies, as an index into
       WINDOWS_DAYS — or null for "nothing has been watched at all".
     *
     * `countsByWindow` is one map per window, in WINDOWS_DAYS order. A window
     * counts as having an answer if ANY channel has a non-zero count in it;
     * a map of zeroes is the same as an empty one and must not win, or a
     * device with a single stale row would sort by nothing forever. */
    fun windowIndex(countsByWindow: List<Map<String, Int>>): Int? =
        countsByWindow.indexOfFirst { counts -> counts.values.any { it > 0 } }
            .takeIf { it >= 0 }

    /* The list, in the chosen order.
     *
     * `channels` arrives newest-approved first, which is ChannelStore's order
     * and therefore LAST_ADDED with nothing to do.
     *
     * A-Z ignores case and falls back to the id, so a channel whose title
     * never resolved still lands somewhere predictable rather than at whichever
     * end empty strings sort to.
     *
     * MOST_WATCHED breaks ties A-Z rather than by count-then-arrival: two
     * channels watched three times each are not meaningfully ordered by which
     * was approved first, and alphabetical at least does not move between
     * refreshes. */
    fun sort(
        channels: List<Channel>,
        mode: Mode,
        countsByWindow: List<Map<String, Int>> = emptyList(),
    ): List<Channel> = when (mode) {
        Mode.LAST_ADDED -> channels
        Mode.A_Z -> channels.sortedWith(BY_NAME)
        Mode.MOST_WATCHED -> {
            val window = windowIndex(countsByWindow)
            if (window == null) {
                /* Nothing has ever been watched. A-Z rather than last-added,
                   so an absent answer does not masquerade as the default one
                   having agreed with it. */
                channels.sortedWith(BY_NAME)
            } else {
                val counts = countsByWindow[window]
                channels.sortedWith(
                    compareByDescending<Channel> { counts[it.id] ?: 0 }.then(BY_NAME),
                )
            }
        }
    }

    private val BY_NAME = compareBy<Channel> { it.title.trim().ifEmpty { it.id }.lowercase() }
}
