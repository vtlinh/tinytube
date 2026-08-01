package dev.vtlinh.tinytube

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/* The approved-channel list, on this device.

   Unlike catalog.json — which is curated in the repository and shared by every
   install — this is per-device and belongs to whoever holds the phone. The two
   are deliberately separate: nothing a parent approves here can be undone by a
   deploy, and nothing removed from the repo silently strips a channel they
   added themselves.

   The SQL lives in Schema.kt so it can be run against a real engine in a test;
   this file is only the Android plumbing around it. */
class ChannelStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, Schema.DATABASE, null, Schema.VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        for (sql in Schema.statementsFor(0, Schema.VERSION)) db.execSQL(sql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (sql in Schema.statementsFor(oldVersion, newVersion)) db.execSQL(sql)
    }

    /* Approve a channel, or refresh the title of one already approved.
       Returns false for an id that isn't well-formed rather than storing it —
       a bad id here becomes a feed URL later.

       `handle` is whatever @name the parent approved from, when there was one.
       It is remembered so the approve button can tell, with no network call,
       whether the /@handle page it is looking at is already on the list. */
    fun add(
        channelId: String,
        title: String,
        handle: String?,
        avatarUrl: String?,
        nowMillis: Long,
    ): Boolean {
        if (!YouTubeUrls.isValidChannelId(channelId)) return false
        val previous = findByChannelId(channelId)
        val values = ContentValues().apply {
            put("channel_id", channelId)
            put("title", title.trim().ifEmpty { channelId })
            put("added_at", nowMillis)
            /* Don't overwrite what we already know with nothing: approving the
               same channel again from its /channel/UC… page carries no handle,
               and would otherwise erase the one recorded the first time. */
            (handle ?: previous?.handle)?.let { put("handle", it) }
            (avatarUrl ?: previous?.avatarUrl)?.let { put("avatar_url", it) }
        }
        return writableDatabase.insertWithOnConflict(
            Schema.CHANNELS, null, values, SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L
    }

    /* When this channel's uploads were last fetched, or null for never.
     *
     * The deep fetch happens at most once a day per channel; this is what
     * remembers when. Null is deliberate rather than zero: a channel approved
     * a moment ago has never been fetched and must fetch now, and "never" and
     * "in 1970" should not have to be the same value for that to work.
     *
     * Note that add() REPLACEs the row, which clears this. Re-approving a
     * channel therefore refetches it — which is what someone re-adding one
     * would expect. */
    fun uploadsFetchedAt(channelId: String): Long? =
        readableDatabase.query(
            Schema.CHANNELS, arrayOf("uploads_at"), "channel_id = ?", arrayOf(channelId),
            null, null, null, "1",
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun markUploadsFetched(channelId: String, nowMillis: Long) {
        val values = ContentValues().apply { put("uploads_at", nowMillis) }
        writableDatabase.update(Schema.CHANNELS, values, "channel_id = ?", arrayOf(channelId))
    }

    /* Fill in an avatar for a row approved before we recorded them. Only ever
       adds: it never clears one, so a failed lookup leaves what was there. */
    fun setAvatar(channelId: String, avatarUrl: String) {
        val values = ContentValues().apply { put("avatar_url", avatarUrl) }
        writableDatabase.update(
            Schema.CHANNELS, values, "channel_id = ?", arrayOf(channelId),
        )
    }

    fun remove(channelId: String) {
        writableDatabase.delete(Schema.CHANNELS, "channel_id = ?", arrayOf(channelId))
    }

    fun contains(channelId: String): Boolean = findByChannelId(channelId) != null

    fun findByChannelId(channelId: String): Channel? =
        queryOne("channel_id = ?", arrayOf(channelId))

    /* Handles are case-insensitive on YouTube: /@SomeChannel and
       /@somechannel are the same place, and matching them case-sensitively
       would offer to approve a channel that is already on the list. */
    fun findByHandle(handle: String): Channel? =
        queryOne("handle IS NOT NULL AND handle = ? COLLATE NOCASE", arrayOf(handle))

    private fun queryOne(where: String, args: Array<String>): Channel? =
        readableDatabase.query(
            Schema.CHANNELS, COLUMNS, where, args, null, null, null, "1",
        ).use { c -> if (c.moveToFirst()) c.toChannel() else null }

    /* Newest approval first — that's the order a parent reviewing the list
       expects, and the one the index is built for. */
    fun all(): List<Channel> {
        val out = mutableListOf<Channel>()
        readableDatabase.query(
            Schema.CHANNELS, COLUMNS, null, null, null, null, "added_at DESC",
        ).use { c ->
            while (c.moveToNext()) out.add(c.toChannel())
        }
        return out
    }

    private fun android.database.Cursor.toChannel() = Channel(
        id = getString(0),
        title = getString(1),
        addedAt = getLong(2),
        handle = if (isNull(3)) null else getString(3),
        avatarUrl = if (isNull(4)) null else getString(4),
    )

    companion object {
        private val COLUMNS = arrayOf("channel_id", "title", "added_at", "handle", "avatar_url")

        @Volatile private var instance: ChannelStore? = null

        /* One helper for the process. Two SQLiteOpenHelper instances on the
           same file each keep their own connection and will lock each other
           out under concurrent writes. */
        fun get(context: Context): ChannelStore =
            instance ?: synchronized(this) {
                instance ?: run {
                    /* Before ANY helper touches the file. Every store in the
                       app — VideoStore, WatchStore — opens the database through
                       this method, which is what makes one call here enough. */
                    renameLegacyDatabase(context.applicationContext)
                    ChannelStore(context).also { instance = it }
                }
            }

        /* Move the database a previous build left under a different name.
         *
         * A device that ran an earlier build holds the approved channels, the
         * grid and the watch history in a file named by that build.
         * SQLiteOpenHelper would create a fresh, empty one beside it and the app
         * would come up with nothing — the data present on disk and never looked
         * for. So the file moves to the current name first.
         *
         * The old name is FOUND, not remembered. This app has only ever created
         * one database, so within its own databases directory the single .db
         * that is not the current name is the one to adopt. That is also why the
         * previous name appears nowhere in this repository.
         *
         * If there is more than one, nothing is touched: that is a situation
         * this code has no way to be right about, and guessing would mean
         * overwriting somebody's data with somebody else's.
         *
         * Runs at most once per install: afterwards there is no other database
         * to find. Every failure is survivable and none is worth crashing for —
         * a failed move leaves the app exactly where it would have been without
         * any of this, and a half-finished one is retried next launch because
         * the database itself moves last. */
        private fun renameLegacyDatabase(context: Context) {
            val current = context.getDatabasePath(Schema.DATABASE)
            /* Already migrated, or a fresh install. Never overwrite a live
               database with an older one. */
            if (current.exists()) return

            val dir = current.parentFile ?: return
            val candidates = dir.listFiles { f ->
                f.isFile && f.name.endsWith(Schema.SUFFIX) && f.name != current.name
            } ?: return
            val legacy = candidates.singleOrNull() ?: return

            for (suffix in Schema.CARRIED_SUFFIXES) {
                val from = java.io.File(legacy.path + suffix)
                if (!from.exists()) continue
                try {
                    from.renameTo(java.io.File(current.path + suffix))
                } catch (e: Exception) {
                    /* Leave whatever moved where it is. The next launch retries,
                       and the database moving last is what makes that retry
                       meaningful. */
                }
            }
        }
    }
}

data class Channel(
    val id: String,
    val title: String,
    val addedAt: Long,
    /* the @name it was approved from, when there was one */
    val handle: String? = null,
    /* the channel's picture, when we managed to find it */
    val avatarUrl: String? = null,
) {
    /* Where to send the parent's WebView to look at this channel again. The
       id is canonical and always works; a handle can be changed by its owner. */
    val url: String get() = "https://m.youtube.com/channel/$id"
}
