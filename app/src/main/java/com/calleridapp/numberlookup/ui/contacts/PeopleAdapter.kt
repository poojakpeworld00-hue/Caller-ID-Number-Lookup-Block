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
import com.calleridapp.numberlookup.data.PersonItem
import com.calleridapp.numberlookup.databinding.ItemContactBinding
import com.calleridapp.numberlookup.databinding.ItemSectionHeaderBinding
import com.calleridapp.numberlookup.ui.common.HomeMotion

class PeopleAdapter(
    private val onCall: (String) -> Unit,
    private val onOpen: (PersonItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<PersonRow> = emptyList()

    /** Rows animate in once; scrolling back or re-submitting must not replay the stagger. */
    private var lastAnimated = -1

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<PersonRow>) {
        rows = list
        lastAnimated = -1
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is PersonRow.Header) TYPE_HEADER else TYPE_CONTACT

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
            is PersonRow.Header -> (holder as HeaderVH).bind(row.letter)
            is PersonRow.Item -> (holder as ContactVH).bind(row)
        }

        // Claude Design's cid-rise-in stagger, once per row per submit().
        if (position > lastAnimated) {
            lastAnimated = position
            HomeMotion.riseIn(holder.itemView, delay = position * HomeMotion.STAGGER_STEP)
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.itemView.animate().cancel()
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
        fun bind(row: PersonRow.Item) {
            val c = row.contact
            binding.tvAvatar.text = c.initials
            binding.tvName.text = c.name
            binding.tvNumber.text = c.detail
            loadContactPhoto(c)
            binding.btnCall.setOnClickListener { onCall(c.detail) }
            binding.root.setOnClickListener { onOpen(c) }
        }

        /** Shows the real contact photo over the initials, falling back to initials. */
        private fun loadContactPhoto(c: PersonItem) {
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
