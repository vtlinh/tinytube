package dev.vtlinh.tinytube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChallengeTest {

    /* Every puzzle the generator can emit must actually be solvable as stated:
       both (sum + difference) / 2 and (sum − difference) / 2 have to come out
       whole, or the gate asks for numbers that don't exist and nobody gets in. */
    @Test fun `every generated puzzle has exact integer answers`() {
        for (seed in 0 until 2000) {
            val p = Challenge.generate(Random(seed))
            assertEquals(
                "sum+difference must be even for seed $seed",
                0, (p.sum + p.difference) % 2,
            )
            assertEquals("x+y should equal sum for seed $seed", p.sum, p.x + p.y)
            assertEquals("x-y should equal difference for seed $seed", p.difference, p.x - p.y)
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
            assertTrue("x=${p.x} out of range for seed $seed", p.x in 12..49)
            assertTrue("y=${p.y} out of range for seed $seed", p.y in 3..48)
            assertTrue("y should be below x for seed $seed", p.y < p.x)
        }
    }

    @Test fun `accepts both answers, with surrounding whitespace`() {
        val p = Challenge.Puzzle(sum = 30, difference = 10)   // x=20, y=10
        assertEquals(20, p.x)
        assertEquals(10, p.y)
        assertTrue(Challenge.isCorrect(p, "20", "10"))
        assertTrue(Challenge.isCorrect(p, "  20 ", " 10  "))
    }

    /* The whole reason both are asked: X alone can be produced by a rule —
       halve the sum and the difference, add them — that someone can copy
       without solving anything. Y has to be right too. */
    @Test fun `the right x with the wrong y is refused`() {
        val p = Challenge.Puzzle(sum = 30, difference = 10)   // x=20, y=10
        assertFalse(Challenge.isCorrect(p, "20", "9"))
        assertFalse(Challenge.isCorrect(p, "20", "20"))
        assertFalse(Challenge.isCorrect(p, "20", ""))
    }

    @Test fun `the right y with the wrong x is refused`() {
        val p = Challenge.Puzzle(sum = 30, difference = 10)
        assertFalse(Challenge.isCorrect(p, "19", "10"))
        assertFalse(Challenge.isCorrect(p, "", "10"))
    }

    /* Swapping them is the obvious slip, and it must not pass. */
    @Test fun `swapping x and y is refused`() {
        val p = Challenge.Puzzle(sum = 30, difference = 10)   // x=20, y=10
        assertFalse(Challenge.isCorrect(p, "10", "20"))
    }

    /* A keypad can produce every one of these, and none is an error worth
       distinguishing from a wrong answer. */
    @Test fun `rejects blank and non-numeric input without throwing`() {
        val p = Challenge.Puzzle(sum = 30, difference = 10)
        val bad = listOf("", "   ", "abc", "2 0", "20.0", "99999999999999999999")
        for (b in bad) {
            assertFalse("should have rejected x='$b'", Challenge.isCorrect(p, b, "10"))
            assertFalse("should have rejected y='$b'", Challenge.isCorrect(p, "20", b))
        }
    }

    /* toIntOrNull follows Integer.parseInt, which accepts any Unicode decimal
       digit — so Arabic-Indic ٢٠ is read as 20 and accepted. That is right: a
       parent typing on an Arabic keyboard is giving the correct answer, and
       the gate exists to stop a small child, not to insist on ASCII. */
    @Test fun `accepts answers written in non-ascii digits`() {
        val p = Challenge.Puzzle(sum = 30, difference = 10)   // x=20, y=10
        assertTrue(Challenge.isCorrect(p, "٢٠", "١٠"))
        assertFalse(Challenge.isCorrect(p, "٢١", "١٠"))
    }

    /* Successive puzzles must not be the same one, or watching a parent answer
       once is enough to get in forever. */
    @Test fun `successive puzzles vary`() {
        val random = Random(7)
        val seen = (0 until 50).map { Challenge.generate(random) }.toSet()
        assertTrue("expected variety, got $seen", seen.size > 10)
    }

    /* A leading plus is ACCEPTED, on both platforms, and that is worth pinning
       rather than leaving to be rediscovered. Kotlin's toIntOrNull and Swift's
       Int(_:) both take "+20" as 20 — the iOS port's test asserted it was junk
       and was wrong about both. Neither is a problem: a parent who types it has
       answered correctly.

       Pinned because it is the kind of agreement that could silently stop being
       one, and the gate is a place where the two apps must behave alike. */
    @Test fun `a leading plus is accepted, matching the iOS port`() {
        val p = Challenge.Puzzle(sum = 30, difference = 10)   // x = 20, y = 10
        assertTrue(Challenge.isCorrect(p, "+20", "10"))
        assertTrue(Challenge.isCorrect(p, "20", "+10"))
    }
}
