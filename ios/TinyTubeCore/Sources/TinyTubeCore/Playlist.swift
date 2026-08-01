import Foundation

/* What plays after the video that just ended.

   The list is whatever the child was looking at when they tapped. That is the
   whole of the scoping rule and it needs no special case: on the Videos tab it
   is every approved channel's uploads, and on a channel it is that channel's,
   because narrowing the grid is what the Channels tab does. A video started
   from inside one channel therefore cannot lead out of it, and one started from
   the full grid is not confined to where it began.

   Ported from Playlist.kt. Getting this wrong is not a crash — it is a child
   handed a video from somewhere they were not. */
public enum Playlist {

    /* How the next one is chosen. The parent picks; see the settings screen. */
    public enum Mode: String, CaseIterable, Sendable {
        case inOrder = "IN_ORDER"
        case random = "RANDOM"
    }

    /* Stored by NAME rather than by position, so the cases can be reordered
       without silently changing what every device already chose. The raw values
       match the Kotlin enum's names exactly — the two platforms write the same
       strings, which matters if a settings export ever crosses between them. */
    public static func mode(of name: String?) -> Mode {
        guard let name else { return .inOrder }
        return Mode(rawValue: name) ?? .inOrder
    }

    /* The index to play next, or nil for "stop and go back to the grid".
     *
     * inOrder walks down the list and STOPS at the end rather than wrapping. A
     * grid that plays forever with no edge is a different product from one that
     * plays what a parent approved and then hands the screen back, and the end
     * of the list is the only natural place this ever stops on its own.
     *
     * random never repeats the video that just played, which is the one outcome
     * that reads as broken rather than as random. With a list of one that means
     * stopping, and that is right: the single video is over.
     *
     * `roll` returns a value in 0..<its argument. Passed in rather than called
     * here so the choice is testable — a random next video is exactly the kind
     * of thing that is only wrong once in fifty runs. */
    public static func next(count: Int, current: Int, mode: Mode, roll: (Int) -> Int) -> Int? {
        guard count > 0, current >= 0, current < count else { return nil }
        switch mode {
        case .inOrder:
            let next = current + 1
            return next < count ? next : nil
        case .random:
            guard count >= 2 else { return nil }
            /* Roll among the OTHER videos and skip past the current one, rather
               than rolling over all of them and retrying. Retrying has no
               bound; this has none of the distribution skew that makes people
               reach for retrying either. */
            let n = min(max(roll(count - 1), 0), count - 2)
            return n >= current ? n + 1 : n
        }
    }
}
