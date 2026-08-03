package dev.vtlinh.tinytube

/* Channels gathered into named groups, and every rule about how that behaves.

   Android-free and tested, like the rest of this layer, because the rules are
   fiddlier than they look and all of them are wrong in ways a screen will not
   show you. Two screens depend on this — the parent's list and the child's
   Channels tab — and they must agree.

   THE INVARIANT: A GROUP HAS AT LEAST TWO CHANNELS. Grouping is offered only
   for two or more; a group that loses members until one is left dissolves, and
   an empty one goes. There is no such thing as a group of one, so nothing
   downstream has to wonder what it would mean. `dissolving` is what enforces
   it, and it runs after every change rather than at the call sites that happen
   to be remembered.

   THE ORDER is groups first, then loose channels. Group headers are A-Z among
   themselves NO MATTER what sort is in force — a parent looking for a group
   wants it where it was last time, and "recently added" is not a property a
   group has. What the sort does control is the channels: inside a group, and
   in the loose list below. */
object ChannelGroups {

    data class Group(val id: String, val name: String)

    /* One line of the list, flattened. A sealed type rather than a nested one
       because both platforms' list views want a flat array they can index. */
    sealed class Row {
        data class Header(val group: Group, val size: Int) : Row()
        /* `grouped` is what tells a row it is a member rather than a loose
           channel — the two look different and select differently. */
        data class Item(val channel: Channel, val grouped: Boolean) : Row()
    }

    fun arrange(
        channels: List<Channel>,
        groups: List<Group>,
        mode: ChannelSort.Mode,
        countsByWindow: List<Map<String, Int>> = emptyList(),
    ): List<Row> {
        val rows = mutableListOf<Row>()
        val byGroup = channels.filter { it.groupId != null }.groupBy { it.groupId }

        /* A-Z on the name, then the id. The id is the tiebreaker rather than
           decoration: two groups cannot share a name today, but Swift's sort is
           not stable and a list that reorders itself between redraws for equal
           keys is the kind of thing nobody reports and everybody notices. */
        val ordered = groups.sortedWith(
            compareBy<Group> { it.name.trim().lowercase() }.thenBy { it.id },
        )
        val drawn = mutableSetOf<String>()
        for (group in ordered) {
            val members = byGroup[group.id].orEmpty()
            /* A group with fewer than two members should not exist — see
               `dissolving`. Skipped rather than drawn, so a database that got
               into that state through some path nobody thought of does not put
               a header with nothing under it on a parent's screen. */
            if (members.size < 2) continue
            drawn += group.id
            rows += Row.Header(group, members.size)
            rows += ChannelSort.sort(members, mode, countsByWindow)
                .map { Row.Item(it, grouped = true) }
        }

        /* Loose is "not under a header that was drawn", NOT "groupId is null".
           A channel naming a group that was skipped above — or one that no
           longer exists — used to fall out of BOTH halves and vanish from the
           list entirely: still approved, still filling the child's grid, still
           playing, and invisible on the one screen that can un-approve it. A
           defensive skip must never hide a channel from the parent. */
        val loose = channels.filter { it.groupId == null || it.groupId !in drawn }
        rows += ChannelSort.sort(loose, mode, countsByWindow)
            .map { Row.Item(it, grouped = false) }
        return rows
    }

    /* ---- what the selection allows ---- */

    /* Two or more, because a group of one is not a group. */
    fun canGroup(selected: Collection<Channel>): Boolean = selected.size >= 2

    /* Offered only when everything selected is already in ONE group.
     *
     * A selection spanning two groups deliberately does NOT offer it: "ungroup"
     * would then mean "empty two different groups", which is a bigger thing
     * than the word promises and not what a parent tapping it would expect.
     * Grouping them together is still offered, and that is the coherent action
     * for a mixed selection. */
    fun canUngroup(selected: Collection<Channel>): Boolean {
        if (selected.isEmpty()) return false
        val first = selected.first().groupId ?: return false
        return selected.all { it.groupId == first }
    }

    /* Selecting a group selects its channels — this is what a header's tap
       expands to. Returns the ids to add. */
    fun membersOf(groupId: String, channels: List<Channel>): Set<String> =
        channels.filter { it.groupId == groupId }.map { it.id }.toSet()

