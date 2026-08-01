package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {

    private fun v(id: String, title: String = id, at: Long? = null) = Video(id, title, at)

    /* Undated videos keep the order they arrived in — the sort is stable and
       they all compare equal. That is what makes a cache written by an older
       build still show a sensible grid. */
    @Test fun `keeps feed order when nothing is dated`() {
        val out = Library.collate(listOf(v("aaaaaaaaaaa"), v("bbbbbbbbbbb"), v("ccccccccccc")))
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), out.map { it.id })
    }

    /* The grid is one list ordered by upload time, not one channel's feed
       after another's. */
    @Test fun `collate puts the newest upload first, across channels`() {
        val out = Library.collate(
            listOf(
                v("aaaaaaaaaaa", at = 100),   // channel one, its newest
                v("bbbbbbbbbbb", at = 50),
                v("ccccccccccc", at = 300),   // channel two, newer than both
                v("ddddddddddd", at = 75),
            ),
        )
        assertEquals(
            listOf("ccccccccccc", "aaaaaaaaaaa", "ddddddddddd", "bbbbbbbbbbb"),
            out.map { it.id },
        )
    }

    /* Channels schedule batches, so equal timestamps are ordinary. A grid that
       reshuffled them between refreshes would move a tile out from under a
       finger. */
    @Test fun `equal timestamps keep their arrival order`() {
        val out = Library.newestFirst(
            listOf(v("aaaaaaaaaaa", at = 10), v("bbbbbbbbbbb", at = 10), v("ccccccccccc", at = 10)),
        )
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), out.map { it.id })
    }

    /* Last, not first: an undated video is a cache from an older build or a
       source that told us nothing, and the top of the grid should belong to
       what can actually be dated. */
    @Test fun `an undated video sorts below every dated one`() {
        val out = Library.newestFirst(
            listOf(v("aaaaaaaaaaa"), v("bbbbbbbbbbb", at = 1), v("ccccccccccc", at = 2)),
        )
        assertEquals(listOf("ccccccccccc", "bbbbbbbbbbb", "aaaaaaaaaaa"), out.map { it.id })
    }

    /* ---- reconciling the two sources ---- */

    @Test fun `dated videos keep the date the feed gave them`() {
        val out = Library.datePositions(
            ordered = listOf(v("aaaaaaaaaaa"), v("bbbbbbbbbbb")),
            dated = mapOf("aaaaaaaaaaa" to 500L, "bbbbbbbbbbb" to 400L),
            fallback = 1000,
        )
        assertEquals(listOf(500L, 400L), out.map { it.publishedAt })
    }

    /* The page's order is upload order and the feed only reaches fifteen deep.
       Everything past that is placed just below whatever preceded it — not a
       guess at when it was posted, a key that keeps the known order. */
    @Test fun `undated videos are placed below the last dated one, in page order`() {
        val out = Library.datePositions(
            ordered = listOf(v("aaaaaaaaaaa"), v("bbbbbbbbbbb"), v("ccccccccccc"), v("ddddddddddd")),
            dated = mapOf("aaaaaaaaaaa" to 500L, "bbbbbbbbbbb" to 400L),
            fallback = 1000,
        )
        assertEquals(listOf(500L, 400L, 399L, 398L), out.map { it.publishedAt })
    }

    /* A channel that posted sixteen videos between two refreshes: the page's
       first entries are newer than anything the feed dated. They sort as
       newest, which is what they are. */
    @Test fun `entries above the newest dated one start from the fallback`() {
        val out = Library.datePositions(
            ordered = listOf(v("aaaaaaaaaaa"), v("bbbbbbbbbbb"), v("ccccccccccc")),
            dated = mapOf("ccccccccccc" to 400L),
            fallback = 1000,
        )
        assertEquals(listOf(999L, 998L, 400L), out.map { it.publishedAt })
    }

    /* Whatever the sources say, the result has to be strictly descending —
       that is the property the grid's order depends on. A feed entry claiming
       to be newer than something above it in the page is the case that breaks
       it, and the page's order wins. */
    @Test fun `the result always descends, even when a date disagrees`() {
        val out = Library.datePositions(
            ordered = listOf(v("aaaaaaaaaaa"), v("bbbbbbbbbbb"), v("ccccccccccc")),
            dated = mapOf("aaaaaaaaaaa" to 100L, "bbbbbbbbbbb" to 900L, "ccccccccccc" to 50L),
            fallback = 1000,
        )
        val keys = out.mapNotNull { it.publishedAt }
        assertEquals(3, keys.size)
        for (i in 1 until keys.size) {
            assertTrue("$keys is not descending", keys[i] < keys[i - 1])
        }
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), out.map { it.id })
    }

    /* A cached video already carrying a date, refreshed when the feed no
       longer reaches it. Its own date still counts. */
    @Test fun `a video's existing date is used when the feed does not mention it`() {
        val out = Library.datePositions(
            ordered = listOf(v("aaaaaaaaaaa", at = 500), v("bbbbbbbbbbb", at = 300)),
            dated = emptyMap(),
            fallback = 1000,
        )
        assertEquals(listOf(500L, 300L), out.map { it.publishedAt })
    }

    @Test fun `dating nothing is nothing`() {
        assertTrue(Library.datePositions(emptyList(), emptyMap(), 1000).isEmpty())
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
