import Foundation
import TinyTubeCore

/* The approved channels. THIS IS THE PARENTAL CONTROL.

   There is no server-side list and nothing to deploy when a channel is
   approved: what a child may watch is this table, on this device. The Worker
   answers questions ABOUT a channel already in here and can never add one.

   Counterpart of ChannelStore.kt.

   `Channel` itself lives in TinyTubeCore, not here — it is the one part of
   ChannelStore.kt with no Android in it, and `ChannelSort.sort` takes it. A
   second Channel declared in this target would compile perfectly well and then
   refuse to pass to the sorter, so there is deliberately only one. */
extension Channel: Identifiable {}

enum ChannelStore {

    private static let columns =
        "channel_id, title, added_at, handle, avatar_url, group_id"

    private static func channel(_ r: Database.Row) -> Channel {
        Channel(
            id: r.string(0),
            title: r.string(1),
            addedAt: r.int(2),
            handle: r.stringOrNil(3),
            avatarURL: r.stringOrNil(4),
            groupId: r.stringOrNil(5)
        )
    }

    /* Approving a channel. Refuses anything that is not a channel id — the same
       check the Worker makes, made again here, because this is the row that
       decides what a child can reach. */
    @discardableResult
    static func add(
        channelId: String,
        title: String,
        handle: String? = nil,
        avatarURL: String? = nil,
        now: Int64
    ) -> Bool {
        guard YouTubeUrls.isValidChannelId(channelId) else { return false }

        let previous = find(channelId: channelId)
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)

