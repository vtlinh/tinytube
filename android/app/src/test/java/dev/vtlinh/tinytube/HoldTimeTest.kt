package dev.vtlinh.tinytube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldTimeTest {

    /* One second, not Android's half — that is short enough for a resting
       thumb, which is the whole thing this control exists to be harder than. */
    @Test fun `the range is one to five seconds, defaulting to one`() {
        assertEquals(1, HoldTime.MIN_SECONDS)
        assertEquals(5, HoldTime.MAX_SECONDS)
        assertEquals(1, HoldTime.DEFAULT_SECONDS)
        assertEquals(1000L, HoldTime.DEFAULT_MILLIS)
    }

    /* A preference file survives an app update and can be edited on a rooted
       device. A hold of zero would make the corner a tap. */
    @Test fun `anything out of range is clamped`() {
        assertEquals(1, HoldTime.clamp(0))
        assertEquals(1, HoldTime.clamp(-7))
        assertEquals(1, HoldTime.clamp(Int.MIN_VALUE))
        assertEquals(5, HoldTime.clamp(6))
        assertEquals(5, HoldTime.clamp(Int.MAX_VALUE))
        for (s in 1..5) assertEquals(s, HoldTime.clamp(s))
    }

    @Test fun `milliseconds follow the clamped seconds`() {
        assertEquals(1000L, HoldTime.millisFor(1))
        assertEquals(5000L, HoldTime.millisFor(5))
        assertEquals(1000L, HoldTime.millisFor(0))
        assertEquals(5000L, HoldTime.millisFor(99))
    }

    /* A SeekBar counts steps from zero while this range starts at one, which
       is exactly where an off-by-one hides — and the end that would break is
       the short one, where a hold becomes something a resting thumb finishes. */
    @Test fun `the slider covers every value and nothing else`() {
        assertEquals(4, HoldTime.sliderMax())
        val reachable = (0..HoldTime.sliderMax()).map { HoldTime.secondsForProgress(it) }
        assertEquals(listOf(1, 2, 3, 4, 5), reachable)
    }

    @Test fun `progress and seconds round-trip`() {
        for (s in HoldTime.MIN_SECONDS..HoldTime.MAX_SECONDS) {
            assertEquals(s, HoldTime.secondsForProgress(HoldTime.progressForSeconds(s)))
        }
        for (p in 0..HoldTime.sliderMax()) {
            assertEquals(p, HoldTime.progressForSeconds(HoldTime.secondsForProgress(p)))
        }
    }

    /* A slider whose thumb is dragged past either end, or restored from a
       stored value that is out of range, must still land on a real setting. */
    @Test fun `a progress outside the track still lands on a usable hold`() {
        for (p in listOf(-3, -1, 5, 99, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val seconds = HoldTime.secondsForProgress(p)
            assertTrue("progress $p gave $seconds", seconds in 1..5)
        }
        for (s in listOf(-3, 0, 6, 99)) {
            val progress = HoldTime.progressForSeconds(s)
            assertTrue("seconds $s gave $progress", progress in 0..HoldTime.sliderMax())
        }
    }
}
