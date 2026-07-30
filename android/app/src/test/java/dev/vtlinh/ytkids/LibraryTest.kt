package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {

    private fun v(id: String, title: String = id) = Video(id, title)

    @Test fun `keeps feed order`() {
        val out = Library.collate(listOf(v("aaaaaaaaaaa"), v("bbbbbbbbbbb"), v("ccccccccccc")))
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), out.map { it.id })
    }

    /* The same video legitimately appears in two channels' feeds after a
       collaboration or a re-upload. Two identical tiles reads as a bug. */
    @Test fun `a video in two channels' feeds appears once`() {
        val out = Library.collate(
            listOf(
                v("aaaaaaaaaaa", "As posted by channel one"),
                v("bbbbbbbbbbb"),
                v("aaaaaaaaaaa", "As posted by channel two"),
            ),
        )
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), out.map { it.id })
        /* first occurrence wins, so the newest-approved channel's copy leads */
        assertEquals("As posted by channel one", out[0].title)
    }

    @Test fun `no approved channels means an empty grid`() {
        assertTrue(Library.collate(emptyList()).isEmpty())
    }
}
