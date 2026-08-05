package com.vpnapp

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.vpnapp.databinding.ActivitySplitTunnelBinding

class SplitTunnelActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplitTunnelBinding
    private lateinit var store: SplitTunnelStore
    private lateinit var adapter: AppAdapter
    private var apps = emptyList<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplitTunnelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = SplitTunnelStore(this)
        apps = loadApps()

        adapter = AppAdapter(
            initialSelection = store.apps(),
            onToggle = { pkg, checked ->
                val newApps = store.apps().toMutableSet()
                if (checked) newApps.add(pkg) else newApps.remove(pkg)
                store.setApps(newApps)
            },
        )
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
        adapter.submitList(apps)

        binding.btnBackToMain.setOnClickListener { finish() }

        if (store.mode() == SplitTunnelStore.Mode.INCLUDE) {
            binding.radioInclude.isChecked = true
        } else {
            binding.radioExclude.isChecked = true
        }
        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            store.setMode(
                if (checkedId == binding.radioInclude.id) SplitTunnelStore.Mode.INCLUDE
                else SplitTunnelStore.Mode.EXCLUDE
            )
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadApps(): List<AppInfo> {
        val pm = packageManager
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            pm.getInstalledApplications(0)
        }
        return infos.asSequence()
            .mapNotNull { info ->
                val pkg = info.packageName ?: return@mapNotNull null
                val label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(pkg)
                AppInfo(pkg, label.ifBlank { pkg })
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
