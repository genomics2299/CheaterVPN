package com.cheatervpnapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cheatervpnapp.databinding.ItemSessionBinding

class SessionAdapter : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

    private val sessions = mutableListOf<SessionRecord>()

    fun submitList(list: List<SessionRecord>) {
        sessions.clear()
        sessions.addAll(list)
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(record: SessionRecord) {
            binding.tvServerName.text = record.serverLabel.ifEmpty { "—" }
            binding.tvTime.text = Formatters.time(record.startTime)
            binding.tvDuration.text = Formatters.durationShort(record.durationSec)
            binding.tvTraffic.text =
                "↓ ${Formatters.bytes(record.rxBytes)}  ↑ ${Formatters.bytes(record.txBytes)}"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = sessions.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(sessions[position])
    }
}
