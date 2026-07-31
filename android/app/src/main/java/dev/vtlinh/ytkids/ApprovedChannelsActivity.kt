package dev.vtlinh.ytkids

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

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
        title = getString(R.string.parent_approved_title)

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        reload()
    }

    private fun reload() {
        channels.clear()
        channels.addAll(ChannelStore.get(this).all())
        adapter.notifyDataSetChanged()
        val none = channels.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        list.visibility = if (none) View.GONE else View.VISIBLE
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
                   grid after the channel is gone */
                ChannelFeeds.forget(this, channel.id)
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
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val remove: ImageButton = view.findViewById(R.id.remove)
    }
}
