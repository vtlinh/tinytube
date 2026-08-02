package dev.vtlinh.tinytube

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView

/* The ? beside a section heading, and what it shows.
 *
 * Every explanation on the settings screen used to sit under its heading, in
 * grey, permanently. Four of those is most of the screen: a parent scrolling
 * for the hold slider reads three paragraphs about things they already
 * understand to get to it, and the prose crowds out the controls it is there to
 * explain. So the words are behind a ? now, and the screen is a list of
 * settings again.
 *
 * A POPUP RATHER THAN A DIALOG, and rather than TooltipCompat. A dialog dims
 * the screen and takes a dismiss to leave, which is a heavy answer to "what
 * does this do"; TooltipCompat only appears on a LONG press, which nobody will
 * guess at on a control that looks tappable. This opens on a tap, next to what
 * was tapped, and closes on the next touch anywhere.
 *
 * Not a pure file — it holds a View — so it belongs here rather than beside
 * VideoId and Player. Its counterpart on iOS is an alert, for a reason worth
 * knowing before porting anything: SwiftUI's popover needs 16.4 to stay a
 * popover on an iPhone, and this app targets 16.0, where it would take over the
 * whole screen. See SettingsView. */
object Tooltip {

    /* Wire a ? to its text. The anchor's own content description is set from
       the same string, so a screen reader gets the explanation without having
       to open anything. */
    fun attach(anchor: View, text: CharSequence) {
        anchor.contentDescription = text
        anchor.setOnClickListener { show(it, text) }
    }

    fun attach(anchor: View, textId: Int) {
        attach(anchor, anchor.context.getString(textId))
    }

    /* Public because a toolbar ACTION is a ? too — see
       ApprovedChannelsActivity. Its view belongs to the menu and already has
       the toolbar's own click listener on it, so attach() would replace the
       thing that dispatches the menu item; the popup is shown from the item's
       handler instead, anchored to the same view. */
    fun show(anchor: View, text: CharSequence) {
        val context: Context = anchor.context
        val body = LayoutInflater.from(context).inflate(R.layout.view_tooltip, null)
        body.findViewById<TextView>(R.id.tooltip_text).text = text

        /* Wide enough to read, never wider than the screen. A tooltip that
           matches its anchor's width is a column two words across. */
        val margin = (32 * context.resources.displayMetrics.density).toInt()
        val width = context.resources.displayMetrics.widthPixels - margin

        val popup = PopupWindow(body, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        /* Focusable plus a background is what makes an outside tap dismiss it;
           without the background the touch goes to whatever is underneath and
           the popup stays up. */
        popup.setBackgroundDrawable(ColorDrawable(0))
        popup.elevation = 12f * context.resources.displayMetrics.density
        popup.isOutsideTouchable = true

        /* Pulled left so a ? near the right edge doesn't push the popup off
           screen. showAsDropDown clamps horizontally on modern Android, but not
           on every version this app runs on. */
        popup.showAsDropDown(anchor, -width + anchor.width, 0)
    }
}
