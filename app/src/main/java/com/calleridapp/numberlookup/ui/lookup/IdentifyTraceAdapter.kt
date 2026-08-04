package com.calleridapp.numberlookup.ui.lookup

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.databinding.ItemSearchHistoryBinding
import com.calleridapp.numberlookup.ui.common.CallPresenter
import com.calleridapp.numberlookup.ui.common.HomeMotion

class IdentifyTraceAdapter(
    private val onClick: (TraceEntry) -> Unit,
    private val onCall: (TraceEntry) -> Unit,
    // Tapping a still-locked name asks the host to gate the reveal behind a
    // rewarded ad; the host calls [revealName] once the reward is earned.
    private val onRevealName: (TraceEntry) -> Unit = {}
) : RecyclerView.Adapter<IdentifyTraceAdapter.VH>() {

    private val items = mutableListOf<TraceEntry>()

    /** rawNumbers whose caller name has been unlocked (rewarded ad watched) this session. */
    private val revealed = mutableSetOf<String>()

    /** Rows animate in once; a reveal's partial rebind must not replay the stagger. */
    private var lastAnimated = -1

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<TraceEntry>) {
        items.clear()
        items.addAll(list)
        lastAnimated = -1
        notifyDataSetChanged()
    }

    /** Un-masks [rawNumber]'s name after its rewarded ad and refreshes that row. */
    fun revealName(rawNumber: String) {
        if (revealed.add(rawNumber)) {
            val i = items.indexOfFirst { it.rawNumber == rawNumber }
            if (i != RecyclerView.NO_POSITION) notifyItemChanged(i)
        }
    }

    /**
     * Re-hides every name (drops all reveals). Call when the page is entered again
     * so names must be re-earned with a fresh rewarded ad each visit.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun resetReveals() {
        if (revealed.isEmpty()) return
        revealed.clear()
        notifyDataSetChanged()
    }

    /** Whether this entry still hides its name behind a rewarded ad. */
    private fun isLocked(item: TraceEntry): Boolean =
        item.name != null && item.rawNumber !in revealed

    inner class VH(val binding: ItemSearchHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p])
            }
            binding.ivHistCall.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onCall(items[p])
            }
            // The name is revealed only via the explicit eye button (rewarded ad) —
            // never by tapping the row/name directly.
            binding.ivHistReveal.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onRevealName(items[p])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSearchHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            tvHistAvatar.text = CallPresenter.initials(item.name, item.rawNumber)
            val locked = isLocked(item)
            // Locked: blur the name and surface the eye button to unlock it.
            tvHistName.text = if (locked) blurName(item.name!!) else (item.name ?: item.number)
            ivHistReveal.visibility = if (locked) View.VISIBLE else View.GONE
            tvHistSub.text = item.subtitle ?: item.number
        }

        // Claude Design's cid-rise-in stagger, once per row per submit().
        if (position > lastAnimated) {
            lastAnimated = position
            HomeMotion.riseIn(holder.itemView, delay = position * HomeMotion.STAGGER_STEP)
        }
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        super.onViewDetachedFromWindow(holder)
        holder.itemView.animate().cancel()
    }

    override fun getItemCount(): Int = items.size

    /** First letter + dots (e.g. "John" → "J•••"), matching the lookup-card blur. */
    private fun blurName(name: String): String =
        if (name.isNotEmpty()) name[0] + "•".repeat(name.length - 1) else name
}
