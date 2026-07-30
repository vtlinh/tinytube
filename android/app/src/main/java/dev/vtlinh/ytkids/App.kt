package dev.vtlinh.ytkids

import android.app.Application
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
