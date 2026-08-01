package identifycaller.phonelookup.contacts.calllog.ui.common

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import identifycaller.phonelookup.contacts.calllog.R
import identifycaller.phonelookup.contacts.calllog.data.CallLogItem
import identifycaller.phonelookup.contacts.calllog.data.CallType
import identifycaller.phonelookup.contacts.calllog.databinding.ItemCallBinding

class CallAdapter(
    initial: List<CallLogItem> = emptyList(),
    private val onCall: (String) -> Unit = {},
    private val onIdentify: (String) -> Unit = {}
) : RecyclerView.Adapter<CallAdapter.VH>() {

    private var items: List<CallLogItem> = initial

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CallLogItem>) {
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
        val isSpam = item.type == CallType.SPAM

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
                CallType.INCOMING -> R.drawable.ic_call_received to R.color.on_surface_variant
                CallType.OUTGOING -> R.drawable.ic_call_made to R.color.on_surface_variant
                CallType.MISSED -> R.drawable.ic_call_missed to R.color.danger
                CallType.SPAM -> R.drawable.ic_warning to R.color.spam_on
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
    }

    override fun getItemCount(): Int = items.size
}
