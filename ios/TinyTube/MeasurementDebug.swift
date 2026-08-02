import Foundation
import UIKit

/* TEMPORARY. Delete this file, its call sites in ScreenMeasurement, and the
   debug section of SettingsView once the blocker measurement is known to work
   on real hardware.

   WHY IT EXISTS, AND WHY IT BREAKS A RULE ON PURPOSE.

   CLAUDE.md says the player's frame capture stays a measurement and never a
   picture — don't keep the bitmap, don't add a caller that wants the image
   rather than the number. It also says a build that saved the frame "existed
   for one round of debugging and was removed once the measurement worked; if it
   is ever needed again it is in the history, with the backup exclusions that
   made it safe." This is that round, on iOS this time.

   The measurement is failing on a real device: the blocker sits on its
   16-point fallback and YouTube's seek bar, settings gear, "More videos" and
   logo are all reachable by the child the app exists to fence in. And the
   failure is invisible — `measureStrip` returning nil for a frame merely SKIPS
   that frame and waits for the eight-second timeout, so "no frames arrived at
   all" and "frames arrived and Chrome found no seek bar in them" produce the
   identical outcome from outside. Those need different fixes. Nothing short of
   looking at what was captured tells them apart, and there is no Xcode console
   in reach — the IPA is sideloaded from a Windows machine.

   WHAT IT KEEPS, AND THE LIMITS KEPT ON IT.

   Only the STRIP, which is what `Chrome` is given — never the whole frame,
   even though ReplayKit hands one over. One file, overwritten each attempt,
   never appended to. Written inside the app's own Documents directory and
   EXCLUDED FROM BACKUP, so it does not travel to iCloud or to a new device.
   Nothing uploads it, and nothing puts it on a share sheet: it is read back by
   the About screen, which lives behind the parent gate.

   The strip is the bottom 40% of the player while a video is on screen. Treat
   it as a screenshot, because that is what it is. */
enum MeasurementDebug {

    // MARK: - the record

    /* Every field optional or defaulted, so a record written by an earlier
       build still decodes instead of throwing the whole thing away. */
    struct Record: Codable {
        var at: Date?
        var recorderAvailable: Bool?
        var captureError: String?

        var videoFrames: Int = 0
        var framesRejectedNoImageBuffer: Int = 0
        var framesRejectedPixelFormat: Int = 0
        var framesChromeFoundNothing: Int = 0

        var bufferWidth: Int = 0
        var bufferHeight: Int = 0
        var pixelFormat: String = ""
        var orientationRaw: UInt32 = 0
        var orientationName: String = ""
        var displayWidth: Int = 0
        var displayHeight: Int = 0
        var stripRows: Int = 0

        var stripAllOneColour: Bool?
        var stripDarkFraction: Double?
        var framePath: String?

        var measuredPixels: Double?
        var measuredPoints: Double?
        var plausible: Bool?
        var storedPoints: Double?

        var outcome: String = "never run"
    }

    // MARK: - storage

    private static let key = "measurement_debug_v1"
    private static let lock = NSLock()
    private static var live = Record()

    /* Mutating from the ReplayKit callback thread and read from the main one. */
    static func note(_ mutate: (inout Record) -> Void) {
        lock.lock(); defer { lock.unlock() }
        mutate(&live)
    }

    static func beginAttempt() {
        lock.lock(); defer { lock.unlock() }
        live = Record()
        live.at = Date()
        live.outcome = "started, no result yet"
    }

    static func persist(_ defaults: UserDefaults = .standard) {
        lock.lock()
        let snapshot = live
        lock.unlock()
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        defaults.set(data, forKey: key)
    }

