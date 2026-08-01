package dev.vtlinh.ytkids

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/* Keeps the last frame the player measured, so it can be looked at.

   This is a deliberate exception to the rule that the capture is a
   measurement and never a picture, and it is worth being explicit about what
   changed and what did not.

   What changed: the strip is now written to a PNG so a wrong reading can be
   examined rather than inferred. Six rounds of this were spent guessing at
   what the analysis was looking at, and the round that finally landed did so
   because the app reported its working instead of its conclusion. The image
   is the rest of that working.

   What did not change: the SAME rectangle is captured as before — the bottom
   of the player, never the part with the picture in it. It goes to the app's
   own internal storage, which no other app can read and which the backup
   rules exclude, not to the gallery or anywhere shared. There is exactly one
   file and each capture overwrites it, so nothing accumulates. It is deleted
   on request from About, and nothing sends it anywhere: sharing it is an
   explicit tap by a parent, through the system's own share sheet. */
object CaptureStore {

    private const val DIR = "capture"
    private const val NAME = "last-strip.png"

    fun file(context: Context) = File(File(context.filesDir, DIR).apply { mkdirs() }, NAME)

    fun exists(context: Context) = file(context).let { it.exists() && it.length() > 0 }

    /* Overwrites the previous one. Best-effort: a diagnostic that throws while
       a child is watching a video would be a poor trade. */
    fun save(context: Context, bitmap: Bitmap) {
        try {
            file(context).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Throwable) {
        }
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (e: Throwable) {}
    }
}
