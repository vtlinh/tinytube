package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* The catalog is the app's whole safety boundary: whatever survives parsing is
   what a child can be shown. These cases are all about what must NOT survive. */
class CatalogTest {

    private fun doc(vararg entries: String) =
        """{"videos":[${entries.joinToString(",")}]}"""

    private fun entry(id: String, title: String = "T") =
        """{"id":"$id","title":"$title"}"""

    @Test fun `accepts a well formed entry`() {
        val v = Catalog.parse(doc(entry("abcdefghijk", "Counting Song")))
        assertEquals(1, v.size)
        assertEquals("abcdefghijk", v[0].id)
        assertEquals("Counting Song", v[0].title)
    }

    /* An id of the wrong shape is the one thing that must never reach the
       player: the id is interpolated straight into the embed URL, so a value
       carrying a slash or a query character addresses a different video — or a
       different page entirely — than the one that was approved. */
    @Test fun `rejects ids that are not exactly 11 url-safe characters`() {
        val bad = listOf(
            "",                 // absent
            "short",            // too few
            "abcdefghijkl",     // too many
            "abcdefghij",       // 10, off by one
            "abcdefghij/",      // path separator
            "abcdefghij?",      // starts a query string
            "abcdefghij&",      // starts another parameter
            "abcdefghij#",      // fragment
            "abcdefghij.",      // dot
            "abcdefghi j",      // space
            "abcdefghij\n",     // newline
            "../../etcpass",    // traversal shape, right length
        )
        for (id in bad) {
            assertFalse("should have rejected: '$id'", Catalog.isValidId(id))
            assertTrue("should not have parsed: '$id'", Catalog.parse(doc(entry(id))).isEmpty())
        }
    }

    /* A newline inside the id would break out of the JS string literal the
       player builds, which is script injection into our own page. */
    @Test fun `rejects an id that would escape the player's js string`() {
        assertFalse(Catalog.isValidId("';alert(1)//"))
        assertTrue(Catalog.parse(doc(entry("';alert(1)//"))).isEmpty())
    }

    @Test fun `keeps the good entries and drops the bad ones alongside them`() {
        val v = Catalog.parse(doc(entry("aaaaaaaaaaa"), entry("nope"), entry("bbbbbbbbbbb")))
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), v.map { it.id })
    }

    @Test fun `drops a repeated id`() {
        val v = Catalog.parse(doc(entry("aaaaaaaaaaa", "First"), entry("aaaaaaaaaaa", "Again")))
        assertEquals(1, v.size)
        assertEquals("First", v[0].title)
    }

    @Test fun `falls back to the id when the title is missing or blank`() {
        assertEquals("aaaaaaaaaaa", Catalog.parse(doc("""{"id":"aaaaaaaaaaa"}""")).single().title)
        assertEquals("aaaaaaaaaaa", Catalog.parse(doc(entry("aaaaaaaaaaa", "   "))).single().title)
    }

    /* Malformed input is reachable in production: a half-written catalog, a
       captive-portal HTML page served where JSON was expected, a truncated
       response. None of it may throw on the main thread. */
    @Test fun `returns empty rather than throwing on malformed input`() {
        val junk = listOf(
            "", "   ", "not json at all", "<html>portal</html>",
            "{", "[]", "null", "{\"videos\":null}", "{\"videos\":\"nope\"}",
            "{\"videos\":[1,2,3]}", "{\"other\":[]}",
        )
        for (j in junk) assertTrue("should have been empty for: '$j'", Catalog.parse(j).isEmpty())
    }

    @Test fun `thumbnail url is derived from the validated id`() {
        val v = Catalog.parse(doc(entry("aaaaaaaaaaa"))).single()
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg", v.thumbnailUrl)
    }
}
