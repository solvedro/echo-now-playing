package io.music_assistant.nowplaying.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.music_assistant.nowplaying.R
import io.music_assistant.nowplaying.model.AppItem

class AppDrawerAdapter(
    private val onItemClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppDrawerAdapter.AppViewHolder>() {

    private val allApps = mutableListOf<AppItem>()
    private val displayedApps = mutableListOf<AppItem>()

    fun setApps(apps: List<AppItem>) {
        allApps.clear()
        allApps.addAll(apps)
        filter("")
    }

    fun filter(query: String) {
        displayedApps.clear()
        if (query.isBlank()) {
            displayedApps.addAll(allApps)
        } else {
            val q = query.trim().lowercase()
            for (app in allApps) {
                if (app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q)) {
                    displayedApps.add(app)
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_drawer, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val item = displayedApps[position]
        holder.bind(item, onItemClick)
    }

    override fun getItemCount(): Int = displayedApps.size

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
        private val txtName: TextView = itemView.findViewById(R.id.txtAppName)

        fun bind(item: AppItem, onClick: (AppItem) -> Unit) {
            imgIcon.setImageDrawable(item.icon)
            txtName.text = item.label
            itemView.setOnClickListener {
                onClick(item)
            }
        }
    }
}
