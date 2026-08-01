import Foundation
import UIKit

/* How tall the player's bottom blocker is on iOS.

   MEASURED, like Android — not the fixed inset an earlier commit here settled
   on. That earlier answer said ReplayKit was refused because "it yields a
   picture", and that reasoning was wrong: Android's `PixelCopy` yields a bitmap
   too. The rule in CLAUDE.md is about RETENTION — recycle it, never store it,
   never send it — and a discarded ReplayKit frame satisfies it exactly as a
   discarded Android bitmap does. What is genuinely different on iOS is the
   consent alert, and that is a reason to capture RARELY rather than never. So
   it captures once per install. See `ScreenMeasurement` for the shape that
   forces, and README's Platform differences for the difference that remains.

   The other candidates really are unusable, and that half of the spike stands:
   `takeSnapshot` is software-painted, `CALayer.render(in:)` cannot see an
   out-of-process layer, and `drawHierarchy` returns black over video on devices
   while working in the simulator.

   In POINTS throughout. `ScreenMeasurement` divides the pixel answer by the
   screen's scale on the way out, so nothing downstream converts anything. */
enum PlayerChrome {

    /* What the blocker is until something better is known — on the first video
       of a fresh install, on a device that refused the capture, and on one that
       has used up its attempts.
     *
     * The same 16 points Android's `player_bottom_block` falls back to. Erring
     * small is deliberate: too tall and the blocker covers the seek bar itself,
     * which is the one control the reveal corner exists to reach. A blocker
     * slightly too short leaves a sliver of YouTube reachable; one too tall
     * makes the player unusable for the adult who just unlocked it. */
    static let fallbackPoints: CGFloat = 16

    /* A measured answer this far from plausible is not an answer. `Chrome`
       already refuses implausible geometry in ratio terms; this is the outer
       bound in points, and exists because a measurement that came back as most
       of the screen would block most of the player.
     *
     * Expressed as a fraction of the screen rather than a constant, so it holds
     * on a phone and on an iPad. */
    static func isPlausible(_ points: CGFloat, screenHeight: CGFloat) -> Bool {
        points > 0 && points <= screenHeight / 4
    }

    /* The height to use right now: what was measured on this display, or the
       fallback. Never blocks on a capture — the capture, if it runs at all,
       reports later and the blocker resizes then. */
    static func currentPoints(_ defaults: UserDefaults = .standard,
                              screen: UIScreen = .main) -> CGFloat {
        guard let stored = BlockHeightStore.get(defaults, screen: screen),
              isPlausible(stored, screenHeight: screen.bounds.height)
        else { return fallbackPoints }
        return stored
    }
}
