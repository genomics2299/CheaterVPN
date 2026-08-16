package com.cheatervpnapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cheatervpnapp.databinding.ItemAppBinding

data class AppInfo(
    val packageName: String,
    val label: String,
)

class AppAdapter(
    initialSelection: Set<String>,
    private val onToggle: (String, Boolean) -> Unit,
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private val apps = mutableListOf<AppInfo>()
    private val selection = initialSelection.toMutableSet()

    private var fullList = emptyList<AppInfo>()
    private var query = ""

    fun submitList(list: List<AppInfo>) {
        fullList = list
        apps.clear()
        apps.addAll(list)
        notifyDataSetChanged()
    }

    fun filter(q: String) {
        query = q.trim()
        val needle = query.lowercase()
        apps.clear()
        apps.addAll(if (needle.isEmpty()) fullList else fullList.filter {
            it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
        })
        notifyDataSetChanged()
    }

    fun setSelection(selection: Set<String>) {
        this.selection.clear()
        this.selection.addAll(selection)
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(app: AppInfo) {
            binding.tvAppLabel.text = app.label
            binding.tvAppPackage.text = app.packageName
            binding.switchApp.setOnCheckedChangeListener(null)
            binding.switchApp.isChecked = app.packageName in selection
            binding.switchApp.setOnCheckedChangeListener { _, checked ->
                if (checked) selection.add(app.packageName) else selection.remove(app.packageName)
                onToggle(app.packageName, checked)
            }
            binding.root.setOnClickListener {
                binding.switchApp.isChecked = !binding.switchApp.isChecked
            }
            val ctx = binding.root.context
            binding.imgAppIcon.setImageDrawable(null)
            runCatching { ctx.packageManager.getApplicationIcon(app.packageName) }
                .onSuccess { binding.imgAppIcon.setImageDrawable(it) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = apps.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(apps[position])
    }
}
