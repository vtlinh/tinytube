package dev.vtlinh.ytkids

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/* The grid, on this device.

   This used to be a file of tab-separated lines per channel, next to the
   database. It is in the database now for one reason above the others: the
   Worker's reply is a delta, and answering "which videos do I already have"
   is a query rather than a file to re-read and re-parse. Rows and the channel
   they belong to also go together now — removing a channel removes its videos
   in the same place the approval lives.

   Not an optimisation, either way. A child opening the app on dropped wifi
   should still get the channels their parent approved rather than an empty
   screen they have no way to interpret or fix.

   The SQL lives in Schema.kt so it can be run against a real engine in a test;
   this file is only the Android plumbing around it. */
object VideoStore {

    private fun db(context: Context): SQLiteDatabase =
        ChannelStore.get(context).writableDatabase

    private val COLUMNS =
        arrayOf("video_id", "title", "published_at", "thumb_url", "channel_id")

    /* One channel's videos, in the order the Worker sent — which is upload
       order. The grid re-sorts by date across channels; the Channels tab shows
       one and this order is already right for it. */
    fun forChannel(context: Context, channelId: String): List<Video> = try {
        db(context).query(
            Schema.VIDEOS, COLUMNS, "channel_id = ?", arrayOf(channelId),
            null, null, "position ASC",
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.toVideo()) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /* Everything, grouped, in ChannelStore's order — which is what the grid
       and the Channels tab are both built from. */
    fun byChannel(context: Context): Map<String, List<Video>> {
        val out = LinkedHashMap<String, List<Video>>()
        for (channel in ChannelStore.get(context).all()) {
            val videos = forChannel(context, channel.id)
            if (videos.isNotEmpty()) out[channel.id] = videos
        }
        return out
    }

    /* Just the ids, which is what goes to the Worker so it can leave out the
       details we already have. */
    fun knownIds(context: Context, channelId: String): List<String> = try {
        db(context).query(
            Schema.VIDEOS, arrayOf("video_id"), "channel_id = ?", arrayOf(channelId),
            null, null, "position ASC",
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /* REPLACE the channel's list, rather than merge into it.
     *
     * The Worker's reply is the answer to "what does this channel have now",
     * so a video missing from it is a video the channel no longer lists — an
     * upload deleted, made private, or pushed past the hundred. Merging would
     * keep those forever and the grid would slowly fill with videos that no
     * longer exist, each one a tile that plays nothing.
     *
     * In one transaction: a delete that succeeded and an insert that did not
     * would empty a channel the child was looking at. */
    fun replace(context: Context, channelId: String, videos: List<Video>) {
        if (videos.isEmpty()) return
        val d = db(context)
        try {
            d.beginTransaction()
            d.delete(Schema.VIDEOS, "channel_id = ?", arrayOf(channelId))
            videos.forEachIndexed { position, v ->
                val values = ContentValues().apply {
                    put("video_id", v.id)
                    put("channel_id", channelId)
                    put("title", v.title)
                    v.publishedAt?.let { put("published_at", it) }
                    v.thumbUrl?.let { put("thumb_url", it) }
                    put("position", position)
                }
                /* CONFLICT_REPLACE on the id, because the same video appears in
                   two channels' lists after a collaboration and the primary key
                   is the video rather than the pair. Whichever channel was
                   written last owns the row; the grid collates by id anyway, so
                   the tile is the same either way. */
                d.insertWithOnConflict(
                    Schema.VIDEOS, null, values, SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            d.setTransactionSuccessful()
        } catch (e: Exception) {
            /* Leaves whatever was there. An empty grid is worse than a stale
               one. */
        } finally {
            try { d.endTransaction() } catch (e: Exception) {}
        }
    }

    /* A channel is no longer approved: its videos go with it, at once, rather
       than lingering until something else evicts them. */
    fun forget(context: Context, channelId: String) {
        try {
            db(context).delete(Schema.VIDEOS, "channel_id = ?", arrayOf(channelId))
        } catch (e: Exception) {}
    }

    private fun android.database.Cursor.toVideo() = Video(
        id = getString(0),
        title = getString(1),
        publishedAt = if (isNull(2)) null else getLong(2),
        thumbUrl = if (isNull(3)) null else getString(3),
    )
}
