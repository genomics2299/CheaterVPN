package com.cheatervpnapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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

            val ctx = binding.root.context
            val pingColor = when {
                server.host.isEmpty() || ping == null -> R.color.text_secondary
                ping < 100 -> R.color.success
                ping < 250 -> R.color.warning
                else -> R.color.error
            }
            binding.tvPing.setTextColor(ContextCompat.getColor(ctx, pingColor))

            val selected = server.id == selectedId
            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(ctx, if (selected) R.color.primary_container else R.color.surface)
            )
            binding.root.setStrokeColor(
                ContextCompat.getColor(ctx, if (selected) R.color.primary else R.color.outline_variant)
            )

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
