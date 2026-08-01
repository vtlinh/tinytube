package dev.vtlinh.ytkids

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
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
    private var pausedScrim: View? = null
    private var holdProgress: ProgressBar? = null

    /* Best-effort, from the page. While it is true the overlay stops taking
       touches — an ad has to remain interactive, and being wrong here costs a
       tappable player rather than a blocked one. */
    private var showingAd = false

    /* True while the overlay has been deliberately lifted, so YouTube's own
       controls are reachable. It always comes back on its own: either the
       video starts playing again, or nothing is touched for a few seconds. */
    private var revealed = false

    private val reveal = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable { setRevealed(true) }
    private val idleRunnable = Runnable { setRevealed(false) }

    /* Fills the corner's ring over the length of the hold. Kept so a finger
       lifted early can cancel it mid-sweep. */
    private var holdAnimator: ObjectAnimator? = null

    /* The corner's tint, which is shown on demand rather than permanently.
       The view underneath stays touchable throughout — only the tint fades. */
    private var corner: View? = null
    private val fadeTintRunnable = Runnable { setTintShown(false) }

    private var bottomBlocker: View? = null
    private val measureRunnable = Runnable { measureBlockHeight() }

    companion object {
        /* YT.PlayerState */
        private const val STATE_PLAYING = 1
        private const val STATE_PAUSED = 2

        /* Long enough that no thumb rests its way through by accident — four
           times Android's own long-press, which is the thing being avoided. */
        private const val HOLD_MILLIS = 2000L
        /* And the overlay returns on its own if nothing is touched. */
        private const val IDLE_MILLIS = 7000L
        /* How long the corner's tint stays up after a tap. Short: it is a
           reminder of where to press, not something to watch a video through. */
        private const val TINT_MILLIS = 1000L
        private const val TINT_IN_MILLIS = 120L
        private const val TINT_OUT_MILLIS = 400L

        /* Long enough after a pause for YouTube's controls to have finished
           animating in. Measuring mid-fade reads a half-opaque bar. */
        private const val MEASURE_DELAY_MILLIS = 400L
        /* Floors under the dp figures in dimens, for a player short enough
           that 200dp would be most of it. Whichever is larger wins, so the
           measurement degrades to "look at a third of the screen" rather than
           to "look at all of it" on an unusually squat display. */
        private const val MEASURE_MAX_FRACTION = 0.30f
        private const val MEASURE_STRIP_FRACTION = 0.35f

        /* The measured blocker height, in pixels, or 0 for "not yet".
         *
         * Deliberately here rather than on the instance: it is a property of
         * this device and this build of YouTube's player, not of one video, so
         * measuring it once is enough for every video afterwards. A process
         * restart measures again, which is the right cadence for something
         * that only changes when the player does. */
        @Volatile private var measuredBlockPx: Int = 0

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
       every touch.

       Native rather than part of the page, because a view in this hierarchy is
       unambiguously on top and nothing the page does can reach past it. That
       is what makes YouTube's surface — end-screen cards especially, which
       appear over the last stretch of a video and cannot be turned off by any
       player parameter — impossible to tap even while visible.

       It carries no controls of its own. Our own play/pause was tried and
       taken back out: it sat on the picture, it duplicated a button YouTube
       already draws underneath, and a video that plays start to finish needs
       no button at all. The one thing on the overlay is the corner, and all
       that does is hand the player back to an adult.

       Nothing here touches R.id.bottom_blocker, which needs no wiring: it is
       a sibling of the overlay, not a child, so it keeps blocking the strip
       under YouTube's seek bar even while the overlay is lifted. See the
       layout and dimens. */
    private fun setUpOverlay() {
        overlay = findViewById(R.id.overlay)
        pausedScrim = findViewById(R.id.paused_scrim)
        holdProgress = findViewById(R.id.reveal_progress)
        corner = findViewById(R.id.reveal_corner)
        bottomBlocker = findViewById(R.id.bottom_blocker)

        /* Already worked out earlier in this process — apply it now rather
           than waiting for this video to be paused too. */
        if (measuredBlockPx > 0) applyBlockHeight(measuredBlockPx)

        /* A tap anywhere shows the corner. Nothing else: the overlay has no
           controls to toggle, so this is the whole of what tapping does. */
        overlay?.setOnClickListener { setTintShown(true) }

        /* Hold the corner for two seconds. Deliberately not Android's own
           long-press, which fires in half a second — that is short enough for
           a child to hit by resting a thumb.

           This works whether or not the tint is showing. Requiring the tap
           first would make the corner a two-step control and, worse, make it
           unreachable if the tap that summons it ever failed to register.

           The ring is shown either way, and deliberately without dragging the
           tint up with it: a press gets feedback because it was a press, not
           because a hint happened to be visible. */
        corner?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    reveal.postDelayed(holdRunnable, HOLD_MILLIS)
                    startHoldProgress()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    reveal.removeCallbacks(holdRunnable)
                    stopHoldProgress()
                    v.performClick()
                    true
                }
                else -> true
            }
        }
    }

    /* The corner's tint, faded in on a tap and back out a few seconds later.
     *
     * Only the tint moves. The view keeps its size and keeps taking touches at
     * alpha 0, so the hold is available at every moment — someone who knows
     * where the corner is never has to tap first. */
    private fun setTintShown(shown: Boolean) {
        val c = corner ?: return
        reveal.removeCallbacks(fadeTintRunnable)
        c.animate().cancel()
        c.animate()
            .alpha(if (shown) 1f else 0f)
            .setDuration(if (shown) TINT_IN_MILLIS else TINT_OUT_MILLIS)
            .start()
        if (shown) reveal.postDelayed(fadeTintRunnable, TINT_MILLIS)
    }

    /* The ring, counting out the hold.
     *
     * Animated rather than stepped, and over exactly HOLD_MILLIS, so what it
     * shows is the truth about when the finger can come off. Two seconds of
     * a screen doing nothing is indistinguishable from a dead spot.
     *
     * Its visibility is its own, independent of the tint's alpha — see the
     * layout for why it had to stop being a child of the tinted view. */
    private fun startHoldProgress() {
        val bar = holdProgress ?: return
        holdAnimator?.cancel()
        bar.progress = 0
        bar.visibility = View.VISIBLE
        holdAnimator = ObjectAnimator.ofInt(bar, "progress", 0, bar.max).apply {
            duration = HOLD_MILLIS
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopHoldProgress() {
        holdAnimator?.cancel()
        holdAnimator = null
        holdProgress?.let {
            it.visibility = View.INVISIBLE
            it.progress = 0
        }
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
        /* The ring has done its job either way: the hold completed, or the
           overlay came back and there is no hold in progress to show. */
        stopHoldProgress()
        /* And the overlay returns tintless, whichever way it went. Fading a
           hint back in over a video nobody has touched would undo the point
           of hiding it. */
        reveal.removeCallbacks(fadeTintRunnable)
        corner?.animate()?.cancel()
        corner?.alpha = 0f
        reveal.removeCallbacks(idleRunnable)
        if (value) {
            reveal.postDelayed(idleRunnable, IDLE_MILLIS)
            /* Say so. The overlay was transparent, so lifting it changes very
               little on screen — without a word it is not obvious the three
               seconds did anything. */
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

    /* The player's state.
     *
     * Only two things depend on it, and BUFFERING must be neither of them:
     * it is not playing, but it is not paused either, and treating every
     * stall — including the one at the start of every video — as a pause
     * dropped the scrim over the picture each time. */
    private fun applyState(state: Int) {
        /* Playback starting is the signal that whoever lifted the overlay is
           done with it — they pressed play, so the video is for watching
           again. */
        if (state == STATE_PLAYING && revealed) setRevealed(false)
        /* Only a real pause draws YouTube's chrome over the frame. */
        pausedScrim?.visibility =
            if (state == STATE_PAUSED && !showingAd) View.VISIBLE else View.GONE

        /* A pause is the one moment the controls are certainly on screen and
           certainly still, which is why the measurement waits for one. */
        if (state == STATE_PAUSED && !showingAd && measuredBlockPx == 0) {
            reveal.removeCallbacks(measureRunnable)
            reveal.postDelayed(measureRunnable, MEASURE_DELAY_MILLIS)
        }
    }

    /* Measure the player's bottom inset from the pixels it actually drew.
     *
     * The height of the bottom blocker used to be a constant, because the
     * player is a cross-origin iframe and there is no way to ask it where its
     * seek bar is. There is a way to LOOK, though: draw the WebView onto a
     * bitmap and find the bar. Chrome does the finding, and is pure so it can
     * be tested; this does the capturing.
     *
     * Three things about the capture, all deliberate:
     *
     * - Nothing is saved. The bitmap exists inside this method, is read into
     *   an int array, and is recycled before the method returns. It is never
     *   written to storage, never handed to another component, and never
     *   leaves the process. The pixels are a measurement, not a picture.
     * - Only the bottom fifth is drawn at all, by translating the canvas. The
     *   part of the screen with the video in it is never captured, which makes
     *   the above true by construction rather than by promise.
     * - It happens once. measuredBlockPx is on the companion, so every later
     *   video in this process reuses the answer.
     *
     * Any failure leaves the compiled-in fallback in place, which is what the
     * app used before this existed. */
    private fun measureBlockHeight() {
        if (measuredBlockPx > 0) return
        val w = web ?: return
        val vw = w.width
        val vh = w.height
        if (vw <= 0 || vh <= 0) return

        /* Both bounds come from dp, floored by a fraction of the player.
         *
         * dp because YouTube's chrome is a fixed physical size — a bar, a row
         * of buttons, an inset — and stays that size whatever the resolution
         * or aspect ratio. The fractions are only there so an unusually squat
         * player, where 200dp would be most of the screen, degrades to looking
         * at a third of itself rather than at all of it. */
        val maxPx = maxOf(
            resources.getDimensionPixelSize(R.dimen.player_chrome_max),
            (vh * MEASURE_MAX_FRACTION).toInt(),
        )
        val stripH = minOf(
            vh,
            maxOf(
                maxPx + resources.getDimensionPixelSize(R.dimen.player_chrome_headroom),
                (vh * MEASURE_STRIP_FRACTION).toInt(),
            ),
        )
        if (stripH <= 0) return
        val fallback = resources.getDimensionPixelSize(R.dimen.player_bottom_block)

        val measured = try {
            val bmp = Bitmap.createBitmap(vw, stripH, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bmp)
                /* Slide the WebView up so only its last stripH rows land in
                   the bitmap. */
                canvas.translate(0f, -(vh - stripH).toFloat())
                w.draw(canvas)
                val pixels = IntArray(vw * stripH)
                bmp.getPixels(pixels, 0, vw, 0, 0, vw, stripH)
                Chrome.blockHeight(
                    pixels,
                    vw,
                    stripH,
                    fallbackPx = fallback,
                    maxPx = maxPx,
                    /* Left reachable under the bar, so a thumb aiming at a
                       line under 4dp thick still lands on it. */
                    touchMarginPx =
                        resources.getDimensionPixelSize(R.dimen.player_seek_touch_margin),
                )
            } finally {
                bmp.recycle()
            }
        } catch (e: Throwable) {
            /* OutOfMemory on a large display, or a WebView that refuses to
               draw to a software canvas. Neither is worth crashing a video
               over; the constant was good enough before. */
            fallback
        }

        measuredBlockPx = measured
        applyBlockHeight(measured)
    }

    private fun applyBlockHeight(px: Int) {
        val b = bottomBlocker ?: return
        if (b.layoutParams.height == px) return
        b.layoutParams = b.layoutParams.apply { height = px }
        b.requestLayout()
    }

    /* While an ad is playing the overlay stops intercepting, so the ad stays
       interactive. The reveal corner keeps working through it: it is the way
       out of a stuck player, and an ad is exactly when a parent might want
       one. */
    private fun setShowingAd(ad: Boolean) {
        showingAd = ad
        overlay?.isClickable = !ad
        overlay?.isFocusable = !ad
        if (ad) pausedScrim?.visibility = View.GONE
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
        stopHoldProgress()
        reveal.removeCallbacks(fadeTintRunnable)
        corner?.animate()?.cancel()
        corner?.alpha = 0f
        if (revealed) setRevealed(false)
        /* A pending measurement dies with the screen. Drawing a WebView that
           has just been paused would read a stale or blank frame and pin the
           wrong height for the rest of the process. */
        reveal.removeCallbacks(measureRunnable)
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        goFullscreen()
    }

    override fun onDestroy() {
        reveal.removeCallbacks(holdRunnable)
        reveal.removeCallbacks(idleRunnable)
        reveal.removeCallbacks(fadeTintRunnable)
        reveal.removeCallbacks(measureRunnable)
        holdAnimator?.cancel()
        holdAnimator = null
        corner?.animate()?.cancel()
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
