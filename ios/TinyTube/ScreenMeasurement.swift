import Foundation
import CoreVideo
import CoreMedia
import ReplayKit
import TinyTubeCore

/* Feeding `Chrome` on iOS: getting the composited pixels of a playing video.

   `Chrome` finds YouTube's seek bar and is pure, so it is tested on Linux. This
   does the capturing, and the capturing is the part iOS makes hard.

   WHY ReplayKit AND NOTHING ELSE. Every cheaper API returns a software repaint
   rather than the screen: `WKWebView.takeSnapshot(with:)` is software-painted
   (WebKit's own words: "video/WebGL may-or-may-not work"), `CALayer.render(in:)`
   walks only the layer tree this process owns, and
   `UIView.drawHierarchy(in:afterScreenUpdates:)` returns black over video on
   devices while WORKING IN THE SIMULATOR — the one that will agree with you and
   be wrong. ReplayKit reads what is actually on screen, which is what Android's
   `PixelCopy` reads and why `PixelCopy` succeeded there where
   `WebView.draw(Canvas)` failed.

   ⚠️ THE COST IS A CONSENT ALERT, and it is the reason everything below is
   shaped the way it is. ReplayKit shows the user "TinyTube would like to record
   your screen" once per app process, and again after eight minutes in the
   background. A child can be the one looking at that alert. So:

     - It runs AT MOST ONCE PER INSTALL. The answer goes to BlockHeightStore
       keyed by display, and `shouldMeasure` is false forever after. One alert,
       on one video, ever — not one per session.
     - A device where it never works stops asking after three launches, rather
       than prompting a child on every launch for the life of the install. See
       BlockHeightStore.
     - A FAILURE STORES NOTHING. `Chrome.blockHeight` returns nil for "could not
       tell" precisely so a blank frame cannot be persisted as an answer, which
       is the bug that made the whole feature silently do nothing on Android.

   ⚠️ AND ONE HONEST DIFFERENCE FROM ANDROID, which the rule in CLAUDE.md says
   to write down rather than gloss. Android passes PixelCopy a SOURCE RECTANGLE,
   so only the bottom strip is ever copied and the part of the screen with the
   video in it is never captured at all — true by construction. ReplayKit has no
   such parameter: the system hands over whole frames. What this does instead is
   read only the strip's rows out of the buffer, so nothing above it is ever
   copied into memory this code owns, and the sample buffer is released
   untouched on the next line. That is weaker than Android's guarantee — the
   frame does exist, briefly, in a buffer the system owns — and it is the
   closest iOS allows. Nothing is written to storage, handed on, or sent
   anywhere, and capture stops the moment a measurement succeeds. */
final class ScreenMeasurement {

    /* The same bottom fraction Android reads, so both platforms hand `Chrome`
       the same shape of input. */
    static let stripFraction = 0.4

    private let recorder = RPScreenRecorder.shared()
    private var running = false
    private var delivered = false

    /* Starts a capture and calls back exactly once, with the measured height in
       POINTS, or nil if this device could not be measured.
     *
     * Points rather than pixels because the caller lays out in points: the
     * strip comes back in the buffer's own pixels, so the answer is divided by
     * the scale on the way out. `Chrome`'s figures are all ratios of the bar's
     * own drawn thickness, so it does not care which it was given. */
    func measure(scale: CGFloat, completion: @escaping (CGFloat?) -> Void) {
        guard !running else { return }

        /* ReplayKit shares its plumbing with AirPlay and the system screen
           recorder, so it simply refuses while either is running. That is a
           "try again another day", not a "this device cannot do it". */
        guard recorder.isAvailable else { completion(nil); return }

        running = true
        var finished = false
        let finish: (CGFloat?) -> Void = { [weak self] result in
            guard !finished else { return }
            finished = true
            self?.stop()
            DispatchQueue.main.async { completion(result) }
        }

        recorder.startCapture { [weak self] sample, bufferType, _ in
            guard let self, bufferType == .video, !self.delivered else { return }
            guard let points = Self.measureStrip(in: sample, scale: scale) else { return }
            self.delivered = true
            finish(points)
        } completionHandler: { error in
            /* Denied consent, or unavailable. Either way there is no frame
               coming, so stop waiting for one. */
            if error != nil { finish(nil) }
        }

        /* A capture that is running but never produces a frame `Chrome` can
           read would otherwise hold the recorder — and the recording indicator
           — open indefinitely. */
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { finish(nil) }
    }

    func stop() {
        guard running else { return }
        running = false
        recorder.stopCapture(handler: nil)
    }

    /* The strip, out of one frame, as ARGB — and nothing above the strip.
     *
     * `Chrome` takes ARGB packed into UInt32 (unsigned on purpose: Kotlin's
     * signed Int sign-extends on `shr` over an alpha bit, and an unsigned type
     * means the question cannot arise). ReplayKit delivers BGRA, so the bytes
     * are repacked rather than reinterpreted. */
    static func measureStrip(in sample: CMSampleBuffer, scale: CGFloat) -> CGFloat? {
        guard let buffer = CMSampleBufferGetImageBuffer(sample) else { return nil }
        guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA
        else { return nil }

        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }

        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        guard width > 0, height > 0, let base = CVPixelBufferGetBaseAddress(buffer)
        else { return nil }

        let stripHeight = Int(Double(height) * stripFraction)
        guard stripHeight > 0 else { return nil }
        let firstRow = height - stripHeight

        let bytes = base.assumingMemoryBound(to: UInt8.self)
        var pixels = [UInt32](repeating: 0, count: width * stripHeight)
        for y in 0..<stripHeight {
            let row = bytes + (firstRow + y) * stride
            for x in 0..<width {
                let p = row + x * 4
                /* BGRA in memory: 0=B, 1=G, 2=R. Alpha is dropped — Chrome only
                   ever reads the three colour channels. */
                pixels[y * width + x] =
                    (UInt32(p[2]) << 16) | (UInt32(p[1]) << 8) | UInt32(p[0])
            }
        }

        guard let blockPx = Chrome.blockHeight(pixels, width: width, height: stripHeight)
        else { return nil }
        return CGFloat(blockPx) / max(scale, 1)
    }
}
