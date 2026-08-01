package dev.vtlinh.ytkids

import org.json.JSONArray
import org.json.JSONObject

/* Reading what the Worker says a channel has posted.

   The phone used to fetch two megabytes of YouTube's web app per channel and
   parse it. Now the Worker does that and answers with the list, and the phone
   sends along the ids it already has so the reply carries details only for
   what is new:

     {"channel":"UC…","videos":[
        "dQw4w9WgXcQ",                                   <- already had it
        {"id":"…","title":"…","published":123,"thumb":"…"} <- new
     ]}

   A phone with nothing new to learn gets about a kilobyte back. A fresh
   install gets fifteen.

   The order of `videos` is the answer to "what does this channel have now",
   including removals — so it replaces the stored list rather than merging into
   it. A bare id whose details we do NOT have is dropped rather than shown as a
   blank tile; it comes back in full on the next refresh, because the phone
   will no longer claim to know it.

   Android-free and tested, and none of it is trusted. The Worker validated
   these ids too, and this validates them again: an id goes into a URL and into
   a JS string literal on the way to the player, and "our own server said so"
   is not a reason to skip the check that stops the wrong video playing. */
object Uploads {

    /* What one reply may carry. The Worker sends a hundred; this is a bound on
       a hostile or broken one, not a target. */
    private const val MAX_VIDEOS = 500

    /* Parse a reply, given what the caller already had.
     *
     * `known` is looked up by id to fill in the bare entries. Never throws: a
     * truncated body, an error page, or a shape from a future Worker all come
     * back empty, and the caller keeps what it had. */
    fun parse(body: String, known: Map<String, Video>): List<Video> {
        val out = mutableListOf<Video>()
        val seen = mutableSetOf<String>()
        try {
            val videos = JSONObject(body).optJSONArray("videos") ?: return emptyList()
            for (i in 0 until minOf(videos.length(), MAX_VIDEOS)) {
                val video = when (val entry = videos.opt(i)) {
                    is String -> known[entry]?.takeIf { VideoId.isValid(entry) }
                    is JSONObject -> full(entry)
                    else -> null
                } ?: continue
                if (seen.add(video.id)) out.add(video)
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    private fun full(entry: JSONObject): Video? {
        val id = entry.optString("id")
        if (!VideoId.isValid(id)) return null
        val title = entry.optString("title").trim().ifEmpty { id }
        /* 0 is not a real upload time and JSONObject cannot tell a missing
           number from a zero, so it reads as "no date" — which sorts last
           rather than to 1970. */
        val published = entry.optLong("published", 0L).takeIf { it > 0 }
        return Video(
            id = id,
            title = title,
            publishedAt = published,
            thumbUrl = thumb(entry.optString("thumb"), id),
        )
    }

    /* The thumbnail, but only from the host that serves them.
     *
     * Whatever is stored here is later fetched and drawn, so it does not get
     * to be an arbitrary URL somebody sent us. A reply that names anything
     * else falls back to the derived one, which always works. */
    fun thumb(url: String?, id: String): String? {
        if (url.isNullOrEmpty()) return null
        val host = Player.hostOf(url) ?: return null
        return if (host == "i.ytimg.com" || host == "img.youtube.com") url else null
    }

    /* What the phone tells the Worker it already has. Ids only, and only ones
       that are well-formed — a malformed id in the request would just come
       back in full, but there is no reason to send one. */
    fun request(channelId: String, known: Collection<String>): String =
        JSONObject()
            .put("channel", channelId)
            .put("known", JSONArray(known.filter { VideoId.isValid(it) }))
            .toString()
}
