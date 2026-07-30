package dev.vtlinh.ytkids

/* What the grid shows: the reviewed catalog plus recent uploads from approved
   channels, as one list.

   Android-free and tested. The ordering rule is the interesting part — see
   merge. */
object Library {

    /* Repo-catalog videos first, then channel uploads, with any id appearing
       in both kept only once — as its catalog entry.
     *
     * Catalog entries come first because an adult chose each one specifically;
     * channel uploads arrive on their own. And when the same video is in both,
     * the catalog's title is the one a parent wrote, so it wins over whatever
     * the uploader called it.
     */
    fun merge(catalog: List<Video>, channelUploads: List<Video>): List<Video> {
        val out = mutableListOf<Video>()
        val seen = mutableSetOf<String>()
        for (v in catalog) if (seen.add(v.id)) out.add(v)
        for (v in channelUploads) if (seen.add(v.id)) out.add(v)
        return out
    }
}
