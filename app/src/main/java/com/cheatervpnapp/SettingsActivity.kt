package com.cheatervpnapp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
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

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != downloadId) return

            val update = currentUpdate ?: return
            val fileName = "CheaterVPN-${update.versionName}.apk"
            val file = File(cacheDir, "${UpdateChecker.DOWNLOAD_DIR}/$fileName")

            if (file.exists() && file.length() > 0) {
                updateChecker.installApk(file)
            } else {
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
        Toast.makeText(this, getString(R.string.update_downloading), Toast.LENGTH_SHORT).show()
        downloadId = updateChecker.downloadAndInstall(update)
    }

}
