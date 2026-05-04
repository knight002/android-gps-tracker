package com.example.gpstracker.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    private const val RELEASES_URL = "https://api.github.com/repos/knight002/android-gps-tracker/releases/latest"
    private val APK_ASSET_NAME = Regex("GPSTracker\\.apk$")

    data class UpdateInfo(
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    suspend fun checkForUpdates(): UpdateInfo? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(RELEASES_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "GPSTracker-App")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode != 200) {
                println("Updater: API response code: ${connection.responseCode}")
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.getString("tag_name")
            val releaseNotes = json.optString("body", "No release notes.")
            val assets = json.getJSONArray("assets")

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (APK_ASSET_NAME.containsMatchIn(name)) {
                    val downloadUrl = asset.getString("url")
                    return UpdateInfo(tagName, downloadUrl, releaseNotes)
                }
            }
            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun downloadAndInstall(context: Activity, updateInfo: UpdateInfo): Boolean {
        var connection: HttpURLConnection? = null
        val apkFile = File(context.getExternalFilesDir(null), "update.apk")
        try {
            val url = URL(updateInfo.downloadUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", "GPSTracker-App")
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()

            if (connection.responseCode != 200) {
                Toast.makeText(context, "Download failed: ${connection.responseCode}", Toast.LENGTH_SHORT).show()
                return false
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(apkFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            installApk(context, apkFile)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            apkFile.delete()
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun installApk(context: Activity, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "APK file not found", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot install APK: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
