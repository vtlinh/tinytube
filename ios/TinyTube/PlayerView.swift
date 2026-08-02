import SwiftUI
import TinyTubeCore

/* The player screen. Counterpart of PlayerActivity.

   WHAT PLAYS NEXT COMES FROM THE LIST THE CHILD TAPPED ON. This screen is
   handed the whole visible list and an index, so a video started from a
   channel-filtered grid cannot lead out of that channel — and there is no rule
   in here saying so, which is the point. The screen that was on is the
   authority; the player has no idea of its own about what is playable.

   A native overlay covers the web view so none of YouTube's controls can be
   touched. Holding the top-right corner lifts it for as long as the settings
   say. The corner is invisible and glows for a second when a video starts and
   each time the overlay comes back, so an adult can find it without a coloured
   wedge sitting over the picture the whole time.

   The first glow waits for playback rather than firing when the screen opens: a
   web view shows a black rectangle while it loads, and a glow spent against
   that is one nobody sees. */
struct PlayerView: View {

    let videos: [Video]
    @State var index: Int
    let onClose: () -> Void

    @Environment(\.scenePhase) private var scenePhase

    @State private var overlayLifted = false
    @State private var holdProgress = 0.0
    @State private var glowing = false
    /* When a TOUCH last lit the corner. Only touch glows are rationed, and only
       against each other — see glowMinGap. */
    @State private var lastTouchGlowAt: Date?
    @State private var glowedThisVideo = false
    @State private var paused = false
    @State private var showingAd = false
    @State private var failures = 0
    @State private var blockPoints = PlayerChrome.currentPoints()
    @State private var holdTask: Task<Void, Never>?
    @State private var liftTask: Task<Void, Never>?

    private let measurement = ScreenMeasurement()

    /* A list where everything fails would otherwise be walked end to end in a
       moment — or, in RANDOM's case, never stop at all. */
    private static let maxConsecutiveFailures = 3

    /* How long the overlay stays lifted with nobody touching anything. Matches
       IDLE_MILLIS in PlayerActivity. Distinct from the hold duration, which is
       how long an adult presses to get IN — see restartIdleTimer. */
    private static let idleSeconds: TimeInterval = 5

    /* The shortest gap between two TOUCH glows. Matches GLOW_MIN_GAP_MILLIS.
     *
     * A touch on the locked overlay glows the corner, because a child pawing at
     * a video is exactly when an adult is about to be handed the phone — and
     * the corner is invisible, so without this they are hunting for it. But a
     * child does not tap once, and a corner that lit on every tap would be a
     * flashing light over the video: useless as a hint, and an advertisement
     * that something is there. Ten seconds turns a burst of taps into one glow.
     *
     * ⚠️ IT RATIONS TOUCH GLOWS AGAINST EACH OTHER, AND NOTHING ELSE. The first
     * version put this check inside glow() itself, so the glow that fires when
     * a video STARTS claimed the window — and a tap in the ten seconds after a
     * video started, which is exactly when anyone would try it, did nothing.
     * The feature looked broken because the commonest case was the suppressed
     * one. */
    private static let glowMinGap: TimeInterval = 10

