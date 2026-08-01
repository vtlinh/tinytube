package dev.vtlinh.tinytube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* The navigation allowlist is the last thing between a tap inside the player
   and the open web. Every case here is a URL that has to be refused. */
class PlayerTest {

    /* THE PLAYER RUNS ON THE NOCOOKIE DOMAIN, and this pins it because moving
       it cost both apps their playback.
     *
     * It was set to https://www.youtube.com so a signed-in Premium account
     * would play without ads. That shipped, and every video on both platforms
     * came up "Video unavailable": the page is a synthetic document built by
     * loadDataWithBaseURL, so claiming youtube.com claims an origin it cannot
     * prove, and YouTube's embed refuses to serve a player to it. The nocookie
     * domain exists to be embedded by pages that are not YouTube, which is
     * exactly what this is.
     *
     * Nothing failed when that constant changed, which is why it reached a
     * phone. Now something does. If you are here because this test is red,
     * read the comment on Player.ORIGIN before changing the expectation. */
    @Test fun `the player's origin is the embeddable one`() {
        assertEquals("https://www.youtube-nocookie.com", Player.ORIGIN)
        assertTrue(Player.isPlayerURL(Player.ORIGIN + "/embed/aaaaaaaaaaa"))
    }

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

    /* The parameters that actually do something are pinned here.
       controls is deliberately NOT among them: controls: 0 was tried and the
       mobile embed keeps its centre play/pause and title anyway, so the chrome
       is covered by a native overlay instead of asked away. The two below are
       the ones that remove routes out of the video — annotation cards link to
       other videos, and the keyboard shortcuts include next/previous. */
    @Test fun `the page turns off the parameters that lead elsewhere`() {
        val page = Player.pageFor("aaaaaaaaaaa")!!
        assertTrue("annotations must be off", page.contains("iv_load_policy: 3"))
        assertTrue("keyboard shortcuts must be off", page.contains("disablekb: 1"))
        assertTrue("related videos must be off", page.contains("rel: 0"))
    }

    /* The page's only job beyond playing is telling the Activity what is
       happening, and it is a one-way street: the overlay has no controls of
       its own, so nothing here is ever called back into. A rename on either
       side of these fails silently — addJavascriptInterface reports nothing
       when a method is missing. */
    @Test fun `the page reports state back to the activity`() {
        val page = Player.pageFor("aaaaaaaaaaa")!!
        assertTrue(page.contains("Bridge.onState"))
        assertTrue(page.contains("Bridge.onEnded"))
        assertTrue(page.contains("Bridge.onError"))
        assertTrue(page.contains("Bridge.onAd"))
    }

    /* VideoId already refuses these; the player refuses them again rather than
       trusting its caller, because this is where a bad id becomes script. */
    @Test fun `refuses to build a page for an invalid id`() {
        for (id in listOf("", "short", "';alert(1)//", "aaaaaaaaaa/", "aaaaaaaaaaaa")) {
            assertNull("should not have built a page for: '$id'", Player.pageFor(id))
        }
    }
}
