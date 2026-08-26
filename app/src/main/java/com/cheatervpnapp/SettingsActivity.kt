package com.cheatervpnapp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cheatervpnapp.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var updateChecker: UpdateChecker
    private var currentUpdate: UpdateChecker.UpdateInfo? = null
    private var downloadId: Long = -1L
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != downloadId) return
            stopProgress()

            val update = currentUpdate ?: return
            val fileName = "CheaterVPN-${update.versionName}.apk"
            val file = File(cacheDir, "${UpdateChecker.DOWNLOAD_DIR}/$fileName")

            if (file.exists() && file.length() > 0) {
                binding.updateProgressText.text = getString(R.string.update_ready)
                updateChecker.installApk(file)
            } else {
                binding.updateProgress.visibility = View.GONE
                binding.updateProgressText.visibility = View.GONE
                Toast.makeText(this@SettingsActivity, getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateChecker = UpdateChecker(this)

        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnKillSwitch.setOnClickListener {
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

        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
    }

    override fun onDestroy() {
        stopProgress()
        runCatching { unregisterReceiver(downloadReceiver) }
        super.onDestroy()
    }

    private fun checkForUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching { updateChecker.checkForUpdate() }
            binding.btnCheckUpdate.isEnabled = true

            if (result.isFailure) {
                Toast.makeText(this@SettingsActivity, getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val update = result.getOrNull()
            if (update == null) {
                Toast.makeText(this@SettingsActivity, getString(R.string.update_current), Toast.LENGTH_SHORT).show()
                return@launch
            }

            currentUpdate = update
            val notes = update.releaseNotes.ifBlank { null }
            val message = if (notes != null) {
                getString(R.string.update_available, update.versionName) + "\n\n" +
                    getString(R.string.update_notes) + "\n" + notes
            } else {
                getString(R.string.update_available, update.versionName)
            }

            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(getString(R.string.update_confirm))
                .setMessage(message)
                .setPositiveButton(getString(R.string.update_confirm)) { _, _ -> startDownload(update) }
                .setNegativeButton(getString(R.string.update_later), null)
                .show()
        }
    }

    private fun startDownload(update: UpdateChecker.UpdateInfo) {
        binding.updateProgress.visibility = View.VISIBLE
        binding.updateProgressText.visibility = View.VISIBLE
        binding.updateProgress.progress = 0
        binding.updateProgressText.text = getString(R.string.update_downloading_version, update.versionName)

        downloadId = updateChecker.downloadAndInstall(update)
        startProgressPolling()
    }

    private fun startProgressPolling() {
        progressRunnable = object : Runnable {
            override fun run() {
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            binding.updateProgress.progress = percent
                            val kb = downloaded / 1024
                            val totalKb = total / 1024
                            binding.updateProgressText.text = getString(R.string.update_progress, percent, kb, totalKb)
                        }
                    }
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgress() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }
}
