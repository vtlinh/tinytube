import Foundation

/* How long the player's corner must be held to lift the overlay.

   A second by default, and the parent can set anything from one to five.

   The range is the interesting part. A half-second press is short enough for a
   child to trigger by resting a thumb — so one second is the floor rather than
   the default-and-floor being the same number by accident. Five is the ceiling
   because a hold nobody will sit through is not a stronger lock, it is a
   control an adult gives up on; the thing standing between a child and
   YouTube's controls is that the corner is invisible and in a place nothing
   else is, not the duration.

   Ported from HoldTime.kt. A slider is exactly the kind of control that ships
   with an off-by-one at one end of its range. */
public enum HoldTime {

    public static let minSeconds = 1
    public static let maxSeconds = 5
    public static let defaultSeconds = 1

    public static let defaultInterval: TimeInterval = TimeInterval(defaultSeconds)

    /* Anything stored, out of range, or absent becomes something usable. A
       preference survives an app update and can be edited on a jailbroken
       device; a hold of zero would make the corner a tap. */
    public static func clamp(_ seconds: Int) -> Int {
        min(max(seconds, minSeconds), maxSeconds)
    }

    public static func interval(forSeconds seconds: Int) -> TimeInterval {
        TimeInterval(clamp(seconds))
    }

    /* A slider counts from zero, and its range is a count of steps rather than
       the values themselves. These two are where an off-by-one would live, so
       they are here rather than inline in the view. */
    public static var sliderMax: Int { maxSeconds - minSeconds }

    /* Bounded BEFORE the addition, not after, and that is not fussiness: Swift
       traps on integer overflow where Kotlin wraps silently, so the Kotlin
       original's `clamp(progress + MIN_SECONDS)` is a crash here the moment
       progress is Int.max. The test that found it passes Int.max deliberately.

       Worth carrying to every other file ported from Kotlin — it is the one
       language difference in this port that turns a harmless line into a
       crash rather than into a wrong answer. */
    public static func seconds(forProgress progress: Int) -> Int {
        clamp(min(max(progress, 0), sliderMax) + minSeconds)
    }

    public static func progress(forSeconds seconds: Int) -> Int {
        clamp(seconds) - minSeconds
    }
}
