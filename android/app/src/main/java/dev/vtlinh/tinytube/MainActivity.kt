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
   channel, it cannot open YouTube, and it cannot make, rename or break up a
   group — the approved list is the parental control, and editing it lives
   behind the gate, in ApprovedChannelsActivity. This tab is a way to browse
   what is already allowed, nothing more.

   THE GROUPS ARE SHOWN AND THEIR MEMBERS ARE SHOWN TOO. A group is a header
   that filters to the whole group, with its channels listed individually
   beneath it — reaching one channel of a group must not cost a child two taps
   and an idea about how grouping works. Same rows, same order, as the parent's
   list: ChannelGroups.arrange decides both. */
class MainActivity : AppCompatActivity() {

    companion object {
        /* Asked for by the update notification. See openSettingsIfAsked. */
        const val EXTRA_OPEN_SETTINGS = "open_settings"
    }


    private lateinit var grid: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var empty: TextView
    private lateinit var header: TextView
    private lateinit var back: View

    private val videos = mutableListOf<Video>()
    private val adapter = VideoAdapter()

    private val channels = mutableListOf<Channel>()
    private var groups = listOf<ChannelGroups.Group>()
    private val rows = mutableListOf<ChannelGroups.Row>()
    private val channelAdapter = ChannelAdapter()

    /* Everything the app knows, kept per channel so the Channels tab can show
       one of them without going back to the network. */
    private var byChannel: Map<String, List<Video>> = emptyMap()

    private var tab = BottomTabs.VIDEOS
    /* Non-null while the grid is narrowed rather than showing everything.
     *
     * An ID rather than the thing itself, and the title and the channels are
     * derived from the current list on every read. That is what makes the
     * filter heal itself: a channel removed, or a group dissolved down to one
     * member, stops resolving and the grid widens back out rather than showing
     * a heading for something that is gone. */
    private var filter: Filter? = null

    private sealed class Filter {
        data class OneChannel(val id: String) : Filter()
        data class OneGroup(val id: String) : Filter()
    }

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

        back.setOnClickListener { showFiltered(null) }

        BottomTabs.bind(this, tab) { selected ->
            /* Tapping Videos is also how you get back to all of them. There is
               no second gesture to learn and no state to be stuck in. */
            if (selected == BottomTabs.VIDEOS) showFiltered(null)
            setTab(selected)
        }

        /* Show the last known list immediately — before any network — so the
           screen is never blank while a request is in flight. */
        byChannel = ChannelFeeds.cachedByChannel(this)
        reloadChannels()
        applyVideos()

        setTab(intent.getIntExtra(BottomTabs.EXTRA_TAB, BottomTabs.VIDEOS))

