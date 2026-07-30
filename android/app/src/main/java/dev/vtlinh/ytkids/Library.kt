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
}
