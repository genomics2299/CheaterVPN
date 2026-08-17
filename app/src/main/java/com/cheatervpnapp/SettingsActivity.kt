package com.cheatervpnapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cheatervpnapp.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var killSwitchStore: KillSwitchStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        killSwitchStore = KillSwitchStore(this)

        binding.btnBack.setOnClickListener { finish() }

        binding.swKillSwitch.isChecked = killSwitchStore.isEnabled()
        binding.swKillSwitch.setOnCheckedChangeListener { _, isChecked ->
            killSwitchStore.setEnabled(isChecked)
        }

        binding.btnKillSwitchInfo.setOnClickListener {
            startActivity(Intent(this, KillSwitchInfoActivity::class.java))
        }

        binding.btnSplitTunnel.setOnClickListener {
            startActivity(Intent(this, SplitTunnelActivity::class.java))
        }

        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        binding.btnContact.setOnClickListener {
            startActivity(Intent(this, ContactActivity::class.java))
        }
    }
}