    static func load(_ defaults: UserDefaults = .standard) -> Record? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(Record.self, from: data)
    }

    /* The whole diagnosis as plain lines, for the About screen to print.
       Built here rather than in the view so it costs the SwiftUI type checker
       nothing — see the note at the call site. */
    static func report(_ defaults: UserDefaults = .standard) -> [String] {
        let stored = BlockHeightStore.get(defaults)
        var out: [String] = []

        out.append(stored == nil
            ? "blocker in use   \(Int(PlayerChrome.fallbackPoints))pt  ** FALLBACK, NOT MEASURED **"
            : "blocker in use   \(Int(stored!))pt  (measured)")
        let b = UIScreen.main.bounds
        out.append("screen           \(Int(b.width))x\(Int(b.height))pt @\(UIScreen.main.scale)x")
        out.append("will retry       " + (BlockHeightStore.shouldMeasure()
            ? "yes, on the next video played" : "no, it has an answer"))

        guard let r = load(defaults) else {
            out.append("")
            out.append("No capture attempt recorded yet.")
            out.append("Play a video for ~10s, then come back here.")
            return out
        }

        out.append("")
        out.append("OUTCOME          \(r.outcome)")
        if let at = r.at {
            out.append("last attempt     \(at.formatted(date: .abbreviated, time: .standard))")
        }
        if let a = r.recorderAvailable { out.append("replaykit avail  \(a ? "yes" : "NO")") }
        if let e = r.captureError { out.append("capture error    \(e)") }

        out.append("")
        out.append("video frames     \(r.videoFrames)")
        if r.framesRejectedNoImageBuffer > 0 {
            out.append("  no img buffer  \(r.framesRejectedNoImageBuffer)")
        }
        if r.framesRejectedPixelFormat > 0 {
            out.append("  bad px format  \(r.framesRejectedPixelFormat)")
        }
        if r.framesChromeFoundNothing > 0 {
            out.append("  chrome found nothing in \(r.framesChromeFoundNothing)")
        }

        if r.bufferWidth > 0 {
            out.append("")
            out.append("buffer           \(r.bufferWidth)x\(r.bufferHeight)  \(r.pixelFormat)")
            out.append("orientation      \(r.orientationName) (raw \(r.orientationRaw))")
            out.append("read as display  \(r.displayWidth)x\(r.displayHeight)")
            out.append("strip rows       \(r.stripRows)")
        }
        if let u = r.stripAllOneColour {
            out.append("strip flat       " + (u ? "YES — nothing was captured" : "no"))
        }
        if let d = r.stripDarkFraction {
            out.append("strip darkness   " + String(format: "%.0f%% near-black", d * 100))
        }

        out.append("")
        if let px = r.measuredPixels { out.append("chrome answer    \(Int(px))px") }
        if let pt = r.measuredPoints { out.append("converted        \(Int(pt))pt") }
        if let ok = r.plausible { out.append("plausible        " + (ok ? "yes" : "NO — discarded")) }
        if let s = r.storedPoints { out.append("stored           \(Int(s))pt") }
        return out
    }

    static func clear(_ defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: key)
        lock.lock(); live = Record(); lock.unlock()
        if let url = stripURL() { try? FileManager.default.removeItem(at: url) }
    }

    // MARK: - the strip, as a picture

    static func stripURL() -> URL? {
        FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)
            .first?
            .appendingPathComponent("measurement-strip.png")
    }

    /* Writes the strip exactly as `Chrome` received it — after the orientation
       transform, so a wrong transform is visible as a picture rather than
       inferred from a number. Returns the path, and records how dark it was:
       an all-one-colour strip is the signature of a capture that yielded
       nothing, which is the first thing worth ruling out. */
    @discardableResult
    static func saveStrip(_ pixels: [UInt32], width: Int, height: Int) -> String? {
        guard width > 0, height > 0, pixels.count == width * height else { return nil }

        var first: UInt32? = nil
        var uniform = true
        var dark = 0
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        for i in 0..<(width * height) {
            let p = pixels[i]
            if first == nil { first = p } else if p != first { uniform = false }
            let r = UInt8((p >> 16) & 0xFF), g = UInt8((p >> 8) & 0xFF), b = UInt8(p & 0xFF)
            if Int(r) + Int(g) + Int(b) < 90 { dark += 1 }
            rgba[i * 4] = r
            rgba[i * 4 + 1] = g
            rgba[i * 4 + 2] = b
            rgba[i * 4 + 3] = 255
        }

        note {
            $0.stripAllOneColour = uniform
            $0.stripDarkFraction = Double(dark) / Double(width * height)
        }

        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let image = CGImage(
                width: width, height: height,
                bitsPerComponent: 8, bitsPerPixel: 32,
                bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                provider: provider, decode: nil,
                shouldInterpolate: false, intent: .defaultIntent
              ),
              let png = UIImage(cgImage: image).pngData(),
              var url = stripURL()
        else { return nil }

        do {
            try png.write(to: url, options: .atomic)
            /* Never to iCloud, and never onto the next device with a restore. */
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(&values)
        } catch {
            return nil
        }
        return url.path
    }
}
