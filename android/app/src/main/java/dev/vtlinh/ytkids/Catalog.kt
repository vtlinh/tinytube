package dev.vtlinh.ytkids

import org.json.JSONObject

/* The approved-video list, and the rules for what counts as one.

   Deliberately free of Android so it runs under a plain JVM test (CatalogTest).
   This is the file that decides which video ids the player is ever allowed to
   load, and a mistake here doesn't produce a crash or an empty screen — it puts
   a child in front of something nobody approved. So the parse is total: it
   never throws, and anything it can't fully validate is dropped rather than
   passed along in a half-checked state. */
object Catalog {

    /* YouTube video ids are exactly 11 characters of URL-safe base64. Anchored,
       because an unanchored match would accept "…/watch?v=BAD" as containing a
       valid id and hand the whole string to the player. */
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    fun isValidId(id: String): Boolean = VIDEO_ID.matches(id)

    /* Parse the catalog document served by the Worker.

       Returns an empty list for anything malformed. An empty catalog is a safe,
       displayable state ("no videos approved yet"); a partially-parsed one is
       not, so there is no in-between. */
    fun parse(json: String): List<Video> {
        val out = mutableListOf<Video>()
        val seen = mutableSetOf<String>()
        try {
            val arr = JSONObject(json).optJSONArray("videos") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "").trim()
                if (!isValidId(id)) continue
                /* the same id twice would render two identical tiles and make
                   the grid's stable ids ambiguous */
                if (!seen.add(id)) continue
                val title = o.optString("title", "").trim().ifEmpty { id }
                out.add(Video(id = id, title = title))
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }
}

data class Video(val id: String, val title: String) {
    /* i.ytimg.com serves thumbnails for any public video with no key and no
       cookie. hqdefault exists for every video; maxresdefault does not, and a
       missing one 404s into an empty tile. */
    val thumbnailUrl: String get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}
