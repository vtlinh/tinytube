package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* Whatever passes this check is what a child can be shown. Every case here is
   about what must NOT pass. */
class VideoIdTest {

    @Test fun `accepts a well formed id`() {
        assertTrue(VideoId.isValid("abcdefghijk"))
        assertTrue(VideoId.isValid("dQw4w9WgXcQ"))
        assertTrue(VideoId.isValid("_-_-_-_-_-_"))
    }

    /* An id of the wrong shape is the one thing that must never reach the
       player: it is interpolated straight into the embed URL, so a value
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
        for (id in bad) assertFalse("should have rejected: '$id'", VideoId.isValid(id))
    }

    /* A quote inside the id would break out of the JS string literal the
       player builds, which is script injection into our own page. */
    @Test fun `rejects an id that would escape the player's js string`() {
        assertFalse(VideoId.isValid("';alert(1)//"))
    }

    @Test fun `thumbnail url is derived from the id`() {
        assertEquals(
            "https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg",
            Video("aaaaaaaaaaa", "T").thumbnailUrl,
        )
    }
}
