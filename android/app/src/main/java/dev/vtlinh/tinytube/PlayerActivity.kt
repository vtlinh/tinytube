package dev.vtlinh.tinytube

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.PixelCopy
import android.widget.ProgressBar
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

    /* The way out, shown only while the overlay is lifted. See setRevealed. */
    private var backButton: View? = null

    /* Best-effort, from the page. While it is true the overlay stops taking
       touches — an ad has to remain interactive, and being wrong here costs a
       tappable player rather than a blocked one. */
    private var showingAd = false

    /* True while the overlay has been deliberately lifted, so YouTube's own
       controls are reachable. It comes back when play is pressed, or once the
       video has run for a few seconds with nobody touching anything — and,
       whatever state it is in, as soon as the activity leaves the
       foreground. */
    private var revealed = false

    /* Whether the video is running, as the page last reported it. The idle
       countdown that puts the overlay back is armed only while this is true —
       see restartIdleTimer. */
    private var playing = false

    private val reveal = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable { setRevealed(true) }
    private val idleRunnable = Runnable { setRevealed(false) }

    /* Fills the corner's ring over the length of the hold. Kept so a finger
       lifted early can cancel it mid-sweep. */
    private var holdAnimator: ObjectAnimator? = null

    /* The corner's glow, which announces itself when the overlay appears and
       is invisible otherwise. The view underneath stays touchable throughout —
       only the colour fades. */
    private var corner: View? = null
    private val fadeGlowRunnable = Runnable { fadeGlow() }

    /* How long the corner must be held, in milliseconds. The parent's, from
       SettingsStore — read once per video rather than per press, so changing
       it mid-hold cannot leave a ring counting to a different number than the
       one the timer is waiting for. */
    private var holdMillis = HoldTime.DEFAULT_MILLIS

    private var bottomBlocker: View? = null
    private val measureRunnable = Runnable { measureBlockHeight() }
    private var measureAttempts = 0

    /* The blocker height in pixels, or -1 for "not measured yet".
     *
     * An instance field rather than a companion one, so a video that has to
     * measure does its own work. Once a measurement lands it goes to
     * BlockHeightStore and every later video reads it from there instead —
     * which is the same "measure once" the companion field gave, minus the
     * part where one video's answer became another's without either knowing. */
    private var measuredBlockPx: Int = -1

    companion object {
        /* YT.PlayerState */
        private const val STATE_PLAYING = 1
        private const val STATE_PAUSED = 2

        /* The overlay returns on its own if nothing is touched while the video
           is running. */
        private const val IDLE_MILLIS = 5000L

        /* The corner's glow, one second end to end, whenever the overlay comes
           back. A quick rise so it registers, a long fall so it reads as
           something settling rather than something blinking. */
        private const val GLOW_MILLIS = 1000L
        private const val GLOW_IN_MILLIS = 180L
        private const val GLOW_OUT_MILLIS = 500L

        /* Long enough after playback starts for YouTube's controls to have
           finished animating in. Measuring mid-fade reads a half-opaque bar. */
        private const val MEASURE_DELAY_MILLIS = 700L
        /* And how long before looking again when a frame had no bar in it —
           the controls auto-hide, so most frames do not. */
        private const val MEASURE_RETRY_MILLIS = 600L
        /* Enough tries to cover the few seconds of controls that follow a
           touch or the start of playback, and few enough that a device which
           can never produce one stops asking. */
        private const val MEASURE_MAX_ATTEMPTS = 24
        /* How much of the bottom edge is drawn. Not a statement about where
           YouTube's chrome is — Chrome works that out from the pixels — only
           about how much of the screen is worth looking at, and how much is
           deliberately never captured. Two fifths is far more than any inset
           and still leaves the picture out of it. */
        private const val MEASURE_STRIP_FRACTION = 0.4f

        /* Unplayable videos in a row before the player gives up and goes back
           to the grid. A few, because a removed video next to a private one is
           ordinary; not unbounded, because a black screen that silently walks
           a hundred entries is worse than a grid. */
        private const val MAX_CONSECUTIVE_FAILURES = 3

        private const val EXTRA_IDS = "ids"
        private const val EXTRA_INDEX = "index"

        /* The whole list the child was looking at, and which of it they
           tapped. Not one video: what plays next has to come from the same
           place this one did, and the only thing that knows where that was is
           the screen they tapped on. A grid narrowed to one channel therefore
           cannot lead out of that channel, with no rule here to say so.

           Ids only. Titles are not needed once the player is open — it shows
           YouTube's own — and a hundred of them is a hundred strings across a
           binder transaction for nothing. */
        fun start(context: Context, videos: List<Video>, index: Int) {
            if (index < 0 || index >= videos.size) return
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_IDS, videos.map { it.id }.toTypedArray())
                    .putExtra(EXTRA_INDEX, index),
            )
        }
    }

    /* The list this player is walking, and where in it we are. */
    private var ids: List<String> = emptyList()
    private var index = 0

    /* Unplayable videos in a row. Reset by anything actually playing, so it
       counts a run of dead entries rather than a total. */
    private var failures = 0

    /* Whether this video has already been counted as watched. Playback reports
       PLAYING again after every pause and every buffering stall, and a video
       paused six times is not six views. */
    private var counted = false

    /* Whether the corner has announced itself for this video yet. The first
       glow waits for playback rather than firing in onCreate, so it needs
       something to remember that it is still owed. */
    private var glowed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /* The ids arrive through an Intent, which any app on the device can
           send. Validated here rather than trusted for having come from our own
           grid — pageFor refuses too, and this is the cheaper refusal. Filtered
           rather than rejected wholesale, so one bad id in a hundred costs that
           video and not the tap. */
        val sent = intent.getStringArrayExtra(EXTRA_IDS).orEmpty().toList()
        val tapped = sent.getOrNull(intent.getIntExtra(EXTRA_INDEX, 0))
        ids = sent.filter { VideoId.isValid(it) }
        /* Found by identity rather than kept as a number: dropping a bad id
           shifts every index after it, and a silently shifted index plays a
           video nobody tapped. If the tapped one was itself refused there is
           nothing to play and we leave. */
        index = ids.indexOf(tapped)

        val page = ids.getOrNull(index)?.let { Player.pageFor(it) }
        if (page == null) { finish(); return }

        setContentView(R.layout.activity_player)
        goFullscreen()

        val w = findViewById<WebView>(R.id.web)
        web = w

        /* The signed-in session, so a Premium account plays without ads.
         *
         * WebViews share one process-wide cookie store, so the session parent
         * mode established is already here — what was missing is that the
         * player never opted in to using it, and that its document ran on
         * youtube-nocookie.com, which carries no session by design. Player.ORIGIN
         * is youtube.com now; this is the other half.
         *
         * Third-party cookies as well as first: the player document is
         * youtube.com and so is the iframe inside it, but the media and API
         * traffic underneath is spread across googlevideo and ytimg, and a
         * partitioned store is how a "signed in" embed still plays ads.
         *
         * This is the child's screen, so be exact about what it does and does
         * not open. It lets the player authenticate. It does not let the player
         * NAVIGATE anywhere new — Player.isPlayerUrl is unchanged and
         * www.youtube.com was always on it — and every control YouTube draws is
         * still under the overlay. What it does mean is that a navigation that
         * did get through would now show a signed-in page rather than a signed-
         * out one, which is the cost written up in README. */
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }

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
        backButton = findViewById<View>(R.id.player_back).also {
            it.setOnClickListener { finish() }
        }

        /* Measured before — on an earlier video, or in an earlier run of the
           app entirely — so apply it now rather than waiting for this video to
           be paused too. */
        /* Measured once per device and read back for every video after that,
           including after a reboot. This was off while the measurement was
           being worked out — a stale entry, a wrong one and a correct one all
           produce the same screen, so every fix had to get past whatever was
           already on disk before it could be seen. That is what
           BlockHeightStore.VERSION is for: bump it and old entries are
           ignored rather than trusted. */
        if (measuredBlockPx < 0) measuredBlockPx = BlockHeightStore.get(this) ?: -1
        if (measuredBlockPx >= 0) applyBlockHeight(measuredBlockPx)

        /* How long the hold is, as the parent set it. Read here rather than at
           each press so a change cannot land between the ring starting and the
           timer firing, which would have them counting to different numbers. */
        holdMillis = HoldTime.millisFor(SettingsStore.holdSeconds(this))

        /* The overlay takes every touch and does nothing with them. It used to
           summon the corner's tint on a tap; the glow does that job now, at a
           moment nobody is trying to watch anything. Still clickable, because
           that is what stops the touch reaching YouTube underneath. */

        /* Hold the corner. Deliberately not Android's own long-press, which
           fires in half a second — short enough for a child to hit by resting
           a thumb.

           This works whether or not the corner is glowing. Requiring a tap
           first would make it a two-step control and, worse, make it
           unreachable if the tap that summoned it ever failed to register.

           The ring is shown either way, and deliberately without dragging the
           glow up with it: a press gets feedback because it was a press, not
           because a hint happened to be visible. */
        corner?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    reveal.postDelayed(holdRunnable, holdMillis)
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

        /* No glow here, and that is the fix rather than an omission.
         *
         * It used to fire from onCreate, which is a second spent glowing over
         * the black rectangle a WebView shows while it loads — the whole
         * announcement was over before there was a picture to announce
         * anything against. The first one now waits for the video to actually
         * start; see applyState. */
    }

    /* The corner announces itself: up quickly, held, then down slowly, one
     * second end to end.
     *
     * Fired when the overlay BECOMES VISIBLE — at the start of a video and
     * when a reveal ends — and at no other time. The tint it replaces appeared
     * on any tap and faded a second later, which put a coloured wedge over the
     * picture at exactly the moment a child was most likely to be touching the
     * screen. Saying it once, when the thing it belongs to arrives, tells a
     * parent the same thing while nobody is watching anything yet.
     *
     * Only the colour moves. The view keeps its size and keeps taking touches
     * at alpha 0, so the hold is available at every moment — someone who knows
     * where the corner is never has to wait for it. */
    private fun glow() {
        val c = corner ?: return
        reveal.removeCallbacks(fadeGlowRunnable)
        c.animate().cancel()
        c.animate().alpha(1f).setDuration(GLOW_IN_MILLIS).start()
        reveal.postDelayed(fadeGlowRunnable, GLOW_MILLIS - GLOW_OUT_MILLIS)
    }

    private fun fadeGlow() {
        val c = corner ?: return
        c.animate().cancel()
        c.animate().alpha(0f).setDuration(GLOW_OUT_MILLIS).start()
    }

    /* The ring, counting out the hold.
     *
     * Animated rather than stepped, and over exactly the hold the parent set,
     * so what it shows is the truth about when the finger can come off. Any
     * number of seconds of a screen doing nothing is indistinguishable from a
     * dead spot.
     *
     * Its visibility is its own, independent of the corner's alpha — see the
     * layout for why it had to stop being a child of the glowing view. */
    private fun startHoldProgress() {
        val bar = holdProgress ?: return
        holdAnimator?.cancel()
        bar.progress = 0
        bar.visibility = View.VISIBLE
        holdAnimator = ObjectAnimator.ofInt(bar, "progress", 0, bar.max).apply {
            duration = holdMillis
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
       accident: pressing play ends it, and while the video is running it ends
       on its own after a few seconds of nobody touching anything. See
       restartIdleTimer for why a paused player is the exception to the
       second. */
    private fun setRevealed(value: Boolean) {
        revealed = value
        overlay?.visibility = if (value) View.GONE else View.VISIBLE
        /* The exit appears with the rest of the adult's controls and goes with
           them. Back already worked — the system button finishes this activity
           — but nothing on screen said so, and iOS has no system button at all.
           Both platforms now show the same control at the same moment, rather
           than one sitting over every video a child watches. */
        backButton?.visibility = if (value) View.VISIBLE else View.GONE
        /* The ring has done its job either way: the hold completed, or the
           overlay came back and there is no hold in progress to show. */
        stopHoldProgress()
        /* And the overlay returns tintless, whichever way it went. Fading a
           hint back in over a video nobody has touched would undo the point
           of hiding it. */
        reveal.removeCallbacks(fadeGlowRunnable)
        corner?.animate()?.cancel()
        corner?.alpha = 0f
        /* And it announces itself again whenever it comes BACK — the overlay
           reappearing is exactly the moment worth marking, and the only one
           the glow fires on. */
        if (!value) glow()
        /* Lifting the overlay is the other reliable moment: the parent is
           about to touch the player, and a touch is what brings the controls
           back. Also the only moment a scrimmed pause can be measured, since
           the scrim goes with the overlay. */
        if (value) wantMeasurement(MEASURE_RETRY_MILLIS, freshOpportunity = true)
        restartIdleTimer()
    }

    /* The countdown that puts the overlay back, armed only while the video is
       actually running.
     *
     * A paused player does not count down at all, deliberately. Pausing is what
     * an adult does to read something on screen, to look at where the scrubber
     * is, or to hand the phone to someone — none of which produce touches, and
     * all of which used to end with the overlay dropping back mid-sentence. So
     * the timer follows playback: it runs while the video does.
     *
     * This is only the unattended path. Pressing play ends the reveal outright,
     * without waiting — see applyState. So the countdown covers the one case
     * nothing else does: revealed during playback, and then left alone.
     *
     * The trade is that a player left paused and revealed stays that way. What
     * bounds it is the activity: onPause puts the overlay back, so leaving the
     * screen, locking the phone, or switching apps all end the reveal. What is
     * given up is only the case where the phone is set down, unlocked, on a
     * paused video. */
    private fun restartIdleTimer() {
        reveal.removeCallbacks(idleRunnable)
        if (revealed && playing) reveal.postDelayed(idleRunnable, IDLE_MILLIS)
    }

    /* Every touch in the activity passes through here before it reaches
       whatever will handle it — which is the only way to notice the ones that
       go to the WebView while the overlay is lifted. Without it the idle timer
       would expire mid-scrub. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (revealed && event.actionMasked == MotionEvent.ACTION_DOWN) {
            restartIdleTimer()
            /* That tap went to the player and will have brought its controls
               up — the best frame we are going to get. */
            wantMeasurement(MEASURE_DELAY_MILLIS, freshOpportunity = true)
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
        /* Only PLAYING and PAUSED move this. Buffering leaves it alone: a
           stall mid-video is not a pause, and letting it read as one would
           both close the overlay on the way out of the stall and stop the
           countdown every time the network hiccuped. */
        val resumed = state == STATE_PLAYING && !playing
        when (state) {
            STATE_PLAYING -> {
                playing = true
                failures = 0
                noteWatched()
                /* The first frame is the first moment there is anything for the
                   corner to be visible against. Once per video: playback
                   reports PLAYING again after every pause and every buffering
                   stall, and a corner that flashed at each of those would be a
                   fault rather than a hint. */
                if (!glowed) { glowed = true; glow() }
            }
            STATE_PAUSED -> playing = false
        }
        /* Pause to play is somebody pressing play, and that is the clearest
           statement there is that they are finished with the controls: the
           overlay goes back at once rather than counting anything down.
         *
         * It has to be the TRANSITION, not the state. Treating every PLAYING
         * report as "they pressed play" would close the overlay on the far
         * side of a buffering stall, seconds into a scrub nobody had
         * finished. */
        if (resumed && revealed) setRevealed(false) else restartIdleTimer()

        /* Only a real pause draws YouTube's chrome over the frame. */
        pausedScrim?.visibility =
            if (state == STATE_PAUSED && !showingAd) View.VISIBLE else View.GONE

        /* YouTube shows its controls for a few seconds when playback starts,
           which is the one moment guaranteed to happen on every video without
           anybody touching anything. */
        if (state == STATE_PLAYING && !showingAd) wantMeasurement(MEASURE_DELAY_MILLIS)
    }

    /* Ask for a measurement, if one is still wanted.
     *
     * Called at every moment YouTube's controls are likely to be on screen,
     * because that is the only time there is a seek bar to find. The first
     * version waited for a PAUSE, which was close to unreachable: the overlay
     * eats every touch, so a child cannot pause at all, and a parent can only
     * do it after holding the corner. On most installs the measurement simply
     * never ran. */
    private fun wantMeasurement(delayMillis: Long, freshOpportunity: Boolean = false) {
        if (measuredBlockPx >= 0) return
        /* The cap bounds retrying within ONE opportunity, not across them.
           Without this reset the attempts were spent during the first seconds
           of playback — where the controls show for about three and then hide
           — and by the time a parent lifted the overlay, which is the best
           frame there is, the budget was gone and nothing looked again. */
        if (freshOpportunity) measureAttempts = 0
        if (measureAttempts >= MEASURE_MAX_ATTEMPTS) return
        reveal.removeCallbacks(measureRunnable)
        reveal.postDelayed(measureRunnable, delayMillis)
    }

    /* Measure the player's bottom inset from the pixels on screen.
     *
     * The height of the bottom blocker used to be a constant, because the
     * player is a cross-origin iframe and there is no way to ask it where its
     * seek bar is. There is a way to LOOK. Chrome does the finding, and is
     * pure so it can be tested; this does the capturing.
     *
     * PixelCopy rather than WebView.draw(Canvas). Drawing the view to a
     * software canvas is the obvious way and it does not work here: the player
     * is composited in hardware, and what comes back is blank or nearly so —
     * which looked exactly like "this device has no seek bar" and left the
     * fallback in place forever. PixelCopy reads the surface that is actually
     * on screen, which is the thing being measured.
     *
     * Four things about the capture, all deliberate:
     *
     * - Nothing is saved. The bitmap is read into an int array and recycled in
     *   the callback that received it. It is never written to storage, never
     *   handed to another component, and never leaves the process. The pixels
     *   are a measurement, not a picture.
     * - Only the bottom two fifths is copied, via the source rectangle. The
     *   part of the screen with the video in it is never captured, which makes
     *   the above true by construction rather than by promise.
     * - Only while the picture is actually visible. If the paused scrim is up,
     *   the copy would be a rectangle of near-black — PixelCopy sees the whole
     *   composited window, this app's own views included.
     * - It succeeds once, ever. The answer goes to BlockHeightStore, so every
     *   later video reuses it, including after a reboot; it is measured again
     *   only if the display's geometry changes underneath it. A FAILURE stores
     *   nothing and asks for another frame. */
    private fun measureBlockHeight() {
        if (measuredBlockPx >= 0) return
        val w = web ?: return
        val vw = w.width
        val vh = w.height
        if (vw <= 0 || vh <= 0) return
        /* Our own scrim over the frame would be all the copy could see. */
        /* isShown, not visibility: the scrim is a CHILD of the overlay, so
           while the overlay is lifted the scrim is not on screen no matter
           what its own visibility flag says. Testing the flag refused to
           measure in the one state that is ideal for it — paused, overlay
           lifted, controls up and still — which is where this sat doing
           nothing on a real phone. */
        if (pausedScrim?.isShown == true) { wantMeasurement(MEASURE_RETRY_MILLIS); return }

        val stripH = (vh * MEASURE_STRIP_FRACTION).toInt()
        if (stripH <= 0) return
        measureAttempts++

        val loc = IntArray(2)
        w.getLocationInWindow(loc)
        val src = Rect(loc[0], loc[1] + vh - stripH, loc[0] + vw, loc[1] + vh)
        val bmp = try {
            Bitmap.createBitmap(vw, stripH, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            return  /* out of memory on a large display; the fallback stands */
        }

        try {
            PixelCopy.request(window, src, bmp, { result ->
                val found = if (result == PixelCopy.SUCCESS) {
                    readBlockHeight(bmp, vw, stripH)
                } else {
                    /* PixelCopy can refuse a window it considers protected, or
                       one whose surface is not ready yet. Try the older way
                       before giving this frame up: it comes back blank on a
                       hardware-composited player, but not every player is
                       one, and a second chance costs a bitmap. */
                    drawFallback(w, vw, vh, stripH)
                }
                bmp.recycle()
                if (found != null) latchBlockHeight(found) else wantMeasurement(MEASURE_RETRY_MILLIS)
            }, reveal)
        } catch (e: Throwable) {
            /* Some devices refuse a copy of a secure or not-yet-ready surface
               loudly rather than by result code. */
            bmp.recycle()
            wantMeasurement(MEASURE_RETRY_MILLIS)
        }
    }

    /* The pre-PixelCopy way, kept only as a second chance when a copy is
       refused outright. On a hardware-composited player it comes back blank,
       which is what sent this down the wrong path to begin with. */
    private fun drawFallback(w: WebView, vw: Int, vh: Int, stripH: Int): Int? = try {
        val bmp = Bitmap.createBitmap(vw, stripH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.translate(0f, -(vh - stripH).toFloat())
            w.draw(canvas)
            readBlockHeight(bmp, vw, stripH)
        } finally {
            bmp.recycle()
        }
    } catch (e: Throwable) {
        null
    }

    /* Nothing is passed in but the pixels. The bar's own drawn thickness is
       the scale, and it comes out of them — so nothing here has to know the
       device's density or trust a figure somebody eyeballed off a
       screenshot. */
    private fun readBlockHeight(bmp: Bitmap, width: Int, height: Int): Int? = try {
        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        Chrome.blockHeightOrNull(pixels, width, height)
    } catch (e: Throwable) {
        null
    }

    private fun latchBlockHeight(px: Int) {
        measuredBlockPx = px
        BlockHeightStore.put(this, px)
        applyBlockHeight(px)
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

    /* Count this video once, when it actually starts.
     *
     * On PLAYING rather than on the tap that opened it: a video abandoned
     * before its first frame is not something anybody watched, and the list
     * this feeds is meant to say what is actually being watched. Once per
     * video, because pausing is not a second view.
     *
     * Off the main thread — it is a database write in the middle of playback
     * starting, and nothing waits for the answer. */
    private fun noteWatched() {
        if (counted) return
        counted = true
        val id = ids.getOrNull(index) ?: return
        val now = System.currentTimeMillis()
        Thread { WatchStore.record(applicationContext, id, now) }.start()
    }

    /* The video finished: play the next one from the same list, or leave.
     *
     * Either way this happens AT ONCE, before YouTube's end-screen grid of
     * related videos can be looked at, let alone tapped. That grid is the
     * reason the player closed itself on every ended video before there was a
     * next one, and loading over it serves the same purpose.
     *
     * Which list, and therefore what can play next, was decided by the screen
     * the child tapped on — see start(). Whether it is the following video or
     * a random one is the parent's, from SettingsActivity. */
    private fun playNext() {
        val next = Playlist.next(
            count = ids.size,
            current = index,
            mode = SettingsStore.nextMode(this),
            roll = { n -> java.util.concurrent.ThreadLocalRandom.current().nextInt(n) },
        )
        val page = next?.let { Player.pageFor(ids[it]) }
        if (next == null || page == null) { finish(); return }

        index = next
        /* The overlay goes back over the new video whatever state it was in.
           A parent who lifted it to scrub the last one has not asked for the
           next one to arrive unprotected. */
        if (revealed) setRevealed(false)
        playing = false
        counted = false
        glowed = false
        web?.loadDataWithBaseURL(Player.ORIGIN, page, "text/html", "utf-8", null)
    }

    private inner class Bridge {
        /* The video finished. */
        @JavascriptInterface
        fun onEnded() = runOnUiThread { playNext() }

        /* The IFrame API reports an unplayable video — removed, made private,
           or embedding disabled by its owner. Nothing to show and nothing the
           child can do, so move on rather than sit on a black rectangle.
         *
         * Moving on rather than leaving, now that there is a list: one video
         * an uploader made private should not end an afternoon. What stops it
         * running away is the counter — a list where everything fails would
         * otherwise be walked end to end in a second, or in RANDOM's case
         * never stop at all. */
        @JavascriptInterface
        fun onError(code: String) = runOnUiThread {
            failures++
            if (failures > MAX_CONSECUTIVE_FAILURES) finish() else playNext()
        }

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
        reveal.removeCallbacks(fadeGlowRunnable)
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
        reveal.removeCallbacks(fadeGlowRunnable)
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
