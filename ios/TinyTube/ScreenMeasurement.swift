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
        MeasurementDebug.beginAttempt()
        Self.resetStripCapture()
        MeasurementDebug.note { $0.recorderAvailable = recorder.isAvailable }
        guard recorder.isAvailable else {
            MeasurementDebug.note { $0.outcome = "RPScreenRecorder.isAvailable was false — AirPlay or the system recorder is using the pipeline" }
            MeasurementDebug.persist()
            completion(nil)
            return
        }

        running = true
        var finished = false
        let finish: (CGFloat?) -> Void = { [weak self] result in
            guard !finished else { return }
            finished = true
            self?.stop()
            MeasurementDebug.note { $0.measuredPoints = result.map(Double.init) }
            MeasurementDebug.persist()
            DispatchQueue.main.async { completion(result) }
        }

        recorder.startCapture { [weak self] sample, bufferType, _ in
            guard let self, bufferType == .video, !self.delivered else { return }
            MeasurementDebug.note { $0.videoFrames += 1 }
            guard let points = Self.measureStrip(in: sample, scale: scale) else { return }
            self.delivered = true
            MeasurementDebug.note { $0.outcome = "measured" }
            finish(points)
        } completionHandler: { error in
            /* Denied consent, or unavailable. Either way there is no frame
               coming, so stop waiting for one. */
            if let error {
                MeasurementDebug.note {
                    $0.captureError = error.localizedDescription
                    $0.outcome = "startCapture failed — consent refused, or ReplayKit unavailable"
                }
                finish(nil)
            }
        }

        /* A capture that is running but never produces a frame `Chrome` can
           read would otherwise hold the recorder — and the recording indicator
           — open indefinitely. */
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) {
            MeasurementDebug.note {
                guard $0.outcome.hasPrefix("started") else { return }
                $0.outcome = $0.videoFrames == 0
                    ? "timed out after 8s with NO video frames — capture never started delivering"
                    : "timed out after 8s: \($0.videoFrames) frames arrived but Chrome found no seek bar in any of them"
            }
            finish(nil)
        }
    }

    func stop() {
        guard running else { return }
        running = false
        recorder.stopCapture(handler: nil)
    }

    /* HOW THE BUFFER'S ROWS RELATE TO WHAT THE USER IS LOOKING AT.
     *
     * This is the bug that made the whole measurement do nothing on iOS. The
     * player forces LANDSCAPE, and ReplayKit does not rotate its buffers to
     * match: it hands over frames in the device's own orientation and attaches
     * `RPVideoSampleOrientationKey` to say how they should be read. Ignoring it
     * and taking "the last rows of the buffer" as "the bottom of the screen" is
     * correct only in portrait. In landscape those rows are a band down one
     * SIDE of the screen — so `Chrome` went looking for a full-width horizontal
     * track in a strip that could never contain one, found nothing on every
     * frame, and the blocker sat on its 16-point fallback for good.
     *
     * The cases are Apple's CGImagePropertyOrientation, whose names describe
     * where the stored image's first row and column END UP on screen. */
    enum FrameOrientation: UInt32 {
        case up = 1       // 0th row at top,    0th column on left
        case down = 3     // 0th row at bottom, 0th column on right
        case right = 6    // 0th row on right,  0th column on top
        case left = 8     // 0th row on left,   0th column on bottom

        /* Whether reading it swaps width and height. */
        var swapsAxes: Bool { self == .right || self == .left }
    }

    /* Display coordinates to buffer coordinates.
     *
     * Pure integer arithmetic and deliberately separable, because it is the one
     * part of this file a test can reach: TinyTubeTests pins all four cases on
     * a simulator. Nothing else here can be tested without a device and a
     * consent alert.
     *
     * `display` is what the user sees — origin top-left, +y downwards, which is
     * the space `Chrome` works in. */
    static func bufferCoord(
        displayX dx: Int, displayY dy: Int,
        bufferWidth bw: Int, bufferHeight bh: Int,
        orientation: FrameOrientation
    ) -> (x: Int, y: Int) {
        switch orientation {
        case .up:    return (dx, dy)
        case .down:  return (bw - 1 - dx, bh - 1 - dy)
        /* 0th row on the right means buffer row `by` lands at display column
           bh-1-by; 0th column on top means buffer column `bx` lands at display
           row bx. Inverted here, because we walk display space. */
        case .right: return (dy, bh - 1 - dx)
        case .left:  return (bw - 1 - dy, dx)
        }
    }

    /* Display size, given the buffer's. */
    static func displaySize(bufferWidth bw: Int, bufferHeight bh: Int,
                            orientation: FrameOrientation) -> (width: Int, height: Int) {
        orientation.swapsAxes ? (bh, bw) : (bw, bh)
    }

    /* What ReplayKit says about this frame; `.up` if it says nothing, which is
       what portrait capture looks like. */
    static func orientation(of sample: CMSampleBuffer) -> FrameOrientation {
        guard let raw = CMGetAttachment(
                sample,
                key: RPVideoSampleOrientationKey as CFString,
                attachmentModeOut: nil) as? NSNumber,
              let parsed = FrameOrientation(rawValue: raw.uint32Value)
        else { return .up }
        return parsed
    }

    /* The strip, out of one frame, as ARGB — and nothing above the strip.
     *
     * `Chrome` takes ARGB packed into UInt32 (unsigned on purpose: Kotlin's
     * signed Int sign-extends on `shr` over an alpha bit, and an unsigned type
     * means the question cannot arise). ReplayKit delivers BGRA, so the bytes
     * are repacked rather than reinterpreted.
     *
     * The rows read are the bottom `stripFraction` of the DISPLAY, worked out
     * through the orientation above — not the bottom of the buffer. Nothing
     * above that strip is copied into memory this code owns, in any
     * orientation, which is the promise in the header. */
    static func measureStrip(in sample: CMSampleBuffer, scale: CGFloat) -> CGFloat? {
        guard let buffer = CMSampleBufferGetImageBuffer(sample) else {
            MeasurementDebug.note { $0.framesRejectedNoImageBuffer += 1 }
            return nil
        }
        let format = CVPixelBufferGetPixelFormatType(buffer)
        guard format == kCVPixelFormatType_32BGRA else {
            MeasurementDebug.note {
                $0.framesRejectedPixelFormat += 1
                $0.pixelFormat = Self.fourCC(format) + " (wanted BGRA)"
            }
            return nil
        }
        MeasurementDebug.note { $0.pixelFormat = Self.fourCC(format) }

        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }

        let bw = CVPixelBufferGetWidth(buffer)
        let bh = CVPixelBufferGetHeight(buffer)
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        guard bw > 0, bh > 0, let base = CVPixelBufferGetBaseAddress(buffer)
        else { return nil }

        let facing = orientation(of: sample)
        let (dw, dh) = displaySize(bufferWidth: bw, bufferHeight: bh, orientation: facing)

        let stripHeight = Int(Double(dh) * stripFraction)
        guard stripHeight > 0 else { return nil }
        let firstRow = dh - stripHeight

        let bytes = base.assumingMemoryBound(to: UInt8.self)
        var pixels = [UInt32](repeating: 0, count: dw * stripHeight)
        for y in 0..<stripHeight {
            for x in 0..<dw {
                let (bx, by) = bufferCoord(
                    displayX: x, displayY: firstRow + y,
                    bufferWidth: bw, bufferHeight: bh,
                    orientation: facing
                )
                let p = bytes + by * stride + bx * 4
                /* BGRA in memory: 0=B, 1=G, 2=R. Alpha is dropped — Chrome only
                   ever reads the three colour channels. */
                pixels[y * dw + x] =
                    (UInt32(p[2]) << 16) | (UInt32(p[1]) << 8) | UInt32(p[0])
            }
        }

        /* TEMPORARY, and the whole reason this debugging build exists: keep the
           strip as Chrome received it, so a wrong orientation transform is
           visible as a picture rather than guessed at from a null. Only the
           first frame of an attempt, overwritten each time. See
           MeasurementDebug for the limits kept on it. */
        MeasurementDebug.note {
            $0.bufferWidth = bw
            $0.bufferHeight = bh
            $0.orientationRaw = facing.rawValue
            $0.orientationName = "\(facing)"
            $0.displayWidth = dw
            $0.displayHeight = dh
            $0.stripRows = stripHeight
        }
        if !firstStripSaved {
            firstStripSaved = true
            let path = MeasurementDebug.saveStrip(pixels, width: dw, height: stripHeight)
            MeasurementDebug.note { $0.framePath = path }
        }

        guard let blockPx = Chrome.blockHeight(pixels, width: dw, height: stripHeight) else {
            MeasurementDebug.note { $0.framesChromeFoundNothing += 1 }
            return nil
        }
        MeasurementDebug.note { $0.measuredPixels = Double(blockPx) }
        return CGFloat(blockPx) / max(scale, 1)
    }

    /* Set once per attempt so the saved strip is the first frame analysed
       rather than the last, and so a long capture does not rewrite the file
       hundreds of times. */
    private static var firstStripSaved = false

    static func resetStripCapture() { firstStripSaved = false }

    /* A CVPixelFormatType is four packed ASCII bytes; printed raw it is an
       unreadable integer, and which format arrived is exactly what matters when
       frames are being rejected. */
    static func fourCC(_ value: OSType) -> String {
        let bytes = [
            UInt8((value >> 24) & 0xFF), UInt8((value >> 16) & 0xFF),
            UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF),
        ]
        let text = String(bytes: bytes, encoding: .ascii) ?? ""
        return text.allSatisfy { $0.isLetter || $0.isNumber || $0 == " " }
            ? text.trimmingCharacters(in: .whitespaces)
            : "0x" + String(value, radix: 16)
    }
}
