import Foundation

/* What the grid shows: recent uploads from every approved channel, as one list,
   newest first.

   Ported from Library.kt. */
public enum Library {

    /* Fill in an upload time for every video in one channel's list.
     *
     * The two sources know different halves. The playlist page gives a hundred
     * videos in exact upload order and no dates whatsoever; the Atom feed gives
     * the newest fifteen with real timestamps. Neither alone can order the grid
     * across channels — a date is what makes one channel's third video
     * comparable to another's tenth.
     *
     * So: a video the feed dated keeps that date. A video it did not is placed
     * ONE SECOND BEFORE whatever preceded it in the page's order. That is not a
     * guess at when it was posted and is not presented as one; it is a sort key
     * that preserves two things which are actually known — that the page's
     * order is upload order, and that everything below the feed's oldest entry
     * is older than it.
     *
     * `fallback` starts the walk when the page's very first entries are not in
     * the feed at all — a channel that posted sixteen videos between two
     * refreshes. Passing the current time makes those sort as newest, which is
     * what they are. */
    public static func datePositions(
        ordered: [Video],
        dated: [String: Int64],
        fallback: Int64
    ) -> [Video] {
        var previous = fallback
        return ordered.map { v in
            let known = dated[v.id] ?? v.publishedAt
            let at: Int64
            if let known, known <= previous { at = known } else { at = previous - 1 }
            previous = at
            return v.publishedAt == at ? v : v.with(publishedAt: at)
        }
    }

    /* Newest first, across everything.
     *
     * Explicitly stable, because Swift's sort is not: videos sharing a
     * timestamp — which happens, channels schedule batches — must keep the
     * order they arrived in rather than shuffling between refreshes, and the
     * Kotlin original gets that from sortedWith for free. Sorting on (key,
     * original index) is how you buy it here.
     *
     * A video with no date at all sorts last: it is either a cache written by
     * an older build or a source that told us nothing, and the top of the grid
     * should belong to things we can actually date. */
    public static func newestFirst(_ videos: [Video]) -> [Video] {
        videos.enumerated()
            .sorted { a, b in
                let ka = a.element.publishedAt ?? Int64.min
                let kb = b.element.publishedAt ?? Int64.min
                if ka != kb { return ka > kb }
                return a.offset < b.offset
            }
            .map(\.element)
    }

    /* Flatten the per-channel lists into the grid's, keeping each video once.
     *
     * Channels' lists are already deduplicated within themselves, but not
     * against each other, and the same video legitimately appears in two
     * channels' lists after a collaboration or a re-upload. Two identical tiles
     * in a row looks like a bug to a child and makes the grid's stable ids
     * ambiguous.
     *
     * Order is by upload time, newest first, across every channel together —
     * not one channel's list after another's. */
    public static func collate(_ uploads: [Video]) -> [Video] {
        var out: [Video] = []
        var seen = Set<String>()
        for v in uploads where seen.insert(v.id).inserted { out.append(v) }
        return newestFirst(out)
    }

    /* Per-channel lists back into one, in the given order.
     *
     * Takes the channel order explicitly rather than iterating a dictionary:
     * Swift dictionaries have no order at all, so the LinkedHashMap the Kotlin
     * version leans on has no counterpart. The caller knows the order — it is
     * the approved list's — and passing it is the only way this can be right. */
    public static func flatten(
        byChannel: [String: [Video]],
        channelOrder: [String]
    ) -> [Video] {
        channelOrder.flatMap { byChannel[$0] ?? [] }
    }

    /* One channel's uploads, collated. An unknown id gives an empty list rather
       than everything: a channel removed while its tab was open should show
       nothing, not the whole library. */
    public static func forChannel(byChannel: [String: [Video]], channelId: String) -> [Video] {
        collate(byChannel[channelId] ?? [])
    }

    /* A group's uploads: several channels' feeds as one grid, collated
       together rather than one channel's after another's.
     *
     * That collation is the whole reason this exists instead of the caller
     * calling forChannel in a loop and concatenating. The same video in two of
     * the group's channels — a collaboration, a re-upload — would otherwise
     * appear twice, and the result would be sorted only within each channel.
     *
     * `channelIds` is ORDERED, for the same reason flatten takes an order: ties
     * keep the order they arrived in, so the sequence has to be one both
     * platforms agree on rather than whatever a dictionary iterates in. Ids
     * naming no feed contribute nothing, so a group whose channels are all gone
     * gives an empty grid rather than the whole library. */
    public static func forChannels(
        byChannel: [String: [Video]],
        channelIds: [String]
    ) -> [Video] {
        collate(channelIds.flatMap { byChannel[$0] ?? [] })
    }
}
