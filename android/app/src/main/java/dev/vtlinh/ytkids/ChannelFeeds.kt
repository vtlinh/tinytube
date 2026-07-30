package dev.vtlinh.ytkids

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/* Recent uploads from every approved channel.

   YouTube publishes a per-channel Atom feed that needs no API key and no
   quota, which is what makes channel approval workable without a Data API key.
   It carries roughly the latest 15 uploads and nothing older.

   Worth being clear about what approving a channel therefore means: the grid
   will show that channel's NEW uploads as they appear, which no adult has
   looked at. Video-level entries in catalog.json are reviewed; channel-level
   approval is a standing trust in the channel. */
object ChannelFeeds {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun cacheDir(context: Context) = File(context.filesDir, "feeds").apply { mkdirs() }
    private fun cacheFile(context: Context, channelId: String) = File(cacheDir(context), "$channelId.xml")

    /* Whatever was last fetched, with no network. Same reason CatalogStore
       caches: a child opening the app on dropped wifi should still see what
       was approved, not an empty screen they can't interpret. */
    fun cached(context: Context): List<Video> {
        val out = mutableListOf<Video>()
        for (channel in ChannelStore.get(context).all()) {
            val f = cacheFile(context, channel.id)
            if (!f.exists()) continue
            try { out += Feed.parse(f.readText()) } catch (e: Exception) {}
        }
        return out
    }

    /* Refresh every approved channel. Returns what we have afterwards —
       a channel whose fetch failed contributes its cached copy, so one dead
       feed doesn't empty the grid. */
    suspend fun refresh(context: Context): List<Video> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Video>()
        for (channel in ChannelStore.get(context).all()) {
            val url = YouTubeUrls.feedUrl(channel.id) ?: continue
            val body = try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            } catch (e: Exception) {
                null
            }
            val parsed = body?.let { Feed.parse(it) }
            if (parsed != null && parsed.isNotEmpty()) {
                /* only overwrite the cache once the bytes are known to parse */
                try { cacheFile(context, channel.id).writeText(body) } catch (e: Exception) {}
                out += parsed
            } else {
                val f = cacheFile(context, channel.id)
                if (f.exists()) {
                    try { out += Feed.parse(f.readText()) } catch (e: Exception) {}
                }
            }
        }
        out
    }

    /* Drop the cache for a channel that is no longer approved, so its videos
       stop appearing immediately rather than lingering until something else
       evicts them. */
    fun forget(context: Context, channelId: String) {
        try { cacheFile(context, channelId).delete() } catch (e: Exception) {}
    }
}
