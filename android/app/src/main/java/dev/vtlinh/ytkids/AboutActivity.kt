package dev.vtlinh.ytkids

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.graphics.BitmapFactory
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/* The parent-facing screen, reached by long-pressing the grid header.

   Not a settings screen and not where curation happens — channels are approved
   in parent mode. This exists so an adult can see what version is running,
   whether a newer one is waiting, and install it without hunting for the
   notification, plus fix notifications when they're off. */
class AboutActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var notifStatus: TextView
    private lateinit var notifAction: Button
    /* The hairline above the notification block, so it appears and disappears
       with it rather than leaving a rule under nothing. */
    private lateinit var notifDivider: View
    private lateinit var playerMeasure: TextView
    private lateinit var capturePreview: ImageView
    private lateinit var captureNone: TextView
    private lateinit var captureShare: Button
    private lateinit var captureDelete: Button

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
        setContentView(R.layout.activity_about)
        title = getString(R.string.about_title)

        status = findViewById(R.id.status)
        action = findViewById(R.id.action)
        notifStatus = findViewById(R.id.notif_status)
        notifAction = findViewById(R.id.notif_action)
        notifDivider = findViewById(R.id.notif_divider)
        playerMeasure = findViewById(R.id.player_measure)
        capturePreview = findViewById(R.id.capture_preview)
        captureNone = findViewById(R.id.capture_none)
        captureShare = findViewById(R.id.capture_share)
        captureDelete = findViewById(R.id.capture_delete)
        captureShare.setOnClickListener { shareCapture() }
        captureDelete.setOnClickListener {
            CaptureStore.clear(this)
            renderCapture()
            Toast.makeText(this, R.string.about_capture_deleted, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.player_remeasure).setOnClickListener {
            BlockHeightStore.clear(this)
            /* The store is only half of it: the running process caches the
               number, so clearing the preference alone would change nothing
               until the app was killed. */
            PlayerActivity.forgetMeasurement()
            renderMeasurement()
            Toast.makeText(this, R.string.about_remeasured, Toast.LENGTH_SHORT).show()
        }

        /* Neither tab is "here", so neither is drawn as selected — both are
           the way out. About is somewhere you arrive from the grid and leave
           back to it. */
        BottomTabs.bind(this, selected = -1) { BottomTabs.goToGrid(this, it) }

        findViewById<TextView>(R.id.version).text = getString(
            R.string.version_fmt,
            packageManager.getPackageInfo(packageName, 0).versionName,
        )
        val approved = ChannelStore.get(this).all().size
        findViewById<TextView>(R.id.catalog_count).text =
            resources.getQuantityString(R.plurals.approved_channels, approved, approved)
    }

    override fun onResume() {
        super.onResume()
        renderMeasurement()
        renderCapture()
        refreshUpdateState()
        /* re-read on every resume: returning from the system Settings screen is
           exactly how this changes */
        renderNotifications()
    }

    /* ---- the player's measurement ---- */

    /* Everything known about it, whether or not any of it looks wrong.
     *
     * The player blocks a strip along its bottom edge so a scrub that slides
     * off YouTube's seek bar lands on nothing, and the height of that strip is
     * measured off the player's own pixels rather than written down. When the
     * measurement is wrong there is nothing on screen to say so — the strip is
     * simply the wrong size — so this is where it can be read, and reported,
     * without anyone having to catch it happening. */
    private fun renderMeasurement() {
        val live = PlayerActivity.measuredPx()
        val raw = BlockHeightStore.rawPx(this)
        val fallback = resources.getDimensionPixelSize(R.dimen.player_bottom_block)

        /* What is in force comes from the running process, not from the
           preference — the player does not read that back at the moment. The
           stored line below is history, and is labelled as such. */
        val inUse =
            if (live >= 0) getString(R.string.about_measure_measured, live)
            else getString(R.string.about_measure_fallback, fallback)
        val storedText =
            if (raw >= 0) "$raw px" else getString(R.string.about_measure_none)
        val storedFor =
            BlockHeightStore.storedDisplay(this) ?: getString(R.string.about_measure_none)

        playerMeasure.text = getString(
            R.string.about_measure_fmt,
            inUse,
            storedText,
            BlockHeightStore.displayKey(this),
            storedFor,
            "${BlockHeightStore.storedVersion(this)} of ${BlockHeightStore.currentVersion()}",
            BlockHeightStore.note(this),
        )
    }

    /* The frame the analysis looked at, if there is one.
     *
     * Shown small — it is a wide strip and this is a phone — with Share for
     * getting the real pixels off the device, since a screenshot of a preview
     * is not what anyone needs to inspect. */
    private fun renderCapture() {
        val has = CaptureStore.exists(this)
        captureNone.visibility = if (has) View.GONE else View.VISIBLE
        capturePreview.visibility = if (has) View.VISIBLE else View.GONE
        captureShare.isEnabled = has
        captureDelete.isEnabled = has
        if (!has) { capturePreview.setImageDrawable(null); return }
        capturePreview.setImageBitmap(
            try {
                BitmapFactory.decodeFile(CaptureStore.file(this).absolutePath)
            } catch (e: Throwable) {
                null
            },
        )
    }

    private fun shareCapture() {
        if (!CaptureStore.exists(this)) return
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.files", CaptureStore.file(this))
        } catch (e: Throwable) {
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(send, getString(R.string.about_capture_share_title)))
        } catch (e: Throwable) {}
    }

    /* ---- notifications ---- */

    private fun renderNotifications() {
        when (NotificationPrompt.state(this)) {
            Notifications.State.OK -> {
                /* nothing to say when it works — this screen is for the two
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
            if (latest.first <= Updater.currentVersionCode(this@AboutActivity)) {
                status.text = getString(R.string.up_to_date)
                action.isEnabled = true
                return@launch
            }
            status.text = getString(R.string.downloading_fmt, latest.second)
            val apk = Updater.ensureApk(this@AboutActivity, latest.first)
            if (apk == null) {
                status.text = getString(R.string.download_failed)
                action.isEnabled = true
                return@launch
            }
            Updater.rememberPendingName(this@AboutActivity, latest.second)
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
