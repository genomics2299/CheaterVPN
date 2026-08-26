package com.cheatervpnapp

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_REPO = "genomics2299/CheaterVPN"
        private const val RELEASES_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        private const val DOWNLOAD_DIR = "updates"
    }

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String,
    )

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.connect()

            if (conn.responseCode != 200) {
                Log.w(TAG, "GitHub API returned ${conn.responseCode}")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "")
            val releaseNotes = json.optString("body", "")
            val versionName = tagName.removePrefix("v").ifEmpty { return@withContext null }

            val assets = json.optJSONArray("assets") ?: return@withContext null
            val apkAsset = findApkAsset(assets) ?: return@withContext null

            val downloadUrl = apkAsset.getString("browser_download_url")
            val latestVersionCode = parseVersionCode(versionName)
            val currentVersionCode = getCurrentVersionCode()

            if (latestVersionCode <= currentVersionCode) {
                return@withContext null
            }

            UpdateInfo(
                versionName = versionName,
                versionCode = latestVersionCode,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            null
        }
    }

    private fun findApkAsset(assets: JSONArray): JSONObject? {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.endsWith(".apk")) return asset
        }
        return null
    }

    private fun parseVersionCode(versionName: String): Int {
        val parts = versionName.split(".")
        var code = 0
        for (part in parts) {
            code = code * 100 + (part.toIntOrNull() ?: 0)
        }
        return code
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            0
        }
    }

    fun downloadAndInstall(updateInfo: UpdateInfo, onProgress: ((Int) -> Unit)? = null): Long {
        val fileName = "CheaterVPN-${updateInfo.versionName}.apk"
        val dir = File(context.cacheDir, DOWNLOAD_DIR)
        dir.mkdirs()

        val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl))
            .setTitle(context.getString(R.string.update_downloading))
            .setDescription(context.getString(R.string.update_downloading_version, updateInfo.versionName))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "$DOWNLOAD_DIR/$fileName")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    fun getApkFile(updateInfo: UpdateInfo): File? {
        val fileName = "CheaterVPN-${updateInfo.versionName}.apk"
        val externalDir = context.getExternalFilesDir(null) ?: return null
        val file = File(externalDir, "$DOWNLOAD_DIR/$fileName")
        return if (file.exists() && file.length() > 0) file else null
    }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    fun cleanOldDownloads() {
        val externalDir = context.getExternalFilesDir(null) ?: return
        val dir = File(externalDir, DOWNLOAD_DIR)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