    /* The name to put in the dialog, or null for an empty box.
     *
     * Only one case fills it: exactly one group is involved, ALL of that
     * group's channels are selected, and at least one loose channel is selected
     * too. That is "add these to this group", and making the parent retype the
     * name they can see on screen would be rude.
     *
     * Not filled when the selection spans two groups — neither name is the
     * obvious winner and picking one silently would be a guess. Not filled for
     * a partial group either: taking half of Cartoons and some loose channels
     * and calling the result Cartoons would leave two groups wanting the name,
     * and the dialog would refuse it as taken. Empty box, parent decides. */
    fun prefillName(
        selected: Collection<Channel>,
        groups: List<Group>,
        all: List<Channel>,
    ): String? {
        val involved = selected.mapNotNull { it.groupId }.toSet()
        if (involved.size != 1) return null
        val groupId = involved.first()
        val whole = membersOf(groupId, all) == selected.filter { it.groupId != null }.map { it.id }.toSet()
        if (!whole) return null
        if (selected.none { it.groupId == null }) return null
        return groups.firstOrNull { it.id == groupId }?.name
    }

    /* Group names a new group may NOT take, given what is selected.
     *
     * Not simply "every name": a group with every one of its members in the
     * selection is about to be emptied, so it dissolves and its name comes
     * free. That is precisely the case prefillName fills the box for — all of
     * Cartoons plus a loose channel, named Cartoons — and judging it against
     * the raw list would refuse the one name the parent was offered. */
    fun namesInUse(
        groups: List<Group>,
        all: List<Channel>,
        selectedIds: Set<String>,
    ): List<String> =
        groups.filterNot { emptiedBy(it, all, selectedIds) }.map { it.name }

    /* The group whose ROW a new group of this name should take over, or null
       for a genuinely new one.
     *
     * Only one qualifies, and it is the same one namesInUse forgives: same
     * name, every member selected. Reusing the row rather than inserting a
     * second one is not tidiness — the name column is UNIQUE, so the insert
     * would abort, and the tidy that dissolves the emptied group only runs
     * afterwards. Adding a channel to a group would fail with no explanation.
     *
     * A partly-selected group does NOT qualify. It keeps members, keeps its
     * name, and that name is genuinely taken. */
    fun absorbing(
        name: String,
        groups: List<Group>,
        all: List<Channel>,
        selectedIds: Set<String>,
    ): String? {
        val trimmed = name.trim()
        return groups.firstOrNull {
            it.name.trim().equals(trimmed, ignoreCase = true) && emptiedBy(it, all, selectedIds)
        }?.id
    }

    private fun emptiedBy(group: Group, all: List<Channel>, selectedIds: Set<String>): Boolean {
        val members = membersOf(group.id, all)
        /* isNotEmpty guards a group with no members at all — it should not
           exist, and treating "nothing left to move" as "fully selected" would
           hand its name to any selection at all. */
        return members.isNotEmpty() && selectedIds.containsAll(members)
    }

    /* ---- the name, as the dialog judges it ---- */

    enum class NameError { EMPTY, TAKEN }

    /* Empty-after-trimming and already-taken are the two refusals, and both
       disable the dialog's confirm rather than failing after it. A name that is
       only spaces is not a name, and duplicate names would put two identical
       headers on the list with no way to tell which is which.
     *
     * `existing` is every current group name. Comparison is trimmed and
     * case-insensitive: "Cartoons" and "cartoons " are the same name to a
     * person, and a list showing both would look broken. */
    fun nameError(name: String, existing: Collection<String>): NameError? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return NameError.EMPTY
        val taken = existing.any { it.trim().equals(trimmed, ignoreCase = true) }
        return if (taken) NameError.TAKEN else null
    }

    /* ---- the invariant, enforced ---- */

    /* Which groups no longer have enough channels to exist, given the state
       AFTER a change. Their remaining member — if there is one — becomes loose.
     *
     * Called after every mutation rather than only the obvious ones: removing a
     * channel, ungrouping some of a group, and moving a channel to another
     * group can each strand the one it left. */
    fun dissolving(channels: List<Channel>, groups: List<Group>): Set<String> {
        val counts = channels.filter { it.groupId != null }.groupingBy { it.groupId!! }.eachCount()
        return groups.map { it.id }.filter { (counts[it] ?: 0) < 2 }.toSet()
    }
}
