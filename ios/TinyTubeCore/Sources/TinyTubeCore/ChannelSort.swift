import Foundation

/* One approved channel. Ported from the Channel data class in ChannelStore.kt,
   which is the only part of that file with no Android in it. */
public struct Channel: Equatable, Sendable {
    public let id: String
    public let title: String
    public let addedAt: Int64
    /* the @name it was approved from, when there was one */
    public let handle: String?
    /* the channel's picture, when we managed to find it */
    public let avatarURL: String?

    /* Which group it belongs to, or nil for a loose channel. A group always has
       at least two of these — see ChannelGroups. */
    public let groupId: String?

    public init(
        id: String,
        title: String,
        addedAt: Int64,
        handle: String? = nil,
        avatarURL: String? = nil,
        groupId: String? = nil
    ) {
        self.id = id
        self.title = title
        self.addedAt = addedAt
        self.handle = handle
        self.avatarURL = avatarURL
        self.groupId = groupId
    }

    /* Where to send the parent's web view to look at this channel again. The id
       is canonical and always works; a handle can be changed by its owner. */
    public var url: String { "https://m.youtube.com/channel/\(id)" }
}

/* What order the approved channels are listed in.

   Three orders, and the third one is the reason this file exists.

   lastAdded is what the list has always done and is still the default: a parent
   who just approved something looks for it at the top.

   aToZ is for a list long enough that "where is it" beats "what did I just do".

   mostWatched is the interesting one, because "most watched" is only a question
   with an answer if there is something to count. So it is a LADDER, not a
   single window: the last 7 days, and if nothing was watched in them the last
   30, and if nothing then the last 365 — and if a device has no history at all,
   A-Z, because falling back to last-added would make an empty history look like
   a real answer that happened to agree with the default.

   The point of narrowing first is that recency is the useful part. A channel
   watched daily this week should lead even if another was watched more often a
   year ago; a list sorted over all time would freeze into whatever was popular
   when the phone was new.

   Ported from ChannelSort.kt. The counting happens where the database is;
   everything about which window applies and how ties break is here. */
public enum ChannelSort {

    public enum Mode: String, CaseIterable, Sendable {
        case lastAdded = "LAST_ADDED"
        case aToZ = "A_Z"
        case mostWatched = "MOST_WATCHED"
    }

    /* The ladder, in days, narrowest first. Each rung is only used when every
       narrower one found nothing at all. */
    public static let windowsInDays = [7, 30, 365]

    public static func mode(of name: String?) -> Mode {
        guard let name else { return .lastAdded }
        return Mode(rawValue: name) ?? .lastAdded
    }

    /* Cycling rather than a menu: one button, three states, and the bar says
       which. */
    public static func next(_ mode: Mode) -> Mode {
        let all = Mode.allCases
        let i = all.firstIndex(of: mode) ?? 0
        return all[(i + 1) % all.count]
    }

    /* Which rung of the ladder actually applies, as an index into
       windowsInDays — or nil for "nothing has been watched at all".
     *
     * `countsByWindow` is one map per window, in windowsInDays order. A window
     * counts as having an answer if ANY channel has a non-zero count in it; a
     * map of zeroes is the same as an empty one and must not win, or a device
     * with a single stale row would sort by nothing forever. */
    public static func windowIndex(_ countsByWindow: [[String: Int]]) -> Int? {
        countsByWindow.firstIndex { counts in counts.values.contains { $0 > 0 } }
    }

    /* The list, in the chosen order.
     *
     * `channels` arrives newest-approved first, which is the store's order and
     * therefore lastAdded with nothing to do.
     *
     * A-Z ignores case and falls back to the id, so a channel whose title never
     * resolved still lands somewhere predictable rather than at whichever end
     * empty strings sort to.
     *
     * mostWatched breaks ties A-Z rather than by count-then-arrival: two
     * channels watched three times each are not meaningfully ordered by which
     * was approved first, and alphabetical at least does not move between
     * refreshes. */
    public static func sort(
        _ channels: [Channel],
        mode: Mode,
        countsByWindow: [[String: Int]] = []
    ) -> [Channel] {
        switch mode {
        case .lastAdded:
            return channels
        case .aToZ:
            return byName(channels)
        case .mostWatched:
            guard let window = windowIndex(countsByWindow) else {
                /* Nothing has ever been watched. A-Z rather than last-added, so
                   an absent answer does not masquerade as the default one
                   having agreed with it. */
                return byName(channels)
            }
            let counts = countsByWindow[window]
            /* Sorted by (count desc, name) as one key rather than by two passes
               — Swift's sort is not guaranteed stable, so leaning on a previous
               pass to break ties would be leaning on something undocumented. */
            return channels.sorted { a, b in
                let ca = counts[a.id] ?? 0
                let cb = counts[b.id] ?? 0
                if ca != cb { return ca > cb }
                return sortKey(a) < sortKey(b)
            }
        }
    }

    private static func byName(_ channels: [Channel]) -> [Channel] {
        channels.sorted { sortKey($0) < sortKey($1) }
    }

    private static func sortKey(_ c: Channel) -> String {
        let trimmed = c.title.trimmingCharacters(in: .whitespacesAndNewlines)
        return (trimmed.isEmpty ? c.id : trimmed).lowercased()
    }
}
