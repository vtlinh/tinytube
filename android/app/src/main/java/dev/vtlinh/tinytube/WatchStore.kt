package dev.vtlinh.tinytube

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/* What has been watched, on this device.

   Written when a video actually starts playing rather than when a tile is
   tapped: a video opened and abandoned before the first frame is not something
   anybody watched, and the difference matters to a list sorted by this.

   It exists for one feature — sorting the approved channels by what is
   actually being watched — and it stays that small. It is never uploaded, the
   Worker is never told what was played, removing a channel removes its rows,
   and rows older than the widest window this can be asked about are deleted.

   ChannelSort decides which window applies and how the list is ordered; this
   only counts. The SQL lives in Schema.kt so it can be run against a real
   engine in a test. */
object WatchStore {

    /* Beyond the widest rung of ChannelSort's ladder, nothing can ever read a
       row again. A fortnight of slack so a device whose clock moved does not
       lose the year it was supposed to keep. */
    private val KEEP_MILLIS = (ChannelSort.WINDOWS_DAYS.max() + 14) * 24L * 60 * 60 * 1000

    private fun db(context: Context): SQLiteDatabase =
        ChannelStore.get(context).writableDatabase

    /* Note the video, and the channel it came from.
     *
     * The channel is looked up here and stored alongside, rather than joined
     * later: a refresh replaces a channel's videos and an uploader can delete
     * one, and a join would then drop exactly the oldest history — which is
     * the history the 365-day rung is for. A video we cannot place is not
     * recorded at all; an unattributed play cannot be counted towards
     * anything. */
    fun record(context: Context, videoId: String, nowMillis: Long) {
        if (!VideoId.isValid(videoId)) return
        try {
            val d = db(context)
            val channelId = d.query(
                Schema.VIDEOS, arrayOf("channel_id"), "video_id = ?", arrayOf(videoId),
                null, null, null, "1",
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return

            d.insert(
                Schema.WATCHES, null,
                ContentValues().apply {
                    put("channel_id", channelId)
                    put("video_id", videoId)
                    put("watched_at", nowMillis)
                },
            )
            prune(d, nowMillis)
        } catch (e: Exception) {
            /* History is a nice-to-have. Failing to write one is not worth
               interrupting a video for. */
        }
    }

    private fun prune(d: SQLiteDatabase, nowMillis: Long) {
        try {
            d.delete(Schema.WATCHES, "watched_at < ?", arrayOf((nowMillis - KEEP_MILLIS).toString()))
        } catch (e: Exception) {}
    }

    /* How many plays each channel has had since a moment. Channels with none
       are absent rather than zero, which is what lets ChannelSort tell an
       empty window from a full one. */
    fun countsSince(context: Context, sinceMillis: Long): Map<String, Int> = try {
        db(context).rawQuery(
            "SELECT channel_id, COUNT(*) FROM ${Schema.WATCHES} WHERE watched_at >= ? " +
                "GROUP BY channel_id",
            arrayOf(sinceMillis.toString()),
        ).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    /* One map per rung of ChannelSort's ladder, in its order. Read together so
       the ladder is walked over a consistent view rather than three queries a
       play could land between. */
    fun countsByWindow(context: Context, nowMillis: Long): List<Map<String, Int>> =
        ChannelSort.WINDOWS_DAYS.map { days ->
            countsSince(context, nowMillis - days * 24L * 60 * 60 * 1000)
        }

    /* A channel is no longer approved: what was watched on it goes too. */
    fun forget(context: Context, channelId: String) {
        try {
            db(context).delete(Schema.WATCHES, "channel_id = ?", arrayOf(channelId))
        } catch (e: Exception) {}
    }
}
