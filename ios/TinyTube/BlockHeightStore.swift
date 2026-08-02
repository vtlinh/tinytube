import Foundation
import UIKit

/* Remembers what the player measured, so a device that has a usable answer does
   not go asking for another one.

   The counterpart of BlockHeightStore.kt, and it keeps that file's DISPLAY key
   for the same reason: a measurement is about a particular geometry. An iPad in
   a different orientation, Display Zoom being changed, or the same install
   restored onto another device all produce a different key, and a mismatch
   simply measures again.

   IT NO LONGER VERSIONS AND IT NO LONGER GIVES UP. Both were deliberate and
   both were load-bearing, so what removing them costs is written down here
   rather than discovered later:

   - The VERSION key existed because a preference file survives an app update,
     so a wrong answer written by one build is read back by every build after
     it. Bumping it was the only thing that let a corrected measurement reach a
     device that had already stored a bad number. Without it, a stored answer
     outlives any change to how the measurement is made, and a build that
     changes what the measurement returns has no way to invalidate what is
     already on a device short of `clear`.

   - The ATTEMPT COUNT existed because ReplayKit shows a consent alert — once
     per app process, and again after eight minutes backgrounded — and A CHILD
     CAN BE THE ONE LOOKING AT IT. Giving up after three fruitless launches was
     what stopped a device where capture never works from prompting forever.
     Without it, that is exactly what happens: a prompt every launch until some
     capture finally succeeds.

   That second cost is real and current, not hypothetical. It is accepted for
   now because the alternative was worse in the way that matters: the
   measurement is failing on a real device, the blocker is sitting on its
   16-point fallback, and YouTube's seek bar, settings gear, "More videos" and
   logo are all reachable by the child this app exists to fence in. A consent
   alert is recoverable and visible; an unblocked player is the failure the
   whole app is built to prevent.

   Put the attempt count back once the measurement is known to work on real
   hardware, and take the same care with VERSION at the same time. */
enum BlockHeightStore {

    private static let keyPoints = "block_points"
    private static let keyDisplay = "block_display"

    /* Written by builds that versioned and counted attempts. Nothing reads them
       any more; they are named only so `clear` can sweep them off devices that
       still carry them. */
    private static let legacyKeys = ["block_version", "block_sessions"]

    /* What the display is, as a string to compare against later. Points and
       scale rather than raw pixels: the answer is applied in points, so two
       geometries that differ only in a value nothing uses are the same display
       for this purpose. */
    static func displayKey(_ screen: UIScreen = .main) -> String {
        let b = screen.bounds
        return "\(Int(b.width))x\(Int(b.height))@\(screen.scale)"
    }

    /* The remembered height, or nil if there is nothing here worth trusting for
       this display. */
    static func get(_ defaults: UserDefaults = .standard, screen: UIScreen = .main) -> CGFloat? {
        guard defaults.string(forKey: keyDisplay) == displayKey(screen) else { return nil }
        let points = defaults.double(forKey: keyPoints)
        return points > 0 ? CGFloat(points) : nil
    }

    /* Only ever called with a real answer. `Chrome.blockHeight` returns nil for
       "could not tell" precisely so this is never handed a failure dressed up
       as a number. */
    static func put(_ points: CGFloat,
                    defaults: UserDefaults = .standard,
                    screen: UIScreen = .main) {
        defaults.set(Double(points), forKey: keyPoints)
        defaults.set(displayKey(screen), forKey: keyDisplay)
    }

    /* Whether it is worth starting a capture at all — now purely "is there an
       answer for this display yet". No launch budget: a device that has never
       produced one tries again on every restart, for as long as it takes. */
    static func shouldMeasure(_ defaults: UserDefaults = .standard,
                              screen: UIScreen = .main) -> Bool {
        get(defaults, screen: screen) == nil
    }

    /* Throw the answer away and measure again. */
    static func clear(_ defaults: UserDefaults = .standard) {
        for k in [keyPoints, keyDisplay] + legacyKeys {
            defaults.removeObject(forKey: k)
        }
    }
}
