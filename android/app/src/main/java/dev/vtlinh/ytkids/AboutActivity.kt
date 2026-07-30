package dev.vtlinh.ytkids

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/* The parent-facing screen, reached by long-pressing the grid header.

   It is not a settings screen — curation happens in the repository, not here.
   It exists so an adult can see what version is running, whether a newer one is
   waiting, and install it without hunting for the notification. */
class AboutActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var action: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        title = getString(R.string.about_title)

        status = findViewById(R.id.status)
        action = findViewById(R.id.action)

        findViewById<TextView>(R.id.version).text = getString(
            R.string.version_fmt,
            packageManager.getPackageInfo(packageName, 0).versionName,
            Updater.currentVersionCode(this),
        )
        findViewById<TextView>(R.id.catalog_count).text = resources.getQuantityString(
            R.plurals.approved_videos, CatalogStore.cached(this).size, CatalogStore.cached(this).size,
        )
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateState()
    }

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
}
