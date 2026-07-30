package dev.vtlinh.ytkids

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/* Parent mode: YouTube in a WebView, with a bar across the top for approving
   the channel you're looking at and getting back to kids mode.
 *
 * This screen is the opposite of the rest of the app — it is real YouTube,
 * with search and recommendations and everything else. That is the point, and
 * it is why ChallengeActivity stands in front of it. Two things still hold:
 * navigation is confined to YouTube's own hosts, so a tap on an ad doesn't
 * wander into the open web, and nothing here can start the player.
 */
class ParentActivity : AppCompatActivity() {

    private var web: WebView? = null
    private lateinit var current: TextView
    private lateinit var addButton: Button

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent)

        current = findViewById(R.id.current)
        addButton = findViewById(R.id.add_channel)

        findViewById<Button>(R.id.kids_mode).setOnClickListener { finish() }
        findViewById<Button>(R.id.approved).setOnClickListener { showApproved() }
        addButton.setOnClickListener { addCurrentChannel() }

        val w = findViewById<WebView>(R.id.web)
        web = w
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            /* An adult is driving; autoplaying every thumbnail they scroll past
               is just noise and data. */
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !YouTubeUrls.isParentBrowsable(request.url?.toString().orEmpty())

            @Suppress("OverridingDeprecatedMember", "DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
                !YouTubeUrls.isParentBrowsable(url.orEmpty())

            override fun onPageFinished(view: WebView, url: String?) {
                showWhereWeAre(url.orEmpty())
            }
        }
        w.loadUrl(YouTubeUrls.PARENT_START)
    }

    /* Tell the parent what approving right now would add, before they tap.
       A watch page approves the uploader, which is not obvious. */
    private fun showWhereWeAre(url: String) {
        val id = YouTubeUrls.channelIdFromUrl(url)
        val handle = YouTubeUrls.handleFromUrl(url)
        current.text = when {
            id != null -> getString(R.string.parent_on_channel, id)
            handle != null -> getString(R.string.parent_on_handle, handle)
            url.contains("/watch") -> getString(R.string.parent_on_video)
            else -> getString(R.string.parent_browse_hint)
        }
    }

    private fun addCurrentChannel() {
        val url = web?.url.orEmpty()
        if (url.isEmpty()) return
        addButton.isEnabled = false
        current.setText(R.string.parent_resolving)
        lifecycleScope.launch {
            val resolved = ChannelResolver.resolve(url)
            addButton.isEnabled = true
            if (resolved == null) {
                current.setText(R.string.parent_no_channel_here)
                return@launch
            }
            val store = ChannelStore.get(this@ParentActivity)
            val already = store.contains(resolved.id)
            store.add(resolved.id, resolved.title, System.currentTimeMillis())
            current.text = getString(
                if (already) R.string.parent_already_approved else R.string.parent_approved,
                resolved.title,
            )
            Toast.makeText(
                this@ParentActivity,
                getString(R.string.parent_approved, resolved.title),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /* The approved list, with removal. Without this a mistaken approval could
       only be undone by clearing the app's data. */
    private fun showApproved() {
        val store = ChannelStore.get(this)
        val channels = store.all()
        if (channels.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.parent_approved_title)
                .setMessage(R.string.parent_none_approved)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val names = channels.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.parent_approved_title)
            .setItems(names) { _, which ->
                val channel = channels[which]
                AlertDialog.Builder(this)
                    .setTitle(channel.title)
                    .setMessage(getString(R.string.parent_remove_confirm, channel.title))
                    .setPositiveButton(R.string.parent_remove) { _, _ ->
                        store.remove(channel.id)
                        /* drop the cached feed too, or its videos keep showing
                           in the grid after the channel is gone */
                        ChannelFeeds.forget(this, channel.id)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /* Back walks the browsing history — an adult in a browser expects that —
       and only leaves the screen once there's nowhere back to go. */
    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else finish()
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        web?.let { w ->
            (w.parent as? android.view.ViewGroup)?.removeView(w)
            w.stopLoading()
            w.destroy()
        }
        web = null
        super.onDestroy()
    }

    /* Kept out of onCreate so the hint reads the same on first paint as it
       does after a page load. */
    override fun onStart() {
        super.onStart()
        if (current.text.isNullOrEmpty()) current.setText(R.string.parent_browse_hint)
        findViewById<View>(R.id.web).requestFocus()
    }
}
