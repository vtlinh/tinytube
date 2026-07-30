package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChallengeTest {

    /* Every puzzle the generator can emit must actually be solvable as stated:
       X = (sum + difference) / 2 has to come out a whole number, or the gate
       asks for something that doesn't exist and nobody can get in. */
    @Test fun `every generated puzzle has an exact integer answer`() {
        for (seed in 0 until 2000) {
            val p = Challenge.generate(Random(seed))
            assertEquals(
                "sum+difference must be even for seed $seed",
                0, (p.sum + p.difference) % 2,
            )
            val x = p.answer
            val y = p.sum - x
            assertEquals("x+y should equal sum for seed $seed", p.sum, x + y)
            assertEquals("x-y should equal difference for seed $seed", p.difference, x - y)
        }
    }

    /* A zero difference makes X visibly sum/2; a negative one turns a quick
       question into sign-juggling at the moment a parent just wants in. */
    @Test fun `difference is always positive`() {
        for (seed in 0 until 2000) {
            assertTrue(Challenge.generate(Random(seed)).difference > 0)
        }
    }

    @Test fun `both operands stay positive and small enough to do in the head`() {
        for (seed in 0 until 2000) {
            val p = Challenge.generate(Random(seed))
            val x = p.answer
            val y = p.sum - x
            assertTrue("x=$x out of range for seed $seed", x in 12..49)
            assertTrue("y=$y out of range for seed $seed", y in 3..48)
            assertTrue("y should be below x for seed $seed", y < x)
        }
    }

    @Test fun `accepts the right answer, with surrounding whitespace`() {
        val p = Challenge.Puzzle(sum = 30, difference = 10)   // x=20, y=10
        assertEquals(20, p.answer)
        assertTrue(Challenge.isCorrect(p, "20"))
        assertTrue(Challenge.isCorrect(p, "  20 "))
    }

    /* A keypad can produce every one of these, and none of them is an error
       worth distinguishing from a wrong answer. */
    @Test fun `rejects wrong, blank and non-numeric input without throwing`() {
        val p = Challenge.Puzzle(sum = 30, difference = 10)   // x=20
        for (bad in listOf("", "   ", "19", "21", "10", "abc", "2 0", "20.0", "-20", "99999999999999999999")) {
            assertFalse("should have rejected '$bad'", Challenge.isCorrect(p, bad))
        }
    }

    /* toIntOrNull follows Integer.parseInt, which accepts any Unicode decimal
       digit — so Arabic-Indic ٢٠ is read as 20 and accepted. That is the right
       behaviour and worth pinning: a parent typing on an Arabic keyboard is
       giving the correct answer, and the gate exists to stop a small child,
       not to insist on ASCII. */
    @Test fun `accepts the right answer written in non-ascii digits`() {
        val p = Challenge.Puzzle(sum = 30, difference = 10)   // x=20
        assertTrue(Challenge.isCorrect(p, "٢٠"))    // ٢٠
        assertFalse(Challenge.isCorrect(p, "٢١"))   // ٢١
    }

    /* Successive puzzles must not be the same one, or watching a parent type
       the answer once is enough to get in forever. */
    @Test fun `successive puzzles vary`() {
        val random = Random(7)
        val seen = (0 until 50).map { Challenge.generate(random) }.toSet()
        assertTrue("expected variety, got $seen", seen.size > 10)
    }
}
