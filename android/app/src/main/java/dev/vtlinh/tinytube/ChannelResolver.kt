package dev.vtlinh.tinytube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/* Works out which channel the parent is currently looking at.
 *
 * A /channel/UC… URL carries the id outright. Most YouTube URLs don't — they
 * use an @handle, or they're a watch page whose uploader is the channel we
 * want — and a handle cannot be turned into an id locally. So the page itself
 * is fetched and read.
 *
 * All the reading is in YouTubeUrls, where it is tested; this is the fetch. */
object ChannelResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /* Desktop UA on purpose: the mobile pages the WebView browses are lighter
       and don't always carry the canonical channel link we read. */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    data class Resolved(val id: String, val title: String, val avatarUrl: String?)

    /* Null when this page isn't a channel we can identify — a search results
       page, the home feed, a settings screen. The caller says so rather than
       approving something arbitrary. */
    suspend fun resolve(url: String): Resolved? = withContext(Dispatchers.IO) {
        if (!YouTubeUrls.isParentBrowsable(url)) return@withContext null

        val html = fetch(url) ?: run {
            /* The fetch failed, but if the id was in the URL all along we can
               still approve it — just without a nice name. */
            val direct = YouTubeUrls.channelIdFromUrl(url)
            return@withContext direct?.let { Resolved(it, it, null) }
        }

        val id = YouTubeUrls.channelIdFromUrl(url)
            ?: YouTubeUrls.channelIdFromHtml(html)
            ?: return@withContext null

        val title = YouTubeUrls.channelTitleFromHtml(html) ?: id
        Resolved(id, title, YouTubeUrls.channelAvatarFromHtml(html))
    }

    private fun fetch(url: String): String? = try {
        client.newCall(
            Request.Builder().url(url).header("User-Agent", UA).build(),
        ).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }
}
