package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTest {

    private val order = Playlist.Mode.IN_ORDER
    private val random = Playlist.Mode.RANDOM

    /* Rolls that are never used, so a test that says IN_ORDER really means it:
       calling this fails rather than quietly returning zero. */
    private val never: (Int) -> Int = { throw AssertionError("should not have rolled") }

    @Test fun `in order walks down the list`() {
        assertEquals(1, Playlist.next(5, 0, order, never))
        assertEquals(4, Playlist.next(5, 3, order, never))
    }

    /* Not a loop. A grid that plays forever with no edge is a different
       product from one that plays what a parent approved and stops. */
    @Test fun `in order stops at the end rather than wrapping`() {
        assertNull(Playlist.next(5, 4, order, never))
        assertNull(Playlist.next(1, 0, order, never))
    }

    @Test fun `random never repeats the video that just played`() {
        for (current in 0 until 5) {
            for (roll in 0 until 4) {
                val next = Playlist.next(5, current, random) { roll }
                assertTrue("rolled $roll at $current gave $next", next != current)
                assertTrue("out of range: $next", next in 0..4)
            }
        }
    }

    /* Every other video is reachable, and each from exactly one roll — so the
       skip-past does not double any of them up or leave one unreachable. */
    @Test fun `random reaches every other video exactly once`() {
        val count = 6
        for (current in 0 until count) {
            val reached = (0 until count - 1).map { roll ->
                Playlist.next(count, current, random) { roll }
            }
            assertEquals(((0 until count).toList() - current).toSet(), reached.toSet())
            assertEquals(count - 1, reached.toSet().size)
        }
    }

    /* With one video there is no other one to pick, and replaying it would
       read as a stuck player rather than as randomness. */
    @Test fun `random with a single video stops`() {
        assertNull(Playlist.next(1, 0, random, never))
    }

    /* A roll outside its contract must not index off the end of the list.
       This is the one input that comes from outside the pure code. */
    @Test fun `an out-of-range roll is clamped rather than trusted`() {
        for (roll in listOf(-5, -1, 4, 99, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val next = Playlist.next(5, 2, random) { roll }
            assertTrue("roll $roll gave $next", next != null && next in 0..4 && next != 2)
        }
    }

    @Test fun `an empty or out-of-range list has no next`() {
        assertNull(Playlist.next(0, 0, order, never))
        assertNull(Playlist.next(0, 0, random, never))
        assertNull(Playlist.next(3, -1, order, never))
        assertNull(Playlist.next(3, 3, order, never))
        assertNull(Playlist.next(3, 99, random, never))
    }

    /* The stored value is a name, and an unknown one has to mean something.
       In order, because that is the behaviour a parent who has never opened
       settings would expect from a list. */
    @Test fun `an unreadable stored mode reads as in order`() {
        assertEquals(Playlist.Mode.IN_ORDER, Playlist.modeOf(null))
        assertEquals(Playlist.Mode.IN_ORDER, Playlist.modeOf(""))
        assertEquals(Playlist.Mode.IN_ORDER, Playlist.modeOf("SHUFFLE"))
        assertEquals(Playlist.Mode.IN_ORDER, Playlist.modeOf("in_order"))
    }

    @Test fun `a stored mode round-trips by name`() {
        for (mode in Playlist.Mode.entries) assertEquals(mode, Playlist.modeOf(mode.name))
    }
}
