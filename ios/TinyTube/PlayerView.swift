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

                /* While an ad is playing the overlay stops intercepting, so the
                   ad stays interactive. The reveal corner keeps working through
                   it: it is the way out of a stuck player, and an ad is exactly
                   when a parent might want one. */
                if !overlayLifted && !showingAd {
                    Color.clear
                        .contentShape(Rectangle())
                        .ignoresSafeArea()
                        /* Swallows the tap rather than passing it down. */
                        .onTapGesture {}
                }

                /* YouTube draws its own chrome over a paused frame, so cover it
                   — but not during an ad, where the frame is the ad. */
                if paused && !showingAd && !overlayLifted {
                    Color.black.opacity(0.9).ignoresSafeArea()
                }

                bottomBlocker(geo)
                revealCorner(geo)
            }
        }
        .statusBarHidden()
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
        liftTask?.cancel()
        liftTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
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
            /* The first glow of a video waits for this rather than firing when
               the screen opened, against a black rectangle. */
            if !glowedThisVideo {
                glowedThisVideo = true
                glow()
                measureOnce()
            }
        case PlayerWebView.PlayerState.paused:
            paused = true
        default:
            break
        }
    }

    /* Once per install, and only while a video is actually playing — a capture
       taken against the loading rectangle has no seek bar in it. See
       ScreenMeasurement for why "once" matters so much here. */
    private func measureOnce() {
        guard BlockHeightStore.shouldMeasure() else { return }
        BlockHeightStore.noteSessionSpent()
        measurement.measure(scale: UIScreen.main.scale) { points in
            /* A failure stores NOTHING. `Chrome.blockHeight` returns nil for
               "could not tell" precisely so a blank frame cannot be written
               down as an answer. */
            guard let points,
                  PlayerChrome.isPlausible(points, screenHeight: UIScreen.main.bounds.height)
            else { return }
            BlockHeightStore.put(points)
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
