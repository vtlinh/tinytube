import Foundation

/* Channels gathered into named groups, and every rule about how that behaves.

   Ported from ChannelGroups.kt, rule for rule. Two screens depend on it — the
   parent's list and the child's Channels tab — and they must agree, which is
   the same reason it is pure and tested on Linux rather than living in a view.

   THE INVARIANT: A GROUP HAS AT LEAST TWO CHANNELS. Grouping is offered only
   for two or more; a group that loses members until one is left dissolves, and
   an empty one goes. There is no such thing as a group of one. `dissolving` is
   what enforces it, and it runs after every change rather than at whichever
   call sites are remembered.

   THE ORDER is groups first, then loose channels. Group headers are A–Z among
   themselves no matter what sort is in force — a parent looking for a group
   wants it where it was last time, and "recently added" is not a property a
   group has. The sort controls the channels: inside a group, and in the loose
   list below. */
public enum ChannelGroups {

    public struct Group: Equatable, Sendable {
        public let id: String
        public let name: String

        public init(id: String, name: String) {
            self.id = id
            self.name = name
        }
    }

    /* One line of the list, flattened — both platforms' list views want an
       array they can index. */
    public enum Row: Equatable {
        case header(group: Group, size: Int)
        /* `grouped` is what tells a row it is a member rather than a loose
           channel: the two look different and select differently. */
        case item(channel: Channel, grouped: Bool)
    }

    public static func arrange(
        channels: [Channel],
        groups: [Group],
        mode: ChannelSort.Mode,
        countsByWindow: [[String: Int]] = []
    ) -> [Row] {
        var rows: [Row] = []

        /* A–Z on the name, then the id. The id is load-bearing rather than
           decoration: SWIFT'S SORT IS NOT STABLE, so two groups comparing
           equal could swap places between redraws. Kotlin's would not, and a
           list that reorders itself on one platform only is the kind of bug
           that gets blamed on the data. */
        let ordered = groups.sorted { a, b in
            let l = a.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let r = b.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if l != r { return l < r }
            return a.id < b.id
        }

        var drawn: Set<String> = []
        for group in ordered {
            let members = channels.filter { $0.groupId == group.id }
            /* A group with fewer than two members should not exist — see
               `dissolving`. Skipped rather than drawn, so a database that got
               into that state by some path nobody thought of does not put an
               empty header on a parent's screen. */
            if members.count < 2 { continue }
            drawn.insert(group.id)
            rows.append(.header(group: group, size: members.count))
            rows += ChannelSort.sort(members, mode: mode, countsByWindow: countsByWindow)
                .map { .item(channel: $0, grouped: true) }
        }

        /* Loose is "not under a header that was drawn", NOT "groupId is nil".
           A channel naming a group that was skipped above — or one that no
           longer exists — used to fall out of BOTH halves and vanish from the
           list entirely: still approved, still filling the child's grid, still
           playing, and invisible on the one screen that can un-approve it. A
           defensive skip must never hide a channel from the parent. */
        let loose = channels.filter { channel in
            guard let groupId = channel.groupId else { return true }
            return !drawn.contains(groupId)
        }
        rows += ChannelSort.sort(loose, mode: mode, countsByWindow: countsByWindow)
            .map { .item(channel: $0, grouped: false) }
        return rows
    }

    // MARK: - What the selection allows

    /* Two or more, because a group of one is not a group. */
    public static func canGroup(_ selected: [Channel]) -> Bool { selected.count >= 2 }

    /* Offered only when everything selected is already in ONE group.
     *
     * A selection spanning two groups deliberately does NOT offer it:
     * "ungroup" would then mean "empty two different groups", which is bigger
     * than the word promises. Grouping them together is still offered, and
     * that is the coherent action for a mixed selection. */
    public static func canUngroup(_ selected: [Channel]) -> Bool {
        guard let first = selected.first?.groupId else { return false }
        return selected.allSatisfy { $0.groupId == first }
    }

    /* Selecting a group selects its channels — what a header's tap expands to. */
    public static func membersOf(_ groupId: String, in channels: [Channel]) -> Set<String> {
        Set(channels.filter { $0.groupId == groupId }.map(\.id))
    }