        askForNotificationsIfNeeded()
        openSettingsIfAsked(intent)
    }

    /* Arriving from About, which sends you back here on a chosen tab — or from
       the update notification, which asks for settings. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setTab(intent.getIntExtra(BottomTabs.EXTRA_TAB, tab))
        openSettingsIfAsked(intent)
    }

    /* THE UPDATE NOTIFICATION LANDS HERE, NOT ON SETTINGS.
     *
     * A notification sits in the shade and on the lock screen, where a child
     * can reach it, so it cannot be a door into the parent's settings — one of
     * the things on that screen is how long the player's corner has to be held.
     * What it can do is ask for the same gate every other way in asks for. So
     * the tap arrives here, ChallengeActivity runs, and settings opens only on
     * a RESULT_OK — which is exactly the sequence a parent gets from the grid's
     * Parent button.
     *
     * The extra is consumed rather than left on the intent: setIntent keeps it
     * for the life of the activity, and a rotation would otherwise re-run the
     * gate on a screen the parent had already dismissed it from.
     *
     * And the gate is skipped when parent mode is ALREADY on screen — see
     * ParentSession for why that is a count rather than a flag. */
    private fun openSettingsIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) != true) return
        intent.removeExtra(EXTRA_OPEN_SETTINGS)

        /* ALREADY PAST THE GATE — don't ask twice. A parent looking at parent
           mode, or at the settings inside it, has just answered this question,
           and challenging them again for tapping a notification about the
           screen they are on is nonsense.
         *
         * CLEAR_TOP with SINGLE_TOP rather than a plain start: ParentActivity
         * is already in this task, so this brings it back to the front and
         * delivers to its onNewIntent instead of stacking a second copy with
         * its own WebView. */
        if (ParentSession.isOpen) {
            startActivity(
                Intent(this, ParentActivity::class.java)
                    .putExtra(ParentActivity.EXTRA_OPEN_SETTINGS, true)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
            return
        }

        wantSettings = true
        parentGate.launch(Intent(this, ChallengeActivity::class.java))
    }

    private fun setTab(selected: Int) {
        tab = selected
        BottomTabs.select(this, selected)
        render()
    }

    /* Narrow the grid to one channel or one group, or widen it back to all of
       them. Every caller goes through here, so the tab always follows. */
    private fun showFiltered(to: Filter?) {
        filter = to
        applyVideos()
        setTab(BottomTabs.VIDEOS)
    }

    /* Which channels the filter covers, in ChannelStore's own order.
     *
     * A list rather than a set because that order decides how videos posted at
     * the same second fall — see Library.forChannels — and ChannelStore's order
     * is the one the unfiltered grid already resolves ties by. The sort the
     * parent picked deliberately does NOT come into it: that arranges the
     * channel LIST, and letting it reorder the video grid would mean the same
     * two videos swapped places because of a setting about something else.
     *
     * Derived from the current list every time, so a filter naming something
     * that has gone resolves to nothing and the grid widens back out. */
    private fun filteredChannelIds(): List<String> = when (val f = filter) {
        null -> emptyList()
        is Filter.OneChannel -> channels.filter { it.id == f.id }.map { it.id }
        is Filter.OneGroup -> channels.filter { it.groupId == f.id }.map { it.id }
    }

    /* What the heading says while the grid is narrowed, or null if the thing
       it named no longer exists. */
    private fun filterTitle(): String? = when (val f = filter) {
        null -> null
        is Filter.OneChannel -> channels.firstOrNull { it.id == f.id }?.title
        is Filter.OneGroup -> groups.firstOrNull { it.id == f.id }?.name
    }

    private fun applyVideos() {
        val fresh =
            if (filter == null) Library.collate(Library.flatten(byChannel))
            else Library.forChannels(byChannel, filteredChannelIds())
        videos.clear()
        videos.addAll(fresh)
        adapter.notifyDataSetChanged()
        render()
    }

    private fun reloadChannels() {
        val store = ChannelStore.get(this)
        channels.clear()
        channels.addAll(store.all())
        groups = store.groups()

        /* The same order and the same grouping the parent set on their own
           list. It is one list, and two arrangements of it is how a parent ends
           up unable to find on this screen what they just arranged on the
           other. Read-only here, like everything else on this tab — the screen
           that changes it is in parent mode. */
        rows.clear()
        rows.addAll(
            ChannelGroups.arrange(
                channels,
                groups,
                SettingsStore.channelSort(this),
                WatchStore.countsByWindow(this, System.currentTimeMillis()),
            ),
        )
        channelAdapter.notifyDataSetChanged()

        /* A channel approved and then removed while its videos were on screen —
           or a group dissolved because it dropped to one member — would
           otherwise leave the grid filtered to nothing, with a heading naming
           something that is gone. */
        if (filter != null && filterTitle() == null) filter = null
    }

    /* Set when the gate was opened by the update notification rather than by
       the Parent button, so a pass lands on settings instead of the browser. */
    private var wantSettings = false

    private val parentGate =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val toSettings = wantSettings
            /* Cleared whatever the answer was. A failed challenge must not
               leave the request armed for whenever the gate next passes. */
            wantSettings = false
            if (result.resultCode == Activity.RESULT_OK) {
                startActivity(
                    Intent(this, ParentActivity::class.java)
                        .putExtra(ParentActivity.EXTRA_OPEN_SETTINGS, toSettings),
                )
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
        if (tab == BottomTabs.VIDEOS && filter != null) showFiltered(null)
        else super.onBackPressed()
    }

    /* An empty catalog is a normal state, not an error — it is what a fresh
       install looks like before anything is approved. Say so in words a parent
       can act on rather than leaving a blank screen. */
    private fun render() {
        val onChannels = tab == BottomTabs.CHANNELS
        val none = if (onChannels) rows.isEmpty() else videos.isEmpty()

        grid.visibility = if (!onChannels && !none) View.VISIBLE else View.GONE
        channelList.visibility = if (onChannels && !none) View.VISIBLE else View.GONE

        empty.visibility = if (none) View.VISIBLE else View.GONE
        empty.setText(if (onChannels) R.string.channels_empty else R.string.empty_catalog)

        /* The heading says which of the three things you are looking at:
           everything, one channel or group, or the channel list. A group reads
           the same as a channel does — "Showing Cartoons" — because from the
           grid's side they are the same thing: a narrower set of videos with a
           name and a way back. */
        val narrowedTo = filterTitle()
        header.text = when {
            onChannels -> getString(R.string.channels_title)
            narrowedTo != null -> getString(R.string.showing_channel, narrowedTo)
            else -> getString(R.string.app_name)
        }
        val showBack = !onChannels && narrowedTo != null
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
    /* Two kinds of row, the same two the parent's list has — and drawn from
       the same ChannelGroups.arrange, so the two screens cannot drift apart.
       What differs is what a tap does and what is missing: no remove button, no
       long press, no selection. */
    private inner class ChannelAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is ChannelGroups.Row.Header -> TYPE_GROUP
            is ChannelGroups.Row.Item -> TYPE_CHANNEL
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_GROUP) {
                GroupHolder(inflater.inflate(R.layout.item_channel_group, parent, false))
            } else {
                ChannelHolder(inflater.inflate(R.layout.item_approved_channel, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is ChannelGroups.Row.Header -> bindGroup(holder as GroupHolder, row)
                is ChannelGroups.Row.Item -> bindChannel(holder as ChannelHolder, row)
            }
        }

        /* Tapping a group shows every channel in it at once. Its members are
           listed underneath as well, so this is a shortcut rather than the only
           way in — a child who wants one channel of a group does not have to
           understand grouping to reach it. */
        private fun bindGroup(holder: GroupHolder, row: ChannelGroups.Row.Header) {
            holder.name.text = row.group.name
            holder.size.text = getString(R.string.group_size, row.size)
            holder.itemView.setOnClickListener {
                showFiltered(Filter.OneGroup(row.group.id))
            }
        }

        private fun bindChannel(holder: ChannelHolder, row: ChannelGroups.Row.Item) {
            val channel = row.channel
            holder.title.text = channel.title
            holder.subtitle.text = channel.handle?.let { "@$it" } ?: channel.id
            /* Not merely hidden — GONE, so it takes no space and cannot be
               reached by touch exploration either. */
            holder.remove.visibility = View.GONE
            holder.itemView.setOnClickListener { showFiltered(Filter.OneChannel(channel.id)) }

            /* Members are indented under their header. Without it the header
               reads as a divider above an unrelated list rather than as
               something these rows are inside. */
            holder.itemView.setPaddingRelative(
                if (row.grouped) GROUPED_INDENT_DP.dp() else ROW_START_DP.dp(),
                holder.itemView.paddingTop,
                holder.itemView.paddingEnd,
                holder.itemView.paddingBottom,
            )

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

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private class GroupHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.group_name)
        val size: TextView = view.findViewById(R.id.group_size)
    }

    private class ChannelHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.avatar)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val remove: View = view.findViewById(R.id.remove)
    }
}

private const val TYPE_GROUP = 0
private const val TYPE_CHANNEL = 1

/* item_approved_channel's own paddingStart, and the indent a grouped row gets
   instead — the same two numbers ApprovedChannelsActivity uses, because the two
   lists must line up. Set in code rather than in the layout because one view is
   drawn either way and a recycled row has to be told which it is. */
private const val ROW_START_DP = 20
private const val GROUPED_INDENT_DP = 46
