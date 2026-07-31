package dev.vtlinh.ytkids

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.Toast
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

    /* True while the overlay has been deliberately lifted, so YouTube's own
       controls are reachable. It always comes back on its own: either the
       video starts playing again, or nothing is touched for a few seconds. */
    private var revealed = false

    private val reveal = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable { setRevealed(true) }
    private val idleRunnable = Runnable { setRevealed(false) }

    companion object {
        /* YT.PlayerState */
        private const val STATE_PLAYING = 1
        private const val STATE_PAUSED = 2

        /* Long enough that no thumb rests its way through by accident. */
        private const val HOLD_MILLIS = 3000L
        /* And the overlay returns on its own if nothing is touched. */
        private const val IDLE_MILLIS = 7000L

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

            /* The user agent is left alone. A desktop one was tried, on the
               theory that the desktop embed would honour controls: 0 where the
               mobile embed does not; it made no difference to the chrome and
               only got a layout meant for a mouse. The chrome is dealt with by
               the overlay instead — see setUpOverlay. */
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

        /* Hold the corner for three seconds. Deliberately not Android's own
           long-press, which fires in half a second — that is short enough for
           a child to hit by resting a thumb. */
        findViewById<View>(R.id.reveal_corner).setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    reveal.postDelayed(holdRunnable, HOLD_MILLIS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    reveal.removeCallbacks(holdRunnable)
                    v.performClick()
                    true
                }
                else -> true
            }
        }

        /* Visible at the start so it is discoverable, then out of the way. */
        setControlsVisible(true)
    }

    /* Lift the overlay, or put it back.

       While it is lifted the whole of YouTube's player is reachable, which is
       the point — an adult wanting to scrub, or to see what a video actually
       is, has no other way to do it. It is not a mode anyone can be left in by
       accident: it ends when playback resumes, and it ends on its own after a
       few seconds of nobody touching anything. */
    private fun setRevealed(value: Boolean) {
        revealed = value
        overlay?.visibility = if (value) View.GONE else View.VISIBLE
        reveal.removeCallbacks(idleRunnable)
        if (value) {
            reveal.postDelayed(idleRunnable, IDLE_MILLIS)
            /* Say so. Three seconds of holding an invisible corner deserves an
               acknowledgement, and without one it is not obvious the layer went
               anywhere — it was transparent to begin with. */
            Toast.makeText(this, R.string.player_revealed, Toast.LENGTH_SHORT).show()
        }
    }

    /* Every touch in the activity passes through here before it reaches
       whatever will handle it — which is the only way to notice the ones that
       go to the WebView while the overlay is lifted. Without it the idle timer
       would expire mid-scrub. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (revealed && event.actionMasked == MotionEvent.ACTION_DOWN) {
            reveal.removeCallbacks(idleRunnable)
            reveal.postDelayed(idleRunnable, IDLE_MILLIS)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun evalJs(js: String) {
        try { web?.evaluateJavascript(js, null) } catch (e: Exception) {}
    }

    private fun setPlaying(value: Boolean) {
        playing = value
        playPause?.setImageResource(if (value) R.drawable.ic_pause else R.drawable.ic_play)
        playPause?.contentDescription =
            getString(if (value) R.string.player_pause else R.string.player_play)
    }

    /* The player's state, handled as the several things it actually is.
     *
     * Treating it as "playing, or not" was wrong in both directions.
     * BUFFERING is not playing, so every stall — including the one at the
     * start of every video — dropped the paused scrim over the picture. And
     * the auto-hide timer was only re-armed on a NON-playing state, so the
     * transition into PLAYING left our button on screen with no timer running
     * at all: it stayed up for the whole video. */
    private fun applyState(state: Int) {
        /* Playback starting is the signal that whoever lifted the overlay is
           done with it — they pressed play, so the video is for watching
           again. */
        if (state == STATE_PLAYING && revealed) setRevealed(false)
        setPlaying(state == STATE_PLAYING)
        /* Only a real pause draws YouTube's chrome over the frame. Buffering
           does not, and covering it just makes a stall look like a fault. */
        pausedScrim?.visibility =
            if (state == STATE_PAUSED && !showingAd) View.VISIBLE else View.GONE
        /* Unconditional, and after setPlaying: this is what arms the timer,
           and it can only do that once it knows whether we are playing. */
        setControlsVisible(true)
    }

    private fun setControlsVisible(visible: Boolean) {
        playPause?.visibility = if (visible) View.VISIBLE else View.GONE
        hideControls.removeCallbacks(hideControlsRunnable)
        /* Nothing here touches the reveal timers. They belong to a different
           thing — whether the overlay exists at all — and a state change
           arriving mid-hold must not cancel the hold. */
        /* Paused stays on screen: a control that vanishes while nothing is
           happening leaves a child with a frozen picture and nothing to do. */
        if (visible && playing) hideControls.postDelayed(hideControlsRunnable, 2500)
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

        @JavascriptInterface
        fun onState(state: Int) = runOnUiThread { applyState(state) }

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
        /* And put the overlay back. Leaving the screen ends the reveal: the
           adult who lifted it has gone, and the idle timer does not run while
           the activity is stopped, so without this the child comes back to an
           unprotected player. */
        reveal.removeCallbacks(holdRunnable)
        if (revealed) setRevealed(false)
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        goFullscreen()
    }

    override fun onDestroy() {
        hideControls.removeCallbacks(hideControlsRunnable)
        reveal.removeCallbacks(holdRunnable)
        reveal.removeCallbacks(idleRunnable)
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
