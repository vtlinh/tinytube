package dev.vtlinh.tinytube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* The rules a screen will not show you are wrong. */
class ChannelGroupsTest {

    private fun ch(id: String, title: String, group: String? = null, addedAt: Long = 0) =
        Channel(id = id, title = title, addedAt = addedAt, groupId = group)

    private val cartoons = ChannelGroups.Group("g1", "Cartoons")
    private val science = ChannelGroups.Group("g2", "science")

    /* Groups first and A-Z among themselves, whatever the sort is — a parent
       looking for a group wants it where it was last time, and "recently
       added" is not a property a group has. */
    @Test fun `groups lead, alphabetically, regardless of sort`() {
        val channels = listOf(
            ch("c1", "Zed", science.id, addedAt = 100),
            ch("c2", "Alpha", science.id, addedAt = 90),
            ch("c3", "Beta", cartoons.id, addedAt = 80),
            ch("c4", "Gamma", cartoons.id, addedAt = 70),
            ch("c5", "Loose", null, addedAt = 60),
        )
        val rows = ChannelGroups.arrange(
            channels, listOf(science, cartoons), ChannelSort.Mode.LAST_ADDED,
        )
        val headers = rows.filterIsInstance<ChannelGroups.Row.Header>().map { it.group.name }
        assertEquals(listOf("Cartoons", "science"), headers)
        /* And the loose channel is last, below every group. */
        assertTrue((rows.last() as ChannelGroups.Row.Item).channel.id == "c5")
    }

    /* Inside a group, the sort DOES apply. */
    @Test fun `the sort orders channels within a group`() {
        val channels = listOf(
            ch("c1", "Zed", cartoons.id, addedAt = 100),
            ch("c2", "Alpha", cartoons.id, addedAt = 90),
        )
        val byName = ChannelGroups.arrange(channels, listOf(cartoons), ChannelSort.Mode.A_Z)
            .filterIsInstance<ChannelGroups.Row.Item>().map { it.channel.id }
        assertEquals(listOf("c2", "c1"), byName)

        val byAdded = ChannelGroups.arrange(channels, listOf(cartoons), ChannelSort.Mode.LAST_ADDED)
            .filterIsInstance<ChannelGroups.Row.Item>().map { it.channel.id }
        assertEquals(listOf("c1", "c2"), byAdded)
    }

    /* A group of one should not exist, and if the database ever holds one it
       must not reach a parent's screen as a header with nothing under it. */
    @Test fun `a group with fewer than two members is not drawn`() {
        val channels = listOf(ch("c1", "Only", cartoons.id))
        val rows = ChannelGroups.arrange(channels, listOf(cartoons), ChannelSort.Mode.A_Z)
        assertTrue(rows.filterIsInstance<ChannelGroups.Row.Header>().isEmpty())
    }

    /* The half of that which nothing used to check. Skipping the HEADER must
       not skip the CHANNEL: it is still approved and still feeding the child's
       grid, and the list is the only place a parent can take it away. */
    @Test fun `the survivor of an undrawn group is still listed, as loose`() {
        val channels = listOf(ch("c1", "Only", cartoons.id), ch("c2", "Loose"))
        val rows = ChannelGroups.arrange(channels, listOf(cartoons), ChannelSort.Mode.A_Z)
        val items = rows.filterIsInstance<ChannelGroups.Row.Item>()
        assertEquals(listOf("c2", "c1"), items.map { it.channel.id })
        assertTrue("it is not under a header, so it is not grouped",
            items.none { it.grouped })
    }

    /* Same thing by the other route: a group_id pointing at a group that is not
       in the list at all. Nothing should be able to make a channel disappear. */
    @Test fun `a channel naming a group that does not exist is still listed`() {
        val channels = listOf(ch("c1", "Orphan", "gone"), ch("c2", "Loose"))
        val rows = ChannelGroups.arrange(channels, emptyList(), ChannelSort.Mode.A_Z)
        assertEquals(
            listOf("c2", "c1"),
            rows.filterIsInstance<ChannelGroups.Row.Item>().map { it.channel.id },
        )
    }

    @Test fun `group needs two, ungroup needs one group`() {
        val a = ch("c1", "A", cartoons.id)
        val b = ch("c2", "B", cartoons.id)
        val loose = ch("c3", "C")
        val other = ch("c4", "D", science.id)

        assertFalse(ChannelGroups.canGroup(listOf(a)))
        assertTrue(ChannelGroups.canGroup(listOf(a, loose)))

        assertTrue(ChannelGroups.canUngroup(listOf(a, b)))
        assertTrue("a subset of one group still counts", ChannelGroups.canUngroup(listOf(a)))
        assertFalse("a loose channel is in no group", ChannelGroups.canUngroup(listOf(loose)))
        assertFalse("mixed with a loose one", ChannelGroups.canUngroup(listOf(a, loose)))
        assertFalse("two different groups", ChannelGroups.canUngroup(listOf(a, other)))
        assertFalse(ChannelGroups.canUngroup(emptyList()))
    }

