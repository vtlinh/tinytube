import Foundation

/* The gate in front of parent mode.

   Parent mode is unrestricted YouTube in a web view, which is exactly what the
   rest of the app exists to keep a child out of. The gate is a simultaneous
   equation — X + Y and X − Y are given, both are asked for — because it is
   trivial for an adult and not something a young child can brute-force by
   tapping.

   It is a speed bump, not a lock, and on iOS it is needed even less often than
   on Android: LocalAuthentication's deviceOwnerAuthentication policy falls back
   to the passcode by itself, so a device with any lock at all never reaches
   this. It exists for a device with no lock set up at all.

   Ported from Challenge.kt. Free of UIKit so the arithmetic is tested rather
   than assumed. */
public enum Challenge {

    public struct Puzzle: Equatable, Sendable {
        public let sum: Int
        public let difference: Int

        public init(sum: Int, difference: Int) {
            self.sum = sum
            self.difference = difference
        }

        /* X = (sum + difference) / 2 and Y = (sum − difference) / 2, which is
           why generate() only ever emits pairs whose sum is even — an odd one
           has no whole-number solution and the gate would be unanswerable. */
        public var x: Int { (sum + difference) / 2 }
        public var y: Int { (sum - difference) / 2 }
    }

    /* X is kept to two digits and Y strictly smaller and positive, so the
       difference is never zero (which would give X away as sum/2 at a glance)
       and never negative (which turns it into a sign-handling puzzle rather
       than a quick one).

       The generator is injected so the tests can pin it. It returns a value in
       the given range, matching Kotlin's nextInt(from, until) — half-open at
       the top. */
    public static func generate(
        _ nextInt: (Int, Int) -> Int = { from, until in Int.random(in: from..<until) }
    ) -> Puzzle {
        let x = nextInt(12, 50)
        let y = nextInt(3, x)
        return Puzzle(sum: x + y, difference: x - y)
    }

    /* Both unknowns have to be right.
     *
     * Asking for X alone leaves a shortcut: X is (sum + difference) / 2, and
     * "halve the two numbers you can see and add them" is a rule that can be
     * copied without understanding it. Requiring Y as well means actually
     * solving the pair.
     *
     * Blank, non-numeric and out-of-range input are all simply wrong rather
     * than errors — there is nothing useful to say beyond "try again", and a
     * number keypad can produce all three.
     *
     * Int(_:) rather than a formatter, deliberately: a locale-aware parse would
     * accept "1 234" or "١٢" on some devices, and this compares against a
     * number the app generated itself. */
    public static func isCorrect(_ puzzle: Puzzle, xInput: String, yInput: String) -> Bool {
        guard let x = Int(xInput.trimmingCharacters(in: .whitespacesAndNewlines)),
              let y = Int(yInput.trimmingCharacters(in: .whitespacesAndNewlines))
        else { return false }
        return x == puzzle.x && y == puzzle.y
    }
}
