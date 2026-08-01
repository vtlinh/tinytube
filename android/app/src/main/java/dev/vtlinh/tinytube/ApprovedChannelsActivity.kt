package dev.vtlinh.tinytube

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/* The approved channels, as a screen rather than a dialog.

   A dialog could list them but couldn't comfortably do both things a parent
   wants here: tapping a row to go and look at the channel again, and removing
   one without the list closing underneath. */
class ApprovedChannelsActivity : AppCompatActivity() {

    private val channels = mutableListOf<Channel>()
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private val adapter = ChannelAdapter()

    companion object {
        /* Set when the parent tapped a row: the URL parent mode should go to. */
        const val EXTRA_OPEN_URL = "open_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_approved_channels)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            title = getString(R.string.parent_approved_title)
            setDisplayHomeAsUpEnabled(true)
        }

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        reload()
        backfillAvatars()
    }

    /* One button in the bar, cycling the three orders. A menu of three would
       be more discoverable and this is a screen a parent visits rarely; the
       toolbar's subtitle is what makes cycling legible, by naming the order
       that is now in force rather than leaving the list to have silently
       rearranged itself. */
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.approved_channels, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId != R.id.action_sort) return super.onOptionsItemSelected(item)
        SettingsStore.setChannelSort(this, ChannelSort.next(SettingsStore.channelSort(this)))
        reload()
        return true
    }

    /* What the order is, in words — including which rung of the ladder the
       watch counts actually landed on. "Most watched" that fell all the way
       through to A-Z has to say so: a list that looks unsorted and a list that
       is broken look the same otherwise. */
    private fun describe(mode: ChannelSort.Mode, countsByWindow: List<Map<String, Int>>): String =
        when (mode) {
            ChannelSort.Mode.LAST_ADDED -> getString(R.string.parent_sort_last_added)
            ChannelSort.Mode.A_Z -> getString(R.string.parent_sort_a_z)
            ChannelSort.Mode.MOST_WATCHED -> {
                val window = ChannelSort.windowIndex(countsByWindow)
                if (window == null) getString(R.string.parent_sort_watched_none)
                else getString(R.string.parent_sort_watched_days, ChannelSort.WINDOWS_DAYS[window])
            }
        }

    /* The up arrow means "back to what I was doing", not "up to a parent
       screen that doesn't exist" — finish rather than synthesise a stack. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        val mode = SettingsStore.channelSort(this)
        /* Read even for the two orders that don't need them, so the subtitle
           can be written from the same snapshot the list was sorted from.
           Three grouped counts over a table pruned to a year is not work worth
           arranging around. */
        val counts = WatchStore.countsByWindow(this, System.currentTimeMillis())

        channels.clear()
        channels.addAll(ChannelSort.sort(ChannelStore.get(this).all(), mode, counts))
        adapter.notifyDataSetChanged()
        supportActionBar?.subtitle = describe(mode, counts)

        val none = channels.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        list.visibility = if (none) View.GONE else View.VISIBLE
    }

    /* Channels approved before avatars were recorded have none, and would sit
       as blank circles for good otherwise. Fetch them once, quietly, and
       remember — a row that fails simply stays blank and is tried again next
       time this screen opens. */
    private fun backfillAvatars() {
        val missing = channels.filter { it.avatarUrl == null }
        if (missing.isEmpty()) return
        lifecycleScope.launch {
            var found = false
            for (channel in missing) {
                val resolved = ChannelResolver.resolve(channel.url) ?: continue
                val avatar = resolved.avatarUrl ?: continue
                ChannelStore.get(this@ApprovedChannelsActivity).setAvatar(channel.id, avatar)
                found = true
            }
            if (found) reload()
        }
    }

    private fun open(channel: Channel) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OPEN_URL, channel.url))
        finish()
    }

    /* Confirmed, because it is destructive and one row looks much like
       another on a small screen. */
    private fun confirmRemove(channel: Channel) {
        AlertDialog.Builder(this)
            .setTitle(channel.title)
            .setMessage(getString(R.string.parent_remove_confirm, channel.title))
            .setPositiveButton(R.string.parent_remove) { _, _ ->
                ChannelStore.get(this).remove(channel.id)
                /* drop the cached feed too, or its videos keep showing in the
                   grid after the channel is gone — and the watch history with
                   it, so an unapproved channel leaves nothing behind */
                ChannelFeeds.forget(this, channel.id)
                WatchStore.forget(this, channel.id)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<Holder>() {
        init { setHasStableIds(true) }

        override fun getItemId(position: Int) = channels[position].id.hashCode().toLong()
        override fun getItemCount() = channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_approved_channel, parent, false),
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val channel = channels[position]
            holder.title.text = channel.title
            /* the handle if we have one, the id otherwise — something to tell
               two similarly-named channels apart by */
            holder.subtitle.text = channel.handle?.let { "@$it" } ?: channel.id
            holder.itemView.setOnClickListener { open(channel) }
            holder.remove.contentDescription =
                getString(R.string.parent_remove_named, channel.title)
            holder.remove.setOnClickListener { confirmRemove(channel) }

            /* Clear first: a recycled row would otherwise wear the previous
               channel's face until this one's arrives. */
            val url = channel.avatarUrl
            holder.avatar.setImageDrawable(null)
            Thumbnails.tagFor(holder.avatar, url.orEmpty())
            if (url == null) return
            Thumbnails.cached(url)?.let { holder.avatar.setImageBitmap(it); return }
            lifecycleScope.launch {
                val bmp = Thumbnails.load(url) ?: return@launch
                if (Thumbnails.stillWanted(holder.avatar, url)) holder.avatar.setImageBitmap(bmp)
            }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.avatar)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val remove: ImageButton = view.findViewById(R.id.remove)
    }
}
