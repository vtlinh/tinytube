package dev.vtlinh.ytkids

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
    fun add(channelId: String, title: String, handle: String?, nowMillis: Long): Boolean {
        if (!YouTubeUrls.isValidChannelId(channelId)) return false
        val values = ContentValues().apply {
            put("channel_id", channelId)
            put("title", title.trim().ifEmpty { channelId })
            put("added_at", nowMillis)
            /* Don't overwrite a known handle with nothing: approving the same
               channel again from its /channel/UC… page would otherwise erase
               the handle recorded the first time. */
            val existing = handle ?: findByChannelId(channelId)?.handle
            if (existing != null) put("handle", existing)
        }
        return writableDatabase.insertWithOnConflict(
            Schema.CHANNELS, null, values, SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L
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
    )

    companion object {
        private val COLUMNS = arrayOf("channel_id", "title", "added_at", "handle")

        @Volatile private var instance: ChannelStore? = null

        /* One helper for the process. Two SQLiteOpenHelper instances on the
           same file each keep their own connection and will lock each other
           out under concurrent writes. */
        fun get(context: Context): ChannelStore =
            instance ?: synchronized(this) {
                instance ?: ChannelStore(context).also { instance = it }
            }
    }
}

data class Channel(
    val id: String,
    val title: String,
    val addedAt: Long,
    /* the @name it was approved from, when there was one */
    val handle: String? = null,
) {
    /* Where to send the parent's WebView to look at this channel again. The
       id is canonical and always works; a handle can be changed by its owner. */
    val url: String get() = "https://m.youtube.com/channel/$id"
}
