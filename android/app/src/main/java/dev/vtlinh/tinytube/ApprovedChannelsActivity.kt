package dev.vtlinh.tinytube

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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

/* The approved channels, as a screen of its own again.

   It spent a build inside SettingsActivity, as the list rather than a button
   that opened one, and that was right while it was a flat column of rows.
   Groups changed the shape of it: there is a selection mode now, a toolbar
   with two states, and a dialog. A long-press that starts selecting rows in
   the middle of a scrolling page of sliders and radio buttons is a trap, and
   a toolbar that becomes "3 selected" cannot be a heading inside another
   screen. So it is a screen, reached from parent mode's bar.

   No gate of its own — see ParentActivity. Getting here already required one.

   THE LIST IS ALWAYS EXPANDED. A group is a header with its channels drawn
   beneath it as ordinary rows; there is no chevron and no collapsed state. A
   parent's list is a handful of rows, and hiding some of them behind a
   disclosure triangle would mean "what is approved?" could be answered wrongly
   by looking. ChannelGroups.arrange is what flattens it.

   SELECTION IS LONG-PRESS. Plain taps keep doing what they always did — a row
   opens that channel in parent mode — because that is the thing done often and
   grouping is the thing done twice. */
class ApprovedChannelsActivity : AppCompatActivity() {

    companion object {
        /* Set when the parent tapped a row: the URL parent mode should go to. */
        const val EXTRA_OPEN_URL = "open_url"
    }

    private lateinit var toolbar: Toolbar
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView

    private val rows = mutableListOf<ChannelGroups.Row>()
    private val channels = mutableListOf<Channel>()
    private var groups = listOf<ChannelGroups.Group>()
    private val adapter = RowAdapter()

