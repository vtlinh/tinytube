package dev.vtlinh.ytkids

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var addButton: ImageButton

    /* The approved-channels screen hands back a channel to go and look at. */
    private val approvedList =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringExtra(ApprovedChannelsActivity.EXTRA_OPEN_URL)
                    ?.let { web?.loadUrl(it) }
            }
            /* Channels may have been removed while it was open. */
            updateApproveButton(web?.url)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent)

        current = findViewById(R.id.current)
        addButton = findViewById(R.id.add_channel)

        findViewById<Button>(R.id.kids_mode).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.approved).setOnClickListener {
            approvedList.launch(Intent(this, ApprovedChannelsActivity::class.java))
        }
        /* No gate of its own: getting here already required one. This is the
           same reason ApprovedChannelsActivity opens straight from the button
           next to it — everything on this side of ChallengeActivity is already
           past it. */
        findViewById<ImageButton>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        /* Nothing is loaded yet, so there is certainly no channel to approve.
           This also installs the button's click listener. */
        updateApproveButton(null)

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
            /* Sign-in's last step opens a window. With windows unsupported,
               window.open() returns null and the flow simply stops there —
               which is what "hangs at the last step" looks like from the
               outside. onCreateWindow below loads that target in this same
               WebView instead of opening anything. */
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            useWideViewPort = true
            loadWithOverviewMode = true

            /* Google refuses to sign in when it can tell it is inside a
               WebView, answering with "This browser or app may not be secure"
               rather than a login form. It recognises one by the "; wv" token
               Android puts in the default user agent, so drop just that token
               and leave the rest of the string honest.

               This is a workaround for a deliberate restriction, not a
               supported path: Google may tighten it at any time, and if this
               stops working the answer is a Custom Tab rather than a cleverer
               disguise. */
            userAgentString = userAgentString.replace("; wv", "")
        }

        /* Sign-in is cookies. Third-party ones especially: the flow bounces
           between youtube.com and accounts.google.com and each has to be able
           to set state for the other, and without this it loops back to the
           login page forever. */
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                refuseUnlessBrowsable(request.url?.toString().orEmpty())

            @Suppress("OverridingDeprecatedMember", "DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
                refuseUnlessBrowsable(url.orEmpty())

            override fun onPageFinished(view: WebView, url: String?) {
                current.visibility = View.GONE
                updateApproveButton(url)
            }

            /* YouTube's mobile site is a single-page app: tapping a channel
               swaps the content with pushState and never loads a page, so
               onPageFinished does not fire and the button would stay stuck at
               whatever the last real navigation left it. This fires on those
               history updates, which is what actually keeps it honest. */
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                current.visibility = View.GONE
                updateApproveButton(url)
            }
        }
        /* Without a WebChromeClient a WebView silently drops window.open and
           window.close, which are exactly what the tail of a sign-in flow
           uses. */
        w.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                /* A throwaway WebView purely to learn where the popup wanted
                   to go; the destination is then loaded in the real one, so
                   the flow continues in place instead of in a window we do not
                   show. */
                val probe = WebView(this@ParentActivity)
                probe.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        openInPlace(request.url?.toString().orEmpty())
                        probe.destroy()
                        return true
                    }

                    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String?): Boolean {
                        openInPlace(url.orEmpty())
                        probe.destroy()
                        return true
                    }
                }
                transport.webView = probe
                resultMsg.sendToTarget()
                return true
            }

            /* The page asked to close itself, which the sign-in tail does once
               it is finished. There is no window to close, so take it as "we
               are done here" and go back to YouTube. */
            override fun onCloseWindow(window: WebView) {
                web?.loadUrl(YouTubeUrls.PARENT_START)
            }
        }

        w.loadUrl(YouTubeUrls.PARENT_START)
    }

    private fun openInPlace(url: String) {
        if (url.isEmpty()) return
        if (YouTubeUrls.isParentBrowsable(url)) web?.loadUrl(url) else sayRefused(url)
    }

    /* True means "I handled it", which for a refusal means nothing happens at
       all — and nothing happening is indistinguishable from the app hanging.
       Say which host was refused instead: it is the only way to tell a blocked
       redirect apart from a slow one, and a sign-in chain that stops on an
       unlisted Google host was exactly that failure. */
    private fun refuseUnlessBrowsable(url: String): Boolean {
        if (YouTubeUrls.isParentBrowsable(url)) return false
        sayRefused(url)
        return true
    }

    private fun sayRefused(url: String) {
        val host = Player.hostOf(url) ?: url.take(40)
        say(getString(R.string.parent_blocked_fmt, host))
    }

    /* The channel this page is, if it is one AND we already have it approved.
       Matched locally: a /channel/UC… URL carries the id, and a /@handle URL
       is matched against the handle recorded when it was approved. Going to
       the network to resolve a handle here would make the button flicker
       between states on every navigation. */
    private fun approvedChannelOnPage(url: String?): Channel? {
        if (url == null || !YouTubeUrls.isChannelPage(url)) return null
        val store = ChannelStore.get(this)
        YouTubeUrls.channelIdFromUrl(url)?.let { return store.findByChannelId(it) }
        YouTubeUrls.handleFromUrl(url)?.let { return store.findByHandle(it) }
        return null
    }

    /* One button, two states. On a channel that isn't approved it is a plus
       that approves; on one that is, it is a minus that removes. Off a channel
       page it is disabled.

       A single toggling control rather than two buttons, because the answer to
       "is this channel in the list?" is the thing a parent standing on the
       page actually wants to know, and a button that already tells them costs
       nothing extra to read. */
    private fun updateApproveButton(url: String?) {
        val on = url != null && YouTubeUrls.isChannelPage(url)
        val approved = approvedChannelOnPage(url)

        addButton.isEnabled = on
        /* alpha as well as isEnabled: an ImageButton's drawable does not dim on
           its own, so without this the button looks tappable when it isn't */
        addButton.alpha = if (on) 1f else 0.35f
        addButton.setImageResource(
            if (approved != null) R.drawable.ic_remove_channel else R.drawable.ic_approve_channel,
        )
        addButton.contentDescription = getString(
            if (approved != null) R.string.parent_remove_channel else R.string.parent_add_channel,
        )
        addButton.setOnClickListener {
            val current = approvedChannelOnPage(web?.url)
            if (current != null) confirmRemove(current) else addCurrentChannel()
        }
    }

    private fun addCurrentChannel() {
        val url = web?.url.orEmpty()
        if (url.isEmpty()) return
        addButton.isEnabled = false
        say(getString(R.string.parent_resolving), toast = false)
        lifecycleScope.launch {
            val resolved = ChannelResolver.resolve(url)
            /* Re-derive rather than just re-enabling: resolving hits the
               network, and the parent may have navigated somewhere with no
               channel on it while it ran. Enabling unconditionally would leave
               a live button on a page it can't act on. */
            updateApproveButton(web?.url)
            if (resolved == null) {
                say(getString(R.string.parent_no_channel_here))
                return@launch
            }
            val store = ChannelStore.get(this@ParentActivity)
            store.add(
                channelId = resolved.id,
                title = resolved.title,
                /* remember which @name it was approved from, so the button can
                   recognise this page again without a network call */
                handle = YouTubeUrls.handleFromUrl(url),
                avatarUrl = resolved.avatarUrl,
                nowMillis = System.currentTimeMillis(),
            )
            say(getString(R.string.parent_approved, resolved.title))
            updateApproveButton(web?.url)
        }
    }

    /* Confirmed, because removing is destructive and the button it hangs off
       sits exactly where the approve button was a moment ago. */
    private fun confirmRemove(channel: Channel) {
        AlertDialog.Builder(this)
            .setTitle(channel.title)
            .setMessage(getString(R.string.parent_remove_confirm, channel.title))
            .setPositiveButton(R.string.parent_remove) { _, _ ->
                ChannelStore.get(this).remove(channel.id)
                /* drop the cached feed too, or its videos keep showing in the
                   grid after the channel is gone — and the watch history with
                   it, so an unapproved channel leaves nothing behind */
                ChannelFeeds.forget(this, channel.id)
                WatchStore.forget(this, channel.id)
                say(getString(R.string.parent_removed, channel.title))
                updateApproveButton(web?.url)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /* The status line is normally invisible — a browser does not need a
       caption. It appears only to report something that just happened, and
       goes again on the next navigation. */
    private fun say(message: String, toast: Boolean = true) {
        current.text = message
        current.visibility = View.VISIBLE
        /* Outcomes get a toast; progress does not. "Working out which channel
           this is" is a thing that is happening, not a thing that happened,
           and it was covering the page for three seconds to say so while the
           same words sat in the status line underneath it. */
        if (toast) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        /* Cookies are written lazily and a signed-in session that is never
           flushed is one the parent has to establish again next time. */
        try { CookieManager.getInstance().flush() } catch (e: Exception) {}
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
        findViewById<View>(R.id.web).requestFocus()
    }
}