    /* The name to put in the dialog, or nil for an empty box.
     *
     * Only one case fills it: exactly one group is involved, ALL of that
     * group's channels are selected, and at least one loose channel is too.
     * That is "add these to this group", and making a parent retype a name
     * they can see on screen would be rude.
     *
     * Not filled across two groups — neither name is the obvious winner. Not
     * filled for a partial group either: half of Cartoons plus some loose ones
     * called Cartoons would leave two groups wanting the name, and the dialog
     * would refuse it as taken. Empty box; the parent decides. */
    public static func prefillName(
        selected: [Channel],
        groups: [Group],
        all: [Channel]
    ) -> String? {
        let involved = Set(selected.compactMap(\.groupId))
        guard involved.count == 1, let groupId = involved.first else { return nil }
        let selectedInGroup = Set(selected.filter { $0.groupId != nil }.map(\.id))
        guard membersOf(groupId, in: all) == selectedInGroup else { return nil }
        guard selected.contains(where: { $0.groupId == nil }) else { return nil }
        return groups.first { $0.id == groupId }?.name
    }

    /* Group names a new group may NOT take, given what is selected.
     *
     * Not simply "every name": a group with every one of its members in the
     * selection is about to be emptied, so it dissolves and its name comes
     * free. That is precisely the case prefillName fills the box for — all of
     * Cartoons plus a loose channel, named Cartoons — and judging it against
     * the raw list would refuse the one name the parent was offered. */
    public static func namesInUse(
        groups: [Group],
        all: [Channel],
        selectedIds: Set<String>
    ) -> [String] {
        groups.filter { !emptiedBy($0, all: all, selectedIds: selectedIds) }.map(\.name)
    }

    /* The group whose ROW a new group of this name should take over, or nil
     * for a genuinely new one.
     *
     * Only one qualifies, and it is the same one namesInUse forgives: same
     * name, every member selected. Reusing the row rather than inserting a
     * second one is not tidiness — the name column is UNIQUE, so the insert
     * would abort, and the tidy that dissolves the emptied group only runs
     * afterwards. Adding a channel to a group would fail with no explanation.
     *
     * A partly-selected group does NOT qualify. It keeps members, keeps its
     * name, and that name is genuinely taken. */
    public static func absorbing(
        name: String,
        groups: [Group],
        all: [Channel],
        selectedIds: Set<String>
    ) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return groups.first {
            $0.name.trimmingCharacters(in: .whitespacesAndNewlines)
                .caseInsensitiveCompare(trimmed) == .orderedSame
                && emptiedBy($0, all: all, selectedIds: selectedIds)
        }?.id
    }

    private static func emptiedBy(_ group: Group, all: [Channel], selectedIds: Set<String>) -> Bool {
        let members = membersOf(group.id, in: all)
        /* The non-empty guard covers a group with no members at all — it should
           not exist, and treating "nothing left to move" as "fully selected"
           would hand its name to any selection at all. */
        return !members.isEmpty && selectedIds.isSuperset(of: members)
    }

    // MARK: - The name, as the dialog judges it

    public enum NameError: Equatable {
        case empty
        case taken
    }

    /* Blank-after-trimming and already-taken are the two refusals, and both
     * disable the dialog's confirm rather than failing after it. A name that is
     * only spaces is not a name, and duplicates would put two identical headers
     * on the list with no way to tell them apart.
     *
     * Trimmed and case-insensitive: "Cartoons" and "cartoons " are the same
     * name to a person, and a list showing both would look broken. */
    public static func nameError(_ name: String, existing: [String]) -> NameError? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .empty }
        let taken = existing.contains {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
                .caseInsensitiveCompare(trimmed) == .orderedSame
        }
        return taken ? .taken : nil
    }

    // MARK: - The invariant, enforced

    /* Which groups no longer have enough channels to exist, given the state
     * AFTER a change. Their remaining member, if any, becomes loose.
     *
     * Called after every mutation rather than only the obvious ones: removing a
     * channel, ungrouping part of a group, and moving one elsewhere can each
     * strand the channel left behind. */
    public static func dissolving(channels: [Channel], groups: [Group]) -> Set<String> {
        var counts: [String: Int] = [:]
        for channel in channels {
            guard let id = channel.groupId else { continue }
            counts[id, default: 0] += 1
        }
        return Set(groups.map(\.id).filter { (counts[$0] ?? 0) < 2 })
    }
}
