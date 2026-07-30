package dev.vtlinh.ytkids

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/* Watches the whole app's foreground lifecycle so the self-update check runs
   from any screen rather than only the grid's onResume — which the "resume
   straight back into the player" path would skip. */
class App : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        /* The app is dark, not "dark when the phone is". Most of the screen
           time here is a video on a black background in a dim room, and a grid
           that is cream on one device and near-black on another — flashing
           between the two on every return from the player — is worse than
           picking one. values/ still carries the light palette and the theme
           is still DayNight, so MODE_NIGHT_FOLLOW_SYSTEM here is all it takes
           to hand the choice back to the phone. */
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        /* if we're now running the version we had cached, the update went
           through — delete the leftover APK (kept otherwise for a retry) */
        try { Updater.cleanupIfInstalled(applicationContext) } catch (e: Exception) {}
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    Updater.autoCheck(applicationContext, scope)
                }
            },
        )
    }
}
