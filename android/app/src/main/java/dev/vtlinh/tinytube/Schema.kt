package dev.vtlinh.tinytube

/* The on-device database of parent-approved channels.

   The statements live here, free of Android, so the exact list the app runs on
   a device can be run against a real SQLite engine in a unit test (SchemaTest,
   via sqlite-jdbc). Migrations execute once, on the only copy of the approved
   list a parent has; a typo in one of them is not something to discover on a
   device. */
object Schema {

    /* ⚠️ THE FILENAME STAYS "ytkids.db". IT IS NOT A LABEL, IT IS AN ADDRESS.
       This is the name of the SQLite file sitting on every phone that has ever
       run this app. Rename it and the app opens a new, empty database: every
       approved channel, every video in the grid and all of the watch history
       become unreachable — not deleted, just no longer looked for — and the
       parental control silently resets to nothing on next launch.

       There would be no error and no crash. A child would simply be shown an
       empty grid, and an adult would have to approve everything again.

       That is the same class of breakage as renaming the applicationId or the
       Worker, which cost every installed phone its channels once already — see
       README's Naming. A rename sweep across this repository has come past here
       once. Leave it.

       iOS uses "tinytube.sqlite" in Schema.swift, and that divergence is fine:
       no iOS build has ever shipped, so nothing there has a file to lose. The
       two platforms need not agree on a filename; they need to agree on the
       LADDER, which is what SchemaTest and SchemaTests check. */
    const val DATABASE = "ytkids.db"
    const val VERSION = 5

    const val CHANNELS = "channels"
    const val VIDEOS = "videos"
    const val WATCHES = "watches"

    private val V1 = listOf(
        """
        CREATE TABLE IF NOT EXISTS channels (
            channel_id TEXT PRIMARY KEY NOT NULL,
            title      TEXT NOT NULL,
            added_at   INTEGER NOT NULL
        )
        """.trimIndent(),
        /* Newest-approved first is the order the parent screen lists them in. */
        "CREATE INDEX IF NOT EXISTS idx_channels_added_at ON channels (added_at DESC)",
    )

    /* The @handle a channel was approved from, when it was.

       Needed to answer "is the channel on this page already approved?" without
       a network round trip. A /@handle URL carries no channel id, and going to
       the network to find out would mean the approve button flickered between
       states on every navigation — so the handle is remembered at approval
       time and matched directly.

       Nullable: channels approved from a /channel/UC… page have no handle, and
       so do rows written before this column existed. */
    private val V2 = listOf(
        "ALTER TABLE channels ADD COLUMN handle TEXT",
    )

    /* The channel's avatar, so the approved list shows faces rather than a
       column of text. Nullable: it is cosmetic, it isn't always found, and
       rows approved before this column existed have none until the list
       backfills them. */
    private val V3 = listOf(
        "ALTER TABLE channels ADD COLUMN avatar_url TEXT",
    )

    /* The grid itself, which used to be a file of tab-separated lines next to
       the database and is now in it.
     *
       Moving it here is what lets the app tell the Worker which videos it
       already has, which is what keeps a refresh at a kilobyte instead of two
       megabytes. It also means one store rather than two: a channel's rows go
       when the channel does, in the same place the approval lives.

       `position` is the order the Worker sent, kept because it is upload order
       and the sort key below is derived from it. `published_at` is what the
       grid actually sorts on and is nullable — see Video and
       Library.datePositions for why a video can arrive undated.

       `uploads_at` on channels is the throttle: the deep fetch happens at most
       once a day per channel, and this is what remembers when. NULL means
       never, which is why a channel approved a moment ago fetches at once.
       A re-approval REPLACEs the row and clears it, so re-adding a channel
       also refetches — which is what someone re-adding one would expect. */
    private val V4 = listOf(
        """
        CREATE TABLE IF NOT EXISTS videos (
            video_id     TEXT PRIMARY KEY NOT NULL,
            channel_id   TEXT NOT NULL,
            title        TEXT NOT NULL,
            published_at INTEGER,
            thumb_url    TEXT,
            position     INTEGER NOT NULL
        )
        """.trimIndent(),
        /* Both reads this table has: one channel's videos in order, and
           everything in date order for the grid. */
        "CREATE INDEX IF NOT EXISTS idx_videos_channel ON videos (channel_id, position)",
        "CREATE INDEX IF NOT EXISTS idx_videos_published ON videos (published_at DESC)",
        "ALTER TABLE channels ADD COLUMN uploads_at INTEGER",
    )

    /* What has been watched, so the approved list can be sorted by it.
     *
       One row per play, rather than a counter per channel: "most watched in the
       last 7 days" cannot be answered by a running total, and a total is what
       you end up stuck with the first time somebody wants a different window.
       Rows are pruned by age — see WatchStore — so this does not grow forever.

       channel_id is denormalised out of `videos` at write time on purpose. The
       counting query has to work for a channel whose videos have since been
       replaced by a refresh, or removed by the uploader; joining to `videos`
       would quietly drop exactly the history that is oldest and therefore most
       likely to matter to the 365-day rung.

       It never leaves the device. Nothing uploads it, the Worker is never told
       what was played, and removing a channel removes its rows. */
    private val V5 = listOf(
        """
        CREATE TABLE IF NOT EXISTS watches (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            channel_id TEXT NOT NULL,
            video_id   TEXT NOT NULL,
            watched_at INTEGER NOT NULL
        )
        """.trimIndent(),
        /* Every read is "since when", and the prune is "older than". */
        "CREATE INDEX IF NOT EXISTS idx_watches_at ON watches (watched_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_watches_channel ON watches (channel_id, watched_at DESC)",
    )

    /* Every statement needed to move a database from `from` to `to`.
       from == 0 means a fresh install, which is just every version in order. */
    fun statementsFor(from: Int, to: Int): List<String> {
        val out = mutableListOf<String>()
        if (from < 1 && to >= 1) out += V1
        if (from < 2 && to >= 2) out += V2
        if (from < 3 && to >= 3) out += V3
        if (from < 4 && to >= 4) out += V4
        if (from < 5 && to >= 5) out += V5
        /* Later versions append their own block here. Nothing is ever edited
           in place: a device that already ran V1 will never run it again, so
           changing it only affects fresh installs and silently splits the
           schema in two. */
        return out
    }
}
