package com.calleridapp.numberlookup.ui.common

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.CallCardData
import com.calleridapp.numberlookup.data.CallKind
import com.calleridapp.numberlookup.databinding.ItemCallBinding

class CallRowAdapter(
    initial: List<CallCardData> = emptyList(),
    private val onCall: (String) -> Unit = {},
    private val onIdentify: (String) -> Unit = {}
) : RecyclerView.Adapter<CallRowAdapter.VH>() {

    private var items: List<CallCardData> = initial

    /** Rows animate in once; scrolling back or re-submitting must not replay the stagger. */
    private var lastAnimated = -1

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CallCardData>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemCallBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCallBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        val isSpam = item.type == CallKind.SPAM

        fun color(res: Int) = ContextCompat.getColor(ctx, res)
        fun tint(res: Int) = ColorStateList.valueOf(color(res))

        with(holder.binding) {
            tvAvatar.text = item.initials
            tvName.text = item.name
            tvSub.text = item.info

            // Verdict container: spam rows read red before the text does; everything
            // else sits on a neutral surface card with a primary-container avatar.
            rowCall.setBackgroundResource(
                if (isSpam) R.drawable.bg_home_tile_spam else R.drawable.bg_home_tile
            )
            tvAvatar.backgroundTintList =
                tint(if (isSpam) R.color.spam_avatar_bg else R.color.primary_container)
            tvAvatar.setTextColor(color(if (isSpam) R.color.spam_on else R.color.on_primary_container))
            tvName.setTextColor(color(if (isSpam) R.color.spam_on else R.color.on_surface))

            val (iconRes, subColorRes) = when (item.type) {
                CallKind.INCOMING -> R.drawable.ph_arrow_down_left to R.color.on_surface_variant
                CallKind.OUTGOING -> R.drawable.ph_arrow_up_right to R.color.on_surface_variant
                CallKind.MISSED -> R.drawable.ic_call_missed to R.color.danger
                CallKind.SPAM -> R.drawable.ph_shield_warning to R.color.spam_on
            }
            ivType.setImageResource(iconRes)
            ivType.imageTintList = tint(subColorRes)
            tvSub.setTextColor(color(subColorRes))

            // Spam → no action (auto-blocked); unknown number → Identify (opens Lookup);
            // otherwise the Call button.
            val unknown = !item.identified && item.number.isNotBlank()
            when {
                isSpam -> {
                    btnCall.visibility = android.view.View.GONE
                    btnIdentify.visibility = android.view.View.GONE
                }
                unknown -> {
                    btnCall.visibility = android.view.View.GONE
                    btnIdentify.visibility = android.view.View.VISIBLE
                    btnIdentify.setOnClickListener { onIdentify(item.number) }
                }
                else -> {
                    btnCall.visibility = android.view.View.VISIBLE
                    btnIdentify.visibility = android.view.View.GONE
                    btnCall.setOnClickListener { if (item.number.isNotBlank()) onCall(item.number) }
                }
            }
        }

        // Claude Design's cid-rise-in stagger, once per row.
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
}
