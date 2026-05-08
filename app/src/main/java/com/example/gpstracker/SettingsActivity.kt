package com.example.gpstracker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gpstracker.databinding.ActivitySettingsBinding
import com.example.gpstracker.utils.SessionExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                binding.exportButton.isEnabled = false
                val result = SessionExporter.export(this@SettingsActivity, uri)
                withContext(Dispatchers.Main) {
                    binding.exportButton.isEnabled = true
                    if (result.isSuccess) {
                        Toast.makeText(this@SettingsActivity, "Sessions exported", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@SettingsActivity, "Export failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                binding.importButton.isEnabled = false
                val result = SessionExporter.import(this@SettingsActivity, uri)
                withContext(Dispatchers.Main) {
                    binding.importButton.isEnabled = true
                    if (result.isSuccess) {
                        val count = result.getOrThrow()
                        Toast.makeText(this@SettingsActivity, "$count sessions imported", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@SettingsActivity, "Import failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        loadSettings()

        binding.saveButton.setOnClickListener {
            saveSettings()
        }

        binding.cancelButton.setOnClickListener {
            finish()
        }

        binding.exportButton.setOnClickListener {
            exportLauncher.launch("gps_tracker_export.json")
        }

        binding.importButton.setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(TrackingService.PREFS_NAME, MODE_PRIVATE)
        val threshold = prefs.getFloat(TrackingService.KEY_MOVEMENT_THRESHOLD, Config.DEFAULT_MOVEMENT_THRESHOLD_M.toFloat())
        binding.movementThresholdEdit.setText(threshold.toString())
        val dwellTime = prefs.getInt(TrackingService.KEY_DWELL_TIME, TrackingService.DEFAULT_DWELL_TIME_S)
        binding.dwellTimeEdit.setText(dwellTime.toString())
    }

    private fun saveSettings() {
        val thresholdStr = binding.movementThresholdEdit.text.toString()
        val dwellStr = binding.dwellTimeEdit.text.toString()

        val threshold = thresholdStr.toFloatOrNull()
        val dwellTime = dwellStr.toIntOrNull()

        if (threshold == null || threshold <= 0) {
            binding.movementThresholdEdit.error = "Enter a valid number >0"
            return
        }

        if (dwellTime == null || dwellTime <= 0) {
            binding.dwellTimeEdit.error = "Enter a valid number >0"
            return
        }

        val prefs = getSharedPreferences(TrackingService.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putFloat(TrackingService.KEY_MOVEMENT_THRESHOLD, threshold)
            .putInt(TrackingService.KEY_DWELL_TIME, dwellTime)
            .apply()

        finish()
    }
}
