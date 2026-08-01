import Foundation

/* How tall the player's bottom blocker is on iOS — a constant, and the reason
   it is a constant rather than a measurement.

   Android measures this. `PixelCopy` there reads the COMPOSITED window,
   hardware video surface and all, so the app can look at YouTube's own seek bar
   and work out how much room to leave under it. `Chrome.swift` is that logic,
   ported line for line and tested, and on iOS it has nothing to read:

     - `WKWebView.takeSnapshot(with:)` is software-painted — the same class of
       thing as Android's `WebView.draw(Canvas)`, which is exactly what failed
       there before `PixelCopy` replaced it.
     - `CALayer.render(in:)` walks the layer tree this process owns; the video
       is composited out of process and is not in it.
     - `UIView.drawHierarchy(in:afterScreenUpdates:)` comes back black over
       video ON DEVICES while working in the SIMULATOR. Do not "confirm" this
       one on a simulator — it will agree with you and be wrong.
     - ReplayKit does capture the composited screen, and is refused rather than
       unavailable: it is a recording API that hands over a picture, and the
       rule in CLAUDE.md is that this capture stays a measurement.

   So: a fixed inset, at the same 16 points Android's `player_bottom_block`
   falls back to before it has measured. Erring small is deliberate. Too tall
   and the blocker covers the seek bar itself, which is the one control the
   reveal corner exists to reach — a blocker that is slightly too short leaves a
   sliver of YouTube reachable, a blocker that is too tall makes the player
   unusable for the adult who just unlocked it.

   In POINTS, and never converted. UIKit lays out in points and the iOS blocker
   is not derived from any pixel measurement, so there is nothing here that
   wants a scale factor. */
enum PlayerChrome {

    /* The blocked strip along the bottom of the player, in points.
     *
     * Matches `player_bottom_block` in android/app/src/main/res/values/dimens.xml.
     * If that number ever changes, this one changes with it — it is the same
     * decision about the same player, and the platforms agreeing is the point. */
    static let bottomBlockPoints: CGFloat = 16
}