    private var current: Video? {
        videos.indices.contains(index) ? videos[index] : nil
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()

                if let current {
                    PlayerWebView(
                        videoId: current.id,
                        onState: applyState,
                        onEnded: playNext,
                        onError: handleError
                    )
                    .ignoresSafeArea()
                    .id(current.id)
                }

                /* YouTube draws its own chrome over a paused frame, so cover it
                   — but not during an ad, where the frame is the ad. */
                if paused && !showingAd && !overlayLifted {
                    Color.black.opacity(0.9).ignoresSafeArea()
                }

                /* While an ad is playing this stops intercepting, so the ad
                   stays interactive. The reveal corner keeps working through
                   it: it is the way out of a stuck player, and an ad is exactly
                   when a parent might want one.
                 *
                 * ⚠️ ABOVE THE PAUSED SCRIM, not below it. A `Color` is
                   hit-testable, so with the scrim on top a tap on a PAUSED
                   video never reached this at all — and a paused video is one
                   of the likelier moments for a child to be prodding the
                   screen. Being above it changes nothing visually: this layer
                   is clear. */
                if !overlayLifted && !showingAd {
                    Color.clear
                        .contentShape(Rectangle())
                        .ignoresSafeArea()
                        /* Swallows the tap rather than passing it down — and
                           makes the swallowing say something. Whoever is
                           prodding the video is told where the way in is, at
                           most once every ten seconds; the tap itself still
                           goes nowhere. */
                        .onTapGesture { glowFromTouch() }
                }

                /* Above the web view so it sees touches, below nothing —
                   it consumes none of them. The counterpart of
                   dispatchTouchEvent: without it the idle countdown expires
                   while an adult is mid-scrub. */
                if overlayLifted {
                    TouchReporter { restartIdleTimer() }
                        .allowsHitTesting(false)
                        .ignoresSafeArea()
                }

                bottomBlocker(geo)
                if overlayLifted { backButton }
                revealCorner(geo)
            }
        }
        .onAppear {
            /* A 16:9 video in portrait is a letterboxed strip. Android says
               sensorLandscape in one line of manifest; iOS has no per-screen
               setting, so the player asks and AppDelegate answers. */
            OrientationLock.lockToLandscape()
        }
        .onDisappear { OrientationLock.unlock() }
        /* GONE THE WHOLE TIME THE PLAYER IS UP, lifted overlay or not — the same
           rule Android applies to its status and navigation bars.
         *
         * They are the child's other way out: a clock, the notification shade,
         * the home indicator. Covering YouTube's controls means little with the
         * system's own sitting on the same screen.
         *
         * They used to come back when the overlay lifted, on the reasoning that
         * lifting hands the player to an adult. That reasoning holds and is
         * still not a reason to SHOW them: iOS brings the home indicator back on
         * a touch by itself, which is the way an adult gets at it, and popping
         * the bars up unasked just re-laid the video out under someone reaching
         * for the scrubber. */
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onAppear { recordWatch() }
        .onChange(of: index) { _ in
            glowedThisVideo = false
            failures = 0
            recordWatch()
        }
        .onChange(of: scenePhase) { phase in
            /* Backgrounded mid-video: put the overlay back rather than
               returning to a lifted one nobody is watching. */
            if phase != .active { lower() }
        }
    }

    // MARK: - The way out

    /* Shown only while the overlay is lifted, exactly as on Android.
     *
     * Android has always had a way out — the system back button finishes the
     * activity — but nothing on screen said so, and iOS has no system back at
     * all, which left the player with no exit whatsoever. Both platforms now
     * show the same control at the same moment: it is an adult's, and it
     * arrives with the other adult controls rather than sitting over every
     * video a child watches.
     *
     * Top-left, clear of the reveal corner at top-right. It carries its own
     * dark disc because the background is whatever frame the video is showing,
     * and a white chevron on a white scene is not a control. */
    private var backButton: some View {
        VStack {
            HStack {
                Button(action: onClose) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(Color.black.opacity(0.6)))
                }
                .accessibilityLabel("Back")
                Spacer()
            }
            Spacer()
        }
        .padding(8)
    }

    // MARK: - The blocked strip

    /* A strip along the bottom stays blocked even while the overlay is lifted,
       so a scrub that slides off the seek bar lands on nothing.
     *
     * While the overlay is lifted it tints faintly, so an adult who has just
     * unlocked the controls can see where the live area ends rather than
     * finding the bottom of the screen mysteriously dead. */
    private func bottomBlocker(_ geo: GeometryProxy) -> some View {
        VStack {
            Spacer()
            Rectangle()
                .fill(Color.white.opacity(overlayLifted ? 0.12 : 0.001))
                .frame(height: blockPoints)
                .contentShape(Rectangle())
                .onTapGesture {}
        }
        .ignoresSafeArea()
    }

    // MARK: - The reveal corner

    private func revealCorner(_ geo: GeometryProxy) -> some View {
        let side = min(geo.size.width, geo.size.height) * 0.22
        return VStack {
            HStack {
                Spacer()
                ZStack {
                    /* Invisible until it glows. What keeps a child out is that
                       the corner is unmarked and somewhere nothing else is —
                       not that the hold is long. */
                    RoundedRectangle(cornerRadius: side / 3)
                        .fill(Color.green.opacity(glowing ? 0.48 : 0.0))
                        .animation(.easeInOut(duration: 0.35), value: glowing)

                    if holdProgress > 0 {
                        Circle()
                            .trim(from: 0, to: holdProgress)
                            .stroke(Color.white.opacity(0.8), lineWidth: 3)
                            .rotationEffect(.degrees(-90))
                            .frame(width: side * 0.45, height: side * 0.45)
                    }
                }
                .frame(width: side, height: side)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { _ in beginHold() }
                        .onEnded { _ in cancelHold() }
                )
            }
            Spacer()
        }
        .ignoresSafeArea()
    }

    private func beginHold() {
        guard holdTask == nil, !overlayLifted else { return }
        let seconds = HoldTime.interval(forSeconds: SettingsStore.holdSeconds())
        holdTask = Task { @MainActor in
            let steps = 30
            for step in 1...steps {
                try? await Task.sleep(nanoseconds: UInt64(seconds / Double(steps) * 1_000_000_000))
                if Task.isCancelled { return }
                holdProgress = Double(step) / Double(steps)
            }
            lift(for: seconds)
        }
    }

    private func cancelHold() {
        holdTask?.cancel()
        holdTask = nil
        holdProgress = 0
    }

    private func lift(for seconds: TimeInterval) {
        cancelHold()
        overlayLifted = true
        restartIdleTimer()
    }

    /* The countdown that puts the overlay back, ARMED ONLY WHILE THE VIDEO IS
       ACTUALLY RUNNING.
     *
     * A paused player does not count down at all, deliberately. Pausing is what
     * an adult does to read something on screen, to look at where the scrubber
     * is, or to hand the phone to someone — none of which produce touches, and
     * all of which otherwise end with the overlay dropping back mid-sentence.
     * So the timer follows playback: it runs while the video does.
     *
     * It is also an IDLE timeout, restarted by every touch, and not the hold
     * duration. Those are different numbers for different jobs — the hold is
     * how long an adult must press to get in, this is how long the player stays
     * open with nobody touching it — and using the hold for both meant a
     * one-second lift that re-locked while a parent was still reaching for the
     * scrubber.
     *
     * The trade is that a player left paused and revealed stays that way. What
     * bounds it is leaving the screen: backgrounding puts the overlay back, so
     * locking the phone or switching apps both end the reveal. What is given up
     * is only the case where the phone is set down, unlocked, on a paused
     * video. Ported from restartIdleTimer in PlayerActivity. */
    private func restartIdleTimer() {
        liftTask?.cancel()
        guard overlayLifted, !paused else { return }
        liftTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(Self.idleSeconds * 1_000_000_000))
            if Task.isCancelled { return }
            lower()
        }
    }

    private func lower() {
        liftTask?.cancel()
        liftTask = nil
        guard overlayLifted else { return }
        overlayLifted = false
        /* The overlay coming back is the other moment the corner glows — an
           adult who was just using the controls is told where it went. */
        glow()
    }

    /* A touch on the locked overlay, rationed. */
    private func glowFromTouch() {
        if let last = lastTouchGlowAt,
           Date().timeIntervalSince(last) < Self.glowMinGap { return }
        lastTouchGlowAt = Date()
        glow()
    }

    private func glow() {
        glowing = true
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            glowing = false
        }
    }

    // MARK: - Playback

    private func applyState(_ state: Int) {
        switch state {
        case PlayerWebView.PlayerState.playing:
            paused = false
            /* Playback resuming arms the countdown that pausing disarmed. */
            restartIdleTimer()
            /* The first glow of a video waits for this rather than firing when
               the screen opened, against a black rectangle. */
            if !glowedThisVideo {
                glowedThisVideo = true
                glow()
                measureUntilItWorks()
            }
        case PlayerWebView.PlayerState.paused:
            paused = true
            /* And pausing disarms it, so the overlay does not drop back over an
               adult who paused precisely in order to look at something. */
            restartIdleTimer()
        default:
            break
        }
    }

    /* Until it works, and only while a video is actually playing — a capture
       taken against the loading rectangle has no seek bar in it.

       Once per install UNTIL ONE SUCCEEDS, rather than once per install full
       stop: a device that has an answer never captures again, and one that has
       never managed it tries on every restart. The launch budget that used to
       stop that is gone — see BlockHeightStore for what it cost. */
    private func measureUntilItWorks() {
        guard BlockHeightStore.shouldMeasure() else { return }
        measurement.measure(scale: UIScreen.main.scale) { points in
            /* A failure stores NOTHING. `Chrome.blockHeight` returns nil for
               "could not tell" precisely so a blank frame cannot be written
               down as an answer. */
            let ok = points.map {
                PlayerChrome.isPlausible($0, screenHeight: UIScreen.main.bounds.height)
            }
            MeasurementDebug.note {
                $0.plausible = ok
                if let points, ok == false {
                    $0.outcome = "measured \(Int(points))pt but refused as implausible "
                        + "(must be >0 and <= a quarter of \(Int(UIScreen.main.bounds.height))pt)"
                }
            }
            guard let points, ok == true else {
                MeasurementDebug.persist()
                return
            }
            BlockHeightStore.put(points)
            MeasurementDebug.note {
                $0.storedPoints = Double(points)
                $0.outcome = "measured \(Int(points))pt and stored"
            }
            MeasurementDebug.persist()
            blockPoints = points
        }
    }

    private func recordWatch() {
        guard let current else { return }
        WatchStore.record(videoId: current.id, now: Int64(Date().timeIntervalSince1970 * 1000))
    }

    private func playNext() {
        guard let next = Playlist.next(
            count: videos.count,
            current: index,
            mode: SettingsStore.nextMode(),
            roll: { Int.random(in: 0..<$0) }
        ) else {
            /* IN_ORDER walked off the end. A session has an edge. */
            onClose()
            return
        }
        index = next
    }

    /* The IFrame API reports an unplayable video — removed, made private, or
       embedding disabled by its owner. Nothing to show and nothing the child
       can do, so move on rather than sit on a black rectangle. One video an
       uploader made private should not end an afternoon. */
    private func handleError() {
        failures += 1
        if failures > Self.maxConsecutiveFailures { onClose() } else { playNext() }
    }
}
