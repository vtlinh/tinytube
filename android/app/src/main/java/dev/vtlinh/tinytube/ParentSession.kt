package dev.vtlinh.tinytube

/* Whether a parent is, right now, on the far side of the gate.

   THE ONE THING THIS IS FOR: the update notification. Tapping it asks for
   settings, and MainActivity runs ChallengeActivity before opening them —
   because a notification sits in the shade where a child can reach it. But an
   adult who is ALREADY in parent mode has just passed that gate, and asking
   again is asking a question that has been answered. So the notification skips
   the challenge exactly when parent mode is on screen, and never otherwise.

   A COUNT RATHER THAN A FLAG, and that is the whole subtlety here. Parent mode
   is two activities — ParentActivity and the SettingsActivity it opens — and
   Android overlaps their lifecycles: opening settings runs SettingsActivity's
   onStart BEFORE ParentActivity's onStop. A single boolean set by whichever
   fired last would read "closed" while the parent was sitting in settings. A
   count goes 1 → 2 → 1 across that handover and never touches zero.

   Started/stopped, not created/destroyed. Backgrounding the app stops both, so
   a phone put down with parent mode open and picked up by a child gets the
   gate back. That is the point of using onStart at all: the question is "is a
   parent looking at this now", not "was one earlier".

   No Android imports, so a plain JVM test can hold it to that — see
   ParentSessionTest. */
object ParentSession {

    private var started = 0

    val isOpen: Boolean get() = started > 0

    fun started() {
        started++
    }

    fun stopped() {
        /* Floored rather than allowed to go negative. Nothing should stop what
           it did not start, but a process death mid-transition is not worth
           making the gate's behaviour depend on. */
        if (started > 0) started--
    }

    /* For tests, and for nothing else. */
    fun reset() {
        started = 0
    }
}
