package dev.vtlinh.ytkids

/* Turning a channel's upload feed into videos the grid can show.

   The feed is Atom XML. Parsing it with regex rather than a real parser is a
   deliberate limit, not laziness: nothing here is trusted. Every id is put
   through VideoId.isValid before it can become a tile, so the worst a
   malformed or hostile feed can do is produce fewer videos than it should —
   never a bad one. A DOM parser would buy correctness we don't need and an
   XXE surface we very much don't want.

   Android-free and tested. */
object Feed {

    private val ENTRY = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
    private val VIDEO_ID = Regex("<yt:videoId>\\s*([^<\\s]+)\\s*</yt:videoId>")
    private val TITLE = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)

    /* Never throws and never returns a video whose id isn't well-formed. An
       empty result is the honest answer for a feed that is empty, truncated,
       an error page, or not XML at all. */
    fun parse(xml: String): List<Video> {
        val out = mutableListOf<Video>()
        val seen = mutableSetOf<String>()
        try {
            for (entry in ENTRY.findAll(xml)) {
                val body = entry.groupValues[1]
                val id = VIDEO_ID.find(body)?.groupValues?.get(1)?.trim() ?: continue
                if (!VideoId.isValid(id)) continue
                if (!seen.add(id)) continue
                val title = TITLE.find(body)?.groupValues?.get(1)?.let { unescape(it).trim() }
                out.add(Video(id = id, title = title?.ifEmpty { null } ?: id))
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    /* The five predefined XML entities, plus numeric references. Titles are
       full of ampersands and quotes, and a title rendered as "Ten &amp; Two"
       in a child's grid looks broken. */
    private fun unescape(s: String): String {
        var out = s
        out = Regex("&#x([0-9A-Fa-f]+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { cp -> codePoint(cp) } ?: m.value
        }
        out = Regex("&#([0-9]+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { cp -> codePoint(cp) } ?: m.value
        }
        /* &amp; last, so "&amp;lt;" becomes "&lt;" and not "<" */
        return out
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null
}
