package com.cheatervpnapp

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cheatervpnapp.databinding.ActivityStatsBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private lateinit var awgManager: AwgManager
    private val adapter = SessionAdapter()
    private var lastConnected: Boolean? = null
    private var prevRx = 0L
    private var prevTx = 0L
    private var prevTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        awgManager = AwgManager.get(this)

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        binding.btnStatsBack.setOnClickListener {
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        binding.btnClearHistory.setOnClickListener {
            val totals = StatsStore.totals(this)
            if (totals.totalSessions == 0L) {
                Toast.makeText(this, getString(R.string.stats_empty_history), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.stats_clear_confirm)
                .setMessage(R.string.stats_clear_confirm_msg)
                .setPositiveButton(R.string.stats_clear) { _, _ ->
                    StatsStore.clear(this)
                    reload()
                    Toast.makeText(this, getString(R.string.stats_cleared), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.stats_cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        lastConnected = null
        prevRx = 0L
        prevTx = 0L
        prevTime = 0L
        reload()
        lifecycleScope.launch {
            while (isActive) {
                val live = awgManager.liveStats()
                val connected = live != null
                if (connected != lastConnected) {
                    lastConnected = connected
                    prevRx = 0L
                    prevTx = 0L
                    prevTime = 0L
                    reload()
                }
                updateLive(live)
                delay(1000)
            }
        }
    }

    private fun updateLive(live: LiveStats?) {
        if (live != null) {
            binding.tvSessionStatus.text = getString(R.string.stats_connected, SessionTracker.currentServerLabel.ifEmpty { getString(R.string.stats_unknown_server) })
            binding.tvSessionStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.tvSessionDuration.text = Formatters.durationClock(live.elapsedSec)
            binding.tvSessionRx.text = Formatters.bytes(live.rxBytes)
            binding.tvSessionTx.text = Formatters.bytes(live.txBytes)

            val now = SystemClock.elapsedRealtime()
            if (prevTime != 0L) {
                val dtSec = (now - prevTime) / 1000.0
                if (dtSec > 0) {
                    binding.tvSpeedDown.text = Formatters.speed(((live.rxBytes - prevRx) / dtSec).toLong().coerceAtLeast(0L))
                    binding.tvSpeedUp.text = Formatters.speed(((live.txBytes - prevTx) / dtSec).toLong().coerceAtLeast(0L))
                }
            }
            prevRx = live.rxBytes
            prevTx = live.txBytes
            prevTime = now
        } else {
            binding.tvSessionStatus.text = getString(R.string.stats_not_connected)
            binding.tvSessionStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvSessionDuration.text = "00:00"
            binding.tvSessionRx.text = "0 B"
            binding.tvSessionTx.text = "0 B"
            binding.tvSpeedDown.text = getString(R.string.speed_zero)
            binding.tvSpeedUp.text = getString(R.string.speed_zero)
            prevRx = 0L
            prevTx = 0L
            prevTime = 0L
        }
    }

    private fun reload() {
        val totals = StatsStore.totals(this)
        binding.tvTotalSessions.text = totals.totalSessions.toString()
        binding.tvTotalTime.text = Formatters.durationShort(totals.totalDurationSec)
        binding.tvTotalRx.text = Formatters.bytes(totals.totalRx)
        binding.tvTotalTx.text = Formatters.bytes(totals.totalTx)
        binding.ringTraffic.setData(
            totals.totalRx,
            totals.totalTx,
            Formatters.bytes(totals.totalRx + totals.totalTx),
            getString(R.string.lifetime_total_label),
        )

        val history = StatsStore.loadHistory(this)
        adapter.submitList(history)
        binding.tvEmptyHistory.visibility = if (history.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }
}