    /* Channel ids, not positions: the list re-sorts under a selection when a
       group is made, and positions would then point at the wrong rows. */
    private val selected = linkedSetOf<String>()
    private val selecting: Boolean get() = selected.isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_approved_channels)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        reload()
        backfillAvatars()
    }

    /* Inside parent mode, so it counts as parent mode — otherwise the update
       notification would challenge a parent who is looking at parent controls.
       See ParentSession. */
    override fun onStart() {
        super.onStart()
        ParentSession.started()
    }

    override fun onStop() {
        ParentSession.stopped()
        super.onStop()
    }

    /* ---- the bar ---- */

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.approved_channels, menu)
        return true
    }

    /* The two bars. Sort belongs to the ordinary one; Group and Ungroup to the
       selecting one, and each appears only when the selection permits it —
       ChannelGroups decides that, not this screen. A greyed-out button would
       be honest too, but "why can't I tap this" is a question the parent then
       has to answer from an icon. */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val picked = channels.filter { it.id in selected }
        menu.findItem(R.id.action_sort).isVisible = !selecting
        menu.findItem(R.id.action_help).isVisible = !selecting
        menu.findItem(R.id.action_group).isVisible =
            selecting && ChannelGroups.canGroup(picked)
        menu.findItem(R.id.action_ungroup).isVisible =
            selecting && ChannelGroups.canUngroup(picked)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_sort -> {
            SettingsStore.setChannelSort(this, ChannelSort.next(SettingsStore.channelSort(this)))
            reload()
            true
        }
        /* Anchored to the action's own view, so the popup points at the ? that
           opened it exactly as it does beside a settings heading. Shown rather
           than attached: that view is the menu's, and it already carries the
           toolbar's click listener — attach() would replace the very thing that
           dispatches this item. */
        R.id.action_help -> {
            findViewById<View>(R.id.action_help)?.let {
                Tooltip.show(it, getString(R.string.parent_approved_explain))
            }
            true
        }
        R.id.action_group -> {
            askGroupName()
            true
        }
        R.id.action_ungroup -> {
            ChannelStore.get(this).ungroup(selected.toList())
            clearSelection()
            reload()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /* The up arrow means two different things, which is the whole reason
       selection uses it: while selecting it cancels, and otherwise it goes
       back. Back does the same, below. */
    override fun onSupportNavigateUp(): Boolean {
        if (selecting) {
            clearSelection()
            reload()
        } else {
            finish()
        }
        return true
    }

    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
    override fun onBackPressed() {
        /* Back cancels a selection before it leaves the screen. A parent who
           long-pressed by accident should be able to get out of selection mode
           with the button they already reach for, rather than deselecting rows
           one at a time. */
        if (selecting) {
            clearSelection()
            reload()
            return
        }
        super.onBackPressed()
    }

    /* ---- selection ---- */

    private fun clearSelection() {
        selected.clear()
    }

    private fun toggle(channelId: String) {
        if (!selected.remove(channelId)) selected.add(channelId)
        afterSelectionChanged()
    }

    /* A header takes its whole group with it. Tapping it again puts them all
       back, rather than leaving a half-selected group whose header still looks
       selected — which is what toggling each member individually would do. */
    private fun toggleGroup(groupId: String) {
        val members = ChannelGroups.membersOf(groupId, channels)
        if (selected.containsAll(members)) selected.removeAll(members)
        else selected.addAll(members)
        afterSelectionChanged()
    }

    private fun afterSelectionChanged() {
        invalidateOptionsMenu()
        renderTitle()
        adapter.notifyDataSetChanged()
    }

    private fun renderTitle() {
        if (selecting) {
            supportActionBar?.title = getString(R.string.group_selection_count, selected.size)
            supportActionBar?.subtitle = null
        } else {
            supportActionBar?.title = getString(R.string.parent_approved_title)
            supportActionBar?.subtitle = describe(
                SettingsStore.channelSort(this),
                WatchStore.countsByWindow(this, System.currentTimeMillis()),
            )
        }
    }

    /* ---- the group dialog ---- */

    /* Named on the way in rather than renamed afterwards: a group with no name
       has nothing to draw in its header, and "Untitled group" is a thing a
       parent then has to go and fix.

       The confirm starts disabled and stays disabled while the name is one
       ChannelGroups.nameError refuses, with the reason under the field. Not a
       toast: the thing to fix is the text they are looking at. */
    private fun askGroupName() {
        val picked = channels.filter { it.id in selected }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_group_name, null)
        val input = view.findViewById<EditText>(R.id.group_name_input)
        val error = view.findViewById<TextView>(R.id.group_name_error)
        /* Not every group name — the ones still in use once this selection has
           moved. A group whose every member is selected is emptied by the
           grouping and dissolves, so its name is free, which is what lets the
           prefilled "Cartoons" be accepted rather than refused as taken. */
        val existing = ChannelGroups.namesInUse(groups, channels, selected.toSet())

        ChannelGroups.prefillName(picked, groups, channels)?.let {
            input.setText(it)
            input.setSelection(it.length)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.group_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.group_selected) { _, _ ->
                /* group() re-checks the name and the count. The dialog has
                   already refused both, so this is the second lock on the same
                   door — but it is the one that runs in a transaction. */
                if (ChannelStore.get(this).group(selected.toList(), input.text.toString())) {
                    clearSelection()
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun judge() {
                val typed = input.text.toString()
                when (ChannelGroups.nameError(typed, existing)) {
                    ChannelGroups.NameError.EMPTY -> {
                        ok.isEnabled = false
                        error.setText(R.string.group_error_empty)
                        /* Blank is the state the box STARTS in when nothing was
                           prefilled, and shouting at a parent who has not typed
                           anything yet is rude. Disabled, but silent. */
                        error.visibility =
                            if (typed.isEmpty()) View.GONE else View.VISIBLE
                    }
                    ChannelGroups.NameError.TAKEN -> {
                        ok.isEnabled = false
                        error.setText(R.string.group_error_taken)
                        error.visibility = View.VISIBLE
                    }
                    null -> {
                        ok.isEnabled = true
                        error.visibility = View.GONE
                    }
                }
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = judge()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
            judge()
        }
        dialog.show()
    }

    /* ---- the list ---- */

    private fun reload() {
        val mode = SettingsStore.channelSort(this)
        /* Read even for the two orders that don't use them, so the subtitle is
           written from the same snapshot the list was sorted from. */
        val counts = WatchStore.countsByWindow(this, System.currentTimeMillis())
        val store = ChannelStore.get(this)

        channels.clear()
        channels.addAll(store.all())
        groups = store.groups()

        /* A selection can outlive the rows it pointed at — removing a channel
           while selecting is the obvious way. Drop anything that is gone rather
           than carrying an id no row will ever draw. */
        selected.retainAll(channels.map { it.id }.toSet())

        rows.clear()
        rows.addAll(ChannelGroups.arrange(channels, groups, mode, counts))
        adapter.notifyDataSetChanged()

        renderTitle()
        invalidateOptionsMenu()

        val none = channels.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        list.visibility = if (none) View.GONE else View.VISIBLE
    }

    /* What the order is, in words — including which rung of the ladder the
       watch counts actually landed on. "Most watched" that fell all the way
       through to A-Z has to say so: a list that looks unsorted and a list that
       is broken look the same otherwise. */
    private fun describe(mode: ChannelSort.Mode, countsByWindow: List<Map<String, Int>>): String =
        when (mode) {
            ChannelSort.Mode.LAST_ADDED -> getString(R.string.parent_sort_last_added)
            ChannelSort.Mode.A_Z -> getString(R.string.parent_sort_a_z)
            ChannelSort.Mode.MOST_WATCHED ->
                if (ChannelSort.windowIndex(countsByWindow) == null) {
                    getString(R.string.parent_sort_watched_none)
                } else {
                    getString(R.string.parent_sort_watched_days)
                }
        }

    /* Channels approved before avatars were recorded have none, and would sit
       as blank circles for good otherwise. Fetch them once, quietly; a row that
       fails stays blank and is tried again next time this screen opens. */
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

    /* A row tapped means "take me to that channel", which this screen cannot
       do — ParentActivity owns the WebView. So it is handed back up. */
    private fun open(channel: Channel) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OPEN_URL, channel.url))
        finish()
    }

    /* Confirmed, because it is destructive and one row looks much like another
       on a small screen. */
    private fun confirmRemove(channel: Channel) {
        AlertDialog.Builder(this)
            .setTitle(channel.title)
            .setMessage(getString(R.string.parent_remove_confirm, channel.title))
            .setPositiveButton(R.string.parent_remove) { _, _ ->
                /* Everything goes together inside remove(): the row, its
                   videos, its watch history and its cached pictures — and now
                   the group it leaves behind, if that drops it below two. */
                ChannelStore.get(this).remove(channel.id)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is ChannelGroups.Row.Header -> TYPE_HEADER
            is ChannelGroups.Row.Item -> TYPE_ITEM
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(inflater.inflate(R.layout.item_channel_group, parent, false))
            } else {
                ItemHolder(inflater.inflate(R.layout.item_approved_channel, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is ChannelGroups.Row.Header -> bindHeader(holder as HeaderHolder, row)
                is ChannelGroups.Row.Item -> bindItem(holder as ItemHolder, row)
            }
        }

        private fun bindHeader(holder: HeaderHolder, row: ChannelGroups.Row.Header) {
            holder.name.text = row.group.name
            holder.size.text = getString(R.string.group_size, row.size)

            val members = ChannelGroups.membersOf(row.group.id, channels)
            /* Activated when the WHOLE group is selected. A group with one
               member picked is not a selected group, and drawing it as one
               would say the Ungroup about to be tapped covers all of them. */
            holder.itemView.isActivated = members.isNotEmpty() && selected.containsAll(members)

            /* A plain tap does nothing outside selection. There is nothing for
               it to do — the group is already expanded, and there is no
               "open a group" anywhere in this app. */
            holder.itemView.setOnClickListener {
                if (selecting) toggleGroup(row.group.id)
            }
            holder.itemView.setOnLongClickListener {
                toggleGroup(row.group.id)
                true
            }
        }

        private fun bindItem(holder: ItemHolder, row: ChannelGroups.Row.Item) {
            val channel = row.channel
            holder.title.text = channel.title
            /* the handle if we have one, the id otherwise — something to tell
               two similarly-named channels apart by */
            holder.subtitle.text = channel.handle?.let { "@$it" } ?: channel.id
            holder.itemView.isActivated = channel.id in selected

            /* Members are indented under their header. The header is the only
               thing saying these rows belong together, and a header with rows
               flush against it below reads as a divider. */
            holder.itemView.setPaddingRelative(
                if (row.grouped) INDENT_DP.dp() else DEFAULT_START_DP.dp(),
                holder.itemView.paddingTop,
                holder.itemView.paddingEnd,
                holder.itemView.paddingBottom,
            )

            holder.itemView.setOnClickListener {
                if (selecting) toggle(channel.id) else open(channel)
            }
            holder.itemView.setOnLongClickListener {
                toggle(channel.id)
                true
            }

            /* Hidden rather than disabled while selecting: it is a 48dp target
               sitting exactly where a thumb goes to select the row, and a
               remove dialog is not what that thumb asked for. */
            holder.remove.visibility = if (selecting) View.GONE else View.VISIBLE
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

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.group_name)
        val size: TextView = view.findViewById(R.id.group_size)
    }

    private class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.avatar)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val remove: ImageButton = view.findViewById(R.id.remove)
    }
}

private const val TYPE_HEADER = 0
private const val TYPE_ITEM = 1

/* item_approved_channel's own paddingStart, and the indent a grouped row gets
   instead. Both live here rather than in the layout because one view has to be
   drawn either way, and a recycled row must be told which it is. */
private const val DEFAULT_START_DP = 20
private const val INDENT_DP = 46
