import Foundation

/* The on-device database.

   The statements live here, free of any SQLite binding, so the exact list the
   app runs on a device can be run against a real engine in a test. Migrations
   execute once, on the only copy of the approved-channel list a parent has; a
   typo in one of them is not something to discover on a phone.

   Ported from Schema.kt, statement for statement. The two platforms keep the
   SAME shape on purpose: the tables answer the same questions from the same
   pure code, and a column that exists on one but not the other is a bug waiting
   for whichever platform gets the next feature.

   What is deliberately NOT shared is the version history. An iOS install has
   never had a v1 through v4 of its own, so it creates everything at once — but
   the statements it creates are the accumulated ones, and `statements(from:to:)`
   keeps the same per-version blocks so an iOS device that ships today can be
   migrated tomorrow the same way an Android one is. */
public enum Schema {

    public static let database = "tinytube.sqlite"
    public static let version = 6

    /* Android renamed its database file and carried nothing across; a device
       updating into that build finds an empty approved list. Nothing equivalent
       is needed here — no iOS build has ever shipped, so there is no earlier
       file to lose. See the note on Schema.kt's DATABASE. */

    public static let groups = "groups"
    public static let channels = "channels"
    public static let videos = "videos"
    public static let watches = "watches"

    static let v1 = [
        """
        CREATE TABLE IF NOT EXISTS channels (
            channel_id TEXT PRIMARY KEY NOT NULL,
            title      TEXT NOT NULL,
            added_at   INTEGER NOT NULL
        )
        """,
        /* Newest-approved first is the order the parent screen lists them in. */
        "CREATE INDEX IF NOT EXISTS idx_channels_added_at ON channels (added_at DESC)",
    ]

    /* The @handle a channel was approved from, when it was. Needed to answer
       "is the channel on this page already approved?" without a network round
       trip, since a /@handle URL carries no channel id. Nullable: channels
       approved from a /channel/UC… page have no handle. */
    static let v2 = ["ALTER TABLE channels ADD COLUMN handle TEXT"]

    /* The channel's avatar, so the approved list shows faces rather than a
       column of text. Nullable and cosmetic. */
    static let v3 = ["ALTER TABLE channels ADD COLUMN avatar_url TEXT"]

    /* The grid. `position` is the order the Worker sent, which is upload order;
       `published_at` is what the grid actually sorts on and is nullable — see
       Library.datePositions for why a video can arrive undated.

       `uploads_at` on channels is the once-a-day throttle. NULL means never,
       which is why a channel approved a moment ago fetches at once. */
    static let v4 = [
        """
        CREATE TABLE IF NOT EXISTS videos (
            video_id     TEXT PRIMARY KEY NOT NULL,
            channel_id   TEXT NOT NULL,
            title        TEXT NOT NULL,
            published_at INTEGER,
            thumb_url    TEXT,
            position     INTEGER NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_videos_channel ON videos (channel_id, position)",
        "CREATE INDEX IF NOT EXISTS idx_videos_published ON videos (published_at DESC)",
        "ALTER TABLE channels ADD COLUMN uploads_at INTEGER",
    ]

    /* What has been watched, so the approved list can be sorted by it. One row
       per play rather than a counter per channel: "most watched in the last 7
       days" cannot be answered by a running total.

       channel_id is denormalised out of `videos` at write time on purpose. The
       counting query has to work for a channel whose videos have since been
       replaced by a refresh; joining would quietly drop exactly the history
       that is oldest and therefore most likely to matter to the 365-day rung.

       It never leaves the device. */
    static let v5 = [
        """
        CREATE TABLE IF NOT EXISTS watches (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            channel_id TEXT NOT NULL,
            video_id   TEXT NOT NULL,
            watched_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_watches_at ON watches (watched_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_watches_channel ON watches (channel_id, watched_at DESC)",
    ]

    /* Named groups of channels. Mirrors Schema.kt's V6.

       The name is UNIQUE and NOCASE, which is the whole identity model: the
       dialog refuses a name already in use, so there is never a second
       "Cartoons" for a parent to tell apart, and NOCASE means the database
       refuses "cartoons" by the same rule ChannelGroups applies. The id is
       separate from the name so a rename would stay a one-row update if it is
       ever asked for; nothing renames one today.

       group_id on channels is nullable — a loose channel has none — and
       dissolving a group must leave its channels APPROVED and loose. That is
       why nothing cascades: a cascade here would delete the channels, which is
       to say silently un-approve them. */
    static let v6 = [
        """
        CREATE TABLE IF NOT EXISTS groups (
            group_id TEXT PRIMARY KEY NOT NULL,
            name     TEXT NOT NULL COLLATE NOCASE UNIQUE
        )
        """,
        "ALTER TABLE channels ADD COLUMN group_id TEXT",
        "CREATE INDEX IF NOT EXISTS idx_channels_group ON channels (group_id)",
    ]

    /* Every statement needed to move a database from `from` to `to`.
       from == 0 means a fresh install, which is just every version in order. */
    public static func statements(from: Int, to: Int) -> [String] {
        var out: [String] = []
        if from < 1 && to >= 1 { out += v1 }
        if from < 2 && to >= 2 { out += v2 }
        if from < 3 && to >= 3 { out += v3 }
        if from < 4 && to >= 4 { out += v4 }
        if from < 5 && to >= 5 { out += v5 }
        if from < 6 && to >= 6 { out += v6 }
        /* Later versions append their own block here. Nothing is ever edited in
           place: a device that already ran v1 will never run it again, so
           changing it only affects fresh installs and silently splits the
           schema in two. */
        return out
    }
}
