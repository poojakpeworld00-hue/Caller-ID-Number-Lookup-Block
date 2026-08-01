package identifycaller.phonelookup.contacts.ap_ad_module.presentation.my_main_counter.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import identifycaller.phonelookup.contacts.ap_ad_module.data.Reminder
import identifycaller.phonelookup.contacts.calllog.R
import identifycaller.phonelookup.contacts.calllog.util.triggerClick
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReminderAdapter(
    private val list: List<Reminder>,
    private val onDelete: (Reminder) -> Unit
) :
    RecyclerView.Adapter<ReminderAdapter.ReminderViewHolder>() {

    class ReminderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tvTitle)
        val time: TextView = itemView.findViewById(R.id.tvTime)
        val delete: ImageView = itemView.findViewById(R.id.ivDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReminderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reminder1, parent, false)
        return ReminderViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReminderViewHolder, position: Int) {
        val reminder = list[position]
        holder.title.text = reminder.title
        val format = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        holder.time.text = format.format(Date(reminder.dateTime))
        holder.delete.triggerClick {
            onDelete(reminder)
        }
    }

    override fun getItemCount() = list.size
}
