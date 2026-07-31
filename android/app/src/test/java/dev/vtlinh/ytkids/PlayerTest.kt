package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* The navigation allowlist is the last thing between a tap inside the player
   and the open web. Every case here is a URL that has to be refused. */
class PlayerTest {

    @Test fun `allows the player's own origins`() {
        val ok = listOf(
            "https://www.youtube-nocookie.com/embed/aaaaaaaaaaa",
            "https://youtube-nocookie.com/embed/aaaaaaaaaaa",
            "https://www.youtube.com/iframe_api",
            "https://s.ytimg.com/yts/jsbin/player.js",
            "https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg",
            "https://rr3---sn-4g5ednse.googlevideo.com/videoplayback?x=1",
        )
        for (u in ok) assertTrue("should have allowed: $u", Player.isPlayerUrl(u))
    }

    /* The lookalike family. Each of these defeats one of the obvious wrong
       implementations — substring search, endsWith without a dot, reading the
       host before the '@'. */
    @Test fun `refuses hosts that merely look like youtube`() {
        val bad = listOf(
            "https://youtube.com.attacker.example/watch",   // domain as a prefix
            "https://notyoutube.com/watch",                 // endsWith without a dot
            "https://www.youtube.com.evil.test/",           // suffix continues
            "https://evilgooglevideo.com/videoplayback",    // no dot before googlevideo
            "https://www.youtube.com@attacker.example/",    // userinfo, real host is last
            "https://attacker.example/?u=www.youtube.com",  // in the query string
            "https://attacker.example/#www.youtube.com",    // in the fragment
        )
        for (u in bad) assertFalse("should have refused: $u", Player.isPlayerUrl(u))
    }

    /* Where a tap on an end-screen card or a channel link actually goes. */
    @Test fun `refuses real youtube destinations outside the embed`() {
        val bad = listOf(
            "https://m.youtube.com/watch?v=aaaaaaaaaaa",
            "https://music.youtube.com/",
            "https://accounts.google.com/signin",
            "https://www.google.com/search?q=x",
        )
        for (u in bad) assertFalse("should have refused: $u", Player.isPlayerUrl(u))
    }

    /* Non-http schemes have no host to check. intent:// in particular is how a
       page hands control to another installed app. */
    @Test fun `refuses non-http schemes outright`() {
        val bad = listOf(
            "javascript:alert(1)",
            "intent://watch#Intent;package=com.google.android.youtube;end",
            "file:///android_asset/x.html",
            "content://media/external/video/1",
            "data:text/html,<h1>x",
            "about:blank",
            "market://details?id=com.x",
            "",
            "   ",
        )
        for (u in bad) assertFalse("should have refused: $u", Player.isPlayerUrl(u))
        assertNull(Player.hostOf("javascript:alert(1)"))
    }

    @Test fun `host parsing strips port and userinfo and lowercases`() {
        assertEquals("www.youtube.com", Player.hostOf("https://WWW.YouTube.COM/x"))
        assertEquals("www.youtube.com", Player.hostOf("https://www.youtube.com:443/x"))
        assertEquals("attacker.example", Player.hostOf("https://www.youtube.com@attacker.example/x"))
    }

    @Test fun `builds a page for a valid id`() {
        val page = Player.pageFor("aaaaaaaaaaa")!!
        assertTrue(page.contains("ourId = 'aaaaaaaaaaa'"))
        assertTrue(page.contains("iframe_api"))
    }

    /* The app draws its own play/pause on a native layer, so YouTube must draw
       none of its own — with controls: 1 there is a whole row of its chrome
       sitting under our overlay, reachable the moment the overlay stops
       intercepting for an ad. */
    @Test fun `the page asks for no youtube chrome`() {
        val page = Player.pageFor("aaaaaaaaaaa")!!
        assertTrue("controls must be off", page.contains("controls: 0"))
        assertTrue("annotations must be off", page.contains("iv_load_policy: 3"))
        assertTrue("keyboard shortcuts must be off", page.contains("disablekb: 1"))
    }

    /* The overlay's buttons call these; a rename here breaks play/pause
       silently, because evaluateJavascript reports nothing back. */
    @Test fun `the page exposes the controls the overlay calls`() {
        val page = Player.pageFor("aaaaaaaaaaa")!!
        assertTrue(page.contains("window.ytk"))
        assertTrue(page.contains("play:"))
        assertTrue(page.contains("pause:"))
    }

    /* VideoId already refuses these; the player refuses them again rather than
       trusting its caller, because this is where a bad id becomes script. */
    @Test fun `refuses to build a page for an invalid id`() {
        for (id in listOf("", "short", "';alert(1)//", "aaaaaaaaaa/", "aaaaaaaaaaaa")) {
            assertNull("should not have built a page for: '$id'", Player.pageFor(id))
        }
    }
}
