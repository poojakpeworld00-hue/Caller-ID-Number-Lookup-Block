package com.calleridapp.numberlookup.launcher.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.transition.Transition
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.realScreenSize
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.launcher.activities.SimpleActivity
import com.calleridapp.numberlookup.databinding.ItemLauncherLabelBinding
import com.calleridapp.numberlookup.launcher.extensions.animateScale
import com.calleridapp.numberlookup.launcher.extensions.config
import com.calleridapp.numberlookup.launcher.interfaces.AllAppsListener
import com.calleridapp.numberlookup.launcher.models.AppLauncher

class LaunchersAdapter(
    val activity: SimpleActivity,
    val allAppsListener: AllAppsListener,
    val itemClick: (Any) -> Unit
) : ListAdapter<AppLauncher, RecyclerView.ViewHolder>(AppLauncherDiffCallback()),
    RecyclerViewFastScroller.OnPopupTextUpdate {

    // the drawer is translucent black over the wallpaper, labels are always white on it
    private var textColor = Color.WHITE
    private var iconPadding = 0

    /**
     * The ad frame, carried as row 0 so it scrolls away with the apps instead of holding a
     * strip of the drawer permanently. It is one long-lived view owned by the fragment — the
     * holder re-parents it on bind rather than re-rendering it, so scrolling the header out of
     * view and back does not re-show (and re-count) the ad.
     */
    private var adHeader: View? = null

    /** Row 0 is the ad; every launcher position is shifted by this. */
    private val headerCount: Int get() = if (adHeader != null) 1 else 0

    init {
        setHasStableIds(true)
        calculateIconWidth()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setAdHeader(view: View?) {
        if (adHeader === view) {
            return
        }

        adHeader = view
        notifyDataSetChanged()
    }

    /** True when [position] is the ad row rather than an app. */
    fun isAdHeader(position: Int): Boolean = headerCount == 1 && position == 0

    override fun getItemCount(): Int = super.getItemCount() + headerCount

    override fun getItemViewType(position: Int): Int =
        if (isAdHeader(position)) VIEW_TYPE_AD else VIEW_TYPE_LAUNCHER

    override fun getItemId(position: Int): Long {
        if (isAdHeader(position)) {
            return AD_HEADER_ID
        }

        return getItem(position - headerCount).getLauncherIdentifier().hashCode().toLong()
    }

    fun launchFirstApp(): Boolean {
        val launcher = currentList.firstOrNull() ?: return false
        itemClick(launcher)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_TYPE_AD) {
            val host = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            return AdViewHolder(host)
        }

        val binding = ItemLauncherLabelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AdViewHolder -> holder.attach(adHeader)
            is ViewHolder -> holder.bindView(getItem(position - headerCount))
        }
    }

    override fun submitList(list: MutableList<AppLauncher>?) {
        calculateIconWidth()
        super.submitList(list)
    }

    private fun calculateIconWidth() {
        val currentColumnCount = activity.config.drawerColumnCount
        val iconWidth = activity.realScreenSize.x / currentColumnCount
        iconPadding = (iconWidth * 0.1f).toInt()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateTextColor(newTextColor: Int) {
        if (newTextColor != textColor) {
            textColor = newTextColor
            notifyDataSetChanged()
        }
    }

    /**
     * Holds the shared ad frame. Binding moves the one instance in, detaching it from the
     * holder it was last in — recycling must not leave it parented to a dead row.
     */
    class AdViewHolder(private val host: FrameLayout) : RecyclerView.ViewHolder(host) {
        fun attach(adView: View?) {
            if (adView == null || adView.parent === host) {
                return
            }

            (adView.parent as? ViewGroup)?.removeView(adView)
            host.removeAllViews()
            host.addView(
                adView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        @SuppressLint("ClickableViewAccessibility")
        fun bindView(launcher: AppLauncher): View {
            val binding = ItemLauncherLabelBinding.bind(itemView)
            itemView.apply {
                binding.launcherLabel.text = launcher.title
                binding.launcherLabel.setTextColor(textColor)
                binding.launcherLabel.beVisibleIf(activity.config.showDrawerAppLabels)
                binding.launcherIcon.setPadding(iconPadding, iconPadding, iconPadding, 0)

                if (launcher.drawable != null && binding.launcherIcon.tag == true) {
                    binding.launcherIcon.setImageDrawable(launcher.drawable)
                } else {
                    val placeholderDrawable = activity.resources.getColoredDrawableWithColor(
                        drawableId = R.drawable.placeholder_drawable,
                        color = launcher.thumbnailColor
                    )
                    Glide.with(activity)
                        .load(launcher.drawable)
                        .placeholder(placeholderDrawable)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .into(object : DrawableImageViewTarget(binding.launcherIcon) {
                            override fun onResourceReady(
                                resource: Drawable,
                                transition: Transition<in Drawable>?
                            ) {
                                super.onResourceReady(resource, transition)
                                view.tag = true
                            }
                        })
                }

                setOnClickListener { itemClick(launcher) }
                setOnLongClickListener {
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
                            binding.launcherIcon.drawable.alpha = LAUNCHER_ALPHA_PRESSED
                            animateScale(
                                from = LAUNCHER_SCALE_NORMAL,
                                to = LAUNCHER_SCALE_PRESSED,
                                duration = LAUNCHER_SCALE_UP_DURATION
                            )
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            binding.launcherIcon.drawable.alpha = LAUNCHER_ALPHA_NORMAL
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

    override fun onChange(position: Int) =
        currentList.getOrNull(position - headerCount)?.getBubbleText() ?: ""

    companion object {
        const val VIEW_TYPE_LAUNCHER = 0
        const val VIEW_TYPE_AD = 1

        /** Stable ids are on, so the ad row needs one of its own that no launcher can collide with. */
        private const val AD_HEADER_ID = Long.MIN_VALUE

        private const val LAUNCHER_SCALE_NORMAL = 1f
        private const val LAUNCHER_SCALE_PRESSED = 1.15f
        private const val LAUNCHER_SCALE_UP_DURATION = 100L
        private const val LAUNCHER_SCALE_DOWN_DURATION = 50L
        private const val LAUNCHER_ALPHA_NORMAL = 255
        private const val LAUNCHER_ALPHA_PRESSED = 220
    }
}

class AppLauncherDiffCallback : DiffUtil.ItemCallback<AppLauncher>() {
    override fun areItemsTheSame(oldItem: AppLauncher, newItem: AppLauncher): Boolean {
        return oldItem.getLauncherIdentifier().hashCode().toLong() ==
                newItem.getLauncherIdentifier().hashCode().toLong()
    }

    override fun areContentsTheSame(oldItem: AppLauncher, newItem: AppLauncher): Boolean {
        return oldItem.title == newItem.title &&
                oldItem.order == newItem.order &&
                oldItem.thumbnailColor == newItem.thumbnailColor &&
                oldItem.drawable != null &&
                newItem.drawable != null
    }
}
