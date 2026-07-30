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

    private fun entry(id: String, title: String) = """
        <entry>
          <id>yt:video:$id</id>
          <yt:videoId>$id</yt:videoId>
          <yt:channelId>UCaaaaaaaaaaaaaaaaaaaaaa</yt:channelId>
          <title>$title</title>
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
}
