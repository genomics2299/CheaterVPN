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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var updateChecker: UpdateChecker
    private var currentUpdate: UpdateChecker.UpdateInfo? = null
    private val handler = Handler(Looper.getMainLooper())

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            showInstallOrError()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateChecker = UpdateChecker(this)

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
        binding.updateProgress.visibility = View.VISIBLE
        binding.updateProgressText.visibility = View.VISIBLE
        binding.updateProgress.progress = 0
        binding.updateProgressText.text = getString(R.string.update_downloading_version, update.versionName)

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { downloadApk(update) }
            }

            if (result.isSuccess) {
                showInstallOrError()
            } else {
                binding.updateProgress.visibility = View.GONE
                binding.updateProgressText.visibility = View.GONE
                Toast.makeText(this@SettingsActivity, getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadApk(update: UpdateChecker.UpdateInfo) {
        val dir = File(cacheDir, UpdateChecker.DOWNLOAD_DIR)
        dir.mkdirs()
        val file = File(dir, "CheaterVPN-${update.versionName}.apk")

        val conn = URL(update.downloadUrl).openConnection()
        conn.connect()
        val total = conn.contentLength

        conn.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var downloaded = 0
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total > 0) {
                        val percent = downloaded * 100 / total
                        val kb = downloaded / 1024
                        val totalKb = total / 1024
                        runOnUiThread {
                            binding.updateProgress.progress = percent
                            binding.updateProgressText.text = getString(R.string.update_progress, percent, kb, totalKb)
                        }
                    }
                }
            }
        }
    }

    private fun showInstallOrError() {
        val update = currentUpdate ?: return
        val fileName = "CheaterVPN-${update.versionName}.apk"
        val file = File(cacheDir, "${UpdateChecker.DOWNLOAD_DIR}/$fileName")

        if (file.exists() && file.length() > 0) {
            binding.updateProgressText.text = getString(R.string.update_ready)
            updateChecker.installApk(file)
        } else {
            binding.updateProgress.visibility = View.GONE
            binding.updateProgressText.visibility = View.GONE
            Toast.makeText(this, getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
        }
    }
}
