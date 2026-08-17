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

        updateStatus()
        binding.swKillSwitch.isChecked = killSwitchStore.isEnabled()
        binding.swKillSwitch.setOnCheckedChangeListener { _, isChecked ->
            killSwitchStore.setEnabled(isChecked)
            updateStatus()
        }
    }

    private fun updateStatus() {
        val enabled = killSwitchStore.isEnabled()
        binding.tvKillSwitchStatus.text = if (enabled) {
            getString(R.string.kill_switch_status_on)
        } else {
            getString(R.string.kill_switch_status_off)
        }
        binding.tvKillSwitchStatus.setTextColor(
            if (enabled) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.darker_gray)
        )
    }
}
