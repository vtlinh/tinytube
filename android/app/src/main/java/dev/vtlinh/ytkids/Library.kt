package dev.vtlinh.ytkids

/* What the grid shows: recent uploads from every approved channel, as one
   list.

   Android-free and tested. */
object Library {

    /* Flatten the per-channel feeds into the grid's list, keeping each video
       once.

       Feeds are already deduplicated within themselves, but not against each
       other, and the same video legitimately appears in two channels' feeds
       after a collaboration or a re-upload. Two identical tiles in a row looks
       like a bug to a child and makes the grid's stable ids ambiguous.

       Order is preserved: channels come newest-approved first from
       ChannelStore, and each feed is newest-upload first, so the freshest
       thing from the most recently approved channel leads. */
    fun collate(uploads: List<Video>): List<Video> {
        val out = mutableListOf<Video>()
        val seen = mutableSetOf<String>()
        for (v in uploads) if (seen.add(v.id)) out.add(v)
        return out
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
