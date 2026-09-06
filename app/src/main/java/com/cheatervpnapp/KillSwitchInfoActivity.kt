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

        binding.btnBack.setOnClickListener {
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        binding.switchKillSwitch.isChecked = killSwitchStore.isEnabled()
        updateUI()
        binding.switchKillSwitch.setOnCheckedChangeListener { _, checked ->
            killSwitchStore.setEnabled(checked)
            updateUI()
        }
    }

    private fun updateUI() {
        val enabled = killSwitchStore.isEnabled()
        binding.tvKillSwitchStatus.text = if (enabled) {
            getString(R.string.kill_switch_enabled)
        } else {
            getString(R.string.kill_switch_disabled)
        }
        binding.tvKillSwitchStatus.setTextColor(
            if (enabled) getColor(R.color.success)
            else getColor(R.color.text_secondary)
        )
    }
}
