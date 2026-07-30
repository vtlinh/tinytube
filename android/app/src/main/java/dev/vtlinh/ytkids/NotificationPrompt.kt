package dev.vtlinh.ytkids

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/* Shared plumbing for the POST_NOTIFICATIONS request.

   Both the grid (which asks on first launch) and the About screen (which
   explains and re-offers) need the same three facts, and the "have we asked
   before?" bit has to be remembered in one place or the two disagree about
   whether the system will still show a dialog. */
object NotificationPrompt {

    const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

    /* Android gives no way to ask "have I requested this before?", and
       shouldShowRequestPermissionRationale returns false both before the first
       request and after a permanent denial — so the two are indistinguishable
       without remembering it ourselves. */
    private const val ASKED_KEY = "askedNotificationPermission"

    private fun prefs(context: Context) =
        context.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(ASKED_KEY, true).apply()
    }

    fun state(activity: Activity): Notifications.State = Notifications.state(
        sdkInt = Build.VERSION.SDK_INT,
        enabled = NotificationManagerCompat.from(activity).areNotificationsEnabled(),
        askedBefore = prefs(activity).getBoolean(ASKED_KEY, false),
        showRationale = Build.VERSION.SDK_INT >= Notifications.RUNTIME_PERMISSION_SDK &&
            activity.shouldShowRequestPermissionRationale(PERMISSION),
    )
}
