package dev.vtlinh.ytkids

/* The on-device database of parent-approved channels.

   The statements live here, free of Android, so the exact list the app runs on
   a device can be run against a real SQLite engine in a unit test (SchemaTest,
   via sqlite-jdbc). Migrations execute once, on the only copy of the approved
   list a parent has; a typo in one of them is not something to discover on a
   device. */
object Schema {

    const val DATABASE = "ytkids.db"
    const val VERSION = 1

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

    /* Every statement needed to move a database from `from` to `to`.
       from == 0 means a fresh install, which is just every version in order. */
    fun statementsFor(from: Int, to: Int): List<String> {
        val out = mutableListOf<String>()
        if (from < 1 && to >= 1) out += V1
        /* Later versions append their own block here. Nothing is ever edited
           in place: a device that already ran V1 will never run it again, so
           changing it only affects fresh installs and silently splits the
           schema in two. */
        return out
    }
}
