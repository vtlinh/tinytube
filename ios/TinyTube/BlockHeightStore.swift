import Foundation
import UIKit

/* Remembers what the player measured, so it is measured ONCE and never again.

   The counterpart of BlockHeightStore.kt, and it carries the same two keys for
   the same two reasons — plus a third that only iOS needs.

   Keyed by DISPLAY, because a measurement is about a particular geometry. An
   iPad in a different orientation, Display Zoom being changed, or the same
   install restored onto another device all produce a different key, and a
   mismatch simply measures again.

   Keyed by VERSION, which is the more important half. On Android a build that
   could persist a FAILED capture wrote the fallback here as though it had been
   measured; preferences survive an app update, so every later build read it
   back, concluded the work was done, and never looked again. Fixing the bug
   changed nothing on any device that had already run the broken one. So: bump
   VERSION whenever the measurement changes, and old entries are ignored rather
   than trusted. It costs one re-measure per device.

   And keyed by an ATTEMPT COUNT, which Android does not have and iOS needs.
   Android's capture is silent, so it can retry forever at no cost to anyone.
   iOS's capture is ReplayKit, and ReplayKit shows the user a consent alert —
   once per app process, and again after eight minutes in the background. A
   device where the capture never yields a usable frame would therefore ask a
   CHILD to approve screen recording on every single launch, forever. After
   `maxSessions` fruitless launches this gives up and lets the fallback stand.
   Bumping VERSION resets that too, so a build that fixes the measurement gets
   its chance on devices that had given up. */
enum BlockHeightStore {

    private static let keyPoints = "block_points"
    private static let keyDisplay = "block_display"
    private static let keyVersion = "block_version"
    private static let keySessions = "block_sessions"

    /* iOS's own ladder, starting at 1 — no earlier iOS build ever persisted
       anything, so there is nothing here to distrust yet. The NUMBER does not
       have to agree with Android's; the RULE does. Bump it whenever what the
       measurement would return changes. */
    static let version = 1

    /* How many launches may fail before this stops asking. Three is enough to
       ride out a device that was mirroring to a TV or had the app backgrounded
       at the wrong moment, and few enough that a device where this simply does
       not work stops prompting almost immediately. */
    static let maxSessions = 3

    /* What the display is, as a string to compare against later. Points and
       scale rather than raw pixels: the answer is applied in points, so two
       geometries that differ only in a value nothing uses are the same display
       for this purpose. */
    static func displayKey(_ screen: UIScreen = .main) -> String {
        let b = screen.bounds
        return "\(Int(b.width))x\(Int(b.height))@\(screen.scale)"
    }

    /* The remembered height, or nil if there is nothing here worth trusting for
       this display and this version of the measurement. */
    static func get(_ defaults: UserDefaults = .standard, screen: UIScreen = .main) -> CGFloat? {
        guard defaults.integer(forKey: keyVersion) == version else { return nil }
        guard defaults.string(forKey: keyDisplay) == displayKey(screen) else { return nil }
        let points = defaults.double(forKey: keyPoints)
        return points > 0 ? CGFloat(points) : nil
    }

    /* Only ever called with a real answer. `Chrome.blockHeight` returns nil for
       "could not tell" precisely so this is never handed a failure dressed up
       as a number — see the comment above about what happens when it is. */
    static func put(_ points: CGFloat,
                    defaults: UserDefaults = .standard,
                    screen: UIScreen = .main) {
        defaults.set(Double(points), forKey: keyPoints)
        defaults.set(displayKey(screen), forKey: keyDisplay)
        defaults.set(version, forKey: keyVersion)
    }

    /* Whether it is still worth starting a capture at all — i.e. whether this
       display has an answer already, and whether the attempts are used up. */
    static func shouldMeasure(_ defaults: UserDefaults = .standard,
                              screen: UIScreen = .main) -> Bool {
        if get(defaults, screen: screen) != nil { return false }
        return sessionsSpent(defaults, screen: screen) < maxSessions
    }

    /* Counted per launch rather than per frame: within one process the consent
       alert has already been shown, so retrying costs the user nothing and the
       player retries freely. It is the NEXT launch that would prompt again. */
    static func noteSessionSpent(_ defaults: UserDefaults = .standard,
                                 screen: UIScreen = .main) {
        defaults.set(sessionsSpent(defaults, screen: screen) + 1, forKey: keySessions)
        defaults.set(displayKey(screen), forKey: keyDisplay)
        defaults.set(version, forKey: keyVersion)
    }

    private static func sessionsSpent(_ defaults: UserDefaults,
                                      screen: UIScreen) -> Int {
        guard defaults.integer(forKey: keyVersion) == version,
              defaults.string(forKey: keyDisplay) == displayKey(screen)
        else { return 0 }
        return defaults.integer(forKey: keySessions)
    }

    /* Throw the answer away and measure again. */
    static func clear(_ defaults: UserDefaults = .standard) {
        for k in [keyPoints, keyDisplay, keyVersion, keySessions] {
            defaults.removeObject(forKey: k)
        }
    }
}
