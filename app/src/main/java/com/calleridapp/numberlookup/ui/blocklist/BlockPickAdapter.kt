package com.calleridapp.numberlookup.ui.blocklist

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.data.CallEntry
import com.calleridapp.numberlookup.databinding.ItemBlockPickBinding

/** Lists recent call-log numbers so the user can tap one to block it. */
class BlockPickAdapter(
    private val onPick: (CallEntry) -> Unit
) : RecyclerView.Adapter<BlockPickAdapter.VH>() {

    private var items: List<CallEntry> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CallEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBlockPickBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemBlockPickBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: CallEntry) {
            val name = entry.name?.takeIf { it.isNotBlank() }
            binding.tvName.text = name ?: entry.number
            // Hide the secondary line when there's no name to avoid showing the number twice.
            binding.tvNumber.text = entry.number
            binding.tvNumber.visibility =
                if (name == null) android.view.View.GONE else android.view.View.VISIBLE
            binding.root.setOnClickListener { onPick(entry) }
        }
    }
}
