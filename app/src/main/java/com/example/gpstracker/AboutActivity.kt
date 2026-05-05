package com.example.gpstracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.gpstracker.databinding.ActivityAboutBinding
import com.example.gpstracker.utils.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private val scope = CoroutineScope(Dispatchers.Main)

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkForUpdates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "About"

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "Unknown"
        }

        binding.versionText.text = "Version: $versionName"

        binding.checkUpdatesButton.setOnClickListener {
            checkForUpdates()
        }
    }

    private fun checkForUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("This app needs permission to install updates. Please enable 'Install unknown apps' for GPS Tracker.")
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    intent.data = Uri.parse("package:$packageName")
                    installPermissionLauncher.launch(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        binding.checkUpdatesButton.isEnabled = false
        binding.checkUpdatesButton.text = "Checking..."

        scope.launch {
            val updateInfo = Updater.checkForUpdates()

            binding.checkUpdatesButton.isEnabled = true
            binding.checkUpdatesButton.text = "Check for Updates"

            if (updateInfo == null) {
                Toast.makeText(this@AboutActivity, "No updates found or failed to check", Toast.LENGTH_LONG).show()
                return@launch
            }

            val currentVersion = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) {
                "0"
            }

            if (updateInfo.versionName == currentVersion) {
                Toast.makeText(this@AboutActivity, "You are already on the latest version", Toast.LENGTH_LONG).show()
                return@launch
            }

            AlertDialog.Builder(this@AboutActivity)
                .setTitle("Update Available")
                .setMessage("Version ${updateInfo.versionName} is available (current: $currentVersion)")
                .setPositiveButton("Download") { _, _ ->
                    downloadUpdate(updateInfo)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun downloadUpdate(updateInfo: Updater.UpdateInfo) {
        binding.checkUpdatesButton.isEnabled = false
        binding.checkUpdatesButton.text = "Downloading..."

        scope.launch {
            val success = Updater.downloadAndInstall(this@AboutActivity, updateInfo)

            binding.checkUpdatesButton.isEnabled = true
            binding.checkUpdatesButton.text = "Check for Updates"

            if (success) {
                Toast.makeText(this@AboutActivity, "Installing update...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
