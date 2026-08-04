package com.calleridapp.numberlookup.launcher.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.launcher.models.AppLauncher

/**
 * Minimal launcher list used by the left panel, one instance per section. The row layouts all
 * share the same two ids, so a single adapter covers grids and lists alike.
 */
class PanelAppsAdapter(
    private val layoutRes: Int,
    private val itemClick: (AppLauncher) -> Unit,
) : ListAdapter<AppLauncher, PanelAppsAdapter.ViewHolder>(AppLauncherDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bindView(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bindView(launcher: AppLauncher) {
            itemView.findViewById<TextView>(R.id.item_title).text = launcher.title
            // the icons are already loaded in memory by the drawer, no need for Glide here
            itemView.findViewById<ImageView>(R.id.item_icon).setImageDrawable(launcher.drawable)
            itemView.setOnClickListener { itemClick(launcher) }
        }
    }
}
