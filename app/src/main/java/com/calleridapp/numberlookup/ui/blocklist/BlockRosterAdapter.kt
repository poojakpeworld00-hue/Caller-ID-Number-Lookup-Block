package com.calleridapp.numberlookup.ui.blocklist

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.BarredEntry
import com.calleridapp.numberlookup.databinding.ItemBlocklistBinding

/**
 * Renders the blocklist as a flat list of blocked numbers. Each row carries a
 * red-tinted "spam" or a neutral treatment, an avatar, the number and a direct
 * Unblock pill. Tapping the row opens details; the pill unblocks in one step.
 */
class BlockRosterAdapter(
    private val onUnblock: (BarredEntry) -> Unit,
    private val onRowClick: (BarredEntry) -> Unit,
) : RecyclerView.Adapter<BlockRosterAdapter.VH>() {

    private var rows: List<BarredRowUi> = emptyList()
    private var lastAnimated = -1

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<BarredRowUi>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBlocklistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(rows[position])
        animateIn(holder.itemView, position)
    }

    override fun getItemCount(): Int = rows.size

    /** Staggered spring-in the first time each row scrolls into view (40ms cascade). */
    private fun animateIn(view: View, position: Int) {
        if (position <= lastAnimated) return
        lastAnimated = position
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.translationY = view.resources.displayMetrics.density * 8f
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(position * 40L)
            .setDuration(340L)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    inner class VH(val binding: ItemBlocklistBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BarredRowUi) {
            val ctx = binding.root.context
            binding.tvLabel.text = row.label
            binding.tvNumber.text = row.entry.number

            if (row.isSpam) {
                binding.blockRow.setBackgroundResource(R.drawable.bg_row_spam)
                binding.avatarBox.setBackgroundResource(R.drawable.bg_avatar_spam)
                binding.tvBang.visibility = View.VISIBLE
                binding.ivAvatar.visibility = View.GONE
                binding.tvLabel.setTextColor(ContextCompat.getColor(ctx, R.color.spam_on))
                binding.tvNumber.setTextColor(ContextCompat.getColor(ctx, R.color.spam_on))
                binding.btnUnblock.setBackgroundResource(R.drawable.bg_unblock_spam)
                binding.btnUnblock.setTextColor(ContextCompat.getColor(ctx, R.color.spam_on))
            } else {
                binding.blockRow.setBackgroundResource(R.drawable.bg_row_neutral)
                binding.avatarBox.setBackgroundResource(R.drawable.bg_avatar_neutral)
                binding.tvBang.visibility = View.GONE
                binding.ivAvatar.visibility = View.VISIBLE
                binding.tvLabel.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                binding.tvNumber.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_variant))
                binding.btnUnblock.setBackgroundResource(R.drawable.bg_unblock_neutral)
                binding.btnUnblock.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
            }

            binding.btnUnblock.setOnClickListener { onUnblock(row.entry) }
            binding.blockRow.setOnClickListener { onRowClick(row.entry) }
        }
    }
}
