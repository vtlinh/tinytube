package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTest {

    private fun feed(vararg entries: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
          <title>Some Channel</title>
          ${entries.joinToString("\n")}
        </feed>
    """.trimIndent()

    private fun entry(id: String, title: String, published: String? = null) = """
        <entry>
          <id>yt:video:$id</id>
          <yt:videoId>$id</yt:videoId>
          <yt:channelId>UCaaaaaaaaaaaaaaaaaaaaaa</yt:channelId>
          <title>$title</title>
          ${published?.let { "<published>$it</published>" } ?: ""}
        </entry>
    """.trimIndent()

    @Test fun `reads videos in feed order`() {
        val v = Feed.parse(feed(entry("aaaaaaaaaaa", "First"), entry("bbbbbbbbbbb", "Second")))
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), v.map { it.id })
        assertEquals(listOf("First", "Second"), v.map { it.title })
    }

    /* The feed is not trusted. Anything whose id isn't well-formed is dropped
       rather than passed to the player, exactly as with the repo catalog. */
    @Test fun `drops entries whose id is not a valid video id`() {
        val v = Feed.parse(
            feed(
                entry("aaaaaaaaaaa", "Good"),
                entry("short", "Bad"),
                entry("../../etcpasswd", "Bad"),
                entry("aaaaaaaaaa/", "Bad"),
                entry("';alert(1)//", "Bad"),
                entry("bbbbbbbbbbb", "Also good"),
            ),
        )
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), v.map { it.id })
    }

    @Test fun `drops a repeated id`() {
        val v = Feed.parse(feed(entry("aaaaaaaaaaa", "One"), entry("aaaaaaaaaaa", "Two")))
        assertEquals(1, v.size)
        assertEquals("One", v[0].title)
    }

    @Test fun `unescapes xml entities in titles`() {
        assertEquals("Ten & Two", Feed.parse(feed(entry("aaaaaaaaaaa", "Ten &amp; Two"))).single().title)
        assertEquals("\"Quoted\"", Feed.parse(feed(entry("aaaaaaaaaaa", "&quot;Quoted&quot;"))).single().title)
        assertEquals("a<b>c", Feed.parse(feed(entry("aaaaaaaaaaa", "a&lt;b&gt;c"))).single().title)
        assertEquals("café", Feed.parse(feed(entry("aaaaaaaaaaa", "caf&#233;"))).single().title)
        assertEquals("café", Feed.parse(feed(entry("aaaaaaaaaaa", "caf&#xE9;"))).single().title)
    }

    /* &amp; has to be resolved last or "&amp;lt;" turns into "<" — an escaped
       ampersand becoming markup. */
    @Test fun `does not double-unescape`() {
        assertEquals("&lt;", Feed.parse(feed(entry("aaaaaaaaaaa", "&amp;lt;"))).single().title)
    }

    @Test fun `falls back to the id when there is no usable title`() {
        val noTitle = "<entry><yt:videoId>aaaaaaaaaaa</yt:videoId></entry>"
        assertEquals("aaaaaaaaaaa", Feed.parse(feed(noTitle)).single().title)
        assertEquals("aaaaaaaaaaa", Feed.parse(feed(entry("aaaaaaaaaaa", "   "))).single().title)
    }

    /* All of these are reachable in production: a captive portal, an error
       page, a truncated response, a channel with no uploads. */
    @Test fun `returns empty rather than throwing on junk`() {
        for (junk in listOf(
            "", "   ", "not xml", "<html><body>portal</body></html>",
            "<feed>", "<feed></feed>", "{\"json\":true}",
            "<feed><entry></entry></feed>",
        )) {
            assertTrue("should have been empty for '$junk'", Feed.parse(junk).isEmpty())
        }
    }

    /* ---- upload times ---- */

    /* The feed is the only source that dates anything, and the dates are what
       the grid sorts on. The format is RFC 3339 with an offset, always. */
    @Test fun `reads the published time`() {
        val v = Feed.parse(feed(entry("aaaaaaaaaaa", "One", "2026-07-29T15:58:06+00:00")))
        assertEquals(1785340686L, v.single().publishedAt)
    }

    @Test fun `reads a published time in another offset`() {
        val utc = Feed.parse(feed(entry("aaaaaaaaaaa", "One", "2026-07-29T15:58:06+00:00")))
            .single().publishedAt
        val plusTwo = Feed.parse(feed(entry("aaaaaaaaaaa", "One", "2026-07-29T17:58:06+02:00")))
            .single().publishedAt
        assertEquals("the same instant either way", utc, plusTwo)
    }

    /* An undated entry is still a video. It sorts last rather than vanishing —
       a missing timestamp is not a reason to hide something a parent
       approved. */
    @Test fun `an entry with no published time still parses`() {
        val v = Feed.parse(feed(entry("aaaaaaaaaaa", "One")))
        assertEquals("aaaaaaaaaaa", v.single().id)
        assertEquals(null, v.single().publishedAt)
    }

    @Test fun `an unparseable published time is null rather than a crash`() {
        for (bad in listOf("", "yesterday", "2026-13-45T99:99:99Z", "1785340686")) {
            assertEquals(null, Feed.epochSeconds(bad))
        }
        assertEquals(1785340686L, Feed.epochSeconds("2026-07-29T15:58:06Z"))
    }

    /* ---- the uploads playlist page ---- */

    /* The current shape, as YouTube actually serves it — the fields are in the
       order and nesting the real page uses, and the fixture below is three
       entries lifted verbatim from one. */
    private fun lockup(id: String, title: String) = """
        {"lockupViewModel":{"contentImage":{"thumbnailViewModel":{"image":{"sources":[
        {"url":"https://i.ytimg.com/vi/$id/hqdefault.jpg","width":168,"height":94}]}}},
        "contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
        "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":
        {"videoId":"$id","playlistId":"UUaaaaaaaaaaaaaaaaaaaaaa"}}}}},
        "metadata":{"lockupMetadataViewModel":{"title":{"content":"$title"}}}}}
    """.trimIndent()

    /* And the shape it served before mid-2025, which still turns up. */
    private fun renderer(id: String, title: String) = """
        {"playlistVideoRenderer":{"videoId":"$id",
        "title":{"runs":[{"text":"$title"}],"accessibility":{}},
        "index":{"simpleText":"1"}}}
    """.trimIndent()

    private fun page(vararg entries: String) =
        "<!doctype html><html><body><script>var ytInitialData = {\"contents\":[" +
            entries.joinToString(",") + "]};</script></body></html>"

    @Test fun `reads videos from the current page shape, in page order`() {
        val v = Feed.parseUploadsPage(
            page(lockup("aaaaaaaaaaa", "First"), lockup("bbbbbbbbbbb", "Second")),
        )
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), v.map { it.id })
        assertEquals(listOf("First", "Second"), v.map { it.title })
    }

    @Test fun `reads videos from the older page shape`() {
        val v = Feed.parseUploadsPage(
            page(renderer("aaaaaaaaaaa", "First"), renderer("bbbbbbbbbbb", "Second")),
        )
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), v.map { it.id })
        assertEquals(listOf("First", "Second"), v.map { it.title })
    }

    /* Same rule as the Atom feed and for the same reason: the page is not
       trusted, and an id goes into a URL and a JS string literal. */
    @Test fun `drops page entries whose id is not a valid video id`() {
        val v = Feed.parseUploadsPage(
            page(
                lockup("aaaaaaaaaaa", "Good"),
                lockup("short", "Bad"),
                lockup("aaaaaaaaaa/", "Bad"),
                lockup("bbbbbbbbbbb", "Good"),
            ),
        )
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), v.map { it.id })
    }

    @Test fun `drops a repeated id from the page`() {
        val v = Feed.parseUploadsPage(page(lockup("aaaaaaaaaaa", "One"), lockup("aaaaaaaaaaa", "Two")))
        assertEquals(1, v.size)
        assertEquals("One", v[0].title)
    }

    @Test fun `stops at the limit`() {
        val many = (0 until 150).map { lockup(idAt(it), "Video $it") }
        assertEquals(Feed.MAX_UPLOADS, Feed.parseUploadsPage(page(*many.toTypedArray())).size)
        assertEquals(7, Feed.parseUploadsPage(page(*many.toTypedArray()), limit = 7).size)
    }

    /* 100 is the ask and 100 is what one request can see. If this ever needs
       to be a different number it is one constant, not a rewrite. */
    @Test fun `the limit is a hundred`() {
        assertEquals(100, Feed.MAX_UPLOADS)
    }

    /* The entry a title is taken from must be the entry the id came from. An
       entry with no title of its own has to fall back to its id rather than
       reach forward into the next entry — which is the only way this parser
       can produce a WRONG tile rather than a missing one. */
    @Test fun `does not borrow the next entry's title`() {
        val untitled = """{"lockupViewModel":{"contentId":"aaaaaaaaaaa"}}"""
        val v = Feed.parseUploadsPage(page(untitled, lockup("bbbbbbbbbbb", "Not mine")))
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), v.map { it.id })
        assertEquals(listOf("aaaaaaaaaaa", "Not mine"), v.map { it.title })
    }

    @Test fun `unescapes json escapes in page titles`() {
        assertEquals("Ten & Two", titleOf(lockup("aaaaaaaaaaa", "Ten \\u0026 Two")))
        assertEquals("\"Quoted\"", titleOf(lockup("aaaaaaaaaaa", "\\\"Quoted\\\"")))
        assertEquals("a/b", titleOf(lockup("aaaaaaaaaaa", "a\\/b")))
        assertEquals("café", titleOf(lockup("aaaaaaaaaaa", "caf\\u00e9")))
        assertEquals("back\\slash", titleOf(lockup("aaaaaaaaaaa", "back\\\\slash")))
    }

    /* A title is cosmetic. An escape this doesn't know is left as it stands
       rather than dropping the video. */
    @Test fun `leaves an unknown escape alone`() {
        assertEquals("a\\qb", Feed.jsonUnescape("a\\qb"))
        assertEquals("a\\u12", Feed.jsonUnescape("a\\u12"))
        assertEquals("trailing\\", Feed.jsonUnescape("trailing\\"))
    }

    /* Every one of these is a real response: an error, a consent
       interstitial, the mobile page after a redirect, a shape we no longer
       recognise. Each has to come back empty so the caller falls through to
       the Atom feed rather than showing an empty channel. */
    @Test fun `returns empty rather than throwing on a page with nothing in it`() {
        for (junk in listOf(
            "", "   ", "<html><body>Before you continue to YouTube</body></html>",
            "{\"lockupViewModel\":", "{\"lockupViewModel\":{}}",
            page("""{"lockupViewModel":{"contentId":"tooshort"}}"""),
            "<html>{\"videoId\":\"aaaaaaaaaaa\"}</html>",
        )) {
            assertTrue("should have been empty for '${junk.take(40)}'",
                Feed.parseUploadsPage(junk).isEmpty())
        }
    }

    /* The real thing: three entries lifted verbatim out of a live uploads
       playlist page, wrapped in the surrounding document. Synthetic fixtures
       test what I believed the shape to be; this one tests what it is. */
    @Test fun `reads a real uploads page`() {
        val html = FeedTest::class.java.getResourceAsStream("/uploads-page.html")!!
            .bufferedReader().use { it.readText() }
        val v = Feed.parseUploadsPage(html)
        assertEquals(3, v.size)
        assertEquals(listOf("RUZjwisAnHw", "rc6W2KuTBSs", "eCQwPYARIg8"), v.map { it.id })
        assertEquals("just need a video of him taking the hint #YouTubePartner", v[0].title)
        assertTrue(v[1].title.contains("hydraulic press"))
        /* The ampersand in "@Fredagainagain and @latinmafia" arrives as
           & — the escape that made jsonUnescape necessary. */
        assertTrue(v[2].title.startsWith("@Fredagainagain"))
        for (video in v) assertTrue(VideoId.isValid(video.id))
    }

    /* ---- the cache format ---- */

    @Test fun `encodes and decodes a list unchanged`() {
        val videos = listOf(
            Video("aaaaaaaaaaa", "First", 1785340686L),
            Video("bbbbbbbbbbb", "Ten & Two \"quoted\"", 1L),
            Video("ccccccccccc", "café", null),
        )
        assertEquals(videos, Feed.decode(Feed.encode(videos)))
    }

    /* A title with a tab in it must not read back as a timestamp field, and a
       cache written by the build before timestamps existed must still read. */
    @Test fun `decodes the two-field lines an older build wrote`() {
        val v = Feed.decode("aaaaaaaaaaa\tAn older cache line")
        assertEquals("aaaaaaaaaaa", v.single().id)
        assertEquals("An older cache line", v.single().title)
        assertEquals(null, v.single().publishedAt)
    }

    @Test fun `an unreadable timestamp reads as no timestamp, not as a title`() {
        val v = Feed.decode("aaaaaaaaaaa\tnot-a-number\tThe title")
        assertEquals("The title", v.single().title)
        assertEquals(null, v.single().publishedAt)
    }

    @Test fun `encodes an empty list to something decode reads back as empty`() {
        assertTrue(Feed.decode(Feed.encode(emptyList())).isEmpty())
    }

    /* Tab and newline are the format's separators and the only two characters
       it cannot carry. Losing them from a title is cosmetic; letting one
       through would split one video into two lines. */
    @Test fun `flattens separators out of a title rather than escaping them`() {
        val encoded = Feed.encode(listOf(Video("aaaaaaaaaaa", "a\tb\nc\rd")))
        assertEquals(1, encoded.lines().size)
        assertEquals("a b c d", Feed.decode(encoded).single().title)
    }

    /* The cache is a file on a device and a file can be edited. */
    @Test fun `decode drops lines it cannot trust`() {
        val text = listOf(
            "aaaaaaaaaaa\tGood",
            "short\tBad",
            "aaaaaaaaaa/\tBad",
            "\tno id",
            "no tab at all",
            "",
            "aaaaaaaaaaa\tDuplicate",
            "bbbbbbbbbbb\tGood",
        ).joinToString("\n")
        val v = Feed.decode(text)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), v.map { it.id })
        assertEquals("Good", v[0].title)
    }

    @Test fun `decode falls back to the id for an empty title`() {
        assertEquals("aaaaaaaaaaa", Feed.decode("aaaaaaaaaaa\t   ").single().title)
    }

    private fun titleOf(entry: String) = Feed.parseUploadsPage(page(entry)).single().title

    /* Distinct valid ids, for the tests that need more than a handful. */
    private fun idAt(n: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
        return "vid" + alphabet[n / 64] + alphabet[n % 64] + "aaaaaa"
    }
}
