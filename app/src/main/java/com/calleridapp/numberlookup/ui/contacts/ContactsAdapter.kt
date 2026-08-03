package com.calleridapp.numberlookup.ui.contacts

import android.annotation.SuppressLint
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.ContactItem
import com.calleridapp.numberlookup.databinding.ItemContactBinding
import com.calleridapp.numberlookup.databinding.ItemSectionHeaderBinding

class ContactsAdapter(
    private val onCall: (String) -> Unit,
    private val onOpen: (ContactItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<ContactRow> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<ContactRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is ContactRow.Header) TYPE_HEADER else TYPE_CONTACT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            ContactVH(ItemContactBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ContactRow.Header -> (holder as HeaderVH).bind(row.letter)
            is ContactRow.Item -> (holder as ContactVH).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderVH(private val binding: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(letter: String) {
            binding.tvHeader.text = letter
            binding.tvHeader.setTextColor(
                ContextCompat.getColor(binding.root.context, R.color.primary)
            )
        }
    }

    inner class ContactVH(val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: ContactRow.Item) {
            val c = row.contact
            binding.tvAvatar.text = c.initials
            binding.tvName.text = c.name
            binding.tvNumber.text = c.detail
            loadContactPhoto(c)
            binding.btnCall.setOnClickListener { onCall(c.detail) }
            binding.root.setOnClickListener { onOpen(c) }
        }

        /** Shows the real contact photo over the initials, falling back to initials. */
        private fun loadContactPhoto(c: ContactItem) {
            val iv = binding.ivAvatar
            val uri = c.photoUri
            if (uri.isNullOrBlank()) {
                Glide.with(iv).clear(iv)
                iv.setImageDrawable(null)
                iv.visibility = View.GONE
                return
            }
            iv.visibility = View.VISIBLE
            Glide.with(iv)
                .load(Uri.parse(uri))
                .circleCrop()
                .into(iv)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CONTACT = 1
    }
}
