package dev.vtlinh.ytkids

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/* Recent uploads from every approved channel.

   Two sources, both needing no API key and no quota — which is what makes
   channel approval workable here at all. The playlist page carries the latest
   100 in upload order; the Atom feed carries about 15 WITH the upload times the
   grid sorts on, and is also the whole answer when the page yields nothing.
   Feed does the parsing and explains the trade.

   Both name the channel's UULF playlist rather than its UU one — the same
   uploads with Shorts taken out, by YouTube's own classification. That is the
   entirety of how Shorts stay off a child's screen; see YouTubeUrls.

   Worth being clear about what approving a channel therefore means: the grid
   will show that channel's NEW uploads as they appear, which no adult has
   looked at. Channel-level approval is a standing trust in the channel. */
object ChannelFeeds {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /* The playlist page is served to whoever asks, but WHAT is served depends
       on the user agent: a mobile one is redirected to m.youtube.com, whose
       page lists twenty videos and puts the rest behind a continuation. This
       asks as a desktop browser so the hundred-video page comes back.

       It is about two megabytes, and about three hundred kilobytes on the wire
       once gzipped — which OkHttp asks for and unwraps by itself. That is the
       price of the other 85 videos, per channel per refresh. */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    private fun cacheDir(context: Context) = File(context.filesDir, "feeds").apply { mkdirs() }

    /* The parsed list, one line per video — not the bytes it was parsed from.
       See Feed.encode. */
    private fun cacheFile(context: Context, channelId: String) =
        File(cacheDir(context), "$channelId.tsv")

    /* What builds before this one wrote: the Atom feed's raw XML. Still read,
       so the first launch after an update shows the grid it had rather than an
       empty screen while the network is asked. Deleted as soon as there is a
       .tsv to replace it. */
    private fun legacyCacheFile(context: Context, channelId: String) =
        File(cacheDir(context), "$channelId.xml")

    /* Whatever was last fetched, with no network.

       The cache is not an optimisation. A child opening the app on dropped
       wifi should still get the channels their parent approved, rather than an
       empty screen they have no way to interpret or fix. */
    fun cached(context: Context): List<Video> = Library.flatten(cachedByChannel(context))

    /* The same, but keeping which channel each video came from.
     *
     * A feed entry carries no channel id of its own — the id is the feed's,
     * not the video's — so the only place that association exists is here,
     * where the feed was fetched. The Channels tab needs it to show one
     * channel's uploads. Ordered like ChannelStore's list, newest-approved
     * first, which is what gives the grid its order. */
    fun cachedByChannel(context: Context): Map<String, List<Video>> {
        val out = LinkedHashMap<String, List<Video>>()
        for (channel in ChannelStore.get(context).all()) {
            readCache(context, channel.id)?.let { out[channel.id] = it }
        }
        return out
    }

    private fun readCache(context: Context, channelId: String): List<Video>? {
        val f = cacheFile(context, channelId)
        if (f.exists()) {
            return try { Feed.decode(f.readText()) } catch (e: Exception) { null }
        }
        val legacy = legacyCacheFile(context, channelId)
        if (legacy.exists()) {
            return try { Feed.parse(legacy.readText()) } catch (e: Exception) { null }
        }
        return null
    }

    private fun writeCache(context: Context, channelId: String, videos: List<Video>) {
        try {
            cacheFile(context, channelId).writeText(Feed.encode(videos))
            legacyCacheFile(context, channelId).delete()
        } catch (e: Exception) {}
    }

    /* Refresh every approved channel. Returns what we have afterwards —
       a channel whose fetch failed contributes its cached copy, so one dead
       feed doesn't empty the grid. */
    suspend fun refresh(context: Context): List<Video> = Library.flatten(refreshByChannel(context))

    suspend fun refreshByChannel(context: Context): Map<String, List<Video>> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, List<Video>>()
            for (channel in ChannelStore.get(context).all()) {
                val fetched = fetchUploads(channel.id)
                if (fetched.isNotEmpty()) {
                    /* only overwrite the cache once something has parsed */
                    writeCache(context, channel.id, fetched)
                    out[channel.id] = fetched
                } else {
                    readCache(context, channel.id)?.let { out[channel.id] = it }
                }
            }
            out
        }

    /* One channel's uploads, dated.
     *
     * BOTH sources, every time, because they know different halves. The page
     * gives a hundred videos in upload order and no dates; the ten-kilobyte
     * feed gives fifteen with real timestamps, which is what the grid sorts
     * on. Library.datePositions reconciles them.
     *
     * And the feed is the fallback as well: when the page yields nothing it
     * is the whole answer, at fifteen videos rather than none. That fallback
     * turns on the page's CONTENT rather than its status, because a page that
     * returns 200 and parses to nothing — a consent interstitial, a locale
     * that shapes the state differently, a rename of the entry we look for —
     * is indistinguishable from success at the HTTP layer.
     *
     * Neither source can carry a Short: both name the UULF playlist, which is
     * YouTube's own uploads list with Shorts taken out. */
    private fun fetchUploads(channelId: String): List<Video> {
        val dated = YouTubeUrls.feedUrl(channelId)?.let { get(it, null) }
            ?.let { Feed.parse(it) }
            .orEmpty()

        val page = YouTubeUrls.uploadsUrl(channelId)?.let { get(it, DESKTOP_UA) }
        val ordered = page?.let { Feed.parseUploadsPage(it) }.orEmpty()
        if (ordered.isEmpty()) return dated

        return Library.datePositions(
            ordered = ordered,
            dated = dated.mapNotNull { v -> v.publishedAt?.let { v.id to it } }.toMap(),
            fallback = System.currentTimeMillis() / 1000,
        )
    }

    private fun get(url: String, userAgent: String?): String? = try {
        val request = Request.Builder().url(url)
            .apply { if (userAgent != null) header("User-Agent", userAgent) }
            .build()
        client.newCall(request).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    /* Drop the cache for a channel that is no longer approved, so its videos
       stop appearing immediately rather than lingering until something else
       evicts them. */
    fun forget(context: Context, channelId: String) {
        try { cacheFile(context, channelId).delete() } catch (e: Exception) {}
        try { legacyCacheFile(context, channelId).delete() } catch (e: Exception) {}
    }
}
