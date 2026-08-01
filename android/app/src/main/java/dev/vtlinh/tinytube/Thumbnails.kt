package dev.vtlinh.tinytube

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/* A very small image loader for the grid's poster frames and channel avatars.

   Deliberately not a library. The grid holds a handful of tiles of one known
   size from one known host, which is a few dozen lines here against a whole
   image pipeline and its transitive dependencies in the APK.

   ⚠️ MEMORY AND DISK, and the disk half has no expiry at all. A URL that has
   been fetched once is never fetched again — not re-validated, not refreshed,
   not aged out. Two consequences, both deliberate:

     - The grid draws with no network. Every launch used to start with an empty
       LruCache, so a phone in a car with no signal showed a wall of blank
       tiles, and a phone with signal re-downloaded every poster it had already
       seen. Both are gone.
     - A poster or an avatar that CHANGES upstream is not picked up. That is
       the trade being made rather than an oversight: a thumbnail is a picture
       of a video that already exists, an avatar changes about never, and
       neither is worth a conditional request per tile per launch.

   Nothing here is a cache in the sense of "may vanish". It lives in filesDir,
   not cacheDir, precisely so Android cannot reclaim it under storage pressure
   and quietly reintroduce the re-download. What bounds it instead is removal:
   `forget` is called when a channel goes, and when a refresh drops videos off
   the end of a channel's list. See ChannelStore.remove and VideoStore.replace. */
object Thumbnails {

    /* The application context, for filesDir. Set once from App.onCreate, which
       runs before any screen can ask for a picture. A missing one is not fatal:
       everything below falls back to memory-and-network, which is exactly how
       this behaved before there was a disk half. */
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun dir(): File? {
        val c = appContext ?: return null
        val d = File(c.filesDir, "images")
        if (!d.exists() && !d.mkdirs()) return null
        return d
    }

    /* SHA-256 of the URL, hex. A file name has to be safe on the filesystem and
       stable across launches, and a URL is neither — it carries slashes and
       query strings and can be longer than the name limit. Hashing gives both
       for free, and collisions are not a practical concern at a few thousand
       entries. */
    private fun fileFor(url: String): File? {
        val d = dir() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return File(d, digest.joinToString("") { "%02x".format(it) })
    }

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

    /* Drop these, because the channel they belong to is no longer approved —
       or because a refresh pushed them off the end of its list.
     *
     * MEMORY AND DISK BOTH, and the disk half is the one that matters now.
     * Nothing here expires on its own, so this is the only thing that ever
     * removes a picture from the phone: "removed" has to mean removed, or a
     * parent who takes a channel away leaves its posters sitting in filesDir
     * for the life of the install. */
    fun forget(urls: Collection<String>) {
        for (url in urls) {
            if (url.isEmpty()) continue
            memory.remove(url)
            try { fileFor(url)?.delete() } catch (e: Exception) {}
        }
    }

    /* Memory, then disk, then the network — and the network only ever once per
       URL. */
    suspend fun load(url: String): Bitmap? {
        memory.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = fileFor(url)

            /* Already on the phone. No request is made, online or off, and no
               freshness check either — see the note at the top about what that
               trades away. */
            if (file != null && file.exists()) {
                try {
                    BitmapFactory.decodeFile(file.path)?.let {
                        memory.put(url, it)
                        return@withContext it
                    }
                } catch (e: Exception) {}
                /* Unreadable — truncated by a kill mid-write, most likely. Drop
                   it and fetch again rather than leaving a poison entry that
                   fails forever. */
                try { file.delete() } catch (e: Exception) {}
            }

            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val bytes = r.body?.bytes() ?: return@withContext null
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@withContext null
                    /* Written via a temporary and renamed, so a process death
                       mid-write cannot leave a half a JPEG under a name that
                       says it is whole. */
                    if (file != null) {
                        try {
                            val tmp = File(file.path + ".part")
                            tmp.writeBytes(bytes)
                            if (!tmp.renameTo(file)) tmp.delete()
                        } catch (e: Exception) {}
                    }
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
