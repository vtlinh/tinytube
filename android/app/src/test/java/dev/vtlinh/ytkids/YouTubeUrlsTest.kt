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

    /* Cosmetic, but it is stored and then fetched and drawn — so an og:image
       pointing anywhere other than YouTube's avatar hosts is refused rather
       than kept. */
    @Test fun `reads the channel avatar, and only from youtube image hosts`() {
        val ok = """<meta property="og:image" content="https://yt3.googleusercontent.com/a/x=s900">"""
        assertEquals("https://yt3.googleusercontent.com/a/x=s900", YouTubeUrls.channelAvatarFromHtml(ok))

        val ggpht = """<meta property="og:image" content="https://yt3.ggpht.com/a/y">"""
        assertEquals("https://yt3.ggpht.com/a/y", YouTubeUrls.channelAvatarFromHtml(ggpht))

        for (bad in listOf(
            """<meta property="og:image" content="https://attacker.example/a.png">""",
            """<meta property="og:image" content="https://yt3.ggpht.com.attacker.example/a">""",
            """<meta property="og:image" content="javascript:alert(1)">""",
            """<meta property="og:title" content="not an image">""",
            "",
        )) {
            assertNull("should have refused: $bad", YouTubeUrls.channelAvatarFromHtml(bad))
        }
    }

    @Test fun `builds a feed url only for a valid id`() {
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?channel_id=$ok",
            YouTubeUrls.feedUrl(ok),
        )
        assertNull(YouTubeUrls.feedUrl("nope"))
        assertNull(YouTubeUrls.feedUrl("UC" + "a".repeat(21) + "&x=1"))
    }

    /* UC to UU, and nothing else touched. The id keeps its 22 characters, so
       a valid channel id always makes a valid playlist id. */
    @Test fun `derives the uploads playlist id`() {
        assertEquals("UU" + ok.substring(2), YouTubeUrls.uploadsPlaylistId(ok))
        assertEquals(24, YouTubeUrls.uploadsPlaylistId(ok)!!.length)
        assertEquals("UUx_-yZ0123456789abcdefg", YouTubeUrls.uploadsPlaylistId("UCx_-yZ0123456789abcdefg"))
    }

    /* Same gate as feedUrl, and for the same reason: this string is fetched.
       An unvalidated id here is a query parameter someone else chose. */
    @Test fun `builds an uploads url only for a valid id`() {
        assertEquals(
            "https://www.youtube.com/playlist?list=UU${ok.substring(2)}&hl=en",
            YouTubeUrls.uploadsUrl(ok),
        )
        for (bad in listOf("nope", "", "UC" + "a".repeat(21) + "&x=1", "UU" + "a".repeat(22))) {
            assertNull("should have refused: $bad", YouTubeUrls.uploadsPlaylistId(bad))
            assertNull("should have refused: $bad", YouTubeUrls.uploadsUrl(bad))
        }
    }

    /* www, not m. The mobile page lists twenty videos and hides the rest
       behind a continuation — the request is made with a desktop user agent
       for the same reason, see ChannelFeeds. */
    @Test fun `the uploads url is the desktop host`() {
        assertTrue(YouTubeUrls.uploadsUrl(ok)!!.startsWith("https://www.youtube.com/"))
    }

    @Test fun `recognises a channel page`() {
        for (u in listOf(
            "https://www.youtube.com/channel/$ok",
            "https://m.youtube.com/channel/$ok/videos",
            "https://www.youtube.com/channel/$ok?view=0",
            "https://www.youtube.com/@SomeChannel",
            "https://m.youtube.com/@SomeChannel/videos",
            "https://www.youtube.com/@some.channel/featured#x",
        )) {
            assertTrue("should be a channel page: $u", YouTubeUrls.isChannelPage(u))
        }
    }

    /* The whole point of the check: these all MENTION a channel without being
       one, and approving from them would be guessing which channel was meant. */
    @Test fun `refuses pages that are not a channel`() {
        for (u in listOf(
            "https://www.youtube.com/",
            "https://m.youtube.com/feed/trending",
            "https://www.youtube.com/watch?v=aaaaaaaaaaa",
            "https://www.youtube.com/results?search_query=@SomeChannel",
            "https://www.youtube.com/playlist?list=PL123",
            "https://www.youtube.com/shorts/aaaaaaaaaaa",
        )) {
            assertFalse("should not be a channel page: $u", YouTubeUrls.isChannelPage(u))
        }
    }

    /* Anchored at the start of the path, so a channel path appearing later in
       a URL doesn't qualify. */
    @Test fun `channel path must start the path, not appear within it`() {
        assertFalse(YouTubeUrls.isChannelPage("https://www.youtube.com/redirect?q=/channel/$ok"))
        assertFalse(YouTubeUrls.isChannelPage("https://www.youtube.com/foo/channel/$ok"))
        assertFalse(YouTubeUrls.isChannelPage("https://www.youtube.com/foo/@SomeChannel"))
    }

    @Test fun `refuses channel-shaped paths on the wrong host`() {
        assertFalse(YouTubeUrls.isChannelPage("https://youtube.com.attacker.example/@x"))
        assertFalse(YouTubeUrls.isChannelPage("https://i.ytimg.com/@SomeChannel"))
        assertFalse(YouTubeUrls.isChannelPage("https://www.youtube.com@attacker.example/@x"))
        assertFalse(YouTubeUrls.isChannelPage("javascript:/@x"))
        assertFalse(YouTubeUrls.isChannelPage(""))
    }

    @Test fun `path extraction drops query and fragment`() {
        assertEquals("/@x", YouTubeUrls.pathOf("https://www.youtube.com/@x?a=1#b"))
        assertEquals("/", YouTubeUrls.pathOf("https://www.youtube.com"))
        assertEquals("/", YouTubeUrls.pathOf("https://www.youtube.com/?a=1"))
        assertNull(YouTubeUrls.pathOf("javascript:alert(1)"))
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

    /* Signing in spans several Google hosts and fails as a blank page rather
       than a visible refusal if any of them is blocked. */
    /* Sign-in is a redirect chain across several Google hosts and it stops
       dead on the first one that is blocked — which looks like a hang, not a
       refusal. Enumerating them host by host kept missing one, so google.com
       is allowed wholesale in parent mode. */
    @Test fun `parent browsing allows the google sign-in hosts`() {
        for (u in listOf(
            "https://accounts.google.com/ServiceLogin?service=youtube",
            "https://accounts.youtube.com/accounts/CheckConnection",
            "https://consent.youtube.com/m?continue=https://www.youtube.com/",
            "https://apis.google.com/js/api.js",
            "https://ssl.gstatic.com/accounts/x.png",
            "https://lh3.googleusercontent.com/a/avatar",
            "https://google.com/",
            "https://ogs.google.com/widget/app/so",
            "https://play.google.com/log",
            "https://signaler-pa.clients6.google.com/punctual/v1/chooseServer",
        )) {
            assertTrue("should have allowed $u", YouTubeUrls.isParentBrowsable(u))
        }
    }

    /* Widening to Google's sign-in hosts must not widen to anything that
       merely ends in a similar-looking string. */
    @Test fun `sign-in lookalikes are still refused`() {
        for (u in listOf(
            "https://accounts.google.com.attacker.example/",
            "https://notgstatic.com/",
            "https://evilgoogleusercontent.com/",
            "https://accounts.google.com@attacker.example/",
        )) {
            assertFalse("should have refused $u", YouTubeUrls.isParentBrowsable(u))
        }
    }

    /* The player is a separate, narrower allowlist and gains none of this —
       a signed-in Google page must never be reachable from the child's
       screen. */
    @Test fun `the player still refuses the sign-in hosts`() {
        for (u in listOf(
            "https://accounts.google.com/ServiceLogin",
            "https://myaccount.google.com/",
            "https://ssl.gstatic.com/accounts/x.png",
        )) {
            assertFalse("player should refuse $u", Player.isPlayerUrl(u))
        }
    }
}
