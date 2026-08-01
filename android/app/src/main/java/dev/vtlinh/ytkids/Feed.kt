package dev.vtlinh.ytkids

/* Turning a channel's uploads into videos the grid can show.

   Two sources, because they answer different questions.

   The Atom feed is the one YouTube publishes for the purpose. It needs no key
   and no quota, it is two kilobytes, and it carries roughly the latest 15
   uploads — a hard cap with no parameter to raise it. That was the whole grid
   until the depth mattered.

   The uploads playlist PAGE carries 100. Every channel has one, its id is the
   channel's with "UU" in place of "UC", and its first payload lists a hundred
   videos before any continuation is needed. It is a rendering of YouTube's
   own web app rather than a published feed, which is the trade: no key, no
   quota, a hundred videos, and a shape that can change under us. So the page
   is tried first and the feed is what happens when it yields nothing —
   fifteen videos is a worse grid, an empty one is a broken app.

   Both are parsed with regex rather than a real parser, and that is a
   deliberate limit rather than laziness: nothing here is trusted. Every id is
   put through VideoId.isValid before it can become a tile, so the worst a
   malformed or hostile source can do is produce FEWER videos than it should —
   never a bad one. A DOM parser would buy correctness we don't need and an
   XXE surface we very much don't want, and a JSON parser pointed at two
   megabytes of someone else's app state would buy about as much.

   Android-free and tested. */
object Feed {

    /* What the grid holds per channel. The playlist page's first payload is
       exactly this many, so it is also "everything one request can see". */
    const val MAX_UPLOADS = 100

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

    /* ---- the uploads playlist page ---- */

    /* Where one video's entry begins in the page's embedded state. Two of
       them, because YouTube renamed the thing mid-2025 and old shapes come
       back on old app versions and in some locales. Neither name is ours to
       rely on, which is what the Atom fallback is for. */
    private val ENTRY_MARKERS = listOf("{\"lockupViewModel\":", "{\"playlistVideoRenderer\":")

    /* The id, in the two places an entry carries one. contentId is the
       lockup's own subject; videoId appears in the endpoint a tap would
       follow. Anchored to eleven characters, so a longer string with a
       valid-looking prefix does not match — same rule as VideoId, checked
       again there anyway. */
    private val PAGE_ID = Regex("\"(?:contentId|videoId)\":\"([A-Za-z0-9_-]{11})\"")

    /* And the title, in the shapes that have carried it: the current metadata
       view model, and the older runs/simpleText pair. */
    private val PAGE_TITLES = listOf(
        Regex("\"lockupMetadataViewModel\":\\{\"title\":\\{\"content\":\"((?:[^\"\\\\]|\\\\.)*)\""),
        Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\""),
        Regex("\"title\":\\{[^{}]*\"simpleText\":\"((?:[^\"\\\\]|\\\\.)*)\""),
    )

    /* How far past an entry's start to look, when the next entry is further
       away than that. Each entry ends where the following one begins and the
       search is bounded there first — without that bound, an entry missing its
       own title would take the NEXT entry's, which is the one failure mode
       here that produces a WRONG answer rather than a missing one. The window
       is what bounds the last entry on the page, which has nothing after
       it. */
    private const val ENTRY_WINDOW = 20000

    /* The uploads playlist page, as videos.
     *
     * Never throws. An empty result is the honest answer for a page that is an
     * error, a consent interstitial, a redirect to the mobile site, or a shape
     * this no longer recognises — and it is what makes the caller fall back to
     * the Atom feed rather than show an empty channel. */
    fun parseUploadsPage(html: String, limit: Int = MAX_UPLOADS): List<Video> {
        val out = mutableListOf<Video>()
        val seen = mutableSetOf<String>()
        try {
            val marker = ENTRY_MARKERS.firstOrNull { html.contains(it) } ?: return emptyList()
            var from = html.indexOf(marker)
            while (from >= 0 && out.size < limit) {
                val next = html.indexOf(marker, from + marker.length)
                val end = minOf(
                    if (next >= 0) next else html.length,
                    from + ENTRY_WINDOW,
                    html.length,
                )
                val chunk = html.substring(from + marker.length, end)
                val id = PAGE_ID.find(chunk)?.groupValues?.get(1)
                if (id != null && VideoId.isValid(id) && seen.add(id)) {
                    val title = PAGE_TITLES.firstNotNullOfOrNull { it.find(chunk) }
                        ?.groupValues?.get(1)
                        ?.let { jsonUnescape(it).trim() }
                    out.add(Video(id = id, title = title?.ifEmpty { null } ?: id))
                }
                from = next
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    /* JSON's string escapes, which is what a title inside the page's state is
       wearing. Distinct from unescape above: that one speaks XML entities, and
       a title is put through exactly one of them depending on where it came
       from. An unrecognised escape is left alone rather than dropped — a title
       is cosmetic and a mangled one beats a missing one. */
    fun jsonUnescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { out.append(c); i++; continue }
            when (val e = s[i + 1]) {
                '"', '\\', '/' -> { out.append(e); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append(''); i += 2 }
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val cp = if (hex.length == 4) hex.toIntOrNull(16) else null
                    if (cp == null) { out.append(c); i++ } else { out.append(cp.toChar()); i += 6 }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /* ---- the on-disk cache ---- */

    /* What the cache holds, which is the PARSED list rather than the bytes it
       came from. The Atom feed was small enough to keep whole; a playlist page
       is two megabytes per channel, and re-running these regexes over it on
       every cold start to recover a hundred short strings is work nobody
       needs. One line per video, id and title separated by a tab.
     *
     * Tabs and newlines are stripped from the title rather than escaped: they
     * are the only two characters this format cannot carry, neither belongs in
     * a video title, and a format with no escapes has no escaping bugs. */
    fun encode(videos: List<Video>): String =
        videos.joinToString("\n") { v ->
            v.id + "\t" + v.title.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        }

    /* And back. Every id is revalidated on the way in: the cache is a file on
       a device, and a file can be edited. */
    fun decode(text: String): List<Video> {
        val out = mutableListOf<Video>()
        val seen = mutableSetOf<String>()
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val id = line.substring(0, tab)
            if (!VideoId.isValid(id) || !seen.add(id)) continue
            val title = line.substring(tab + 1).trim()
            out.add(Video(id = id, title = title.ifEmpty { id }))
        }
        return out
    }
}
