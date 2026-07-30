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
       a bad id here becomes a feed URL later. */
    fun add(channelId: String, title: String, nowMillis: Long): Boolean {
        if (!YouTubeUrls.isValidChannelId(channelId)) return false
        val values = ContentValues().apply {
            put("channel_id", channelId)
            put("title", title.trim().ifEmpty { channelId })
            put("added_at", nowMillis)
        }
        return writableDatabase.insertWithOnConflict(
            Schema.CHANNELS, null, values, SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L
    }

    fun remove(channelId: String) {
        writableDatabase.delete(Schema.CHANNELS, "channel_id = ?", arrayOf(channelId))
    }

    fun contains(channelId: String): Boolean =
        readableDatabase.query(
            Schema.CHANNELS, arrayOf("channel_id"), "channel_id = ?",
            arrayOf(channelId), null, null, null, "1",
        ).use { it.moveToFirst() }

    /* Newest approval first — that's the order a parent reviewing the list
       expects, and the one the index is built for. */
    fun all(): List<Channel> {
        val out = mutableListOf<Channel>()
        readableDatabase.query(
            Schema.CHANNELS, arrayOf("channel_id", "title", "added_at"),
            null, null, null, null, "added_at DESC",
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Channel(id = c.getString(0), title = c.getString(1), addedAt = c.getLong(2)))
            }
        }
        return out
    }

    companion object {
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

data class Channel(val id: String, val title: String, val addedAt: Long)
