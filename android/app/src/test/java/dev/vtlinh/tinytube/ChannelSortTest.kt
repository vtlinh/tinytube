package dev.vtlinh.tinytube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelSortTest {

    /* ChannelStore hands its list over newest-approved first, so `addedAt`
       descends down the list this builds. */
    private fun channels(vararg titles: String): List<Channel> =
        titles.mapIndexed { i, title ->
            Channel(id = "UC" + title.take(1).repeat(22), title = title, addedAt = 1000L - i)
        }

    private fun ids(vararg titles: String) = titles.map { "UC" + it.take(1).repeat(22) }

    private val none = listOf(emptyMap<String, Int>(), emptyMap(), emptyMap())

    @Test fun `last added leaves the store's order alone`() {
        val list = channels("Zebra", "Apple", "Mango")
        assertEquals(
            list.map { it.id },
            ChannelSort.sort(list, ChannelSort.Mode.LAST_ADDED, none).map { it.id },
        )
    }

    @Test fun `a to z ignores case`() {
        val out = ChannelSort.sort(channels("zebra", "Apple", "mango"), ChannelSort.Mode.A_Z)
        assertEquals(listOf("Apple", "mango", "zebra"), out.map { it.title })
    }

    /* A channel whose title never resolved would otherwise land at whichever
       end the empty string sorts to, which is not somewhere anybody looks. */
    @Test fun `a to z falls back to the id for a channel with no title`() {
        val list = listOf(
            Channel(id = "UC" + "m".repeat(22), title = "   ", addedAt = 1),
            Channel(id = "UC" + "a".repeat(22), title = "Zebra", addedAt = 2),
        )
        /* "UCmmm…" sorts after "zebra"? No — compare lowercased: "uc..." < "z". */
        assertEquals(
            listOf("UC" + "m".repeat(22), "UC" + "a".repeat(22)),
            ChannelSort.sort(list, ChannelSort.Mode.A_Z).map { it.id },
        )
    }

    /* ---- the ladder ---- */

    @Test fun `the ladder is seven, thirty, three hundred and sixty five days`() {
        assertEquals(listOf(7, 30, 365), ChannelSort.WINDOWS_DAYS)
    }

    @Test fun `the narrowest window with anything in it wins`() {
        val week = mapOf("UCa" to 1)
        val month = mapOf("UCb" to 99)
        assertEquals(0, ChannelSort.windowIndex(listOf(week, month, month)))
        assertEquals(1, ChannelSort.windowIndex(listOf(emptyMap(), month, month)))
        assertEquals(2, ChannelSort.windowIndex(listOf(emptyMap(), emptyMap(), month)))
    }

    @Test fun `no history anywhere has no window`() {
        assertNull(ChannelSort.windowIndex(none))
        assertNull(ChannelSort.windowIndex(emptyList()))
    }

    /* A window of zeroes is an empty window. Otherwise one stale row that
       happens to group to zero would pin the ladder to its rung forever. */
    @Test fun `a window of zeroes does not count as an answer`() {
        assertNull(ChannelSort.windowIndex(listOf(mapOf("UCa" to 0), mapOf("UCb" to 0))))
        assertEquals(1, ChannelSort.windowIndex(listOf(mapOf("UCa" to 0), mapOf("UCb" to 3))))
    }

    /* ---- most watched ---- */

    @Test fun `most watched sorts by the count in the winning window`() {
        val list = channels("Apple", "Banana", "Cherry")
        val (a, b, c) = ids("Apple", "Banana", "Cherry")
        val out = ChannelSort.sort(
            list,
            ChannelSort.Mode.MOST_WATCHED,
            listOf(mapOf(a to 2, b to 9, c to 5), emptyMap(), emptyMap()),
        )
        assertEquals(listOf(b, c, a), out.map { it.id })
    }

    /* The whole point of narrowing first: a channel watched this week leads
       one watched far more often a year ago. A list sorted over all time would
       freeze into whatever was popular when the phone was new. */
    @Test fun `a busy week beats a busier year`() {
        val list = channels("Recent", "Ancient")
        val (recent, ancient) = ids("Recent", "Ancient")
        val out = ChannelSort.sort(
            list,
            ChannelSort.Mode.MOST_WATCHED,
            listOf(mapOf(recent to 1), mapOf(recent to 1), mapOf(recent to 1, ancient to 500)),
        )
        assertEquals(listOf(recent, ancient), out.map { it.id })
    }

    @Test fun `a channel watched in no window sorts below every channel that was`() {
        val list = channels("Apple", "Banana")
        val (a, b) = ids("Apple", "Banana")
        val out = ChannelSort.sort(
            list, ChannelSort.Mode.MOST_WATCHED, listOf(mapOf(b to 1), emptyMap(), emptyMap()),
        )
        assertEquals(listOf(b, a), out.map { it.id })
    }

    /* Alphabetical rather than by arrival, so a refresh cannot reorder two
       channels that have been watched the same number of times. */
    @Test fun `equal counts break alphabetically`() {
        val list = channels("Zebra", "Apple", "Mango")
        val counts = ids("Zebra", "Apple", "Mango").associateWith { 4 }
        val out = ChannelSort.sort(
            list, ChannelSort.Mode.MOST_WATCHED, listOf(counts, emptyMap(), emptyMap()),
        )
        assertEquals(listOf("Apple", "Mango", "Zebra"), out.map { it.title })
    }

    /* The last rung of the ladder. A-Z rather than last-added, so an absent
       answer cannot masquerade as the default one having agreed with it. */
    @Test fun `most watched with no history at all falls back to a to z`() {
        val list = channels("Zebra", "Apple", "Mango")
        assertEquals(
            listOf("Apple", "Mango", "Zebra"),
            ChannelSort.sort(list, ChannelSort.Mode.MOST_WATCHED, none).map { it.title },
        )
        assertEquals(
            listOf("Apple", "Mango", "Zebra"),
            ChannelSort.sort(list, ChannelSort.Mode.MOST_WATCHED, emptyList()).map { it.title },
        )
    }

    @Test fun `sorting nothing is nothing`() {
        for (mode in ChannelSort.Mode.entries) {
            assertEquals(emptyList<Channel>(), ChannelSort.sort(emptyList(), mode, none))
        }
    }

    /* ---- the button ---- */

    @Test fun `the button cycles through every order and back`() {
        var mode = ChannelSort.Mode.LAST_ADDED
        val seen = mutableListOf(mode)
        repeat(ChannelSort.Mode.entries.size) {
            mode = ChannelSort.next(mode)
            seen.add(mode)
        }
        assertEquals(ChannelSort.Mode.entries.toSet(), seen.toSet())
        assertEquals("should return to where it started", ChannelSort.Mode.LAST_ADDED, mode)
    }

    /* Stored by name, so an unknown one has to mean something. Last-added,
       because that is what the list did before there was a choice. */
    @Test fun `an unreadable stored order reads as last added`() {
        assertEquals(ChannelSort.Mode.LAST_ADDED, ChannelSort.modeOf(null))
        assertEquals(ChannelSort.Mode.LAST_ADDED, ChannelSort.modeOf(""))
        assertEquals(ChannelSort.Mode.LAST_ADDED, ChannelSort.modeOf("BY_VIBES"))
        assertEquals(ChannelSort.Mode.LAST_ADDED, ChannelSort.modeOf("a_z"))
    }

    @Test fun `a stored order round-trips by name`() {
        for (mode in ChannelSort.Mode.entries) assertEquals(mode, ChannelSort.modeOf(mode.name))
    }
}
