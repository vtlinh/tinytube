package dev.vtlinh.tinytube

/* What the grid shows: recent uploads from every approved channel, as one
   list, newest first.

   Android-free and tested. */
object Library {

    /* Fill in an upload time for every video in one channel's list.
     *
     * The two sources know different halves. The playlist page gives a hundred
     * videos in exact upload order and no dates whatsoever; the Atom feed gives
     * the newest fifteen with real timestamps. Neither alone can order the grid
     * across channels — a date is what makes one channel's third video
     * comparable to another's tenth.
     *
     * So: a video the feed dated keeps that date. A video it did not is placed
     * ONE SECOND BEFORE whatever preceded it in the page's order. That is not a
     * guess at when it was posted and is not presented as one; it is a sort key
     * that preserves two things which are actually known — that the page's
     * order is upload order, and that everything below the feed's oldest entry
     * is older than it.
     *
     * The result is a grid whose top is dated exactly, and whose tail keeps
     * each channel's videos in their real order without claiming to interleave
     * them precisely. The alternative was a hundred requests per channel.
     *
     * `fallback` starts the walk when the page's very first entries are not in
     * the feed at all — a channel that posted sixteen videos between two
     * refreshes. Passing the current time makes those sort as newest, which is
     * what they are. */
    fun datePositions(ordered: List<Video>, dated: Map<String, Long>, fallback: Long): List<Video> {
        var previous = fallback
        return ordered.map { v ->
            val known = dated[v.id] ?: v.publishedAt
            val at = if (known != null && known <= previous) known else previous - 1
            previous = at
            if (v.publishedAt == at) v else v.copy(publishedAt = at)
        }
    }

    /* Newest first, across everything.
     *
     * Stable, so videos sharing a timestamp — which happens, channels schedule
     * batches — keep the order they arrived in rather than shuffling between
     * refreshes. A video with no date at all sorts last: it is either a cache
     * written by an older build or a source that told us nothing, and the top
     * of the grid should belong to things we can actually date. */
    fun newestFirst(videos: List<Video>): List<Video> =
        videos.sortedWith(compareByDescending { it.publishedAt ?: Long.MIN_VALUE })

    /* Flatten the per-channel feeds into the grid's list, keeping each video
       once.

       Feeds are already deduplicated within themselves, but not against each
       other, and the same video legitimately appears in two channels' feeds
       after a collaboration or a re-upload. Two identical tiles in a row looks
       like a bug to a child and makes the grid's stable ids ambiguous.

       Order is by upload time, newest first, across every channel together —
       not one channel's feed after another's. Which channel a video came from
       is not something a child is choosing between on this screen; when they
       are, the Channels tab narrows to one and the same rule applies within
       it. Ties keep the order they arrived in, which is ChannelStore's. */
    fun collate(uploads: List<Video>): List<Video> {
        val out = mutableListOf<Video>()
        val seen = mutableSetOf<String>()
        for (v in uploads) if (seen.add(v.id)) out.add(v)
        return newestFirst(out)
    }

    /* Per-channel feeds back into one list, in the map's own order.
     *
     * The grid wants every approved channel's uploads together; the Channels
     * tab wants one channel's on its own. Keeping the feeds separate and
     * flattening here means both come from the same fetch, rather than the
     * grid getting a flat list and the tab going back for the parts. */
    fun flatten(byChannel: Map<String, List<Video>>): List<Video> {
        val out = mutableListOf<Video>()
        for ((_, videos) in byChannel) out += videos
        return out
    }

    /* One channel's uploads, collated. An unknown id gives an empty list
       rather than everything: a channel removed while its tab was open should
       show nothing, not the whole library. */
    fun forChannel(byChannel: Map<String, List<Video>>, channelId: String): List<Video> =
        collate(byChannel[channelId].orEmpty())
}
