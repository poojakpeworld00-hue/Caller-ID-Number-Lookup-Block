package io.launcher.home.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.commons.extensions.getProperTextColor
import io.launcher.home.R
import io.launcher.home.activities.LauncherBasePanel
import io.launcher.home.databinding.LnchItemWidgetListItemsHolderBinding
import io.launcher.home.databinding.LnchItemWidgetListSectionBinding
import io.launcher.home.databinding.LnchItemWidgetPreviewBinding
import io.launcher.home.helpers.WIDGET_LIST_ITEMS_HOLDER
import io.launcher.home.helpers.WIDGET_LIST_SECTION
import io.launcher.home.interfaces.WidgetsFragmentListener
import io.launcher.home.models.WidgetsListItem
import io.launcher.home.models.WidgetsListItemsHolder
import io.launcher.home.models.WidgetsListSection

class WidgetsLineup(
    val activity: LauncherBasePanel,
    var widgetListItems: ArrayList<WidgetsListItem>,
    val widgetsFragmentListener: WidgetsFragmentListener,
    val itemClick: () -> Unit
) : RecyclerView.Adapter<WidgetsLineup.ViewHolder>() {

    private var textColor = activity.getProperTextColor()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = when (viewType) {
            WIDGET_LIST_SECTION -> LnchItemWidgetListSectionBinding.inflate(inflater, parent, false)
            else -> LnchItemWidgetListItemsHolderBinding.inflate(inflater, parent, false)
        }

        return ViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val widgetListItem = widgetListItems[position]
        holder.bindView(widgetListItems[position]) { itemView, layoutPosition ->
            when (widgetListItem) {
                is WidgetsListSection -> setupListSection(itemView, widgetListItem)
                is WidgetsListItemsHolder -> setupListItemsHolder(itemView, widgetListItem)
            }
        }
    }

    override fun getItemCount() = widgetListItems.size

    override fun getItemViewType(position: Int) = when {
        widgetListItems[position] is WidgetsListSection -> WIDGET_LIST_SECTION
        else -> WIDGET_LIST_ITEMS_HOLDER
    }

    private fun setupListSection(view: View, section: WidgetsListSection) {
        LnchItemWidgetListSectionBinding.bind(view).apply {
            widgetAppTitleUi.text = section.appTitle
            widgetAppTitleUi.setTextColor(textColor)
            widgetAppIconUi.setImageDrawable(section.appIcon)
        }
    }

    private fun setupListItemsHolder(view: View, listItem: WidgetsListItemsHolder) {
        val binding = LnchItemWidgetListItemsHolderBinding.bind(view)
        binding.widgetListItemsHolderUi.removeAllViews()
        binding.widgetListItemsScrollViewUi.scrollX = 0
        listItem.widgets.forEachIndexed { index, widget ->
            val imageSize = activity.resources.getDimension(R.dimen.launcher_widget_preview_size).toInt()
            val widgetPreview = LnchItemWidgetPreviewBinding.inflate(LayoutInflater.from(activity))
            binding.widgetListItemsHolderUi.addView(widgetPreview.root)

            val endMargin = if (index == listItem.widgets.size - 1) {
                activity.resources.getDimension(org.fossify.commons.R.dimen.medium_margin).toInt()
            } else {
                0
            }

            widgetPreview.widgetTitleUi.apply {
                text = widget.widgetTitleUi
                setTextColor(textColor)
            }

            widgetPreview.widgetSizeUi.apply {
                text = if (widget.isShortcut) {
                    activity.getString(org.fossify.commons.R.string.shortcut)
                } else {
                    "${widget.widthCells} x ${widget.heightCells}"
                }
                setTextColor(textColor)
            }

            (widgetPreview.widgetImageUi.layoutParams as RelativeLayout.LayoutParams).apply {
                marginStart = activity.resources.getDimension(org.fossify.commons.R.dimen.activity_margin).toInt()
                marginEnd = endMargin
                width = imageSize
                height = imageSize
            }

            Glide.with(activity)
                .load(widget.widgetPreviewImage)
                .into(widgetPreview.widgetImageUi)

            widgetPreview.root.setOnClickListener { itemClick() }

            widgetPreview.root.setOnLongClickListener { view ->
                widgetsFragmentListener.onWidgetLongPressed(widget)
                true
            }
        }
    }

    fun updateItems(newItems: ArrayList<WidgetsListItem>) {
        val oldSum = widgetListItems.sumOf { it.getHashToCompare() }
        val newSum = newItems.sumOf { it.getHashToCompare() }
        if (oldSum != newSum) {
            widgetListItems = newItems
            notifyDataSetChanged()
        }
    }

    fun updateTextColor(newTextColor: Int) {
        if (newTextColor != textColor) {
            textColor = newTextColor
            notifyDataSetChanged()
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bindView(widgetListItem: WidgetsListItem, callback: (itemView: View, adapterPosition: Int) -> Unit) {
            itemView.apply {
                callback(this, adapterPosition)
            }
        }
    }
}
