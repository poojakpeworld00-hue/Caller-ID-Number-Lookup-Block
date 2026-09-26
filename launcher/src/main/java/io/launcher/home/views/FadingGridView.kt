package io.launcher.home.views

import android.content.Context
import android.util.AttributeSet
import io.launcher.home.R
import org.fossify.commons.views.MyRecyclerView

/**
 * The scrolling drawer's grid, with its fading edges on its real edges.
 *
 * The grid draws into its padding (clipToPadding off, the bottom padding being the navigation-bar
 * clearance), but View places a fading edge inside the padding - so the fade sat above a strip of
 * rows drawn at full strength and the last row was cut against the search pill in a hard line.
 * Offsetting by the padding moves both fades onto the view's bounds, where the rows really end.
 */
class FadingGridView(context: Context, attrs: AttributeSet) : MyRecyclerView(context, attrs) {

    init {
        // Stated here rather than only in the layout: nothing else can switch the fade off.
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(resources.getDimensionPixelSize(R.dimen.launcher_drawer_fade))
        setWillNotDraw(false)
    }

    override fun setVerticalFadingEdgeEnabled(verticalFadingEdgeEnabled: Boolean) {
        super.setVerticalFadingEdgeEnabled(true)
    }

    // RecyclerView calls setWillNotDraw(true) whenever over-scroll is off (it is here), and a view
    // that will not draw never runs View.draw - the only place fading edges are painted.
    override fun setWillNotDraw(willNotDraw: Boolean) {
        super.setWillNotDraw(false)
    }

    // View derives the strengths from computeVerticalScroll*, which this grid's base does not report
    // usefully, so no fade was ever drawn. Read them off the rows instead: fade an edge while a row
    // is cut by it or more rows lie beyond it.
    override fun getBottomFadingEdgeStrength(): Float {
        val last = getChildAt(childCount - 1) ?: return 0f
        val more = getChildAdapterPosition(last) < (adapter?.itemCount ?: 0) - 1
        return if (more || last.bottom > height) 1f else 0f
    }

    override fun getTopFadingEdgeStrength(): Float {
        val first = getChildAt(0) ?: return 0f
        return if (getChildAdapterPosition(first) > 0 || first.top < 0) 1f else 0f
    }

    override fun isPaddingOffsetRequired() = true

    override fun getTopPaddingOffset() = -paddingTop

    override fun getBottomPaddingOffset() = paddingBottom
}
