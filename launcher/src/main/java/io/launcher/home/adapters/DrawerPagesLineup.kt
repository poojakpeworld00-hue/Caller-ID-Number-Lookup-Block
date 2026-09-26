package io.launcher.home.adapters

import android.view.View
import io.launcher.home.R
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.launcher.home.activities.LauncherBasePanel
import io.launcher.home.interfaces.AllAppsListener
import io.launcher.home.models.AppLauncher

/**
 * The pages of the paged (Samsung-style) drawer: each holder is a non-scrolling grid of
 * `columns` × `rows` apps with its own [LaunchersLineup], so tap, long-press-to-drag and the
 * launch gate behave exactly as they do in the scrolling drawer. The native slot rides on page
 * one only, where [LaunchersLineup.AD_SLOT] sits in that page's list.
 */
class DrawerPagesLineup(
    private val activity: LauncherBasePanel,
    private val listener: AllAppsListener,
    private val adSlot: View?,
    private val itemClick: (Any) -> Unit,
) : RecyclerView.Adapter<DrawerPagesLineup.PageHolder>() {

    private var pages: List<List<AppLauncher>> = emptyList()
    private var columns = 4
    private var rows = 6

    /** The first page's adapter, for "launch the first result" on a search. */
    var firstPageAdapter: LaunchersLineup? = null
        private set

    fun submitPages(newPages: List<List<AppLauncher>>, columnCount: Int, rowCount: Int) {
        pages = newPages
        columns = columnCount
        rows = rowCount.coerceAtLeast(1)
        notifyDataSetChanged()
    }

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val grid = RecyclerView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(
                0, parent.resources.getDimensionPixelSize(R.dimen.launcher_drawer_top_gap),
                0, parent.resources.getDimensionPixelSize(R.dimen.launcher_drawer_page_bottom_gap),
            )
            itemAnimator = null
            isNestedScrollingEnabled = false
        }
        return PageHolder(grid, parent)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val isFirst = position == 0
        holder.bind(pages[position], columns, rows, if (isFirst) adSlot else null)
        if (isFirst) firstPageAdapter = holder.adapter
    }

    inner class PageHolder(private val grid: RecyclerView, private val pager: ViewGroup) : RecyclerView.ViewHolder(grid) {
        var adapter: LaunchersLineup? = null
            private set
        private var hostsAdSlot = false
        private var rowCount = 6

        init {
            // The page's height is final only after layout, and moves with the search bar, the
            // insets and the nav mode, so the row height follows it instead of being fixed once.
            grid.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) grid.post { syncCellHeight(bottom - top) }
            }
        }

        private fun cellHeightFor(pageHeight: Int) =
            if (pageHeight > 0) (pageHeight - grid.paddingTop - grid.paddingBottom) / rowCount else 0

        private fun syncCellHeight(pageHeight: Int) {
            val lineup = adapter ?: return
            val cell = cellHeightFor(pageHeight)
            if (cell <= 0 || lineup.cellHeightPx == cell) return
            lineup.cellHeightPx = cell
            lineup.notifyItemRangeChanged(0, lineup.itemCount)
        }

        fun bind(apps: List<AppLauncher>, columnCount: Int, rows: Int, slot: View?) {
            rowCount = rows
            // A page never scrolls on its own: what does not fit its rows is clipped, and every
            // vertical drag stays with the sheet (pull-down closes it) instead of being claimed
            // by the page as a scroll.
            val layoutManager = (grid.layoutManager as? GridLayoutManager)
                ?: object : GridLayoutManager(grid.context, columnCount) {
                    override fun canScrollVertically() = false
                }.also { grid.layoutManager = it }
            layoutManager.spanCount = columnCount
            // A page that used to host the slot cannot keep an adapter without it, and vice versa:
            // the slot is a single view, and only the adapter given it re-parents it.
            if (adapter == null || hostsAdSlot != (slot != null)) {
                adapter = LaunchersLineup(activity, listener, slot, itemClick)
                hostsAdSlot = slot != null
                grid.adapter = adapter
            }
            val lineup = adapter!!
            layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (lineup.isAdSlotPosition(position)) layoutManager.spanCount else 1
            }
            // A fresh page is bound before it is attached and laid out: the pager's height is the page's.
            cellHeightFor(grid.height.takeIf { it > 0 } ?: pager.height).takeIf { it > 0 }?.let { lineup.cellHeightPx = it }
            lineup.submitList(apps.toMutableList())
        }
    }
}
