package com.cheatervpnapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cheatervpnapp.databinding.ActivityKillSwitchInfoBinding

class KillSwitchInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKillSwitchInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKillSwitchInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
    }
}
