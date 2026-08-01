package dev.vtlinh.tinytube

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/* Every parent-facing control there is, the approved list included.

   Not exported, and reached only from parent mode's bar, which is itself only
   reachable through a RESULT_OK from ChallengeActivity. Nothing here needs a
   gate of its own; adding one would be asking the same question twice.

   The bottom half of this screen used to be AboutActivity, opened by
   long-pressing the grid's title. That put a parent-facing screen on the
   child's side of the app, behind a gesture nobody would guess was there.

   THE APPROVED LIST IS IN HERE TOO, and as the list rather than as a button
   that opens one. ApprovedChannelsActivity is gone: a whole screen — toolbar,
   up arrow, overflow menu — existed to show three or four rows, and the
   question a parent came to settings with is usually "what IS approved",
   which is now answered by looking rather than by tapping.

   The explanations are behind the ? beside each heading. See Tooltip.

   Settings save on the tap rather than behind a Save button. There is no draft
   state to lose and nothing to confirm; the control IS the setting. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var holdValue: TextView

    private lateinit var channelsList: LinearLayout
    private lateinit var channelsEmpty: TextView
    private lateinit var channelsCount: TextView
    private lateinit var channelsOrder: TextView

    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var notifStatus: TextView
    private lateinit var notifAction: Button
    /* The hairline above the notification block, so it appears and disappears
       with it rather than leaving a rule under nothing. */
    private lateinit var notifDivider: View

    /* One at a time, so an impatient double-tap on Install queues instead of
       racing two PackageInstaller commits. */
    private val installs: ExecutorService = Executors.newSingleThreadExecutor()

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            /* The result itself is ignored: areNotificationsEnabled() is the
               real answer, and it also covers the case where the permission is
               granted but the user has notifications switched off. */
            renderNotifications()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setUpNextVideo()
        setUpHold()

        status = findViewById(R.id.status)
        action = findViewById(R.id.action)
        notifStatus = findViewById(R.id.notif_status)
        notifAction = findViewById(R.id.notif_action)
        notifDivider = findViewById(R.id.notif_divider)
        setUpChannels()
        setUpHelp()
        findViewById<TextView>(R.id.version).text = getString(
            R.string.version_fmt,
            packageManager.getPackageInfo(packageName, 0).versionName,
        )
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateState()
        /* re-read on every resume: returning from the system Settings screen is
           exactly how this changes */
        renderNotifications()
    }

    /* ---- the approved channels ---- */

    private val channels = mutableListOf<Channel>()

    /* A row tapped means "take me to that channel", which this screen cannot
       do — ParentActivity owns the WebView. So it is handed back up: we finish
       with the URL, and ParentActivity loads it. Settings is a pass-through for
       that one value and nothing else. It used to pass through TWICE, because
       the list was a third screen behind this one. */
    private fun openChannel(channel: Channel) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OPEN_URL, channel.url))
        finish()
    }

    private fun setUpChannels() {
        channelsList = findViewById(R.id.channels_list)
        channelsEmpty = findViewById(R.id.channels_empty)
        channelsCount = findViewById(R.id.channels_count)
        channelsOrder = findViewById(R.id.channels_order)

        findViewById<ImageButton>(R.id.channels_sort).setOnClickListener {
            SettingsStore.setChannelSort(this, ChannelSort.next(SettingsStore.channelSort(this)))
            showChannels()
        }
        showChannels()
        backfillAvatars()
    }

    /* Rebuilds the rows from scratch. There are as many of these as a parent
       typed in by hand, so removing and re-inflating them costs nothing and
       avoids a diffing adapter for a list that changes twice a year. */
    private fun showChannels() {
        val mode = SettingsStore.channelSort(this)
        /* Read even for the two orders that don't use them, so the label is
           written from the same snapshot the list was sorted from. */
        val counts = WatchStore.countsByWindow(this, System.currentTimeMillis())

        channels.clear()
        channels.addAll(ChannelSort.sort(ChannelStore.get(this).all(), mode, counts))

        channelsCount.text =
            if (channels.isEmpty()) getString(R.string.settings_channels_none)
            else getString(R.string.settings_channels_count, channels.size)
        channelsOrder.text = describe(mode, counts)
        channelsEmpty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE

        channelsList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (channel in channels) bind(inflater, channelsList, channel)
    }

    private fun bind(inflater: LayoutInflater, into: ViewGroup, channel: Channel) {
        val row = inflater.inflate(R.layout.item_approved_channel, into, false)
        row.findViewById<TextView>(R.id.title).text = channel.title
        /* the handle if we have one, the id otherwise — something to tell two
           similarly-named channels apart by */
        row.findViewById<TextView>(R.id.subtitle).text =
            channel.handle?.let { "@$it" } ?: channel.id
        row.setOnClickListener { openChannel(channel) }

        val remove = row.findViewById<ImageButton>(R.id.remove)
        remove.contentDescription = getString(R.string.parent_remove_named, channel.title)
        remove.setOnClickListener { confirmRemove(channel) }

        val avatar = row.findViewById<ImageView>(R.id.avatar)
        val url = channel.avatarUrl
        avatar.setImageDrawable(null)
        Thumbnails.tagFor(avatar, url.orEmpty())
        into.addView(row)
        if (url == null) return
        Thumbnails.cached(url)?.let { avatar.setImageBitmap(it); return }
        lifecycleScope.launch {
            val bmp = Thumbnails.load(url) ?: return@launch
            if (Thumbnails.stillWanted(avatar, url)) avatar.setImageBitmap(bmp)
        }
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

    /* Confirmed, because it is destructive and one row looks much like another
       on a small screen. */
    private fun confirmRemove(channel: Channel) {
        AlertDialog.Builder(this)
            .setTitle(channel.title)
            .setMessage(getString(R.string.parent_remove_confirm, channel.title))
            .setPositiveButton(R.string.parent_remove) { _, _ ->
                /* Everything goes together inside remove(): the row, its
                   videos, its watch history and its cached pictures. */
                ChannelStore.get(this).remove(channel.id)
                showChannels()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                ChannelStore.get(this@SettingsActivity).setAvatar(channel.id, avatar)
                found = true
            }
            if (found) showChannels()
        }
    }

    /* ---- the ? beside each heading ---- */

    /* Every explanation this screen used to print under its headings, moved
       behind a tap. The next-video card had two paragraphs — what the choice
       does, and what "the list" means — and they are one tooltip rather than
       two questions marks a millimetre apart. */
    private fun setUpHelp() {
        Tooltip.attach(findViewById<View>(R.id.help_next_video), buildString {
            append(getString(R.string.settings_next_video_explain))
            append("\n\n")
            append(getString(R.string.settings_next_scope_explain))
        })
        Tooltip.attach(findViewById<View>(R.id.help_hold), R.string.settings_hold_explain)
        Tooltip.attach(findViewById<View>(R.id.help_updates), R.string.settings_updates_explain)
        Tooltip.attach(findViewById<View>(R.id.help_channels), R.string.settings_channels_explain)
    }

    /* ---- what plays next ---- */

    private fun setUpNextVideo() {
        val group = findViewById<RadioGroup>(R.id.next_mode)
        group.check(
            when (SettingsStore.nextMode(this)) {
                Playlist.Mode.IN_ORDER -> R.id.next_in_order
                Playlist.Mode.RANDOM -> R.id.next_random
            },
        )
        /* Checked AFTER the initial state is set, so restoring the stored
           value doesn't write it straight back. Harmless here, but a listener
           that fires while a screen is being built is how a "setting" quietly
           becomes whatever the first radio happens to be. */
        group.setOnCheckedChangeListener { _, id ->
            SettingsStore.setNextMode(
                this,
                if (id == R.id.next_random) Playlist.Mode.RANDOM else Playlist.Mode.IN_ORDER,
            )
        }
    }

    /* ---- how long the hold is ---- */

    /* The slider's arithmetic lives in HoldTime, tested, rather than inline
       here: a control whose range starts at one and whose progress starts at
       zero is exactly where an off-by-one goes unnoticed, and the end of the
       range that would break is the short one — a hold a resting thumb can
       complete. */
    private fun setUpHold() {
        holdValue = findViewById(R.id.hold_value)
        val slider = findViewById<SeekBar>(R.id.hold_slider)
        val seconds = SettingsStore.holdSeconds(this)

        slider.max = HoldTime.sliderMax()
        slider.progress = HoldTime.progressForSeconds(seconds)
        showHold(seconds)

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val chosen = HoldTime.secondsForProgress(progress)
                showHold(chosen)
                /* Written as it moves rather than on release. A player already
                   open keeps the value it read when its video started, so
                   nothing changes underneath a hold in progress. */
                SettingsStore.setHoldSeconds(this@SettingsActivity, chosen)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun showHold(seconds: Int) {
        holdValue.text = resources.getQuantityString(R.plurals.hold_seconds, seconds, seconds)
    }

    /* ---- notifications ---- */

    private fun renderNotifications() {
        when (NotificationPrompt.state(this)) {
            Notifications.State.OK -> {
                /* nothing to say when it works — this block is for the two
                   cases where an update could otherwise go unnoticed */
                notifStatus.visibility = View.GONE
                notifAction.visibility = View.GONE
                notifDivider.visibility = View.GONE
            }
            Notifications.State.ASKABLE -> {
                notifStatus.visibility = View.VISIBLE
                notifAction.visibility = View.VISIBLE
                notifDivider.visibility = View.VISIBLE
                notifStatus.setText(R.string.notif_off_explain)
                notifAction.setText(R.string.notif_turn_on)
                notifAction.setOnClickListener {
                    NotificationPrompt.markAsked(this)
                    askNotifications.launch(NotificationPrompt.PERMISSION)
                }
            }
            Notifications.State.BLOCKED -> {
                notifStatus.visibility = View.VISIBLE
                notifAction.visibility = View.VISIBLE
                notifDivider.visibility = View.VISIBLE
                notifStatus.setText(R.string.notif_blocked_explain)
                notifAction.setText(R.string.notif_open_settings)
                notifAction.setOnClickListener { openNotificationSettings() }
            }
        }
    }

    private fun openNotificationSettings() {
        /* Deep-link to this app's notification screen. Falls back to the app's
           details page, which every version has. */
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (e2: Exception) {}
        }
    }

    /* ---- updates ---- */

    private fun refreshUpdateState() {
        val pending = Updater.pendingUpdateName(this)
        if (pending != null) {
            status.text = getString(R.string.update_ready_fmt, pending)
            action.text = getString(R.string.install_update)
            action.isEnabled = true
            action.setOnClickListener { install() }
            return
        }
        status.text = getString(R.string.up_to_date)
        action.text = getString(R.string.check_for_updates)
        action.isEnabled = true
        action.setOnClickListener { check() }
    }

    private fun check() {
        action.isEnabled = false
        status.text = getString(R.string.checking)
        lifecycleScope.launch {
            val latest = Updater.latestVersion()
            if (latest == null) {
                status.text = getString(R.string.check_failed)
                action.isEnabled = true
                return@launch
            }
            if (latest.first <= Updater.currentVersionCode(this@SettingsActivity)) {
                status.text = getString(R.string.up_to_date)
                action.isEnabled = true
                return@launch
            }
            status.text = getString(R.string.downloading_fmt, latest.second)
            val apk = Updater.ensureApk(this@SettingsActivity, latest.first)
            if (apk == null) {
                status.text = getString(R.string.download_failed)
                action.isEnabled = true
                return@launch
            }
            Updater.rememberPendingName(this@SettingsActivity, latest.second)
            refreshUpdateState()
        }
    }

    /* Tapping Install again retries, rather than being ignored.
     *
     * An install commits a PackageInstaller session and then hears nothing
     * back until the system decides — which sometimes it never does: a
     * confirmation dialog dismissed by a stray tap, a session that stalls.
     * Disabling the button on the first tap left the screen reading
     * "Installing…" for good, with the one control that could fix it greyed
     * out. Whoever hit that would reasonably conclude updating is broken.
     *
     * Retrying is meaningful because installPending abandons any leftover
     * sessions before committing a fresh one, so a second tap genuinely
     * clears a stuck attempt instead of piling another on top.
     *
     * Taps run on a single-threaded executor, so an impatient double-tap
     * queues rather than racing two commits against each other. */
    private fun install() {
        if (!Updater.canInstall(this)) {
            /* no grant, no commit — send them to the toggle rather than
               failing silently */
            Updater.openInstallPermission(this)
            return
        }
        status.text = getString(R.string.installing)
        /* Deliberately left enabled, and labelled so a second tap looks like
           the offer it is. Streaming the APK into the session blocks, so it
           stays off the main thread even though the commit returns quickly. */
        action.text = getString(R.string.install_retry)
        installs.execute {
            val why = Updater.installPending(applicationContext)
            runOnUiThread {
                if (why != null) {
                    status.text = why
                    /* back to the plain offer: this attempt is over */
                    action.text = getString(R.string.install_update)
                }
                /* on success the system kills us as the update applies, so
                   there is deliberately nothing to do in that branch */
            }
        }
    }

    override fun onDestroy() {
        installs.shutdown()
        super.onDestroy()
    }
    companion object {
        /* Read by ParentActivity, which is the only thing that can act on it. */
        const val EXTRA_OPEN_URL = "open_url"
    }

}
