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

    /* Flattening keeps the map's order, which is ChannelStore's order:
       newest-approved channel first. That is what the grid's order IS. */
    @Test fun `flatten keeps channel order and then upload order`() {
        val byChannel = linkedMapOf(
            "UC1" to listOf(v("aaaaaaaaaaa"), v("bbbbbbbbbbb")),
            "UC2" to listOf(v("ccccccccccc")),
        )
        assertEquals(
            listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"),
            Library.flatten(byChannel).map { it.id },
        )
    }

    @Test fun `flatten of nothing is nothing`() {
        assertTrue(Library.flatten(emptyMap()).isEmpty())
    }

    @Test fun `one channel's uploads come back on their own`() {
        val byChannel = linkedMapOf(
            "UC1" to listOf(v("aaaaaaaaaaa")),
            "UC2" to listOf(v("bbbbbbbbbbb"), v("ccccccccccc")),
        )
        assertEquals(
            listOf("bbbbbbbbbbb", "ccccccccccc"),
            Library.forChannel(byChannel, "UC2").map { it.id },
        )
    }

    /* A channel removed while its videos were on screen. Showing the whole
       library instead would be the worst possible reading of "unknown". */
    @Test fun `an unknown channel shows nothing, not everything`() {
        val byChannel = linkedMapOf("UC1" to listOf(v("aaaaaaaaaaa")))
        assertTrue(Library.forChannel(byChannel, "UC-gone").isEmpty())
    }

    @Test fun `a channel's own duplicates are still collapsed`() {
        val byChannel = linkedMapOf(
            "UC1" to listOf(v("aaaaaaaaaaa"), v("aaaaaaaaaaa"), v("bbbbbbbbbbb")),
        )
        assertEquals(
            listOf("aaaaaaaaaaa", "bbbbbbbbbbb"),
            Library.forChannel(byChannel, "UC1").map { it.id },
        )
    }
}
