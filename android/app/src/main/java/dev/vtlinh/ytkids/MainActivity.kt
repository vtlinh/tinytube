package dev.vtlinh.ytkids

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/* The one screen a child sees: every approved video as a big poster tile.

   No search box, no menu, no text entry, nothing that scrolls off into
   somewhere else. Tapping a tile plays that video and returns here when it
   ends. That is the entire vocabulary of the screen, on purpose. */
class MainActivity : AppCompatActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private val videos = mutableListOf<Video>()
    private val adapter = VideoAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        grid.layoutManager = GridLayoutManager(this, spanCount())
        grid.adapter = adapter

        /* the way in to the parent-facing screen: a long press on the header,
           which a child will not discover by tapping around, and which costs
           nothing to reach deliberately */
        findViewById<View>(R.id.header).setOnLongClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
            true
        }

        /* Show the last known list immediately — before any network — so the
           screen is never blank while a request is in flight. */
        videos.addAll(CatalogStore.cached(this))
        adapter.notifyDataSetChanged()
        render()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val fresh = CatalogStore.refresh(this@MainActivity) ?: return@launch
            if (fresh == videos) return@launch
            videos.clear()
            videos.addAll(fresh)
            adapter.notifyDataSetChanged()
            render()
        }
    }

    private fun spanCount() =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (grid.layoutManager as GridLayoutManager).spanCount = spanCount()
    }

    /* An empty catalog is a normal state, not an error — it is what a fresh
       install looks like before anything is approved. Say so in words a parent
       can act on rather than leaving a blank screen. */
    private fun render() {
        val none = videos.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        grid.visibility = if (none) View.GONE else View.VISIBLE
    }

    private inner class VideoAdapter : RecyclerView.Adapter<VideoHolder>() {
        init { setHasStableIds(true) }

        override fun getItemId(position: Int) = videos[position].id.hashCode().toLong()
        override fun getItemCount() = videos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VideoHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false),
        )

        override fun onBindViewHolder(holder: VideoHolder, position: Int) {
            val v = videos[position]
            holder.title.text = v.title
            holder.itemView.contentDescription = v.title

            /* Clear first. Without this a recycled tile shows the previous
               video's poster until the new one arrives — which on a slow
               connection means tapping what looks like one video and getting
               another. */
            val url = v.thumbnailUrl
            Thumbnails.tagFor(holder.thumb, url)
            val hit = Thumbnails.cached(url)
            if (hit != null) {
                holder.thumb.setImageBitmap(hit)
            } else {
                holder.thumb.setImageDrawable(null)
                lifecycleScope.launch {
                    val bmp = Thumbnails.load(url) ?: return@launch
                    if (Thumbnails.stillWanted(holder.thumb, url)) holder.thumb.setImageBitmap(bmp)
                }
            }

            holder.itemView.setOnClickListener { PlayerActivity.start(this@MainActivity, v) }
        }
    }

    private class VideoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val title: TextView = view.findViewById(R.id.title)
    }
}
