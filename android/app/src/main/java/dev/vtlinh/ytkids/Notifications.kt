package dev.vtlinh.ytkids

/* Whether the app can post the "update ready" notification, and what to do
   about it if not.

   Android 13 made POST_NOTIFICATIONS a runtime permission. Declaring it in the
   manifest is not enough: without the grant, notify() succeeds silently and
   nothing appears, so updates download and then sit there with nothing saying
   so. The three outcomes need three different responses, and getting them
   confused means either a dead button or a prompt the system will never show.

   Android-free so the decision is unit-tested rather than reasoned about once
   and trusted; see NotificationsTest. */
object Notifications {

    enum class State {
        /* notifications will appear — nothing to do */
        OK,

        /* the runtime permission can still be requested, and the system will
           actually show a dialog for it */
        ASKABLE,

        /* asking is pointless. Either the user denied twice (Android stops
           showing the dialog and requestPermissions returns denied
           immediately), or they turned notifications off in Settings, or this
           is a pre-13 device where there was never a permission to grant. Only
           Settings can change it from here. */
        BLOCKED,
    }

    /* API 33. Below this POST_NOTIFICATIONS does not exist as a runtime
       permission, so "not enabled" can only mean switched off in Settings. */
    const val RUNTIME_PERMISSION_SDK = 33

    /**
     * @param enabled       NotificationManagerCompat.areNotificationsEnabled()
     * @param askedBefore   have we ever launched the permission request?
     * @param showRationale shouldShowRequestPermissionRationale() — true only
     *                      while the system is still willing to show a dialog
     */
    fun state(
        sdkInt: Int,
        enabled: Boolean,
        askedBefore: Boolean,
        showRationale: Boolean,
    ): State = when {
        enabled -> State.OK
        sdkInt < RUNTIME_PERMISSION_SDK -> State.BLOCKED
        !askedBefore || showRationale -> State.ASKABLE
        else -> State.BLOCKED
    }
}
