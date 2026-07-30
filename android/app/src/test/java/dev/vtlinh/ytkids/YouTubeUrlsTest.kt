package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlsTest {

    private val ok = "UC" + "a".repeat(22)

    @Test fun `channel ids must be UC plus 22 url-safe characters`() {
        assertTrue(YouTubeUrls.isValidChannelId(ok))
        assertTrue(YouTubeUrls.isValidChannelId("UC_x-5XG1OV2P6uZZ5FSM9Tt"))
        for (bad in listOf(
            "", "UC", "UCshort", ok + "a", ok.dropLast(1),
            "XX" + "a".repeat(22),      // wrong prefix
            "UC" + "a".repeat(21) + "/", // path separator
            "UC" + "a".repeat(21) + "?",
            "uc" + "a".repeat(22),      // lowercase prefix
        )) {
            assertFalse("should have rejected '$bad'", YouTubeUrls.isValidChannelId(bad))
        }
    }

    @Test fun `finds the channel id in a channel url`() {
        assertEquals(ok, YouTubeUrls.channelIdFromUrl("https://www.youtube.com/channel/$ok"))
        assertEquals(ok, YouTubeUrls.channelIdFromUrl("https://m.youtube.com/channel/$ok/videos"))
        assertEquals(ok, YouTubeUrls.channelIdFromUrl("https://www.youtube.com/channel/$ok?view=0"))
    }

    @Test fun `no channel id where there isn't one`() {
        for (u in listOf(
            "https://www.youtube.com/",
            "https://www.youtube.com/watch?v=aaaaaaaaaaa",
            "https://www.youtube.com/channel/notachannelid",
            "javascript:alert(1)",
            "",
        )) {
            assertNull("should have found nothing in '$u'", YouTubeUrls.channelIdFromUrl(u))
        }
    }

    @Test fun `finds the handle in a handle url`() {
        assertEquals("SomeChannel", YouTubeUrls.handleFromUrl("https://www.youtube.com/@SomeChannel"))
        assertEquals("some.channel", YouTubeUrls.handleFromUrl("https://m.youtube.com/@some.channel/videos"))
        assertNull(YouTubeUrls.handleFromUrl("https://www.youtube.com/channel/$ok"))
        assertNull(YouTubeUrls.handleFromUrl("javascript:/@nope"))
    }

    @Test fun `reads the channel id out of a page`() {
        val canonical =
            """<html><head><link rel="canonical" href="https://www.youtube.com/channel/$ok"></head></html>"""
        assertEquals(ok, YouTubeUrls.channelIdFromHtml(canonical))

        val payload = """{"header":{"channelId":"$ok","title":"x"}}"""
        assertEquals(ok, YouTubeUrls.channelIdFromHtml(payload))

        assertNull(YouTubeUrls.channelIdFromHtml("<html>nothing here</html>"))
        assertNull(YouTubeUrls.channelIdFromHtml(""))
    }

    @Test fun `builds a feed url only for a valid id`() {
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?channel_id=$ok",
            YouTubeUrls.feedUrl(ok),
        )
        assertNull(YouTubeUrls.feedUrl("nope"))
        assertNull(YouTubeUrls.feedUrl("UC" + "a".repeat(21) + "&x=1"))
    }

    /* Parent mode is wider than the player, but still bounded — a tap on an ad
       or an external link must not wander into the open web in our WebView. */
    @Test fun `parent browsing is limited to youtube`() {
        for (u in listOf(
            "https://m.youtube.com/",
            "https://www.youtube.com/@someone",
            "https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg",
            "https://rr1---sn-abc.googlevideo.com/videoplayback",
        )) {
            assertTrue("should have allowed $u", YouTubeUrls.isParentBrowsable(u))
        }
        for (u in listOf(
            "https://youtube.com.attacker.example/",
            "https://notyoutube.com/",
            "https://www.youtube.com@attacker.example/",
            "https://evilgooglevideo.com/",
            "https://example.com/",
            "intent://x#Intent;end",
            "javascript:alert(1)",
        )) {
            assertFalse("should have refused $u", YouTubeUrls.isParentBrowsable(u))
        }
    }
}
