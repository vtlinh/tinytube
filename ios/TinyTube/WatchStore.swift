import Foundation
import TinyTubeCore

/* What was played, on this device only. Counterpart of WatchStore.kt.

   It exists for ONE feature — ordering the approved list by what is actually
   being watched — and it never leaves the phone. Nothing uploads it, the Worker
   is never told what was played, removing a channel removes its rows, and
   anything older than the widest rung of ChannelSort's ladder is deleted.
   Don't grow it into analytics, and don't send it anywhere. */
enum WatchStore {

    /* Kept only as long as the widest window can ask about. A row older than
       the last rung of the ladder can never change an answer, so it is storage
       spent on nothing.

       PLUS A FORTNIGHT, which WatchStore.kt has always had and this did not:
       slack so a device whose clock moved does not lose the year it was
       supposed to keep. Two platforms pruning different history from the same
       plays would order "most watched" differently, which is the one thing this
       table exists to decide. */
    static var keepDays: Int { (ChannelSort.windowsInDays.max() ?? 365) + 14 }
    private static var keepMillis: Int64 { Int64(keepDays) * 24 * 60 * 60 * 1000 }

    /* One row per play rather than a counter per channel: "most watched in the
       last 7 days" cannot be answered by a running total. */
    static func record(videoId: String, now: Int64) {
        guard VideoId.isValid(videoId) else { return }

        /* channel_id is denormalised out of `videos` at write time on purpose.
           The counting query has to work for a channel whose videos have since
           been replaced by a refresh; joining would quietly drop exactly the
           history that is oldest and therefore matters most to the 365-day
           rung. */
        guard let channelId = (try? Database.shared.read(
            "SELECT channel_id FROM videos WHERE video_id = ? LIMIT 1",
            [.text(videoId)],
            row: { $0.string(0) }
        ))?.first else { return }

        /* History is a nice-to-have. Failing to write one is not worth
           interrupting a video for. */
        try? Database.shared.write(
            """
            INSERT INTO watches (channel_id, video_id, watched_at)
            VALUES (?, ?, ?)
            """,
            [.text(channelId), .text(videoId), .int(now)]
        )
        prune(now: now)
    }

    private static func prune(now: Int64) {
        try? Database.shared.write(
            "DELETE FROM watches WHERE watched_at < ?", [.int(now - keepMillis)]
        )
    }

    /* How many plays each channel has had since a moment. Channels with none
       are ABSENT rather than zero, which is what lets ChannelSort tell an empty
       window from a full one — a window of zeroes counts as empty, or one stale
       row would pin the ladder to its rung forever. */
    static func countsSince(_ since: Int64) -> [String: Int] {
        let rows = (try? Database.shared.read(
            """
            SELECT channel_id, COUNT(*) FROM watches
            WHERE watched_at >= ? GROUP BY channel_id
            """,
            [.int(since)],
            row: { ($0.string(0), Int($0.int(1))) }
        )) ?? []
        return Dictionary(uniqueKeysWithValues: rows)
    }

    /* One map per rung of ChannelSort's ladder, in its order. */
    static func countsByWindow(now: Int64) -> [[String: Int]] {
        ChannelSort.windowsInDays.map { days in
            countsSince(now - Int64(days) * 24 * 60 * 60 * 1000)
        }
    }

    /* A channel is no longer approved: what was watched on it goes too. */
    static func forget(channelId: String) {
        try? Database.shared.write(
            "DELETE FROM watches WHERE channel_id = ?", [.text(channelId)]
        )
    }
}
