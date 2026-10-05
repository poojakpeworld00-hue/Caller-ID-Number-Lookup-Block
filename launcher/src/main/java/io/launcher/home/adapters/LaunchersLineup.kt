package io.launcher.home.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.realScreenSize
import io.launcher.home.R
import io.launcher.home.promo.SponsoredTiles
import com.bumptech.glide.Glide
import io.launcher.home.activities.LauncherBasePanel
import io.launcher.home.databinding.LnchItemLauncherLabelBinding
import io.launcher.home.extensions.animateScale
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.helpers.IconLoader
import io.launcher.home.helpers.LogoShape
import io.launcher.home.interfaces.AllAppsListener
import io.launcher.home.models.AppLauncher

/**
 * The drawer's grid.
 *
 * [adSlot], when given, rides in the list as a full-span row of its own — see [AD_SLOT], the marker
 * the caller puts at the index the row belongs at. It is carried *inside* the list rather than
 * concatenated ahead of it because its position is configurable
 * (`launcher.screens.app_drawer.ad_row_position`), and a concatenated adapter can only ever prepend
 * or append. Keeping the marker in the submitted list is what keeps the differ honest: every
 * position it reports is still a position in this adapter.
 */
class LaunchersLineup(
    val activity: LauncherBasePanel,
    val allAppsListener: AllAppsListener,
    private val adSlot: View? = null,
    val itemClick: (Any) -> Unit
) : ListAdapter<AppLauncher, RecyclerView.ViewHolder>(AppLauncherDiffCallback()),
    RecyclerViewFastScroller.OnPopupTextUpdate {

    private var iconPadding = 0
    private var iconPx = 0

    /** The tile's own horizontal padding each side (lnch_item_launcher_label's paddingStart/End). */
    private val tilePadding = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)

    /** The grid this adapter draws into, for its measured width - see [knownIconWidth]. */
    private var recycler: RecyclerView? = null

    /**
     * The grid's width changed (first measure, rotation, split screen): every tile is re-sized
     * against the new width. Posted, so the rebind lands after the layout pass, not inside it.
     */
    private val gridSizeListener = View.OnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left > 0 && right - left != oldRight - oldLeft) {
            v.post { if (itemCount > 0) notifyItemRangeChanged(0, itemCount) }
        }
    }

    /** The search query whose first occurrence in a label is tinted with the accent (One UI Finder style); empty = plain labels. */
    var highlight: String = ""
        set(value) { field = value.trim() }
    private var labelTextSp = 0f

    /** A sponsored logo's decode size and shape: an app icon's cache size, so Glide caches one bitmap per logo. */
    private val logoPx = activity.resources.getDimensionPixelSize(R.dimen.launcher_icon_cache_size)
    private val logoShape = LogoShape(activity.resources, activity.launcherConfig.legacyIconTray)

    /**
     * The paged drawer's row height: the page split evenly over its rows, with icon and label
     * centred in each cell - how One UI lays out its apps screen (`cell_layout` height / rows,
     * `ItemStyleUtil.getItemPosition` centring the content). 0 = wrap (the scrolling drawer).
     */
    var cellHeightPx = 0

    init {
        setHasStableIds(true)
        calculateIconWidth()
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).getLauncherIdentifier().hashCode().toLong()
    }

    override fun getItemViewType(position: Int) =
        if (isAdSlot(getItem(position))) VIEW_TYPE_AD else VIEW_TYPE_LAUNCHER

    /** True when [position] holds the ad row rather than an app — the grid gives it the full span. */
    fun isAdSlotPosition(position: Int) =
        position in 0 until itemCount && isAdSlot(getItem(position))

    fun launchFirstApp(): Boolean {
        val launcher = currentList.firstOrNull { !isAdSlot(it) } ?: return false
        itemClick(launcher)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_TYPE_AD) {
            // The slot itself is never the itemView: RecyclerView would then own a view the ad SDK
            // also holds on to, and creating a second holder before the first is recycled would try
            // to attach it twice. The holder is a throwaway host the slot is moved into instead.
            val host = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return AdViewHolder(host)
        }

        val binding = LnchItemLauncherLabelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding.root)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView
        recyclerView.addOnLayoutChangeListener(gridSizeListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnLayoutChangeListener(gridSizeListener)
        if (recycler === recyclerView) recycler = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /**
     * Inflates [count] app cells into [grid]'s pool ahead of the first scroll, one per idle moment
     * of the main thread. Without it the first fling after the process starts inflates a fresh cell
     * for every row that scrolls in (2-9 ms each), which shows as a stutter; afterwards cells are
     * only ever recycled.
     */
    fun prewarm(grid: RecyclerView, count: Int) {
        val pool = grid.recycledViewPool
        pool.setMaxRecycledViews(VIEW_TYPE_LAUNCHER, count)
        Looper.myQueue().addIdleHandler {
            if (grid.adapter !== this || pool.getRecycledViewCount(VIEW_TYPE_LAUNCHER) >= count) return@addIdleHandler false
            pool.putRecycledView(createViewHolder(grid, VIEW_TYPE_LAUNCHER))
            true
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AdViewHolder -> holder.bindSlot()
            is ViewHolder -> holder.bindView(getItem(position))
        }
    }

    /**
     * Hosts [adSlot]. The row exists whether or not an ad has arrived: while the slot is GONE — no
     * fill, or a placement the launcherConfig does not define — the host wraps to zero height and the row is
     * invisible, so a fill that lands later only has to reveal the slot to re-measure the row in
     * place, with nothing inserted or removed under the user.
     */
    inner class AdViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bindSlot() {
            val slot = adSlot ?: return
            val host = itemView as FrameLayout
            if (slot.parent !== host) {
                (slot.parent as? ViewGroup)?.removeView(slot)
                host.addView(
                    slot,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
    }

    override fun submitList(list: MutableList<AppLauncher>?) {
        calculateIconWidth()
        super.submitList(list)
    }

    private fun calculateIconWidth() {
        labelTextSp = activity.launcherConfig.labelTextSp
        val currentColumnCount = activity.launcherConfig.drawerColumnCount
        val iconWidth = activity.realScreenSize.x / currentColumnCount
        // The previous launcher's icon size in px, when known: the image view pads itself to it once
        // it is laid out (its real width is the grid's cell less the list's and the item's own
        // horizontal padding, which only the layout pass knows).
        val drawerDp = activity.launcherConfig.drawerIconSizeDp.takeIf { it > 0 } ?: activity.launcherConfig.iconSizeDp
        iconPx = (drawerDp * activity.resources.displayMetrics.density).toInt()
        // 15% of the cell at scale 1, as before; the profile's scale grows or shrinks the icon
        // inside the same cell (1.3 -> ~4% padding, 0.7 -> ~26%). Used when no icon size was read.
        val padding = 0.5f - 0.35f * activity.launcherConfig.iconScale
        iconPadding = (iconWidth * padding.coerceIn(0f, 0.45f)).toInt()
    }

    /**
     * Pads [view] so exactly [iconPx] stays visible inside its laid-out width, and makes it just
     * that tall (plus a small top gap) so a row is icon + label rather than a cell-wide square;
     * no-op once it already is.
     */
    private fun padToIconSize(view: View, width: Int) {
        if (width <= 0) return
        val side = ((width - iconPx) / 2).coerceIn(0, (width * 0.45f).toInt())
        val top = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
        if (view.paddingLeft != side || view.paddingTop != top || view.paddingRight != side || view.paddingBottom != 0) {
            view.setPadding(side, top, side, 0)
        }
        val height = iconPx + top
        if (view.layoutParams.height != height) {
            view.layoutParams = view.layoutParams.apply { this.height = height }
        }
    }

    /** Without a profile size the view is what it always was: a square as wide as the cell. */
    private fun squareByWidth(view: View, width: Int) {
        if (width > 0 && view.layoutParams.height != width) {
            view.layoutParams = view.layoutParams.apply { height = width }
        }
    }

    private fun highlightedTitle(title: String): CharSequence {
        if (highlight.isEmpty()) return title
        val start = title.indexOf(highlight, ignoreCase = true)
        if (start < 0) return title
        return SpannableString(title).apply {
            setSpan(ForegroundColorSpan(activity.getColor(R.color.lnch_search_highlight)), start, start + highlight.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private val iconSizeListener = View.OnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) {
            if (iconPx > 0) padToIconSize(v, right - left) else squareByWidth(v, right - left)
        }
    }

    /**
     * The icon view's width, worked out from the grid's own measured width rather than the
     * screen's: its content width split over the spans, less the tile's padding. That is exactly
     * what GridLayoutManager gives each cell, and it holds in split screen and on a foldable,
     * where the screen width does not. 0 while the grid has not been measured.
     */
    private fun knownIconWidth(): Int {
        val grid = recycler ?: return 0
        val content = grid.width - grid.paddingLeft - grid.paddingRight
        val spans = (grid.layoutManager as? GridLayoutManager)?.spanCount ?: return 0
        if (content <= 0 || spans <= 0) return 0
        return (content / spans - 2 * tilePadding).coerceAtLeast(0)
    }

    /**
     * Sizes the icon view at bind time from [knownIconWidth], so a tile is right in the frame it is
     * first drawn - the first tiles used to be bound before they had a width and sat clipped until a
     * scroll rebound them. The layout listener stays as the fallback: it covers a grid not yet
     * measured, and is a no-op whenever the computed width was right.
     */
    private fun sizeIcon(view: View) {
        val width = knownIconWidth().takeIf { it > 0 } ?: view.width
        if (iconPx > 0) {
            padToIconSize(view, width)
        } else {
            view.setPadding(iconPadding, iconPadding, iconPadding, 0)
            squareByWidth(view, width)
        }
        view.removeOnLayoutChangeListener(iconSizeListener)
        view.addOnLayoutChangeListener(iconSizeListener)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private fun fitToCell() {
            val holder = itemView as? RelativeLayout ?: return
            val height = if (cellHeightPx > 0) cellHeightPx else ViewGroup.LayoutParams.WRAP_CONTENT
            val gravity = if (cellHeightPx > 0) Gravity.CENTER else Gravity.START or Gravity.TOP
            if (holder.gravity != gravity) holder.gravity = gravity
            val params = holder.layoutParams ?: return
            if (params.height != height) holder.layoutParams = params.apply { this.height = height }
        }

        @SuppressLint("ClickableViewAccessibility")
        fun bindView(launcher: AppLauncher): View {
            val binding = LnchItemLauncherLabelBinding.bind(itemView)
            fitToCell()
            itemView.apply {
                val sponsored = SponsoredTiles.isSponsored(launcher)
                // A sponsored tile says so in its label: it opens a web page, not an installed app.
                binding.launcherLabelUi.text =
                    if (sponsored) context.getString(R.string.lnch_sponsored_label, launcher.title)
                    else highlightedTitle(launcher.title)
                // The drawer is a translucent scrim over the wallpaper, so an app name is white
                // here whatever the theme says — re-stated on every bind because MyTextView takes
                // the theme's colour when its view is inflated. The layout carries the same colour
                // and the shadow that keeps it legible against a light wallpaper.
                binding.launcherLabelUi.setTextColor(Color.WHITE)
                // Same label size as the home screen when the profile resolved one; else the layout's 12sp.
                labelTextSp.takeIf { it > 0f }?.let { binding.launcherLabelUi.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
                binding.launcherLabelUi.beVisibleIf(activity.launcherConfig.showDrawerAppLabels)
                sizeIcon(binding.launcherIconUi)

                val icon = binding.launcherIconUi
                // A recycled tile may still have a sponsored logo on its way in; it must not land
                // on the app now bound here.
                Glide.with(activity).clear(icon)
                val ready = if (sponsored) null
                    else launcher.drawable ?: IconLoader.cached(launcher.packageName, launcher.activityName)
                if (sponsored) {
                    // Shaped like every other drawer icon (the device's mask, same size), not the
                    // raw square the logo URL serves; see LogoShape.
                    icon.tag = null
                    Glide.with(activity)
                        .load(SponsoredTiles.logo(launcher))
                        .override(logoPx)
                        .transform(logoShape)
                        .placeholder(R.drawable.lnch_placeholder_drawable)
                        .error(R.drawable.lnch_placeholder_drawable)
                        .into(icon)
                } else if (ready != null) {
                    // Already decoded and in memory: set it in this frame. Going through Glide put
                    // the coloured placeholder up first and swapped the icon in a frame or more
                    // later, which showed as every icon "refreshing" on each re-bind.
                    launcher.drawable = ready
                    icon.setImageDrawable(ready)
                    icon.tag = null
                } else {
                    // No icon yet (a cold start): the tile's tint for the moment, and its own icon
                    // decoded right now - not after every other app's, which is what the full scan
                    // waits for. The tag says which app this (recycled) view is waiting on.
                    val wanted = launcher.packageName + "/" + launcher.activityName
                    icon.tag = wanted
                    icon.setImageDrawable(
                        activity.resources.getColoredDrawableWithColor(
                            drawableId = R.drawable.lnch_placeholder_drawable,
                            color = launcher.thumbnailColor
                        )
                    )
                    IconLoader.load(activity, launcher.packageName, launcher.activityName) { loaded ->
                        if (icon.tag == wanted) {
                            launcher.drawable = loaded
                            icon.setImageDrawable(loaded)
                            icon.tag = null
                        }
                    }
                }

                setOnClickListener { itemClick(launcher) }
                setOnLongClickListener {
                    // Nothing to drag onto the home screen or uninstall: a tile is a link.
                    if (sponsored) return@setOnLongClickListener true
                    val location = IntArray(2)
                    getLocationOnScreen(location)
                    allAppsListener.onAppLauncherLongPressed(
                        x = (location[0] + width / 2).toFloat(),
                        y = location[1].toFloat(),
                        appLauncher = launcher
                    )
                    true
                }

                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            binding.launcherIconUi.drawable?.alpha = LAUNCHER_ALPHA_PRESSED
                            animateScale(
                                from = LAUNCHER_SCALE_NORMAL,
                                to = LAUNCHER_SCALE_PRESSED,
                                duration = LAUNCHER_SCALE_UP_DURATION
                            )
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            binding.launcherIconUi.drawable?.alpha = LAUNCHER_ALPHA_NORMAL
                            animateScale(
                                from = LAUNCHER_SCALE_PRESSED,
                                to = LAUNCHER_SCALE_NORMAL,
                                duration = LAUNCHER_SCALE_DOWN_DURATION
                            )
                        }
                    }
                    false
                }
            }

            return itemView
        }
    }

    override fun onChange(position: Int) = currentList.getOrNull(position)?.getBubbleText() ?: ""

    companion object {
        private const val LAUNCHER_SCALE_NORMAL = 1f
        private const val LAUNCHER_SCALE_PRESSED = 1.15f
        private const val LAUNCHER_SCALE_UP_DURATION = 100L
        private const val LAUNCHER_SCALE_DOWN_DURATION = 50L
        private const val LAUNCHER_ALPHA_NORMAL = 255
        private const val LAUNCHER_ALPHA_PRESSED = 220

        private const val VIEW_TYPE_LAUNCHER = 0
        private const val VIEW_TYPE_AD = 1

        /**
         * Not a package any launcher can resolve, so it can never collide with a real app: the
         * drawer is built from queryIntentActivities, and this names no activity at all.
         */
        private const val AD_SLOT_PACKAGE = "io.launcher.home.ad_slot"

        /**
         * The marker the caller inserts at the index the ad row belongs at — see
         * `DrawerSurface.submitList`. A single shared instance, so the differ sees the same item
         * across submissions and leaves the row alone while the list around it changes.
         */
        val AD_SLOT = AppLauncher(
            id = null,
            title = "",
            packageName = AD_SLOT_PACKAGE,
            activityName = "",
            order = 0,
            thumbnailColor = 0,
            drawable = null
        )

        fun isAdSlot(item: AppLauncher) = item.packageName == AD_SLOT_PACKAGE
    }
}

class AppLauncherDiffCallback : DiffUtil.ItemCallback<AppLauncher>() {
    override fun areItemsTheSame(oldItem: AppLauncher, newItem: AppLauncher): Boolean {
        return oldItem.getLauncherIdentifier().hashCode().toLong() ==
                newItem.getLauncherIdentifier().hashCode().toLong()
    }

    override fun areContentsTheSame(oldItem: AppLauncher, newItem: AppLauncher): Boolean {
        // The ad row never changes, and saying otherwise would rebind it on every submission —
        // which moves the ad SDK's view between hosts for nothing, list refresh after list refresh.
        if (LaunchersLineup.isAdSlot(oldItem) || LaunchersLineup.isAdSlot(newItem)) {
            return LaunchersLineup.isAdSlot(oldItem) && LaunchersLineup.isAdSlot(newItem)
        }

        return oldItem.title == newItem.title &&
                oldItem.order == newItem.order &&
                oldItem.thumbnailColor == newItem.thumbnailColor &&
                oldItem.drawable != null &&
                newItem.drawable != null
    }
}
