package dev.vtlinh.ytkids

import kotlin.random.Random

/* The gate in front of parent mode.

   Parent mode is unrestricted YouTube in a WebView, which is exactly what the
   rest of the app exists to keep a child out of. The gate is a simultaneous
   equation — X + Y and X − Y are given, X is asked for — because it is trivial
   for an adult and not something a young child can brute-force by tapping.

   It is a speed bump, not a lock. An older child who can do the algebra will
   get through, and anyone who watches a parent type the answer learns nothing
   reusable only because the numbers change each time. If you need a real
   barrier, this wants a PIN instead — say so and it is a small change.

   Android-free so the arithmetic is tested rather than assumed. */
object Challenge {

    data class Puzzle(val sum: Int, val difference: Int) {
        /* X = (sum + difference) / 2 and Y = (sum − difference) / 2, which is
           why generate() only ever emits pairs whose sum is even — an odd one
           has no whole-number solution and the gate would be unanswerable. */
        val x: Int get() = (sum + difference) / 2
        val y: Int get() = (sum - difference) / 2
    }

    /* X is kept to two digits and Y strictly smaller and positive, so the
       difference is never zero (which would give X away as sum/2 at a glance)
       and never negative (which turns it into a sign-handling puzzle rather
       than a quick one). */
    fun generate(random: Random = Random.Default): Puzzle {
        val x = random.nextInt(12, 50)
        val y = random.nextInt(3, x)
        return Puzzle(sum = x + y, difference = x - y)
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
     * number keypad can produce all three. */
    fun isCorrect(puzzle: Puzzle, xInput: String, yInput: String): Boolean {
        val x = xInput.trim().toIntOrNull() ?: return false
        val y = yInput.trim().toIntOrNull() ?: return false
        return x == puzzle.x && y == puzzle.y
    }
}
