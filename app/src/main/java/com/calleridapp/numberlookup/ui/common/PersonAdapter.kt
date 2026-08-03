package com.calleridapp.numberlookup.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.data.PersonItem
import com.calleridapp.numberlookup.databinding.ItemContactBinding

class PersonAdapter(
    private val items: List<PersonItem>
) : RecyclerView.Adapter<PersonAdapter.VH>() {

    inner class VH(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            tvAvatar.text = item.initials
            tvName.text = item.name
            tvNumber.text = item.detail
        }
    }

    override fun getItemCount(): Int = items.size
}
