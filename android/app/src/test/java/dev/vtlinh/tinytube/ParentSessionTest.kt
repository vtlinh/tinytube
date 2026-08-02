package dev.vtlinh.tinytube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/* What lets the update notification skip the gate — so the cases where it must
   NOT skip are the ones worth pinning. */
class ParentSessionTest {

    @Before fun clear() = ParentSession.reset()

    @Test fun `closed until parent mode starts`() {
        assertFalse(ParentSession.isOpen)
        ParentSession.started()
        assertTrue(ParentSession.isOpen)
        ParentSession.stopped()
        assertFalse(ParentSession.isOpen)
    }

    /* THE CASE THE COUNT EXISTS FOR. Android starts the incoming activity
       before it stops the outgoing one, so opening settings from parent mode
       runs started() before stopped(). A boolean set by whichever fired last
       would read "closed" while a parent sat in settings, and the notification
       would challenge them for the screen they were looking at. */
    @Test fun `stays open across the handover into settings`() {
        ParentSession.started()             // ParentActivity
        ParentSession.started()             // SettingsActivity.onStart
        ParentSession.stopped()             // ParentActivity.onStop, afterwards
        assertTrue("settings is still parent mode", ParentSession.isOpen)
    }

    /* Backgrounding stops both, and then the gate is owed again: the question
       is whether a parent is looking NOW, not whether one was earlier. */
    @Test fun `closed once everything stops`() {
        ParentSession.started()
        ParentSession.started()
        ParentSession.stopped()
        ParentSession.stopped()
        assertFalse(ParentSession.isOpen)
    }

    /* Nothing should stop what it did not start, but a count that can go
       negative would need one extra started() before it read open again. */
    @Test fun `never goes negative`() {
        ParentSession.stopped()
        ParentSession.stopped()
        assertFalse(ParentSession.isOpen)
        ParentSession.started()
        assertTrue(ParentSession.isOpen)
    }
}
