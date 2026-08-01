package dev.vtlinh.tinytube

import android.app.Activity
import android.content.Intent
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat

/* Wiring for the bottom bar in bottom_tabs.xml.

   Two tabs and no framework. BottomNavigationView would want a Material theme
   and a menu resource to draw two items that never change, and its selection
   model assumes a single host — which this is not: the bar appears on the grid
   and on About, and on About both tabs are a way back to the grid. */
object BottomTabs {

    const val VIDEOS = 0
    const val CHANNELS = 1

    /* Which tab MainActivity should open on, when something else sends you
       there. Read and cleared by MainActivity. */
    const val EXTRA_TAB = "tab"

    /* Wire the bar on any screen, saying which tab is current and what to do
       when one is tapped. */
    fun bind(activity: Activity, selected: Int, onSelect: (Int) -> Unit) {
        activity.findViewById<android.view.View>(R.id.tab_videos)
            ?.setOnClickListener { onSelect(VIDEOS) }
        activity.findViewById<android.view.View>(R.id.tab_channels)
            ?.setOnClickListener { onSelect(CHANNELS) }
        select(activity, selected)
    }

    /* Paint the selection. Colour only — no indicator bar, no size change: on
       two tabs the accent is unambiguous, and something that moves under a
       thumb is one more thing for a child to play with rather than watch a
       video. */
    fun select(activity: Activity, selected: Int) {
        paint(activity, R.id.tab_videos_icon, R.id.tab_videos_label, selected == VIDEOS)
        paint(activity, R.id.tab_channels_icon, R.id.tab_channels_label, selected == CHANNELS)
    }

    private fun paint(activity: Activity, iconId: Int, labelId: Int, on: Boolean) {
        val colour = ContextCompat.getColor(
            activity,
            if (on) R.color.accent else R.color.text_muted,
        )
        activity.findViewById<ImageView>(iconId)?.setColorFilter(colour)
        activity.findViewById<TextView>(labelId)?.setTextColor(colour)
    }

    /* From a screen that is not the grid: go back to it, on the tab asked for.
     *
     * CLEAR_TOP rather than a new instance, so this returns to the grid that
     * is already running instead of stacking a second copy behind About. */
    fun goToGrid(activity: Activity, tab: Int) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TAB, tab),
        )
        activity.finish()
    }
}
