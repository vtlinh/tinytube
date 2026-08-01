package dev.vtlinh.tinytube

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/* Everything parent-facing that isn't the approved list.

   Not exported, and reached only from parent mode's bar, which is itself only
   reachable through a RESULT_OK from ChallengeActivity. Nothing here needs a
   gate of its own; adding one would be asking the same question twice.

   The bottom half of this screen used to be AboutActivity, opened by
   long-pressing the grid's title. That put a parent-facing screen on the
   child's side of the app, behind a gesture nobody would guess was there. Its
   "N approved channels" line went with the move: the approved list is one tap
   away in the bar above this screen, and a count of a list you can see is not
   news.

   Settings save on the tap rather than behind a Save button. There is no draft
   state to lose and nothing to confirm; the control IS the setting. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var holdValue: TextView

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
}
