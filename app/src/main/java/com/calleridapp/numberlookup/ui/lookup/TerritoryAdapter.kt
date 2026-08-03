package com.calleridapp.numberlookup.ui.lookup

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.databinding.ItemCountryBinding

class TerritoryAdapter(
    private val onClick: (Territory) -> Unit
) : RecyclerView.Adapter<TerritoryAdapter.VH>() {

    private val items = mutableListOf<Territory>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Territory>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemCountryBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCountryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        with(holder.binding) {
            tvFlag.text = Territories.flag(c.iso2)
            tvName.text = c.name
            tvDial.text = "+${c.dial}"
        }
    }

    override fun getItemCount(): Int = items.size
}