    /* One whole group plus a loose channel is "add this to that group", so the
       name is offered. Every other shape leaves the box empty. */
    @Test fun `the name prefills only for one whole group plus loose`() {
        val a = ch("c1", "A", cartoons.id)
        val b = ch("c2", "B", cartoons.id)
        val loose = ch("c3", "C")
        val other = ch("c4", "D", science.id)
        val all = listOf(a, b, loose, other, ch("c5", "E", science.id))
        val groups = listOf(cartoons, science)

        assertEquals("Cartoons", ChannelGroups.prefillName(listOf(a, b, loose), groups, all))
        assertNull("no loose channel — nothing is being added",
            ChannelGroups.prefillName(listOf(a, b), groups, all))
        assertNull("only part of the group",
            ChannelGroups.prefillName(listOf(a, loose), groups, all))
        assertNull("two groups — neither name wins",
            ChannelGroups.prefillName(listOf(a, b, other), groups, all))
        assertNull("nothing grouped at all",
            ChannelGroups.prefillName(listOf(loose), groups, all))
    }

    @Test fun `a name must not be blank or already taken`() {
        val existing = listOf("Cartoons", "science")
        assertEquals(ChannelGroups.NameError.EMPTY, ChannelGroups.nameError("", existing))
        assertEquals(ChannelGroups.NameError.EMPTY, ChannelGroups.nameError("   ", existing))
        assertEquals(ChannelGroups.NameError.TAKEN, ChannelGroups.nameError("Cartoons", existing))
        assertEquals(
            "trimmed and case-insensitive, because a person reading the list cannot tell them apart",
            ChannelGroups.NameError.TAKEN,
            ChannelGroups.nameError("  cartoons ", existing),
        )
        assertNull(ChannelGroups.nameError("Music", existing))
        assertNull("nothing is taken when nothing exists", ChannelGroups.nameError("Music", emptyList()))
    }

    /* The invariant, after the fact. Removing a channel, ungrouping half a
       group and moving one elsewhere can each strand the one left behind. */
    @Test fun `groups left with fewer than two dissolve`() {
        val groups = listOf(cartoons, science)
        val stranded = listOf(ch("c1", "A", cartoons.id), ch("c2", "B", science.id), ch("c3", "C", science.id))
        assertEquals(setOf(cartoons.id), ChannelGroups.dissolving(stranded, groups))

        val emptied = listOf(ch("c2", "B", science.id), ch("c3", "C", science.id))
        assertEquals("a group with no channels goes too",
            setOf(cartoons.id), ChannelGroups.dissolving(emptied, groups))

        val healthy = listOf(
            ch("c1", "A", cartoons.id), ch("c9", "Z", cartoons.id),
            ch("c2", "B", science.id), ch("c3", "C", science.id),
        )
        assertTrue(ChannelGroups.dissolving(healthy, groups).isEmpty())
    }

    @Test fun `selecting a group selects its channels`() {
        val all = listOf(
            ch("c1", "A", cartoons.id), ch("c2", "B", cartoons.id), ch("c3", "C"),
        )
        assertEquals(setOf("c1", "c2"), ChannelGroups.membersOf(cartoons.id, all))
        assertTrue(ChannelGroups.membersOf("nope", all).isEmpty())
    }

    /* The other half of prefillName. It offers "Cartoons" for all-of-Cartoons
       plus a loose channel; if the name were then judged taken, the dialog
       would refuse the only name it had suggested. */
    private val allChannels = listOf(
        ch("c1", "A", cartoons.id), ch("c2", "B", cartoons.id),
        ch("c4", "D", science.id), ch("c5", "E", science.id),
        ch("c3", "C"),
    )

    @Test fun `a group being wholly regrouped frees its name`() {
        val groups = listOf(cartoons, science)

        assertEquals(
            "all of Cartoons is moving, so Cartoons dissolves and the name is free",
            listOf("science"),
            ChannelGroups.namesInUse(groups, allChannels, setOf("c1", "c2", "c3")),
        )
        assertNull(
            ChannelGroups.nameError(
                "Cartoons",
                ChannelGroups.namesInUse(groups, allChannels, setOf("c1", "c2", "c3")),
            ),
        )

        assertEquals(
            "only half of Cartoons is moving, so it keeps its members and its name",
            listOf("Cartoons", "science"),
            ChannelGroups.namesInUse(groups, allChannels, setOf("c1", "c3")),
        )
        assertEquals(
            ChannelGroups.NameError.TAKEN,
            ChannelGroups.nameError(
                "Cartoons",
                ChannelGroups.namesInUse(groups, allChannels, setOf("c1", "c3")),
            ),
        )
    }

    /* And what the store does with that: take the emptied group's row over
       rather than insert a second one under a UNIQUE name. */
    @Test fun `an emptied group's row is absorbed rather than duplicated`() {
        val groups = listOf(cartoons, science)

        assertEquals(
            cartoons.id,
            ChannelGroups.absorbing("Cartoons", groups, allChannels, setOf("c1", "c2", "c3")),
        )
        assertEquals("trimmed and case-insensitive, like every other name check",
            cartoons.id,
            ChannelGroups.absorbing("  cartoons ", groups, allChannels, setOf("c1", "c2", "c3")))
        assertNull("a partly-selected group keeps its name, so there is nothing to absorb",
            ChannelGroups.absorbing("Cartoons", groups, allChannels, setOf("c1", "c3")))
        assertNull("a name nobody has is simply new",
            ChannelGroups.absorbing("Music", groups, allChannels, setOf("c1", "c2", "c3")))
        assertNull("an empty group hands its name to nobody",
            ChannelGroups.absorbing("Cartoons", groups, listOf(ch("c3", "C")), setOf("c3")))
    }
}
