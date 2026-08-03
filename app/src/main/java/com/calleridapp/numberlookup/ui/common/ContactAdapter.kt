package com.calleridapp.numberlookup.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.data.ContactItem
import com.calleridapp.numberlookup.databinding.ItemContactBinding

class ContactAdapter(
    private val items: List<ContactItem>
) : RecyclerView.Adapter<ContactAdapter.VH>() {

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
