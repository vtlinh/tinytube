package dev.vtlinh.ytkids

import android.content.Context

/* The parent's choices. Currently one: what plays after a video ends.

   On the device, like the approved list, and for the same reason — there is no
   server here and nothing about this app should start needing one. Read on the
   child's screen, written only from behind the gate. */
object SettingsStore {

    private const val PREFS = "settings"
    private const val KEY_NEXT = "next_mode"
    private const val KEY_CHANNEL_SORT = "channel_sort"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* Stored by NAME rather than by ordinal. An ordinal is a promise never to
       reorder an enum, and this one will grow. */
    fun nextMode(context: Context): Playlist.Mode =
        Playlist.modeOf(prefs(context).getString(KEY_NEXT, null))

    fun setNextMode(context: Context, mode: Playlist.Mode) {
        prefs(context).edit().putString(KEY_NEXT, mode.name).apply()
    }

    /* How the approved channels are ordered. Set from the parent's list, and
       read by the child's Channels tab too — it is the same list, and two
       orders for one list is how a parent ends up unable to find on one screen
       what they just arranged on another. */
    fun channelSort(context: Context): ChannelSort.Mode =
        ChannelSort.modeOf(prefs(context).getString(KEY_CHANNEL_SORT, null))

    fun setChannelSort(context: Context, mode: ChannelSort.Mode) {
        prefs(context).edit().putString(KEY_CHANNEL_SORT, mode.name).apply()
    }
}
