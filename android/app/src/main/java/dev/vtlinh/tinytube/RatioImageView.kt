package dev.vtlinh.tinytube

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/* An ImageView that is always 16:9, whatever it contains.

   The tiles have to hold their shape before their poster has loaded, or the
   grid reflows under the child's finger as images arrive — a tap landing on a
   different video than the one that was there when it started. adjustViewBounds
   can't do this: it derives the height from a drawable that isn't there yet.

   Ten lines here instead of a ConstraintLayout dependency for one ratio. */
class RatioImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        setMeasuredDimension(w, w * 9 / 16)
    }
}
