package com.calleridapp.admesh.presentation.my_main_counter.adapter

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.CallRecord
import com.calleridapp.numberlookup.data.CallKind

/**
 * Recent-call list for the post-call screen. Each row shows the caller and a
 * call button; tapping the button (or the row) reports the number back via
 * [onCall] (blank numbers are ignored), which the host places as a direct call.
 */
class TimelineCallAdapter(
    private val onCall: (String) -> Unit
) : RecyclerView.Adapter<TimelineCallAdapter.VH>() {

    private var items: List<CallRecord> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CallRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_call, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivType: ImageView = itemView.findViewById(R.id.ivType)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvNumber: TextView = itemView.findViewById(R.id.tvNumber)
        private val btnCall: ImageView = itemView.findViewById(R.id.btnCall)

        fun bind(entry: CallRecord) {
            val name = entry.name?.takeIf { it.isNotBlank() }
            tvName.text = name ?: entry.number
            tvNumber.text = entry.number
            tvNumber.visibility = if (name == null) View.GONE else View.VISIBLE

            ivType.setImageResource(iconFor(entry.type))
            ivType.setColorFilter(colorFor(entry.type), PorterDuff.Mode.SRC_IN)

            val dial = { if (entry.number.isNotBlank()) onCall(entry.number) }
            btnCall.setOnClickListener { dial() }
            itemView.setOnClickListener { dial() }
        }

        private fun iconFor(type: CallKind): Int = when (type) {
            CallKind.INCOMING -> R.drawable.ic_call_received
            CallKind.OUTGOING -> R.drawable.ic_call_made
            CallKind.MISSED, CallKind.SPAM -> R.drawable.ic_call_missed
        }

        private fun colorFor(type: CallKind): Int = when (type) {
            CallKind.MISSED, CallKind.SPAM -> Color.parseColor("#D32F2F") // danger
            else -> Color.parseColor("#00796B")                           // primary
        }
    }
}
