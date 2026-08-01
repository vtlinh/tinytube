package dev.vtlinh.ytkids

/* What counts as a video id.

   Curation is channel-level: a parent approves channels in parent mode, and
   the grid is built from those channels' upload feeds. Nothing hand-lists
   individual videos any more. But every id arriving from a feed still has to
   be checked before it can become a tile, and this is where that happens.

   Deliberately free of Android so it runs under a plain JVM test. A mistake
   here doesn't produce a crash or an empty screen — it puts a child in front
   of something nobody approved. */
object VideoId {

    /* YouTube video ids are exactly 11 characters of URL-safe base64. Anchored,
       because an unanchored match would accept "…/watch?v=BAD" as containing a
       valid id and hand the whole string to the player. */
    private val PATTERN = Regex("^[A-Za-z0-9_-]{11}$")

    fun isValid(id: String): Boolean = PATTERN.matches(id)
}

/* One tile.
 *
 * publishedAt is epoch seconds, and is what the grid sorts on — newest first,
 * across every approved channel rather than one channel after another. It is
 * nullable because the two sources know different things: the playlist page
 * gives a hundred videos in upload order and no dates at all, the Atom feed
 * gives fifteen with exact ones. Library.datePositions is what reconciles
 * those, and after it has run every video has a key. A null one sorts last,
 * which is the right place for a video nothing can date. */
data class Video(
    val id: String,
    val title: String,
    val publishedAt: Long? = null,
    /* What the Worker said the poster is, when it said. Stored rather than
       always derived because it is what came back with the video and the
       database is meant to hold the tile, not half of it — but it is checked
       against the hosts below before it is kept, because whatever is here is
       later fetched and drawn. */
    val thumbUrl: String? = null,
) {
    /* i.ytimg.com serves thumbnails for any public video with no key and no
       cookie. hqdefault exists for every video; maxresdefault does not, and a
       missing one 404s into an empty tile. */
    val thumbnailUrl: String get() = thumbUrl ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}
