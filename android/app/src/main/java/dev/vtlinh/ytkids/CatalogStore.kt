package dev.vtlinh.ytkids

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/* Fetches the approved-video list from the Worker and keeps the last good copy
   on disk.

   The cache is not an optimisation. A child opening the app on a dropped wifi
   connection should still get the videos that were approved yesterday, rather
   than an empty screen they have no way to interpret or fix. So the network is
   an attempt to refresh, never a precondition for showing anything. */
object CatalogStore {

    private const val CACHE_NAME = "catalog.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_NAME)

    /* Whatever was last successfully fetched. Empty before the first fetch. */
    fun cached(context: Context): List<Video> {
        val f = cacheFile(context)
        if (!f.exists()) return emptyList()
        return try {
            Catalog.parse(f.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /* Refresh from the Worker. Returns the new list, or null if the fetch
       failed or came back as something that parses to nothing.

       A response that parses to zero videos is treated as a failure rather than
       as "the parent removed everything": a captive portal, a truncated body
       and a half-deployed Worker all produce exactly that, and wiping a working
       catalog because of one bad response is a worse outcome than briefly
       showing a stale one. Emptying the catalog on purpose therefore needs the
       cache cleared, which `Clear storage` does. */
    suspend fun refresh(context: Context): List<Video>? = withContext(Dispatchers.IO) {
        val body = try {
            client.newCall(Request.Builder().url(Endpoints.CATALOG).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                r.body?.string() ?: return@withContext null
            }
        } catch (e: Exception) {
            return@withContext null
        }
        val parsed = Catalog.parse(body)
        if (parsed.isEmpty()) return@withContext null
        /* only overwrite the cache once the bytes are known to parse */
        try { cacheFile(context).writeText(body) } catch (e: Exception) {}
        parsed
    }
}
