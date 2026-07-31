package dev.vtlinh.ytkids

/* The on-device database of parent-approved channels.

   The statements live here, free of Android, so the exact list the app runs on
   a device can be run against a real SQLite engine in a unit test (SchemaTest,
   via sqlite-jdbc). Migrations execute once, on the only copy of the approved
   list a parent has; a typo in one of them is not something to discover on a
   device. */
object Schema {

    const val DATABASE = "ytkids.db"
    const val VERSION = 3

    const val CHANNELS = "channels"

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

    /* Every statement needed to move a database from `from` to `to`.
       from == 0 means a fresh install, which is just every version in order. */
    fun statementsFor(from: Int, to: Int): List<String> {
        val out = mutableListOf<String>()
        if (from < 1 && to >= 1) out += V1
        if (from < 2 && to >= 2) out += V2
        if (from < 3 && to >= 3) out += V3
        /* Later versions append their own block here. Nothing is ever edited
           in place: a device that already ran V1 will never run it again, so
           changing it only affects fresh installs and silently splits the
           schema in two. */
        return out
    }
}
