package dev.vtlinh.ytkids

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

/* Plays one approved video, fullscreen, and finishes when it ends.

   Everything here is subtraction. A stock WebView pointed at an embed is one
   tap away from the whole of YouTube: end-screen cards, the channel avatar, the
   "Watch on YouTube" chrome, a long-press context menu offering to open the
   link elsewhere. Each of those is closed off below, and the host allowlist in
   Player.isPlayerUrl catches anything missed. */
class PlayerActivity : AppCompatActivity() {

    private var web: WebView? = null
    private var overlay: android.widget.FrameLayout? = null
    private var playPause: ImageButton? = null
    private var pausedScrim: View? = null

    /* Mirrors the player's state so the button shows what tapping it will do
       rather than what just happened. */
    private var playing = true

    /* Best-effort, from the page. While it is true the overlay stops taking
       touches — an ad has to remain interactive, and being wrong here costs a
       tappable player rather than a blocked one. */
    private var showingAd = false

    private val hideControls = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }

    /* Once per video: the tap that dismisses YouTube's opening chrome. */
    private val chrome = Handler(Looper.getMainLooper())
    private var dismissedChrome = false

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, video: Video) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_ID, video.id)
                    .putExtra(EXTRA_TITLE, video.title),
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        /* The id arrives through an Intent, which any app on the device can
           send. Validate it here rather than trusting that it came from our own
           grid — pageFor refuses too, and this is the cheaper refusal. */
        val page = Player.pageFor(id)
        if (page == null) { finish(); return }

        setContentView(R.layout.activity_player)
        goFullscreen()

        val w = findViewById<WebView>(R.id.web)
        web = w

        w.settings.apply {
            javaScriptEnabled = true               // the IFrame API is javascript
            domStorageEnabled = true               // the player stores its state
            mediaPlaybackRequiresUserGesture = false  // we autoplay deliberately
            /* Nothing below is needed by an embedded player, and each is a way
               out of it or into the device. */
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            /* No pinch-zoom: it does nothing useful to a video and leaves the
               frame stranded off-centre when small fingers do it by accident. */
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        /* The long-press menu offers "Open in new tab" and "Copy link address"
           on the player chrome — both routes out. */
        w.setOnLongClickListener { true }
        w.isLongClickable = false
        w.isHapticFeedbackEnabled = false

        w.addJavascriptInterface(Bridge(), "Bridge")
        w.webViewClient = LockedClient()

        /* Loaded with the embed origin as the base URL so the IFrame API sees a
           real origin rather than "null" and postMessage between the page and
           the player frame works. */
        w.loadDataWithBaseURL(Player.ORIGIN, page, "text/html", "utf-8", null)

        setUpOverlay()
    }

    /* The overlay: a transparent native layer above the WebView that takes
       every touch, plus this app's own play/pause drawn on it.

       Native rather than part of the page, because a view in this hierarchy is
       unambiguously on top and nothing the page does can reach past it. That
       is what makes YouTube's surface — end-screen cards especially, which
       appear over the last stretch of a video and cannot be turned off by any
       player parameter — impossible to tap even while visible.

       Tapping anywhere else shows the control and starts the timer that hides
       it again, so a video is a video rather than a video with a button
       permanently sitting on it. */
    private fun setUpOverlay() {
        val o = findViewById<android.widget.FrameLayout>(R.id.overlay)
        overlay = o
        playPause = findViewById(R.id.play_pause)
        pausedScrim = findViewById(R.id.paused_scrim)

        o.setOnClickListener {
            if (playPause?.visibility == View.VISIBLE) setControlsVisible(false)
            else setControlsVisible(true)
        }
        playPause?.setOnClickListener {
            if (playing) evalJs("window.ytk && window.ytk.pause()")
            else evalJs("window.ytk && window.ytk.play()")
            /* Optimistic, then corrected by onState — waiting for the round
               trip makes the button feel broken on a slow frame. */
            setPlaying(!playing)
            setControlsVisible(true)
        }

        /* Visible at the start so it is discoverable, then out of the way. */
        setControlsVisible(true)
    }

    private fun evalJs(js: String) {
        try { web?.evaluateJavascript(js, null) } catch (e: Exception) {}
    }

    private fun setPlaying(value: Boolean) {
        playing = value
        playPause?.setImageResource(if (value) R.drawable.ic_pause else R.drawable.ic_play)
        playPause?.contentDescription =
            getString(if (value) R.string.player_pause else R.string.player_play)
        /* Paused is when YouTube draws its title and "Watch on YouTube" chrome
           over the frame, and no player parameter turns that off any more.
           Cover it — but only while paused, and never during an ad. */
        pausedScrim?.visibility = if (!value && !showingAd) View.VISIBLE else View.GONE
    }

    private fun setControlsVisible(visible: Boolean) {
        playPause?.visibility = if (visible) View.VISIBLE else View.GONE
        hideControls.removeCallbacks(hideControlsRunnable)
        /* Paused stays on screen: a control that vanishes while nothing is
           happening leaves a child with a frozen picture and nothing to do. */
        if (visible && playing) hideControls.postDelayed(hideControlsRunnable, 2500)
    }

    /* YouTube shows its title, Share, "More videos" and logo for the first
       seconds of a video. It draws that itself — no tap is needed to summon
       it, so blocking taps never removed it, and no player parameter turns it
       off any more.
     *
     * A tap on the video toggles it, and the page never gets one because the
     * overlay eats them all. So send it one directly: dispatchTouchEvent goes
     * to the WebView underneath, past the overlay.
     *
     * With controls: 0 a tap may toggle play/pause rather than the chrome, and
     * which one it does is not something to rely on — so the state is checked
     * shortly after and playback resumed if the tap paused it. That makes the
     * bad outcome a brief stutter rather than a video that silently stopped.
     *
     * The point is chosen off-centre and well inside the frame: the edges are
     * where the chrome's own buttons live, and hitting one of those would be
     * the one thing this must not do. */
    private fun dismissYouTubeChrome() {
        val w = web ?: return
        if (w.width <= 0 || w.height <= 0) return
        val x = w.width * 0.25f
        val y = w.height * 0.5f
        val t = SystemClock.uptimeMillis()
        try {
            MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).let {
                w.dispatchTouchEvent(it); it.recycle()
            }
            MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0).let {
                w.dispatchTouchEvent(it); it.recycle()
            }
        } catch (e: Exception) {
            return
        }
        /* Undo the side effect if there was one. */
        chrome.postDelayed({
            if (!playing && !showingAd) {
                evalJs("window.ytk && window.ytk.play()")
                setPlaying(true)
            }
        }, 400)
    }

    /* While an ad is playing the overlay stops intercepting, so the ad stays
       interactive. Our own control goes with it — there is nothing useful for
       it to do to an ad, and leaving it would suggest otherwise. */
    private fun setShowingAd(ad: Boolean) {
        showingAd = ad
        overlay?.isClickable = !ad
        overlay?.isFocusable = !ad
        if (ad) {
            hideControls.removeCallbacks(hideControlsRunnable)
            playPause?.visibility = View.GONE
            pausedScrim?.visibility = View.GONE
        } else {
            setControlsVisible(true)
        }
    }

    /* Every navigation the page attempts, including the ones a tap on an
       end-screen card produces. Anything that isn't the player's own traffic is
       refused outright — not opened in a browser, not handed to the YouTube
       app, just dropped. Leaving the app is the one thing this screen must not
       do. */
    private inner class LockedClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString().orEmpty()
            /* true == "I handled it", which here means "this does not happen" */
            return !Player.isPlayerUrl(url)
        }

        @Suppress("OverridingDeprecatedMember", "DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
            /* API < 24 path. Same rule; without it the modern override is
               simply not consulted on those devices and everything navigates. */
            return !Player.isPlayerUrl(url.orEmpty())
        }
    }

    private inner class Bridge {
        /* The video finished. Close the player before the end-screen grid of
           related videos can be looked at, let alone tapped. */
        @JavascriptInterface
        fun onEnded() = runOnUiThread { finish() }

        /* The IFrame API reports an unplayable video — removed, made private,
           or embedding disabled by its owner. Nothing to show and nothing the
           child can do, so return to the grid rather than sit on a black
           rectangle. */
        @JavascriptInterface
        fun onError(code: String) = runOnUiThread { finish() }

        /* YT.PlayerState: 1 is playing, everything else is not. */
        @JavascriptInterface
        fun onState(state: Int) = runOnUiThread {
            setPlaying(state == 1)
            if (state != 1) setControlsVisible(true)
            if (state == 1 && !dismissedChrome) {
                dismissedChrome = true
                chrome.postDelayed(::dismissYouTubeChrome, 1200)
            }
        }

        @JavascriptInterface
        fun onAd(isAd: Boolean) = runOnUiThread { setShowingAd(isAd) }
    }

    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    /* Back returns to the grid. It never navigates within the WebView — going
       "back" inside the player would land on a previous YouTube page rather
       than on the tiles, which is the opposite of what the button should do
       here. */
    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
    override fun onBackPressed() {
        finish()
    }

    override fun onPause() {
        super.onPause()
        /* Pause the media rather than letting it play on under the lock screen
           or behind another app. */
        web?.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        goFullscreen()
    }

    override fun onDestroy() {
        hideControls.removeCallbacks(hideControlsRunnable)
        chrome.removeCallbacksAndMessages(null)
        /* Detach before destroying: a WebView torn down while still attached
           leaks its window, and this activity is created and destroyed once per
           video watched. */
        web?.let { w ->
            (w.parent as? android.view.ViewGroup)?.removeView(w)
            w.removeJavascriptInterface("Bridge")
            w.stopLoading()
            w.destroy()
        }
        web = null
        super.onDestroy()
    }
}
