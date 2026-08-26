package com.cheatervpnapp

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val context: Context) {

    companion object {
        private const val GITHUB_REPO = "genomics2299/CheaterVPN"
        private const val RAW_VERSION_URL =
            "https://gist.githubusercontent.com/genomics2299/d1375f0d29a2c7f52f0002ef3ceaedc3/raw/version.json"
        private const val RELEASES_URL =
            "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        const val DOWNLOAD_DIR = "updates"
    }

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String,
    )

    private class UpdateException(message: String) : Exception(message)

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val currentCode = getCurrentVersionCode()

        try {
            checkRawVersion(currentCode)
        } catch (_: Exception) {
            try {
                checkGitHubRelease(currentCode)
            } catch (_: Exception) {
                throw UpdateException("Не удалось проверить обновления")
            }
        }
    }

    private fun checkRawVersion(currentCode: Int): UpdateInfo? {
        val urlWithCacheBust = "${RAW_VERSION_URL}?t=${System.currentTimeMillis()}"
        val conn = URL(urlWithCacheBust).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.connect()

        if (conn.responseCode != 200) {
            throw UpdateException("HTTP ${conn.responseCode}")
        }

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)

        val versionName = json.optString("version", "")
        val remoteCode = json.optInt("versionCode", 0)
        val downloadUrl = json.optString("downloadUrl", "")
        val notes = json.optString("notes", "")

        if (versionName.isEmpty() || downloadUrl.isEmpty()) {
            throw UpdateException("Invalid version.json")
        }

        if (remoteCode <= currentCode) return null

        return UpdateInfo(versionName, remoteCode, downloadUrl, notes)
    }

    private fun checkGitHubRelease(currentCode: Int): UpdateInfo? {
        val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "CheaterVPN/1.5")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.connect()

        if (conn.responseCode != 200) {
            throw UpdateException("GitHub API HTTP ${conn.responseCode}")
        }

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)

        val tagName = json.optString("tag_name", "")
        val releaseNotes = json.optString("body", "")
        val versionName = tagName.removePrefix("v").ifEmpty {
            throw UpdateException("Empty tag_name")
        }

        val assets = json.optJSONArray("assets")
        val apkAsset = findApkAsset(assets) ?: throw UpdateException("No APK asset found")
        val downloadUrl = apkAsset.getString("browser_download_url")

        val latestCode = parseVersionCode(versionName)
        if (latestCode <= currentCode) return null

        return UpdateInfo(versionName, latestCode, downloadUrl, releaseNotes)
    }

    private fun findApkAsset(assets: JSONArray?): JSONObject? {
        if (assets == null) return null
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
        val destFile = File(dir, fileName)

        val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl))
            .setTitle(context.getString(R.string.update_downloading))
            .setDescription(context.getString(R.string.update_downloading_version, updateInfo.versionName))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destFile))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    fun getApkFile(updateInfo: UpdateInfo): File? {
        val fileName = "CheaterVPN-${updateInfo.versionName}.apk"
        val file = File(context.cacheDir, "$DOWNLOAD_DIR/$fileName")
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
        val dir = File(context.cacheDir, DOWNLOAD_DIR)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
