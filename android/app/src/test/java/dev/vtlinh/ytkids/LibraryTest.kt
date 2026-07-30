package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {

    private fun v(id: String, title: String = id) = Video(id, title)

    @Test fun `catalog comes first, then channel uploads`() {
        val merged = Library.merge(
            listOf(v("aaaaaaaaaaa"), v("bbbbbbbbbbb")),
            listOf(v("ccccccccccc"), v("ddddddddddd")),
        )
        assertEquals(
            listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd"),
            merged.map { it.id },
        )
    }

    /* A video can easily be both hand-approved and in its channel's feed. One
       tile, and the title the parent wrote rather than the uploader's. */
    @Test fun `an id in both sources appears once, with the catalog title`() {
        val merged = Library.merge(
            listOf(v("aaaaaaaaaaa", "Bedtime song")),
            listOf(v("aaaaaaaaaaa", "BEDTIME SONG!!! (NEW) 10 HOURS")),
        )
        assertEquals(1, merged.size)
        assertEquals("Bedtime song", merged.single().title)
    }

    @Test fun `duplicates within the channel uploads collapse too`() {
        val merged = Library.merge(emptyList(), listOf(v("aaaaaaaaaaa"), v("aaaaaaaaaaa")))
        assertEquals(1, merged.size)
    }

    @Test fun `either side may be empty`() {
        assertEquals(1, Library.merge(listOf(v("aaaaaaaaaaa")), emptyList()).size)
        assertEquals(1, Library.merge(emptyList(), listOf(v("aaaaaaaaaaa"))).size)
        assertTrue(Library.merge(emptyList(), emptyList()).isEmpty())
    }
}
