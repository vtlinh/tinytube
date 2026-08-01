package dev.vtlinh.tinytube

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/* A very small image loader for the grid's poster frames.

   Deliberately not a library. The grid holds a handful of tiles of one known
   size from one known host, which is a few dozen lines here against a whole
   image pipeline and its transitive dependencies in the APK. */
object Thumbnails {

    /* Roughly an eighth of the heap, the conventional split for a bitmap cache
       that is not the app's main memory consumer. */
    private val memory = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceAtLeast(4 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun cached(url: String): Bitmap? = memory.get(url)

    /* Drop these from the cache, because the channel they belong to is no
       longer approved.
     *
     * Memory only — this loader never writes to disk, so there is nothing else
     * of theirs on the device. Evicting anyway rather than waiting for the LRU
     * to age them out: "removed" should mean removed, and a poster from a
     * channel a parent has just taken away should not be sitting in the cache
     * ready to be drawn if some list asks for it again. */
    fun forget(urls: Collection<String>) {
        for (url in urls) if (url.isNotEmpty()) memory.remove(url)
    }

    suspend fun load(url: String): Bitmap? {
        memory.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val bytes = r.body?.bytes() ?: return@withContext null
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@withContext null
                    memory.put(url, bmp)
                    bmp
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /* Recycled views are the whole hazard here: a tile that scrolls away and
       comes back as a different video would show the previous poster when the
       old request finally lands. The view's tag records the URL its current
       request is for, and a result that no longer matches is dropped. */
    fun tagFor(view: ImageView, url: String) {
        view.setTag(R.id.thumb_url, url)
    }

    fun stillWanted(view: ImageView, url: String): Boolean =
        view.getTag(R.id.thumb_url) == url
}
