package com.cheatervpnapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cheatervpnapp.databinding.ActivityKillSwitchInfoBinding

class KillSwitchInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKillSwitchInfoBinding
    private lateinit var killSwitchStore: KillSwitchStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKillSwitchInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        killSwitchStore = KillSwitchStore(this)

        binding.btnBack.setOnClickListener { finish() }

        binding.swKillSwitch.isChecked = killSwitchStore.isEnabled()
        binding.swKillSwitch.setOnCheckedChangeListener { _, isChecked ->
            killSwitchStore.setEnabled(isChecked)
        }
    }
}
