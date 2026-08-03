import Foundation
import TinyTubeCore

/* The grid, in SQLite. Counterpart of VideoStore.kt. */
enum VideoStore {

    private static let columns =
        "video_id, title, published_at, thumb_url"

    private static func video(_ r: Database.Row) -> Video {
        Video(
            id: r.string(0),
            title: r.string(1),
            publishedAt: r.intOrNil(2),
            thumbURL: r.stringOrNil(3)
        )
    }

    static func forChannel(_ channelId: String) -> [Video] {
        (try? Database.shared.read(
            """
            SELECT \(columns) FROM videos
            WHERE channel_id = ? ORDER BY position ASC
            """,
            [.text(channelId)],
            row: video
        )) ?? []
    }

    /* Every channel's list, keyed by channel. What `Library` collates into the
       grid — it wants them grouped, because the same video can be in two
       channels' lists after a collaboration. */
    static func byChannel() -> [String: [Video]] {
        let rows = (try? Database.shared.read(
            """
            SELECT channel_id, \(columns) FROM videos
            ORDER BY channel_id ASC, position ASC
            """,
            row: { r in
                (r.string(0), Video(
                    id: r.string(1),
                    title: r.string(2),
                    publishedAt: r.intOrNil(3),
                    thumbURL: r.stringOrNil(4)
                ))
            }
        )) ?? []

        var out: [String: [Video]] = [:]
        for (channelId, video) in rows { out[channelId, default: []].append(video) }
        return out
    }

    /* The ids this device already has for a channel. Sent to the Worker so the
       reply carries full details only for what is new — the difference between
       a 1.4 KB refresh and a 19 KB one. */
    static func knownIds(channelId: String) -> [String] {
        (try? Database.shared.read(
            "SELECT video_id FROM videos WHERE channel_id = ? ORDER BY position ASC",
            [.text(channelId)],
            row: { $0.string(0) }
        )) ?? []
    }

    /* The Worker's reply REPLACES a channel's videos rather than merging into
       them. It is the answer to what the channel has NOW, so a video missing
       from it was deleted, made private, or pushed past the hundred; merging
       would slowly fill the grid with tiles that play nothing.
     *
     * An empty list changes nothing. A reply that parsed to nothing is a
     * failure wearing the shape of an answer, and a stale grid beats an empty
     * one — which is also what a phone with no signal gets. */
    static func replace(channelId: String, videos: [Video]) {
        guard !videos.isEmpty else { return }

        /* What this channel's posters were BEFORE the reply, so the ones that
           do not survive it can be deleted from the phone.
         *
         * ImageStore never expires — a URL fetched once is kept for good — so
         * removal is the only thing that reclaims anything. A channel that
         * posts daily pushes a video off the end of its hundred every day, and
         * without this each one leaves a poster on disk for the life of the
         * install. Read here rather than after the write: afterwards there is
         * nothing left to read.
         *
         * BOTH SIDES USE `thumbnailURL`, NOT `thumb_url`. The column is
         * nullable — Uploads.thumb answers nil for a reply with no thumb, or one
         * from an unexpected host — and the grid then draws the DERIVED
         * i.ytimg.com URL, which is the name the JPEG is cached under. Reading
         * the column alone made those posters invisible to this comparison, so a
         * video dropping off the end of the hundred left its file on disk for
         * the life of the install. */
        let before: [String] = (try? Database.shared.read(
            "SELECT video_id, thumb_url FROM videos WHERE channel_id = ?",
            [.text(channelId)],
            row: { Video(id: $0.string(0), title: "", thumbURL: $0.stringOrNil(1)).thumbnailURL }
        )) ?? []

        do {
            try Database.shared.transaction {
                try Database.shared.write(
                    "DELETE FROM videos WHERE channel_id = ?", [.text(channelId)]
                )
                for (position, v) in videos.enumerated() {
                    /* REPLACE on the id, because the same video appears in two
                       channels' lists after a collaboration and the primary key
                       is the video rather than the pair. Whichever channel was
                       written last owns the row; the grid collates by id
                       anyway, so the tile is the same either way. */
                    try Database.shared.write(
                        """
                        INSERT OR REPLACE INTO videos
                            (video_id, channel_id, title, published_at, thumb_url, position)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        [
                            .text(v.id),
                            .text(channelId),
                            .text(v.title),
                            .int(v.publishedAt),
                            .text(v.thumbURL),
                            .int(Int64(position)),
                        ]
                    )
                }
            }
        } catch {
            /* Leaves whatever was there. An empty grid is worse than a stale
               one, so a failed replace is not allowed to become a deletion.
               Nothing is pruned either — dropping the pictures for a write that
               rolled back would blank tiles the grid is still showing. */
            return
        }

        let kept = Set(videos.map { $0.thumbnailURL })
        ImageStore.forget(before.filter { !kept.contains($0) })
    }

    /* A channel is no longer approved: its videos go with it, at once, rather
       than lingering until something else evicts them. */
    static func forget(channelId: String) {
        try? Database.shared.write(
            "DELETE FROM videos WHERE channel_id = ?", [.text(channelId)]
        )
    }
}
