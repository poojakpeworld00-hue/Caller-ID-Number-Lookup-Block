package com.calleridapp.numberlookup.ui.tools

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.databinding.ItemToolBinding
import com.calleridapp.numberlookup.databinding.ItemToolHeaderBinding

/** One tool tile: a white glyph on a gradient tile, plus a launch target. */
data class UtilityUi(
    val name: String,
    val hint: String,
    val iconRes: Int,
    val tileRes: Int,
    val category: String,
    val target: Class<*>,
)

/** A row in the tools grid — a category header (full width) or a tool card. */
sealed interface UtilityRow {
    data class Header(val title: String) : UtilityRow
    data class Tool(val tool: UtilityUi) : UtilityRow
}

class UtilityAdapter(
    private val onClick: (UtilityUi) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<UtilityRow> = emptyList()
    private var lastAnimated = -1

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<UtilityRow>) {
        rows = list
        lastAnimated = -1   // re-cascade after a search filter (fade-through)
        notifyDataSetChanged()
    }

    fun isHeader(position: Int): Boolean = rows[position] is UtilityRow.Header

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is UtilityRow.Header) TYPE_HEADER else TYPE_TOOL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemToolHeaderBinding.inflate(inflater, parent, false))
        } else {
            ToolVH(ItemToolBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is UtilityRow.Header -> (holder as HeaderVH).binding.tvToolCategory.text = row.title
            is UtilityRow.Tool -> (holder as ToolVH).bind(row.tool)
        }
        animateIn(holder.itemView, position)
    }

    override fun getItemCount(): Int = rows.size

    /** Cards cascade in with a 40ms stagger + scale 0.94 → 1. */
    private fun animateIn(view: View, position: Int) {
        if (position <= lastAnimated) return
        lastAnimated = position
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(position * 40L)
            .setDuration(300L)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private class HeaderVH(val binding: ItemToolHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    private inner class ToolVH(val binding: ItemToolBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(tool: UtilityUi) {
            binding.ivToolIcon.setBackgroundResource(tool.tileRes)
            binding.ivToolIcon.setImageResource(tool.iconRes)
            binding.tvToolTitle.text = tool.name
            binding.tvToolSub.text = tool.hint
            binding.toolCard.setOnClickListener {
                springIcon(binding.ivToolIcon)
                onClick(tool)
            }
        }

        /** Tap feedback — icon tile springs 1 → 0.9 → 1. */
        private fun springIcon(view: View) {
            view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90L)
                .withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160L)
                        .setInterpolator(OvershootInterpolator(3f)).start()
                }.start()
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_TOOL = 1
    }
}
