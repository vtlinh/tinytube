package dev.vtlinh.ytkids

import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

/* The parent's settings.

   Not exported, and started only by a RESULT_OK from ChallengeActivity — the
   same rule ParentActivity lives under, for a weaker reason: nothing here is
   real YouTube, but a child who could reach it could set their own next-video
   rule, and a setting a child can change is not a parental control.

   Saved on the tap rather than behind a Save button. There is no draft state
   to lose and nothing to confirm; the radio IS the setting. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val group = findViewById<RadioGroup>(R.id.next_mode)
        group.check(
            when (SettingsStore.nextMode(this)) {
                Playlist.Mode.IN_ORDER -> R.id.next_in_order
                Playlist.Mode.RANDOM -> R.id.next_random
            },
        )
        /* Checked AFTER the initial state is set, so restoring the stored
           value doesn't write it straight back. Harmless here, but a listener
           that fires while a screen is being built is how a "setting" quietly
           becomes whatever the first radio happens to be. */
        group.setOnCheckedChangeListener { _, id ->
            SettingsStore.setNextMode(
                this,
                if (id == R.id.next_random) Playlist.Mode.RANDOM else Playlist.Mode.IN_ORDER,
            )
        }
    }
}
