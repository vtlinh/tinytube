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
class ChannelStore private constructor(private val app: Context) :
    SQLiteOpenHelper(app, Schema.DATABASE, null, Schema.VERSION) {

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

    /* Removing a channel removes EVERYTHING that came with it, here, in one
       place.
     *
     * This used to be three calls that every caller had to remember to make in
     * the right order — the channel row, then its videos, then its watch rows —
     * duplicated at each of the two places that remove one. That is how a
     * fourth thing gets forgotten, and a fourth thing had been: the poster
     * frames and the channel's avatar stayed in the image cache afterwards.
     *
     * The image URLs are read BEFORE the rows go, because reading them
     * afterwards finds nothing. */
    fun remove(channelId: String) {
        val images = imageUrlsFor(channelId)
        writableDatabase.delete(Schema.CHANNELS, "channel_id = ?", arrayOf(channelId))
        VideoStore.forget(app, channelId)
        WatchStore.forget(app, channelId)
        Thumbnails.forget(images)
        /* Removing a channel can strand the group it was in — a pair minus one
           is not a group. Same tidy every other writer ends with. */
        tidy()
    }

    /* Every picture this channel put on the device: its avatar, and a poster
       for each of its videos. */
    private fun imageUrlsFor(channelId: String): List<String> {
        val out = mutableListOf<String>()
        findByChannelId(channelId)?.avatarUrl?.let { out.add(it) }
        out += VideoStore.forChannel(app, channelId).map { it.thumbnailUrl }
        return out
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

    /* ---- groups ----
     *
     * ChannelGroups holds every RULE; this holds the rows. The split is the
     * usual one here: the rules are testable on a plain JVM and the SQL is not.
     *
     * ⚠️ EVERY MUTATION ENDS IN tidy(). A group of one is not a group, and the
     * ways to make one are easy to miss: removing a channel, ungrouping half a
     * group, or moving a member into a different group all strand whatever is
     * left. Putting it at the end of each writer rather than at the call sites
     * is what keeps that true for callers nobody has written yet. */
    fun groups(): List<ChannelGroups.Group> {
        val out = mutableListOf<ChannelGroups.Group>()
        readableDatabase.query(
            Schema.GROUPS, arrayOf("group_id", "name"), null, null, null, null, "name COLLATE NOCASE ASC",
        ).use { c ->
            while (c.moveToNext()) out.add(ChannelGroups.Group(c.getString(0), c.getString(1)))
        }
        return out
    }

    /* Put these channels in a group of this name, creating it.
     *
     * Returns false if the name is refused — the dialog checks first and
     * disables its confirm, so this is the second lock on the same door rather
     * than the only one. A UNIQUE index on the name is the third.
     *
     * Whatever group a channel was in, it leaves: the column is overwritten,
     * not merged. That is rule 7, and it needs no code of its own — but it does
     * need the tidy afterwards, because the group it left may now be down to
     * one channel. */
    fun group(channelIds: Collection<String>, name: String): Boolean {
        val trimmed = name.trim()
        if (channelIds.size < 2) return false

        val ids = channelIds.toSet()
        val all = all()
        val existing = groups()
        /* Judged against the names still IN USE afterwards, not every name
           there is. A group with every member in this selection is emptied by
           it, so it dissolves and its name comes free — which is what makes
           "add these to Cartoons" work at all. See ChannelGroups.namesInUse. */
        if (ChannelGroups.nameError(trimmed, ChannelGroups.namesInUse(existing, all, ids)) != null) {
            return false
        }

        /* And when that is the case, take the emptied group's ROW rather than
           inserting a second one: the name column is UNIQUE, so the insert
           would abort here and tidy() only removes the old group afterwards.
           Adding a channel to a group would fail with nothing to see. */
        val absorbed = ChannelGroups.absorbing(trimmed, existing, all, ids)
        val id = absorbed ?: java.util.UUID.randomUUID().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (absorbed == null) {
                db.insertWithOnConflict(
                    Schema.GROUPS, null,
                    ContentValues().apply {
                        put("group_id", id)
                        put("name", trimmed)
                    },
                    SQLiteDatabase.CONFLICT_ABORT,
                )
            }
            for (channelId in channelIds) {
                db.update(
                    Schema.CHANNELS,
                    ContentValues().apply { put("group_id", id) },
                    "channel_id = ?", arrayOf(channelId),
                )
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            return false
        } finally {
            db.endTransaction()
        }
        tidy()
        return true
    }

    /* Take these channels out of whatever group they are in. They stay
       approved — ungrouping is not removing, and conflating the two would be
       the worst possible reading of the word. */
    fun ungroup(channelIds: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (channelId in channelIds) {
                db.update(
                    Schema.CHANNELS,
                    ContentValues().apply { putNull("group_id") },
                    "channel_id = ?", arrayOf(channelId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        tidy()
    }

    /* The invariant, enforced after every change: a group with fewer than two
       channels is dissolved, and its remaining member — if any — becomes
       loose. The channels are never touched beyond that column. */
    private fun tidy() {
        val doomed = ChannelGroups.dissolving(all(), groups())
        if (doomed.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (groupId in doomed) {
                db.update(
                    Schema.CHANNELS,
                    ContentValues().apply { putNull("group_id") },
                    "group_id = ?", arrayOf(groupId),
                )
                db.delete(Schema.GROUPS, "group_id = ?", arrayOf(groupId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

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
        groupId = if (isNull(5)) null else getString(5),
    )

    companion object {
        private val COLUMNS =
            arrayOf("channel_id", "title", "added_at", "handle", "avatar_url", "group_id")

        @Volatile private var instance: ChannelStore? = null

        /* One helper for the process. Two SQLiteOpenHelper instances on the
           same file each keep their own connection and will lock each other
           out under concurrent writes. */
        /* No migration from the database an earlier build used — see the note
           on Schema.DATABASE. A device updating into this build starts with an
           empty approved list, which was the accepted cost of the rename. */
        fun get(context: Context): ChannelStore =
            instance ?: synchronized(this) {
                /* applicationContext HERE, because the instance is held for the
                   life of the process — handing it an Activity would keep that
                   Activity alive for just as long. It used to be unwrapped
                   inside the constructor; the store now keeps the context to
                   reach VideoStore and WatchStore on removal, so the unwrapping
                   has to happen before it is kept, not on the way past. */
                instance ?: ChannelStore(context.applicationContext).also { instance = it }
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
    /* Which group it belongs to, or null for a loose channel. A group always
       has at least two of these — see ChannelGroups. */
    val groupId: String? = null,
) {
    /* Where to send the parent's WebView to look at this channel again. The
       id is canonical and always works; a handle can be changed by its owner. */
    val url: String get() = "https://m.youtube.com/channel/$id"
}
