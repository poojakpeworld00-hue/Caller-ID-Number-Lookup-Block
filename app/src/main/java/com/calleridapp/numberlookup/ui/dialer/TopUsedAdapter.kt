package com.calleridapp.numberlookup.ui.dialer

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.data.TopUsedDigit
import com.calleridapp.numberlookup.databinding.ItemFrequentBinding
import com.calleridapp.numberlookup.ui.common.CallPresenter

/** Favorite/most-used contacts shown as horizontal cards. Tapping a card fills the
 *  dialer; the green badge dials. */
class TopUsedAdapter(
    private val onClick: (String) -> Unit,
    private val onCall: (String) -> Unit
) : RecyclerView.Adapter<TopUsedAdapter.VH>() {

    private var items: List<TopUsedDigit> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<TopUsedDigit>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemFrequentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFrequentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val hasName = !item.name.isNullOrBlank()
        holder.binding.tvName.text = CallPresenter.displayName(item.name, item.number)
        holder.binding.tvAvatar.text = CallPresenter.initials(item.name, item.number)
        // Show the number only when the name is the headline (otherwise it'd duplicate).
        holder.binding.tvCount.text = item.number
        holder.binding.tvCount.visibility = if (hasName) View.VISIBLE else View.GONE
        holder.binding.root.setOnClickListener { onClick(item.number) }
        holder.binding.btnCall.setOnClickListener { onCall(item.number) }
    }

    override fun getItemCount(): Int = items.size
}