        do {
            /* REPLACE on the id, which also clears uploads_at — so re-approving
               a channel refetches it, which is what someone re-adding one would
               expect. */
            try Database.shared.write(
                """
                INSERT OR REPLACE INTO channels
                    (channel_id, title, added_at, handle, avatar_url)
                VALUES (?, ?, ?, ?, ?)
                """,
                [
                    .text(channelId),
                    .text(trimmed.isEmpty ? channelId : trimmed),
                    .int(now),
                    /* Don't overwrite what we already know with nothing:
                       approving the same channel again from its /channel/UC…
                       page carries no handle, and would otherwise erase the one
                       recorded the first time. */
                    .text(handle ?? previous?.handle),
                    .text(avatarURL ?? previous?.avatarURL),
                ]
            )
            return true
        } catch {
            return false
        }
    }

    static func all() -> [Channel] {
        (try? Database.shared.read(
            "SELECT \(columns) FROM channels ORDER BY added_at DESC",
            row: channel
        )) ?? []
    }

    static func find(channelId: String) -> Channel? {
        (try? Database.shared.read(
            "SELECT \(columns) FROM channels WHERE channel_id = ? LIMIT 1",
            [.text(channelId)],
            row: channel
        ))?.first
    }

    static func find(handle: String) -> Channel? {
        (try? Database.shared.read(
            "SELECT \(columns) FROM channels WHERE handle = ? LIMIT 1",
            [.text(handle)],
            row: channel
        ))?.first
    }

    static func contains(channelId: String) -> Bool {
        find(channelId: channelId) != nil
    }

    /* Removing a channel removes EVERYTHING it put on this device: its row, its
       videos, its watch history, and its pictures.
     *
     * The pictures are the half that is easy to miss, because they are not in
     * the database — the database holds their URLs. `AsyncImage` loads through
     * `URLSession.shared`, whose cache is disk-backed, so every poster and the
     * avatar are files in the Caches directory until something removes them.
     * Android has no equivalent to clear, its loader being memory-only; see
     * ImageCache.
     *
     * The URLs are read BEFORE the rows go. Reading them afterwards finds
     * nothing, which is a way of quietly leaving every picture behind. */
    static func remove(channelId: String) {
        let images = imageURLs(channelId: channelId)

        try? Database.shared.write(
            "DELETE FROM channels WHERE channel_id = ?", [.text(channelId)]
        )
        VideoStore.forget(channelId: channelId)
        WatchStore.forget(channelId: channelId)
        ImageCache.forget(images)
        /* Removing a channel can strand the group it was in — a pair minus one
           is not a group. Same tidy every other writer ends with. */
        tidy()
    }

    // MARK: - Groups

    /* ChannelGroups holds every RULE; this holds the rows. The split is the
       usual one here: the rules are testable on Linux and the SQL is not.

       ⚠️ EVERY MUTATION ENDS IN tidy(). A group of one is not a group, and the
       ways to make one are easy to miss: removing a channel, ungrouping half a
       group, or moving a member into a different group all strand whatever is
       left behind. Putting it at the end of each writer rather than at the call
       sites is what keeps that true for callers nobody has written yet. */
    static func groups() -> [ChannelGroups.Group] {
        (try? Database.shared.read(
            "SELECT group_id, name FROM groups ORDER BY name COLLATE NOCASE ASC",
            row: { ChannelGroups.Group(id: $0.string(0), name: $0.string(1)) }
        )) ?? []
    }

    /* Put these channels in a group of this name, creating it.
     *
     * Returns false if the name is refused — the sheet checks first and
     * disables its confirm, so this is the second lock on the same door rather
     * than the only one. The UNIQUE index on the name is the third.
     *
     * Whatever group a channel was in, it leaves: the column is overwritten,
     * not merged. That needs no code of its own, but it does need the tidy
     * afterwards, because the group it left may now be down to one channel. */
    @discardableResult
    static func group(channelIds: [String], name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard ChannelGroups.nameError(trimmed, existing: groups().map(\.name)) == nil,
              channelIds.count >= 2
        else { return false }

        let id = UUID().uuidString
        do {
            try Database.shared.transaction {
                try Database.shared.write(
                    "INSERT INTO groups (group_id, name) VALUES (?, ?)",
                    [.text(id), .text(trimmed)]
                )
                for channelId in channelIds {
                    try Database.shared.write(
                        "UPDATE channels SET group_id = ? WHERE channel_id = ?",
                        [.text(id), .text(channelId)]
                    )
                }
            }
        } catch {
            return false
        }
        tidy()
        return true
    }

    /* Take these channels out of whatever group they are in. They stay
       approved — ungrouping is not removing, and conflating the two would be
       the worst possible reading of the word. */
    static func ungroup(channelIds: [String]) {
        try? Database.shared.transaction {
            for channelId in channelIds {
                try Database.shared.write(
                    "UPDATE channels SET group_id = NULL WHERE channel_id = ?",
                    [.text(channelId)]
                )
            }
        }
        tidy()
    }

    /* The invariant, enforced after every change: a group with fewer than two
       channels is dissolved, and its remaining member — if any — becomes loose.
       The channels themselves are never touched beyond that column. */
    private static func tidy() {
        let doomed = ChannelGroups.dissolving(channels: all(), groups: groups())
        guard !doomed.isEmpty else { return }
        try? Database.shared.transaction {
            for groupId in doomed {
                try Database.shared.write(
                    "UPDATE channels SET group_id = NULL WHERE group_id = ?", [.text(groupId)]
                )
                try Database.shared.write(
                    "DELETE FROM groups WHERE group_id = ?", [.text(groupId)]
                )
            }
        }
    }

    /* Every picture this channel put on the device: its avatar, and a poster
       for each of its videos. */
    private static func imageURLs(channelId: String) -> [String] {
        var out: [String] = []
        if let avatar = find(channelId: channelId)?.avatarURL { out.append(avatar) }
        out += VideoStore.forChannel(channelId).map(\.thumbnailURL)
        return out
    }

    /* When this channel's uploads were last fetched, or nil for never.
     *
     * Nil is deliberate rather than zero: a channel approved a moment ago has
     * never been fetched and must fetch now, and "never" and "in 1970" should
     * not have to be the same value for that to work. */
    static func uploadsFetchedAt(channelId: String) -> Int64? {
        (try? Database.shared.read(
            "SELECT uploads_at FROM channels WHERE channel_id = ? LIMIT 1",
            [.text(channelId)],
            row: { $0.intOrNil(0) }
        ))?.first ?? nil
    }

    /* Only ever called after a fetch that produced something. Marking a failure
       buys the outage a full day. */
    static func markUploadsFetched(channelId: String, now: Int64) {
        try? Database.shared.write(
            "UPDATE channels SET uploads_at = ? WHERE channel_id = ?",
            [.int(now), .text(channelId)]
        )
    }

    static func setAvatar(channelId: String, avatarURL: String) {
        try? Database.shared.write(
            "UPDATE channels SET avatar_url = ? WHERE channel_id = ?",
            [.text(avatarURL), .text(channelId)]
        )
    }
}
