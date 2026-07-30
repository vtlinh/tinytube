package dev.vtlinh.ytkids

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/* The parent-facing screen, reached by long-pressing the grid header.

   It is not a settings screen — curation happens in the repository, not here.
   It exists so an adult can see what version is running, whether a newer one is
   waiting, and install it without hunting for the notification. */
class AboutActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var notifStatus: TextView
    private lateinit var notifAction: Button

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

        findViewById<TextView>(R.id.version).text = getString(
            R.string.version_fmt,
            packageManager.getPackageInfo(packageName, 0).versionName,
            Updater.currentVersionCode(this),
        )
        val approved = CatalogStore.cached(this).size
        findViewById<TextView>(R.id.catalog_count).text =
            resources.getQuantityString(R.plurals.approved_videos, approved, approved)
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateState()
        /* re-read on every resume: returning from the system Settings screen is
           exactly how this changes */
        renderNotifications()
    }

    /* ---- notifications ---- */

    private fun notificationState(): Notifications.State = Notifications.state(
        sdkInt = Build.VERSION.SDK_INT,
        enabled = NotificationManagerCompat.from(this).areNotificationsEnabled(),
        askedBefore = prefs().getBoolean(ASKED_KEY, false),
        showRationale = Build.VERSION.SDK_INT >= Notifications.RUNTIME_PERMISSION_SDK &&
            shouldShowRequestPermissionRationale(PERMISSION),
    )

    private fun renderNotifications() {
        when (notificationState()) {
            Notifications.State.OK -> {
                /* nothing to say when it works — this screen is for the two
                   cases where an update could otherwise go unnoticed */
                notifStatus.visibility = View.GONE
                notifAction.visibility = View.GONE
            }
            Notifications.State.ASKABLE -> {
                notifStatus.visibility = View.VISIBLE
                notifAction.visibility = View.VISIBLE
                notifStatus.setText(R.string.notif_off_explain)
                notifAction.setText(R.string.notif_turn_on)
                notifAction.setOnClickListener {
                    prefs().edit().putBoolean(ASKED_KEY, true).apply()
                    askNotifications.launch(PERMISSION)
                }
            }
            Notifications.State.BLOCKED -> {
                notifStatus.visibility = View.VISIBLE
                notifAction.visibility = View.VISIBLE
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

    private fun install() {
        if (!Updater.canInstall(this)) {
            /* no grant, no commit — send them to the toggle rather than
               failing silently */
            Updater.openInstallPermission(this)
            return
        }
        action.isEnabled = false
        status.text = getString(R.string.installing)
        /* streaming the APK into the session blocks; keep it off the main
           thread even though the commit itself returns quickly */
        Thread {
            val why = Updater.installPending(applicationContext)
            runOnUiThread {
                if (why != null) {
                    status.text = why
                    action.isEnabled = true
                }
                /* on success the system kills us as the update applies, so
                   there is deliberately nothing to do in that branch */
            }
        }.start()
    }

    private fun prefs() = getSharedPreferences("app", Context.MODE_PRIVATE)

    private companion object {
        const val PERMISSION = "android.permission.POST_NOTIFICATIONS"
        /* Android gives no way to ask "have I requested this before?", and
           shouldShowRequestPermissionRationale returns false both before the
           first request and after a permanent denial — so the two are
           indistinguishable without remembering it ourselves. */
        const val ASKED_KEY = "askedNotificationPermission"
    }
}
