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
        /* X = (sum + difference) / 2, which is why generate() only ever emits
           pairs whose sum is even. */
        val answer: Int get() = (sum + difference) / 2
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

    /* Accepts whatever the parent typed. Blank, non-numeric and out-of-range
       input are all just wrong rather than errors — there is nothing useful to
       say about them beyond "try again", and a keypad can produce all three. */
    fun isCorrect(puzzle: Puzzle, input: String): Boolean {
        val n = input.trim().toIntOrNull() ?: return false
        return n == puzzle.answer
    }
}
