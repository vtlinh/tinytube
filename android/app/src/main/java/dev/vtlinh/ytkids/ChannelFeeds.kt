package dev.vtlinh.ytkids

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/* Recent uploads from every approved channel.

   The phone used to do this itself: fetch YouTube's uploads playlist page —
   about two megabytes, three hundred kilobytes gzipped — per channel, per
   refresh, and parse it here. That is now the Worker's job, and the phone asks
   it a question instead.

   The question carries the ids it already has, so the answer carries details
   only for what is new. A refresh that finds nothing new is about a kilobyte.
   A fresh channel is about fifteen. See Uploads for the shape of it.

   And at most once a day per channel. A channel-approval app is not a news
   feed: the cost of learning about a new upload eleven hours late is that it
   appears tomorrow, and the cost of asking every time the app is opened is
   somebody's data allowance. ChannelStore.uploadsFetchedAt is the clock, and a
   newly approved channel has none, so it fetches at once.

   None of it can carry a Short — the Worker asks for the channel's UULF
   playlist, which is YouTube's own uploads list with Shorts taken out.

   Curation has not moved. Which channels a child may watch is still SQLite on
   this device and nothing the Worker says can change it; this only answers
   what a channel the parent already approved has posted. */
object ChannelFeeds {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /* Once a day. Long enough that the answer is measured in requests per
       week rather than per screen unlock, short enough that a channel a child
       watches every day is never more than a day behind. */
    private const val REFRESH_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

    /* Whatever was last stored, with no network. */
    fun cached(context: Context): List<Video> = Library.flatten(cachedByChannel(context))

    /* The same, keeping which channel each video came from — the Channels tab
       needs it to show one channel's uploads, and the association exists only
       here because a video does not carry its channel. Ordered like
       ChannelStore's list, newest-approved first. */
    fun cachedByChannel(context: Context): Map<String, List<Video>> =
        VideoStore.byChannel(context)

    suspend fun refresh(context: Context): List<Video> = Library.flatten(refreshByChannel(context))

    suspend fun refreshByChannel(context: Context): Map<String, List<Video>> =
        withContext(Dispatchers.IO) {
            dropLegacyCache(context)
            val channels = ChannelStore.get(context)
            val now = System.currentTimeMillis()
            for (channel in channels.all()) {
                val last = channels.uploadsFetchedAt(channel.id)
                if (last != null && now - last < REFRESH_INTERVAL_MILLIS) continue

                val fetched = fetchUploads(context, channel.id)
                if (fetched.isEmpty()) continue
                VideoStore.replace(context, channel.id, fetched)
                /* Marked only on a fetch that produced something. A failed one
                   leaves the clock alone so the next foreground tries again,
                   rather than buying the outage a full day. */
                channels.markUploadsFetched(channel.id, now)
            }
            VideoStore.byChannel(context)
        }

    /* One channel's current list, as the Worker sees it.
     *
     * The bare ids in the reply are filled in from what is already stored, so
     * this needs both. An empty result means "learned nothing" — a Worker that
     * is down, a body that would not parse — and the caller keeps what it had
     * rather than emptying a grid a child is looking at.
     *
     * There is no direct-to-YouTube path here any more, and that is the point
     * of the change: the phone downloading and parsing two megabytes of
     * someone else's web app is exactly what moved. What it costs is that a
     * Worker outage means no NEW videos; the stored ones keep playing. */
    private fun fetchUploads(context: Context, channelId: String): List<Video> {
        if (!YouTubeUrls.isValidChannelId(channelId)) return emptyList()
        val known = VideoStore.forChannel(context, channelId).associateBy { it.id }
        val body = post(Endpoints.uploads(), Uploads.request(channelId, known.keys))
            ?: return emptyList()
        return Uploads.parse(body, known)
    }

    private fun post(url: String, body: String): String? = try {
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON)).build()
        client.newCall(request).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    /* Drop a channel that is no longer approved. */
    fun forget(context: Context, channelId: String) {
        VideoStore.forget(context, channelId)
    }

    /* The grid used to be files under filesDir/feeds — raw Atom XML, and later
       tab-separated lines, one file per channel. It is in the database now.
     *
       Deleted rather than left: an app's own storage counts against the
       device, and a directory of files nothing reads is exactly the kind of
       thing that is still there in a year. Nothing is migrated out of it
       first — the first refresh fills the database from the Worker, and until
       it lands the grid is the empty one a fresh install shows. */
    private fun dropLegacyCache(context: Context) {
        try {
            val dir = java.io.File(context.filesDir, "feeds")
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        } catch (e: Exception) {}
    }
}
