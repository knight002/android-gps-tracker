package com.example.gpstracker.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    private const val RELEASES_URL = "https://github.com/knight002/android-gps-tracker/releases/latest"
    private const val DOWNLOAD_BASE = "https://github.com/knight002/android-gps-tracker/releases/download"
    private const val APK_FILENAME = "GPSTracker-signed.apk"

    data class UpdateInfo(
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    suspend fun checkForUpdates(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(RELEASES_URL)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "GPSTracker-App")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true

                println("Updater: Checking for updates at $RELEASES_URL")
                println("Updater: Response code: ${connection.responseCode}")

                if (connection.responseCode != 200) {
                    return@withContext null
                }

                val finalUrl = connection.url.toString()
                println("Updater: Final URL after redirect: $finalUrl")

                val tagName = finalUrl.substringAfter("/tag/")
                if (tagName.isBlank()) {
                    println("Updater: Could not extract tag from URL")
                    return@withContext null
                }

                println("Updater: Latest tag: $tagName")
                val versionName = tagName.removePrefix("v")

                val downloadUrl = "$DOWNLOAD_BASE/$tagName/$APK_FILENAME"
                println("Updater: Download URL: $downloadUrl")

                return@withContext UpdateInfo(versionName, downloadUrl, "")
            } catch (e: Exception) {
                println("Updater: Exception in checkForUpdates: ${e.message}")
                e.printStackTrace()
                return@withContext null
            } finally {
                connection?.disconnect()
            }
        }
    }

    suspend fun downloadAndInstall(context: Activity, updateInfo: UpdateInfo): Boolean {
        return withContext(Dispatchers.IO) {
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
                connection.instanceFollowRedirects = true
                connection.connect()

                if (connection.responseCode != 200) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Download failed: ${connection.responseCode}", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext false
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

                withContext(Dispatchers.Main) {
                    installApk(context, apkFile)
                }
                return@withContext true
            } catch (e: Exception) {
                println("Updater: Exception in downloadAndInstall: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
                apkFile.delete()
                return@withContext false
            } finally {
                connection?.disconnect()
            }
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
