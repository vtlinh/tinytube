package dev.vtlinh.tinytube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/* Works out which channel the parent is currently looking at.
 *
 * A /channel/UC… URL carries the id outright. Most YouTube URLs don't — they
 * use an @handle, and a handle cannot be turned into an id locally.
 *
 * THE PHONE NO LONGER FETCHES THE PAGE TO FIND OUT. It used to: this file
 * downloaded a full desktop channel page — megabytes of somebody else's web app
 * — to read one 24-character string out of it, on a parent's data, every time
 * they approved anything. That is the same trade the uploads parsing already
 * made, and it is made here now too: the Worker fetches and reads, the phone
 * sends an identifier and gets an answer back.
 *
 * What is sent is THE URL the app is standing on, as-is — see the note further
 * down. That is not a way to point the Worker at an arbitrary host: the string
 * is never fetched. The Worker parses it, demands a YouTube channel-page host
 * and a path of exactly /channel/UC… or /@handle, and BUILDS the address it
 * fetches from what it extracted. See the note above channel() in worker.js.
 *
 * Curation has not moved. The reply says which channel a page is for; whether a
 * child may watch it is ChannelStore, on this device. */
object ChannelResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Resolved(
        val id: String,
        val title: String,
        val avatarUrl: String?,
        /* The channel's uploads, in the same reply. Approving needs the id, the
           name AND the first hundred videos, and asking for those separately
           meant two round trips with a parent watching a spinner through both.
           Empty means the Worker could not tell — which VideoStore.replace
           treats as "change nothing", not as "this channel has none". */
        val videos: List<Video> = emptyList(),
    )

    /* Null when this page isn't a channel we can identify — a search results
       page, the home feed, a settings screen. The caller says so rather than
       approving something arbitrary. */
    suspend fun resolve(url: String): Resolved? = withContext(Dispatchers.IO) {
        if (!YouTubeUrls.isParentBrowsable(url)) return@withContext null

        /* THE URL GOES AS-IS. The app does not pick the handle or id out of it
           first — reading YouTube is the Worker's job, and an address is
           something to read. The Worker validates the host and path and REBUILDS
           what it fetches, so nothing sent from here reaches fetch() verbatim.

           The id that comes back is still checked below. That has not moved and
           should not: it becomes a database primary key and a request
           parameter. */
        val direct = YouTubeUrls.channelIdFromUrl(url)
        val request = JSONObject().put("url", url).toString()

        val body = post(request) ?: run {
            /* The Worker was unreachable, but if the id was in the URL all
               along we can still approve it — just without a name or videos.
               The daily refresh will fill them in. */
            return@withContext direct?.let { Resolved(it, it, null) }
        }

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return@withContext null
        }

        /* Re-validated on arrival, exactly as Uploads.parse re-validates video
           ids. This one becomes a primary key and a request parameter, and the
           avatar is stored and then fetched and drawn; "our own server said so"
           is not the same assurance as a check at the point of use. */
        val id = json.optString("id").takeIf { YouTubeUrls.isValidChannelId(it) }
            ?: return@withContext null
        val title = json.optString("title").takeIf { it.isNotBlank() } ?: id
        val avatar = json.optString("avatarUrl")
            .takeIf { it.isNotBlank() && YouTubeUrls.isAllowedAvatar(it) }

        /* Straight through the same parser the uploads reply uses, so every id
           and thumbnail host is re-validated exactly as it would be there.
           `known` is empty: a channel being approved has nothing stored yet. */
        Resolved(id, title, avatar, Uploads.parse(body, emptyMap()))
    }

    private fun post(body: String): String? = try {
        client.newCall(
            Request.Builder()
                .url(Endpoints.channel())
                .post(body.toRequestBody(JSON))
                .build(),
        ).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }
}
