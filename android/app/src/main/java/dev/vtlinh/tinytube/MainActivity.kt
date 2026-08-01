package dev.vtlinh.tinytube

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/* The child's side of the app: approved videos as big poster tiles, and the
   channels they come from.

   No search box, no menu, no text entry, nothing that scrolls off into
   somewhere else. Tapping a tile plays that video and returns here when it
   ends; tapping a channel narrows the grid to that channel. That is the
   entire vocabulary of the screen, on purpose.

   Note what the Channels tab deliberately does NOT do. It cannot remove a
   channel and it cannot open YouTube — the approved list is the parental
   control, and editing it lives behind the gate, in SettingsActivity.
   This tab is a way to browse what is already allowed, nothing more. */
class MainActivity : AppCompatActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var empty: TextView
    private lateinit var header: TextView
    private lateinit var back: View

    private val videos = mutableListOf<Video>()
    private val adapter = VideoAdapter()

    private val channels = mutableListOf<Channel>()
    private val channelAdapter = ChannelAdapter()

    /* Everything the app knows, kept per channel so the Channels tab can show
       one of them without going back to the network. */
    private var byChannel: Map<String, List<Video>> = emptyMap()

    private var tab = BottomTabs.VIDEOS
    /* Non-null while the grid is showing one channel rather than all of them. */
    private var filter: Channel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        grid = findViewById(R.id.grid)
        channelList = findViewById(R.id.channels)
        empty = findViewById(R.id.empty)
        header = findViewById(R.id.header)
        back = findViewById(R.id.back)

        grid.layoutManager = GridLayoutManager(this, spanCount())
        grid.adapter = adapter
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter

        /* Parent mode, behind the device lock. The one control on this bar —
           the settings live inside parent mode, next to the approved list. */
        findViewById<View>(R.id.parent_mode).setOnClickListener {
            parentGate.launch(Intent(this, ChallengeActivity::class.java))
        }

        /* The title used to open About on a long-press. About is part of
           Settings now, inside parent mode — a version number and an update
           button are parent-facing, and a gesture nobody would guess was there
           is not where a parent finds them. Nothing on this screen has a
           hidden action any more. */

        back.setOnClickListener { showChannel(null) }

        BottomTabs.bind(this, tab) { selected ->
            /* Tapping Videos is also how you get back to all of them. There is
               no second gesture to learn and no state to be stuck in. */
            if (selected == BottomTabs.VIDEOS) showChannel(null)
            setTab(selected)
        }

        /* Show the last known list immediately — before any network — so the
           screen is never blank while a request is in flight. */
        byChannel = ChannelFeeds.cachedByChannel(this)
        reloadChannels()
        applyVideos()

        setTab(intent.getIntExtra(BottomTabs.EXTRA_TAB, BottomTabs.VIDEOS))

        askForNotificationsIfNeeded()
    }

    /* Arriving from About, which sends you back here on a chosen tab. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setTab(intent.getIntExtra(BottomTabs.EXTRA_TAB, tab))
    }

    private fun setTab(selected: Int) {
        tab = selected
        BottomTabs.select(this, selected)
        render()
    }

    /* Narrow the grid to one channel, or widen it back to all of them. */
    private fun showChannel(channel: Channel?) {
        filter = channel
        applyVideos()
        setTab(BottomTabs.VIDEOS)
    }

    private fun applyVideos() {
        val f = filter
        val fresh =
            if (f == null) Library.collate(Library.flatten(byChannel))
            else Library.forChannel(byChannel, f.id)
        videos.clear()
        videos.addAll(fresh)
        adapter.notifyDataSetChanged()
        render()
    }

    private fun reloadChannels() {
        channels.clear()
        /* The same order the parent set on their own list. It is one list, and
           two orders for it is how a parent ends up unable to find on this
           screen what they just arranged on the other. Read-only here, like
           everything else on this tab — the button that changes it is in
           parent mode. */
        channels.addAll(
            ChannelSort.sort(
                ChannelStore.get(this).all(),
                SettingsStore.channelSort(this),
                WatchStore.countsByWindow(this, System.currentTimeMillis()),
            ),
        )
        channelAdapter.notifyDataSetChanged()
        /* A channel approved and then removed while its videos were on screen
           would otherwise leave the grid filtered to nothing, with a heading
           naming something that is gone. */
        val stillThere = filter?.let { f -> channels.any { it.id == f.id } } ?: true
        if (!stillThere) filter = null
    }

    private val parentGate =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startActivity(Intent(this, ParentActivity::class.java))
            }
        }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /* Asked on first launch rather than waiting for someone to open About.
       The notification is the only thing that says an update is ready, and a
       parent who never visits About would never be asked at all.

       The cost is that the dialog can appear to whoever opens the app first,
       and two dismissals deny it permanently with no way back except Settings.
       About still explains the state and links there when that happens. */
    private fun askForNotificationsIfNeeded() {
        if (NotificationPrompt.state(this) != Notifications.State.ASKABLE) return
        NotificationPrompt.markAsked(this)
        askNotifications.launch(NotificationPrompt.PERMISSION)
    }

    override fun onResume() {
        super.onResume()
        /* Also runs on the way back from parent mode, which is what makes a
           newly approved channel's videos appear without a restart — and a
           removed one's disappear. */
        reloadChannels()
        lifecycleScope.launch {
            val fresh = ChannelFeeds.refreshByChannel(this@MainActivity)
            if (fresh == byChannel) return@launch
            byChannel = fresh
            applyVideos()
        }
    }

    private fun spanCount() =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (grid.layoutManager as GridLayoutManager).spanCount = spanCount()
    }

    /* Back leaves a channel's videos for all of them, so the system button
       agrees with the arrow in the bar. Anywhere else it does what it always
       does — the tabs are tabs, not a stack, and consuming back on one of
       those is how an app becomes impossible to leave. */
    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
    override fun onBackPressed() {
        if (tab == BottomTabs.VIDEOS && filter != null) showChannel(null)
        else super.onBackPressed()
    }

    /* An empty catalog is a normal state, not an error — it is what a fresh
       install looks like before anything is approved. Say so in words a parent
       can act on rather than leaving a blank screen. */
    private fun render() {
        val onChannels = tab == BottomTabs.CHANNELS
        val list = if (onChannels) channels else videos
        val none = list.isEmpty()

        grid.visibility = if (!onChannels && !none) View.VISIBLE else View.GONE
        channelList.visibility = if (onChannels && !none) View.VISIBLE else View.GONE

        empty.visibility = if (none) View.VISIBLE else View.GONE
        empty.setText(if (onChannels) R.string.channels_empty else R.string.empty_catalog)

        /* The heading says which of the three things you are looking at:
           everything, one channel, or the channel list. */
        val f = filter
        header.text = when {
            onChannels -> getString(R.string.channels_title)
            f != null -> getString(R.string.showing_channel, f.title)
            else -> getString(R.string.app_name)
        }
        val showBack = !onChannels && f != null
        back.visibility = if (showBack) View.VISIBLE else View.GONE
        /* With the arrow up, the title sits where a title sits next to a nav
           control. Without it, the title carries the bar's start inset itself
           — the alternative is a permanently reserved blank 40dp, which reads
           as a missing button rather than as a heading. */
        (header.layoutParams as android.view.ViewGroup.MarginLayoutParams).let {
            val start = if (showBack) 0 else (16 * resources.displayMetrics.density).toInt()
            if (it.marginStart != start) {
                it.marginStart = start
                header.layoutParams = it
            }
        }
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

            /* The whole visible list goes with the tap, not just this video.
               What plays after it has to come from the same place — which on
               a grid narrowed to one channel is that channel, with no rule
               here saying so. See PlayerActivity.start. */
            holder.itemView.setOnClickListener {
                PlayerActivity.start(this@MainActivity, videos.toList(), holder.bindingAdapterPosition)
            }
        }
    }

    private class VideoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val title: TextView = view.findViewById(R.id.title)
    }

    /* The Channels tab. The same row layout parent mode uses, with the remove
       button taken out — this side of the app may look at the approved list
       but not change it. */
    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelHolder>() {
        init { setHasStableIds(true) }

        override fun getItemId(position: Int) = channels[position].id.hashCode().toLong()
        override fun getItemCount() = channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ChannelHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_approved_channel, parent, false),
        )

        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            val channel = channels[position]
            holder.title.text = channel.title
            holder.subtitle.text = channel.handle?.let { "@$it" } ?: channel.id
            /* Not merely hidden — GONE, so it takes no space and cannot be
               reached by touch exploration either. */
            holder.remove.visibility = View.GONE
            holder.itemView.setOnClickListener { showChannel(channel) }

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

    private class ChannelHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.avatar)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val remove: View = view.findViewById(R.id.remove)
    }
}
