package identifycaller.phonelookup.contacts.calllog.ui.recents

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import identifycaller.phonelookup.contacts.calllog.R
import identifycaller.phonelookup.contacts.calllog.data.CallEntry
import identifycaller.phonelookup.contacts.calllog.data.CallType
import identifycaller.phonelookup.contacts.calllog.databinding.ItemCallBinding
import identifycaller.phonelookup.contacts.calllog.databinding.ItemSectionHeaderBinding
import identifycaller.phonelookup.contacts.calllog.ui.common.CallFormat

class RecentsAdapter(
    private val onCall: (String) -> Unit,
    private val onOpen: (CallEntry) -> Unit,
    private val onIdentify: (String) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<RecentRow> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<RecentRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is RecentRow.Header) TYPE_HEADER else TYPE_CALL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            CallVH(ItemCallBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is RecentRow.Header -> (holder as HeaderVH).binding.tvHeader.setText(row.titleRes)
            is RecentRow.Call -> (holder as CallVH).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderVH(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    inner class CallVH(val binding: ItemCallBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: RecentRow.Call) {
            val e = row.entry
            val ctx = binding.root.context
            val isSpam = e.type == CallType.SPAM

            fun color(res: Int) = ContextCompat.getColor(ctx, res)
            fun tint(res: Int) = ColorStateList.valueOf(color(res))

            binding.tvAvatar.text = CallFormat.initials(e.name, e.number)
            binding.tvName.text = CallFormat.displayName(e.name, e.number)

            val type = ctx.getString(CallFormat.typeLabelRes(e.type))
            val time = CallFormat.timeLabel(e.date)
            val duration = CallFormat.durationLabel(e.durationSec)
            binding.tvSub.text = buildString {
                append(type).append(" · ").append(time)
                if (duration.isNotEmpty()) append(" · ").append(duration)
            }

            // Verdict-tinted row + avatar (matches the Home list): spam reads red,
            // everything else sits on a neutral card with a primary-container avatar.
            binding.rowCall.setBackgroundResource(
                if (isSpam) R.drawable.bg_home_tile_spam else R.drawable.bg_home_tile
            )
            binding.tvAvatar.backgroundTintList =
                tint(if (isSpam) R.color.spam_avatar_bg else R.color.primary_container)
            binding.tvAvatar.setTextColor(
                color(if (isSpam) R.color.spam_on else R.color.on_primary_container)
            )
            binding.tvName.setTextColor(color(if (isSpam) R.color.spam_on else R.color.on_surface))

            // Icon + subtitle colour by verdict/type.
            val subColorRes = when (e.type) {
                CallType.MISSED -> R.color.danger
                CallType.SPAM -> R.color.spam_on
                else -> R.color.on_surface_variant
            }
            binding.ivType.setImageResource(CallFormat.typeIconRes(e.type))
            binding.ivType.imageTintList = tint(subColorRes)
            binding.tvSub.setTextColor(color(subColorRes))

            // Spam → no action; unknown/unsaved → Identify (opens Lookup); else Call.
            val unknown = e.name.isNullOrBlank() && e.number.isNotBlank()
            when {
                isSpam -> {
                    binding.btnCall.visibility = View.GONE
                    binding.btnIdentify.visibility = View.GONE
                }
                unknown -> {
                    binding.btnCall.visibility = View.GONE
                    binding.btnIdentify.visibility = View.VISIBLE
                    binding.btnIdentify.setOnClickListener { onIdentify(e.number) }
                }
                else -> {
                    binding.btnCall.visibility = View.VISIBLE
                    binding.btnIdentify.visibility = View.GONE
                    binding.btnCall.setOnClickListener { onCall(e.number) }
                }
            }
            binding.root.setOnClickListener { onOpen(e) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CALL = 1
    }
}
