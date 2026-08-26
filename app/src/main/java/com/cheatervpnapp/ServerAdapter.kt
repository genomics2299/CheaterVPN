package com.cheatervpnapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cheatervpnapp.databinding.ItemServerBinding

class ServerAdapter(
    private val onClick: (Server) -> Unit,
    private val onLongClick: (Server) -> Unit,
    private val onRename: (Server) -> Unit,
) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

    private val servers = mutableListOf<Server>()
    private val pings = mutableMapOf<String, Long?>()
    private var selectedId: String? = null

    fun submitList(list: List<Server>) {
        servers.clear()
        servers.addAll(list)
        pings.clear()
        notifyDataSetChanged()
    }

    fun setSelected(id: String?) {
        selectedId = id
        notifyDataSetChanged()
    }

    fun selectedServer(): Server? = servers.firstOrNull { it.id == selectedId }

    fun setPing(id: String, value: Long?) {
        pings[id] = value
        val index = servers.indexOfFirst { it.id == id }
        if (index != -1) notifyItemChanged(index)
    }

    inner class ViewHolder(val binding: ItemServerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(server: Server) {
            binding.tvFlag.text = server.flag()
            binding.tvCountry.text = server.country.ifEmpty { server.name }
            binding.tvHost.text = server.endpointLabel()

            val hasResult = pings.containsKey(server.id)
            val ping = pings[server.id]
            binding.tvPing.text = when {
                server.host.isEmpty() -> "—"
                !hasResult -> "…"
                ping == null -> "—"
                else -> "$ping ms"
            }

            val selected = server.id == selectedId
            binding.root.setBackgroundColor(if (selected) 0x220066FF.toInt() else 0x00000000)

            binding.root.setOnClickListener { onClick(server) }
            binding.root.setOnLongClickListener {
                onLongClick(server)
                true
            }
            binding.btnRename.setOnClickListener { onRename(server) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = servers.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(servers[position])
    }
}
