package dev.vtlinh.ytkids

import dev.vtlinh.ytkids.Notifications.State
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationsTest {

    private fun state(sdk: Int, enabled: Boolean, asked: Boolean, rationale: Boolean) =
        Notifications.state(sdk, enabled, asked, rationale)

    @Test fun `enabled is ok on every version`() {
        for (sdk in listOf(26, 32, 33, 34, 36)) {
            assertEquals(State.OK, state(sdk, enabled = true, asked = false, rationale = false))
            /* still OK even if we asked before — the grant is what matters */
            assertEquals(State.OK, state(sdk, enabled = true, asked = true, rationale = false))
        }
    }

    /* Below 33 there is no runtime permission, so a disabled state can only
       have come from Settings. Asking would show nothing at all. */
    @Test fun `pre 33 disabled is blocked, never askable`() {
        for (sdk in listOf(26, 30, 32)) {
            assertEquals(State.BLOCKED, state(sdk, enabled = false, asked = false, rationale = false))
            assertEquals(State.BLOCKED, state(sdk, enabled = false, asked = true, rationale = true))
        }
    }

    @Test fun `33 plus, never asked, is askable`() {
        assertEquals(State.ASKABLE, state(33, enabled = false, asked = false, rationale = false))
        assertEquals(State.ASKABLE, state(34, enabled = false, asked = false, rationale = false))
    }

    /* Denied once: the system still shows the dialog, and shouldShowRationale
       says so. */
    @Test fun `33 plus, denied once, is still askable`() {
        assertEquals(State.ASKABLE, state(34, enabled = false, asked = true, rationale = true))
    }

    /* Denied twice: requestPermissions returns denied without any UI. Offering
       an "Allow" button here is a button that visibly does nothing. */
    @Test fun `33 plus, permanently denied, is blocked`() {
        assertEquals(State.BLOCKED, state(34, enabled = false, asked = true, rationale = false))
    }
}
